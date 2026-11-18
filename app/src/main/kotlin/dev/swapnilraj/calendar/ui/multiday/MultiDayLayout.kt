package dev.swapnilraj.calendar.ui.multiday

import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.CalEvent
import java.time.*
import java.time.temporal.ChronoUnit

internal val MultiDayMinDate: LocalDate = LocalDate.of(1, 1, 1)
internal val MultiDayMaxDate: LocalDate = LocalDate.of(9999, 12, 31)

internal fun multiDayDates(range: CalendarDateRange): List<LocalDate> {
    val days = ChronoUnit.DAYS.between(range.start, range.endExclusive)
    require(days == 3L || days == 7L) { "Multi-day ranges must contain exactly 3 or 7 dates" }
    require(range.start >= MultiDayMinDate && range.endExclusive <= MultiDayMaxDate.plusDays(1)) { "Dates must be within years 1..9999" }
    return (0 until days).map { range.start.plusDays(it) }
}

internal fun multiDayWindow(date: LocalDate, days: Int): CalendarDateRange {
    require(date in MultiDayMinDate..MultiDayMaxDate)
    require(days == 3 || days == 7)
    val end = minOf(date.plusDays(days.toLong()), MultiDayMaxDate.plusDays(1))
    return CalendarDateRange(end.minusDays(days.toLong()), end)
}

internal fun adjacentMultiDayRange(range: CalendarDateRange, later: Boolean): CalendarDateRange? {
    val days = multiDayDates(range).size.toLong()
    val start = if (later) range.endExclusive else range.start.minusDays(days)
    val end = start.plusDays(days)
    return if (start < MultiDayMinDate || end > MultiDayMaxDate.plusDays(1)) null else CalendarDateRange(start, end)
}

/** An adapter to the existing day renderer, never a second placement engine. */
internal fun multiDayEvents(range: CalendarDateRange, events: List<EventOccurrence>, zone: ZoneId): Map<LocalDate, List<CalEvent>> {
    val unique = events.distinctBy { it.ref }
    return multiDayDates(range).associateWith { date ->
        unique.filter { it.time.intersects(CalendarDateRange.day(date), zone) }
            .sortedWith(compareBy<EventOccurrence> { it.time !is EventTime.AllDay }.thenBy { it.ref.instanceStart }.thenBy { it.ref.eventId })
            .map { occurrence ->
                val (start, end) = when (val time = occurrence.time) {
                    is EventTime.Timed -> time.start to time.end
                    is EventTime.AllDay -> time.dates.start.atStartOfDay(ZoneOffset.UTC).toInstant() to time.dates.endExclusive.atStartOfDay(ZoneOffset.UTC).toInstant()
                }
                CalEvent(occurrence.ref.eventId, start, end, occurrence.title, occurrence.color,
                    occurrence.time is EventTime.AllDay, occurrence.declined, occurrence.ref)
            }
    }
}
