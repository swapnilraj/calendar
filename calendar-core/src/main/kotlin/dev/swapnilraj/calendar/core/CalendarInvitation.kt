package dev.swapnilraj.calendar.core

/** App-local display choice, independent of provider visibility, reminders and mutation authority. */
enum class InvitationFilter { ALL, PENDING_ONLY }

/** Read-only classification. UNKNOWN must not be presented as complete absence of invitations. */
enum class InvitationState { UNKNOWN, PENDING, NOT_PENDING }

/**
 * CalendarContract attendee statuses are NONE=0, ACCEPTED=1, DECLINED=2, INVITED=3 and
 * TENTATIVE=4. Organizer evidence must be the provider's exact Boolean, not email inference.
 * Keep the raw status as Long: narrowing first could wrap an invalid value into INVITED.
 * This classification grants no response/edit right and does not normalize provider data.
 */
fun classifyInvitation(isOrganizer: Boolean?, selfAttendeeStatus: Long?): InvitationState {
    if (isOrganizer == null || selfAttendeeStatus == null || selfAttendeeStatus !in 0L..4L)
        return InvitationState.UNKNOWN
    return when (selfAttendeeStatus) {
        1L, 2L, 4L -> InvitationState.NOT_PENDING
        3L -> if (isOrganizer) InvitationState.UNKNOWN else InvitationState.PENDING
        else -> if (isOrganizer) InvitationState.NOT_PENDING else InvitationState.UNKNOWN
    }
}
