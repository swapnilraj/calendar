package dev.swapnilraj.calendar.android.navigation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import dev.swapnilraj.calendar.core.CalendarReadState
import dev.swapnilraj.calendar.core.EventDetails
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow

/** Root supplies strict settings/zone and source-change signals; no remembered default environment. */
class CalendarEntryServices(
    val source: IncomingCalendarSource,
    val readZone: suspend () -> CalendarReadState<ZoneId>,
    val changes: Flow<Unit>,
) {
    val resolver = IncomingCalendarResolver(source)
}

sealed interface CalendarEntryState {
    data object Loading : CalendarEntryState
    data class Result(val sessionId: String?, val resolution: CalendarEntryResolution) : CalendarEntryState
    data class Details(val sessionId: String, val details: EventDetails, val resolved: CalendarEntryResolution.Event, val displayZone: ZoneId) : CalendarEntryState
}

data class CalendarEntryActions(
    val dismiss: () -> Unit,
    val retry: () -> Unit,
    val requestCalendarRead: () -> Unit,
    val review: (() -> Unit)?,
    val openLink: (String) -> Unit,
)
fun interface CalendarEntryContent {
    @Composable fun Render(state: CalendarEntryState, actions: CalendarEntryActions)
}

data class CalendarEntryReviewCommand(val sessionId: String, val resolution: CalendarEntryResolution, val displayZone: ZoneId)
fun interface CalendarEntryReviewHandler {
    /** True only if Root accepted the explicit review/navigation request. Never writes provider data. */
    fun open(activity: ComponentActivity, command: CalendarEntryReviewCommand): Boolean
}
interface CalendarEntryOwner {
    val calendarEntryServices: CalendarEntryServices
    val calendarEntryContent: CalendarEntryContent
    val calendarEntryReviewHandler: CalendarEntryReviewHandler
}
