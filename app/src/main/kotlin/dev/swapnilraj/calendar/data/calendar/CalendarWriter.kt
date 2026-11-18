package dev.swapnilraj.calendar.data.calendar

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.OperationApplicationException
import android.net.Uri
import android.provider.CalendarContract
import dev.swapnilraj.calendar.core.*
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Ordinary, owned, guest-free one-offs only. Recurrence and invitations have separate contracts. */
class CalendarWriter(
    private val resolver: ContentResolver,
    private val reader: CalendarReader,
    private val store: CalendarLocalStore,
    private val hasPermission: (String) -> Boolean,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val readBudgets: ProviderReadBudgets = ProviderReadBudgets(),
) : CalendarMutations {
    private val mutex = Mutex()
    private class Denial(val reason: MutationDenial) : Exception()
    private class Invalid(val fields: Set<InvalidEventField>) : Exception()
    private class Stale : Exception()
    private data class Source(val event: Map<String, String?>, val calendar: Map<String, String?>,
        val alerts: List<Map<String, String?>>, val attendees: List<Map<String, String?>>, val maxCanonicalBytes: Int = 1024 * 1024) {
        private var encoded: String? = null
        fun encode(): String {
            encoded?.let { return it }
            checkCanonical(mapOf("event" to event, "calendar" to calendar, "alerts" to alerts, "attendees" to attendees), maxCanonicalBytes)
            return JSONObject().put("event", json(event)).put("calendar", json(calendar))
            .put("alerts", JSONArray(alerts.map(::json))).put("attendees", JSONArray(attendees.map(::json))).toString().also { encoded = it }
        }
    }

    override suspend fun loadForEdit(ref: EventRef): EditReadResult = withContext(io + readBudgetContext(readBudgets.mutation)) {
        if (!hasPermission(Manifest.permission.READ_CALENDAR)) return@withContext EditReadResult.PermissionRequired
        try {
            val before = source(ref)
            policy(before)
            val read = reader.readDetails(ref)
            if (read == CalendarReadState.LimitExceeded) throw ProviderReadLimitExceeded()
            val details = (read as? CalendarReadState.Ready)?.value ?: return@withContext EditReadResult.Unavailable
            if (before.encode() != source(ref).encode()) return@withContext EditReadResult.Unavailable
            val token = EditToken(signature("EDIT", JSONObject(before.encode())))
            store.saveEdit(token.value, before.encode())
            EditReadResult.Ready(EditableEvent(details, token, EditableField.entries.toSet().let {
                if (before.calendar[CalendarContract.Calendars.CAN_MODIFY_TIME_ZONE] == "0") it - EditableField.ZONE else it
            }.let {
                if (allowedAvailability(before.calendar).isNullOrEmpty()) it - EditableField.AVAILABILITY else it
            }))
        } catch (_: ProviderReadLimitExceeded) { EditReadResult.LimitExceeded }
        catch (_: SecurityException) { EditReadResult.PermissionRequired }
        catch (denied: Denial) { EditReadResult.Denied(denied.reason) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { EditReadResult.Unavailable }
    }

    override suspend fun create(operationId: String, draft: EventDraft): MutationResult = withContext(io + readBudgetContext(readBudgets.mutation)) {
        // Never silently downgrade authored recurrence before the series-create encoder is enabled.
        if (draft.recurrence != null) return@withContext MutationResult.ValidationFailed(setOf(InvalidEventField.RECURRENCE))
        try { store.abandoned(operationId) { createFingerprint(draft, readBudgets.maxCanonicalBytes) }?.let { return@withContext it } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return@withContext MutationResult.Unavailable }
        val target = draft.targetIdentity
        if (target == null || target.calendarId != draft.calendarId || target.account.name.isBlank() || target.account.type.isBlank()) return@withContext MutationResult.ValidationFailed(setOf(InvalidEventField.CALENDAR))
        if (!permissions()) return@withContext MutationResult.PermissionRequired
        try {
            if (calendarIdentity(calendar(draft.calendarId)) != target) return@withContext MutationResult.ValidationFailed(setOf(InvalidEventField.CALENDAR))
        } catch (_: Stale) { return@withContext MutationResult.ValidationFailed(setOf(InvalidEventField.CALENDAR)) }
        catch (_: SecurityException) { return@withContext MutationResult.PermissionRequired }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return@withContext MutationResult.Unavailable }
        try { mutate(operationId, { draftJson(draft, readBudgets.maxCanonicalBytes) }, "CREATE") {
            val calendar = try { calendar(draft.calendarId) } catch (_: Stale) { throw Invalid(setOf(InvalidEventField.CALENDAR)) }
            if (calendarIdentity(calendar) != target) throw Invalid(setOf(InvalidEventField.CALENDAR))
            writable(calendar)
            validate(draft, calendar, 0, initial = true)
            val operations = arrayListOf(assertCalendar(calendar))
            operations += ContentProviderOperation.newInsert(CalendarContract.Events.CONTENT_URI)
                .withValues(values(draft)).withYieldAllowed(false).build()
            draft.alertMinutes.forEach { minutes ->
                operations += ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI)
                    .withValueBackReference(CalendarContract.Reminders.EVENT_ID, 1)
                    .withValue(CalendarContract.Reminders.MINUTES, minutes)
                    .withValue(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                    .withYieldAllowed(false).build()
            }
            Prepared(operations, draft.time, draft.calendarId, null, target)
        } } catch (_: ProviderReadLimitExceeded) { MutationResult.Unavailable }
    }

    override suspend fun update(operationId: String, ref: EventRef, token: EditToken, patch: EventPatch): MutationResult =
        mutate(operationId, { patchJson(ref, token, patch, readBudgets.maxCanonicalBytes) }, "UPDATE") {
            val source = checkedSource(ref, token)
            policy(source)
            val read = reader.readDetails(ref)
            if (read == CalendarReadState.LimitExceeded) throw ProviderReadLimitExceeded()
            val details = (read as? CalendarReadState.Ready)?.value ?: throw Stale()
            val old = details.occurrence
            val draft = EventDraft(ref.calendarId, patch.time.resolve(old.time), patch.eventZone.resolve(details.eventZone),
                patch.title.resolve(old.title), patch.location.resolve(details.location), patch.description.resolve(details.description),
                patch.alertMinutes.resolve(source.alerts.filter { it[CalendarContract.Reminders.METHOD]?.toIntOrNull() == CalendarContract.Reminders.METHOD_ALERT }
                    .map { it[CalendarContract.Reminders.MINUTES]!!.toInt() }))
            val changes = ContentValues()
            (patch.title as? FieldChange.Set)?.let { changes.put(CalendarContract.Events.TITLE, it.value) }
            (patch.location as? FieldChange.Set)?.let { changes.put(CalendarContract.Events.EVENT_LOCATION, it.value) }
            (patch.description as? FieldChange.Set)?.let { changes.put(CalendarContract.Events.DESCRIPTION, it.value) }
            val authoredAvailability = (patch.availability as? FieldChange.Set)?.let { it.value ?: 0 }
            val authoredPrivacy = (patch.privacy as? FieldChange.Set)?.let { it.value ?: 0 }
            validateMetadata(authoredAvailability, authoredPrivacy, source.calendar)
            authoredAvailability?.let { changes.put(CalendarContract.Events.AVAILABILITY, it) }
            authoredPrivacy?.let { changes.put(CalendarContract.Events.ACCESS_LEVEL, it) }
            if (patch.time is FieldChange.Set || patch.eventZone is FieldChange.Set) {
                validateTime(draft)
                if (source.calendar[CalendarContract.Calendars.CAN_MODIFY_TIME_ZONE] == "0" &&
                    draft.eventZone?.id != source.event[CalendarContract.Events.EVENT_TIMEZONE]) throw Invalid(setOf(InvalidEventField.ZONE))
                changes.putAll(timeValues(draft))
            }
            if (patch.alertMinutes is FieldChange.Set) {
                val preserved = source.alerts.count { it[CalendarContract.Reminders.METHOD]?.toIntOrNull() != CalendarContract.Reminders.METHOD_ALERT }
                changes.put(CalendarContract.Events.HAS_ALARM, if (draft.alertMinutes.isNotEmpty() || preserved > 0) 1 else 0)
            }
            val operations = assertions(source)
            if (changes.size() > 0) operations += ContentProviderOperation.newUpdate(eventUri(ref.eventId))
                .withValues(changes).withExpectedCount(1).withYieldAllowed(false).build()
            if (patch.alertMinutes is FieldChange.Set) {
                val preserved = source.alerts.count { it[CalendarContract.Reminders.METHOD]?.toIntOrNull() != CalendarContract.Reminders.METHOD_ALERT }
                validateAlerts(draft.alertMinutes, source.calendar, preserved)
                operations += ContentProviderOperation.newDelete(CalendarContract.Reminders.CONTENT_URI)
                    .withSelection("${CalendarContract.Reminders.EVENT_ID} = ? AND ${CalendarContract.Reminders.METHOD} = ?",
                        arrayOf(ref.eventId.toString(), CalendarContract.Reminders.METHOD_ALERT.toString()))
                    .withYieldAllowed(false).build()
                draft.alertMinutes.forEach { minutes -> operations += ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI)
                    .withValue(CalendarContract.Reminders.EVENT_ID, ref.eventId).withValue(CalendarContract.Reminders.MINUTES, minutes)
                    .withValue(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT).withYieldAllowed(false).build() }
            }
            Prepared(operations, draft.time, ref.calendarId, ref.eventId, calendarIdentity(source.calendar), ref, source)
        }

    override suspend fun delete(operationId: String, ref: EventRef, token: EditToken): MutationResult =
        mutate(operationId, { deleteJson(ref, token) }, "DELETE") {
            val source = checkedSource(ref, token)
            policy(source)
            Prepared(assertions(source).apply {
                add(ContentProviderOperation.newDelete(eventUri(ref.eventId)).withExpectedCount(1).withYieldAllowed(false).build())
            }, EventTime.Timed(ref.instanceStart, ref.instanceEnd), ref.calendarId, ref.eventId, calendarIdentity(source.calendar), ref, source)
        }

    private data class Prepared(val operations: ArrayList<ContentProviderOperation>, val time: EventTime,
        val calendarId: CalendarId, val eventId: Long?, val namespace: CalendarIdentity,
        val originalRef: EventRef? = null, val expectedSource: Source? = null)

    private suspend fun mutate(id: String, payloadFactory: () -> JSONObject, kind: String, prepare: suspend () -> Prepared): MutationResult =
        withContext(io + readBudgetContext(readBudgets.mutation)) { mutex.withLock {
            try { store.abandoned(id) { kind to signature(kind, payloadFactory()) }?.let { return@withLock it } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return@withLock MutationResult.Unavailable }
            if (!permissions()) return@withLock MutationResult.PermissionRequired
            var attempted = false
            var dispatch: CalendarLocalStore.Operation? = null
            var knownId: Long? = null
            var submitted: Prepared? = null
            try {
                val payload = payloadFactory()
                val signature = signature(kind, payload)
                require(id.isNotBlank())
                val existing = store.operation(id)
                if (existing != null) {
                    if (existing.signature != signature || existing.kind != kind) return@withLock MutationResult.Conflict(null)
                    if (existing.phase in setOf("ABANDONED", "ATTEMPTED", "COMMITTED", "VERIFIED")) return@withLock recover(existing, allowSafeRetry = false)
                    // An old unbound create is not made dispatchable by a newer UI/calendar choice.
                    if (proof(existing) == null) return@withLock MutationResult.Uncertain(id, null)
                }
                val prepared = prepare()
                // These checks are repeated immediately before crossing the provider boundary.
                if (!permissions()) return@withLock MutationResult.PermissionRequired
                currentCoroutineContext().ensureActive()
                val preparedProof = JSONObject().put("version", 2).put("stage", "prepared").put("kind", kind).put("signature", signature)
                    .put("command", payload).put("identity", identityJson(prepared.namespace))
                store.prepare(id, signature, kind, preparedProof.toString())
                val persisted = store.operation(id) ?: error("Operation journal unavailable")
                if (persisted.signature != signature || persisted.kind != kind) return@withLock MutationResult.Conflict(null)
                if (persisted.phase == "ABANDONED") return@withLock MutationResult.Retired(id)
                if (proof(persisted)?.toString() != preparedProof.toString()) return@withLock MutationResult.Uncertain(id, null)
                if (!store.attempt(persisted)) return@withLock recover(store.operation(id)!!, allowSafeRetry = false)
                dispatch = persisted.copy(phase = "ATTEMPTED")
                attempted = true
                submitted = prepared
                if (!permissions()) {
                    check(store.mark(requireNotNull(dispatch), "FAILED"))
                    return@withLock MutationResult.PermissionRequired
                }
                val results = resolver.applyBatch(CalendarContract.AUTHORITY, prepared.operations)
                knownId = prepared.eventId ?: results.getOrNull(1)?.uri?.let(ContentUris::parseId)
                    ?: error("Provider did not identify created event")
                val ref = reference(prepared.calendarId, knownId, prepared.time)
                preparedProof.put("stage", "committed").put("ref", refJson(ref))
                check(store.mark(requireNotNull(dispatch), "COMMITTED", preparedProof.toString()))
                recover(store.operation(id)!!, allowSafeRetry = false)
            } catch (_: ProviderReadLimitExceeded) { if (attempted) MutationResult.Uncertain(id, knownId) else MutationResult.Unavailable }
            catch (denied: Denial) { MutationResult.Denied(denied.reason) }
            catch (invalid: Invalid) { MutationResult.ValidationFailed(invalid.fields) }
            catch (_: Stale) { MutationResult.Conflict(null) }
            catch (_: SecurityException) {
                if (attempted) MutationResult.Uncertain(id, knownId) else MutationResult.PermissionRequired
            } catch (_: OperationApplicationException) {
                // CalendarProvider applies the non-yielding batch in one transaction. An assertion
                // failure is definite rollback; permit a user-requested retry after fresh checks.
                try {
                    store.abandoned(id) { kind to signature(kind, payloadFactory()) }?.let { return@withLock it }
                    if (!attempted) return@withLock MutationResult.Unavailable
                    check(store.mark(requireNotNull(dispatch), "FAILED"))
                    val ref = submitted?.originalRef
                    val changed = if (ref == null) false else try {
                        source(ref).encode() != submitted?.expectedSource?.encode()
                    } catch (_: Stale) { true }
                    if (changed) MutationResult.Conflict(null) else MutationResult.Unavailable
                } catch (_: Exception) { MutationResult.Uncertain(id, knownId) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (attempted) MutationResult.Uncertain(id, knownId) else MutationResult.Unavailable }
        } }

    /** Recovery never submits provider operations, even if a matching create seems likely. */
    override suspend fun recover(operationId: String): MutationResult = withContext(io + readBudgetContext(readBudgets.mutation)) { mutex.withLock {
        try { if (store.operationHeader(operationId)?.phase == "ABANDONED") return@withLock MutationResult.Retired(operationId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return@withLock MutationResult.Unavailable }
        if (!hasPermission(Manifest.permission.READ_CALENDAR)) MutationResult.PermissionRequired
        else try { store.operation(operationId)?.let { recover(it) } ?: MutationResult.Unavailable }
        catch (_: SecurityException) { MutationResult.PermissionRequired }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { MutationResult.Unavailable }
    } }

    /** Null means legacy, contradictory or unbound journal data; never dispatch or infer a target. */
    private fun proof(operation: CalendarLocalStore.Operation): JSONObject? = try {
        val json = JSONObject(operation.resultRef ?: error("Missing proof"))
        require(json.getInt("version") == 2 && json.getString("kind") == operation.kind)
        val command = json.getJSONObject("command")
        require(json.getString("signature") == operation.signature && signature(operation.kind, command) == operation.signature)
        val target = parseIdentity(json.getJSONObject("identity"))
        require(target.calendarId.value == command.getLong("calendar"))
        when (operation.kind) {
            "CREATE" -> {
                require(target.account.name.isNotBlank() && target.account.type.isNotBlank())
                require(command.has("targetIdentity") && parseIdentity(command.getJSONObject("targetIdentity")) == target)
            }
            "UPDATE", "DELETE" -> {
                val token = command.getString("token")
                val raw = store.edit(token) ?: error("Missing source snapshot")
                require(signature("EDIT", JSONObject(raw)) == token)
                val source = JSONObject(raw)
                val sourceCalendar = source.getJSONObject("calendar")
                require(sourceCalendar.has(CalendarContract.Calendars._SYNC_ID))
                val values = sourceCalendar.keys().asSequence().associateWith { key -> if (sourceCalendar.isNull(key)) null else sourceCalendar.getString(key) }
                require(calendarIdentity(values) == target)
                require(source.getJSONObject("event").getString("_id").toLong() == command.getLong("event"))
            }
            else -> error("Unknown kind")
        }
        when (json.getString("stage")) {
            "prepared" -> require(!json.has("ref"))
            "committed" -> {
                val ref = parseRef(json.getJSONObject("ref"))
                require(ref.calendarId == target.calendarId)
                if (operation.kind == "CREATE") require(ref.instanceStart.toString() == command.getString("start") && ref.instanceEnd.toString() == command.getString("end"))
                else require(ref.eventId == command.getLong("event"))
                if (operation.kind == "DELETE") require(ref == parseRef(command))
            }
            else -> error("Unknown stage")
        }
        json
    } catch (_: Exception) { null }

    private suspend fun recover(operation: CalendarLocalStore.Operation, allowSafeRetry: Boolean = true): MutationResult {
        if (operation.phase == "ABANDONED") return MutationResult.Retired(operation.id)
        val proof = proof(operation) ?: return MutationResult.Uncertain(operation.id, null)
        if (operation.phase in setOf("PREPARED", "FAILED")) {
            return if (proof.getString("stage") != "prepared") MutationResult.Uncertain(operation.id, null)
                else if (allowSafeRetry) MutationResult.SafeToRetry(operation.id) else MutationResult.Unavailable
        }
        if (operation.phase !in setOf("COMMITTED", "VERIFIED") || proof.getString("stage") != "committed")
            return MutationResult.Uncertain(operation.id, null)
        val target = parseIdentity(proof.getJSONObject("identity"))
        val current = try { calendar(target.calendarId) } catch (_: Stale) { return MutationResult.Uncertain(operation.id, null) }
        if (calendarIdentity(current) != target ||
            (current[CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL]?.toIntOrNull() ?: -1) < CalendarContract.Calendars.CAL_ACCESS_READ)
            return MutationResult.Uncertain(operation.id, null)
        val ref = parseRef(proof.getJSONObject("ref"))
        if (operation.kind == "DELETE") {
            val rows = rows(eventUri(ref.eventId), arrayOf(CalendarContract.Events.CALENDAR_ID, CalendarContract.Events.DELETED))
            if (rows.any { it[CalendarContract.Events.CALENDAR_ID]?.toLongOrNull() != target.calendarId.value } ||
                rows.isNotEmpty() && rows.single()[CalendarContract.Events.DELETED] != "1") return MutationResult.Uncertain(operation.id, null)
            if (calendarIdentity(calendar(target.calendarId)) != target) return MutationResult.Uncertain(operation.id, null)
            check(store.mark(operation, "VERIFIED"))
            reader.refresh()
            return MutationResult.LocalDeleted(ref)
        }
        val result = reader.readDetails(ref)
        if (result !is CalendarReadState.Ready) return MutationResult.Uncertain(operation.id, ref.eventId)
        if (calendarIdentity(calendar(target.calendarId)) != target) return MutationResult.Uncertain(operation.id, null)
        check(store.mark(operation, "VERIFIED"))
        reader.refresh()
        return MutationResult.LocalSaved(result.value.occurrence.ref)
    }

    // Runtime WRITE is required only for provider mutation, not editor eligibility or recovery.
    private fun permissions() = hasPermission(Manifest.permission.READ_CALENDAR) && hasPermission(Manifest.permission.WRITE_CALENDAR)

    private fun source(ref: EventRef): Source {
        // Eligibility discovery is READ-only, but it must not inspect private event/child fields
        // on calendars that cannot support this mutation path (including free/busy access).
        val calendar = calendar(ref.calendarId)
        writable(calendar)
        val event = rows(eventUri(ref.eventId), eventFields).singleOrNull() ?: throw Stale()
        if (event[CalendarContract.Events.CALENDAR_ID]?.toLongOrNull() != ref.calendarId.value) throw Stale()
        val recurring = listOf(CalendarContract.Events.RRULE, CalendarContract.Events.RDATE, CalendarContract.Events.EXRULE,
            CalendarContract.Events.EXDATE, CalendarContract.Events.ORIGINAL_ID, CalendarContract.Events.ORIGINAL_INSTANCE_TIME,
            CalendarContract.Events.ORIGINAL_SYNC_ID).any { !event[it].isNullOrBlank() }
        if (!recurring && (event[CalendarContract.Events.DTSTART]?.toLongOrNull() != ref.instanceStart.toEpochMilli() ||
            event[CalendarContract.Events.DTEND]?.toLongOrNull() != ref.instanceEnd.toEpochMilli())) throw Stale()
        // HAS_ATTENDEE_DATA=0 and a matching organizer email are not visibility authority.
        // Check explicit provider policy before even querying the guest table.
        if (event[CalendarContract.Events.IS_ORGANIZER] != "1" && event[CalendarContract.Events.GUESTS_CAN_SEE_GUESTS] != "1")
            throw Denial(MutationDenial.UNSUPPORTED_EVENT)
        policy(Source(event, calendar, emptyList(), emptyList()))
        return Source(event, calendar,
            rows(CalendarContract.Reminders.CONTENT_URI, reminderFields, "${CalendarContract.Reminders.EVENT_ID} = ?", arrayOf(ref.eventId.toString())),
            rows(CalendarContract.Attendees.CONTENT_URI, attendeeFields, "${CalendarContract.Attendees.EVENT_ID} = ?", arrayOf(ref.eventId.toString())), readBudgets.maxCanonicalBytes)
    }

    private fun checkedSource(ref: EventRef, token: EditToken): Source {
        val expected = store.edit(token.value) ?: throw Stale()
        val current = source(ref)
        if (current.encode() != expected) throw Stale()
        return current
    }

    private fun calendar(id: CalendarId): Map<String, String?> = rows(ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, id.value), calendarFields)
        .singleOrNull()?.also { if (it[CalendarContract.Calendars._ID]?.toLongOrNull() != id.value) throw Stale() }
        ?: throw Stale()

    private fun writable(calendar: Map<String, String?>) {
        val level = calendar[CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL]?.toIntOrNull()
        if (level !in setOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR, CalendarContract.Calendars.CAL_ACCESS_EDITOR,
                CalendarContract.Calendars.CAL_ACCESS_OWNER, CalendarContract.Calendars.CAL_ACCESS_ROOT)) throw Denial(MutationDenial.CALENDAR_READ_ONLY)
    }

    private fun policy(source: Source) {
        writable(source.calendar)
        val e = source.event
        if (listOf(CalendarContract.Events.RRULE, CalendarContract.Events.RDATE, CalendarContract.Events.EXRULE,
                CalendarContract.Events.EXDATE, CalendarContract.Events.ORIGINAL_ID, CalendarContract.Events.ORIGINAL_INSTANCE_TIME,
            CalendarContract.Events.ORIGINAL_SYNC_ID).any { !e[it].isNullOrBlank() }) throw Denial(MutationDenial.RECURRENCE)
        val endZone = e[CalendarContract.Events.EVENT_END_TIMEZONE]
        if (!endZone.isNullOrBlank() && endZone != e[CalendarContract.Events.EVENT_TIMEZONE]) throw Denial(MutationDenial.UNSUPPORTED_EVENT)
        if (e[CalendarContract.Events.DELETED] != "0" || e[CalendarContract.Events.STATUS]?.toIntOrNull() == CalendarContract.Events.STATUS_CANCELED ||
            !e[CalendarContract.Events.CUSTOM_APP_PACKAGE].isNullOrBlank()) throw Denial(MutationDenial.UNSUPPORTED_EVENT)
        val actor = source.calendar[CalendarContract.Calendars.ACCOUNT_NAME]
        val organizer = e[CalendarContract.Events.ORGANIZER]
        if (actor.isNullOrBlank() || organizer.isNullOrBlank() || !actor.equals(organizer, ignoreCase = true) || e[CalendarContract.Events.IS_ORGANIZER] == "0")
            throw Denial(MutationDenial.OWNERSHIP_UNKNOWN)
        val hasAttendees = e[CalendarContract.Events.HAS_ATTENDEE_DATA]
        if (hasAttendees !in setOf("0", "1") || hasAttendees == "1" && e[CalendarContract.Events.GUESTS_CAN_SEE_GUESTS] != "1")
            throw Denial(MutationDenial.UNSUPPORTED_EVENT)
        if (source.attendees.any { !it[CalendarContract.Attendees.ATTENDEE_EMAIL].orEmpty().equals(actor, ignoreCase = true) })
            throw Denial(MutationDenial.INVITATION)
    }

    private fun validateTime(draft: EventDraft) {
        if (draft.time is EventTime.Timed && draft.eventZone == null) throw Invalid(setOf(InvalidEventField.ZONE))
    }
    private fun validate(draft: EventDraft, calendar: Map<String, String?>, preservedReminderCount: Int, initial: Boolean = false) {
        validateTime(draft)
        validateMetadata(draft.availability, draft.privacy, calendar)
        if (initial && calendar[CalendarContract.Calendars.CAN_MODIFY_TIME_ZONE] == "0" && draft.time is EventTime.Timed &&
            draft.eventZone?.id != calendar[CalendarContract.Calendars.CALENDAR_TIME_ZONE]) throw Invalid(setOf(InvalidEventField.ZONE))
        validateAlerts(draft.alertMinutes, calendar, preservedReminderCount)
    }

    // Alert changes do not author TIME/ZONE. Preserve imported NULL zones unless explicitly edited.
    private fun validateAlerts(alertMinutes: List<Int>, calendar: Map<String, String?>, preservedReminderCount: Int) {
        if (alertMinutes.any { it == CalendarContract.Reminders.MINUTES_DEFAULT } || alertMinutes.distinct().size != alertMinutes.size)
            throw Invalid(setOf(InvalidEventField.ALERTS))
        if (alertMinutes.isNotEmpty()) {
            val methods = calendar[CalendarContract.Calendars.ALLOWED_REMINDERS]?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
            val maximum = calendar[CalendarContract.Calendars.MAX_REMINDERS]?.toIntOrNull()
            if (methods?.contains(CalendarContract.Reminders.METHOD_ALERT) != true || maximum == null || maximum < alertMinutes.size + preservedReminderCount)
                throw Invalid(setOf(InvalidEventField.ALERTS))
        }
    }

    private fun allowedAvailability(calendar: Map<String, String?>): Set<Int>? {
        val raw = calendar[CalendarContract.Calendars.ALLOWED_AVAILABILITY]?.takeIf { it.isNotBlank() } ?: return null
        val values = raw.split(',').map { it.trim().toIntOrNull() ?: return null }
        if (values.any { it !in 0..2 }) return null
        return values.toSet()
    }

    private fun validateMetadata(availability: Int?, privacy: Int?, calendar: Map<String, String?>) {
        val invalid = mutableSetOf<InvalidEventField>()
        if (availability != null && (availability !in 0..2 || allowedAvailability(calendar)?.contains(availability) != true))
            invalid += InvalidEventField.AVAILABILITY
        if (privacy != null && privacy !in 0..3) invalid += InvalidEventField.PRIVACY
        if (invalid.isNotEmpty()) throw Invalid(invalid)
    }

    private fun values(draft: EventDraft) = timeValues(draft).apply {
        put(CalendarContract.Events.CALENDAR_ID, draft.calendarId.value); put(CalendarContract.Events.TITLE, draft.title)
        put(CalendarContract.Events.HAS_ALARM, if (draft.alertMinutes.isNotEmpty()) 1 else 0)
        put(CalendarContract.Events.EVENT_LOCATION, draft.location); put(CalendarContract.Events.DESCRIPTION, draft.description)
        draft.availability?.let { put(CalendarContract.Events.AVAILABILITY, it) }
        draft.privacy?.let { put(CalendarContract.Events.ACCESS_LEVEL, it) }
    }
    private fun timeValues(draft: EventDraft) = ContentValues().apply {
        val ref = reference(draft.calendarId, 0, draft.time)
        put(CalendarContract.Events.DTSTART, ref.instanceStart.toEpochMilli()); put(CalendarContract.Events.DTEND, ref.instanceEnd.toEpochMilli())
        put(CalendarContract.Events.ALL_DAY, if (draft.time is EventTime.AllDay) 1 else 0)
        val zone = if (draft.time is EventTime.AllDay) "UTC" else draft.eventZone!!.id
        put(CalendarContract.Events.EVENT_TIMEZONE, zone)
        put(CalendarContract.Events.EVENT_END_TIMEZONE, zone)
        putNull(CalendarContract.Events.DURATION)
    }

    private fun assertions(source: Source): ArrayList<ContentProviderOperation> = arrayListOf(assertCalendar(source.calendar)).apply {
        add(assertRow(eventUri(source.event[CalendarContract.Events._ID]!!.toLong()), source.event))
        listOf(Triple(CalendarContract.Reminders.CONTENT_URI, source.alerts, CalendarContract.Reminders.EVENT_ID),
            Triple(CalendarContract.Attendees.CONTENT_URI, source.attendees, CalendarContract.Attendees.EVENT_ID)).forEach { (uri, children, parentColumn) ->
            add(ContentProviderOperation.newAssertQuery(uri).withSelection("$parentColumn = ?", arrayOf(source.event[CalendarContract.Events._ID]!!))
                .withExpectedCount(children.size).withYieldAllowed(false).build())
            children.forEach { add(assertRow(ContentUris.withAppendedId(uri, it["_id"]!!.toLong()), it)) }
        }
    }
    private fun assertCalendar(calendar: Map<String, String?>) = assertRow(ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI,
        calendar[CalendarContract.Calendars._ID]!!.toLong()), calendar)
    private fun assertRow(uri: Uri, values: Map<String, String?>): ContentProviderOperation = ContentProviderOperation.newAssertQuery(uri)
        .withValues(ContentValues().apply { values.forEach { (key, value) -> if (value == null) putNull(key) else put(key, value) } })
        .withExpectedCount(1).withYieldAllowed(false).build()

    private fun rows(uri: Uri, projection: Array<String>, selection: String? = null, args: Array<String>? = null): List<Map<String, String?>> =
        (resolver.query(uri, projection, selection, args, "_id ASC") ?: error("Calendar provider unavailable")).bounded(if (uri.pathSegments.firstOrNull() in setOf("attendees", "reminders")) readBudgets.maxSeriesChildren else Int.MAX_VALUE).use { cursor ->
            buildList { while (cursor.moveToNext()) add(projection.associateWith { key ->
                val index = cursor.getColumnIndexOrThrow(key)
                if (cursor.isNull(index)) null else cursor.getString(index)
            }) }
        }

    companion object {
        private fun calendarIdentity(values: Map<String, String?>) = CalendarIdentity(CalendarId(values.getValue(CalendarContract.Calendars._ID)!!.toLong()),
            CalendarAccount(values[CalendarContract.Calendars.ACCOUNT_NAME].orEmpty(), values[CalendarContract.Calendars.ACCOUNT_TYPE].orEmpty()),
            values[CalendarContract.Calendars.OWNER_ACCOUNT]?.takeIf(String::isNotBlank), values[CalendarContract.Calendars._SYNC_ID]?.takeIf(String::isNotBlank))
        private fun identityJson(identity: CalendarIdentity) = JSONObject().put("calendar", identity.calendarId.value)
            .put("name", identity.account.name).put("type", identity.account.type).put("owner", identity.owner ?: JSONObject.NULL)
            .put("providerId", identity.providerId ?: JSONObject.NULL)
        private fun parseIdentity(json: JSONObject): CalendarIdentity {
            require(json.has("owner") && json.has("providerId"))
            return CalendarIdentity(CalendarId(json.getLong("calendar")), CalendarAccount(json.getString("name"), json.getString("type")),
                if (json.isNull("owner")) null else json.getString("owner"), if (json.isNull("providerId")) null else json.getString("providerId"))
        }
        internal fun createFingerprint(draft: EventDraft, maxBytes: Int): Pair<String, String> = "CREATE" to signature("CREATE", draftJson(draft, maxBytes))
        internal fun updateFingerprint(ref: EventRef, token: EditToken, patch: EventPatch, maxBytes: Int): Pair<String, String> =
            "UPDATE" to signature("UPDATE", patchJson(ref, token, patch, maxBytes))
        internal fun deleteFingerprint(ref: EventRef, token: EditToken): Pair<String, String> = "DELETE" to signature("DELETE", deleteJson(ref, token))
        private fun deleteJson(ref: EventRef, token: EditToken) = refJson(ref).put("token", token.value)
        private fun eventUri(id: Long) = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
        private fun json(values: Map<String, String?>): JSONObject = JSONObject().apply {
            values.toSortedMap().forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
        }
        private fun reference(calendarId: CalendarId, eventId: Long, time: EventTime): EventRef = when (time) {
            is EventTime.Timed -> EventRef(calendarId, eventId, time.start, time.end)
            is EventTime.AllDay -> EventRef(calendarId, eventId, time.dates.start.atStartOfDay(ZoneOffset.UTC).toInstant(),
                time.dates.endExclusive.atStartOfDay(ZoneOffset.UTC).toInstant())
        }
        private fun refJson(ref: EventRef) = JSONObject().put("calendar", ref.calendarId.value).put("event", ref.eventId)
            .put("start", ref.instanceStart.toString()).put("end", ref.instanceEnd.toString())
        private fun parseRef(json: JSONObject) = EventRef(CalendarId(json.getLong("calendar")), json.getLong("event"),
            Instant.parse(json.getString("start")), Instant.parse(json.getString("end")))
        private fun draftJson(draft: EventDraft, maxBytes: Int): JSONObject {
            checkCanonical(mapOf("title" to draft.title, "location" to draft.location, "description" to draft.description, "alerts" to draft.alertMinutes), maxBytes)
            listOf(draft.title, draft.location, draft.description).forEach { currentReadBudget()?.text(it) }
            return refJson(reference(draft.calendarId, 0, draft.time))
            .put("allDay", draft.time is EventTime.AllDay).put("zone", draft.eventZone?.id ?: JSONObject.NULL)
            .put("title", draft.title).put("location", draft.location).put("description", draft.description).put("alerts", JSONArray(draft.alertMinutes))
            .apply {
                draft.availability?.let { put("availability", it) }
                draft.privacy?.let { put("privacy", it) }
                draft.targetIdentity?.let { put("targetIdentity", identityJson(it)) }
            }
        }
        private fun patchJson(ref: EventRef, token: EditToken, patch: EventPatch, maxBytes: Int): JSONObject {
            checkPatchInput(patch, maxBytes)
            return refJson(ref).put("token", token.value).apply {
            fun change(name: String, change: FieldChange<*>) { if (change is FieldChange.Set) put(name, change.value?.toString() ?: JSONObject.NULL) }
            change("title", patch.title); change("time", patch.time); change("zone", patch.eventZone)
            change("location", patch.location); change("description", patch.description); change("alerts", patch.alertMinutes)
            change("availability", patch.availability); change("privacy", patch.privacy)
        }
        }
        private fun signature(kind: String, payload: JSONObject): String = MessageDigest.getInstance("SHA-256")
            .digest((kind + payload.toString()).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        private fun <T> FieldChange<T>.resolve(old: T): T = when (this) { FieldChange.Keep -> old; is FieldChange.Set -> value }
        private val eventFields = arrayOf(CalendarContract.Events._ID, CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND, CalendarContract.Events.DURATION, CalendarContract.Events.ALL_DAY, CalendarContract.Events.HAS_ALARM,
            CalendarContract.Events.EVENT_TIMEZONE, CalendarContract.Events.EVENT_END_TIMEZONE, CalendarContract.Events.TITLE, CalendarContract.Events.EVENT_LOCATION, CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.RRULE, CalendarContract.Events.RDATE, CalendarContract.Events.EXRULE, CalendarContract.Events.EXDATE,
            CalendarContract.Events.ORIGINAL_ID, CalendarContract.Events.ORIGINAL_INSTANCE_TIME, CalendarContract.Events.ORIGINAL_SYNC_ID, CalendarContract.Events.STATUS, CalendarContract.Events.DELETED,
            CalendarContract.Events.ORGANIZER, CalendarContract.Events.IS_ORGANIZER, CalendarContract.Events.HAS_ATTENDEE_DATA,
            CalendarContract.Events.GUESTS_CAN_SEE_GUESTS, CalendarContract.Events.CUSTOM_APP_PACKAGE,
            CalendarContract.Events.AVAILABILITY, CalendarContract.Events.ACCESS_LEVEL)
        private val calendarFields = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.OWNER_ACCOUNT, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.ALLOWED_REMINDERS,
            CalendarContract.Calendars.MAX_REMINDERS, CalendarContract.Calendars.CAN_MODIFY_TIME_ZONE, CalendarContract.Calendars.CALENDAR_TIME_ZONE,
            CalendarContract.Calendars.ALLOWED_AVAILABILITY, CalendarContract.Calendars._SYNC_ID)
        private val reminderFields = arrayOf(CalendarContract.Reminders._ID, CalendarContract.Reminders.EVENT_ID, CalendarContract.Reminders.MINUTES, CalendarContract.Reminders.METHOD)
        private val attendeeFields = arrayOf(CalendarContract.Attendees._ID, CalendarContract.Attendees.EVENT_ID, CalendarContract.Attendees.ATTENDEE_EMAIL,
            CalendarContract.Attendees.ATTENDEE_RELATIONSHIP, CalendarContract.Attendees.ATTENDEE_STATUS, CalendarContract.Attendees.ATTENDEE_TYPE)
    }
}
