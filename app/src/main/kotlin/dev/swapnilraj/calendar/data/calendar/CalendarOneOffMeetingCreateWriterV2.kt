package dev.swapnilraj.calendar.data.calendar

import android.Manifest
import android.content.*
import android.net.Uri
import android.os.Parcel
import android.provider.CalendarContract
import dev.swapnilraj.calendar.core.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** New receipts only. Existing receipt 1 retains its original executor and recovery contract. */
internal class CalendarOneOffMeetingCreateWriterV2(
    private val resolver: ContentResolver,
    private val reader: CalendarReader,
    private val store: CalendarLocalStore,
    private val permission: (String) -> Boolean,
    private val owner: CalendarMeetingCreateWriter,
    private val budgets: ProviderReadBudgets,
) {
    private val mutex = Mutex()
    private val provider = OwnedMeetingProvider(resolver, permission, budgets)
    private class Conflict : Exception()
    private data class Batch(val operations: ArrayList<ContentProviderOperation>, val event: Int, val attendees: List<Int>, val reminders: List<Int>)

    fun handles(operation: CalendarLocalStore.Operation): Boolean = try {
        operation.kind == MeetingOneOffCreationReceipt.Kind && JSONObject(operation.resultRef ?: "{}").let {
            it.optInt("version") == 2 && it.optString("feature") == MeetingOneOffCreationReceipt.Feature
        }
    } catch (_: Exception) { false }

    suspend fun create(id: String, command: JSONObject, signature: String, existing: CalendarLocalStore.Operation?): MeetingMutationResult = mutex.withLock {
        var attempted: CalendarLocalStore.Operation? = null
        var knownId: Long? = null
        try {
            if (!permissions()) return@withLock common(MutationResult.PermissionRequired)
            val prepared = if (existing != null) {
                if (existing.kind != MeetingOneOffCreationReceipt.Kind || existing.signature != signature) return@withLock common(MutationResult.Conflict(null))
                if (existing.phase !in setOf("PREPARED", "FAILED")) return@withLock recover(existing, false)
                proof(existing)?.second ?: return@withLock common(MutationResult.Uncertain(id, null))
            } else {
                val token = EditToken(command.getString("token"))
                val draft = CalendarMeetingCreateWriter.oneOffDraft(command.getJSONObject("draft"))
                val guests = CalendarMeetingCreateWriter.oneOffGuests(command.getJSONArray("guests"))
                val (raw, calendar) = owner.issuedOneOffSource(token)
                if (draft.targetIdentity != meetingIdentity(calendar) || draft.calendarId != meetingIdentity(calendar).calendarId) throw Conflict()
                val values = owner.initialOneOffPayload(calendar, draft, guests)
                val plan = MeetingOneOffFrozenPlan.freeze(values.first, values.second, draft, UUID.randomUUID().toString() + "@daylight.invalid")
                plan.validate(draft, meetingIdentity(calendar), guests)
                if (plan.reminders.size > minOf(budgets.maxSeriesChildren, 4000)) throw ProviderReadLimitExceeded()
                MeetingOneOffCreationReceipt(command, raw, calendar, draft, plan)
            }
            if (prepared.command.toString() != command.toString()) return@withLock common(MutationResult.Conflict(null))
            currentSource(prepared)
            val metadata = prepared.json(signature)
            preflight(prepared, metadata)
            checkParcel(batch(prepared).operations)
            if (!permissions()) return@withLock common(MutationResult.PermissionRequired)
            currentCoroutineContext().ensureActive()
            store.prepare(id, signature, MeetingOneOffCreationReceipt.Kind, metadata.toString())
            val persisted = store.operation(id) ?: error("Journal unavailable")
            if (persisted.kind != MeetingOneOffCreationReceipt.Kind || persisted.signature != signature) return@withLock common(MutationResult.Conflict(null))
            if (persisted.phase == "ABANDONED") return@withLock common(MutationResult.Retired(id))
            if (persisted.phase !in setOf("PREPARED", "FAILED")) return@withLock recover(persisted, false)
            // Another helper may have prepared the same command with another proposed UID. Only
            // its immutable winning receipt is authoritative, including on an explicit retry.
            val winner = proof(persisted)?.second ?: return@withLock common(MutationResult.Uncertain(id, null))
            if (winner.command.toString() != command.toString() || winner.sourceRaw != prepared.sourceRaw) return@withLock common(MutationResult.Conflict(null))
            val winningMetadata = winner.json(signature)
            preflight(winner, winningMetadata)
            val sent = batch(winner); checkParcel(sent.operations)
            currentSource(winner)
            if (!permissions()) return@withLock common(MutationResult.PermissionRequired)
            currentCoroutineContext().ensureActive()
            if (!store.attempt(persisted)) return@withLock recover(store.operation(id)!!, false)
            attempted = persisted.copy(phase = "ATTEMPTED")
            if (!permissions()) {
                check(store.mark(attempted, "FAILED"))
                return@withLock common(MutationResult.PermissionRequired)
            }
            currentCoroutineContext().ensureActive()
            val results = resolver.applyBatch(CalendarContract.AUTHORITY, sent.operations)
            knownId = inserted(results.getOrNull(sent.event)?.uri, "events")
            val committed = JSONObject(winningMetadata.toString()).put("stage", "committed").put("event", knownId)
            val children = try {
                require(results.size == sent.operations.size)
                val attendees = sent.attendees.map { inserted(results.getOrNull(it)?.uri, "attendees") }
                val reminders = sent.reminders.map { inserted(results.getOrNull(it)?.uri, "reminders") }
                require(attendees.distinct().size == attendees.size && reminders.distinct().size == reminders.size)
                attendees to reminders
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
            if (children == null) {
                committed.put("ackComplete", false)
                check(store.mark(attempted, "COMMITTED", committed.toString()))
                return@withLock common(MutationResult.Uncertain(id, knownId))
            }
            committed.put("ackComplete", true).put("attendees", JSONArray(children.first)).put("reminders", JSONArray(children.second))
            check(store.mark(attempted, "COMMITTED", committed.toString()))
            recover(store.operation(id)!!, false)
        } catch (_: SecurityException) { common(if (attempted == null) MutationResult.PermissionRequired else MutationResult.Uncertain(id, knownId)) }
        catch (_: Conflict) { common(if (attempted == null) MutationResult.Conflict(null) else MutationResult.Uncertain(id, knownId)) }
        catch (_: ProviderReadLimitExceeded) { if (attempted == null) MeetingMutationResult.NeedsReview("The complete meeting exceeds its read or storage budget") else common(MutationResult.Uncertain(id, knownId)) }
        catch (invalid: CalendarWriter.Invalid) { if (attempted == null) common(MutationResult.ValidationFailed(invalid.fields)) else common(MutationResult.Uncertain(id, knownId)) }
        catch (invalid: CalendarMeetingCreateWriter.Invalid) { if (attempted == null) MeetingMutationResult.Invalid(invalid.reason) else common(MutationResult.Uncertain(id, knownId)) }
        catch (review: CalendarMeetingCreateWriter.Review) { if (attempted == null) MeetingMutationResult.NeedsReview(review.reason) else common(MutationResult.Uncertain(id, knownId)) }
        catch (denied: CalendarMeetingCreateWriter.Denied) { common(if (attempted == null) MutationResult.Denied(denied.reason) else MutationResult.Uncertain(id, knownId)) }
        catch (_: OperationApplicationException) {
            if (attempted == null) common(MutationResult.Unavailable) else try {
                check(store.mark(attempted, "FAILED")); common(MutationResult.Unavailable)
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { common(MutationResult.Uncertain(id, knownId)) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { common(if (attempted == null) MutationResult.Unavailable else MutationResult.Uncertain(id, knownId)) }
    }

    private suspend fun currentSource(receipt: MeetingOneOffCreationReceipt) {
        provider.ensureRead()
        val actual = owner.currentCreationCalendar(receipt.draft.calendarId)
        if (CalendarMeetingCreateWriter.legacySourceRaw(actual) != CalendarMeetingCreateWriter.legacySourceRaw(receipt.calendar)) throw Conflict()
    }

    private fun preflight(receipt: MeetingOneOffCreationReceipt, prepared: JSONObject) {
        checkStoredJson(prepared.toString(), budgets.maxCanonicalBytes)
        val complete = JSONObject(prepared.toString()).put("stage", "committed").put("event", Long.MAX_VALUE).put("ackComplete", true)
            .put("attendees", JSONArray(List(receipt.plan.attendees.size) { Long.MAX_VALUE }))
            .put("reminders", JSONArray(List(receipt.plan.reminders.size) { Long.MAX_VALUE }))
            .put("firstRef", meetingRefJson(receipt.plan.ref(receipt.draft.calendarId, Long.MAX_VALUE)))
            .put("_sync_id", "\u0000".repeat(budgets.mutation.maxCellUnits))
        checkStoredJson(complete.toString(), budgets.maxCanonicalBytes)
        checkStoredJson(JSONObject(prepared.toString()).put("stage", "committed").put("event", Long.MAX_VALUE).put("ackComplete", false).toString(), budgets.maxCanonicalBytes)
    }

    private fun batch(receipt: MeetingOneOffCreationReceipt): Batch {
        val plan = receipt.plan
        val ops = arrayListOf(ContentProviderOperation.newAssertQuery(ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, receipt.draft.calendarId.value))
            .withValues(values(receipt.calendar)).withExpectedCount(1).withYieldAllowed(false).build())
        val event = ops.size
        ops += ContentProviderOperation.newInsert(CalendarContract.Events.CONTENT_URI).withValues(values(plan.event)).withYieldAllowed(false).build()
        fun insert(uri: Uri, rows: List<Map<String, String?>>): List<Int> = rows.map { row ->
            ops.size.also { ops += ContentProviderOperation.newInsert(uri).withValues(values(row)).withValueBackReference("event_id", event).withYieldAllowed(false).build() }
        }
        val attendees = insert(CalendarContract.Attendees.CONTENT_URI, plan.attendees)
        val reminders = insert(CalendarContract.Reminders.CONTENT_URI, plan.reminders)
        val expected = plan.event + mapOf("deleted" to "0", "isOrganizer" to "1", "selfAttendeeStatus" to "1", "hasAlarm" to if (plan.reminders.isEmpty()) "0" else "1") + NonRecurring.associateWith { null }
        ops += ContentProviderOperation.newAssertQuery(CalendarContract.Events.CONTENT_URI).withSelection("_id = ?", arrayOf("0"))
            .withSelectionBackReference(0, event).withValues(values(expected)).withValueBackReference("_id", event).withExpectedCount(1).withYieldAllowed(false).build()
        for ((uri, rows, indices) in listOf(Triple(CalendarContract.Attendees.CONTENT_URI, plan.attendees, attendees), Triple(CalendarContract.Reminders.CONTENT_URI, plan.reminders, reminders))) {
            ops += ContentProviderOperation.newAssertQuery(uri).withSelection("event_id = ?", arrayOf("0")).withSelectionBackReference(0, event)
                .withValueBackReference("event_id", event).withExpectedCount(rows.size).withYieldAllowed(false).build()
            rows.forEachIndexed { index, row ->
                ops += ContentProviderOperation.newAssertQuery(uri).withSelection("_id = ? AND event_id = ?", arrayOf("0", "0"))
                    .withSelectionBackReference(0, indices[index]).withSelectionBackReference(1, event).withValues(values(row))
                    .withValueBackReference("event_id", event).withExpectedCount(1).withYieldAllowed(false).build()
            }
        }
        return Batch(ops, event, attendees, reminders)
    }

    private fun proof(operation: CalendarLocalStore.Operation) = try {
        MeetingOneOffCreationReceipt.parse(operation, budgets.maxCanonicalBytes)
    } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }

    suspend fun recover(operation: CalendarLocalStore.Operation, retry: Boolean): MeetingMutationResult {
        if (operation.phase == "ABANDONED") return common(MutationResult.Retired(operation.id))
        val (j, receipt) = proof(operation) ?: return common(MutationResult.Uncertain(operation.id, null))
        if (operation.phase in setOf("PREPARED", "FAILED")) return common(if (retry) MutationResult.SafeToRetry(operation.id) else MutationResult.Unavailable)
        val id = j.getLong("event")
        fun uncertain() = common(MutationResult.Uncertain(operation.id, id))
        if (!j.getBoolean("ackComplete")) return uncertain()
        return try {
            val target = meetingIdentity(receipt.calendar); val plan = receipt.plan
            suspend fun namespace(): Boolean {
                val actual = owner.currentCreationCalendar(target.calendarId)
                return meetingIdentity(actual) == target && (actual["calendar_access_level"]?.toIntOrNull() ?: -1) >= CalendarContract.Calendars.CAL_ACCESS_READ
            }
            if (!namespace()) return uncertain()
            val expected = plan.event + mapOf("_id" to id.toString(), "deleted" to "0", "isOrganizer" to "1", "selfAttendeeStatus" to "1", "hasAlarm" to if (plan.reminders.isEmpty()) "0" else "1")
            val fields = (expected.keys + NonRecurring + "_sync_id").toTypedArray()
            suspend fun parent(): Map<String, String?>? {
                val row = provider.rows(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), fields, maxRows = 1).singleOrNull() ?: return null
                if (expected.any { (key, value) -> row[key] != value } || NonRecurring.any { row[it] != null }) return null
                return row
            }
            val first = parent() ?: return uncertain()
            val knownSync = if (j.isNull("_sync_id")) null else j.getString("_sync_id").takeIf(String::isNotBlank)
            val firstSync = first["_sync_id"]?.takeIf(String::isNotBlank)
            if (knownSync != null && knownSync != firstSync) return uncertain()
            val guard = "event_id = ? AND calendar_id = ? AND organizer = ? AND uid2445 = ?"
            val args = arrayOf(id.toString(), target.calendarId.value.toString(), target.owner, plan.uid)
            val attendeeIds = j.getJSONArray("attendees").ids()
            val reminderIds = j.getJSONArray("reminders").ids()
            val guests = provider.rows(CalendarContract.Attendees.CONTENT_URI, CalendarMeetingWriter.attendeeFields, guard, args, plan.attendees.size + 1)
            if (guests.size != plan.attendees.size) return uncertain()
            plan.attendees.forEachIndexed { index, expectedRow ->
                val row = guests.singleOrNull { it["_id"] == attendeeIds[index].toString() } ?: return uncertain()
                if (row["event_id"] != id.toString() || expectedRow.any { (key, value) -> row[key] != value } || !row["attendeeIdentity"].isNullOrBlank() || !row["attendeeIdNamespace"].isNullOrBlank()) return uncertain()
            }
            suspend fun membership(): Boolean {
                val rows = provider.rows(CalendarContract.Reminders.CONTENT_URI, arrayOf("_id", "event_id"), "event_id = ?", arrayOf(id.toString()), minOf(budgets.maxSeriesChildren, reminderIds.size + 1))
                val ids = rows.map { it["_id"]?.toLongOrNull() ?: return false }
                return rows.all { it["event_id"] == id.toString() } && ids.all { it > 0 } && ids.size == reminderIds.size && ids.toSet().size == ids.size && ids.toSet() == reminderIds.toSet()
            }
            if (!membership()) return uncertain()
            plan.reminders.forEachIndexed { index, expectedRow ->
                val rowId = reminderIds[index]
                val row = provider.rows(ContentUris.withAppendedId(CalendarContract.Reminders.CONTENT_URI, rowId), ReminderFields, guard, args, 1).singleOrNull() ?: return uncertain()
                if (row["_id"] != rowId.toString() || row["event_id"] != id.toString() || expectedRow.any { (key, value) -> row[key] != value }) return uncertain()
            }
            val ref = plan.ref(target.calendarId, id)
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
                ContentUris.appendId(this, ref.instanceStart.toEpochMilli()); ContentUris.appendId(this, Math.addExact(ref.instanceStart.toEpochMilli(), 1))
            }.build()
            val instance = provider.rows(uri, InstanceFields, "event_id = ? AND calendar_id = ? AND begin = ?", arrayOf(id.toString(), target.calendarId.value.toString(), ref.instanceStart.toEpochMilli().toString()), 1).singleOrNull() ?: return uncertain()
            if (instance["event_id"] != id.toString() || instance["calendar_id"] != target.calendarId.value.toString() || instance["begin"] != ref.instanceStart.toEpochMilli().toString() ||
                instance["end"] != ref.instanceEnd.toEpochMilli().toString() || instance["allDay"] != plan.event["allDay"] || listOf("original_id", "original_sync_id", "originalInstanceTime").any { !instance[it].isNullOrBlank() }) return uncertain()
            if (!membership()) return uncertain()
            val finalParent = parent() ?: return uncertain()
            val finalSync = finalParent["_sync_id"]?.takeIf(String::isNotBlank)
            val observedSync = knownSync ?: firstSync
            if (observedSync != null && observedSync != finalSync) return uncertain()
            if (!namespace()) return uncertain()
            provider.ensureRead()
            val verified = JSONObject(j.toString()).put("_sync_id", observedSync ?: finalSync ?: JSONObject.NULL).put("firstRef", meetingRefJson(ref))
            check(store.mark(operation, "VERIFIED", verified.toString())); reader.refresh()
            MeetingMutationResult.LocalSaved(ref, RecurrenceScope.THIS)
        } catch (security: SecurityException) { throw security }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { uncertain() }
    }

    private fun permissions() = permission(Manifest.permission.READ_CALENDAR) && permission(Manifest.permission.WRITE_CALENDAR)
    private fun checkParcel(operations: List<ContentProviderOperation>) {
        val parcel = Parcel.obtain()
        try { parcel.writeTypedList(operations); if (parcel.dataSize() > 256 * 1024) throw CalendarMeetingCreateWriter.Review("The complete meeting transaction exceeds its safe size budget") }
        finally { parcel.recycle() }
    }
    companion object {
        private val NonRecurring = listOf("rrule", "rdate", "exrule", "exdate", "original_id", "original_sync_id", "originalInstanceTime", "originalAllDay")
        private val ReminderFields = arrayOf("_id", "event_id", "minutes", "method")
        private val InstanceFields = arrayOf("event_id", "calendar_id", "begin", "end", "allDay", "original_id", "original_sync_id", "originalInstanceTime")
        private fun common(result: MutationResult) = MeetingMutationResult.Common(result)
        private fun values(row: Map<String, String?>) = ContentValues().apply { row.forEach { (key, value) -> if (value == null) putNull(key) else put(key, value) } }
        private fun JSONArray.ids() = List(length()) { getLong(it) }
        private fun inserted(uri: Uri?, table: String): Long {
            require(uri != null && uri.scheme == "content" && uri.authority == CalendarContract.AUTHORITY && uri.encodedQuery == null && uri.encodedFragment == null)
            val segments = uri.pathSegments; require(segments.size == 2 && segments[0] == table)
            val id = segments[1].toLong(); require(id > 0 && uri.encodedPath == "/$table/$id"); return id
        }
    }
}
