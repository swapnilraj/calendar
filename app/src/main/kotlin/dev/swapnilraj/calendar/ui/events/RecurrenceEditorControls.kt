package dev.swapnilraj.calendar.ui.events

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.swapnilraj.calendar.R
import java.time.DayOfWeek
import java.time.format.TextStyle

/** Creation-only controls; the host must first enable the verified recurring-create writer. */
@Composable
internal fun RecurrenceEditorControls(fields: RecurrenceEditorFields, startDate: String, enabled: Boolean,
    onChange: (RecurrenceEditorFields) -> Unit) {
    val locale = LocalLocale.current.platformLocale
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.recurrence_editor_repeat), style = MaterialTheme.typography.titleSmall)
        listOf("NONE" to R.string.recurrence_editor_none, "DAILY" to R.string.recurrence_editor_daily,
            "WEEKLY" to R.string.recurrence_editor_weekly, "MONTHLY" to R.string.recurrence_editor_monthly,
            "YEARLY" to R.string.recurrence_editor_yearly).forEach { (key, label) ->
            FilterChip(selected = fields.frequency == key, enabled = enabled,
                onClick = { onChange(fields.chooseFrequency(key, startDate)) }, label = { Text(stringResource(label)) })
        }
        if (fields.frequency != "NONE") {
            RawRecurrenceInput(fields.interval, R.string.recurrence_editor_interval, enabled) { onChange(fields.copy(interval = it)) }
            if (fields.frequency == "WEEKLY") {
                Text(stringResource(R.string.recurrence_editor_weekdays))
                DayOfWeek.entries.forEach { day -> FilterChip(selected = day.name in fields.weekdays, enabled = enabled,
                    onClick = { onChange(fields.copy(weekdays = if (day.name in fields.weekdays) fields.weekdays - day.name else fields.weekdays + day.name)) },
                    label = { Text(day.getDisplayName(TextStyle.FULL, locale)) }) }
            }
            if (fields.frequency == "MONTHLY") {
                listOf("DATE" to R.string.recurrence_editor_month_date, "WEEKDAY" to R.string.recurrence_editor_month_weekday).forEach { (key, label) ->
                    FilterChip(selected = fields.monthlyMode == key, enabled = enabled,
                        onClick = { onChange(fields.copy(monthlyMode = key)) }, label = { Text(stringResource(label)) })
                }
                if (fields.monthlyMode == "DATE") RawRecurrenceInput(fields.monthDay, R.string.recurrence_editor_day, enabled) { onChange(fields.copy(monthDay = it)) }
                else if (fields.monthlyMode == "WEEKDAY") {
                    RawRecurrenceInput(fields.ordinal, R.string.recurrence_editor_ordinal, enabled) { onChange(fields.copy(ordinal = it)) }
                    DayOfWeek.entries.forEach { day -> FilterChip(selected = fields.weekday == day.name, enabled = enabled,
                        onClick = { onChange(fields.copy(weekday = day.name)) }, label = { Text(day.getDisplayName(TextStyle.FULL, locale)) }) }
                }
            }
            if (fields.frequency == "YEARLY") {
                RawRecurrenceInput(fields.yearMonth, R.string.recurrence_editor_month, enabled) { onChange(fields.copy(yearMonth = it)) }
                RawRecurrenceInput(fields.yearDay, R.string.recurrence_editor_day, enabled) { onChange(fields.copy(yearDay = it)) }
            }
            if (fields.frequency == "WEEKLY") {
                Text(stringResource(R.string.recurrence_editor_week_start))
                DayOfWeek.entries.forEach { day -> FilterChip(selected = fields.weekStart == day.name, enabled = enabled,
                    onClick = { onChange(fields.copy(weekStart = day.name)) }, label = { Text(day.getDisplayName(TextStyle.FULL, locale)) }) }
            }
            Text(stringResource(R.string.recurrence_editor_end), style = MaterialTheme.typography.titleSmall)
            listOf("NEVER" to R.string.recurrence_editor_no_end, "COUNT" to R.string.recurrence_editor_count,
                "DATE" to R.string.recurrence_editor_through).forEach { (key, label) ->
                FilterChip(selected = fields.endMode == key, enabled = enabled,
                    onClick = { onChange(fields.copy(endMode = key)) }, label = { Text(stringResource(label)) })
            }
            if (fields.endMode == "COUNT") RawRecurrenceInput(fields.count, R.string.recurrence_editor_count_value, enabled) { onChange(fields.copy(count = it)) }
            if (fields.endMode == "DATE") RawRecurrenceInput(fields.throughDate, R.string.recurrence_editor_through_value, enabled) { onChange(fields.copy(throughDate = it)) }
            Text(stringResource(R.string.recurrence_editor_skips), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun RawRecurrenceInput(value: String, label: Int, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, enabled = enabled, label = { Text(stringResource(label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}
