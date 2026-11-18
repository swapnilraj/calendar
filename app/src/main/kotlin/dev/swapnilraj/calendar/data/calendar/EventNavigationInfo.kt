package dev.swapnilraj.calendar.data.calendar

import dev.swapnilraj.calendar.core.CalendarIdentity
import java.time.Instant

/** Minimal fresh metadata for an incoming link. This never supplies an occurrence reference. */
data class EventNavigationInfo(
    val eventId: Long,
    val calendar: CalendarIdentity,
    val providerUid: String?,
    val allDay: Boolean,
    val start: Instant,
    val endExclusive: Instant?,
    val recurringOrDetached: Boolean,
)
