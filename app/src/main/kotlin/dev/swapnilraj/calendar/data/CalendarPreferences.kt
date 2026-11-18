package dev.swapnilraj.calendar.data

import dev.swapnilraj.calendar.core.CalendarId
import dev.swapnilraj.calendar.core.CalendarSelection

/** One atomic read: never combine a fresh view with a stale/default calendar filter. */
data class CalendarPreferences(
    val selection: CalendarSelection,
    val defaultCalendar: CalendarId?,
    val view: String,
    val hourHeight: Float,
)

sealed interface CalendarPreferenceState {
    data object Loading : CalendarPreferenceState
    data class Ready(val value: CalendarPreferences) : CalendarPreferenceState
    data object Unavailable : CalendarPreferenceState
}
