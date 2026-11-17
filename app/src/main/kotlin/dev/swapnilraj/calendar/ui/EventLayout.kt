package dev.swapnilraj.calendar.ui

import dev.swapnilraj.calendar.data.CalEvent
import java.time.LocalDate
import java.time.ZoneId

data class TimedEvent(val event: CalEvent, val startMinute: Double, val endMinute: Double)
data class PlacedEvent(val timed: TimedEvent, val column: Int, val columns: Int)

fun timedEvents(events: List<CalEvent>, date: LocalDate, zone: ZoneId): List<TimedEvent> {
    val begin = date.atStartOfDay(zone).toInstant()
    val finish = date.plusDays(1).atStartOfDay(zone).toInstant()
    return events.filter { !it.allDay && it.occursOn(date, zone) }.map { e ->
        val start = if (e.start <= begin) 0.0 else e.start.atZone(zone).toLocalTime().toSecondOfDay() / 60.0
        val end = if (e.end >= finish) 1440.0 else e.end.atZone(zone).toLocalTime().toSecondOfDay() / 60.0
        TimedEvent(e, start, maxOf(start, end))
    }
}

/** Minimum visible duration participates in collision detection, including zero-length events. */
fun layoutEvents(events: List<TimedEvent>, minimumMinutes: Double): List<PlacedEvent> {
    val sorted = events.sortedWith(compareBy<TimedEvent> { it.startMinute }.thenByDescending { it.endMinute - it.startMinute })
    val result = mutableListOf<PlacedEvent>()
    val cluster = mutableListOf<TimedEvent>()
    var clusterEnd = -1.0
    fun end(e: TimedEvent) = maxOf(e.endMinute, e.startMinute + minimumMinutes).coerceAtMost(1440.0)
    fun flush() {
        val columnEnds = mutableListOf<Double>()
        val placements = cluster.map { e ->
            val column = columnEnds.indexOfFirst { it <= e.startMinute }.let { if (it < 0) columnEnds.size else it }
            if (column == columnEnds.size) columnEnds.add(end(e)) else columnEnds[column] = end(e)
            e to column
        }
        result.addAll(placements.map { (e, col) -> PlacedEvent(e, col, columnEnds.size) })
        cluster.clear()
    }
    sorted.forEach { e ->
        if (cluster.isNotEmpty() && e.startMinute >= clusterEnd) { flush(); clusterEnd = -1.0 }
        cluster.add(e)
        clusterEnd = maxOf(clusterEnd, end(e))
    }
    if (cluster.isNotEmpty()) flush()
    return result
}
