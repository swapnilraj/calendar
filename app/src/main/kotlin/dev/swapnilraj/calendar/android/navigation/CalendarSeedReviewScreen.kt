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

/** Only the incomplete external edge needs this step; complete seeds open the real native editor. */
@Composable
internal fun CalendarSeedReviewScreen(
    result: CalendarSeedReviewResult?, busy: Boolean, dismiss: () -> Unit, retry: () -> Unit,
    requestRead: () -> Unit, edit: ((CalendarSeedReviewFields) -> CalendarSeedReviewFields) -> Unit,
    continueReview: () -> Unit,
) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().padding(20.dp).verticalScroll(rememberScrollState()).testTag("calendar_seed_review"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.seed_review_title), style = MaterialTheme.typography.headlineSmall)
            when (result) {
                null -> CircularProgressIndicator()
                is CalendarSeedReviewResult.Review -> {
                    val fields = result.snapshot.fields
                    val seed = result.snapshot.request.seed
                    if (!seed.title.isNullOrEmpty()) Text(seed.title, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.seed_review_complete))
                    OutlinedTextField(fields.startDate, { value -> edit { it.copy(startDate = value, startOffset = "") } }, readOnly = result.unverifiedInput,
                        label = { Text(stringResource(R.string.seed_review_start_date)) }, modifier = Modifier.fillMaxWidth().testTag("seed_start_date"), singleLine = true)
                    OutlinedTextField(fields.endDate, { value -> edit { it.copy(endDate = value, endOffset = "") } }, readOnly = result.unverifiedInput,
                        label = { Text(stringResource(R.string.seed_review_end_date)) }, modifier = Modifier.fillMaxWidth().testTag("seed_end_date"), singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.seed_review_all_day))
                        Switch(fields.allDay, { value -> edit { it.copy(allDay = value) } }, Modifier.testTag("seed_all_day"), enabled = !result.unverifiedInput)
                    }
                    if (!fields.allDay) {
                        OutlinedTextField(fields.startTime, { value -> edit { it.copy(startTime = value, startOffset = "") } }, readOnly = result.unverifiedInput,
                            label = { Text(stringResource(R.string.seed_review_start_time)) }, modifier = Modifier.fillMaxWidth().testTag("seed_start_time"), singleLine = true)
                        OutlinedTextField(fields.endTime, { value -> edit { it.copy(endTime = value, endOffset = "") } }, readOnly = result.unverifiedInput,
                            label = { Text(stringResource(R.string.seed_review_end_time)) }, modifier = Modifier.fillMaxWidth().testTag("seed_end_time"), singleLine = true)
                        OutlinedTextField(fields.zone, { value -> edit { it.copy(zone = value, startOffset = "", endOffset = "") } }, readOnly = result.unverifiedInput,
                            label = { Text(stringResource(R.string.seed_review_zone)) }, modifier = Modifier.fillMaxWidth().testTag("seed_zone"), singleLine = true)
                        if (ambiguous(fields.startDate, fields.startTime, fields.zone)) OutlinedTextField(fields.startOffset, { value -> edit { it.copy(startOffset = value) } }, readOnly = result.unverifiedInput, label = { Text(stringResource(R.string.seed_review_start_offset)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        if (ambiguous(fields.endDate, fields.endTime, fields.zone)) OutlinedTextField(fields.endOffset, { value -> edit { it.copy(endOffset = value) } }, readOnly = result.unverifiedInput, label = { Text(stringResource(R.string.seed_review_end_offset)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    }
                    if (!seed.recurrenceRule.isNullOrEmpty() || fields.rule.isNotEmpty()) {
                        Text(stringResource(R.string.seed_review_original_rule, seed.recurrenceRule.orEmpty()))
                        OutlinedTextField(fields.rule, { value -> edit { it.copy(rule = value) } }, readOnly = result.unverifiedInput, label = { Text(stringResource(R.string.seed_review_rule)) },
                            modifier = Modifier.fillMaxWidth().testTag("seed_rule"))
                        TextButton({ edit { it.copy(rule = "") } }, enabled = !busy && !result.unverifiedInput) { Text(stringResource(R.string.seed_review_remove_rule)) }
                    }
                    result.problem?.let { Text(stringResource(R.string.seed_review_invalid), color = MaterialTheme.colorScheme.error) }
                    if (result.unverifiedInput) Text(stringResource(R.string.seed_review_unsaved), color = MaterialTheme.colorScheme.error)
                    Button(continueReview, enabled = !busy && !result.unverifiedInput, modifier = Modifier.testTag("seed_continue")) { Text(stringResource(R.string.seed_review_continue)) }
                }
                is CalendarSeedReviewResult.NeedsMeetingEditor -> {
                    Text(stringResource(R.string.seed_review_guests))
                    result.snapshot.request.seed.guests.forEach { Text(it) }
                    Text(stringResource(R.string.seed_review_guests_retained))
                }
                CalendarSeedReviewResult.PermissionRequired -> {
                    Text(stringResource(R.string.seed_review_permission))
                    Button(requestRead) { Text(stringResource(R.string.seed_review_allow_read)) }
                }
                CalendarSeedReviewResult.Stale -> Text(stringResource(R.string.seed_review_stale))
                CalendarSeedReviewResult.Unavailable -> Text(stringResource(R.string.seed_review_unavailable))
                is CalendarSeedReviewResult.Editor -> Unit
            }
            if (result == CalendarSeedReviewResult.Stale || result == CalendarSeedReviewResult.Unavailable) {
                Button(retry, enabled = !busy) { Text(stringResource(R.string.seed_review_retry)) }
            }
            TextButton(dismiss) { Text(stringResource(R.string.seed_review_close)) }
        }
    }
}

private fun ambiguous(date: String, time: String, zone: String): Boolean = try {
    java.time.ZoneId.of(zone).rules.getValidOffsets(java.time.LocalDate.parse(date).atTime(java.time.LocalTime.parse(time))).size > 1
} catch (_: Exception) { false }
