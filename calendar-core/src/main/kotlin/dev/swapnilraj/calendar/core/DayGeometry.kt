package dev.swapnilraj.calendar.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.zone.ZoneOffsetTransition

enum class ClockChangeKind { GAP, OVERLAP }

/** Wall-clock area which cannot represent a unique instant. Minutes are relative to this date. */
data class ClockChangeBand(
    val kind: ClockChangeKind,
    val startMinute: Double,
    val endMinute: Double,
    val transition: Instant,
    val offsetBefore: ZoneOffset,
    val offsetAfter: ZoneOffset,
) {
    val offsetBeforeLabel: String get() = offsetBefore.utcLabel()
    val offsetAfterLabel: String get() = offsetAfter.utcLabel()
}

data class TimedSegmentKey(
    val event: EventRef,
    val start: Instant,
    val end: Instant,
    val offset: ZoneOffset,
)

/** Rendering fragment only: [event] and [eventDuration] still refer to the complete occurrence. */
data class TimedSegment(
    val event: EventRef,
    val start: Instant,
    val end: Instant,
    val startMinute: Double,
    val endMinute: Double,
    val offset: ZoneOffset,
) {
    val key: TimedSegmentKey get() = TimedSegmentKey(event, start, end, offset)
    val duration: Duration get() = Duration.between(start, end)
    val eventDuration: Duration get() = Duration.between(event.instanceStart, event.instanceEnd)
    val offsetLabel: String get() = offset.utcLabel()
}

/**
 * A fixed 00:00–24:00 wall-clock grid backed by the actual local day's instant bounds.
 * Events split at clock changes so every fragment uses one offset, including its end boundary.
 * Visual minimum heights/collision placement belong to presentation, not temporal geometry.
 */
class DayGeometry(val date: LocalDate, val zone: ZoneId) {
    val bounds: InstantBounds = CalendarDateRange.day(date).instantBounds(zone)
    private val localStart = date.atStartOfDay()
    private val localEnd = date.plusDays(1).atStartOfDay()

    // Scan by the full valid offset envelope, not by actual day bounds: a skipped local date has
    // zero elapsed duration but still needs a full-date gap band.
    private val transitions: List<ZoneOffsetTransition> = buildList {
        val first = localStart.toInstant(ZoneOffset.MAX)
        val last = localEnd.toInstant(ZoneOffset.MIN)
        var transition = zone.rules.nextTransition(first.minusNanos(1))
        while (transition != null && transition.instant <= last) {
            add(transition)
            transition = zone.rules.nextTransition(transition.instant)
        }
    }

    val clockChanges: List<ClockChangeBand> = transitions.mapNotNull { transition ->
        val first = maxOf(minOf(transition.dateTimeBefore, transition.dateTimeAfter), localStart)
        val last = minOf(maxOf(transition.dateTimeBefore, transition.dateTimeAfter), localEnd)
        if (first >= last) null else ClockChangeBand(
            if (transition.isGap) ClockChangeKind.GAP else ClockChangeKind.OVERLAP,
            minute(first), minute(last), transition.instant, transition.offsetBefore, transition.offsetAfter,
        )
    }

    /** Only instants within this half-open day have a visible current-time position. */
    fun minuteAt(instant: Instant): Double? =
        if (instant in bounds) minute(LocalDateTime.ofInstant(instant, zone)) else null

    fun segments(event: EventRef): List<TimedSegment> {
        val time = EventTime.Timed(event.instanceStart, event.instanceEnd)
        if (!time.intersects(CalendarDateRange.day(date), zone)) return emptyList()
        val start = maxOf(event.instanceStart, bounds.start)
        val end = minOf(event.instanceEnd, bounds.endExclusive)
        if (start == end) return listOf(segment(event, start, end))
        val cuts = buildList {
            add(start)
            transitions.forEach { if (it.instant > start && it.instant < end) add(it.instant) }
            add(end)
        }
        return cuts.zipWithNext { begin, finish -> segment(event, begin, finish) }
    }

    private fun segment(event: EventRef, start: Instant, end: Instant): TimedSegment {
        val offset = zone.rules.getOffset(start)
        return TimedSegment(
            event, start, end,
            minute(LocalDateTime.ofInstant(start, offset)),
            minute(LocalDateTime.ofInstant(end, offset)),
            offset,
        )
    }

    private fun minute(value: LocalDateTime): Double {
        val elapsed = Duration.between(localStart, value)
        return (elapsed.seconds / 60.0 + elapsed.nano / 60_000_000_000.0).coerceIn(0.0, 1440.0)
    }
}

private fun ZoneOffset.utcLabel(): String = if (totalSeconds == 0) "UTC+00:00" else "UTC$id"
