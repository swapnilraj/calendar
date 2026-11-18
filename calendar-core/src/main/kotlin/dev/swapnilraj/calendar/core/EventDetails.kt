package dev.swapnilraj.calendar.core

import java.time.ZoneId

data class EventAttendee(val email: String, val name: String?, val status: Int, val relationship: Int, val type: Int)
data class EventReminder(val minutesBefore: Int, val method: Int)

/** Provider-declared guest policy; it does not grant effective mutation rights. */
data class EventGuestPermissions(
    val modify: AccessDecision = AccessDecision.UNKNOWN,
    val inviteOthers: AccessDecision = AccessDecision.UNKNOWN,
    val seeGuests: AccessDecision = AccessDecision.UNKNOWN,
)

/** Detail-only fields stay out of list queries; unknown action rights are never permission grants. */
data class EventDetails(
    val occurrence: EventOccurrence,
    val calendar: CalendarInfo?,
    val location: String = "",
    val description: String = "",
    val organizer: String? = null,
    val eventZone: ZoneId? = null,
    val attendees: List<EventAttendee> = emptyList(),
    val reminders: List<EventReminder> = emptyList(),
    val recurrenceRule: String? = null,
    val canEdit: AccessDecision = AccessDecision.UNKNOWN,
    val canRespond: AccessDecision = AccessDecision.UNKNOWN,
    /** Nullable raw provider values preserve unknown metadata without inventing a default. */
    val availability: Int? = null,
    val privacy: Int? = null,
    val status: Int? = null,
    val isOrganizer: Boolean? = null,
    val selfAttendeeStatus: Int? = null,
    val guestPermissions: EventGuestPermissions = EventGuestPermissions(),
)
