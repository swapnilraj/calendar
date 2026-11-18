package dev.swapnilraj.calendar.android.navigation

import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.calendar.EventNavigationInfo
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.*

sealed interface CalendarEntryResolution {
    data class Date(val date: LocalDate, val instant: Instant) : CalendarEntryResolution
    data class Event(val ref: EventRef, val binding: CalendarEntryBinding, val proposedEdit: CalendarDraftSeed? = null) : CalendarEntryResolution
    /** Nullable/unsupported seed fields remain inputs for explicit native-form review. */
    data class CreateSeed(val seed: CalendarDraftSeed) : CalendarEntryResolution
    data class NeedsOccurrence(val eventId: Long) : CalendarEntryResolution
    data object PermissionRequired : CalendarEntryResolution
    data object LimitExceeded : CalendarEntryResolution
    data object Stale : CalendarEntryResolution
    data object Unavailable : CalendarEntryResolution
    data object Unsupported : CalendarEntryResolution
    data class Invalid(val reason: CalendarIntentError) : CalendarEntryResolution
}

/** Read-only occurrence resolution. No wall-clock fallback, guessed calendar/ref, or mutation port. */
class IncomingCalendarResolver(private val source: IncomingCalendarSource, private val timeoutMillis: Long = 15_000) {
    init { require(timeoutMillis > 0) }
    suspend fun resolve(session: CalendarEntrySession, displayZone: ZoneId): CalendarEntryResolution = try {
        withContext(Dispatchers.IO) { withTimeout(timeoutMillis) {
            when (val route = session.route) {
                is CalendarIntentRoute.OpenDate -> if (route.instant.atZone(displayZone).year in 1..9999)
                    CalendarEntryResolution.Date(route.instant.atZone(displayZone).toLocalDate(), route.instant)
                    else CalendarEntryResolution.Invalid(CalendarIntentError.OUT_OF_RANGE_TIME)
                is CalendarIntentRoute.CreateDraftSeed -> {
                    val zone = if (route.seed.occurrence.allDay == true) ZoneOffset.UTC else route.seed.timeZone ?: displayZone
                    if (listOfNotNull(route.seed.occurrence.begin, route.seed.occurrence.end).any { it.atZone(zone).year !in 1..9999 })
                        CalendarEntryResolution.Invalid(CalendarIntentError.OUT_OF_RANGE_TIME)
                    else CalendarEntryResolution.CreateSeed(route.seed)
                }
                is CalendarIntentRoute.OpenEvent -> event(session, route.eventId, route.occurrence, displayZone, null)
                is CalendarIntentRoute.EditEvent -> event(session, route.eventId, route.seed.occurrence, displayZone, route.seed)
                is CalendarIntentRoute.Invalid -> CalendarEntryResolution.Invalid(route.reason)
                CalendarIntentRoute.Unsupported -> CalendarEntryResolution.Unsupported
            }
        } }
    } catch (_: TimeoutCancellationException) { CalendarEntryResolution.Unavailable }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: SecurityException) { CalendarEntryResolution.PermissionRequired }
    catch (_: Exception) { CalendarEntryResolution.Unavailable }

    private suspend fun event(session: CalendarEntrySession, id: Long, hints: CalendarOccurrenceHints,
        zone: ZoneId, proposedEdit: CalendarDraftSeed?): CalendarEntryResolution {
        val read = source.readEventInfo(id)
        val info = when (read) {
            CalendarReadState.PermissionRequired -> return CalendarEntryResolution.PermissionRequired
            CalendarReadState.LimitExceeded -> return CalendarEntryResolution.LimitExceeded
            is CalendarReadState.Ready -> read.value ?: return CalendarEntryResolution.Unavailable
            else -> return CalendarEntryResolution.Unavailable
        }
        if (info.eventId != id || !session.bind(info)) return CalendarEntryResolution.Stale
        // Pin immediately, before any occurrence query that may suspend or be cancelled.
        if (info.recurringOrDetached && hints.begin == null) return CalendarEntryResolution.NeedsOccurrence(id)
        if (hints.allDay != null && hints.allDay != info.allDay) return CalendarEntryResolution.Stale
        val begin = hints.begin ?: info.start
        val date = begin.atZone(if (info.allDay) ZoneOffset.UTC else zone).toLocalDate()
        if (date.year !in 1..9999) return CalendarEntryResolution.Invalid(CalendarIntentError.OUT_OF_RANGE_TIME)
        if (!info.recurringOrDetached && info.endExclusive == null) return CalendarEntryResolution.Unavailable
        val request = CalendarRequest(CalendarDateRange.day(date), zone, CalendarSelection.Explicit(setOf(info.calendar.calendarId)))
        val snapshot = when (val result = source.readSnapshot(request)) {
            CalendarReadState.PermissionRequired -> return CalendarEntryResolution.PermissionRequired
            CalendarReadState.LimitExceeded -> return CalendarEntryResolution.LimitExceeded
            is CalendarReadState.Ready -> result.value
            else -> return CalendarEntryResolution.Unavailable
        }
        if (snapshot.request != request) return CalendarEntryResolution.Unavailable
        val calendar = snapshot.calendars.singleOrNull { it.id == info.calendar.calendarId }
            ?: return CalendarEntryResolution.Unavailable
        if (!identityMatches(requireNotNull(session.binding).calendar, calendar.identity)) return CalendarEntryResolution.Stale
        val matches = snapshot.occurrences.filter { occurrence ->
            val ref = occurrence.ref
            ref.calendarId == info.calendar.calendarId &&
                (ref.eventId == id || ref.recurrence?.masterEventId == id) &&
                ((occurrence.time is EventTime.AllDay) == info.allDay) &&
                matchesBounds(occurrence, hints, info)
        }.distinctBy { it.ref }
        if (matches.size != 1) return if (matches.size > 1) CalendarEntryResolution.NeedsOccurrence(id) else CalendarEntryResolution.Stale
        val occurrence = matches.single()
        if (info.recurringOrDetached && occurrence.ref.recurrence == null) return CalendarEntryResolution.NeedsOccurrence(id)
        if (!info.recurringOrDetached && occurrence.ref.recurrence != null) return CalendarEntryResolution.Stale
        when (val latest = source.readEventInfo(id)) {
            CalendarReadState.PermissionRequired -> return CalendarEntryResolution.PermissionRequired
            CalendarReadState.LimitExceeded -> return CalendarEntryResolution.LimitExceeded
            is CalendarReadState.Ready -> {
                val current = latest.value ?: return CalendarEntryResolution.Stale
                if (!sameRow(info, current) || !session.bind(current)) return CalendarEntryResolution.Stale
            }
            else -> return CalendarEntryResolution.Unavailable
        }
        return CalendarEntryResolution.Event(occurrence.ref, requireNotNull(session.binding), proposedEdit)
    }

    private fun matchesBounds(occurrence: EventOccurrence, hints: CalendarOccurrenceHints, info: EventNavigationInfo): Boolean {
        val begin = hints.begin ?: info.start
        val end = hints.end ?: info.endExclusive.takeUnless { info.recurringOrDetached }
        return when (val time = occurrence.time) {
            is EventTime.Timed -> time.start == begin && (end == null || time.end == end)
            is EventTime.AllDay -> time.dates.start == begin.atOffset(ZoneOffset.UTC).toLocalDate() &&
                (end == null || time.dates.endExclusive == end.atOffset(ZoneOffset.UTC).toLocalDate())
        }
    }

    private fun sameRow(first: EventNavigationInfo, second: EventNavigationInfo): Boolean =
        first.eventId == second.eventId && identityMatches(first.calendar, second.calendar) &&
            (first.providerUid == null || first.providerUid == second.providerUid) &&
            first.allDay == second.allDay && first.start == second.start && first.endExclusive == second.endExclusive &&
            first.recurringOrDetached == second.recurringOrDetached

    /** Resolve again on both sides of the independent details read, keeping the original pin. */
    suspend fun readDetails(session: CalendarEntrySession, resolved: CalendarEntryResolution.Event, zone: ZoneId): CalendarReadState<EventDetails> = try {
        withContext(Dispatchers.IO) { withTimeout(timeoutMillis) {
            val before = resolve(session, zone)
            if (before == CalendarEntryResolution.PermissionRequired) return@withTimeout CalendarReadState.PermissionRequired
            if (before == CalendarEntryResolution.LimitExceeded) return@withTimeout CalendarReadState.LimitExceeded
            if (before !is CalendarEntryResolution.Event || before.ref != resolved.ref || !bindingMatches(resolved.binding, before.binding)) return@withTimeout CalendarReadState.Unavailable
            val details = source.readDetails(before.ref)
            if (details !is CalendarReadState.Ready) return@withTimeout details
            val calendar = details.value.calendar?.identity ?: return@withTimeout CalendarReadState.Unavailable
            val pin = session.binding ?: return@withTimeout CalendarReadState.Unavailable
            if (details.value.occurrence.ref != before.ref || !identityMatches(pin.calendar, calendar)) return@withTimeout CalendarReadState.Unavailable
            when (val after = resolve(session, zone)) {
                CalendarEntryResolution.PermissionRequired -> CalendarReadState.PermissionRequired
                CalendarEntryResolution.LimitExceeded -> CalendarReadState.LimitExceeded
                is CalendarEntryResolution.Event -> if (after.ref == before.ref) details else CalendarReadState.Unavailable
                else -> CalendarReadState.Unavailable
            }
        } }
    } catch (_: TimeoutCancellationException) { CalendarReadState.Unavailable }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: SecurityException) { CalendarReadState.PermissionRequired }
    catch (_: Exception) { CalendarReadState.Unavailable }
}
