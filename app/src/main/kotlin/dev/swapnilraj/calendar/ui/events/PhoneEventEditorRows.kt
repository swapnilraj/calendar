package dev.swapnilraj.calendar.ui.events

import android.provider.CalendarContract
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.swapnilraj.calendar.R
import dev.swapnilraj.calendar.core.*
import java.time.format.TextStyle

@Composable
internal fun PhoneEditorDateRow(label: String, date: String, time: String, allDay: Boolean, use24Hour: Boolean,
    enabled: Boolean, onDate: () -> Unit, onTime: () -> Unit) {
    val locale = LocalLocale.current.platformLocale
    val bidi = android.text.BidiFormatter.getInstance(locale)
    val dateText = PhoneEventEditorText.date(date, locale)?.let(bidi::unicodeWrap) ?: stringResource(R.string.phone_event_date)
    val timeText = PhoneEventEditorText.time(time, use24Hour, locale)?.let(bidi::unicodeWrap) ?: stringResource(R.string.phone_event_time)
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.padding(top = 12.dp)) { PhoneEventIcon(PhoneEventGlyph.CLOCK) }
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            @Composable fun DateButton(modifier: Modifier = Modifier) {
                TextButton(onClick = onDate, enabled = enabled, modifier = modifier.heightIn(min = 48.dp).semantics { contentDescription = "$label: $dateText" }, contentPadding = PaddingValues(0.dp)) {
                    Text(dateText, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth())
                }
            }
            if (allDay || LocalDensity.current.fontScale >= 1.5f) {
                DateButton(Modifier.fillMaxWidth())
                if (!allDay) TextButton(onClick = onTime, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "$label: $timeText" }) { Text(timeText, style = MaterialTheme.typography.bodyLarge) }
            } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DateButton(Modifier.weight(1f))
                TextButton(onClick = onTime, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "$label: $timeText" }) { Text(timeText, style = MaterialTheme.typography.bodyLarge) }
            }
            if (PhoneEventEditorText.date(date, locale) == null && date.isNotBlank()) Text(stringResource(R.string.phone_event_unfinished, date), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (!allDay && PhoneEventEditorText.time(time, use24Hour, locale) == null && time.isNotBlank()) Text(stringResource(R.string.phone_event_unfinished, time), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
internal fun PhoneEditorOffsetChoices(date: String, time: String, zone: String, selected: String, enabled: Boolean, label: String, onChoose: (String) -> Unit) {
    val offsets = PhoneEventEditorText.offsets(date, time, zone)
    if (offsets.size > 1) Column(Modifier.fillMaxWidth().padding(start = 64.dp, end = 24.dp)) {
        Text(stringResource(R.string.phone_event_overlap), color = MaterialTheme.colorScheme.error)
        Text(label, style = MaterialTheme.typography.labelMedium)
        offsets.forEachIndexed { index, offset ->
            FilterChip(selected = offset.id == selected, enabled = enabled, onClick = { onChoose(offset.id) },
                label = { Text(stringResource(if (index == 0) R.string.events_editor_first_offset else R.string.events_editor_second_offset, offset.id)) })
        }
    }
}

@Composable
internal fun phoneAlertLabel(minutes: Int): String = when {
    minutes == CalendarContract.Reminders.MINUTES_DEFAULT -> stringResource(R.string.phone_event_default_notification)
    minutes == 0 -> stringResource(R.string.phone_event_at_start)
    else -> {
        val minutesLong = kotlin.math.abs(minutes.toLong())
        val number = when { minutesLong % 1440L == 0L -> minutesLong / 1440L; minutesLong % 60L == 0L -> minutesLong / 60L; else -> minutesLong }
        val resource = when {
            minutesLong % 1440L == 0L -> if (minutes > 0) R.plurals.phone_event_days_before else R.plurals.phone_event_days_after
            minutesLong % 60L == 0L -> if (minutes > 0) R.plurals.phone_event_hours_before else R.plurals.phone_event_hours_after
            else -> if (minutes > 0) R.plurals.phone_event_minutes_before else R.plurals.phone_event_minutes_after
        }
        pluralStringResource(resource, number.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), number)
    }
}
@Composable
internal fun phoneAlertsSummary(raw: String): String {
    val alerts = PhoneEventEditorText.alerts(raw) ?: return stringResource(R.string.phone_event_review_notifications)
    return when (alerts.size) { 0 -> stringResource(R.string.phone_event_no_notification); 1 -> phoneAlertLabel(alerts.single()); else -> pluralStringResource(R.plurals.phone_event_notification_count, alerts.size, alerts.size) }
}
@Composable
internal fun phoneRepeatSummary(raw: RecurrenceEditorFields): String {
    val spec = try { raw.normalized() } catch (_: Exception) { return stringResource(R.string.phone_event_repeat_incomplete) }
        ?: return stringResource(R.string.recurrence_editor_none)
    val locale = LocalLocale.current.platformLocale
    val unit = when (spec.pattern) { RecurrencePattern.Daily -> R.plurals.recurrence_editor_every_day; is RecurrencePattern.Weekly -> R.plurals.recurrence_editor_every_week; is RecurrencePattern.MonthlyDate, is RecurrencePattern.MonthlyWeekday -> R.plurals.recurrence_editor_every_month; is RecurrencePattern.Yearly -> R.plurals.recurrence_editor_every_year }
    val interval = if (spec.interval == 1) stringResource(when (spec.pattern) {
        RecurrencePattern.Daily -> R.string.recurrence_editor_daily; is RecurrencePattern.Weekly -> R.string.recurrence_editor_weekly
        is RecurrencePattern.MonthlyDate, is RecurrencePattern.MonthlyWeekday -> R.string.recurrence_editor_monthly; is RecurrencePattern.Yearly -> R.string.recurrence_editor_yearly
    }) else pluralStringResource(unit, spec.interval, spec.interval)
    val weekly = spec.pattern as? RecurrencePattern.Weekly
    return if (weekly != null) "$interval · ${weekly.days.sortedBy { it.value }.joinToString(", ") { it.getDisplayName(TextStyle.SHORT, locale) }}" else interval
}
@Composable
internal fun phoneMetadataLabel(value: String, privacy: Boolean): String = stringResource(if (privacy) when(value) {
    "" -> R.string.events_not_provided; "0" -> R.string.events_calendar_default_visibility; "1" -> R.string.events_confidential; "2" -> R.string.events_private; "3" -> R.string.events_public; else -> R.string.phone_event_review_value
} else when(value) {
    "" -> R.string.events_not_provided; "0" -> R.string.events_busy; "1" -> R.string.events_free; "2" -> R.string.events_event_tentative; else -> R.string.phone_event_review_value
})

internal fun phoneEditorProblem(problem: EditorProblem) = when (problem) {
    EditorProblem.INVALID_INPUT -> R.string.phone_event_invalid
    EditorProblem.AMBIGUOUS_TIME -> R.string.phone_event_overlap
    EditorProblem.NONEXISTENT_TIME -> R.string.phone_event_gap
    EditorProblem.PERMISSION -> R.string.phone_event_permission
    EditorProblem.DENIED -> R.string.phone_event_denied
    EditorProblem.CONFLICT -> R.string.phone_event_unverified
    EditorProblem.UNCERTAIN -> R.string.phone_event_uncertain
    EditorProblem.UNAVAILABLE -> R.string.phone_event_unavailable
    EditorProblem.DRAFT_UNAVAILABLE -> R.string.phone_event_storage
    EditorProblem.DRAFT_RETIRED -> R.string.phone_event_stopped
    EditorProblem.DRAFT_UPGRADE_REQUIRED -> R.string.phone_event_upgrade
    EditorProblem.DRAFT_VERSION -> R.string.phone_event_newer_version
    EditorProblem.RETIRE_UNAVAILABLE -> R.string.phone_event_discard_unconfirmed
    EditorProblem.RETIRE_BLOCKED_DISPATCH -> R.string.phone_event_may_have_saved
    EditorProblem.RETIRE_BLOCKED_COMMIT -> R.string.phone_event_already_saved
    EditorProblem.RETIRE_CONFLICT -> R.string.phone_event_discard_conflict
    EditorProblem.RETIRE_UNSUPPORTED -> R.string.phone_event_cannot_discard
    EditorProblem.UNSUPPORTED -> R.string.phone_event_denied
    EditorProblem.SAFE_TO_RETRY -> R.string.phone_event_safe_retry
    EditorProblem.RETRY_UNAVAILABLE -> R.string.phone_event_no_retry
    EditorProblem.VISIBILITY_REQUIRED -> R.string.events_copy_visibility_required
    EditorProblem.CALENDAR_REQUIRED -> R.string.events_calendar_reselection
    EditorProblem.RECURRENCE_UNSUPPORTED -> R.string.recurrence_editor_unavailable
    EditorProblem.RECURRENCE_INVALID -> R.string.recurrence_editor_invalid
    EditorProblem.RECURRENCE_REVIEW -> R.string.recurrence_editor_transition
}
internal fun phoneEditorNotice(notice: EditorNotice) = when (notice) {
    EditorNotice.DUPLICATE_AS_ONE_OFF -> R.string.events_copy_one_off
    EditorNotice.GUESTS_NOT_COPIED -> R.string.events_copy_guests
    EditorNotice.RECURRENCE_NOT_COPIED -> R.string.events_copy_recurrence
    EditorNotice.DEFAULT_REMINDERS_UNRESOLVED -> R.string.events_copy_default_reminders
    EditorNotice.REMINDERS_NOT_COPIED -> R.string.events_copy_other_reminders
    EditorNotice.VISIBILITY_REVIEW_REQUIRED -> R.string.events_copy_visibility_review
    EditorNotice.AVAILABILITY_REVIEW -> R.string.events_copy_availability_review
    EditorNotice.CALENDAR_VISIBILITY_REVIEW_REQUIRED -> R.string.events_copy_calendar_visibility_review
    EditorNotice.VISIBILITY_RECOVERY_ONLY -> R.string.phone_event_unknown_visibility
    EditorNotice.CALENDAR_RESELECTION_REQUIRED -> R.string.phone_event_choose_calendar_notice
    EditorNotice.DESTINATION_RECOVERY_ONLY -> R.string.phone_event_unknown_destination
}
