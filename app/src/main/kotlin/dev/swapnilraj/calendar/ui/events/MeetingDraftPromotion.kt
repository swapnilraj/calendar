package dev.swapnilraj.calendar.ui.events

import dev.swapnilraj.calendar.ui.events.meetings.create.MeetingCreationLaunch

/** A promotion changes one local record; it never creates or updates a provider event. */
sealed interface MeetingDraftPromotion {
    data class Ready(val launch: MeetingCreationLaunch) : MeetingDraftPromotion
    data object Rejected : MeetingDraftPromotion
    data object Unavailable : MeetingDraftPromotion
}
