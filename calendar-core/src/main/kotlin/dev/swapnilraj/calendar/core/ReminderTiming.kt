package dev.swapnilraj.calendar.core

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Resolve one resolved minute offset; this does not select defaults, schedule, or deliver an alert.
 * Provider sentinels such as MINUTES_DEFAULT (-1) must be resolved by the caller first;
 * passing that sentinel here would incorrectly interpret it as one minute after the event.
 *
 * Timed offsets are elapsed minutes before the actual start instant. All-day offsets are wall-clock
 * minutes before the first date's local midnight, resolved in the device zone (not the display zone).
 * Thus 900 means 09:00 on the preceding date and -540 means 09:00 on the event date.
 *
 * For ambiguous local times choose the earlier offset. For gaps move forward by the gap length,
 * following java.time's atZone policy. Invalid arithmetic/range overflow propagates to the caller;
 * no clamping or fallback can silently change when an alert is due.
 */
fun dueInstant(event: EventTime, minutesBefore: Int, deviceZone: ZoneId): Instant = when (event) {
    is EventTime.Timed -> event.start.minus(minutesBefore.toLong(), ChronoUnit.MINUTES)
    is EventTime.AllDay -> event.dates.start.atStartOfDay()
        .minusMinutes(minutesBefore.toLong())
        .atZone(deviceZone)
        .withEarlierOffsetAtOverlap()
        .toInstant()
}
