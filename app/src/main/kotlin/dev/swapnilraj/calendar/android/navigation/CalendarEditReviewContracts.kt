package dev.swapnilraj.calendar.android.navigation

import androidx.compose.runtime.Composable
import dev.swapnilraj.calendar.core.CalendarInfo
import dev.swapnilraj.calendar.core.EventRef
import dev.swapnilraj.calendar.core.DraftRecord
import dev.swapnilraj.calendar.ui.events.EditorDraftLaunch
import dev.swapnilraj.calendar.ui.events.EventEditorFields
import java.time.ZoneId

data class CalendarEditReviewEnvironment(
    val calendars: List<CalendarInfo>, val displayZone: ZoneId, val use24Hour: Boolean,
    val calendarReadGranted: Boolean = true,
)

interface CalendarEditReviewOwner {
    val calendarEditReviewServices: CalendarEditReviewServices
    @Composable fun CalendarEditReviewTheme(content: @Composable () -> Unit) {
        dev.swapnilraj.calendar.ui.theme.DaylightTheme(content = content)
    }
}

enum class CalendarEditReviewIssue { GUESTS, RECURRENCE, FIELD_NOT_ALLOWED, SOURCE_NOT_ELIGIBLE, ZONE_AUTHORING_REVIEW }

@ConsistentCopyVisibility
data class CalendarEditReviewSnapshot internal constructor(
    val request: CalendarEditReviewRequest, val generation: String,
    val original: EventEditorFields?, val suggested: EventEditorFields?,
    internal val record: DraftRecord, val sourceZone: ZoneId? = null,
)

sealed interface CalendarEditReviewResult {
    data class Review(val snapshot: CalendarEditReviewSnapshot, val environment: CalendarEditReviewEnvironment) : CalendarEditReviewResult
    data class Unsupported(val snapshot: CalendarEditReviewSnapshot, val reasons: Set<CalendarEditReviewIssue>,
        val environment: CalendarEditReviewEnvironment?) : CalendarEditReviewResult
    data class Editor(val launch: EditorDraftLaunch, val environment: CalendarEditReviewEnvironment,
        val binding: CalendarEntryBinding, val reviewGeneration: String, val sourceFree: Boolean = false,
        internal val originalTokenHash: String, internal val originalBaselineHash: String, internal val reviewedZone: ZoneId) : CalendarEditReviewResult
    data object PermissionRequired : CalendarEditReviewResult
    data object Stale : CalendarEditReviewResult
    data object Consumed : CalendarEditReviewResult
    data object Unavailable : CalendarEditReviewResult
}
