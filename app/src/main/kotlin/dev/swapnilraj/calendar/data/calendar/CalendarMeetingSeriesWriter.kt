package dev.swapnilraj.calendar.data.calendar

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.OperationApplicationException
import android.net.Uri
import android.os.Parcel
import android.provider.CalendarContract
import dev.swapnilraj.calendar.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Existing-row response executor. It never creates exceptions or transforms recurrence/time. */
internal class CalendarMeetingSeriesWriter(
    private val resolver: ContentResolver,
    private val reader: CalendarReader,
    private val store: CalendarLocalStore,
    private val hasPermission: (String) -> Boolean,
    private val io: CoroutineDispatcher,
    private val readBudgets: ProviderReadBudgets,
    planningBudget: RecurrencePlanningBudget,
) {
    private val sourceReader = CalendarMeetingSeriesReader(resolver, reader, hasPermission, readBudgets, planningBudget)
    private val mutex = Mutex()
    suspend fun loadForEdit(selected: EventRef): MeetingReadResult<EditableMeeting> = withContext(io + readBudgetContext(readBudgets.mutation)) {
        try {
            ensureRead()
            val before = sourceReader.capture(selected)
            val canonical = before.canonical(readBudgets.maxCanonicalBytes)
            if (sourceReader.capture(selected, meetingIdentity(before.calendar), false).canonical(readBudgets.maxCanonicalBytes) != canonical) throw MeetingSeriesConflict()
            val scopes = listOf(RecurrenceScope.THIS, RecurrenceScope.ALL).associateWith { scope ->
                MeetingScopeAccess(respond = if (before.targets(scope) != null) AccessDecision.ALLOWED else AccessDecision.DENIED)
            }
            if (scopes.values.none { it.respond == AccessDecision.ALLOWED }) return@withContext MeetingReadResult.NeedsReview("No implemented response scope has complete self-attendee proof")
            val token = EditToken(MeetingSeriesSource.token(canonical))
            ensureRead()
            store.saveEdit(token.value, canonical)
            MeetingReadResult.Ready(EditableMeeting(requireNotNull(before.details).copy(canEdit = AccessDecision.DENIED,
                canRespond = AccessDecision.ALLOWED), token, scopes))
        } catch (_: ProviderReadLimitExceeded) { MeetingReadResult.LimitExceeded }
        catch (_: SecurityException) { MeetingReadResult.PermissionRequired }
        catch (denied: MeetingSeriesDenied) { MeetingReadResult.Denied(denied.reason) }
        catch (review: MeetingSeriesReview) { MeetingReadResult.NeedsReview(review.reason) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { MeetingReadResult.Unavailable }
    }

    suspend fun mutate(id: String, selected: EventRef, token: EditToken, command: MeetingCommand): MeetingMutationResult = withContext(io + readBudgetContext(readBudgets.mutation)) { mutex.withLock {
        try { store.abandoned(id) { requireNotNull(retirementFingerprint(selected, token, command)) }?.let { return@withLock common(it) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return@withLock common(MutationResult.Unavailable) }
        if (command !is MeetingCommand.Respond || command.scope == RecurrenceScope.FOLLOWING)
            return@withLock MeetingMutationResult.Unsupported("Only existing THIS and complete ALL series responses are implemented")
        if (command.scope == RecurrenceScope.THIS && selected.eventId == selected.recurrence?.masterEventId)
            return@withLock MeetingMutationResult.Unsupported("A generated occurrence needs a separately verified new exception")
        if (!permissions()) return@withLock common(MutationResult.PermissionRequired)
        var attempted = false
        var dispatch: CalendarLocalStore.Operation? = null
        try {
            if (id.isBlank()) return@withLock MeetingMutationResult.Invalid("Operation ID is required")
            val frozen = commandJson(selected, token, command)
            val kind = kind(command.scope)
            val signature = meetingHash(CommandFeature + frozen.toString())
            val existing = store.operation(id)
            if (existing != null) {
                if (existing.kind != kind || existing.signature != signature) return@withLock common(MutationResult.Conflict(null))
                if (existing.phase !in setOf("PREPARED", "FAILED")) return@withLock recover(existing, false)
                if (proof(existing) == null) return@withLock common(MutationResult.Uncertain(id, null))
            }
            val raw = store.edit(token.value) ?: throw MeetingSeriesConflict()
            if (MeetingSeriesSource.token(raw) != token.value) throw MeetingSeriesConflict()
            val old = MeetingSeriesSource.decode(raw, readBudgets.maxCanonicalBytes)
            if (old.selected != selected) throw MeetingSeriesConflict()
            val current = sourceReader.capture(selected, meetingIdentity(old.calendar))
            if (current.canonical(readBudgets.maxCanonicalBytes) != raw) throw MeetingSeriesConflict()
            val targets = current.targets(command.scope) ?: return@withLock MeetingMutationResult.NeedsReview("The selected scope lacks complete self-attendee proof")
            val status = CalendarMeetingWriter.responseStatus(command.response)
            val operations = assertions(current)
            targets.sorted().forEach { event ->
                val self = current.ownRows.getValue(event)
                operations += ContentProviderOperation.newUpdate(CalendarContract.Attendees.CONTENT_URI)
                    .withSelection("_id = ? AND event_id = ? AND attendeeEmail = ? AND attendeeStatus = ?",
                        arrayOf(self.getValue("_id")!!, event.toString(), self.getValue("attendeeEmail")!!, self.getValue("attendeeStatus")!!))
                    .withValue("attendeeStatus", status).withExpectedCount(1).withYieldAllowed(false).build()
            }
            current.events.forEach { event ->
                val eventId = requireNotNull(event.meetingLong("_id"))
                val expected = if (eventId in targets) event + ("selfAttendeeStatus" to status.toString()) else event
                operations += assertRow(eventUri(eventId), expected.filterKeys { it !in Bookkeeping })
            }
            current.ownRows.forEach { (event, self) ->
                operations += assertRow(attendeeUri(requireNotNull(self.meetingLong("_id"))),
                    if (event in targets) self + ("attendeeStatus" to status.toString()) else self)
            }
            addMembershipAssertions(operations, current)
            addCandidateAssertions(operations, current)
            checkParcel(operations)
            val metadata = JSONObject().put("feature", CommandFeature).put("version", 1).put("kind", kind).put("signature", signature)
                .put("command", frozen).put("identity", meetingIdentityJson(meetingIdentity(current.calendar))).put("master", current.masterId)
                .put("targets", JSONArray(targets.sorted())).put("selfIds", JSONObject().apply { targets.sorted().forEach { put(it.toString(), current.ownRows.getValue(it).getValue("_id")) } })
                .put("status", status).put("stage", "prepared")
            checkStoredJson(metadata.toString(), readBudgets.maxCanonicalBytes)
            if (!permissions()) return@withLock common(MutationResult.PermissionRequired)
            currentCoroutineContext().ensureActive()
            store.prepare(id, signature, kind, metadata.toString())
            val persisted = store.operation(id) ?: error("Journal unavailable")
            if (persisted.kind != kind || persisted.signature != signature) return@withLock common(MutationResult.Conflict(null))
            if (persisted.phase == "ABANDONED") return@withLock common(MutationResult.Retired(id))
            if (proof(persisted)?.toString() != metadata.toString()) return@withLock common(MutationResult.Uncertain(id, null))
            if (!store.attempt(persisted)) return@withLock recover(store.operation(id)!!, false)
            dispatch = persisted.copy(phase = "ATTEMPTED")
            attempted = true
            if (!permissions()) { check(store.mark(requireNotNull(dispatch), "FAILED")); return@withLock common(MutationResult.PermissionRequired) }
            resolver.applyBatch(CalendarContract.AUTHORITY, operations)
            metadata.put("stage", "committed")
            check(store.mark(requireNotNull(dispatch), "COMMITTED", metadata.toString()))
            recover(store.operation(id)!!, false)
        } catch (_: ProviderReadLimitExceeded) { if (attempted) common(MutationResult.Uncertain(id, selected.eventId)) else MeetingMutationResult.NeedsReview("Complete source exceeds the response budget") }
        catch (_: SecurityException) { common(if (attempted) MutationResult.Uncertain(id, selected.eventId) else MutationResult.PermissionRequired) }
        catch (denied: MeetingSeriesDenied) { if (attempted) common(MutationResult.Uncertain(id, selected.eventId)) else common(MutationResult.Denied(denied.reason)) }
        catch (review: MeetingSeriesReview) { if (attempted) common(MutationResult.Uncertain(id, selected.eventId)) else MeetingMutationResult.NeedsReview(review.reason) }
        catch (_: MeetingSeriesConflict) { common(if (attempted) MutationResult.Uncertain(id, selected.eventId) else MutationResult.Conflict(null)) }
        catch (_: OperationApplicationException) {
            try { check(store.mark(requireNotNull(dispatch), "FAILED")); common(MutationResult.Unavailable) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { common(MutationResult.Uncertain(id, selected.eventId)) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { common(if (attempted) MutationResult.Uncertain(id, selected.eventId) else MutationResult.Unavailable) }
    } }

    suspend fun recover(id: String): MeetingMutationResult = withContext(io + readBudgetContext(readBudgets.mutation)) { mutex.withLock {
        try { if (store.operationHeader(id)?.phase == "ABANDONED") return@withLock common(MutationResult.Retired(id))
            ensureRead(); store.operation(id)?.let { recover(it, true) } ?: common(MutationResult.Unavailable) }
        catch (_: SecurityException) { common(MutationResult.PermissionRequired) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { common(MutationResult.Uncertain(id, null)) }
    } }
    private fun proof(operation: CalendarLocalStore.Operation): JSONObject? = try {
        require(handles(operation.kind))
        val receipt = JSONObject(operation.resultRef ?: error("Missing proof"))
        require(receipt.getInt("version") == 1 && receipt.getString("feature") == CommandFeature && receipt.getString("kind") == operation.kind)
        val command = receipt.getJSONObject("command")
        require(receipt.getString("signature") == operation.signature && meetingHash(CommandFeature + command.toString()) == operation.signature)
        val scope = RecurrenceScope.valueOf(command.getString("scope"))
        require(scope != RecurrenceScope.FOLLOWING && operation.kind == kind(scope))
        val token = command.getString("token")
        val raw = store.edit(token) ?: error("Missing source")
        require(MeetingSeriesSource.token(raw) == token)
        val old = MeetingSeriesSource.decode(raw, readBudgets.maxCanonicalBytes)
        require(old.selected == meetingParseRef(command.getJSONObject("selected")) && old.masterId == receipt.getLong("master"))
        require(meetingIdentity(old.calendar) == meetingParseIdentity(receipt.getJSONObject("identity")))
        val targets = old.targets(scope) ?: error("Scope not proven")
        require(receipt.getJSONArray("targets").let { array -> (0 until array.length()).map { array.getLong(it) } } == targets.sorted())
        require(receipt.getJSONObject("selfIds").length() == targets.size)
        targets.forEach { require(receipt.getJSONObject("selfIds").getString(it.toString()) == old.ownRows.getValue(it).getValue("_id")) }
        require(receipt.getInt("status") == CalendarMeetingWriter.responseStatus(MeetingResponse.valueOf(command.getString("response"))))
        require(receipt.getString("stage") in setOf("prepared", "committed"))
        receipt
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }

    private suspend fun recover(operation: CalendarLocalStore.Operation, safeRetry: Boolean): MeetingMutationResult {
        if (operation.phase == "ABANDONED") return common(MutationResult.Retired(operation.id))
        val proof = proof(operation) ?: return common(MutationResult.Uncertain(operation.id, null))
        if (operation.phase in setOf("PREPARED", "FAILED")) return common(if (proof.getString("stage") != "prepared") MutationResult.Uncertain(operation.id, null)
            else if (safeRetry) MutationResult.SafeToRetry(operation.id) else MutationResult.Unavailable)
        if (operation.phase !in setOf("COMMITTED", "VERIFIED") || proof.getString("stage") != "committed") return common(MutationResult.Uncertain(operation.id, null))
        val command = proof.getJSONObject("command")
        val old = MeetingSeriesSource.decode(store.edit(command.getString("token")) ?: return common(MutationResult.Uncertain(operation.id, null)), readBudgets.maxCanonicalBytes)
        val uncertain = common(MutationResult.Uncertain(operation.id, old.selected.eventId))
        val targetIdentity = meetingParseIdentity(proof.getJSONObject("identity"))
        val current = try { sourceReader.capture(old.selected, targetIdentity) }
            catch (_: MeetingSeriesDenied) { return uncertain }
            catch (_: MeetingSeriesReview) { return uncertain }
            catch (_: MeetingSeriesConflict) { return uncertain }
        if (current.membership != old.membership || current.candidates != old.candidates || current.eligible != old.eligible) return uncertain
        val targets = proof.getJSONArray("targets").let { array -> (0 until array.length()).map { array.getLong(it) }.toSet() }
        val status = proof.getInt("status").toString()
        val expectedEvents = old.events.map { event -> (if (event.meetingLong("_id") in targets) event + ("selfAttendeeStatus" to status) else event).filterKeys { it !in Bookkeeping } }
        if (current.events.map { it.filterKeys { key -> key !in Bookkeeping } } != expectedEvents) return uncertain
        val expectedOwn = old.ownRows.mapValues { (event, row) -> if (event in targets) row + ("attendeeStatus" to status) else row }
        if (current.ownRows != expectedOwn || meetingIdentity(sourceReader.calendar(old.selected.calendarId)) != targetIdentity) return uncertain
        ensureRead()
        check(store.mark(operation, "VERIFIED"))
        reader.refresh()
        return MeetingMutationResult.LocalSaved(old.selected, RecurrenceScope.valueOf(command.getString("scope")))
    }

    private fun assertions(source: MeetingSeriesSource) = arrayListOf(assertRow(ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, source.selected.calendarId.value), source.calendar)).apply {
        addMembershipAssertions(this, source)
        source.events.forEach { add(assertRow(eventUri(requireNotNull(it.meetingLong("_id"))), it)) }
        addCandidateAssertions(this, source)
        source.ownRows.values.forEach { add(assertRow(attendeeUri(requireNotNull(it.meetingLong("_id"))), it)) }
    }
    private fun addMembershipAssertions(operations: ArrayList<ContentProviderOperation>, source: MeetingSeriesSource) {
        val sync = source.master["_sync_id"]?.takeIf(String::isNotBlank)
        val predicate = CalendarMeetingSeriesReader.membershipFilter(source.selected.calendarId, source.masterId, sync)
        operations += ContentProviderOperation.newAssertQuery(CalendarContract.Events.CONTENT_URI).withSelection(predicate.first, predicate.second)
            .withValue("calendar_id", source.selected.calendarId.value).withExpectedCount(source.membership.size).withYieldAllowed(false).build()
        source.membership.forEach { operations += assertRow(eventUri(requireNotNull(it.meetingLong("_id"))), it) }
        if (sync == null) operations += ContentProviderOperation.newAssertQuery(CalendarContract.Events.CONTENT_URI)
            .withSelection("calendar_id = ? AND original_id IS NULL AND original_sync_id IS NOT NULL AND deleted = 0", arrayOf(source.selected.calendarId.value.toString()))
            .withValue("calendar_id", source.selected.calendarId.value).withExpectedCount(0).withYieldAllowed(false).build()
    }
    private fun addCandidateAssertions(operations: ArrayList<ContentProviderOperation>, source: MeetingSeriesSource) {
        val owner = requireNotNull(meetingIdentity(source.calendar).owner)
        source.candidates.toSortedMap().forEach { (event, rows) ->
            val predicate = CalendarMeetingSeriesReader.ownFilter(listOf(event), owner)
            operations += ContentProviderOperation.newAssertQuery(CalendarContract.Attendees.CONTENT_URI).withSelection(predicate.first, predicate.second)
                .withValue("event_id", event).withExpectedCount(rows.size).withYieldAllowed(false).build()
            rows.forEach { operations += assertRow(attendeeUri(requireNotNull(it.meetingLong("_id"))), it) }
        }
    }
    private fun assertRow(uri: Uri, row: Map<String, String?>) = ContentProviderOperation.newAssertQuery(uri).withValues(ContentValues().apply {
        row.forEach { (key, value) -> if (value == null) putNull(key) else put(key, value) }
    }).withExpectedCount(1).withYieldAllowed(false).build()
    private fun checkParcel(operations: List<ContentProviderOperation>) {
        val parcel = Parcel.obtain()
        try { operations.forEach { it.writeToParcel(parcel, 0) }; if (parcel.dataSize() > 256 * 1024) throw MeetingSeriesReview("Complete response transaction exceeds the provider budget") }
        finally { parcel.recycle() }
    }
    private fun ensureRead() { if (!hasPermission(Manifest.permission.READ_CALENDAR)) throw SecurityException() }
    private fun permissions() = hasPermission(Manifest.permission.READ_CALENDAR) && hasPermission(Manifest.permission.WRITE_CALENDAR)
    companion object {
        private const val CommandFeature = "MEETING_SERIES_COMMAND_V1"
        private const val ThisKind = "MEETING_SERIES_V1_THIS_RESPOND_EXISTING"
        private const val AllKind = "MEETING_SERIES_V1_ALL_RESPOND"
        private val Bookkeeping = setOf("dirty", "mutators")
        fun handles(kind: String) = kind == ThisKind || kind == AllKind
        internal fun retirementFingerprint(selected: EventRef, token: EditToken, command: MeetingCommand): Pair<String, String>? {
            val recurrence = selected.recurrence ?: return null
            if (command !is MeetingCommand.Respond || command.scope == RecurrenceScope.FOLLOWING ||
                command.scope == RecurrenceScope.THIS && selected.eventId == recurrence.masterEventId) return null
            return kind(command.scope) to meetingHash(CommandFeature + commandJson(selected, token, command).toString())
        }
        private fun kind(scope: RecurrenceScope) = if (scope == RecurrenceScope.THIS) ThisKind else AllKind
        private fun commandJson(selected: EventRef, token: EditToken, command: MeetingCommand.Respond) = JSONObject().put("selected", meetingRefJson(selected))
            .put("token", token.value).put("scope", command.scope.name).put("response", command.response.name)
        private fun common(result: MutationResult) = MeetingMutationResult.Common(result)
        private fun eventUri(id: Long) = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
        private fun attendeeUri(id: Long) = ContentUris.withAppendedId(CalendarContract.Attendees.CONTENT_URI, id)
    }
}
