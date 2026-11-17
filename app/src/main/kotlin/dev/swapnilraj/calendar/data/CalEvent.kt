package dev.swapnilraj.calendar.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

data class CalEvent(
    val id: Long,
    val start: Instant,
    val end: Instant,
    val title: String,
    val color: Int,
    val allDay: Boolean = false,
    val declined: Boolean = false,
) {
    val key: String get() = "$id:${start.toEpochMilli()}"

    fun occursOn(date: LocalDate, zone: ZoneId): Boolean {
        // CalendarContract stores all-day boundaries as UTC dates, independent of device offset.
        if (allDay) return date >= start.atOffset(ZoneOffset.UTC).toLocalDate() &&
            date < end.atOffset(ZoneOffset.UTC).toLocalDate()
        val begin = date.atStartOfDay(zone).toInstant()
        val finish = date.plusDays(1).atStartOfDay(zone).toInstant()
        return start < finish && (end > begin || end == start && start >= begin)
    }
}
