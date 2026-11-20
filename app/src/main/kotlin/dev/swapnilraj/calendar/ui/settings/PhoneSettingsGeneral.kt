package dev.swapnilraj.calendar.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.swapnilraj.calendar.R
import dev.swapnilraj.calendar.data.settings.CalendarSettingChange
import dev.swapnilraj.calendar.ui.calendars.CalendarViewOption
import java.time.ZoneId

@Composable
internal fun PhoneSettingsGeneral(
    options: CalendarSettingsOptions,
    availableViews: List<CalendarViewOption>,
    deviceZone: ZoneId,
    busy: Boolean,
    onOpen: (PhoneSettingEditor) -> Unit,
    onChange: (CalendarSettingChange) -> Unit,
) {
    val deviceMode = options.displayZone == DisplayZoneOption.Device
    val zone = (options.displayZone as? DisplayZoneOption.Fixed)?.zone ?: deviceZone
    LazyColumn(Modifier.fillMaxSize().testTag("phone_settings_general_list"), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            PhoneSettingsRow(stringResource(R.string.phone_settings_week_start), phoneSettingsChoiceLabel(PhoneSettingEditor.WEEKDAY, options.firstWeekday.name),
                "phone_settings_row_weekday", !busy) { onOpen(PhoneSettingEditor.WEEKDAY) }
            HorizontalDivider()
        }
        item {
            PhoneSettingsSwitch(stringResource(R.string.phone_settings_device_zone), deviceMode, "phone_settings_row_device_zone", !busy) { useDevice ->
                if (useDevice) onChange(CalendarSettingChange.DisplayZone(DisplayZoneOption.Device)) else onOpen(PhoneSettingEditor.ZONE)
            }
        }
        item {
            PhoneSettingsRow(stringResource(R.string.phone_settings_time_zone), zone.id, "phone_settings_row_zone", !busy && !deviceMode) { onOpen(PhoneSettingEditor.ZONE) }
            HorizontalDivider()
        }
        item {
            PhoneSettingsSwitch(stringResource(R.string.settings_show_declined), options.showDeclined, "phone_settings_row_declined", !busy) {
                onChange(CalendarSettingChange.ShowDeclined(it))
            }
            HorizontalDivider()
        }
        item {
            PhoneSettingsRow(stringResource(R.string.invitation_filter_title),
                phoneSettingsChoiceLabel(PhoneSettingEditor.INVITATION_FILTER, options.invitationFilter.name),
                "phone_settings_row_invitations", !busy) { onOpen(PhoneSettingEditor.INVITATION_FILTER) }
            HorizontalDivider()
        }
        item {
            PhoneSettingsRow(stringResource(R.string.phone_settings_duration), pluralStringResource(R.plurals.settings_minutes, options.defaultDurationMinutes, options.defaultDurationMinutes),
                "phone_settings_row_duration", !busy) { onOpen(PhoneSettingEditor.DURATION) }
        }
        item {
            PhoneSettingsRow(stringResource(R.string.settings_theme), phoneSettingsChoiceLabel(PhoneSettingEditor.THEME, options.theme.name),
                "phone_settings_row_theme", !busy) { onOpen(PhoneSettingEditor.THEME) }
            HorizontalDivider()
        }
        item { PhoneSettingsSection(stringResource(R.string.phone_settings_display_section)) }
        item {
            PhoneSettingsRow(stringResource(R.string.phone_settings_view),
                if (options.defaultView in availableViews) phoneSettingsChoiceLabel(PhoneSettingEditor.VIEW, options.defaultView.name) else stringResource(R.string.settings_unsupported_view),
                "phone_settings_row_view", !busy && availableViews.isNotEmpty()) { onOpen(PhoneSettingEditor.VIEW) }
        }
        item {
            PhoneSettingsSwitch(stringResource(R.string.settings_show_weekends), options.showWeekends, "phone_settings_row_weekends", !busy) {
                onChange(CalendarSettingChange.ShowWeekends(it))
            }
            HorizontalDivider()
        }
        item { PhoneSettingsSection(stringResource(R.string.phone_settings_reminder_defaults)) }
        item {
            val minutes = options.timedReminderMinutes
            val subtitle = if (minutes == null) stringResource(R.string.settings_reminder_none)
                else pluralStringResource(R.plurals.phone_settings_minutes_before, minutes, minutes)
            PhoneSettingsRow(stringResource(R.string.settings_timed_reminder_enabled), subtitle, "phone_settings_row_timed_reminder", !busy) { onOpen(PhoneSettingEditor.TIMED_REMINDER) }
        }
        item {
            PhoneSettingsRow(stringResource(R.string.settings_all_day_reminder), phoneSettingsChoiceLabel(PhoneSettingEditor.ALL_DAY_REMINDER, options.allDayReminder.name),
                "phone_settings_row_all_day_reminder", !busy) { onOpen(PhoneSettingEditor.ALL_DAY_REMINDER) }
            HorizontalDivider()
        }
        item { PhoneSettingsSection(stringResource(R.string.settings_weather)) }
        item {
            PhoneSettingsSwitch(stringResource(R.string.settings_weather_visible), options.weatherVisible, "phone_settings_row_weather_visible", !busy) {
                onChange(CalendarSettingChange.WeatherVisible(it))
            }
        }
        item {
            PhoneSettingsRow(stringResource(R.string.settings_weather_units), phoneSettingsChoiceLabel(PhoneSettingEditor.WEATHER_UNITS, options.weatherUnits.name),
                "phone_settings_row_weather_units", !busy) { onOpen(PhoneSettingEditor.WEATHER_UNITS) }
        }
    }
}
