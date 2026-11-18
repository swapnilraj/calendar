package dev.swapnilraj.calendar.core

import java.time.Instant

/** Device-provider identity; this value is not a cloud calendar identifier. */
@JvmInline
value class CalendarId(val value: Long) {
    init { require(value >= 0) { "A provider calendar ID must not be negative" } }
}

/** Original recurrence identity remains unchanged when an exception is moved. */
data class RecurrenceRef(val masterEventId: Long, val originalStart: Instant) {
    init { require(masterEventId >= 0) { "A provider event ID must not be negative" } }
}

/** Instance timestamps are preserved for detail reads and external event intents. */
data class EventRef(
    val calendarId: CalendarId,
    val eventId: Long,
    val instanceStart: Instant,
    val instanceEnd: Instant,
    val recurrence: RecurrenceRef? = null,
) {
    init {
        require(eventId >= 0) { "A provider event ID must not be negative" }
        require(instanceEnd >= instanceStart) { "An event instance must not run backwards" }
    }
}

sealed interface CalendarSelection {
    /** Compatibility default until the user makes an explicit app-local choice. */
    data object ProviderVisible : CalendarSelection

    /** Empty IDs intentionally select no calendars, never all calendars. */
    data class Explicit(val ids: Set<CalendarId>) : CalendarSelection
}

enum class AccessDecision { ALLOWED, DENIED, UNKNOWN }

/** Potential provider capabilities. Android permission and event-level checks are separate. */
data class CalendarCapabilities(
    val readDetails: AccessDecision = AccessDecision.UNKNOWN,
    val createEvents: AccessDecision = AccessDecision.UNKNOWN,
    val modifyEvents: AccessDecision = AccessDecision.UNKNOWN,
    val respondToInvitations: AccessDecision = AccessDecision.UNKNOWN,
)
