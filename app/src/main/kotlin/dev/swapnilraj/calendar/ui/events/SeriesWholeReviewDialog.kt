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
    val spec = review.recurrence
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.series_review_whole)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title.ifBlank { stringResource(R.string.events_no_title) }, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.series_review_whole_scope))
            SeriesLogicalInterval(review.before, R.string.series_before_master)
            SeriesLogicalInterval(review.after, R.string.series_after_master)
            SeriesReviewRule(spec, review.after.eventZone)
            Text(stringResource(R.string.series_review_limits_preserved), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.events_editor_local_save), style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(onClick = confirm) { Text(stringResource(R.string.series_confirm_whole_changes)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.events_editor_cancel)) } })
}

private fun seriesOrdinalLabel(value: Int): Int = when (value) {
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

@Composable
internal fun SeriesFollowingReviewDialog(review: SeriesFollowingReview, title: String, confirm: () -> Unit, dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.series_review_following)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title.ifBlank { stringResource(R.string.events_no_title) }, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(if (review.earlierCount == 0) R.string.series_review_following_first else R.string.series_review_following_scope))
            if (review.earlierCount > 0) Text(pluralStringResource(R.plurals.series_following_earlier, review.earlierCount, review.earlierCount))
            SeriesLogicalInterval(review.before, R.string.series_before_tail)
            SeriesLogicalInterval(review.after, R.string.series_after_tail)
            SeriesReviewRule(review.recurrence, review.after.eventZone)
            Text(stringResource(R.string.series_following_limits), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.events_editor_local_save), style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(onClick = confirm) { Text(stringResource(R.string.series_confirm_following_changes)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.events_editor_cancel)) } })
}

@Composable
private fun SeriesReviewRule(spec: RecurrenceSpec, zone: java.time.ZoneId) {
    val locale = LocalLocale.current.platformLocale
    val bidi = android.text.BidiFormatter.getInstance(locale)
    fun day(value: java.time.DayOfWeek) = value.getDisplayName(TextStyle.FULL, locale)
            Text(when (val pattern = spec.pattern) {
                RecurrencePattern.Daily -> stringResource(R.string.recurrence_editor_daily)
                is RecurrencePattern.Weekly -> stringResource(R.string.recurrence_editor_weekly_days, pattern.days.sortedBy { it.value }.joinToString(", ", transform = ::day))
                is RecurrencePattern.MonthlyDate -> stringResource(R.string.recurrence_editor_monthly_day, pattern.day)
                is RecurrencePattern.MonthlyWeekday -> stringResource(R.string.recurrence_editor_monthly_ordinal, stringResource(seriesOrdinalLabel(pattern.ordinal)), day(pattern.day))
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
                    .withLocale(locale).withZone(zone).format(end.instant)))
            })
}
