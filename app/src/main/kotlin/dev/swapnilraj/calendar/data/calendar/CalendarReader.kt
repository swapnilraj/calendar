package dev.swapnilraj.calendar.data.calendar

import android.content.ContentResolver
import android.content.ContentUris
import android.database.ContentObserver
import android.database.Cursor
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

    // Look up on collection, not when constructing the Flow. Old Flow references can safely
    // resubscribe after idle entries are removed without creating duplicate live queries.
    fun observeEvents(request: CalendarRequest): Flow<CalendarReadState<CalendarSnapshot>> = flow {
        val entry = synchronized(requests) {
            requests.getOrPut(request) {
                val lifetime = SupervisorJob(scope.coroutineContext[Job])
                val requestScope = CoroutineScope(scope.coroutineContext + lifetime)
                RequestEntry(changes.mapLatest { guarded { querySnapshot(request) } }
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

    private suspend fun <T> guarded(block: suspend () -> T): CalendarReadState<T> = try {
        withContext(io) { CalendarReadState.Ready(block()) }
    } catch (_: SecurityException) {
        CalendarReadState.PermissionRequired
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        CalendarReadState.Unavailable
    }

    /** CancellationSignal also aborts a query blocked inside another provider process. */
    private suspend fun query(uri: Uri, fields: Array<String>, selection: String? = null,
        args: Array<String>? = null, order: String? = null): Cursor = suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        continuation.invokeOnCancellation { signal.cancel() }
        try {
            val cursor = resolver.query(uri, fields, selection, args, order, signal)
                ?: throw IllegalStateException("Calendar provider unavailable")
            continuation.resume(cursor, onCancellation = { _, resource, _ -> resource.close() })
        } catch (failure: Exception) { continuation.resumeWithException(failure) }
    }

    private suspend fun queryCalendars(): List<CalendarInfo> = query(CalendarContract.Calendars.CONTENT_URI,
        calendarFields, order = "${CalendarContract.Calendars._ID} ASC").use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                val access = cursor.number(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)?.toInt()
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
                    if (calendar.accessLevel?.let { it < CalendarContract.Calendars.CAL_ACCESS_FREEBUSY } == true)
                        add(CalendarCoverage(id, CoverageReason.INSUFFICIENT_ACCESS))
                }
            }
        }
        val eligible = selected.filter { id -> byId[id]?.let {
            it.accessLevel?.let { access -> access >= CalendarContract.Calendars.CAL_ACCESS_FREEBUSY } != false
        } == true }
        if (eligible.isEmpty()) return CalendarSnapshot(request, calendars, emptyList(), coverage)
        val local = request.range.instantBounds(request.displayZone)
        val utc = request.range.instantBounds(ZoneOffset.UTC)
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
            ContentUris.appendId(this, minOf(local.start, utc.start).toEpochMilli())
            ContentUris.appendId(this, maxOf(local.endExclusive, utc.endExclusive).toEpochMilli())
        }.build()
        val selection = "${CalendarContract.Instances.CALENDAR_ID} IN (${eligible.joinToString(",") { "?" }})"
        val occurrences = query(uri, instanceFields, selection, eligible.map { it.value.toString() }.toTypedArray(),
            "${CalendarContract.Instances.BEGIN} ASC, ${CalendarContract.Instances.EVENT_ID} ASC").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val id = CalendarId(cursor.requiredLong(CalendarContract.Instances.CALENDAR_ID))
                    if (id !in eligible) continue // Do not trust a provider ignoring selection.
                    val occurrence = cursor.occurrence(byId[id])
                    if (occurrence.time.intersects(request.range, request.displayZone)) add(occurrence)
                }
            }
        }.distinctBy { it.ref }.sortedWith(compareBy<EventOccurrence> { it.ref.instanceStart }
            .thenBy { it.ref.eventId }.thenBy { it.ref.calendarId.value })
        return CalendarSnapshot(request, calendars, occurrences, coverage)
    }

    suspend fun readDetails(ref: EventRef): CalendarReadState<EventDetails> = guarded {
        val calendar = queryCalendars().firstOrNull { it.id == ref.calendarId }
            ?: throw IllegalStateException("Calendar no longer available")
        // A retained navigation reference must not display the old time of a rescheduled or
        // deleted occurrence. Validate it against the currently expanded instances first.
        val instanceUri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
            ContentUris.appendId(this, ref.instanceStart.toEpochMilli())
            ContentUris.appendId(this, maxOf(ref.instanceEnd.toEpochMilli(), ref.instanceStart.toEpochMilli() + 1))
        }.build()
        val current = query(instanceUri, instanceFields,
            "${CalendarContract.Instances.EVENT_ID} = ? AND ${CalendarContract.Instances.CALENDAR_ID} = ?",
            arrayOf(ref.eventId.toString(), ref.calendarId.value.toString())).use { instances ->
            var found: EventOccurrence? = null
            while (instances.moveToNext()) {
                val candidate = instances.occurrence(calendar)
                if (candidate.ref.eventId == ref.eventId && candidate.ref.calendarId == ref.calendarId &&
                    candidate.ref.instanceStart == ref.instanceStart && candidate.ref.instanceEnd == ref.instanceEnd) found = candidate
            }
            found ?: throw IllegalStateException("Occurrence no longer available")
        }
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, ref.eventId)
        query(uri, detailFields).use { cursor ->
            check(cursor.moveToFirst()) { "Event no longer available" }
            check(cursor.requiredLong(CalendarContract.Events.CALENDAR_ID) == ref.calendarId.value)
            val standalone = cursor.text(CalendarContract.Events.RRULE).isNullOrBlank() &&
                cursor.text(CalendarContract.Events.RDATE).isNullOrBlank() && cursor.number(CalendarContract.Events.ORIGINAL_ID) == null
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
        attendeeFields, "${CalendarContract.Attendees.EVENT_ID} = ?", arrayOf(eventId.toString())).use { cursor ->
        buildList { while (cursor.moveToNext()) add(EventAttendee(cursor.text(CalendarContract.Attendees.ATTENDEE_EMAIL).orEmpty(),
            cursor.text(CalendarContract.Attendees.ATTENDEE_NAME), cursor.number(CalendarContract.Attendees.ATTENDEE_STATUS)?.toInt() ?: 0,
            cursor.number(CalendarContract.Attendees.ATTENDEE_RELATIONSHIP)?.toInt() ?: 0,
            cursor.number(CalendarContract.Attendees.ATTENDEE_TYPE)?.toInt() ?: 0)) }
    }

    private suspend fun readReminders(eventId: Long): List<EventReminder> = query(CalendarContract.Reminders.CONTENT_URI,
        reminderFields, "${CalendarContract.Reminders.EVENT_ID} = ?", arrayOf(eventId.toString())).use { cursor ->
        buildList { while (cursor.moveToNext()) add(EventReminder(cursor.requiredLong(CalendarContract.Reminders.MINUTES).toInt(),
            cursor.requiredLong(CalendarContract.Reminders.METHOD).toInt())) }
    }

    private fun Cursor.occurrence(calendar: CalendarInfo?): EventOccurrence {
        val start = Instant.ofEpochMilli(requiredLong(CalendarContract.Instances.BEGIN))
        val end = Instant.ofEpochMilli(requiredLong(CalendarContract.Instances.END))
        val eventId = requiredLong(CalendarContract.Instances.EVENT_ID)
        val originalId = number(CalendarContract.Events.ORIGINAL_ID)
        val originalStart = number(CalendarContract.Events.ORIGINAL_INSTANCE_TIME)
        val recurrence = if (originalId != null && originalStart != null) RecurrenceRef(originalId, Instant.ofEpochMilli(originalStart))
            else if (!text(CalendarContract.Events.RRULE).isNullOrBlank() || !text(CalendarContract.Events.RDATE).isNullOrBlank()) RecurrenceRef(eventId, start) else null
        val ref = EventRef(CalendarId(requiredLong(CalendarContract.Instances.CALENDAR_ID)), eventId, start, end, recurrence)
        return EventOccurrence(ref,
            if (number(CalendarContract.Instances.ALL_DAY) == 1L) EventTime.AllDay.fromProvider(start, end) else EventTime.Timed(start, end),
            if (calendar?.capabilities?.readDetails == AccessDecision.ALLOWED) text(CalendarContract.Instances.TITLE).orEmpty() else "Busy",
            if (calendar?.capabilities?.readDetails == AccessDecision.ALLOWED)
                number(CalendarContract.Instances.DISPLAY_COLOR)?.toInt() ?: calendar.color else calendar?.color ?: FallbackColor,
            calendar?.capabilities?.readDetails == AccessDecision.ALLOWED &&
                number(CalendarContract.Instances.SELF_ATTENDEE_STATUS)?.toInt() == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED)
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
        private val calendarFields = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR, CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.OWNER_ACCOUNT, CalendarContract.Calendars.CALENDAR_TIME_ZONE,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.VISIBLE, CalendarContract.Calendars.SYNC_EVENTS)
        private val instanceFields = arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.TITLE,
            CalendarContract.Instances.DISPLAY_COLOR, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.SELF_ATTENDEE_STATUS,
            CalendarContract.Events.ORIGINAL_ID, CalendarContract.Events.ORIGINAL_INSTANCE_TIME, CalendarContract.Events.RRULE, CalendarContract.Events.RDATE)
        private val detailFields = arrayOf(CalendarContract.Events.CALENDAR_ID, CalendarContract.Events.TITLE,
            CalendarContract.Events.DISPLAY_COLOR, CalendarContract.Events.ALL_DAY, CalendarContract.Events.SELF_ATTENDEE_STATUS,
            CalendarContract.Events.EVENT_LOCATION, CalendarContract.Events.DESCRIPTION, CalendarContract.Events.ORGANIZER,
            CalendarContract.Events.EVENT_TIMEZONE, CalendarContract.Events.RRULE, CalendarContract.Events.RDATE,
            CalendarContract.Events.ORIGINAL_ID, CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND,
            CalendarContract.Events.AVAILABILITY, CalendarContract.Events.ACCESS_LEVEL, CalendarContract.Events.STATUS,
            CalendarContract.Events.IS_ORGANIZER, CalendarContract.Events.GUESTS_CAN_MODIFY,
            CalendarContract.Events.GUESTS_CAN_INVITE_OTHERS, CalendarContract.Events.GUESTS_CAN_SEE_GUESTS)
        private val attendeeFields = arrayOf(CalendarContract.Attendees.ATTENDEE_EMAIL, CalendarContract.Attendees.ATTENDEE_NAME,
            CalendarContract.Attendees.ATTENDEE_STATUS, CalendarContract.Attendees.ATTENDEE_RELATIONSHIP, CalendarContract.Attendees.ATTENDEE_TYPE)
        private val reminderFields = arrayOf(CalendarContract.Reminders.MINUTES, CalendarContract.Reminders.METHOD)
    }
}
