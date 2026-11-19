package dev.swapnilraj.calendar.ui.month

import dev.swapnilraj.calendar.core.CalendarDateRange
import dev.swapnilraj.calendar.core.EventOccurrence
import dev.swapnilraj.calendar.core.EventTime
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Always six complete weeks; this same range is the caller's single provider request. */
fun monthDates(month: YearMonth, firstDayOfWeek: DayOfWeek): List<LocalDate> {
    val first = month.atDay(1)
    val offset = Math.floorMod(first.dayOfWeek.value - firstDayOfWeek.value, 7)
    val start = first.minusDays(offset.toLong())
    return List(42) { start.plusDays(it.toLong()) }
}

internal fun monthEventsByDate(dates: List<LocalDate>, events: List<EventOccurrence>, zone: ZoneId): Map<LocalDate, List<EventOccurrence>> {
    val sorted = events.distinctBy { it.ref }.sortedWith(compareBy<EventOccurrence> { it.time !is EventTime.AllDay }
        .thenBy { it.ref.instanceStart }.thenBy { it.ref.calendarId.value }.thenBy { it.ref.eventId })
    return dates.associateWith { date ->
        val range = CalendarDateRange.day(date)
        sorted.filter { it.time.intersects(range, zone) }
    }
}

internal data class MonthContinuation(val before: Boolean, val after: Boolean)
internal fun continuation(event: EventOccurrence, date: LocalDate, zone: ZoneId): MonthContinuation = when (val time = event.time) {
    is EventTime.AllDay -> MonthContinuation(time.dates.start.isBefore(date), time.dates.endExclusive.isAfter(date.plusDays(1)))
    is EventTime.Timed -> MonthContinuation(time.start.isBefore(date.atStartOfDay(zone).toInstant()),
        time.end.isAfter(date.plusDays(1).atStartOfDay(zone).toInstant()))
}

/** Presentation only: the date matrix and provider range remain six complete weeks. */
internal data class MonthOverviewBudget(
    val rowHeight: Float,
    val weekdayHeight: Float,
    val dateHeight: Float,
    val summaryHeight: Float,
    val slots: Int,
) {
    fun visibleEvents(count: Int): Int = if (count <= slots) count else (slots - 1).coerceAtLeast(0)
}

internal fun monthOverviewBudget(width: Float, height: Float, columns: Int, fontScale: Float,
    dateLine: Float, summaryLine: Float, weekdayLine: Float): MonthOverviewBudget? {
    if (fontScale > 1.3f || width >= 600f || width / columns < 48f) return null
    val weekdayHeight = maxOf(32f, weekdayLine + 16f)
    val dateHeight = maxOf(32f * fontScale, dateLine + 8f)
    val summaryHeight = maxOf(20f, summaryLine + 4f)
    val rowHeight = kotlin.math.floor((height - weekdayHeight) / 6f)
    val slots = kotlin.math.floor((rowHeight - dateHeight - 4f) / summaryHeight).toInt()
    if (rowHeight < 48f || slots < 1) return null
    return MonthOverviewBudget(rowHeight, weekdayHeight, dateHeight, summaryHeight, slots)
}
