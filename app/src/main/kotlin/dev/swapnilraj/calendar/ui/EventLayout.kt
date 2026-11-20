package dev.swapnilraj.calendar.ui

import dev.swapnilraj.calendar.data.CalEvent
import dev.swapnilraj.calendar.core.CalendarId
import dev.swapnilraj.calendar.core.DayGeometry
import dev.swapnilraj.calendar.core.EventRef
import dev.swapnilraj.calendar.core.TimedSegment
import java.time.LocalDate
import java.time.ZoneId

data class TimedEvent(
    val event: CalEvent,
    val startMinute: Double,
    val endMinute: Double,
    val segment: TimedSegment? = null,
    val partIndex: Int = 0,
    val partCount: Int = 1,
    val clockChangeAffected: Boolean = false,
) {
    // Minimum-height layout copies must retain the original temporal segment key.
    val key: Any get() = event.key to segment?.key
}
data class PlacedEvent(val timed: TimedEvent, val column: Int, val columns: Int)

fun timedEvents(events: List<CalEvent>, date: LocalDate, zone: ZoneId): List<TimedEvent> {
    val geometry = DayGeometry(date, zone)
    return events.filter { !it.allDay }.flatMap { event ->
        // Legacy fixtures/readers have no calendar identity. This reference exists only for
        // geometry; clicks always receive the original event, never this rendering fallback.
        val reference = event.ref?.copy(instanceStart = event.start, instanceEnd = event.end)
            ?: EventRef(CalendarId(0), event.id.coerceAtLeast(0), event.start, event.end)
        val segments = geometry.segments(reference)
        segments.mapIndexed { index, segment ->
            val affected = segments.size > 1 || geometry.clockChanges.any { band ->
                if (segment.startMinute == segment.endMinute)
                    segment.startMinute >= band.startMinute && segment.startMinute < band.endMinute
                else segment.startMinute < band.endMinute && segment.endMinute > band.startMinute
            }
            TimedEvent(event, segment.startMinute, segment.endMinute, segment, index, segments.size, affected)
        }
    }
}

/** Minimum visible duration participates in collision detection, including zero-length events. */
fun layoutEvents(events: List<TimedEvent>, minimumMinutes: Double): List<PlacedEvent> {
    require(minimumMinutes > 0 && minimumMinutes <= 1440)
    val sorted = events.map { event ->
        val start = event.startMinute.coerceIn(0.0, 1440.0 - minimumMinutes)
        event.copy(startMinute = start, endMinute = maxOf(event.endMinute, start + minimumMinutes).coerceAtMost(1440.0))
    }.sortedWith(compareBy<TimedEvent> { it.startMinute }.thenByDescending { it.endMinute - it.startMinute })
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
