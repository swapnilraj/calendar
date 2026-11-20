package dev.swapnilraj.calendar.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import dev.swapnilraj.calendar.core.InvitationFilter
import dev.swapnilraj.calendar.R
import dev.swapnilraj.calendar.data.settings.CalendarSettingChange
import dev.swapnilraj.calendar.ui.calendars.CalendarViewOption
import java.time.ZoneId

@Composable
internal fun phoneSettingsEditorTitle(editor: PhoneSettingEditor): String = stringResource(when (editor) {
    PhoneSettingEditor.WEEKDAY -> R.string.phone_settings_week_start
    PhoneSettingEditor.ZONE -> R.string.phone_settings_time_zone
    PhoneSettingEditor.DURATION -> R.string.phone_settings_duration
    PhoneSettingEditor.THEME -> R.string.settings_theme
    PhoneSettingEditor.VIEW -> R.string.phone_settings_view
    PhoneSettingEditor.TIMED_REMINDER -> R.string.settings_timed_reminder_enabled
    PhoneSettingEditor.ALL_DAY_REMINDER -> R.string.settings_all_day_reminder
    PhoneSettingEditor.WEATHER_UNITS -> R.string.settings_weather_units
    PhoneSettingEditor.INVITATION_FILTER -> R.string.invitation_filter_title
    PhoneSettingEditor.NONE -> R.string.settings_title
})

/** In-place editor: no child window can escape the caller's readiness/privacy mask. */
@Composable
internal fun PhoneSettingsEditor(
    state: PhoneSettingsState,
    availableViews: List<CalendarViewOption>,
    busy: Boolean,
    saveError: String?,
    onSubmit: (CalendarSettingChange) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rawEditor = state.editor in listOf(PhoneSettingEditor.ZONE, PhoneSettingEditor.DURATION, PhoneSettingEditor.TIMED_REMINDER)
    val validationError = if (rawEditor && state.attemptedApply && state.rawChange() == null) {
        stringResource(when (state.editor) {
            PhoneSettingEditor.ZONE -> R.string.settings_zone_error
            PhoneSettingEditor.TIMED_REMINDER -> R.string.settings_timed_reminder_error
            else -> R.string.settings_duration_error
        })
    } else null
    val error = validationError ?: saveError
    Column(modifier.fillMaxSize().testTag("phone_settings_editor")) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (rawEditor) PhoneSettingsRawEditor(state, busy, validationError != null)
            else {
                val values = when (state.editor) {
                    PhoneSettingEditor.WEEKDAY -> FirstWeekdayOption.entries.map { it.name }
                    PhoneSettingEditor.THEME -> SettingsTheme.entries.map { it.name }
                    PhoneSettingEditor.VIEW -> availableViews.distinct().map { it.name }
                    PhoneSettingEditor.ALL_DAY_REMINDER -> AllDayReminderDefault.entries.map { it.name }
                    PhoneSettingEditor.WEATHER_UNITS -> WeatherUnits.entries.map { it.name }
                    PhoneSettingEditor.INVITATION_FILTER -> InvitationFilter.entries.map { it.name }
                    else -> emptyList()
                }
                Column(Modifier.selectableGroup()) {
                    values.forEach { key ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("phone_settings_choice_" + key)
                            .selectable(state.choice == key, enabled = !busy, role = Role.RadioButton, onClick = {
                                if (!busy && !state.awaitingSave) {
                                    state.choice = key
                                    onSubmit(phoneSettingsChoiceChange(state.editor, key))
                                }
                            }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = state.choice == key, onClick = null, enabled = !busy)
                            Text(phoneSettingsChoiceLabel(state.editor, key), Modifier.weight(1f).padding(start = 12.dp),
                                style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.ContentOrLtr))
                        }
                    }
                }
                if (state.editor == PhoneSettingEditor.INVITATION_FILTER) {
                    Text(stringResource(R.string.invitation_filter_help),
                        style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.ContentOrLtr))
                }
                if (state.editor == PhoneSettingEditor.ALL_DAY_REMINDER) {
                    Text(stringResource(R.string.settings_all_day_zone_help),
                        style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.ContentOrLtr))
                }
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.ContentOrLtr),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(enabled = !busy, modifier = Modifier.testTag("phone_settings_editor_cancel"), onClick = {
                if (!busy && !state.awaitingSave) onCancel()
            }) { Text(stringResource(R.string.settings_cancel)) }
            if (rawEditor) Button(enabled = !busy, modifier = Modifier.testTag("phone_settings_editor_apply"), onClick = {
                if (!busy && !state.awaitingSave) {
                    state.attemptedApply = true
                    state.rawChange()?.let(onSubmit)
                }
            }) { Text(stringResource(R.string.phone_settings_apply)) }
            else if (error != null) state.lastChange?.let { change ->
                TextButton(enabled = !busy, onClick = { onSubmit(change) }) { Text(stringResource(R.string.phone_settings_retry)) }
            }
        }
    }
}

@Composable
private fun PhoneSettingsRawEditor(state: PhoneSettingsState, busy: Boolean, invalid: Boolean) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val zoneEditor = state.editor == PhoneSettingEditor.ZONE
    val reminderEditor = state.editor == PhoneSettingEditor.TIMED_REMINDER
    if (reminderEditor) PhoneSettingsSwitch(stringResource(R.string.phone_settings_no_reminder), state.reminderEnabled,
        "phone_settings_editor_reminder_enabled", !busy) { if (!state.awaitingSave) state.reminderEnabled = it }
    OutlinedTextField(
        value = state.raw,
        onValueChange = { if (!busy && !state.awaitingSave) state.raw = it },
        modifier = Modifier.fillMaxWidth().testTag("phone_settings_editor_input"),
        enabled = !busy && (!reminderEditor || state.reminderEnabled),
        singleLine = true,
        isError = invalid,
        label = { Text(stringResource(when {
            zoneEditor -> R.string.settings_zone
            reminderEditor -> R.string.settings_timed_reminder
            else -> R.string.settings_duration
        })) },
        textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.ContentOrLtr),
        keyboardOptions = KeyboardOptions(keyboardType = if (zoneEditor) KeyboardType.Text else KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); keyboard?.hide() }),
    )
    Text(stringResource(when {
        zoneEditor -> R.string.settings_zone_help
        reminderEditor -> R.string.settings_timed_reminder_help
        else -> R.string.phone_settings_duration_help
    }), style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.ContentOrLtr))
    if (zoneEditor) {
        val zones = remember { ZoneId.getAvailableZoneIds().sorted() }
        val query = state.raw.trim()
        if (query.isNotEmpty() && fixedSettingsZone(query) == null) {
            zones.asSequence().filter { it.contains(query, ignoreCase = true) }.take(6).forEach { zone ->
                TextButton(enabled = !busy, onClick = { if (!state.awaitingSave) state.raw = zone }, modifier = Modifier.fillMaxWidth()) {
                    Text(zone, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.ContentOrLtr))
                }
            }
        }
    }
}

private fun phoneSettingsChoiceChange(editor: PhoneSettingEditor, key: String): CalendarSettingChange = when (editor) {
    PhoneSettingEditor.WEEKDAY -> CalendarSettingChange.FirstWeekday(FirstWeekdayOption.valueOf(key))
    PhoneSettingEditor.THEME -> CalendarSettingChange.Theme(SettingsTheme.valueOf(key))
    PhoneSettingEditor.VIEW -> CalendarSettingChange.View(CalendarViewOption.valueOf(key))
    PhoneSettingEditor.ALL_DAY_REMINDER -> CalendarSettingChange.AllDayReminder(AllDayReminderDefault.valueOf(key))
    PhoneSettingEditor.WEATHER_UNITS -> CalendarSettingChange.WeatherUnits(WeatherUnits.valueOf(key))
    PhoneSettingEditor.INVITATION_FILTER -> CalendarSettingChange.InvitationFilter(InvitationFilter.valueOf(key))
    else -> error("Not a choice editor")
}

@Composable
internal fun phoneSettingsChoiceLabel(editor: PhoneSettingEditor, key: String): String = stringResource(when (editor) {
    PhoneSettingEditor.WEEKDAY -> when (FirstWeekdayOption.valueOf(key)) {
        FirstWeekdayOption.LOCALE -> R.string.settings_weekday_locale
        FirstWeekdayOption.SUNDAY -> R.string.settings_weekday_sunday
        FirstWeekdayOption.MONDAY -> R.string.settings_weekday_monday
        FirstWeekdayOption.SATURDAY -> R.string.settings_weekday_saturday
    }
    PhoneSettingEditor.THEME -> when (SettingsTheme.valueOf(key)) {
        SettingsTheme.SYSTEM -> R.string.phone_settings_system_default
        SettingsTheme.LIGHT -> R.string.settings_theme_light
        SettingsTheme.DARK -> R.string.settings_theme_dark
    }
    PhoneSettingEditor.VIEW -> when (CalendarViewOption.valueOf(key)) {
        CalendarViewOption.DAY -> R.string.calendars_view_day
        CalendarViewOption.THREE_DAY -> R.string.calendars_view_three_day
        CalendarViewOption.WEEK -> R.string.calendars_view_week
        CalendarViewOption.MONTH -> R.string.calendars_view_month
        CalendarViewOption.AGENDA -> R.string.calendar_navigation_schedule
    }
    PhoneSettingEditor.ALL_DAY_REMINDER -> when (AllDayReminderDefault.valueOf(key)) {
        AllDayReminderDefault.NONE -> R.string.settings_reminder_none
        AllDayReminderDefault.PREVIOUS_DAY_0900 -> R.string.settings_reminder_previous
        AllDayReminderDefault.TWO_DAYS_BEFORE_0900 -> R.string.settings_reminder_two_days
        AllDayReminderDefault.WEEK_BEFORE_0900 -> R.string.settings_reminder_week
    }
    PhoneSettingEditor.WEATHER_UNITS -> when (WeatherUnits.valueOf(key)) {
        WeatherUnits.CELSIUS -> R.string.settings_celsius
        WeatherUnits.FAHRENHEIT -> R.string.settings_fahrenheit
    }
    PhoneSettingEditor.INVITATION_FILTER -> when (InvitationFilter.valueOf(key)) {
        InvitationFilter.ALL -> R.string.invitation_filter_all
        InvitationFilter.PENDING_ONLY -> R.string.invitation_filter_pending
    }
    else -> R.string.settings_title
})
