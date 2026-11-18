package dev.swapnilraj.calendar.ui.events

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.swapnilraj.calendar.R
import dev.swapnilraj.calendar.core.*
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle

@Composable
internal fun SeriesWholeReviewDialog(review: SeriesWholeReview, title: String, confirm: () -> Unit, dismiss: () -> Unit) {
    val locale = LocalLocale.current.platformLocale
    val bidi = android.text.BidiFormatter.getInstance(locale)
    val spec = review.recurrence
    fun day(value: java.time.DayOfWeek) = value.getDisplayName(TextStyle.FULL, locale)
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.series_review_whole)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title.ifBlank { stringResource(R.string.events_no_title) }, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.series_review_whole_scope))
            SeriesLogicalInterval(review.before, R.string.series_before_master)
            SeriesLogicalInterval(review.after, R.string.series_after_master)
            Text(when (val pattern = spec.pattern) {
                RecurrencePattern.Daily -> stringResource(R.string.recurrence_editor_daily)
                is RecurrencePattern.Weekly -> stringResource(R.string.recurrence_editor_weekly_days, pattern.days.sortedBy { it.value }.joinToString(", ", transform = ::day))
                is RecurrencePattern.MonthlyDate -> stringResource(R.string.recurrence_editor_monthly_day, pattern.day)
                is RecurrencePattern.MonthlyWeekday -> stringResource(R.string.series_review_monthly_weekday, pattern.ordinal, day(pattern.day))
                is RecurrencePattern.Yearly -> stringResource(R.string.recurrence_editor_yearly_date,
                    pattern.monthDay.format(DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMMd"), locale)))
            })
            Text(pluralStringResource(when (spec.pattern) {
                RecurrencePattern.Daily -> R.plurals.recurrence_editor_every_day
                is RecurrencePattern.Weekly -> R.plurals.recurrence_editor_every_week
                is RecurrencePattern.MonthlyDate, is RecurrencePattern.MonthlyWeekday -> R.plurals.recurrence_editor_every_month
                is RecurrencePattern.Yearly -> R.plurals.recurrence_editor_every_year
            }, spec.interval, spec.interval))
            Text(when (val end = spec.end) {
                RecurrenceEnd.Never -> stringResource(R.string.recurrence_editor_no_end)
                is RecurrenceEnd.Count -> stringResource(R.string.recurrence_editor_end_count, end.count)
                is RecurrenceEnd.ThroughDate -> stringResource(R.string.recurrence_editor_end_date,
                    bidi.unicodeWrap(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(end.date)))
                is RecurrenceEnd.ExactUntil -> stringResource(R.string.series_exact_until, bidi.unicodeWrap(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                    .withLocale(locale).withZone(review.after.eventZone).format(end.instant)))
            })
            Text(stringResource(R.string.series_review_limits_preserved), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.events_editor_local_save), style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(onClick = confirm) { Text(stringResource(R.string.series_confirm_whole_changes)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.events_editor_cancel)) } })
}
