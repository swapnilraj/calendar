package dev.swapnilraj.calendar.data.calendar

import android.Manifest
import android.content.*
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.Parcel
import android.provider.CalendarContract
import dev.swapnilraj.calendar.core.*
import java.time.*
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Fully visible, audited generated-THIS responses. Existing self-only response paths are separate. */
internal class CalendarMeetingGeneratedResponseWriter(
    private val resolver: ContentResolver,
    private val reader: CalendarReader,
    private val sourceReader: CalendarMeetingSeriesReader,
    private val store: CalendarLocalStore,
    private val hasPermission: (String) -> Boolean,
    private val owner: CalendarMeetingSeriesWriter,
    private val io: CoroutineDispatcher,
    private val readBudgets: ProviderReadBudgets,
    private val planningBudget: RecurrencePlanningBudget,
) {
    private val mutex = Mutex()
    private class Review(val reason: String) : Exception()
    private data class Interval(val zone: String, val allDay: Boolean, val provisional: String)

    /** Known unsupported clone profiles do not remove the complete base's ALL capability. */
    suspend fun audit(base: MeetingSeriesSource): MeetingGeneratedSource? = try {
        profileBase(base)
        val latest = rows(eventUri(base.masterId), arrayOf("_id", "calendar_id", "guestsCanSeeGuests", "isOrganizer"), maxRows = 1).singleOrNull()
            ?: throw MeetingSeriesConflict()
        if (latest["_id"] != base.masterId.toString() || latest["calendar_id"] != base.selected.calendarId.value.toString() || latest["guestsCanSeeGuests"] != "1" || latest["isOrganizer"] != "0")
            throw MeetingSeriesConflict()
        val where = "event_id = ? AND guestsCanSeeGuests = ?"
        val args = arrayOf(base.masterId.toString(), "1")
        if (countAbove(CalendarContract.Attendees.CONTENT_URI, where, args, MaxGuests)) throw Review("The visible guest list exceeds the portable clone profile")
        if (exists(CalendarContract.ExtendedProperties.CONTENT_URI, "event_id = ?", arrayOf(base.masterId.toString()))) throw Review("Extended properties require a separately verified clone profile")
        val attendees = rows(CalendarContract.Attendees.CONTENT_URI, null, where, args, MaxGuests, AttendeeCopy + ChildIds, true)
        val reminders = rows(CalendarContract.Reminders.CONTENT_URI, null, "event_id = ?", arrayOf(base.masterId.toString()), readBudgets.maxSeriesChildren, ReminderCopy + ChildIds)
        val result = MeetingGeneratedSource(base, base.canonical(readBudgets.maxCanonicalBytes), attendees, reminders)
        profile(result)
        // Representation is tested before the token is saved, for every response's same-size int.
        checkParcel(operations(result, 1).first)
        result
    } catch (_: Review) { null }

    suspend fun validateNativeSelection(base: MeetingSeriesSource) {
        val selected = base.selected
        val bounds = instanceUri(selected)
        val found = rows(bounds, InstanceFields, "event_id = ? AND calendar_id = ? AND begin = ?",
            arrayOf(selected.eventId.toString(), selected.calendarId.value.toString(), selected.instanceStart.toEpochMilli().toString()), 1).singleOrNull()
            ?: throw MeetingSeriesConflict()
        if (!nativeMatches(found, selected, base.master["allDay"])) throw MeetingSeriesConflict()
    }

    suspend fun mutate(id: String, selected: EventRef, token: EditToken, command: MeetingCommand.Respond): MeetingMutationResult =
        withContext(io + readBudgetContext(readBudgets.mutation)) { mutex.withLock {
        var attempted: CalendarLocalStore.Operation? = null
        var known: Long? = null
        try {
            store.abandoned(id) { fingerprint(selected, token, command) }?.let { return@withLock common(it) }
            if (!permissions()) return@withLock common(MutationResult.PermissionRequired)
            if (id.isBlank()) return@withLock MeetingMutationResult.Invalid("Operation ID is required")
            val (kind, signature) = fingerprint(selected, token, command)
            val existing = store.operation(id)
            if (existing != null) {
                if (existing.kind != kind || existing.signature != signature) return@withLock common(MutationResult.Conflict(null))
                if (existing.phase !in setOf("PREPARED", "FAILED")) return@withLock recover(existing, false)
                if (proof(existing) == null) return@withLock common(MutationResult.Uncertain(id, null))
            }
            val raw = store.edit(token.value) ?: throw MeetingSeriesConflict()
            val authority = MeetingSeriesAuthority.decode(raw, token, readBudgets.maxCanonicalBytes)
            val original = authority.generated ?: return@withLock MeetingMutationResult.Unsupported("This occurrence needs a fresh visible audited response source")
            if (original.base.selected != selected) throw MeetingSeriesConflict()
            profile(original)
            val base = sourceReader.capture(selected, meetingIdentity(original.base.calendar), true)
            if (base.canonical(readBudgets.maxCanonicalBytes) != authority.baseRaw) throw MeetingSeriesConflict()
            val current = audit(base) ?: throw Review("The current invitation cannot be cloned safely")
            if (current.canonical(readBudgets.maxCanonicalBytes) != raw) throw MeetingSeriesConflict()
            val status = CalendarMeetingWriter.responseStatus(command.response)
            val (ops, insertion) = operations(current, status)
            checkParcel(ops)
            val metadata = JSONObject().put("version", 1).put("feature", Feature).put("kind", Kind).put("signature", signature)
                .put("command", CalendarMeetingSeriesWriter.frozenCommandJson(selected, token, command))
                .put("source", JSONObject(raw)).put("stage", "prepared").put("status", status)
            checkStoredJson(metadata.toString(), readBudgets.maxCanonicalBytes)
            if (!permissions()) return@withLock common(MutationResult.PermissionRequired)
            currentCoroutineContext().ensureActive(); store.prepare(id, signature, kind, metadata.toString())
            val persisted = store.operation(id) ?: error("Journal unavailable")
            if (persisted.kind != kind || persisted.signature != signature) return@withLock common(MutationResult.Conflict(null))
            if (persisted.phase == "ABANDONED") return@withLock common(MutationResult.Retired(id))
            if (proof(persisted)?.first?.toString() != metadata.toString()) return@withLock common(MutationResult.Uncertain(id, null))
            if (!store.attempt(persisted)) return@withLock recover(store.operation(id)!!, false)
            attempted = persisted.copy(phase = "ATTEMPTED")
            if (!permissions()) { check(store.mark(attempted, "FAILED")); return@withLock common(MutationResult.PermissionRequired) }
            currentCoroutineContext().ensureActive()
            val results = resolver.applyBatch(CalendarContract.AUTHORITY, ops)
            known = exceptionId(results.getOrNull(insertion)?.uri, current.base)
            metadata.put("stage", "committed").put("event", known)
            check(store.mark(attempted, "COMMITTED", metadata.toString()))
            recover(store.operation(id)!!, false)
        } catch (_: SecurityException) { common(if (attempted == null) MutationResult.PermissionRequired else MutationResult.Uncertain(id, known)) }
        catch (_: ProviderReadLimitExceeded) { if (attempted == null) MeetingMutationResult.NeedsReview("The complete response exceeds the read or storage budget") else common(MutationResult.Uncertain(id, known)) }
        catch (_: MeetingSeriesConflict) { common(if (attempted == null) MutationResult.Conflict(null) else MutationResult.Uncertain(id, known)) }
        catch (denied: MeetingSeriesDenied) { if (attempted == null) common(MutationResult.Denied(denied.reason)) else common(MutationResult.Uncertain(id, known)) }
        catch (review: MeetingSeriesReview) { if (attempted == null) MeetingMutationResult.NeedsReview(review.reason) else common(MutationResult.Uncertain(id, known)) }
        catch (review: Review) { if (attempted == null) MeetingMutationResult.NeedsReview(review.reason) else common(MutationResult.Uncertain(id, known)) }
        catch (_: OperationApplicationException) {
            if (attempted == null) common(MutationResult.Unavailable) else try { check(store.mark(attempted, "FAILED")); common(MutationResult.Unavailable) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { common(MutationResult.Uncertain(id, known)) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { common(if (attempted == null) MutationResult.Unavailable else MutationResult.Uncertain(id, known)) }
    } }

    suspend fun recover(id: String): MeetingMutationResult = withContext(io + readBudgetContext(readBudgets.mutation)) { mutex.withLock {
        try { if (store.operationHeader(id)?.phase == "ABANDONED") return@withLock common(MutationResult.Retired(id))
            ensureRead(); store.operation(id)?.let { recover(it, true) } ?: common(MutationResult.Unavailable)
        } catch (_: SecurityException) { common(MutationResult.PermissionRequired) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { common(MutationResult.Uncertain(id, null)) }
    } }
    private suspend fun recover(operation: CalendarLocalStore.Operation, retry: Boolean): MeetingMutationResult {
        if (operation.phase == "ABANDONED") return common(MutationResult.Retired(operation.id))
        val parsed = proof(operation) ?: return common(MutationResult.Uncertain(operation.id, null))
        val receipt = parsed.first; val source = parsed.second; val base = source.base
        if (operation.phase in setOf("PREPARED", "FAILED")) return common(if (retry) MutationResult.SafeToRetry(operation.id) else MutationResult.Unavailable)
        val id = receipt.getLong("event"); fun uncertain() = common(MutationResult.Uncertain(operation.id, id))
        return try {
            if (!sameNamespace(base.calendar, sourceReader.calendar(base.selected.calendarId))) return uncertain()
            val status = receipt.getInt("status")
            // No old edit snapshot is needed: the receipt binds the complete admitted source.
            for (event in base.events) if (!verifyEvent(requireNotNull(event.meetingLong("_id")), event.filterKeys { it !in Bookkeeping })) return uncertain()
            for ((event, own) in base.ownRows) {
                val actual = rows(CalendarContract.Attendees.CONTENT_URI, AttendeeFields, "_id = ? AND event_id = ?", arrayOf(own.getValue("_id")!!, event.toString()), 1).singleOrNull()
                if (actual != own) return uncertain()
            }
            val expected = expectedEvent(source, status).filterKeys { it != "_sync_id" && it !in (1..10).map { n -> "sync_data$n" } } + ("_id" to id.toString())
            if (!verifyEvent(id, expected)) return uncertain()
            val created = rows(eventUri(id), arrayOf("_id", "_sync_id", "uid2445"), maxRows = 1).singleOrNull() ?: return uncertain()
            val sync = created["_sync_id"]?.takeIf(String::isNotBlank)
            val knownSync = receipt.nullable("createdSync")?.takeIf(String::isNotBlank)
            if (knownSync != null && knownSync != sync || sync != null && sync == base.master["_sync_id"]) return uncertain()
            if (base.master["uid2445"] != created["uid2445"]) return uncertain()
            if (!verifyMembership(base, id, sync)) return uncertain()
            if (!verifyChildren(base.masterId, source.attendees, source.reminders, false)) return uncertain()
            if (!verifyChildren(id, expectedAttendees(source, status), source.reminders, true)) return uncertain()
            val ref = base.selected.copy(eventId = id)
            val native = rows(instanceUri(ref), InstanceFields, "event_id = ? AND calendar_id = ? AND begin = ?",
                arrayOf(id.toString(), ref.calendarId.value.toString(), ref.instanceStart.toEpochMilli().toString()), 1).singleOrNull() ?: return uncertain()
            if (!nativeMatches(native, ref, base.master["allDay"]) || !sameNamespace(base.calendar, sourceReader.calendar(ref.calendarId))) return uncertain()
            ensureRead()
            val verified = JSONObject(receipt.toString()).put("createdSync", knownSync ?: sync ?: JSONObject.NULL)
            check(store.mark(operation, "VERIFIED", verified.toString())); reader.refresh()
            MeetingMutationResult.LocalSaved(EventRef(ref.calendarId, id, Instant.ofEpochMilli(native.getValue("begin")!!.toLong()), Instant.ofEpochMilli(native.getValue("end")!!.toLong()), ref.recurrence), RecurrenceScope.THIS)
        } catch (denied: SecurityException) { throw denied }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { uncertain() }
    }

    private fun profileBase(base: MeetingSeriesSource): Interval {
        val selected = base.selected; val m = base.master
        if (selected.eventId != base.masterId || selected.recurrence?.originalStart != selected.instanceStart || base.masterId !in base.eligible) throw Review("The selected generated invitation lacks unique self-attendee proof")
        if (!m.containsKey("uid2445")) throw Review("The provider does not expose the inherited event identity")
        if (m["rrule"].isNullOrBlank()) throw Review("The public response-exception path requires an RRULE master")
        if (m["guestsCanSeeGuests"] != "1" || m["isOrganizer"] != "0") throw Review("A complete legitimately visible guest list is required")
        if (m["hasExtendedProperties"] != "0") throw Review("Extended properties require a separately verified clone profile")
        if (m.any { (key, value) -> value != null && key !in KnownMaster && !SeriesAttendeeColumns.isJoinedAlias(key) }) throw Review("Provider-specific event metadata cannot be cloned by this profile")
        MeetingSeriesAuthority.validateBase(base)
        val sync = m["_sync_id"]?.takeIf(String::isNotBlank)
        val keys = mutableSetOf<Long>()
        for (member in base.membership.filter { it.meetingLong("_id") != base.masterId }) {
            val parent = member.meetingLong("original_id")
            val linkedSync = member["original_sync_id"]?.takeIf(String::isNotBlank)
            if ((!member["original_id"].isNullOrBlank() && parent == null) || parent != null && parent != base.masterId ||
                linkedSync != null && linkedSync != sync || parent == null && linkedSync == null || member["originalAllDay"] != m["allDay"])
                throw Review("A retained exception has ambiguous parent identity")
            val key = member.meetingLong("originalInstanceTime") ?: throw Review("A retained exception lacks original identity")
            if (!keys.add(key)) throw Review("Multiple retained exceptions own an original key")
        }
        val original = requireNotNull(selected.recurrence).originalStart.toEpochMilli()
        if (base.membership.any { it.meetingLong("_id") != base.masterId && it.meetingLong("originalInstanceTime") == original }) throw Review("An active or deleted response exception already owns this original key")
        val start = selected.instanceStart; val end = selected.instanceEnd
        try {
            require(start.nano == 0 && end.nano == 0 && start.atOffset(ZoneOffset.UTC).year in 1..9999 && end.atOffset(ZoneOffset.UTC).year in 1..9999)
            val seconds = Math.subtractExact(end.epochSecond, start.epochSecond); require(seconds in 1..Int.MAX_VALUE.toLong())
            require(Math.addExact(start.toEpochMilli(), Math.multiplyExact(seconds, 1000)) == end.toEpochMilli())
            val zone = m["eventTimezone"] ?: error("Missing zone")
            val parsed = ZoneId.of(zone); require(ProviderTimeZoneCompatibility.isCompatible(parsed))
            if (!m["eventEndTimezone"].isNullOrBlank() && m["eventEndTimezone"] != zone) throw Review("A split-zone source needs a separately verified profile")
            return when (m["allDay"]) {
                "0" -> Interval(zone, false, "PT${seconds}S")
                "1" -> { require(parsed.rules.isFixedOffset && parsed.rules.getOffset(start) == ZoneOffset.UTC)
                    require(start.atOffset(ZoneOffset.UTC).toLocalTime() == LocalTime.MIDNIGHT && end.atOffset(ZoneOffset.UTC).toLocalTime() == LocalTime.MIDNIGHT)
                    val days = seconds / 86400; require(days * 86400 == seconds && days in 1..(Int.MAX_VALUE / 86400).toLong())
                    Interval(zone, true, "P${days}D") }
                else -> throw Review("Unknown all-day mode")
            }
        } catch (review: Review) { throw review }
        catch (_: Exception) { throw Review("The observed occurrence interval cannot be preserved exactly") }
    }
    private fun profile(source: MeetingGeneratedSource): Interval {
        val interval = profileBase(source.base); val base = source.base
        if (source.attendees.size !in 1..MaxGuests || source.reminders.size > readBudgets.maxSeriesChildren) throw Review("The clone exceeds the portable row budget")
        val self = base.ownRows[base.masterId] ?: throw Review("The master lacks a unique self attendee")
        if (source.attendees.singleOrNull { it["_id"] == self["_id"] } != self || source.attendees.count { it["attendeeEmail"] == meetingIdentity(base.calendar).owner } != 1) throw Review("The visible guest snapshot disagrees with the exact self row")
        if (source.attendees.map { it["_id"] }.toSet().size != source.attendees.size || source.attendees.any { it["event_id"] != base.masterId.toString() }) throw Review("Ambiguous guest identities")
        if (source.reminders.any { it["event_id"] != base.masterId.toString() || it["method"]?.toIntOrNull() !in 0..4 || it["minutes"]?.toIntOrNull() == null }) throw Review("Unsupported stored reminder semantics")
        if (base.master["hasAlarm"] != if (source.reminders.isEmpty()) "0" else "1") throw Review("Alarm metadata and stored reminder coverage disagree")
        return interval
    }
    private fun expectedEvent(source: MeetingGeneratedSource, status: Int): Map<String, String?> {
        val base = source.base; val selected = base.selected; val interval = profile(source)
        return base.master.filterKeys { it !in Bookkeeping && it != "_id" }.toMutableMap().apply {
            for (key in Cleared) if (!key.startsWith("sync_data") || base.master.containsKey(key)) put(key, null)
            put("dtstart", selected.instanceStart.toEpochMilli().toString()); put("dtend", selected.instanceEnd.toEpochMilli().toString())
            put("eventTimezone", interval.zone); put("allDay", if (interval.allDay) "1" else "0")
            put("original_id", base.masterId.toString()); put("original_sync_id", base.master["_sync_id"]?.takeIf(String::isNotBlank))
            put("originalInstanceTime", selected.recurrence!!.originalStart.toEpochMilli().toString()); put("originalAllDay", if (interval.allDay) "1" else "0")
            put("selfAttendeeStatus", status.toString())
        }
    }
    private fun expectedAttendees(source: MeetingGeneratedSource, status: Int): List<Map<String, String?>> {
        val self = source.base.ownRows.getValue(source.base.masterId).getValue("_id")
        return source.attendees.map { row -> row.filterKeys { it !in ChildIds }.toMutableMap().apply { if (row["_id"] == self) put("attendeeStatus", status.toString()) } }
    }
    private fun operations(source: MeetingGeneratedSource, status: Int): Pair<ArrayList<ContentProviderOperation>, Int> {
        val base = source.base; val selected = base.selected; val interval = profile(source)
        val ops = owner.generatedAssertions(base)
        assertExistingChildren(ops, base.masterId, source.attendees, source.reminders)
        val absence = keyFilter(base)
        ops += ContentProviderOperation.newAssertQuery(CalendarContract.Events.CONTENT_URI).withSelection(absence.first, absence.second)
            .withValue("calendar_id", selected.calendarId.value).withExpectedCount(0).withYieldAllowed(false).build()
        ops += ContentProviderOperation.newAssertQuery(instanceUri(selected)).withSelection("event_id = ? AND calendar_id = ? AND begin = ?", arrayOf(base.masterId.toString(), selected.calendarId.value.toString(), selected.instanceStart.toEpochMilli().toString()))
            .withValues(values(mapOf("event_id" to base.masterId.toString(), "calendar_id" to selected.calendarId.value.toString(), "begin" to selected.instanceStart.toEpochMilli().toString(), "end" to selected.instanceEnd.toEpochMilli().toString(), "allDay" to base.master["allDay"])))
            .withExpectedCount(1).withYieldAllowed(false).build()
        val insertion = ops.size
        // Pinned AOSP 10 f013f1c / 16 77bed8f handleInsertException: DTEND is not in
        // ALLOWED_IN_EXCEPTION. The public single-occurrence path clones SDK child data and
        // derives an interim end from DURATION; retain the authoritative observed interval with
        // a same-batch ordinary update, including all raw-time context (updateEventRawTimesLocked).
        val supplied = ContentValues().apply {
            put("originalInstanceTime", selected.recurrence!!.originalStart.toEpochMilli()); put("selfAttendeeStatus", status)
            put("dtstart", selected.instanceStart.toEpochMilli()); put("duration", interval.provisional); put("eventTimezone", interval.zone); put("allDay", if (interval.allDay) 1 else 0)
            base.master["eventStatus"]?.let { put("eventStatus", it) } ?: putNull("eventStatus")
        }
        ops += ContentProviderOperation.newInsert(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_EXCEPTION_URI, base.masterId)).withValues(supplied).withYieldAllowed(false).build()
        val context = ContentValues().apply {
            put("dtstart", selected.instanceStart.toEpochMilli()); put("dtend", selected.instanceEnd.toEpochMilli()); put("eventTimezone", interval.zone); put("allDay", if (interval.allDay) 1 else 0)
            put("originalInstanceTime", selected.recurrence!!.originalStart.toEpochMilli()); put("originalAllDay", if (interval.allDay) 1 else 0)
        }
        var where = "_id = ? AND calendar_id = ? AND original_id = ? AND originalInstanceTime = ? AND dtstart = ? AND allDay = ? AND originalAllDay = ?"
        val args = mutableListOf("0", selected.calendarId.value.toString(), base.masterId.toString(), selected.recurrence!!.originalStart.toEpochMilli().toString(), selected.instanceStart.toEpochMilli().toString(), if (interval.allDay) "1" else "0", if (interval.allDay) "1" else "0")
        val sync = base.master["_sync_id"]?.takeIf(String::isNotBlank)
        if (sync == null) where += " AND original_sync_id IS NULL" else { where += " AND original_sync_id = ?"; args += sync }
        ops += ContentProviderOperation.newUpdate(CalendarContract.Events.CONTENT_URI).withSelection(where, args.toTypedArray()).withSelectionBackReference(0, insertion)
            .withValues(context).withExpectedCount(1).withYieldAllowed(false).build()
        ops += ContentProviderOperation.newAssertQuery(CalendarContract.Events.CONTENT_URI).withSelection("_id = ?", arrayOf("0")).withSelectionBackReference(0, insertion)
            .withValues(values(expectedEvent(source, status))).withValueBackReference("_id", insertion).withExpectedCount(1).withYieldAllowed(false).build()
        for ((uri, rows) in listOf(CalendarContract.Attendees.CONTENT_URI to expectedAttendees(source, status), CalendarContract.Reminders.CONTENT_URI to source.reminders.map { it.filterKeys { key -> key !in ChildIds } })) {
            ops += ContentProviderOperation.newAssertQuery(uri).withSelection("event_id = ?", arrayOf("0")).withSelectionBackReference(0, insertion)
                .withValueBackReference("event_id", insertion).withExpectedCount(rows.size).withYieldAllowed(false).build()
            rows.forEach { row ->
                val fields = row.keys.toList()
                ops += ContentProviderOperation.newAssertQuery(uri).withSelection("event_id = ?" + fields.joinToString("") { " AND $it IS ?" }, arrayOf("0", *fields.map { row[it] }.toTypedArray()))
                    .withSelectionBackReference(0, insertion).withValues(values(row)).withExpectedCount(rows.count { it == row }).withYieldAllowed(false).build()
            }
        }
        ops += ContentProviderOperation.newAssertQuery(CalendarContract.ExtendedProperties.CONTENT_URI).withSelection("event_id = ?", arrayOf("0")).withSelectionBackReference(0, insertion).withValueBackReference("event_id", insertion).withExpectedCount(0).withYieldAllowed(false).build()
        base.events.forEach { ops += assertRow(eventUri(requireNotNull(it.meetingLong("_id"))), it.filterKeys { key -> key !in Bookkeeping }) }
        base.ownRows.values.forEach { ops += assertRow(ContentUris.withAppendedId(CalendarContract.Attendees.CONTENT_URI, requireNotNull(it.meetingLong("_id"))), it) }
        owner.generatedCandidates(ops, base)
        assertExistingChildren(ops, base.masterId, source.attendees, source.reminders)
        val membership = CalendarMeetingSeriesReader.membershipFilter(selected.calendarId, base.masterId, sync)
        ops += ContentProviderOperation.newAssertQuery(CalendarContract.Events.CONTENT_URI).withSelection(membership.first, membership.second).withValue("calendar_id", selected.calendarId.value).withExpectedCount(base.membership.size + 1).withYieldAllowed(false).build()
        base.membership.forEach { ops += assertRow(eventUri(requireNotNull(it.meetingLong("_id"))), it) }
        ops += ContentProviderOperation.newAssertQuery(CalendarContract.Events.CONTENT_URI).withSelection(absence.first, absence.second).withValue("calendar_id", selected.calendarId.value).withExpectedCount(1).withYieldAllowed(false).build()
        return ops to insertion
    }

    private fun assertExistingChildren(ops: ArrayList<ContentProviderOperation>, id: Long, attendees: List<Map<String, String?>>, reminders: List<Map<String, String?>>) {
        for ((uri, rows) in listOf(CalendarContract.Attendees.CONTENT_URI to attendees, CalendarContract.Reminders.CONTENT_URI to reminders)) {
            ops += ContentProviderOperation.newAssertQuery(uri).withSelection("event_id = ?", arrayOf(id.toString())).withValue("event_id", id).withExpectedCount(rows.size).withYieldAllowed(false).build()
            rows.forEach { ops += assertRow(ContentUris.withAppendedId(uri, requireNotNull(it.meetingLong("_id"))), it) }
        }
        ops += ContentProviderOperation.newAssertQuery(CalendarContract.ExtendedProperties.CONTENT_URI).withSelection("event_id = ?", arrayOf(id.toString())).withValue("event_id", id).withExpectedCount(0).withYieldAllowed(false).build()
    }
    private suspend fun verifyEvent(id: Long, expected: Map<String, String?>): Boolean {
        val fields = expected.keys.toTypedArray()
        val actual = try { rows(eventUri(id), fields, maxRows = 1).singleOrNull() }
        catch (_: IllegalArgumentException) { rows(eventUri(id), fields.filter { it != "uid2445" }.toTypedArray(), maxRows = 1).singleOrNull() } ?: return false
        return expected.all { (key, value) -> actual[key] == value }
    }
    private suspend fun verifyChildren(id: Long, attendees: List<Map<String, String?>>, reminders: List<Map<String, String?>>, cloned: Boolean): Boolean {
        for ((uri, expected) in listOf(CalendarContract.Attendees.CONTENT_URI to attendees, CalendarContract.Reminders.CONTENT_URI to reminders)) {
            val fields = if (uri == CalendarContract.Attendees.CONTENT_URI) AttendeeFields else ReminderFields
            val where = if (uri == CalendarContract.Attendees.CONTENT_URI) "event_id = ? AND guestsCanSeeGuests = ?" else "event_id = ?"
            val args = if (uri == CalendarContract.Attendees.CONTENT_URI) arrayOf(id.toString(), "1") else arrayOf(id.toString())
            val actual = rows(uri, fields, where, args, if (uri == CalendarContract.Attendees.CONTENT_URI) MaxGuests else readBudgets.maxSeriesChildren)
            if (actual.size != expected.size) return false
            val remaining = actual.toMutableList()
            for (source in expected) { val compared = if (cloned) source.filterKeys { it !in ChildIds } else source
                val index = remaining.indexOfFirst { row -> row["event_id"] == id.toString() && compared.all { (key, value) -> row[key] == value } }
                if (index < 0) return false; remaining.removeAt(index)
            }
        }
        return !exists(CalendarContract.ExtendedProperties.CONTENT_URI, "event_id = ?", arrayOf(id.toString()))
    }
    private suspend fun verifyMembership(base: MeetingSeriesSource, id: Long, sync: String?): Boolean {
        val filter = CalendarMeetingSeriesReader.membershipFilter(base.selected.calendarId, base.masterId, base.master["_sync_id"]?.takeIf(String::isNotBlank))
        val current = rows(CalendarContract.Events.CONTENT_URI, CalendarMeetingSeriesReader.MembershipFields, filter.first, filter.second, planningBudget.maxExceptions + 2)
        if (current.size != base.membership.size + 1 || base.membership.any { old -> current.singleOrNull { it["_id"] == old["_id"] } != old }) return false
        val row = current.singleOrNull { it["_id"] == id.toString() } ?: return false
        return row["calendar_id"] == base.selected.calendarId.value.toString() && row["deleted"] == "0" && row["_sync_id"]?.takeIf(String::isNotBlank) == sync &&
            row["original_id"] == base.masterId.toString() && row["original_sync_id"]?.takeIf(String::isNotBlank) == base.master["_sync_id"]?.takeIf(String::isNotBlank) && row["originalInstanceTime"] == base.selected.recurrence!!.originalStart.toEpochMilli().toString() && row["originalAllDay"] == base.master["allDay"]
    }
    private fun proof(operation: CalendarLocalStore.Operation): Pair<JSONObject, MeetingGeneratedSource>? = try {
        require(operation.kind == Kind)
        val receipt = JSONObject(operation.resultRef ?: error("Missing preparation"))
        require(receipt.getInt("version") == 1 && receipt.getString("feature") == Feature && receipt.getString("kind") == Kind && receipt.getString("signature") == operation.signature)
        val raw = receipt.getJSONObject("source").toString(); val command = receipt.getJSONObject("command")
        val source = MeetingGeneratedSource.decode(raw, readBudgets.maxCanonicalBytes)
        val token = EditToken(command.getString("token"))
        require(MeetingGeneratedSource.token(raw) == token.value && meetingParseRef(command.getJSONObject("selected")) == source.base.selected)
        val response = MeetingCommand.Respond(RecurrenceScope.valueOf(command.getString("scope")), MeetingResponse.valueOf(command.getString("response")))
        require(fingerprint(source.base.selected, token, response) == (Kind to operation.signature))
        require(receipt.getInt("status") == CalendarMeetingWriter.responseStatus(response.response)); profile(source)
        if (operation.phase in setOf("PREPARED", "FAILED")) require(receipt.getString("stage") == "prepared" && !receipt.has("event"))
        else { require(operation.phase in setOf("COMMITTED", "VERIFIED") && receipt.getString("stage") == "committed"); require(receipt.getLong("event") > 0 && source.base.membership.none { it.meetingLong("_id") == receipt.getLong("event") }) }
        receipt to source
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }
    private fun keyFilter(base: MeetingSeriesSource): Pair<String, Array<String>> {
        val original = base.selected.recurrence!!.originalStart.toEpochMilli().toString(); val sync = base.master["_sync_id"]?.takeIf(String::isNotBlank)
        return ("calendar_id = ? AND (original_id = ?" + (if (sync == null) ")" else " OR original_sync_id = ?)") + " AND originalInstanceTime = ?") to
            (listOf(base.selected.calendarId.value.toString(), base.masterId.toString()) + listOfNotNull(sync) + original).toTypedArray()
    }
    private suspend fun countAbove(uri: Uri, where: String, args: Array<String>, limit: Int): Boolean = cursor(uri, arrayOf("event_id"), where, args).bounded(limit + 1).use { c ->
        var count = 0; while (c.moveToNext()) if (++count > limit) return@use true; false
    }
    private suspend fun exists(uri: Uri, where: String, args: Array<String>) = cursor(uri, arrayOf("event_id"), where, args).bounded(1).use { it.moveToFirst() }
    private suspend fun rows(uri: Uri, fields: Array<String>?, where: String? = null, args: Array<out String?>? = null, maxRows: Int,
        audited: Set<String>? = null, attendeeAliases: Boolean = false): List<Map<String, String?>> = cursor(uri, fields, where, args).bounded(maxRows).use { c ->
        val columns = c.columnNames.filterNot { attendeeAliases && SeriesAttendeeColumns.isJoinedAlias(it) }
        buildList { while (c.moveToNext()) { currentCoroutineContext().ensureActive()
            if (audited != null && columns.any { it !in audited && !c.isNull(c.getColumnIndexOrThrow(it)) }) throw Review("Provider-specific child metadata requires review")
            add(columns.filter { audited == null || it in audited }.associateWith { field -> c.getColumnIndexOrThrow(field).let { i -> if (c.isNull(i)) null else c.getString(i) } })
        } }
    }
    private suspend fun cursor(uri: Uri, fields: Array<String>?, where: String?, args: Array<out String?>?): Cursor {
        ensureRead()
        return suspendCancellableCoroutine { continuation -> val signal = CancellationSignal(); continuation.invokeOnCancellation { signal.cancel() }
            try { val cursor = resolver.query(uri, fields, where, args, "_id ASC", signal) ?: error("Provider unavailable")
                try { ensureRead() } catch (failure: Exception) { cursor.close(); throw failure }
                continuation.resume(cursor, onCancellation = { _, resource, _ -> resource.close() })
            } catch (failure: Exception) { continuation.resumeWithException(failure) }
        }
    }
    private fun ensureRead() { if (!hasPermission(Manifest.permission.READ_CALENDAR)) throw SecurityException() }
    private fun permissions() = hasPermission(Manifest.permission.READ_CALENDAR) && hasPermission(Manifest.permission.WRITE_CALENDAR)
    companion object {
        private const val Kind = "MEETING_SERIES_V1_THIS_RESPOND_GENERATED"
        private const val Feature = "MEETING_GENERATED_RESPONSE_V1"
        private const val CommandFeature = "MEETING_SERIES_COMMAND_V1"
        private const val MaxGuests = 200
        private val ChildIds = setOf("_id", "event_id")
        private val AttendeeCopy = setOf("attendeeName", "attendeeEmail", "attendeeStatus", "attendeeRelationship", "attendeeType", "attendeeIdentity", "attendeeIdNamespace")
        private val ReminderCopy = setOf("minutes", "method")
        private val AttendeeFields = (ChildIds + AttendeeCopy).toTypedArray()
        private val ReminderFields = (ChildIds + ReminderCopy).toTypedArray()
        private val Bookkeeping = setOf("dirty", "mutators", "lastDate", "lastSynced") // Provider expansion/sync caches; actual Instances prove interval.
        private val Cleared = setOf("_sync_id", "rrule", "rdate", "exrule", "exdate", "duration") + (1..10).map { "sync_data$it" }
        private val KnownMaster = Bookkeeping + Cleared + (1..10).map { "cal_sync$it" } + setOf("_id", "displayColor", "ownerAccount", "calendar_access_level", "calendar_color", "calendar_color_index", "calendar_displayName", "calendar_timezone", "visible", "allowedReminders", "allowedAttendeeTypes", "allowedAvailability", "maxReminders", "canOrganizerRespond", "canModifyTimeZone", "isPrimary")
        private val InstanceFields = arrayOf("event_id", "calendar_id", "begin", "end", "allDay", "original_id", "original_sync_id", "originalInstanceTime")
        fun handles(kind: String) = kind == Kind
        fun fingerprint(selected: EventRef, token: EditToken, command: MeetingCommand.Respond): Pair<String, String> {
            require(command.scope == RecurrenceScope.THIS && selected.eventId == selected.recurrence?.masterEventId)
            return Kind to meetingHash(CommandFeature + CalendarMeetingSeriesWriter.frozenCommandJson(selected, token, command).toString())
        }
        private fun common(result: MutationResult) = MeetingMutationResult.Common(result)
        private fun values(row: Map<String, String?>) = ContentValues().apply { row.forEach { (key, value) -> if (value == null) putNull(key) else put(key, value) } }
        private fun eventUri(id: Long) = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
        private fun assertRow(uri: Uri, row: Map<String, String?>) = ContentProviderOperation.newAssertQuery(uri).withValues(values(row)).withExpectedCount(1).withYieldAllowed(false).build()
        private fun instanceUri(ref: EventRef) = CalendarContract.Instances.CONTENT_URI.buildUpon().apply { ContentUris.appendId(this, ref.instanceStart.toEpochMilli()); ContentUris.appendId(this, Math.addExact(ref.instanceStart.toEpochMilli(), 1)) }.build()
        private fun nativeMatches(row: Map<String, String?>, ref: EventRef, allDay: String?) = row["event_id"] == ref.eventId.toString() && row["calendar_id"] == ref.calendarId.value.toString() && row["begin"] == ref.instanceStart.toEpochMilli().toString() && row["end"] == ref.instanceEnd.toEpochMilli().toString() && row["allDay"] == allDay &&
            (if (ref.eventId == ref.recurrence?.masterEventId) row["original_id"].isNullOrBlank() && row["original_sync_id"].isNullOrBlank() else row["original_id"] == ref.recurrence?.masterEventId.toString() && row["originalInstanceTime"] == ref.recurrence?.originalStart?.toEpochMilli().toString())
        private fun sameNamespace(old: Map<String, String?>, current: Map<String, String?>) = meetingIdentity(old) == meetingIdentity(current) && (current["calendar_access_level"]?.toIntOrNull() ?: -1) >= CalendarContract.Calendars.CAL_ACCESS_READ
        private fun checkParcel(ops: List<ContentProviderOperation>) { val p = Parcel.obtain(); try { p.writeTypedList(ops); if (p.dataSize() > 256 * 1024) throw Review("The complete response transaction exceeds the safe local size budget") } finally { p.recycle() } }
        private fun JSONObject.nullable(key: String) = if (isNull(key)) null else getString(key)
        internal fun exceptionId(uri: Uri?, base: MeetingSeriesSource): Long = CalendarExceptionAcknowledgement.read(
            uri, base.masterId, base.membership.map { requireNotNull(it.meetingLong("_id")) }.toSet())
    }
}
