package dev.swapnilraj.calendar.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import dev.swapnilraj.calendar.R
import dev.swapnilraj.calendar.core.InvitationFilter
import dev.swapnilraj.calendar.ui.calendars.CalendarViewOption
import java.time.ZoneId

private val DraftSaver = listSaver<SettingsDraft, Any>(save = {
    listOf(it.view.name, it.duration, it.timedReminder, it.timedReminderEnabled, it.allDayReminder.name,
        it.firstWeekday.name, it.showWeekends, it.showDeclined, it.theme.name, it.fixedZone, it.zone, it.weatherVisible, it.units.name, it.invitationFilter.name)
}, restore = {
    SettingsDraft(CalendarViewOption.valueOf(it[0] as String), it[1] as String, it[2] as String, it[3] as Boolean,
        AllDayReminderDefault.valueOf(it[4] as String), FirstWeekdayOption.valueOf(it[5] as String), it[6] as Boolean,
        it[7] as Boolean, SettingsTheme.valueOf(it[8] as String), it[9] as Boolean, it[10] as String, it[11] as Boolean,
        WeatherUnits.valueOf(it[12] as String), InvitationFilter.entries.firstOrNull { filter -> filter.name == it.getOrNull(13) } ?: InvitationFilter.ALL)
})

/**
 * Editable presentation only. The caller atomically persists [onSave] and dismisses after success.
 * Raw invalid fields survive recomposition/restoration. No callbacks fire before explicit actions.
 * [saving] blocks edits/dismissal during a caller-owned save; [saveError] leaves the draft available.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    options: CalendarSettingsOptions,
    availableViews: List<CalendarViewOption>,
    onSave: (CalendarSettingsOptions) -> Unit,
    onDismiss: () -> Unit,
    onChooseDefaultCalendar: () -> Unit,
    onOpenReminderSetup: (() -> Unit)?,
    modifier: Modifier = Modifier,
    saving: Boolean = false,
    saveError: String? = null,
) {
    var draft by rememberSaveable(options, stateSaver = DraftSaver) { mutableStateOf(SettingsDraft.from(options)) }
    var attemptedSave by rememberSaveable(options) { mutableStateOf(false) }
    val available = availableViews.distinct()
    val errors = if (attemptedSave) draft.errors(available) else emptySet()
    val latestSaving by rememberUpdatedState(saving)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !latestSaving || it != SheetValue.Hidden })
    val zones = remember { ZoneId.getAvailableZoneIds().sorted() }
    val contentDensity = LocalDensity.current
    ModalBottomSheet(onDismissRequest = { if (!saving) onDismiss() }, sheetState = sheetState, modifier = modifier.testTag("settings_sheet")) {
        CompositionLocalProvider(LocalDensity provides contentDensity) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.95f)) {
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall.copy(textDirection = TextDirection.Content),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).semantics { heading() })
            LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("settings_fields"), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item { SectionTitle(stringResource(R.string.settings_defaults)) }
                item {
                    SettingChoice(stringResource(R.string.settings_default_view), available, draft.view, { viewLabel(it) }, enabled = !saving) { draft = draft.copy(view = it) }
                    if (SettingsField.VIEW in errors) FieldError(stringResource(R.string.settings_view_error))
                }
                item {
                    NumberField(draft.duration, { draft = draft.copy(duration = it) }, stringResource(R.string.settings_duration), !saving,
                        SettingsField.DURATION in errors, stringResource(R.string.settings_duration_error), "settings_duration")
                    MinutePresets(listOf(30, 60, 90), draft.duration, !saving) { draft = draft.copy(duration = it) }
                }
                item {
                    SettingSwitch(stringResource(R.string.settings_timed_reminder_enabled), draft.timedReminderEnabled, !saving) { draft = draft.copy(timedReminderEnabled = it) }
                    if (draft.timedReminderEnabled) {
                        NumberField(draft.timedReminder, { draft = draft.copy(timedReminder = it) }, stringResource(R.string.settings_timed_reminder), !saving,
                            SettingsField.TIMED_REMINDER in errors, stringResource(R.string.settings_timed_reminder_error), "settings_timed_reminder")
                        MinutePresets(listOf(0, 10, 30), draft.timedReminder, !saving) { draft = draft.copy(timedReminder = it) }
                        Text(stringResource(R.string.settings_timed_reminder_help), style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content))
                    }
                }
                item {
                    SettingChoice(stringResource(R.string.settings_all_day_reminder), AllDayReminderDefault.entries, draft.allDayReminder, { allDayLabel(it) }, !saving) { draft = draft.copy(allDayReminder = it) }
                    Text(stringResource(R.string.settings_all_day_zone_help), style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content))
                }
                item {
                    TextButton(enabled = !saving, onClick = onChooseDefaultCalendar, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_default_calendar)) }
                    onOpenReminderSetup?.let { open ->
                        TextButton(enabled = !saving, onClick = open, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_reminder_setup)) }
                    }
                }
                item { SectionTitle(stringResource(R.string.settings_calendar_display)) }
                item {
                    SettingChoice(stringResource(R.string.settings_first_weekday), FirstWeekdayOption.entries, draft.firstWeekday, { weekdayLabel(it) }, !saving) { draft = draft.copy(firstWeekday = it) }
                }
                item { SettingSwitch(stringResource(R.string.settings_show_weekends), draft.showWeekends, !saving) { draft = draft.copy(showWeekends = it) } }
                item { SettingSwitch(stringResource(R.string.settings_show_declined), draft.showDeclined, !saving) { draft = draft.copy(showDeclined = it) } }
                item { SectionTitle(stringResource(R.string.settings_appearance)) }
                item { SettingChoice(stringResource(R.string.settings_theme), SettingsTheme.entries, draft.theme, { themeLabel(it) }, !saving) { draft = draft.copy(theme = it) } }
                item {
                    SettingSwitch(stringResource(R.string.settings_fixed_zone), draft.fixedZone, !saving) { draft = draft.copy(fixedZone = it) }
                    Text(stringResource(R.string.settings_device_zone_help), style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content))
                }
                if (draft.fixedZone) item {
                    OutlinedTextField(draft.zone, { draft = draft.copy(zone = it) }, modifier = Modifier.fillMaxWidth().testTag("settings_zone"), enabled = !saving,
                        label = { Text(stringResource(R.string.settings_zone)) }, singleLine = true, isError = SettingsField.ZONE in errors,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                        supportingText = { Text(stringResource(if (SettingsField.ZONE in errors) R.string.settings_zone_error else R.string.settings_zone_help)) })
                    val query = draft.zone.trim()
                    if (query.isNotEmpty() && fixedSettingsZone(query) == null) {
                        zones.asSequence().filter { it.contains(query, ignoreCase = true) }.take(6).forEach { zone ->
                            TextButton(enabled = !saving, onClick = { draft = draft.copy(zone = zone) }, modifier = Modifier.fillMaxWidth()) {
                                Text(zone, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content))
                            }
                        }
                    }
                }
                item { SectionTitle(stringResource(R.string.settings_weather)) }
                item { SettingSwitch(stringResource(R.string.settings_weather_visible), draft.weatherVisible, !saving) { draft = draft.copy(weatherVisible = it) } }
                item { SettingChoice(stringResource(R.string.settings_weather_units), WeatherUnits.entries, draft.units, { unitsLabel(it) }, !saving) { draft = draft.copy(units = it) } }
            }
            if (errors.isNotEmpty()) Box(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) { FieldError(stringResource(R.string.settings_check_fields)) }
            if (saveError != null) Text(saveError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite })
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(enabled = !saving, onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.settings_cancel)) }
                Button(enabled = !saving, onClick = {
                    attemptedSave = true
                    draft.validated(available)?.let(onSave)
                }, modifier = Modifier.weight(1f).testTag("settings_save")) { Text(stringResource(if (saving) R.string.settings_saving else R.string.settings_save)) }
            }
        }
    }
    }
}

@Composable private fun SectionTitle(label: String) {
    Text(label, style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content), modifier = Modifier.semantics { heading() })
}
@Composable private fun FieldError(label: String) {
    Text(label, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}
@Composable private fun NumberField(value: String, onValue: (String) -> Unit, label: String, enabled: Boolean, error: Boolean, errorText: String, tag: String) {
    OutlinedTextField(value, onValue, modifier = Modifier.fillMaxWidth().testTag(tag), enabled = enabled, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
        isError = error, supportingText = if (error) ({ Text(errorText) }) else null)
}
@Composable private fun MinutePresets(values: List<Int>, current: String, enabled: Boolean, onChange: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { value ->
            FilterChip(selected = current.trim().toIntOrNull() == value, enabled = enabled, onClick = { onChange(value.toString()) },
                label = { Text(pluralStringResource(R.plurals.settings_minutes, value, value), style = MaterialTheme.typography.labelLarge.copy(textDirection = TextDirection.Content)) })
        }
    }
}
@Composable private fun SettingSwitch(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f).padding(end = 12.dp), style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content))
        Switch(checked, onChange, enabled = enabled, modifier = Modifier.semantics { contentDescription = label })
    }
}
@Composable private fun <T> SettingChoice(label: String, values: List<T>, current: T, labelFor: @Composable (T) -> String, enabled: Boolean, onChange: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val valueLabel = if (current in values) labelFor(current) else stringResource(R.string.settings_unsupported_view)
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content))
        Box {
            OutlinedButton(enabled = enabled && values.isNotEmpty(), onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()
                .semantics { contentDescription = "$label, $valueLabel" }) { Text(valueLabel, style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content)) }
            DropdownMenu(expanded && enabled, onDismissRequest = { expanded = false }) {
                values.forEach { value -> DropdownMenuItem(text = { Text(labelFor(value), style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content)) },
                    modifier = Modifier.semantics { selected = current == value }, onClick = { expanded = false; onChange(value) }) }
            }
        }
    }
}
@Composable private fun viewLabel(value: CalendarViewOption) = stringResource(when (value) {
    CalendarViewOption.DAY -> R.string.calendars_view_day
    CalendarViewOption.MONTH -> R.string.calendars_view_month
    CalendarViewOption.THREE_DAY -> R.string.calendars_view_three_day
    CalendarViewOption.WEEK -> R.string.calendars_view_week
    CalendarViewOption.AGENDA -> R.string.calendars_view_agenda
})
@Composable private fun allDayLabel(value: AllDayReminderDefault) = stringResource(when (value) {
    AllDayReminderDefault.NONE -> R.string.settings_reminder_none
    AllDayReminderDefault.PREVIOUS_DAY_0900 -> R.string.settings_reminder_previous
    AllDayReminderDefault.TWO_DAYS_BEFORE_0900 -> R.string.settings_reminder_two_days
    AllDayReminderDefault.WEEK_BEFORE_0900 -> R.string.settings_reminder_week
})
@Composable private fun weekdayLabel(value: FirstWeekdayOption) = stringResource(when (value) {
    FirstWeekdayOption.LOCALE -> R.string.settings_weekday_locale
    FirstWeekdayOption.SUNDAY -> R.string.settings_weekday_sunday
    FirstWeekdayOption.MONDAY -> R.string.settings_weekday_monday
    FirstWeekdayOption.SATURDAY -> R.string.settings_weekday_saturday
})
@Composable private fun themeLabel(value: SettingsTheme) = stringResource(when (value) {
    SettingsTheme.SYSTEM -> R.string.settings_theme_system
    SettingsTheme.LIGHT -> R.string.settings_theme_light
    SettingsTheme.DARK -> R.string.settings_theme_dark
})
@Composable private fun unitsLabel(value: WeatherUnits) = stringResource(if (value == WeatherUnits.CELSIUS) R.string.settings_celsius else R.string.settings_fahrenheit)
