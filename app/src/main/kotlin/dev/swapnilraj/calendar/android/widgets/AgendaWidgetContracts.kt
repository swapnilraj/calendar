package dev.swapnilraj.calendar.android.widgets

import android.widget.RemoteViews
import dev.swapnilraj.calendar.core.*
import java.time.LocalDate
import java.time.ZoneId

fun interface AgendaWidgetSnapshotReader {
    suspend fun read(request: CalendarRequest): CalendarReadState<CalendarSnapshot>
}

sealed interface WidgetCalendarSelection {
    data object FollowApp : WidgetCalendarSelection
    /** Empty explicitly means no calendars. Never intersect with the application's selection. */
    data class Explicit(val ids: Set<CalendarId>) : WidgetCalendarSelection
}
enum class WidgetPrivacy { BUSY_ONLY, EVENT_TITLES }
data class AgendaWidgetConfiguration(
    val generation: String,
    val selection: WidgetCalendarSelection = WidgetCalendarSelection.FollowApp,
    val privacy: WidgetPrivacy = WidgetPrivacy.BUSY_ONLY,
    val expectedCalendarIdentities: Map<CalendarId, CalendarIdentity> = emptyMap(),
) { init { require(generation.isNotBlank()) } }
sealed interface AgendaWidgetConfigurationState {
    data object SetupRequired : AgendaWidgetConfigurationState
    data class Ready(val value: AgendaWidgetConfiguration, val epoch: WidgetEpoch) : AgendaWidgetConfigurationState
    data object Unavailable : AgendaWidgetConfigurationState
}
data class AgendaWidgetEnvironment(
    val appSelection: CalendarSelection,
    val displayZone: ZoneId,
    val showDeclined: Boolean,
    val use24Hour: Boolean,
)
interface AgendaWidgetSettings {
    suspend fun configuration(widgetId: Int): AgendaWidgetConfigurationState
    suspend fun environment(): CalendarReadState<AgendaWidgetEnvironment>
    /** Root validates the live host binding independently of any stored configuration row. */
    suspend fun lifecycle(widgetId: Int): WidgetLifecycleProof
}
interface AgendaWidgetRefreshScheduler { fun requestRefresh(widgetIds: Set<Int>): Boolean }
interface AgendaWidgetRuntimeOwner { val agendaWidgetRuntime: AgendaWidgetRuntime }

data class AgendaWidgetSize(val widthDp: Int, val heightDp: Int) {
    init { require(widthDp > 0 && heightDp > 0) }
    val rowCapacity: Int get() = when { heightDp >= 480 -> 8; heightDp >= 336 -> 5; else -> 3 }
}
interface AgendaWidgetHost {
    fun widgetIds(): Set<Int>
    fun size(widgetId: Int): AgendaWidgetSize
    fun publish(widgetId: Int, views: RemoteViews)
}

data class AgendaWidgetRow(val occurrence: EventOccurrence, val date: LocalDate, val availabilityOnly: Boolean = false, val calendarFingerprint: String? = null)
sealed interface AgendaWidgetDisplay {
    data object Loading : AgendaWidgetDisplay
    data object SetupRequired : AgendaWidgetDisplay
    data object PermissionRequired : AgendaWidgetDisplay
    data object Unavailable : AgendaWidgetDisplay
    data class Ready(
        val configuration: AgendaWidgetConfiguration,
        val environment: AgendaWidgetEnvironment,
        val date: LocalDate,
        val rows: List<AgendaWidgetRow>,
        val more: Boolean,
        val noCalendars: Boolean,
        val coverageIncomplete: Boolean,
    ) : AgendaWidgetDisplay
}
