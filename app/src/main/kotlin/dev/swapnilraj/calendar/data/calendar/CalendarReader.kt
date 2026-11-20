package dev.swapnilraj.calendar.data.calendar

import android.content.ContentResolver
import android.content.ContentUris
import android.database.ContentObserver
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.provider.CalendarContract
import dev.swapnilraj.calendar.core.*
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*

/** Read-only provider adapter. The supplied scope must outlive its screen collectors. */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarReader(
    private val resolver: ContentResolver,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val stopTimeoutMillis: Long = 5_000,
    private val readBudgets: ProviderReadBudgets = ProviderReadBudgets(),
) {
    private val manualRefresh = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val changes = manualRefresh.onStart { emit(Unit) }.flatMapLatest { callbackFlow {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) { trySend(Unit) }
        }
        var registered = false
        try {
            resolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
            registered = true
        } catch (_: SecurityException) {
            // The query reports the permission state; refresh/resubscription can recover.
        }
        trySend(Unit)
        awaitClose { if (registered) resolver.unregisterContentObserver(observer) }
    } }.conflate().shareIn(scope, sharing(), replay = 1)

    private val calendarFlow = changes.mapLatest { guarded { queryCalendars() } }
        .onStart { emit(CalendarReadState.Loading) }
        .shareIn(scope, SharingStarted.WhileSubscribed(0, replayExpirationMillis = 0), replay = 1)
    private data class RequestEntry(
        val values: SharedFlow<CalendarReadState<CalendarSnapshot>>, val lifetime: Job, var readers: Int = 0,
    )
    private val requests = mutableMapOf<CalendarRequest, RequestEntry>()

    private fun sharing() = SharingStarted.WhileSubscribed(stopTimeoutMillis, replayExpirationMillis = 0)
    fun refresh() { manualRefresh.tryEmit(Unit) }
    fun observeCalendars(): Flow<CalendarReadState<List<CalendarInfo>>> = calendarFlow

    /** A fresh bounded read; unlike a shared subscription this never returns its replay cache. */
    suspend fun readCalendars(): CalendarReadState<List<CalendarInfo>> = guarded { queryCalendars() }
    suspend fun readSnapshot(request: CalendarRequest): CalendarReadState<CalendarSnapshot> =
        guarded(readBudgets.range) { querySnapshot(request) }

    /** An external event number is only a lookup hint. Do not construct EventRef from this row. */
    suspend fun readEventNavigationInfo(eventId: Long): CalendarReadState<EventNavigationInfo?> = guarded(readBudgets.details) {
        require(eventId > 0)
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        suspend fun event(): Map<String, String?>? = query(uri, navigationFields, maxRows = 1).use { cursor ->
            if (!cursor.moveToFirst()) null else navigationFields.associateWith { cursor.text(it) }.also {
                check(!cursor.moveToNext())
                check(it[CalendarContract.Events._ID]?.toLongOrNull() == eventId)
            }
        }
        val row = event() ?: return@guarded null
        val calendarId = CalendarId(checkNotNull(row[CalendarContract.Events.CALENDAR_ID]?.toLongOrNull()))
        val calendar = queryCalendars().singleOrNull { it.id == calendarId }
            ?: error("Calendar unavailable")
        check(calendar.capabilities.readDetails == AccessDecision.ALLOWED)
        check(calendar.account.name.isNotBlank() && calendar.account.type.isNotBlank())
        check(row[CalendarContract.Events.DELETED] in setOf("0", "1"))
        if (row[CalendarContract.Events.DELETED] == "1" ||
            row[CalendarContract.Events.STATUS] == CalendarContract.Events.STATUS_CANCELED.toString()) return@guarded null
        val allDay = when (row[CalendarContract.Events.ALL_DAY]) { "0" -> false; "1" -> true; else -> error("Unknown event kind") }
        fun instant(raw: String?): Instant = Instant.ofEpochMilli(checkNotNull(raw?.toLongOrNull())).also {
            check(it.atOffset(ZoneOffset.UTC).year in 1..9999)
        }
        val start = instant(row[CalendarContract.Events.DTSTART])
        if (allDay) check(start.atOffset(ZoneOffset.UTC).toLocalTime() == java.time.LocalTime.MIDNIGHT)
        val recurring = listOf(CalendarContract.Events.RRULE, CalendarContract.Events.RDATE,
            CalendarContract.Events.EXRULE, CalendarContract.Events.EXDATE,
            CalendarContract.Events.ORIGINAL_ID, CalendarContract.Events.ORIGINAL_SYNC_ID,
            CalendarContract.Events.ORIGINAL_INSTANCE_TIME).any { !row[it].isNullOrBlank() }
        val end = row[CalendarContract.Events.DTEND]?.let(::instant) ?: if (!recurring) {
            val origin = if (allDay) RecurrenceStart.AllDay(start.atOffset(ZoneOffset.UTC).toLocalDate()) else {
                val eventZone = checkNotNull(zone(row[CalendarContract.Events.EVENT_TIMEZONE]))
                val local = start.atZone(eventZone)
                RecurrenceStart.Timed(local.toLocalDateTime(), eventZone, local.offset)
            }
            val resolved = RecurrenceMasterTemplates.resolve(origin, row[CalendarContract.Events.DURATION]) as? RecurrenceMasterTemplateResult.Ready
                ?: error("Unresolved event duration")
            when (val time = resolved.template.time) {
                is EventTime.Timed -> time.end
                is EventTime.AllDay -> time.dates.endExclusive.atStartOfDay(ZoneOffset.UTC).toInstant()
            }
        } else null
        end?.let {
            check((if (allDay) it > start else it >= start) && it.atOffset(ZoneOffset.UTC).year in 1..9999)
            if (allDay) check(it.atOffset(ZoneOffset.UTC).toLocalTime() == java.time.LocalTime.MIDNIGHT)
        }
        // Pin both namespaces and raw temporal metadata across the asynchronous lookup.
        check(event() == row)
        val latest = queryCalendars().singleOrNull { it.id == calendarId }
        check(latest != null && latest.identity == calendar.identity && latest.capabilities.readDetails == AccessDecision.ALLOWED)
        EventNavigationInfo(eventId, calendar.identity,
            row[CalendarContract.Events.UID_2445]?.takeIf(String::isNotBlank), allDay, start, end, recurring)
    }

    // Look up on collection, not when constructing the Flow. Old Flow references can safely
    // resubscribe after idle entries are removed without creating duplicate live queries.
    fun observeEvents(request: CalendarRequest): Flow<CalendarReadState<CalendarSnapshot>> = flow {
        val entry = synchronized(requests) {
            requests.getOrPut(request) {
                val lifetime = SupervisorJob(scope.coroutineContext[Job])
                val requestScope = CoroutineScope(scope.coroutineContext + lifetime)
                RequestEntry(changes.mapLatest { guarded(readBudgets.range) { querySnapshot(request) } }
                    .onStart { emit(CalendarReadState.Loading) }
                    .shareIn(requestScope, SharingStarted.WhileSubscribed(0, replayExpirationMillis = 0), replay = 1), lifetime)
            }.also { it.readers++ }
        }
        try { emitAll(entry.values) }
        finally {
            synchronized(requests) {
                entry.readers--
                if (entry.readers == 0 && requests[request] === entry) {
                    requests.remove(request)
                    entry.lifetime.cancel()
                }
            }
        }
    }

    private suspend fun <T> guarded(limits: ProviderReadLimits = readBudgets.calendars, block: suspend () -> T): CalendarReadState<T> = try {
        withContext(io + readBudgetContext(limits)) { CalendarReadState.Ready(block()) }
    } catch (_: ProviderReadLimitExceeded) {
        CalendarReadState.LimitExceeded
    } catch (_: SecurityException) {
        CalendarReadState.PermissionRequired
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        CalendarReadState.Unavailable
    }

    /** CancellationSignal also aborts a query blocked inside another provider process. */
    private suspend fun query(uri: Uri, fields: Array<String>, selection: String? = null,
        args: Array<String>? = null, order: String? = null, beforeQuery: () -> Unit = {}, maxRows: Int = Int.MAX_VALUE): Cursor = suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        continuation.invokeOnCancellation { signal.cancel() }
        try {
            beforeQuery()
            val cursor = (resolver.query(uri, fields, selection, args, order, signal)
                ?: throw IllegalStateException("Calendar provider unavailable")).bounded(maxRows)
            try { beforeQuery() } catch (failure: Exception) { cursor.close(); throw failure }
            continuation.resume(cursor, onCancellation = { _, resource, _ -> resource.close() })
        } catch (failure: Exception) { continuation.resumeWithException(failure) }
    }

    private suspend fun queryCalendars(beforeQuery: () -> Unit = {}): List<CalendarInfo> = query(CalendarContract.Calendars.CONTENT_URI,
        calendarFields, order = "${CalendarContract.Calendars._ID} ASC", beforeQuery = beforeQuery, maxRows = readBudgets.calendars.maxRows).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                val access = cursor.calendarAccessObservation()
                add(CalendarInfo(
                    CalendarId(cursor.requiredLong(CalendarContract.Calendars._ID)),
                    cursor.text(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME).orEmpty(),
                    cursor.number(CalendarContract.Calendars.CALENDAR_COLOR)?.toInt() ?: FallbackColor,
                    CalendarAccount(cursor.text(CalendarContract.Calendars.ACCOUNT_NAME).orEmpty(),
                        cursor.text(CalendarContract.Calendars.ACCOUNT_TYPE).orEmpty()),
                    cursor.text(CalendarContract.Calendars.OWNER_ACCOUNT),
                    zone(cursor.text(CalendarContract.Calendars.CALENDAR_TIME_ZONE)), access,
                    cursor.number(CalendarContract.Calendars.VISIBLE) == 1L,
                    cursor.number(CalendarContract.Calendars.SYNC_EVENTS) == 1L,
                    CalendarCapabilities(access.decision(CalendarContract.Calendars.CAL_ACCESS_READ),
                        access.decision(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR),
                        access.decision(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR),
                        access.decision(CalendarContract.Calendars.CAL_ACCESS_RESPOND)),
                    providerId = cursor.text(CalendarContract.Calendars._SYNC_ID),
                ))
            }
        }
    }

    private suspend fun querySnapshot(request: CalendarRequest): CalendarSnapshot {
        val calendars = queryCalendars()
        val byId = calendars.associateBy { it.id }
        val selected = when (val selection = request.selection) {
            CalendarSelection.ProviderVisible -> calendars.filter { it.providerVisible }.map { it.id }.toSet()
            is CalendarSelection.Explicit -> selection.ids
        }
        val coverage = selected.flatMap { id ->
            val calendar = byId[id]
            buildList {
                if (calendar == null) add(CalendarCoverage(id, CoverageReason.MISSING_CALENDAR))
                else {
                    if (!calendar.providerVisible) add(CalendarCoverage(id, CoverageReason.PROVIDER_HIDDEN))
                    if (!calendar.syncEnabled) add(CalendarCoverage(id, CoverageReason.SYNC_DISABLED))
                    if (calendar.accessLevel.decision(CalendarContract.Calendars.CAL_ACCESS_FREEBUSY) != AccessDecision.ALLOWED ||
                        request.invitationFilter == InvitationFilter.PENDING_ONLY && calendar.capabilities.readDetails != AccessDecision.ALLOWED)
                        add(CalendarCoverage(id, CoverageReason.INSUFFICIENT_ACCESS))
                }
            }
        }
        val eligible = selected.filter { id -> byId[id]?.let {
            it.accessLevel.decision(CalendarContract.Calendars.CAL_ACCESS_FREEBUSY) == AccessDecision.ALLOWED
        } == true }
        if (eligible.isEmpty()) return CalendarSnapshot(request, calendars, emptyList(), coverage)
        val page = queryOccurrences(request, byId, eligible)
        if (page.truncated) throw ProviderReadLimitExceeded()
        if (request.invitationFilter == InvitationFilter.ALL) return CalendarSnapshot(request, calendars, page.occurrences, coverage)
        val unknown = page.occurrences.filter { it.invitation == InvitationState.UNKNOWN &&
            byId[it.ref.calendarId]?.capabilities?.readDetails == AccessDecision.ALLOWED }
            .map { CalendarCoverage(it.ref.calendarId, CoverageReason.INVITATION_CLASSIFICATION_UNKNOWN) }.distinct()
        return CalendarSnapshot(request, calendars, page.occurrences.filter { it.invitation == InvitationState.PENDING }, (coverage + unknown).distinct())
    }

    /** Adapter reads share the existing root observer; no per-page observers or dormant jobs. */
    internal fun <T> observeProviderReads(block: suspend () -> T): Flow<CalendarReadState<T>> =
        changes.mapLatest { guarded(readBudgets.search, block) }.onStart { emit(CalendarReadState.Loading) }

    /** Advisory reads mask their previous value before every invalidation's guarded IO. */
    internal fun <T> observeInvalidatingProviderReads(block: suspend () -> T): Flow<CalendarReadState<T>> =
        changes.transformLatest {
            emit(CalendarReadState.Loading)
            emit(guarded(readBudgets.search, block))
        }.onStart { emit(CalendarReadState.Loading) }

    internal suspend fun searchCalendars(beforeQuery: () -> Unit): List<CalendarInfo> =
        withFreshReadBudget(readBudgets.calendars) { queryCalendars(beforeQuery) }

    /** Advisory joins charge repeated metadata materialization to their active attempt budget. */
    internal suspend fun conflictCalendars(beforeQuery: () -> Unit): List<CalendarInfo> =
        withReadBudget(readBudgets.calendars) { queryCalendars(beforeQuery) }

    internal suspend fun searchPage(request: CalendarRequest, calendars: List<CalendarInfo>, maxRows: Int,
        beforeQuery: () -> Unit): CalendarOccurrencePage {
        require(maxRows > 0)
        val ids = (request.selection as? CalendarSelection.Explicit)?.ids ?: error("Search selection must be resolved")
        val byId = calendars.associateBy { it.id }
        check(ids.all { byId[it]?.capabilities?.readDetails == AccessDecision.ALLOWED })
        // Search classifies fresh Events metadata after this unfiltered candidate page. Dropping
        // UNKNOWN/answered Instances here would lose coverage or consume its result limit early.
        return queryOccurrences(request, byId, ids.sortedBy { it.value }, maxRows, beforeQuery, includeTitle = false)
    }

    private suspend fun queryOccurrences(request: CalendarRequest, byId: Map<CalendarId, CalendarInfo>, eligible: List<CalendarId>,
        rowLimit: Int = readBudgets.maxRangeInstances, beforeQuery: () -> Unit = {}, includeTitle: Boolean = true): CalendarOccurrencePage {
        if (eligible.isEmpty()) return CalendarOccurrencePage(emptyList(), 0, false)
        val local = request.range.instantBounds(request.displayZone)
        val utc = request.range.instantBounds(ZoneOffset.UTC)
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
            ContentUris.appendId(this, minOf(local.start, utc.start).toEpochMilli())
            ContentUris.appendId(this, maxOf(local.endExclusive, utc.endExclusive).toEpochMilli())
        }.build()
        val selection = "${CalendarContract.Instances.CALENDAR_ID} IN (${eligible.joinToString(",") { "?" }})"
        var scanned = 0
        var truncated = false
        suspend fun collect(cursor: Cursor): List<EventOccurrence> {
            val parents = syncParents(cursor, byId.filterKeys { it in eligible }, beforeQuery)
            return buildList {
                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val id = CalendarId(cursor.requiredLong(CalendarContract.Instances.CALENDAR_ID))
                    if (id !in eligible) continue
                    val occurrence = cursor.occurrence(byId[id], parents, request.invitationFilter == InvitationFilter.PENDING_ONLY)
                    if (occurrence.time.intersects(request.range, request.displayZone)) add(occurrence)
                }
            }
        }
        val materialized = if (includeTitle && request.invitationFilter == InvitationFilter.PENDING_ONLY)
            instanceFields + CalendarContract.Events.IS_ORGANIZER else instanceFields
        val projected = if (includeTitle) materialized else materialized.filterNot { it == CalendarContract.Instances.TITLE }.toTypedArray()
        val occurrences = query(uri, projected, selection, eligible.map { it.value.toString() }.toTypedArray(),
            "${CalendarContract.Instances.BEGIN} ASC, ${CalendarContract.Instances.EVENT_ID} ASC", beforeQuery).use { cursor ->
            MatrixCursor(materialized).use { bounded ->
                // Bound raw rows BEFORE the sync-parent identity prepass or occurrence materialization.
                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    if (scanned == rowLimit) { truncated = true; break }
                    scanned++
                    val id = CalendarId(cursor.requiredLong(CalendarContract.Instances.CALENDAR_ID))
                    if (id in eligible) bounded.addRow(materialized.map { field -> if (!includeTitle && field == CalendarContract.Instances.TITLE) "" else cursor.text(field) }.toTypedArray())
                }
                if (truncated && includeTitle) throw ProviderReadLimitExceeded()
                collect(bounded)
            }
        }
        // Every authorized raw row contributes evidence before EventRef deduplication. A first
        // answered or pending row must not hide contradictory or unknown duplicate evidence.
        val coalesced = if (request.invitationFilter == InvitationFilter.PENDING_ONLY) {
            occurrences.groupBy { it.ref }.values.map { rows ->
                rows.first().copy(invitation = rows.map { it.invitation }.distinct().singleOrNull() ?: InvitationState.UNKNOWN)
            }
        } else occurrences.distinctBy { it.ref }
        return CalendarOccurrencePage(coalesced.sortedWith(compareBy<EventOccurrence> { it.ref.instanceStart }
            .thenBy { it.ref.eventId }.thenBy { it.ref.calendarId.value }), scanned, truncated)
    }

    suspend fun readDetails(ref: EventRef): CalendarReadState<EventDetails> = guarded(readBudgets.details) {
        val calendar = queryCalendars().firstOrNull { it.id == ref.calendarId }
            ?: throw IllegalStateException("Calendar no longer available")
        // Unknown raw observations do not admit Instances or private detail queries.
        check(calendar.accessLevel.decision(CalendarContract.Calendars.CAL_ACCESS_FREEBUSY) == AccessDecision.ALLOWED)
        // A retained navigation reference must not display the old time of a rescheduled or
        // deleted occurrence. Validate it against the currently expanded instances first.
        val instanceUri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
            ContentUris.appendId(this, ref.instanceStart.toEpochMilli())
            ContentUris.appendId(this, maxOf(ref.instanceEnd.toEpochMilli(), ref.instanceStart.toEpochMilli() + 1))
        }.build()
        val current = query(instanceUri, instanceFields,
            "${CalendarContract.Instances.EVENT_ID} = ? AND ${CalendarContract.Instances.CALENDAR_ID} = ?",
            arrayOf(ref.eventId.toString(), ref.calendarId.value.toString()), maxRows = readBudgets.maxDetailInstances).use { instances ->
            val parents = syncParents(instances, mapOf(ref.calendarId to calendar))
            var found: EventOccurrence? = null
            while (instances.moveToNext()) {
                val candidate = instances.occurrence(calendar, parents)
                if (candidate.ref == ref) found = candidate
            }
            found ?: throw IllegalStateException("Occurrence no longer available")
        }
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, ref.eventId)
        query(uri, detailFields).use { cursor ->
            check(cursor.moveToFirst()) { "Event no longer available" }
            check(cursor.requiredLong(CalendarContract.Events.CALENDAR_ID) == ref.calendarId.value)
            val standalone = cursor.text(CalendarContract.Events.RRULE).isNullOrBlank() &&
                cursor.text(CalendarContract.Events.RDATE).isNullOrBlank() && cursor.number(CalendarContract.Events.ORIGINAL_ID) == null &&
                cursor.text(CalendarContract.Events.ORIGINAL_SYNC_ID).isNullOrBlank()
            if (standalone) {
                check(cursor.number(CalendarContract.Events.DTSTART) == current.ref.instanceStart.toEpochMilli() &&
                    cursor.number(CalendarContract.Events.DTEND) == current.ref.instanceEnd.toEpochMilli()) {
                    "Occurrence changed while reading details"
                }
            }
            val allowed = calendar.capabilities.readDetails == AccessDecision.ALLOWED
            val occurrence = EventOccurrence(current.ref, current.time,
                if (allowed) cursor.text(CalendarContract.Events.TITLE).orEmpty() else "Busy",
                if (allowed) cursor.number(CalendarContract.Events.DISPLAY_COLOR)?.toInt() ?: calendar.color else calendar.color,
                allowed && cursor.number(CalendarContract.Events.SELF_ATTENDEE_STATUS)?.toInt() == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED)
            if (!allowed) return@guarded EventDetails(occurrence, calendar,
                canEdit = AccessDecision.DENIED, canRespond = AccessDecision.DENIED)
            val organizer = cursor.booleanFlag(CalendarContract.Events.IS_ORGANIZER)
            val guestPermissions = EventGuestPermissions(
                cursor.permissionFlag(CalendarContract.Events.GUESTS_CAN_MODIFY),
                cursor.permissionFlag(CalendarContract.Events.GUESTS_CAN_INVITE_OTHERS),
                cursor.permissionFlag(CalendarContract.Events.GUESTS_CAN_SEE_GUESTS),
            )
            val maySeeGuests = organizer == true || guestPermissions.seeGuests == AccessDecision.ALLOWED
            EventDetails(
                occurrence = occurrence, calendar = calendar,
                location = cursor.text(CalendarContract.Events.EVENT_LOCATION).orEmpty(),
                description = cursor.text(CalendarContract.Events.DESCRIPTION).orEmpty(),
                organizer = cursor.text(CalendarContract.Events.ORGANIZER),
                eventZone = zone(cursor.text(CalendarContract.Events.EVENT_TIMEZONE)),
                // Provider-declared visibility is authoritative; matching an email is not proof.
                attendees = if (maySeeGuests) readAttendees(ref.eventId) else emptyList(),
                reminders = readReminders(ref.eventId), recurrenceRule = cursor.text(CalendarContract.Events.RRULE),
                // Guest-policy flags describe the event, not effective app mutation permission.
                canEdit = if (calendar.capabilities.modifyEvents == AccessDecision.DENIED) AccessDecision.DENIED else AccessDecision.UNKNOWN,
                canRespond = if (calendar.capabilities.respondToInvitations == AccessDecision.DENIED) AccessDecision.DENIED else AccessDecision.UNKNOWN,
                availability = cursor.number(CalendarContract.Events.AVAILABILITY)?.toInt(),
                privacy = cursor.number(CalendarContract.Events.ACCESS_LEVEL)?.toInt(),
                status = cursor.number(CalendarContract.Events.STATUS)?.toInt(),
                isOrganizer = organizer,
                selfAttendeeStatus = cursor.number(CalendarContract.Events.SELF_ATTENDEE_STATUS)?.toInt(),
                guestPermissions = guestPermissions,
            )
        }
    }

    private suspend fun readAttendees(eventId: Long): List<EventAttendee> = query(CalendarContract.Attendees.CONTENT_URI,
        attendeeFields, "${CalendarContract.Attendees.EVENT_ID} = ?", arrayOf(eventId.toString()), maxRows = readBudgets.maxDetailChildren).use { cursor ->
        buildList { while (cursor.moveToNext()) add(EventAttendee(cursor.text(CalendarContract.Attendees.ATTENDEE_EMAIL).orEmpty(),
            cursor.text(CalendarContract.Attendees.ATTENDEE_NAME), cursor.number(CalendarContract.Attendees.ATTENDEE_STATUS)?.toInt() ?: 0,
            cursor.number(CalendarContract.Attendees.ATTENDEE_RELATIONSHIP)?.toInt() ?: 0,
            cursor.number(CalendarContract.Attendees.ATTENDEE_TYPE)?.toInt() ?: 0)) }
    }

    private suspend fun readReminders(eventId: Long): List<EventReminder> = query(CalendarContract.Reminders.CONTENT_URI,
        reminderFields, "${CalendarContract.Reminders.EVENT_ID} = ?", arrayOf(eventId.toString()), maxRows = readBudgets.maxDetailChildren).use { cursor ->
        buildList { while (cursor.moveToNext()) add(EventReminder(cursor.requiredLong(CalendarContract.Reminders.MINUTES).toInt(),
            cursor.requiredLong(CalendarContract.Reminders.METHOD).toInt())) }
    }

    private data class SyncParent(val calendar: CalendarId, val syncId: String)

    /** Resolve sync-only exception parents once per cursor, scoped by proven readable calendars. */
    private suspend fun syncParents(instances: Cursor, calendars: Map<CalendarId, CalendarInfo>, beforeQuery: () -> Unit = {}): Map<SyncParent, Long> {
        val requested = mutableSetOf<SyncParent>()
        while (instances.moveToNext()) {
            val id = CalendarId(instances.requiredLong(CalendarContract.Instances.CALENDAR_ID))
            if (id !in calendars) continue
            val sync = instances.text(CalendarContract.Events.ORIGINAL_SYNC_ID)?.takeIf { it.isNotBlank() } ?: continue
            check(calendars.getValue(id).capabilities.readDetails == AccessDecision.ALLOWED)
            requested += SyncParent(id, sync)
        }
        instances.moveToPosition(-1)
        val result = mutableMapOf<SyncParent, Long>()
        for (chunk in requested.toList().chunked(200)) {
            val ids = chunk.map { it.calendar }.distinct()
            val syncs = chunk.map { it.syncId }.distinct()
            val selection = "calendar_id IN (${ids.joinToString(",") { "?" }}) AND _sync_id IN (${syncs.joinToString(",") { "?" }})"
            query(CalendarContract.Events.CONTENT_URI, arrayOf("_id", "calendar_id", "_sync_id", "original_id", "original_sync_id", "deleted"),
                selection, (ids.map { it.value.toString() } + syncs).toTypedArray(), beforeQuery = beforeQuery).use { rows ->
                while (rows.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val key = SyncParent(CalendarId(rows.requiredLong("calendar_id")), rows.text("_sync_id") ?: continue)
                    if (key !in chunk) continue
                    check(rows.number("deleted") == 0L && rows.number("original_id") == null && rows.text("original_sync_id").isNullOrBlank())
                    val id = rows.requiredLong("_id")
                    check(result.putIfAbsent(key, id) == null) { "Ambiguous recurrence parent" }
                }
            }
        }
        return result
    }

    private fun Cursor.occurrence(calendar: CalendarInfo?, parents: Map<SyncParent, Long>, includeInvitation: Boolean = false): EventOccurrence {
        val start = Instant.ofEpochMilli(requiredLong(CalendarContract.Instances.BEGIN))
        val end = Instant.ofEpochMilli(requiredLong(CalendarContract.Instances.END))
        val eventId = requiredLong(CalendarContract.Instances.EVENT_ID)
        val originalId = number(CalendarContract.Events.ORIGINAL_ID)
        val originalStart = number(CalendarContract.Events.ORIGINAL_INSTANCE_TIME)
        val originalSync = text(CalendarContract.Events.ORIGINAL_SYNC_ID)?.takeIf { it.isNotBlank() }
        val calendarId = CalendarId(requiredLong(CalendarContract.Instances.CALENDAR_ID))
        val linked = !text(CalendarContract.Events.ORIGINAL_ID).isNullOrBlank() ||
            !text(CalendarContract.Events.ORIGINAL_INSTANCE_TIME).isNullOrBlank() || originalSync != null
        val recurrence = if (linked) {
            check(originalStart != null)
            check(text(CalendarContract.Events.ORIGINAL_ID).isNullOrBlank() || originalId != null)
            val syncParent = originalSync?.let { parents[SyncParent(calendarId, it)] ?: error("Unresolved recurrence parent") }
            check(originalId == null || syncParent == null || originalId == syncParent)
            RecurrenceRef(originalId ?: checkNotNull(syncParent), Instant.ofEpochMilli(originalStart))
        }
            else if (!text(CalendarContract.Events.RRULE).isNullOrBlank() || !text(CalendarContract.Events.RDATE).isNullOrBlank()) RecurrenceRef(eventId, start) else null
        val ref = EventRef(CalendarId(requiredLong(CalendarContract.Instances.CALENDAR_ID)), eventId, start, end, recurrence)
        return EventOccurrence(ref,
            if (number(CalendarContract.Instances.ALL_DAY) == 1L) EventTime.AllDay.fromProvider(start, end) else EventTime.Timed(start, end),
            if (calendar?.capabilities?.readDetails == AccessDecision.ALLOWED) text(CalendarContract.Instances.TITLE).orEmpty() else "Busy",
            if (calendar?.capabilities?.readDetails == AccessDecision.ALLOWED)
                number(CalendarContract.Instances.DISPLAY_COLOR)?.toInt() ?: calendar.color else calendar?.color ?: FallbackColor,
            calendar?.capabilities?.readDetails == AccessDecision.ALLOWED &&
                (if (includeInvitation) invitationNumber(CalendarContract.Instances.SELF_ATTENDEE_STATUS) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED.toLong()
                 else number(CalendarContract.Instances.SELF_ATTENDEE_STATUS)?.toInt() == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED),
            if (includeInvitation && calendar?.capabilities?.readDetails == AccessDecision.ALLOWED) {
                val organizer = when (invitationNumber(CalendarContract.Events.IS_ORGANIZER)) { 0L -> false; 1L -> true; else -> null }
                classifyInvitation(organizer, invitationNumber(CalendarContract.Events.SELF_ATTENDEE_STATUS))
            } else InvitationState.UNKNOWN)
    }

    private fun Cursor.invitationNumber(name: String): Long? {
        val raw = text(name) ?: return null
        return raw.toLongOrNull()?.takeIf { it.toString() == raw }
    }

    private fun Cursor.booleanFlag(name: String): Boolean? = when (number(name)) {
        1L -> true
        0L -> false
        else -> null
    }
    private fun Cursor.permissionFlag(name: String): AccessDecision = when (booleanFlag(name)) {
        true -> AccessDecision.ALLOWED
        false -> AccessDecision.DENIED
        null -> AccessDecision.UNKNOWN
    }
    private fun Cursor.text(name: String): String? = getColumnIndex(name).takeIf { it >= 0 && !isNull(it) }?.let(::getString)
    private fun Cursor.number(name: String): Long? = getColumnIndex(name).takeIf { it >= 0 && !isNull(it) }?.let(::getLong)
    private fun Cursor.requiredLong(name: String): Long = number(name) ?: throw IllegalStateException("Missing required calendar field")
    private fun zone(value: String?): ZoneId? = value?.let { runCatching { ZoneId.of(it) }.getOrNull() }
    /** Preserve representable observations without coercing a wide or noncanonical value into rights. */
    private fun Cursor.calendarAccessObservation(): Int? {
        val raw = text(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL) ?: return null
        val value = raw.toLongOrNull()?.takeIf { it.toString() == raw } ?: return null
        return value.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()
    }

    private fun Int?.decision(threshold: Int) = when {
        this == null -> AccessDecision.UNKNOWN
        this !in setOf(CalendarContract.Calendars.CAL_ACCESS_NONE, CalendarContract.Calendars.CAL_ACCESS_FREEBUSY,
            CalendarContract.Calendars.CAL_ACCESS_READ, CalendarContract.Calendars.CAL_ACCESS_RESPOND,
            CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR, CalendarContract.Calendars.CAL_ACCESS_EDITOR,
            CalendarContract.Calendars.CAL_ACCESS_OWNER, CalendarContract.Calendars.CAL_ACCESS_ROOT) -> AccessDecision.UNKNOWN
        this >= threshold -> AccessDecision.ALLOWED
        else -> AccessDecision.DENIED
    }

    companion object {
        private const val FallbackColor = -8947849
        private val navigationFields = arrayOf(CalendarContract.Events._ID, CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.UID_2445, CalendarContract.Events.DELETED, CalendarContract.Events.STATUS,
            CalendarContract.Events.ALL_DAY, CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND,
            CalendarContract.Events.DURATION, CalendarContract.Events.EVENT_TIMEZONE,
            CalendarContract.Events.RRULE, CalendarContract.Events.RDATE, CalendarContract.Events.EXRULE,
            CalendarContract.Events.EXDATE, CalendarContract.Events.ORIGINAL_ID,
            CalendarContract.Events.ORIGINAL_SYNC_ID, CalendarContract.Events.ORIGINAL_INSTANCE_TIME)
        private val calendarFields = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR, CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.OWNER_ACCOUNT, CalendarContract.Calendars.CALENDAR_TIME_ZONE,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.VISIBLE, CalendarContract.Calendars.SYNC_EVENTS,
            CalendarContract.Calendars._SYNC_ID)
        private val instanceFields = arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.TITLE,
            CalendarContract.Instances.DISPLAY_COLOR, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.SELF_ATTENDEE_STATUS,
            CalendarContract.Events.ORIGINAL_ID, CalendarContract.Events.ORIGINAL_INSTANCE_TIME, CalendarContract.Events.ORIGINAL_SYNC_ID,
            CalendarContract.Events.RRULE, CalendarContract.Events.RDATE)
        private val detailFields = arrayOf(CalendarContract.Events.CALENDAR_ID, CalendarContract.Events.TITLE,
            CalendarContract.Events.DISPLAY_COLOR, CalendarContract.Events.ALL_DAY, CalendarContract.Events.SELF_ATTENDEE_STATUS,
            CalendarContract.Events.EVENT_LOCATION, CalendarContract.Events.DESCRIPTION, CalendarContract.Events.ORGANIZER,
            CalendarContract.Events.EVENT_TIMEZONE, CalendarContract.Events.RRULE, CalendarContract.Events.RDATE,
            CalendarContract.Events.ORIGINAL_ID, CalendarContract.Events.ORIGINAL_SYNC_ID, CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND,
            CalendarContract.Events.AVAILABILITY, CalendarContract.Events.ACCESS_LEVEL, CalendarContract.Events.STATUS,
            CalendarContract.Events.IS_ORGANIZER, CalendarContract.Events.GUESTS_CAN_MODIFY,
            CalendarContract.Events.GUESTS_CAN_INVITE_OTHERS, CalendarContract.Events.GUESTS_CAN_SEE_GUESTS)
        private val attendeeFields = arrayOf(CalendarContract.Attendees.ATTENDEE_EMAIL, CalendarContract.Attendees.ATTENDEE_NAME,
            CalendarContract.Attendees.ATTENDEE_STATUS, CalendarContract.Attendees.ATTENDEE_RELATIONSHIP, CalendarContract.Attendees.ATTENDEE_TYPE)
        private val reminderFields = arrayOf(CalendarContract.Reminders.MINUTES, CalendarContract.Reminders.METHOD)
    }
}

internal data class CalendarOccurrencePage(val occurrences: List<EventOccurrence>, val scannedRows: Int, val truncated: Boolean)
