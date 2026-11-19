package dev.swapnilraj.calendar.ui.events

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.swapnilraj.calendar.R
import dev.swapnilraj.calendar.core.*
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.time.format.TextStyle

@Composable
internal fun RecurrenceCreationReview(plan: RecurrenceCreationPlan, spec: RecurrenceSpec, title: String,
    onConfirm: () -> Unit, onDismiss: () -> Unit, use24Hour: Boolean = true) {
    val locale = LocalLocale.current.platformLocale
    val bidi = android.text.BidiFormatter.getInstance(LocalLayoutDirection.current == LayoutDirection.Rtl)
    val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    val timed = DateTimeFormatterBuilder().appendLocalized(FormatStyle.MEDIUM, null)
        .appendLiteral(' ').appendPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, if (use24Hour) "Hm" else "hm"))
        .toFormatter(locale).withZone(plan.eventZone)
    fun day(value: java.time.DayOfWeek) = value.getDisplayName(TextStyle.FULL, locale)
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.recurrence_editor_review)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title.ifBlank { stringResource(R.string.events_no_title) }, style = MaterialTheme.typography.titleMedium)
                val pattern = when (val value = spec.pattern) {
                    RecurrencePattern.Daily -> stringResource(R.string.recurrence_editor_daily)
                    is RecurrencePattern.Weekly -> stringResource(R.string.recurrence_editor_weekly_days,
                        value.days.sortedBy { it.value }.joinToString(", ", transform = ::day))
                    is RecurrencePattern.MonthlyDate -> stringResource(R.string.recurrence_editor_monthly_day, value.day)
                    is RecurrencePattern.MonthlyWeekday -> stringResource(R.string.recurrence_editor_monthly_ordinal, stringResource(ordinalLabel(value.ordinal)), day(value.day))
                    is RecurrencePattern.Yearly -> stringResource(R.string.recurrence_editor_yearly_date,
                        value.monthDay.format(DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMMd"), locale)))
                }
                Text(pattern)
                Text(pluralStringResource(when (spec.pattern) {
                    RecurrencePattern.Daily -> R.plurals.recurrence_editor_every_day
                    is RecurrencePattern.Weekly -> R.plurals.recurrence_editor_every_week
                    is RecurrencePattern.MonthlyDate, is RecurrencePattern.MonthlyWeekday -> R.plurals.recurrence_editor_every_month
                    is RecurrencePattern.Yearly -> R.plurals.recurrence_editor_every_year
                }, spec.interval, spec.interval))
                if (spec.pattern is RecurrencePattern.Weekly) Text(stringResource(R.string.recurrence_editor_week_start_value, day(spec.weekStart)))
                Text(when (val end = spec.end) {
                    RecurrenceEnd.Never -> stringResource(R.string.recurrence_editor_no_end)
                    is RecurrenceEnd.Count -> stringResource(R.string.recurrence_editor_end_count, end.count)
                    is RecurrenceEnd.ThroughDate -> stringResource(R.string.recurrence_editor_end_date, bidi.unicodeWrap(date.format(end.date)))
                    is RecurrenceEnd.ExactUntil -> error("Imported recurrence cannot enter creation review")
                })
                Text(when (val time = plan.firstTime) {
                    is EventTime.AllDay -> allDayDateText(time, locale)
                    is EventTime.Timed -> timedDateText(time, plan.eventZone, locale, use24Hour)
                })
                if (!plan.allDay) {
                    Text(bidi.unicodeWrap(stringResource(R.string.recurrence_editor_zone_value, if (plan.eventZone.id == "Z") "UTC" else plan.eventZone.id)))
                    val seconds = (plan.firstTime as EventTime.Timed).duration.seconds
                    Text(bidi.unicodeWrap(if (seconds % 60 == 0L) pluralStringResource(R.plurals.events_duration_minutes,
                        (seconds / 60).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), seconds / 60)
                    else pluralStringResource(R.plurals.events_duration_seconds, seconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), seconds)))
                }
                Text(stringResource(R.string.recurrence_editor_preview))
                plan.preview.forEach { occurrence -> Text(bidi.unicodeWrap(when (occurrence) {
                    is RecurrenceOccurrence.AllDay -> date.format(occurrence.date)
                    is RecurrenceOccurrence.Timed -> timed.format(occurrence.instant)
                })) }
                Text(stringResource(R.string.recurrence_editor_preview_limit), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.recurrence_editor_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.events_editor_cancel)) } })
}

private fun ordinalLabel(value: Int): Int = when (value) {
    1 -> R.string.recurrence_editor_first
    2 -> R.string.recurrence_editor_second
    3 -> R.string.recurrence_editor_third
    4 -> R.string.recurrence_editor_fourth
    5 -> R.string.recurrence_editor_fifth
    -1 -> R.string.recurrence_editor_last
    -2 -> R.string.recurrence_editor_second_last
    -3 -> R.string.recurrence_editor_third_last
    -4 -> R.string.recurrence_editor_fourth_last
    -5 -> R.string.recurrence_editor_fifth_last
    else -> error("Invalid ordinal")
}
