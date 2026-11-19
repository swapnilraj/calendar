package dev.swapnilraj.calendar.android.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.swapnilraj.calendar.R
import dev.swapnilraj.calendar.ui.events.EventEditorFields
import dev.swapnilraj.calendar.ui.events.phoneMetadataLabel

@Composable
internal fun CalendarEditReviewScreen(result: CalendarEditReviewResult?, busy: Boolean, close: () -> Unit,
    retry: () -> Unit, requestRead: () -> Unit, apply: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().padding(20.dp).verticalScroll(rememberScrollState()).testTag("calendar_edit_review"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.incoming_edit_review_title), style = MaterialTheme.typography.headlineSmall)
            val snapshot = when (result) {
                is CalendarEditReviewResult.Review -> result.snapshot
                is CalendarEditReviewResult.Unsupported -> result.snapshot
                else -> null
            }
            when (result) {
                null -> CircularProgressIndicator()
                is CalendarEditReviewResult.Review -> Text(stringResource(R.string.incoming_edit_review_help))
                is CalendarEditReviewResult.Unsupported -> result.reasons.forEach { reason ->
                    Text(stringResource(when (reason) {
                        CalendarEditReviewIssue.GUESTS -> R.string.incoming_edit_review_guests
                        CalendarEditReviewIssue.RECURRENCE -> R.string.incoming_edit_review_recurrence
                        CalendarEditReviewIssue.FIELD_NOT_ALLOWED -> R.string.incoming_edit_review_field
                        CalendarEditReviewIssue.SOURCE_NOT_ELIGIBLE -> R.string.incoming_edit_review_ineligible
                        CalendarEditReviewIssue.ZONE_AUTHORING_REVIEW -> R.string.incoming_edit_review_zone_authoring
                    }), color = MaterialTheme.colorScheme.error)
                }
                CalendarEditReviewResult.PermissionRequired -> {
                    Text(stringResource(R.string.incoming_edit_review_permission))
                    Button(requestRead) { Text(stringResource(R.string.incoming_edit_review_allow)) }
                }
                CalendarEditReviewResult.Stale -> Text(stringResource(R.string.incoming_edit_review_stale))
                CalendarEditReviewResult.Consumed -> Text(stringResource(R.string.incoming_edit_review_consumed))
                CalendarEditReviewResult.Unavailable -> {
                    Text(stringResource(R.string.incoming_edit_review_unavailable))
                    Button(retry, enabled = !busy) { Text(stringResource(R.string.incoming_edit_review_retry)) }
                }
                is CalendarEditReviewResult.Editor -> Unit
            }
            snapshot?.let {
                it.original?.let { original ->
                    Text(stringResource(R.string.incoming_edit_review_current), style = MaterialTheme.typography.titleMedium)
                    Text(original.title.ifEmpty { stringResource(R.string.events_no_title) })
                    val environment = when (result) {
                        is CalendarEditReviewResult.Review -> result.environment
                        is CalendarEditReviewResult.Unsupported -> result.environment
                        else -> null
                    }
                    environment?.calendars?.singleOrNull { calendar -> calendar.identity == it.request.binding.calendar }?.let { calendar ->
                        Text(calendar.displayName); Text(calendar.account.name, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(time(original))
                    Text(if (original.allDay) stringResource(R.string.incoming_edit_review_utc_dates)
                        else it.sourceZone?.id ?: stringResource(R.string.incoming_edit_review_unstored_zone, original.zone))
                }
                Text(stringResource(R.string.incoming_edit_review_proposed), style = MaterialTheme.typography.titleMedium)
                val seed = it.request.proposed
                seed.title?.let { value -> field(stringResource(R.string.events_editor_title), value) }
                if (seed.occurrence.begin != null || seed.occurrence.end != null || seed.occurrence.allDay != null) {
                    it.suggested?.let { suggested -> field(stringResource(R.string.incoming_edit_review_time), time(suggested)) }
                        ?: field(stringResource(R.string.incoming_edit_review_time), listOfNotNull(seed.occurrence.begin?.toString(), seed.occurrence.end?.toString(),
                            seed.occurrence.allDay?.let { flag -> stringResource(if (flag) R.string.events_all_day else R.string.incoming_edit_review_timed) }).joinToString(" · "))
                }
                seed.timeZone?.let { value -> field(stringResource(R.string.seed_review_zone), value.id) }
                seed.location?.let { value -> field(stringResource(R.string.events_location), value) }
                seed.description?.let { value -> field(stringResource(R.string.events_notes), value) }
                seed.availability?.let { value -> field(stringResource(R.string.events_availability), phoneMetadataLabel(value.toString(), false)) }
                seed.accessLevel?.let { value -> field(stringResource(R.string.events_visibility), phoneMetadataLabel(value.toString(), true)) }
                seed.recurrenceRule?.let { value -> field(stringResource(R.string.seed_review_rule), value) }
                if (seed.guests.isNotEmpty()) field(stringResource(R.string.incoming_edit_review_guest_list), seed.guests.joinToString("\n"))
                it.suggested?.let { fields ->
                    if (fields.startDate.isBlank() || fields.endDate.isBlank() || !fields.allDay && (fields.startTime.isBlank() || fields.endTime.isBlank()))
                        Text(stringResource(R.string.incoming_edit_review_incomplete))
                }
            }
            if (result is CalendarEditReviewResult.Review) Button(apply, enabled = !busy, modifier = Modifier.testTag("incoming_edit_apply")) {
                Text(stringResource(R.string.incoming_edit_review_apply))
            }
            TextButton(close) { Text(stringResource(R.string.incoming_edit_review_close)) }
        }
    }
}

@Composable private fun field(label: String, value: String) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    Text(value.ifEmpty { stringResource(R.string.incoming_edit_review_empty) })
}
private fun time(fields: EventEditorFields): String = if (fields.allDay) "${fields.startDate} → ${fields.endDate}"
    else "${fields.startDate} ${fields.startTime} → ${fields.endDate} ${fields.endTime}"
