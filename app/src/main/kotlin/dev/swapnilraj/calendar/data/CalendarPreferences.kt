package dev.swapnilraj.calendar.data

import dev.swapnilraj.calendar.core.CalendarId
import dev.swapnilraj.calendar.core.CalendarSelection
import dev.swapnilraj.calendar.ui.settings.CalendarSettingsOptions
import dev.swapnilraj.calendar.ui.calendars.CalendarViewOption

/** One atomic read: never combine a fresh view with a stale/default calendar filter. */
data class CalendarPreferences(
    val selection: CalendarSelection,
    val defaultCalendar: CalendarId?,
    val view: String,
    val hourHeight: Float,
    val options: CalendarSettingsOptions = CalendarSettingsOptions(defaultView = CalendarViewOption.entries.firstOrNull { it.name == view } ?: CalendarViewOption.DAY),
)

sealed interface CalendarPreferenceState {
    data object Loading : CalendarPreferenceState
    data class Ready(val value: CalendarPreferences) : CalendarPreferenceState
    data object Unavailable : CalendarPreferenceState
}
