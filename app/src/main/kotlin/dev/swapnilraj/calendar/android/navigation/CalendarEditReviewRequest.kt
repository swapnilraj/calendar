package dev.swapnilraj.calendar.android.navigation

import dev.swapnilraj.calendar.core.EventRef
import java.time.ZoneId

/** The existing source target and caller suggestions are separate, immutable inputs. */
data class CalendarEditReviewRequest(
    val sessionId: String, val selected: EventRef, val binding: CalendarEntryBinding,
    val proposed: CalendarDraftSeed, val reviewedZone: ZoneId,
) {
    val nativeId: String get() = "incoming-edit:$sessionId"
    val reviewId: String get() = "incoming-edit-review:$sessionId"
}


data class CalendarEditReviewPreview(val sessionId: String, val title: String, val generation: String)
