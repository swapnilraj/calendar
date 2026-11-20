package dev.swapnilraj.calendar.core

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** All-day dates must never be interpreted as appointment instants in the display zone. */
sealed interface EventTime {
    fun intersects(range: CalendarDateRange, displayZone: ZoneId): Boolean

    data class Timed(val start: Instant, val end: Instant) : EventTime {
        init { require(end >= start) { "An event must not run backwards" } }

        val duration: Duration get() = Duration.between(start, end)

        override fun intersects(range: CalendarDateRange, displayZone: ZoneId): Boolean {
            val bounds = range.instantBounds(displayZone)
            // A point event belongs to its start date, including midnight, but never the next range.
            if (start == end) return start in bounds
            if (bounds.start == bounds.endExclusive) return false
            return start < bounds.endExclusive && end > bounds.start
        }
    }

    data class AllDay(val dates: CalendarDateRange) : EventTime {
        override fun intersects(range: CalendarDateRange, displayZone: ZoneId): Boolean =
            dates.intersects(range)

        companion object {
            /** CalendarContract encodes all-day boundaries as UTC dates with an exclusive end. */
            fun fromProvider(start: Instant, endExclusive: Instant): AllDay = AllDay(
                CalendarDateRange(
                    start.atOffset(ZoneOffset.UTC).toLocalDate(),
                    endExclusive.atOffset(ZoneOffset.UTC).toLocalDate(),
                ),
            )
        }
    }
}
