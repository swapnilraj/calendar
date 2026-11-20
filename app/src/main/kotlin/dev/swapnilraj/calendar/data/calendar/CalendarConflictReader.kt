package dev.swapnilraj.calendar.data.calendar

import android.Manifest
import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.provider.CalendarContract
import dev.swapnilraj.calendar.core.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow

/** Read-only occupancy joins. Shares CalendarReader's observer; never reads private event text. */
class CalendarConflictReader(
    private val resolver: ContentResolver,
    private val reader: CalendarReader,
    private val hasPermission: (String) -> Boolean,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val readBudgets: ProviderReadBudgets = ProviderReadBudgets(),
) : CalendarSchedulingConflicts {
    private val namespace = UUID.randomUUID().toString()
    private val generation = AtomicLong()
    private data class Key(val calendar: CalendarId, val event: Long)
    private data class Authority(val identity: CalendarIdentity, val access: Int?, val readable: AccessDecision,
        val visible: Boolean, val sync: Boolean)
    private data class Occupancy(val raw: List<String?>, val availability: ConflictAvailability,
        val status: ConflictEventStatus, val participation: ConflictParticipation)

    override fun observe(request: ConflictReadRequest): Flow<CalendarReadState<ConflictSnapshot>> =
        reader.observeInvalidatingProviderReads {
            withContext(io) { withFreshReadBudget(readBudgets.search) { read(request) } }
        }

    private suspend fun read(request: ConflictReadRequest): ConflictSnapshot {
        var checks = 0
        fun ensureRead() {
            if (++checks > 4096) throw ProviderReadLimitExceeded()
            if (!hasPermission(Manifest.permission.READ_CALENDAR)) throw SecurityException()
        }
        ensureRead()
        currentCoroutineContext().ensureActive()
        val revision = generation.updateAndGet { check(it < Long.MAX_VALUE); it + 1 }
        val context = request.context(ConflictSourceRevision(namespace, revision))
        fun unsupported() = ConflictSnapshot(context, emptyList(),
            ConflictCoverage.Unavailable(ConflictUnavailableReason.UNSUPPORTED_TIME_RANGE))
        val range = try {
            when (val time = request.proposedTime) {
                is EventTime.AllDay -> {
                    val bounds = time.dates.instantBounds(request.planningZone)
                    if (bounds.start == bounds.endExclusive) return unsupported()
                    time.dates
                }
                is EventTime.Timed -> CalendarDateRange(time.start.atZone(request.planningZone).toLocalDate(),
                    time.end.minusNanos(1).atZone(request.planningZone).toLocalDate().plusDays(1))
            }
        } catch (_: java.time.DateTimeException) { return unsupported() }
        if (range.start.year !in 1..9999 || range.endExclusive.year !in 1..9999 ||
            java.time.temporal.ChronoUnit.DAYS.between(range.start, range.endExclusive) > 366) return unsupported()
        if (request.calendars.isEmpty()) {
            ensureRead()
            return ConflictSnapshot(context, emptyList(), ConflictCoverage.Complete)
        }
        fun indexed(rows: List<CalendarInfo>): Map<CalendarId, CalendarInfo> {
            check(rows.map { it.id }.distinct().size == rows.size)
            return rows.associateBy { it.id }
        }
        fun authority(row: CalendarInfo) = Authority(row.identity, row.accessLevel, row.capabilities.readDetails,
            row.providerVisible, row.syncEnabled)
        suspend fun freshCalendars(): List<CalendarInfo> {
            val materialized = reader.conflictCalendars(::ensureRead)
            val byId = indexed(materialized)
            val ids = request.calendars.map { it.calendarId }.toSet()
            val policies = mutableMapOf<CalendarId, Authority>()
            val selection = "_id IN (${ids.joinToString(",") { "?" }})"
            query(CalendarContract.Calendars.CONTENT_URI, policyFields, selection,
                ids.map { it.value.toString() }.toTypedArray(), ::ensureRead, 256).use { cursor ->
                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val id = CalendarId(checkNotNull(canonical(cursor.text("_id"))))
                    check(id in ids)
                    val access = checkNotNull(canonical(cursor.text(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)))
                    check(access in knownAccessLevels)
                    val visible = canonical(cursor.text(CalendarContract.Calendars.VISIBLE))
                    val sync = canonical(cursor.text(CalendarContract.Calendars.SYNC_EVENTS))
                    check(visible == 0L || visible == 1L)
                    check(sync == 0L || sync == 1L)
                    val account = CalendarAccount(checkNotNull(cursor.text(CalendarContract.Calendars.ACCOUNT_NAME)),
                        checkNotNull(cursor.text(CalendarContract.Calendars.ACCOUNT_TYPE)))
                    check(account.name.isNotBlank() && account.type.isNotBlank())
                    val identity = CalendarIdentity(id, account,
                        cursor.text(CalendarContract.Calendars.OWNER_ACCOUNT)?.takeIf(String::isNotBlank),
                        cursor.text(CalendarContract.Calendars._SYNC_ID)?.takeIf(String::isNotBlank))
                    // Long is validated against known constants BEFORE the materialized Int is compared.
                    val observed = checkNotNull(byId[id])
                    check(observed.identity == identity && observed.accessLevel?.toLong() == access &&
                        observed.providerVisible == (visible == 1L) && observed.syncEnabled == (sync == 1L))
                    check(policies.put(id, authority(observed)) == null)
                }
            }
            check(ids.all { (byId[it] != null) == (policies[it] != null) })
            return materialized
        }
        val calendars = freshCalendars()
        val byId = indexed(calendars)
        val pinned = request.calendars.associate { expected ->
            val present = byId[expected.calendarId]
            check(present == null || present.identity == expected)
            expected.calendarId to present?.let(::authority)
        }
        val reasons = linkedSetOf<ConflictCoverageProblem>()
        val eligible = request.calendars.filter { expected ->
            val row = byId[expected.calendarId]
            if (row == null) reasons += ConflictCoverageProblem.MISSING_CALENDAR
            else {
                if (!row.providerVisible) reasons += ConflictCoverageProblem.HIDDEN_CALENDAR
                if (!row.syncEnabled) reasons += ConflictCoverageProblem.SYNC_DISABLED
                if (row.capabilities.readDetails != AccessDecision.ALLOWED) reasons += ConflictCoverageProblem.ACCESS_LIMITED
            }
            row?.capabilities?.readDetails == AccessDecision.ALLOWED
        }.map { it.calendarId }.toSet()
        suspend fun verifyCalendars() {
            val latest = indexed(freshCalendars())
            check(pinned.all { (id, observation) -> latest[id]?.let(::authority) == observation })
        }
        val calendarRequest = CalendarRequest(range, request.planningZone, CalendarSelection.Explicit(eligible),
            invitationFilter = InvitationFilter.ALL)
        var scanned = 0
        var retained = 0L
        suspend fun occurrences(): List<EventOccurrence> {
            verifyCalendars()
            val page = reader.searchPage(calendarRequest, calendars, SchedulingConflicts.MAX_CANDIDATES, ::ensureRead)
            scanned += page.scannedRows
            if (page.truncated || scanned > 2 * SchedulingConflicts.MAX_CANDIDATES) throw ProviderReadLimitExceeded()
            verifyCalendars()
            check(page.occurrences.map { it.ref }.distinct().size == page.occurrences.size)
            val cost = page.occurrences.size.toLong() * 128
            if (cost > readBudgets.maxSearchRetainedUnits - retained) throw ProviderReadLimitExceeded()
            retained += cost
            return page.occurrences
        }
        suspend fun metadata(rows: List<EventOccurrence>): Map<Key, Occupancy> {
            val needed = rows.map { Key(it.ref.calendarId, it.ref.eventId) }.distinct()
            val result = linkedMapOf<Key, Occupancy>()
            for (chunk in needed.chunked(200)) {
                currentCoroutineContext().ensureActive()
                verifyCalendars()
                val ids = chunk.map { it.event }.distinct()
                val calendarIds = chunk.map { it.calendar }.distinct()
                val selection = "_id IN (${ids.joinToString(",") { "?" }}) AND calendar_id IN (${calendarIds.joinToString(",") { "?" }})"
                query(CalendarContract.Events.CONTENT_URI, fields, selection, (ids.map { it.toString() } + calendarIds.map { it.value.toString() }).toTypedArray(), ::ensureRead, 200).use { cursor ->
                    while (cursor.moveToNext()) {
                        currentCoroutineContext().ensureActive()
                        val raw = fields.map { cursor.text(it) }
                        fun value(field: String) = raw[fields.indexOf(field)]
                        val key = Key(CalendarId(checkNotNull(canonical(value("calendar_id")))), checkNotNull(canonical(value("_id"))))
                        check(key in chunk && key.event >= 0 && canonical(value("deleted")) == 0L)
                        val cost = 128L + raw.sumOf { it?.length?.toLong() ?: 0 }
                        if (cost > readBudgets.maxSearchRetainedUnits - retained) throw ProviderReadLimitExceeded()
                        retained += cost
                        val availability = when (canonical(value(CalendarContract.Events.AVAILABILITY))) {
                            CalendarContract.Events.AVAILABILITY_BUSY.toLong() -> ConflictAvailability.BUSY
                            CalendarContract.Events.AVAILABILITY_FREE.toLong() -> ConflictAvailability.FREE
                            CalendarContract.Events.AVAILABILITY_TENTATIVE.toLong() -> ConflictAvailability.TENTATIVE
                            else -> ConflictAvailability.UNKNOWN
                        }
                        val status = when (canonical(value(CalendarContract.Events.STATUS))) {
                            CalendarContract.Events.STATUS_CONFIRMED.toLong(), CalendarContract.Events.STATUS_TENTATIVE.toLong() -> ConflictEventStatus.ACTIVE
                            CalendarContract.Events.STATUS_CANCELED.toLong() -> ConflictEventStatus.CANCELLED
                            else -> ConflictEventStatus.UNKNOWN
                        }
                        val self = canonical(value(CalendarContract.Events.SELF_ATTENDEE_STATUS))
                        val participation = when (self) {
                            CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED.toLong() -> ConflictParticipation.ACCEPTED
                            CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE.toLong() -> ConflictParticipation.TENTATIVE
                            CalendarContract.Attendees.ATTENDEE_STATUS_INVITED.toLong() -> ConflictParticipation.INVITED
                            CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED.toLong() -> ConflictParticipation.DECLINED
                            CalendarContract.Attendees.ATTENDEE_STATUS_NONE.toLong() ->
                                if (canonical(value(CalendarContract.Events.IS_ORGANIZER)) == 1L &&
                                    canonical(value(CalendarContract.Events.HAS_ATTENDEE_DATA)) == 0L) ConflictParticipation.PERSONAL
                                else ConflictParticipation.UNKNOWN
                            else -> ConflictParticipation.UNKNOWN
                        }
                        check(result.put(key, Occupancy(raw, availability, status, participation)) == null)
                    }
                }
                check(chunk.all { it in result })
                verifyCalendars()
            }
            return result
        }
        val first = occurrences()
        val firstMetadata = metadata(first)
        val second = occurrences()
        check(first.map { it.ref to it.time }.toSet() == second.map { it.ref to it.time }.toSet())
        val secondMetadata = metadata(second)
        check(firstMetadata == secondMetadata)
        verifyCalendars()
        currentCoroutineContext().ensureActive()
        ensureRead()
        val candidates = second.map { occurrence ->
            val values = secondMetadata.getValue(Key(occurrence.ref.calendarId, occurrence.ref.eventId))
            ConflictCandidate(byId.getValue(occurrence.ref.calendarId).identity, occurrence.ref, occurrence.time,
                values.availability, values.status, values.participation)
        }
        val coverage = if (reasons.isEmpty()) ConflictCoverage.Complete else ConflictCoverage.Partial(reasons)
        val snapshot = ConflictSnapshot(context, candidates, coverage)
        // Reuse pure identity/time checks, including exact UTC DATE encoding. Never publish malformed joins.
        check(SchedulingConflicts.assess(context, snapshot) !is ConflictAssessment.Rejected)
        return snapshot
    }

    private suspend fun query(uri: Uri, projected: Array<String>, selection: String, args: Array<String>,
        ensureRead: () -> Unit, maxRows: Int): Cursor =
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            try {
                ensureRead()
                val cursor = (resolver.query(uri, projected, selection, args, "_id ASC", signal)
                    ?: error("Provider unavailable")).bounded(maxRows)
                try { ensureRead() } catch (failure: Exception) { cursor.close(); throw failure }
                continuation.resume(cursor, onCancellation = { _, resource, _ -> resource.close() })
            } catch (failure: Exception) { continuation.resumeWithException(failure) }
        }

    private fun Cursor.text(field: String): String? = getColumnIndexOrThrow(field).let { if (isNull(it)) null else getString(it) }
    private fun canonical(raw: String?): Long? = raw?.toLongOrNull()?.takeIf { it.toString() == raw }
    companion object {
        // CalendarContract's declared access levels, compared as canonical Long values.
        private val knownAccessLevels = setOf(0L, 100L, 200L, 300L, 400L, 500L, 600L, 700L, 800L)
        private val policyFields = arrayOf("_id", CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.Calendars.OWNER_ACCOUNT,
            CalendarContract.Calendars._SYNC_ID, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.VISIBLE, CalendarContract.Calendars.SYNC_EVENTS)
        private val fields = arrayOf("_id", "calendar_id", CalendarContract.Events.DELETED,
            CalendarContract.Events.AVAILABILITY, CalendarContract.Events.STATUS, CalendarContract.Events.SELF_ATTENDEE_STATUS,
            CalendarContract.Events.IS_ORGANIZER, CalendarContract.Events.HAS_ATTENDEE_DATA,
            CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND, CalendarContract.Events.DURATION,
            CalendarContract.Events.ALL_DAY, CalendarContract.Events.RRULE, CalendarContract.Events.RDATE,
            CalendarContract.Events.EXRULE, CalendarContract.Events.EXDATE, CalendarContract.Events.ORIGINAL_ID,
            CalendarContract.Events.ORIGINAL_SYNC_ID, CalendarContract.Events.ORIGINAL_INSTANCE_TIME, CalendarContract.Events.UID_2445)
    }
}
