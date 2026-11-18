package dev.swapnilraj.calendar.data.calendar

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.OperationApplicationException
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.Parcel
import android.provider.CalendarContract
import dev.swapnilraj.calendar.core.*
import java.security.MessageDigest
import java.time.Instant
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Local-provider RSVP only. First packet deliberately excludes recurrence and organizer writes. */
class CalendarMeetingWriter(
    private val resolver: ContentResolver,
    private val reader: CalendarReader,
    private val store: CalendarLocalStore,
    private val hasPermission: (String) -> Boolean,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val readBudgets: ProviderReadBudgets = ProviderReadBudgets(),
) : CalendarMeetings {
    private val mutex = Mutex()
    private class Denied(val reason: MutationDenial) : Exception()
    private class Review(val reason: String) : Exception()
    private class Conflict : Exception()
    private data class Source(val selected: EventRef, val calendar: Map<String, String?>,
        val event: Map<String, String?>, val attendees: List<Map<String, String?>>, val self: Map<String, String?>, val selfOnly: Boolean = false) {
        fun canonical(maxBytes: Int): String {
            val values = mapOf("feature" to Feature, "calendar" to calendar, "event" to event, "attendees" to attendees)
            checkCanonical(values, maxBytes, 1024)
            return JSONObject().put("feature", Feature).put("selected", refJson(selected))
                .put("calendar", rowJson(calendar)).put("event", rowJson(event))
                .put("attendees", JSONArray(attendees.map(::rowJson))).put("self", self.getValue("_id"))
                .apply { if (selfOnly) put("attendeeCoverage", "SELF") }.toString()
        }
    }

    override suspend fun loadForCreate(target: CalendarIdentity): MeetingReadResult<MeetingCreation> =
        MeetingReadResult.Denied(MutationDenial.UNSUPPORTED_EVENT)
    override suspend fun create(operationId: String, token: EditToken, draft: EventDraft,
        guests: List<MeetingGuestInput>): MeetingMutationResult = MeetingMutationResult.Unsupported("Meeting creation is not implemented by this executor")

    override suspend fun loadForEdit(selected: EventRef): MeetingReadResult<EditableMeeting> = withContext(io + readBudgetContext(readBudgets.mutation)) {
        try {
            ensureRead()
            val before = source(selected)
            val canonical = before.canonical(readBudgets.maxCanonicalBytes)
            val details = when (val result = reader.readDetails(selected)) {
                is CalendarReadState.Ready -> result.value
                CalendarReadState.PermissionRequired -> throw SecurityException()
                CalendarReadState.LimitExceeded -> throw ProviderReadLimitExceeded()
                else -> return@withContext MeetingReadResult.Unavailable
            }
            if (source(selected, identity(before.calendar), before.selfOnly).canonical(readBudgets.maxCanonicalBytes) != canonical) throw Conflict()
            val token = EditToken(editTokenFor(canonical))
            ensureRead()
            store.saveEdit(token.value, canonical)
            MeetingReadResult.Ready(EditableMeeting(details.copy(canEdit = AccessDecision.DENIED, canRespond = AccessDecision.ALLOWED), token,
                mapOf(RecurrenceScope.THIS to MeetingScopeAccess(respond = AccessDecision.ALLOWED))))
        } catch (_: ProviderReadLimitExceeded) { MeetingReadResult.LimitExceeded }
        catch (_: SecurityException) { MeetingReadResult.PermissionRequired }
        catch (denied: Denied) { MeetingReadResult.Denied(denied.reason) }
        catch (review: Review) { MeetingReadResult.NeedsReview(review.reason) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { MeetingReadResult.Unavailable }
    }

    override suspend fun mutate(operationId: String, selected: EventRef, token: EditToken,
        command: MeetingCommand): MeetingMutationResult = withContext(io + readBudgetContext(readBudgets.mutation)) { mutex.withLock {
        if (command !is MeetingCommand.Respond || command.scope != RecurrenceScope.THIS)
            return@withLock MeetingMutationResult.Unsupported("Only THIS response on a proven nonrecurring invitation is implemented")
        if (selected.recurrence != null) return@withLock MeetingMutationResult.Unsupported("Recurring responses require a separate verified executor")
        if (!permissions()) return@withLock common(MutationResult.PermissionRequired)
        var attempted = false
        try {
            if (operationId.isBlank()) return@withLock MeetingMutationResult.Invalid("Operation ID is required")
            val frozen = commandJson(selected, token, command.response)
            val signature = hash(CommandFeature + frozen.toString())
            val existing = store.operation(operationId)
            if (existing != null) {
                if (existing.kind != Kind || existing.signature != signature) return@withLock common(MutationResult.Conflict(null))
                if (existing.phase !in setOf("PREPARED", "FAILED")) return@withLock recover(existing, false)
                if (proof(existing) == null) return@withLock common(MutationResult.Uncertain(operationId, null))
            }
            val raw = store.edit(token.value) ?: throw Conflict()
            if (editTokenFor(raw) != token.value) throw Conflict()
            val original = decodeSource(raw)
            if (original.selected != selected) throw Conflict()
            val current = source(selected, identity(original.calendar), original.selfOnly)
            if (current.canonical(readBudgets.maxCanonicalBytes) != raw) throw Conflict()
            val status = responseStatus(command.response)
            val operations = assertions(current)
            // AOSP CalendarProvider2 77bed8f978646fc416a7d90f9e97485421961641:
            // verifyTransactionAllowed/updateEventRelatedTable reject item URI + selection.
            // The table URI retains every exact identity/status guard in a legal public update.
            operations += ContentProviderOperation.newUpdate(CalendarContract.Attendees.CONTENT_URI)
                .withSelection("_id = ? AND event_id = ? AND attendeeEmail = ? AND attendeeStatus = ?", arrayOf(current.self.getValue("_id")!!, selected.eventId.toString(),
                    current.self.getValue(CalendarContract.Attendees.ATTENDEE_EMAIL)!!, current.self.getValue(CalendarContract.Attendees.ATTENDEE_STATUS)!!))
                .withValue(CalendarContract.Attendees.ATTENDEE_STATUS, status).withExpectedCount(1).withYieldAllowed(false).build()
            val post = current.copy(event = current.event + (CalendarContract.Events.SELF_ATTENDEE_STATUS to status.toString()),
                attendees = current.attendees.map { if (it["_id"] == current.self["_id"]) it + (CalendarContract.Attendees.ATTENDEE_STATUS to status.toString()) else it })
            operations += assertRow(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, selected.eventId),
                post.event.filterKeys { it !in setOf(CalendarContract.Events.DIRTY, CalendarContract.Events.MUTATORS) })
            if (current.selfOnly) operations += assertOwnCandidate(current)
            post.attendees.forEach { operations += assertRow(ContentUris.withAppendedId(CalendarContract.Attendees.CONTENT_URI, it.long("_id")!!), it) }
            // Android CalendarProvider supports own RSVP through Attendees.STATUS. An ordinary
            // Events.SELF_ATTENDEE_STATUS update throws; a decline is not event cancellation.
            checkParcel(operations)
            val metadata = JSONObject().put("feature", CommandFeature).put("version", 1).put("kind", Kind).put("signature", signature)
                .put("command", frozen).put("identity", identityJson(identity(current.calendar))).put("self", current.self.getValue("_id"))
                .put("status", status).put("stage", "prepared")
            val encoded = metadata.toString()
            checkStoredJson(encoded, readBudgets.maxCanonicalBytes)
            ensureRead()
            if (!permissions()) return@withLock common(MutationResult.PermissionRequired)
            currentCoroutineContext().ensureActive()
            store.prepare(operationId, signature, Kind, encoded)
            val persisted = store.operation(operationId) ?: error("Journal unavailable")
            if (persisted.kind != Kind || persisted.signature != signature) return@withLock common(MutationResult.Conflict(null))
            if (proof(persisted)?.toString() != metadata.toString()) return@withLock common(MutationResult.Uncertain(operationId, null))
            if (!store.attempt(operationId)) return@withLock recover(store.operation(operationId)!!, false)
            attempted = true
            if (!permissions()) { store.mark(operationId, "FAILED"); return@withLock common(MutationResult.PermissionRequired) }
            resolver.applyBatch(CalendarContract.AUTHORITY, operations)
            metadata.put("stage", "committed")
            store.mark(operationId, "COMMITTED", metadata.toString())
            recover(store.operation(operationId)!!, false)
        } catch (_: ProviderReadLimitExceeded) { if (attempted) common(MutationResult.Uncertain(operationId, selected.eventId)) else MeetingMutationResult.NeedsReview("Complete source exceeds the read budget") }
        catch (_: SecurityException) { common(if (attempted) MutationResult.Uncertain(operationId, selected.eventId) else MutationResult.PermissionRequired) }
        catch (denied: Denied) { common(MutationResult.Denied(denied.reason)) }
        catch (review: Review) { MeetingMutationResult.NeedsReview(review.reason) }
        catch (_: Conflict) { common(MutationResult.Conflict(null)) }
        catch (_: OperationApplicationException) {
            try { store.mark(operationId, "FAILED"); common(MutationResult.Unavailable) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { common(MutationResult.Uncertain(operationId, selected.eventId)) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { common(if (attempted) MutationResult.Uncertain(operationId, selected.eventId) else MutationResult.Unavailable) }
    } }

    override suspend fun recover(operationId: String): MeetingMutationResult = withContext(io + readBudgetContext(readBudgets.mutation)) { mutex.withLock {
        try {
            ensureRead()
            store.operation(operationId)?.let { recover(it, true) } ?: common(MutationResult.Unavailable)
        } catch (_: SecurityException) { common(MutationResult.PermissionRequired) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { common(MutationResult.Uncertain(operationId, null)) }
    } }

    /** Parse only bounded private JSON and require the complete feature/token/command binding. */
    private fun proof(operation: CalendarLocalStore.Operation): JSONObject? = try {
        if (operation.kind != Kind) error("Other feature")
        val value = JSONObject(operation.resultRef ?: error("Missing receipt"))
        require(value.getInt("version") == 1 && value.getString("feature") == CommandFeature && value.getString("kind") == Kind)
        val command = value.getJSONObject("command")
        require(value.getString("signature") == operation.signature && hash(CommandFeature + command.toString()) == operation.signature)
        require(command.getString("scope") == RecurrenceScope.THIS.name)
        val selected = parseRef(command.getJSONObject("selected"))
        val token = command.getString("token")
        val raw = store.edit(token) ?: error("Missing snapshot")
        require(editTokenFor(raw) == token)
        val source = decodeSource(raw)
        require(source.selected == selected && identity(source.calendar) == parseIdentity(value.getJSONObject("identity")))
        require(source.self.getValue("_id") == value.getString("self"))
        require(value.getInt("status") == responseStatus(MeetingResponse.valueOf(command.getString("response"))))
        require(value.getString("stage") in setOf("prepared", "committed"))
        value
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }

    private suspend fun recover(operation: CalendarLocalStore.Operation, safeRetry: Boolean): MeetingMutationResult {
        val proof = proof(operation) ?: return common(MutationResult.Uncertain(operation.id, null))
        if (operation.phase in setOf("PREPARED", "FAILED")) return if (proof.getString("stage") == "prepared") {
            common(if (safeRetry) MutationResult.SafeToRetry(operation.id) else MutationResult.Unavailable)
        } else common(MutationResult.Uncertain(operation.id, null))
        if (operation.phase !in setOf("COMMITTED", "VERIFIED") || proof.getString("stage") != "committed")
            return common(MutationResult.Uncertain(operation.id, null))
        val command = proof.getJSONObject("command")
        val old = decodeSource(store.edit(command.getString("token")) ?: return common(MutationResult.Uncertain(operation.id, null)))
        val uncertain = common(MutationResult.Uncertain(operation.id, old.selected.eventId))
        val target = parseIdentity(proof.getJSONObject("identity"))
        val current = try { source(old.selected, target, old.selfOnly) }
            catch (_: Denied) { return uncertain }
            catch (_: Review) { return uncertain }
            catch (_: Conflict) { return uncertain }
        val status = proof.getInt("status").toString()
        val expectedGuests = old.attendees.map { row -> if (row["_id"] == proof.getString("self")) row + (CalendarContract.Attendees.ATTENDEE_STATUS to status) else row }
        if (current.attendees != expectedGuests || current.event[CalendarContract.Events.SELF_ATTENDEE_STATUS] != status) return uncertain
        // Provider dirty/mutator bookkeeping is allowed; every other event column is preserved.
        val bookkeeping = setOf(CalendarContract.Events.SELF_ATTENDEE_STATUS, CalendarContract.Events.DIRTY, CalendarContract.Events.MUTATORS)
        if (old.event.filterKeys { it !in bookkeeping } != current.event.filterKeys { it !in bookkeeping }) return uncertain
        if (identity(calendar(old.selected.calendarId)) != target) return uncertain
        ensureRead()
        store.mark(operation.id, "VERIFIED")
        reader.refresh()
        return MeetingMutationResult.LocalSaved(old.selected, RecurrenceScope.THIS)
    }

    private suspend fun source(selected: EventRef, expected: CalendarIdentity? = null, expectedSelfOnly: Boolean? = null): Source {
        ensureRead()
        if (selected.recurrence != null) throw Denied(MutationDenial.RECURRENCE)
        val calendar = calendar(selected.calendarId)
        if (expected != null && identity(calendar) != expected) throw Conflict()
        val access = calendar[CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL]?.toIntOrNull()
        if (access == null || access < CalendarContract.Calendars.CAL_ACCESS_RESPOND) throw Denied(MutationDenial.CALENDAR_READ_ONLY)
        val account = identity(calendar)
        val owner = account.owner
        if (owner.isNullOrBlank() || account.account.name.isBlank() || account.account.type.isBlank() || owner != account.account.name)
            throw Denied(MutationDenial.OWNERSHIP_UNKNOWN)
        val event = rows(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, selected.eventId), maxRows = 1).singleOrNull() ?: throw Conflict()
        if (event.long("_id") != selected.eventId || event.long("calendar_id") != selected.calendarId.value || event["deleted"] != "0") throw Conflict()
        if (listOf("rrule", "rdate", "exrule", "exdate", "original_id", "original_sync_id", "originalInstanceTime").any { !event[it].isNullOrBlank() })
            throw Denied(MutationDenial.RECURRENCE)
        if (event.long("dtstart") != selected.instanceStart.toEpochMilli() || event.long("dtend") != selected.instanceEnd.toEpochMilli()) throw Conflict()
        if (event[CalendarContract.Events.IS_ORGANIZER] != "0") throw Denied(MutationDenial.INVITATION)
        val organizer = event[CalendarContract.Events.ORGANIZER]
        if (organizer.isNullOrBlank() || organizer.equals(owner, ignoreCase = true)) throw Denied(MutationDenial.OWNERSHIP_UNKNOWN)
        if (event[CalendarContract.Events.STATUS]?.let { it !in setOf("0", "1") } == true ||
            !event[CalendarContract.Events.CUSTOM_APP_PACKAGE].isNullOrBlank() || !event[CalendarContract.Events.CUSTOM_APP_URI].isNullOrBlank())
            throw Denied(MutationDenial.UNSUPPORTED_EVENT)
        val fullGuestVisibility = event[CalendarContract.Events.GUESTS_CAN_SEE_GUESTS] == "1"
        val selfOnly = expectedSelfOnly ?: !fullGuestVisibility
        if (!selfOnly && !fullGuestVisibility) throw Review("The original complete guest source is no longer visible")
        val attendees = if (selfOnly) {
            // Only IDs and matching addresses are read until uniqueness/exact identity is proved.
            // No full-list fallback or other-guest payload is allowed on this path.
            val candidates = ownCandidates(selected.eventId, owner)
            if (candidates.size != 1 || candidates.single()[CalendarContract.Attendees.ATTENDEE_EMAIL] != owner ||
                candidates.single().long("event_id") != selected.eventId || candidates.single().long("_id") == null)
                throw Review("A unique exact calendar-owner attendee is required")
            val id = candidates.single().long("_id")!!
            rows(CalendarContract.Attendees.CONTENT_URI, "_id = ? AND event_id = ? AND attendeeEmail = ?",
                arrayOf(id.toString(), selected.eventId.toString(), owner), 1, attendeeFields)
        } else rows(CalendarContract.Attendees.CONTENT_URI, "event_id = ?", arrayOf(selected.eventId.toString()), readBudgets.maxDetailChildren, attendeeFields)
        if (attendees.any { it.long("event_id") != selected.eventId || it.long("_id") == null } || attendees.map { it["_id"] }.toSet().size != attendees.size) throw Review("Ambiguous attendee identities")
        val matching = attendees.filter { it[CalendarContract.Attendees.ATTENDEE_EMAIL]?.trim()?.equals(owner.trim(), ignoreCase = true) == true }
        if (matching.size != 1 || matching.single()[CalendarContract.Attendees.ATTENDEE_EMAIL] != owner) throw Review("A unique exact calendar-owner attendee is required")
        val self = matching.single()
        if (self[CalendarContract.Attendees.ATTENDEE_RELATIONSHIP] != CalendarContract.Attendees.RELATIONSHIP_ATTENDEE.toString() ||
            self[CalendarContract.Attendees.ATTENDEE_TYPE] !in setOf("0", "1", "2") ||
            self[CalendarContract.Attendees.ATTENDEE_STATUS] !in setOf("0", "1", "2", "3", "4") ||
            self[CalendarContract.Attendees.ATTENDEE_STATUS] != event[CalendarContract.Events.SELF_ATTENDEE_STATUS]) throw Review("Unknown or contradictory self-attendee policy")
        if (self[CalendarContract.Attendees.ATTENDEE_IDENTITY].isNullOrBlank() != self[CalendarContract.Attendees.ATTENDEE_ID_NAMESPACE].isNullOrBlank())
            throw Review("Incomplete attendee namespace")
        return Source(selected, calendar, event, attendees, self, selfOnly)
    }

    private suspend fun calendar(id: CalendarId) = rows(ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, id.value), maxRows = 1).singleOrNull()
        ?.also { if (it.long("_id") != id.value) throw Conflict() } ?: throw Conflict()
    private suspend fun rows(uri: Uri, selection: String? = null, args: Array<String>? = null, maxRows: Int,
        fields: Array<String>? = null): List<Map<String, String?>> {
        ensureRead()
        val cursor = suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            try {
                ensureRead()
                val value = (resolver.query(uri, fields, selection, args, "_id ASC", signal) ?: error("Provider unavailable")).bounded(maxRows)
                try { ensureRead() } catch (failure: Exception) { value.close(); throw failure }
                continuation.resume(value, onCancellation = { _, resource, _ -> resource.close() })
            } catch (failure: Exception) { continuation.resumeWithException(failure) }
        }
        return cursor.use { buildList { while (it.moveToNext()) {
            currentCoroutineContext().ensureActive()
            add(it.columnNames.associateWith { name -> it.getColumnIndexOrThrow(name).let { index -> if (it.isNull(index)) null else it.getString(index) } })
        } } }
    }
    private fun ensureRead() { if (!hasPermission(Manifest.permission.READ_CALENDAR)) throw SecurityException() }
    private fun permissions() = hasPermission(Manifest.permission.READ_CALENDAR) && hasPermission(Manifest.permission.WRITE_CALENDAR)
    private fun assertions(source: Source): ArrayList<ContentProviderOperation> = arrayListOf(
        assertRow(ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, source.selected.calendarId.value), source.calendar),
        assertRow(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, source.selected.eventId), source.event),
        if (source.selfOnly) assertOwnCandidate(source) else
            ContentProviderOperation.newAssertQuery(CalendarContract.Attendees.CONTENT_URI).withSelection("event_id = ?", arrayOf(source.selected.eventId.toString()))
                .withValue(CalendarContract.Attendees.EVENT_ID, source.selected.eventId).withExpectedCount(source.attendees.size).withYieldAllowed(false).build(),
    ).apply { source.attendees.forEach { add(assertRow(ContentUris.withAppendedId(CalendarContract.Attendees.CONTENT_URI, it.long("_id")!!), it)) } }

    private fun assertOwnCandidate(source: Source): ContentProviderOperation {
        val filter = ownFilter(source.selected.eventId, identity(source.calendar).owner ?: throw Review("Missing actor"))
        return ContentProviderOperation.newAssertQuery(CalendarContract.Attendees.CONTENT_URI).withSelection(filter.first, filter.second)
            .withValue(CalendarContract.Attendees._ID, source.self.long("_id")!!).withExpectedCount(1).withYieldAllowed(false).build()
    }
    private suspend fun ownCandidates(eventId: Long, owner: String): List<Map<String, String?>> {
        val filter = ownFilter(eventId, owner)
        return try { rows(CalendarContract.Attendees.CONTENT_URI, filter.first, filter.second, 2,
            arrayOf(CalendarContract.Attendees._ID, CalendarContract.Attendees.EVENT_ID, CalendarContract.Attendees.ATTENDEE_EMAIL)) }
        catch (denied: SecurityException) { throw denied }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (limit: ProviderReadLimitExceeded) { throw limit }
        catch (_: Exception) { throw Review("Provider cannot prove a unique self attendee without guest access") }
    }
    private fun assertRow(uri: Uri, values: Map<String, String?>) = ContentProviderOperation.newAssertQuery(uri)
        .withValues(ContentValues().apply { values.forEach { (key, value) -> if (value == null) putNull(key) else put(key, value) } })
        .withExpectedCount(1).withYieldAllowed(false).build()
    private fun checkParcel(operations: List<ContentProviderOperation>) {
        val parcel = Parcel.obtain()
        try { operations.forEach { it.writeToParcel(parcel, 0) }; if (parcel.dataSize() > 256 * 1024) throw Review("Complete asserted transaction exceeds the provider budget") }
        finally { parcel.recycle() }
    }
    private fun decodeSource(raw: String): Source {
        checkStoredJson(raw, readBudgets.maxCanonicalBytes)
        val value = JSONObject(raw)
        require(value.getString("feature") == Feature)
        val attendees = value.getJSONArray("attendees").let { list -> List(list.length()) { row(list.getJSONObject(it)) } }
        val self = attendees.single { it["_id"] == value.getString("self") }
        require(!value.has("attendeeCoverage") || value.getString("attendeeCoverage") == "SELF")
        val selfOnly = value.has("attendeeCoverage")
        require(!selfOnly || attendees.size == 1)
        return Source(parseRef(value.getJSONObject("selected")), row(value.getJSONObject("calendar")), row(value.getJSONObject("event")), attendees, self, selfOnly)
    }
    companion object {
        private val caseClasses = java.util.concurrent.ConcurrentHashMap<Char, String>()
        private val trimCharacters: String by lazy {
            buildString { for (code in 0..0xffff) if (code.toChar().isWhitespace()) append(code.toChar()) }
        }
        /** SQLite NOCASE omits Unicode simple-case matches of ASCII I/K/S. Explicit GLOB
         * classes match Kotlin's existing trim/ignoreCase duplicate policy without fetching
         * another guest's payload. Non-ASCII actors require a later provider proof. */
        private fun ownFilter(eventId: Long, owner: String): Pair<String, Array<String>> {
            if (owner.isEmpty() || owner.length > 254 || owner.any { it.code !in 0x21..0x7e })
                throw Review("A bounded ASCII account address is required for private self lookup")
            val pattern = buildString {
                owner.forEach { char ->
                    append(when {
                        char in 'A'..'Z' || char in 'a'..'z' -> caseClasses.computeIfAbsent(char.lowercaseChar()) { letter ->
                            buildString { append('['); for (code in 0..0xffff) if (code.toChar().equals(letter, ignoreCase = true)) append(code.toChar()); append(']') }
                        }
                        char == '*' -> "[*]"
                        char == '?' -> "[?]"
                        char == '[' -> "[[]"
                        char == ']' -> "[]]"
                        else -> char.toString()
                    })
                    if (length > 4096) throw Review("Self lookup pattern exceeds the provider budget")
                }
            }
            return "event_id = ? AND TRIM(attendeeEmail, ?) GLOB ?" to arrayOf(eventId.toString(), trimCharacters, pattern)
        }
        // AOSP sAttendeesProjectionMap also exposes joined Events columns. Projection=null
        // would capture derived event.selfAttendeeStatus on EVERY attendee, which RSVP changes.
        // Bind/assert documented attendee columns only. The status-only delta never copies or
        // overwrites unrepresented OEM columns; their values are not an observed-row proof.
        private val attendeeFields = arrayOf(CalendarContract.Attendees._ID, CalendarContract.Attendees.EVENT_ID,
            CalendarContract.Attendees.ATTENDEE_NAME, CalendarContract.Attendees.ATTENDEE_EMAIL,
            CalendarContract.Attendees.ATTENDEE_STATUS, CalendarContract.Attendees.ATTENDEE_RELATIONSHIP,
            CalendarContract.Attendees.ATTENDEE_TYPE, CalendarContract.Attendees.ATTENDEE_IDENTITY,
            CalendarContract.Attendees.ATTENDEE_ID_NAMESPACE)
        private const val Feature = "MEETING_EDIT_V1"
        private const val CommandFeature = "MEETING_COMMAND_V1"
        private const val Kind = "MEETING_V1_THIS_RESPOND"
        private fun common(result: MutationResult) = MeetingMutationResult.Common(result)
        private fun editTokenFor(raw: String) = "meeting-edit-v1:" + hash(Feature + raw)
        private fun hash(raw: String) = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        private fun rowJson(values: Map<String, String?>) = JSONObject().apply { values.toSortedMap().forEach { (key, value) -> put(key, value ?: JSONObject.NULL) } }
        private fun row(value: JSONObject) = value.keys().asSequence().associateWith { if (value.isNull(it)) null else value.getString(it) }
        private fun Map<String, String?>.long(key: String) = this[key]?.toLongOrNull()
        private fun identity(values: Map<String, String?>) = CalendarIdentity(CalendarId(requireNotNull(values.long("_id"))),
            CalendarAccount(values[CalendarContract.Calendars.ACCOUNT_NAME].orEmpty(), values[CalendarContract.Calendars.ACCOUNT_TYPE].orEmpty()),
            values[CalendarContract.Calendars.OWNER_ACCOUNT]?.takeIf(String::isNotBlank), values[CalendarContract.Calendars._SYNC_ID]?.takeIf(String::isNotBlank))
        private fun identityJson(value: CalendarIdentity) = JSONObject().put("calendar", value.calendarId.value).put("name", value.account.name)
            .put("type", value.account.type).put("owner", value.owner ?: JSONObject.NULL).put("uid", value.providerId ?: JSONObject.NULL)
        private fun parseIdentity(value: JSONObject): CalendarIdentity {
            require(value.has("owner") && value.has("uid"))
            return CalendarIdentity(CalendarId(value.getLong("calendar")), CalendarAccount(value.getString("name"), value.getString("type")),
                if (value.isNull("owner")) null else value.getString("owner"), if (value.isNull("uid")) null else value.getString("uid"))
        }
        private fun refJson(ref: EventRef): JSONObject { require(ref.recurrence == null); return JSONObject().put("calendar", ref.calendarId.value).put("event", ref.eventId)
            .put("start", ref.instanceStart.toString()).put("end", ref.instanceEnd.toString()) }
        private fun parseRef(value: JSONObject) = EventRef(CalendarId(value.getLong("calendar")), value.getLong("event"), Instant.parse(value.getString("start")), Instant.parse(value.getString("end")))
        private fun commandJson(selected: EventRef, token: EditToken, response: MeetingResponse) = JSONObject().put("selected", refJson(selected))
            .put("token", token.value).put("scope", RecurrenceScope.THIS.name).put("response", response.name)
        private fun responseStatus(value: MeetingResponse) = when (value) {
            MeetingResponse.YES -> CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED
            MeetingResponse.MAYBE -> CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE
            MeetingResponse.NO -> CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED
        }
    }
}
