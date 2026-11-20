package dev.swapnilraj.calendar.ui.agenda

import dev.swapnilraj.calendar.core.CalendarDateRange
import dev.swapnilraj.calendar.core.EventOccurrence
import dev.swapnilraj.calendar.core.EventTime
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

internal val AgendaMinDate: LocalDate = LocalDate.of(1, 1, 1)
internal val AgendaMaxDate: LocalDate = LocalDate.of(9999, 12, 31)
private val AgendaMaxEnd = AgendaMaxDate.plusDays(1)

internal fun validateAgendaRange(range: CalendarDateRange) {
    require(range.start >= AgendaMinDate && range.endExclusive <= AgendaMaxEnd) { "Agenda dates must be within years 1..9999" }
    require(ChronoUnit.DAYS.between(range.start, range.endExclusive) <= 366) { "Agenda windows must contain at most 366 days" }
}

internal fun agendaWindow(date: LocalDate, days: Long): CalendarDateRange {
    require(date in AgendaMinDate..AgendaMaxDate) { "Agenda dates must be within years 1..9999" }
    require(days in 1..366) { "Agenda windows must contain 1..366 days" }
    val end = minOf(date.plusDays(days), AgendaMaxEnd)
    val start = maxOf(AgendaMinDate, end.minusDays(days))
    return CalendarDateRange(start, end)
}

internal fun adjacentAgendaRange(range: CalendarDateRange, later: Boolean): CalendarDateRange? {
    validateAgendaRange(range)
    val days = ChronoUnit.DAYS.between(range.start, range.endExclusive)
    return if (later) {
        if (range.endExclusive == AgendaMaxEnd) null
        else CalendarDateRange(range.endExclusive, minOf(range.endExclusive.plusDays(days), AgendaMaxEnd))
    } else {
        if (range.start == AgendaMinDate) null
        else CalendarDateRange(maxOf(range.start.minusDays(days), AgendaMinDate), range.start)
    }
}

internal data class AgendaDay(val date: LocalDate, val events: List<EventOccurrence>)

/** Sparse date groups, with an explicit empty selected date. A bounded window prevents runaway UI work. */
internal fun agendaDays(range: CalendarDateRange, selectedDate: LocalDate, events: List<EventOccurrence>, zone: ZoneId): List<AgendaDay> {
    validateAgendaRange(range)
    require(selectedDate in AgendaMinDate..AgendaMaxDate) { "Selected date must be within years 1..9999" }
    val sorted = events.distinctBy { it.ref }.sortedWith(compareBy<EventOccurrence> { it.time !is EventTime.AllDay }
        .thenBy { it.ref.instanceStart }.thenBy { it.ref.calendarId.value }.thenBy { it.ref.eventId })
    val count = ChronoUnit.DAYS.between(range.start, range.endExclusive).toInt()
    return (0 until count).mapNotNull { offset ->
        val date = range.start.plusDays(offset.toLong())
        val dayRange = CalendarDateRange.day(date)
        val matches = sorted.filter { it.time.intersects(dayRange, zone) }
        if (matches.isNotEmpty() || date == selectedDate) AgendaDay(date, matches) else null
    }
}
