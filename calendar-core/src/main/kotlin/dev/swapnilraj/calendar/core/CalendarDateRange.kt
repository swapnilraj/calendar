package dev.swapnilraj.calendar.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Calendar dates, not an elapsed duration. The end date is exclusive. */
data class CalendarDateRange(val start: LocalDate, val endExclusive: LocalDate) {
    init {
        require(start < endExclusive) { "A calendar range must contain at least one date" }
    }

    operator fun contains(date: LocalDate): Boolean = date >= start && date < endExclusive

    fun intersects(other: CalendarDateRange): Boolean =
        start < other.endExclusive && endExclusive > other.start

    /** Consecutive local midnights may be 23, 24, 25, or even zero hours apart. */
    fun instantBounds(zone: ZoneId): InstantBounds = InstantBounds(
        start.atStartOfDay(zone).toInstant(),
        endExclusive.atStartOfDay(zone).toInstant(),
    )

    companion object {
        fun day(date: LocalDate): CalendarDateRange = CalendarDateRange(date, date.plusDays(1))
    }
}

data class InstantBounds(val start: Instant, val endExclusive: Instant) {
    init {
        require(endExclusive >= start) { "Instant bounds must not run backwards" }
    }

    operator fun contains(instant: Instant): Boolean = instant >= start && instant < endExclusive
}
