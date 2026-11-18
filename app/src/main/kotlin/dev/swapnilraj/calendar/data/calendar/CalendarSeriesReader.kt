package dev.swapnilraj.calendar.data.calendar

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.provider.CalendarContract
import dev.swapnilraj.calendar.core.*
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** Complete local-provider source, independent of visible ranges. No provider writes occur here. */
class CalendarSeriesReader(
    private val resolver: ContentResolver,
    private val reader: CalendarReader,
    private val store: CalendarLocalStore,
    private val hasPermission: (String) -> Boolean,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val budget: RecurrencePlanningBudget = RecurrencePlanningBudget(),
) : CalendarSeriesReadPort {
    override suspend fun loadForEdit(selected: EventRef): SeriesReadResult = withContext(io) {
        try {
            val before = source(selected)
            if (before.canonical() != source(selected).canonical()) return@withContext SeriesReadResult.Unavailable
            ensureRead()
            val token = EditToken(MessageDigest.getInstance("SHA-256").digest(("SERIES_EDIT_V1" + before.canonical())
                .toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) })
            store.saveEdit(token.value, before.canonical())
            SeriesReadResult.Ready(EditableSeries(before.selectedDetails, before.masterFields, before.snapshot, token, before.allowedFields))
        } catch (_: SecurityException) { SeriesReadResult.PermissionRequired }
        catch (denied: SeriesSourceDenied) { SeriesReadResult.Denied(denied.reason) }
        catch (review: SeriesSourceReview) { SeriesReadResult.NeedsReview(review.reason) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { SeriesReadResult.Unavailable }
    }

    /** A12 must compare the exact selected-bound source, recompute its plan, then assert these rows. */
    internal suspend fun checkedSource(selected: EventRef, token: EditToken): CalendarSeriesSource = withContext(io) {
        ensureRead()
        val expected = store.edit(token.value) ?: throw SeriesSourceConflict()
        source(selected).also { if (it.canonical() != expected) throw SeriesSourceConflict() }
    }

    internal fun refresh() { reader.refresh() }

    internal suspend fun selectedReference(ref: EventRef): EventRef? = withContext(io) {
        ensureRead()
        when (val result = reader.readDetails(ref)) {
            is CalendarReadState.Ready -> result.value.occurrence.ref.takeIf { it == ref }
            CalendarReadState.PermissionRequired -> throw SecurityException()
            else -> null
        }.also { ensureRead() }
    }

    internal suspend fun source(selected: EventRef): CalendarSeriesSource = withContext(io) {
        ensureRead()
        val recurrence = selected.recurrence ?: throw SeriesSourceDenied(MutationDenial.RECURRENCE)
        val identity = SeriesIdentity(selected.calendarId, recurrence.masterEventId)
        val calendar = rows(ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, selected.calendarId.value), maxRows = 1).singleOrNull()
            ?: throw SeriesSourceConflict()
        if (calendar.long(CalendarContract.Calendars._ID) != selected.calendarId.value) throw SeriesSourceConflict()
        writable(calendar)
        val masterIdentity = rows(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, identity.masterEventId), maxRows = 1,
            fields = arrayOf("_id", "calendar_id", "_sync_id", "original_id", "original_sync_id", "deleted")).singleOrNull()
            ?: throw SeriesSourceConflict()
        if (masterIdentity.long("calendar_id") != identity.calendarId.value || masterIdentity["deleted"] != "0" ||
            !masterIdentity["original_id"].isNullOrBlank() || !masterIdentity["original_sync_id"].isNullOrBlank())
            throw SeriesSourceReview("Invalid master identity")
        val syncId = masterIdentity[CalendarContract.Events._SYNC_ID]?.takeIf { it.isNotBlank() }
        if (syncId == null && rows(CalendarContract.Events.CONTENT_URI,
                "calendar_id = ? AND original_id IS NULL AND original_sync_id IS NOT NULL", arrayOf(identity.calendarId.value.toString()),
                budget.maxExceptions + 1, arrayOf("_id", "calendar_id", "original_id", "original_sync_id", "deleted"))
                .any { it["deleted"] != "1" && !it["original_sync_id"].isNullOrBlank() })
            throw SeriesSourceReview("Sync-linked exception coverage cannot be proven without a master sync identity")
        val membershipSelection = "(_id = ? OR original_id = ?" + if (syncId == null) ")" else " OR (calendar_id = ? AND original_sync_id = ?))"
        val membershipArgs = listOf(identity.masterEventId.toString(), identity.masterEventId.toString()) +
            if (syncId == null) emptyList() else listOf(identity.calendarId.value.toString(), syncId)
        // Read the complete graph, not Instances or display-filtered rows. Unknown provider columns
        // are retained privately for assertions and preserved by field-only master/exception updates.
        val membership = rows(CalendarContract.Events.CONTENT_URI, membershipSelection,
            membershipArgs.toTypedArray(), budget.maxExceptions + 1,
            arrayOf("_id", "calendar_id", "_sync_id", "original_id", "original_sync_id", "deleted"))
        if (membership.any { it["deleted"] !in setOf("0", "1") }) throw SeriesSourceReview("Unknown deletion state")
        if (membership.any { it["deleted"] == "0" && it.long("calendar_id") != identity.calendarId.value })
            throw SeriesSourceReview("Exception calendar identity changed")
        val graphSelection = "calendar_id = ? AND (_id = ? OR original_id = ?" + if (syncId == null) ")" else " OR original_sync_id = ?)"
        val graphArgs = listOf(identity.calendarId.value.toString(), identity.masterEventId.toString(), identity.masterEventId.toString()) +
            if (syncId == null) emptyList() else listOf(syncId)
        val graph = rows(CalendarContract.Events.CONTENT_URI, graphSelection, graphArgs.toTypedArray(), budget.maxExceptions + 1)
        if (membership.filter { it["deleted"] == "0" }.map { it.long("_id") }.toSet() !=
            graph.filter { it["deleted"] == "0" }.map { it.long("_id") }.toSet()) throw SeriesSourceConflict()
        if (graph.any { it[CalendarContract.Events.DELETED] !in setOf("0", "1") }) throw SeriesSourceReview("Unknown deletion state")
        val master = graph.singleOrNull { it.long(CalendarContract.Events._ID) == identity.masterEventId && it[CalendarContract.Events.DELETED] == "0" }
            ?: throw SeriesSourceConflict()
        if (master[CalendarContract.Events._SYNC_ID]?.takeIf { it.isNotBlank() } != syncId) throw SeriesSourceConflict()
        if (master.long(CalendarContract.Events.CALENDAR_ID) != identity.calendarId.value || !master[CalendarContract.Events.ORIGINAL_ID].isNullOrBlank() ||
            !master[CalendarContract.Events.ORIGINAL_SYNC_ID].isNullOrBlank())
            throw SeriesSourceReview("Invalid master identity")
        if (master[CalendarContract.Events.RRULE].isNullOrBlank() && master[CalendarContract.Events.RDATE].isNullOrBlank())
            throw SeriesSourceDenied(MutationDenial.RECURRENCE)
        val exceptions = graph.filter { it.long(CalendarContract.Events._ID) != identity.masterEventId && it[CalendarContract.Events.DELETED] == "0" }
        if (exceptions.size > budget.maxExceptions) throw SeriesSourceReview("Exception coverage exceeds the planning budget")
        val live = listOf(master) + exceptions
        if (live.map { it.long(CalendarContract.Events._ID) }.toSet().size != live.size) throw SeriesSourceReview("Duplicate event identity")
        live.forEach { event ->
            if (event.long(CalendarContract.Events.CALENDAR_ID) != identity.calendarId.value) throw SeriesSourceReview("Exception calendar identity changed")
            policy(event, calendar, event !== master)
        }
        val start = masterStart(master)
        val masterAllDay = start is RecurrenceStart.AllDay
        val exceptionModels = exceptions.map { event ->
            val parentId = event.long(CalendarContract.Events.ORIGINAL_ID)
            val parentSync = event[CalendarContract.Events.ORIGINAL_SYNC_ID]?.takeIf { it.isNotBlank() }
            if ((parentId != null && parentId != identity.masterEventId) ||
                (parentSync != null && parentSync != syncId) || (parentId == null && parentSync == null) ||
                (!event[CalendarContract.Events.ORIGINAL_ID].isNullOrBlank() && parentId == null) ||
                event.flag(CalendarContract.Events.ORIGINAL_ALL_DAY) != masterAllDay) throw SeriesSourceReview("Exception original identity is incomplete")
            if (listOf(CalendarContract.Events.RRULE, CalendarContract.Events.RDATE, CalendarContract.Events.EXRULE, CalendarContract.Events.EXDATE)
                    .any { !event[it].isNullOrBlank() }) throw SeriesSourceReview("Nested exception recurrence is unsupported")
            val original = event.long(CalendarContract.Events.ORIGINAL_INSTANCE_TIME)?.let(Instant::ofEpochMilli)
                ?: throw SeriesSourceReview("Missing original occurrence time")
            val key = if (masterAllDay) {
                val date = original.atZone(ZoneOffset.UTC)
                if (date.toLocalTime() != java.time.LocalTime.MIDNIGHT) throw SeriesSourceReview("Original all-day identity is not UTC midnight")
                RecurrenceKey.Date(date.toLocalDate())
            } else RecurrenceKey.Timed(original)
            val actualStart = event.long(CalendarContract.Events.DTSTART)?.let(Instant::ofEpochMilli)
                ?: throw SeriesSourceReview("Missing exception start")
            val actualEnd = event.long(CalendarContract.Events.DTEND)?.let(Instant::ofEpochMilli)
                ?: throw SeriesSourceReview("Exception duration needs explicit end-time review")
            val ref = try { EventRef(identity.calendarId, event.long(CalendarContract.Events._ID) ?: error("Missing exception ID"), actualStart, actualEnd,
                RecurrenceRef(identity.masterEventId, original)) } catch (_: Exception) { throw SeriesSourceReview("Invalid exception time") }
            val allDay = event.flag(CalendarContract.Events.ALL_DAY) ?: throw SeriesSourceReview("Unknown exception time mode")
            try { if (allDay) EventTime.AllDay.fromProvider(actualStart, actualEnd) else EventTime.Timed(actualStart, actualEnd) }
            catch (_: Exception) { throw SeriesSourceReview("Invalid exception dates") }
            SeriesException(ref, key, event[CalendarContract.Events.STATUS]?.toIntOrNull() == CalendarContract.Events.STATUS_CANCELED)
        }
        if (exceptionModels.map { it.original }.toSet().size != exceptionModels.size) throw SeriesSourceReview("Ambiguous duplicate exception identity")
        // Every member passes visibility/ownership proof before ANY private attendee/reminder query.
        val ids = live.map { it.long(CalendarContract.Events._ID) ?: throw SeriesSourceReview("Missing event ID") }.toSet()
        val reminders = children(CalendarContract.Reminders.CONTENT_URI, ids)
        val attendees = children(CalendarContract.Attendees.CONTENT_URI, ids)
        attendees.values.flatten().forEach { attendee ->
            if (!attendee[CalendarContract.Attendees.ATTENDEE_EMAIL].orEmpty().equals(calendar[CalendarContract.Calendars.ACCOUNT_NAME], ignoreCase = true))
                throw SeriesSourceDenied(MutationDenial.INVITATION)
        }
        ensureRead()
        val details = when (val read = reader.readDetails(selected)) {
            is CalendarReadState.Ready -> read.value
            CalendarReadState.PermissionRequired -> throw SecurityException()
            else -> {
                val selectedRow = live.singleOrNull { it.long("_id") == selected.eventId }
                if (selectedRow?.long("original_id") == null && !selectedRow?.get("original_sync_id").isNullOrBlank())
                    throw SeriesSourceReview("Selected sync-linked occurrence identity cannot be proven")
                throw SeriesSourceConflict()
            }
        }
        ensureRead()
        if (details.occurrence.ref != selected) throw SeriesSourceConflict()
        if (selected.eventId == identity.masterEventId) {
            if (exceptionModels.any { it.original == if (masterAllDay) RecurrenceKey.Date(recurrence.originalStart.atZone(ZoneOffset.UTC).toLocalDate()) else RecurrenceKey.Timed(recurrence.originalStart) })
                throw SeriesSourceConflict()
            if (selected.instanceStart != recurrence.originalStart) throw SeriesSourceConflict()
        } else if (exceptionModels.none { it.ref == selected && !it.cancelled }) throw SeriesSourceConflict()
        val exDatesRaw = master[CalendarContract.Events.EXDATE]
        val decoded = RecurrenceDateCodec.parse(exDatesRaw, start, RecurrenceDateBudget(maxValues = budget.maxInstances),
            AllDayDateTimePolicy.PROVIDER_UTC_MIDNIGHT)
        val dates = decoded as? ParsedRecurrenceDates.Supported
        val validProperty = exDatesRaw == null || !Regex("(?im)^RDATE[:;]").containsMatchIn(exDatesRaw)
        val snapshot = RecurrenceSeriesSnapshot(identity, start, master[CalendarContract.Events.RRULE], master[CalendarContract.Events.DURATION],
            master[CalendarContract.Events.RDATE], master[CalendarContract.Events.EXRULE], master[CalendarContract.Events.EXDATE],
            exDates = if (validProperty) dates?.keys.orEmpty() else emptySet(), exDatesDecoded = validProperty && dates != null, exceptions = exceptionModels,
            coverage = SeriesCoverage.COMPLETE, validatedSelection = selected)
        val masterId = identity.masterEventId
        val masterFields = SeriesMasterFields(master[CalendarContract.Events.TITLE].orEmpty(), master[CalendarContract.Events.EVENT_LOCATION].orEmpty(),
            master[CalendarContract.Events.DESCRIPTION].orEmpty(), reminders[masterId].orEmpty().map {
                EventReminder(it[CalendarContract.Reminders.MINUTES]?.toIntOrNull() ?: throw SeriesSourceReview("Unknown reminder offset"),
                    it[CalendarContract.Reminders.METHOD]?.toIntOrNull() ?: throw SeriesSourceReview("Unknown reminder method"))
            }, master[CalendarContract.Events.AVAILABILITY]?.toIntOrNull(), master[CalendarContract.Events.ACCESS_LEVEL]?.toIntOrNull())
        val allowed = EditableField.entries.toSet().let {
            if (calendar[CalendarContract.Calendars.CAN_MODIFY_TIME_ZONE] == "1" && !masterAllDay) it else it - EditableField.ZONE
        }.let { if (knownAvailability(calendar)) it else it - EditableField.AVAILABILITY }
        CalendarSeriesSource(calendar, master, exceptions, reminders, attendees, details, masterFields, snapshot, allowed)
    }

    private fun writable(calendar: Map<String, String?>) {
        if (calendar[CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL]?.toIntOrNull() !in setOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR,
                CalendarContract.Calendars.CAL_ACCESS_EDITOR, CalendarContract.Calendars.CAL_ACCESS_OWNER, CalendarContract.Calendars.CAL_ACCESS_ROOT))
            throw SeriesSourceDenied(MutationDenial.CALENDAR_READ_ONLY)
    }
    private fun policy(event: Map<String, String?>, calendar: Map<String, String?>, exception: Boolean) {
        if (event[CalendarContract.Events.IS_ORGANIZER] != "1" && event[CalendarContract.Events.GUESTS_CAN_SEE_GUESTS] != "1")
            throw SeriesSourceDenied(MutationDenial.UNSUPPORTED_EVENT)
        val organizerFlag = event[CalendarContract.Events.IS_ORGANIZER]
        val actor = calendar[CalendarContract.Calendars.ACCOUNT_NAME]
        if (actor.isNullOrBlank() || !event[CalendarContract.Events.ORGANIZER].orEmpty().equals(actor, ignoreCase = true) ||
            organizerFlag !in setOf(null, "1")) throw SeriesSourceDenied(MutationDenial.OWNERSHIP_UNKNOWN)
        if (event[CalendarContract.Events.HAS_ATTENDEE_DATA] !in setOf("0", "1") ||
            event[CalendarContract.Events.HAS_ATTENDEE_DATA] == "1" && event[CalendarContract.Events.GUESTS_CAN_SEE_GUESTS] != "1")
            throw SeriesSourceDenied(MutationDenial.UNSUPPORTED_EVENT)
        val status = event[CalendarContract.Events.STATUS]?.toIntOrNull()
        if (status !in setOf(CalendarContract.Events.STATUS_TENTATIVE, CalendarContract.Events.STATUS_CONFIRMED, CalendarContract.Events.STATUS_CANCELED) ||
            (!exception && status == CalendarContract.Events.STATUS_CANCELED) || !event[CalendarContract.Events.CUSTOM_APP_PACKAGE].isNullOrBlank())
            throw SeriesSourceDenied(MutationDenial.UNSUPPORTED_EVENT)
        val zone = event[CalendarContract.Events.EVENT_TIMEZONE]
        if (!event[CalendarContract.Events.EVENT_END_TIMEZONE].isNullOrBlank() && event[CalendarContract.Events.EVENT_END_TIMEZONE] != zone)
            throw SeriesSourceDenied(MutationDenial.UNSUPPORTED_EVENT)
    }
    private fun masterStart(master: Map<String, String?>): RecurrenceStart = try {
        val instant = Instant.ofEpochMilli(master.long(CalendarContract.Events.DTSTART) ?: error("Missing DTSTART"))
        when (master.flag(CalendarContract.Events.ALL_DAY)) {
            true -> {
                val utc = instant.atZone(ZoneOffset.UTC)
                require(utc.toLocalTime() == java.time.LocalTime.MIDNIGHT)
                RecurrenceStart.AllDay(utc.toLocalDate())
            }
            false -> {
                val zoned = instant.atZone(ZoneId.of(master[CalendarContract.Events.EVENT_TIMEZONE] ?: error("Missing zone")))
                RecurrenceStart.Timed(zoned.toLocalDateTime(), zoned.zone, zoned.offset)
            }
            null -> error("Unknown time mode")
        }
    } catch (_: Exception) { throw SeriesSourceReview("Master DTSTART or zone is not safely representable") }

    private suspend fun children(uri: Uri, ids: Set<Long>): Map<Long, List<Map<String, String?>>> {
        val result = ids.associateWith { mutableListOf<Map<String, String?>>() }
        for (chunk in ids.toList().chunked(200)) {
            val children = rows(uri, "event_id IN (${chunk.joinToString(",") { "?" }})", chunk.map { it.toString() }.toTypedArray())
            children.forEach { row ->
                val parent = row.long("event_id") ?: throw SeriesSourceReview("Missing child parent identity")
                if (parent !in chunk || row.long("_id") == null) throw SeriesSourceReview("Invalid child identity")
                result.getValue(parent) += row
            }
        }
        if (result.values.flatten().map { it.long("_id") }.toSet().size != result.values.sumOf { it.size })
            throw SeriesSourceReview("Duplicate child identity")
        return result
    }
    private fun ensureRead() { if (!hasPermission(Manifest.permission.READ_CALENDAR)) throw SecurityException() }
    private suspend fun rows(uri: Uri, selection: String? = null, args: Array<String>? = null, maxRows: Int? = null,
        fields: Array<String>? = null): List<Map<String, String?>> {
        ensureRead()
        return query(uri, selection, args, fields).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    if (maxRows != null && size >= maxRows) throw SeriesSourceReview("Complete source exceeds the read budget")
                    add(cursor.columnNames.associateWith { name ->
                        val index = cursor.getColumnIndexOrThrow(name)
                        if (cursor.getType(index) == Cursor.FIELD_TYPE_BLOB) throw SeriesSourceReview("Unsupported opaque provider field")
                        if (cursor.isNull(index)) null else cursor.getString(index)
                    })
                }
            }
        }
    }
    private suspend fun query(uri: Uri, selection: String?, args: Array<String>?, fields: Array<String>?): Cursor = suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        continuation.invokeOnCancellation { signal.cancel() }
        try {
            ensureRead()
            val cursor = resolver.query(uri, fields, selection, args, "_id ASC", signal) ?: error("Provider unavailable")
            try { ensureRead() } catch (failure: Exception) { cursor.close(); throw failure }
            continuation.resume(cursor, onCancellation = { _, resource, _ -> resource.close() })
        } catch (failure: Exception) { continuation.resumeWithException(failure) }
    }
    private fun knownAvailability(calendar: Map<String, String?>): Boolean {
        val raw = calendar[CalendarContract.Calendars.ALLOWED_AVAILABILITY]?.takeIf { it.isNotBlank() } ?: return false
        val values = raw.split(',').map { it.trim().toIntOrNull() ?: return false }
        return values.all { it in 0..2 }
    }
    private fun Map<String, String?>.long(name: String): Long? = this[name]?.toLongOrNull()
    private fun Map<String, String?>.flag(name: String): Boolean? = when (this[name]) { "0" -> false; "1" -> true; else -> null }
}

internal class SeriesSourceDenied(val reason: MutationDenial) : Exception()
internal class SeriesSourceReview(val reason: String) : Exception()
internal class SeriesSourceConflict : Exception()

/** Private, lossless source for future asserted field edits/tail planning. Never exposed as UI text. */
internal data class CalendarSeriesSource(
    val calendar: Map<String, String?>,
    val master: Map<String, String?>,
    val exceptions: List<Map<String, String?>>,
    val reminders: Map<Long, List<Map<String, String?>>>,
    val attendees: Map<Long, List<Map<String, String?>>>,
    val selectedDetails: EventDetails,
    val masterFields: SeriesMasterFields,
    val snapshot: RecurrenceSeriesSnapshot,
    val allowedFields: Set<EditableField>,
) {
    fun canonical(): String {
        fun row(values: Map<String, String?>) = JSONObject().apply { values.toSortedMap().forEach { (key, value) -> put(key, value ?: JSONObject.NULL) } }
        fun children(values: Map<Long, List<Map<String, String?>>>) = JSONArray(values.toSortedMap().map { (id, rows) ->
            JSONObject().put("parent", id).put("rows", JSONArray(rows.sortedBy { it["_id"]?.toLongOrNull() }.map(::row)))
        })
        val ref = snapshot.validatedSelection!!
        val recurrence = requireNotNull(ref.recurrence)
        return JSONObject().put("version", 1).put("calendar", row(calendar)).put("master", row(master))
            .put("exceptions", JSONArray(exceptions.sortedBy { it["_id"]?.toLongOrNull() }.map(::row)))
            .put("reminders", children(reminders)).put("attendees", children(attendees))
            .put("selection", JSONObject().put("calendar", ref.calendarId.value).put("event", ref.eventId)
                .put("start", ref.instanceStart.toString()).put("end", ref.instanceEnd.toString())
                .put("master", recurrence.masterEventId).put("original", recurrence.originalStart.toString())).toString()
    }
}
