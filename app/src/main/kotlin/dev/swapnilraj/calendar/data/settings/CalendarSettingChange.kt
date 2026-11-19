package dev.swapnilraj.calendar.data.settings

import dev.swapnilraj.calendar.ui.calendars.CalendarViewOption
import dev.swapnilraj.calendar.ui.settings.*

/** One explicit preference choice. Never carries a stale copy of unrelated settings. */
sealed interface CalendarSettingChange {
    data class View(val value: CalendarViewOption) : CalendarSettingChange
    data class Duration(val value: Int) : CalendarSettingChange
    data class TimedReminder(val value: Int?) : CalendarSettingChange
    data class AllDayReminder(val value: AllDayReminderDefault) : CalendarSettingChange
    data class FirstWeekday(val value: FirstWeekdayOption) : CalendarSettingChange
    data class ShowWeekends(val value: Boolean) : CalendarSettingChange
    data class ShowDeclined(val value: Boolean) : CalendarSettingChange
    data class Theme(val value: SettingsTheme) : CalendarSettingChange
    data class DisplayZone(val value: DisplayZoneOption) : CalendarSettingChange
    data class WeatherVisible(val value: Boolean) : CalendarSettingChange
    data class InvitationFilter(val value: dev.swapnilraj.calendar.core.InvitationFilter) : CalendarSettingChange
    data class WeatherUnits(val value: dev.swapnilraj.calendar.ui.settings.WeatherUnits) : CalendarSettingChange
}

sealed interface CalendarSettingSave {
    data object Saved : CalendarSettingSave
    data object Invalid : CalendarSettingSave
    data object Unavailable : CalendarSettingSave
}
