package dev.swapnilraj.calendar.data.calendar

import android.Manifest
import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.provider.CalendarContract
import dev.swapnilraj.calendar.core.*
import java.time.Instant
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow

/** Literal, provider-authorized search. Invalidation shares CalendarReader's single root observer. */
class CalendarSearchReader(
    private val resolver: ContentResolver,
    private val reader: CalendarReader,
    private val hasPermission: (String) -> Boolean,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val maxScannedInstances: Int = 50_000,
    private val pageDays: Long = 31,
    private val maxPageRows: Int = 5_000,
) : CalendarSearch {
    init { require(maxScannedInstances > 0 && pageDays in 1..366 && maxPageRows > 0) }
    private data class EventKey(val calendar: CalendarId, val event: Long)
    private data class Text(val title: String, val location: String, val description: String, val declined: Boolean, val excluded: Boolean)
    private data class Authority(val account: CalendarAccount, val owner: String?, val access: Int?, val visible: Boolean, val sync: Boolean)

    override fun observe(request: CalendarSearchRequest): Flow<CalendarReadState<CalendarSearchSnapshot>> = reader.observeProviderReads {
        withContext(io) { search(request) }
    }

    private suspend fun search(request: CalendarSearchRequest): CalendarSearchSnapshot {
        ensureRead()
        val calendars = reader.searchCalendars(::ensureRead)
        val byId = calendars.associateBy { it.id }
        val selectedIds = selected(request.calendar.selection, calendars)
        val eligible = selectedIds.filter { byId[it]?.capabilities?.readDetails == AccessDecision.ALLOWED }.toSet()
        val coverage = selectedIds.flatMap { id ->
            val calendar = byId[id]
            buildList {
                if (calendar == null) add(CalendarCoverage(id, CoverageReason.MISSING_CALENDAR)) else {
                    if (!calendar.providerVisible) add(CalendarCoverage(id, CoverageReason.PROVIDER_HIDDEN))
                    if (!calendar.syncEnabled) add(CalendarCoverage(id, CoverageReason.SYNC_DISABLED))
                    if (calendar.capabilities.readDetails != AccessDecision.ALLOWED) add(CalendarCoverage(id, CoverageReason.INSUFFICIENT_ACCESS))
                }
            }
        }
        val baseline = byId.mapValues { authority(it.value) }
        suspend fun verifyCalendars() {
            val current = reader.searchCalendars(::ensureRead)
            check(selected(request.calendar.selection, current) == selectedIds) { "Calendar selection changed" }
            val currentById = current.associateBy { it.id }
            check(selectedIds.all { baseline[it] == currentById[it]?.let(::authority) }) { "Calendar namespace or read policy changed" }
        }
        if (eligible.isEmpty()) return CalendarSearchSnapshot(request, calendars, emptyList(), false, coverage)
        val metadata = mutableMapOf<EventKey, Text>()
        val occurrences = linkedMapOf<EventRef, EventOccurrence>()
        var scanned = 0
        var truncated = false
        var date = request.calendar.range.start
        while (date < request.calendar.range.endExclusive) {
            currentCoroutineContext().ensureActive()
            if (scanned >= maxScannedInstances) { truncated = true; break }
            verifyCalendars()
            val end = minOf(date.plusDays(pageDays), request.calendar.range.endExclusive)
            var pageScanned = 0
            val pageOccurrences = mutableListOf<EventOccurrence>()
            for (calendarChunk in eligible.sortedBy { it.value }.chunked(200)) {
                val remaining = minOf(maxPageRows - pageScanned, maxScannedInstances - scanned)
                if (remaining <= 0) { truncated = true; break }
                val page = reader.searchPage(request.calendar.copy(range = CalendarDateRange(date, end), selection = CalendarSelection.Explicit(calendarChunk.toSet())),
                    calendars, remaining, ::ensureRead)
                scanned += page.scannedRows
                pageScanned += page.scannedRows
                pageOccurrences += page.occurrences
                if (page.truncated) { truncated = true; break }
            }
            verifyCalendars()
            val needed = pageOccurrences.map { EventKey(it.ref.calendarId, it.ref.eventId) }.filter { it !in metadata }.toSet()
            for (chunk in needed.toList().sortedWith(compareBy<EventKey> { it.calendar.value }.thenBy { it.event }).chunked(200)) {
                verifyCalendars()
                val ids = chunk.map { it.event }.distinct()
                val calendarIds = chunk.map { it.calendar }.distinct()
                val selection = "_id IN (${ids.joinToString(",") { "?" }}) AND calendar_id IN (${calendarIds.joinToString(",") { "?" }})"
                val rows = mutableMapOf<EventKey, Text>()
                query(CalendarContract.Events.CONTENT_URI, selection, (ids.map { it.toString() } + calendarIds.map { it.value.toString() }).toTypedArray()).use { cursor ->
                    while (cursor.moveToNext()) {
                        currentCoroutineContext().ensureActive()
                        val key = EventKey(CalendarId(cursor.requiredLong("calendar_id")), cursor.requiredLong("_id"))
                        check(key in chunk) { "Event calendar changed" }
                        val deleted = cursor.requiredLong(CalendarContract.Events.DELETED)
                        val status = cursor.requiredLong(CalendarContract.Events.STATUS).toInt()
                        val self = cursor.requiredLong(CalendarContract.Events.SELF_ATTENDEE_STATUS).toInt()
                        check(deleted == 0L || deleted == 1L)
                        val text = Text(cursor.text(CalendarContract.Events.TITLE).orEmpty(), cursor.text(CalendarContract.Events.EVENT_LOCATION).orEmpty(),
                            cursor.text(CalendarContract.Events.DESCRIPTION).orEmpty(), self == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED,
                            deleted == 1L || status == CalendarContract.Events.STATUS_CANCELED)
                        check(rows.put(key, text) == null) { "Ambiguous event metadata" }
                    }
                }
                verifyCalendars()
                check(rows.keys == chunk.toSet()) { "Event metadata changed during search" }
                metadata.putAll(rows)
            }
            pageOccurrences.forEach { occurrences.putIfAbsent(it.ref, it) }
            if (truncated) break
            date = end
        }
        verifyCalendars()
        val needle = request.query.trim()
        val hits = occurrences.values.mapNotNull { occurrence ->
            val text = metadata.getValue(EventKey(occurrence.ref.calendarId, occurrence.ref.eventId))
            if (text.excluded || (!request.includeDeclined && text.declined)) return@mapNotNull null
            if (!listOf(text.title, text.location, text.description).any { it.contains(needle, ignoreCase = true) }) return@mapNotNull null
            CalendarSearchHit(occurrence.copy(title = text.title, declined = text.declined), text.location.takeIf { it.isNotEmpty() },
                snippet(text.description, needle))
        }.sortedWith(compareBy<CalendarSearchHit> { it.occurrence.ref.instanceStart }.thenBy { it.occurrence.ref.instanceEnd }
            .thenBy { it.occurrence.ref.calendarId.value }.thenBy { it.occurrence.ref.eventId }
            .thenBy { it.occurrence.ref.recurrence?.masterEventId ?: -1 }.thenBy { it.occurrence.ref.recurrence?.originalStart ?: Instant.MIN })
        return CalendarSearchSnapshot(request, calendars, hits.take(request.limit), truncated || hits.size > request.limit, coverage)
    }

    private fun selected(selection: CalendarSelection, calendars: List<CalendarInfo>): Set<CalendarId> = when (selection) {
        CalendarSelection.ProviderVisible -> calendars.filter { it.providerVisible }.map { it.id }.toSet()
        is CalendarSelection.Explicit -> selection.ids
    }
    private fun authority(calendar: CalendarInfo) = Authority(calendar.account, calendar.owner, calendar.accessLevel, calendar.providerVisible, calendar.syncEnabled)
    private fun ensureRead() { if (!hasPermission(Manifest.permission.READ_CALENDAR)) throw SecurityException() }
    private suspend fun query(uri: Uri, selection: String, args: Array<String>): Cursor = suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        continuation.invokeOnCancellation { signal.cancel() }
        try {
            ensureRead()
            val cursor = resolver.query(uri, fields, selection, args, "_id ASC", signal) ?: error("Provider unavailable")
            try { ensureRead() } catch (failure: Exception) { cursor.close(); throw failure }
            continuation.resume(cursor, onCancellation = { _, resource, _ -> resource.close() })
        } catch (failure: Exception) { continuation.resumeWithException(failure) }
    }
    private fun snippet(raw: String, needle: String): String? {
        if (raw.isEmpty()) return null
        val text = buildString(raw.length) { raw.forEach { append(if (it.isISOControl()) ' ' else it) } }
        val match = text.indexOf(needle, ignoreCase = true).coerceAtLeast(0)
        val offset = maxOf(0, text.codePointCount(0, match) - 40)
        val begin = text.offsetByCodePoints(0, offset)
        val end = text.offsetByCodePoints(begin, minOf(160, text.codePointCount(begin, text.length)))
        return text.substring(begin, end)
    }
    private fun Cursor.text(field: String): String? = getColumnIndexOrThrow(field).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.requiredLong(field: String) = text(field)?.toLongOrNull() ?: error("Missing provider field")
    companion object {
        private val fields = arrayOf("_id", "calendar_id", CalendarContract.Events.TITLE, CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DESCRIPTION, CalendarContract.Events.DELETED, CalendarContract.Events.STATUS, CalendarContract.Events.SELF_ATTENDEE_STATUS)
    }
}
