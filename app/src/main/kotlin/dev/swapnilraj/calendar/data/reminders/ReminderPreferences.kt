package dev.swapnilraj.calendar.data.reminders

/** Read failure and corrupt settings never stand in for disabled reminders. */
sealed interface ReminderPreferenceState {
    data object Loading : ReminderPreferenceState
    data class Ready(val settings: SavedReminderSettings) : ReminderPreferenceState
    data class Unavailable(val problem: ReminderSettingsProblem? = null) : ReminderPreferenceState
}

sealed interface ReminderPreferenceSave {
    data class Saved(val settings: SavedReminderSettings) : ReminderPreferenceSave
    /** Another setup or namespace-strengthening operation won. Retain inputs for explicit review. */
    data object Changed : ReminderPreferenceSave
    data object Invalid : ReminderPreferenceSave
    data object Unavailable : ReminderPreferenceSave
}
