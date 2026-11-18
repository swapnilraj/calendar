package dev.swapnilraj.calendar.ui.events

import android.provider.CalendarContract
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.swapnilraj.calendar.R
import dev.swapnilraj.calendar.core.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesScopeSheet(source: EditableSeries, action: SeriesEditorAction, onDismiss: () -> Unit,
    onSelect: (RecurrenceScope) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) { SeriesScopeContent(source, action, onDismiss, onSelect) }
}

@Composable
fun SeriesScopeContent(source: EditableSeries, action: SeriesEditorAction, onDismiss: () -> Unit,
    onSelect: (RecurrenceScope) -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(if (action == SeriesEditorAction.EDIT) R.string.series_choose_edit else R.string.series_choose_delete), style = MaterialTheme.typography.headlineSmall)
        RecurrenceScope.entries.forEach { scope ->
            val enabled = canPrepareSeriesScope(source, scope, action)
            TextButton(onClick = { onSelect(scope) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(scopeLabel(scope))) }
            if (!enabled) Text(stringResource(scopeLimitation(source.scopes[scope]?.limitation)), style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.events_close)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesEventEditorSheet(controller: SeriesEventEditorController, onDismiss: () -> Unit,
    onSaved: (SeriesMutationResult.LocalSaved) -> Unit, onDeleted: (SeriesMutationResult.LocalDeleted) -> Unit,
    onReview: ((SeriesReadResult) -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(confirmValueChange = { value ->
        value != SheetValue.Hidden || !controller.state.saving && !controller.state.reviewing
    })
    fun close() { scope.launch {
        if (controller.prepareDismiss()) onDismiss()
        else sheetState.show() // Swipe/back may have hidden it before a draft write failed.
    } }
    LaunchedEffect(controller.state.result) { when (val result = controller.state.result) {
        is SeriesMutationResult.LocalSaved -> onSaved(result)
        is SeriesMutationResult.LocalDeleted -> onDeleted(result)
        else -> Unit
    } }
    ModalBottomSheet(onDismissRequest = ::close, sheetState = sheetState) { SeriesEventEditorContent(controller, ::close, onReview) }
}

@Composable
fun SeriesEventEditorContent(controller: SeriesEventEditorController, onClose: () -> Unit, onReview: ((SeriesReadResult) -> Unit)? = null) {
    val state = controller.state; val fields = state.fields; val launch = controller.launch
    val boundaryText = seriesDeletionBoundaryText(controller)
    val coroutine = rememberCoroutineScope(); var discard by remember { mutableStateOf(false) }; var retryDelete by remember { mutableStateOf(false) }
    fun editable(field: EditableField) = controller.canChange && field in controller.fieldsAllowed
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(if (controller.recoveryOnly) R.string.events_drafts_recover else if (launch.action == SeriesEditorAction.EDIT) R.string.series_editor_edit else R.string.series_editor_delete), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(scopeLabel(launch.scope)), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(when { launch.scope == RecurrenceScope.ALL -> R.string.series_master_origin
            launch.scope == RecurrenceScope.FOLLOWING && launch.action == SeriesEditorAction.EDIT -> R.string.series_tail_origin
            else -> R.string.series_selected_origin }))
        if (controller.temporalPresented || controller.rulePresented) controller.masterContext?.calendar?.let { Text(it.account.name) }
        if (controller.recoveryOnly) Text(stringResource(R.string.series_recovery_only))
        if (state.loading || state.reviewing) CircularProgressIndicator()
        state.problem?.let { Text(stringResource(seriesProblem(it)), color = MaterialTheme.colorScheme.error) }
        if (state.recoveryRequired) TextButton(onClick = controller::recover, enabled = !state.saving) { Text(stringResource(R.string.events_editor_recover)) }
        if (state.retryReady) {
            Text(stringResource(R.string.events_editor_retry_locked))
            Button(onClick = { if (launch.action == SeriesEditorAction.DELETE) retryDelete = true else controller.retryOriginal() }, enabled = !state.saving) {
                Text(stringResource(if (launch.action == SeriesEditorAction.DELETE) R.string.events_editor_retry_delete else R.string.events_editor_retry_save))
            }
        }
        if (state.reviewRequired && onReview != null) TextButton(onClick = { discard = true }, enabled = controller.canReviewDraft) { Text(stringResource(R.string.series_discard_review)) }
        if (boundaryText != null) Text(stringResource(R.string.series_delete_original_boundary, boundaryText))
        val locale = LocalLocale.current.platformLocale
        controller.masterStart?.takeIf { launch.scope == RecurrenceScope.ALL }?.let { start ->
            val label = when (start) {
                is RecurrenceStart.AllDay -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(start.date)
                is RecurrenceStart.Timed -> DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).format(start.localDateTime)
            }
            val bidi = android.text.BidiFormatter.getInstance(locale)
            if (start is RecurrenceStart.Timed) {
                val zone = if (start.zone.normalized() == java.time.ZoneOffset.UTC) stringResource(R.string.recurrence_utc)
                    else DateTimeFormatter.ofPattern("zzzz", locale).withZone(start.zone).format(start.instant)
                Text(stringResource(R.string.series_master_start_timed, bidi.unicodeWrap(label), bidi.unicodeWrap(zone)))
            } else Text(stringResource(R.string.series_master_start, bidi.unicodeWrap(label)))
        }
        if (launch.action == SeriesEditorAction.DELETE || !controller.temporalPresented && !controller.rulePresented)
            Text(stringResource(if (launch.action == SeriesEditorAction.EDIT) R.string.series_time_unchanged else R.string.series_delete_read_only), style = MaterialTheme.typography.bodySmall)
        if (launch.scope != RecurrenceScope.THIS && launch.action == SeriesEditorAction.EDIT) {
            if (controller.temporalPresented && fields.temporal != null) SeriesTemporalControls(controller)
            if (controller.rulePresented && fields.recurrence != null) {
                SeriesExistingLimit(requireNotNull(controller.masterContext))
                if (launch.scope == RecurrenceScope.FOLLOWING) Text(stringResource(R.string.series_following_keep_help), style = MaterialTheme.typography.bodySmall)
                RecurrenceEditorControls(fields.recurrence.values, fields.temporal?.startDate ?: controller.masterStart?.date.toString(),
                    controller.canChangeRule, allowNone = false, allowKeepEnd = true, onChange = controller::changeRule)
            }
        }
        if (state.localOutcome != null) Text(stringResource(R.string.events_editor_local_save))
        OutlinedTextField(fields.title, { value -> controller.change { it.copy(title = value) } }, enabled = editable(EditableField.TITLE), label = { Text(stringResource(R.string.events_editor_title)) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(fields.location, { value -> controller.change { it.copy(location = value) } }, enabled = editable(EditableField.LOCATION), label = { Text(stringResource(R.string.events_location)) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(fields.description, { value -> controller.change { it.copy(description = value) } }, enabled = editable(EditableField.DESCRIPTION), label = { Text(stringResource(R.string.events_notes)) }, modifier = Modifier.fillMaxWidth())
        if (controller.hasDefaultAlerts) Text(stringResource(R.string.series_default_alerts))
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(fields.replaceAlerts, { value -> controller.change { it.copy(replaceAlerts = value) } }, enabled = editable(EditableField.ALERTS))
            Text(stringResource(R.string.series_replace_alerts))
        }
        if (fields.replaceAlerts) OutlinedTextField(fields.alerts, { value -> controller.change { it.copy(alerts = value) } },
            enabled = editable(EditableField.ALERTS), label = { Text(stringResource(R.string.events_editor_alerts)) }, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.series_other_reminders_retained), style = MaterialTheme.typography.bodySmall)
        SeriesMetadataChoice(R.string.events_availability, fields.availability, listOf("" to R.string.events_not_provided,
            CalendarContract.Events.AVAILABILITY_BUSY.toString() to R.string.events_busy,
            CalendarContract.Events.AVAILABILITY_FREE.toString() to R.string.events_free,
            CalendarContract.Events.AVAILABILITY_TENTATIVE.toString() to R.string.events_event_tentative), editable(EditableField.AVAILABILITY)) { value -> controller.change { it.copy(availability = value) } }
        SeriesMetadataChoice(R.string.events_visibility, fields.privacy, listOf("" to R.string.events_not_provided,
            "0" to R.string.events_calendar_default_visibility, "1" to R.string.events_confidential, "2" to R.string.events_private, "3" to R.string.events_public), editable(EditableField.PRIVACY)) { value -> controller.change { it.copy(privacy = value) } }
        if (launch.scope == RecurrenceScope.ALL && launch.action == SeriesEditorAction.EDIT) Text(stringResource(R.string.series_override_policy))
        Text(stringResource(R.string.events_editor_local_save), style = MaterialTheme.typography.bodySmall)
        if (launch.action == SeriesEditorAction.EDIT) Button(onClick = controller::save, enabled = controller.canChange && controller.hasChanges) { Text(stringResource(R.string.events_editor_save)) }
        else TextButton(onClick = controller::requestDelete, enabled = !controller.recoveryOnly && !state.loading && !state.saving && !state.reviewing && !state.reviewRequired && !state.recoveryRequired && !state.retryReady && !state.draftReadFailed && state.result !is SeriesMutationResult.LocalDeleted) {
            Text(stringResource(R.string.series_editor_delete))
        }
        TextButton(onClick = onClose, enabled = !state.saving && !state.reviewing) { Text(stringResource(R.string.events_editor_cancel)) }
    }
    state.followingReview?.let { review -> SeriesFollowingReviewDialog(review, fields.title, controller::confirmFollowingReview, controller::dismissFollowingReview) }
    state.wholeReview?.let { review -> SeriesWholeReviewDialog(review, fields.title, controller::confirmWholeReview, controller::dismissWholeReview) }
    if (state.confirmDelete || retryDelete) AlertDialog(onDismissRequest = { controller.cancelDelete(); retryDelete = false },
        title = { Text(stringResource(R.string.series_confirm_delete)) },
        text = { Text(when (launch.scope) {
            RecurrenceScope.ALL -> stringResource(R.string.series_delete_all_warning)
            RecurrenceScope.THIS -> stringResource(R.string.series_delete_this_warning)
            RecurrenceScope.FOLLOWING -> stringResource(R.string.series_delete_following_warning,
                boundaryText ?: stringResource(R.string.series_scope_unknown))
        }) },
        confirmButton = { TextButton(onClick = { if (retryDelete) { retryDelete = false; controller.retryOriginal() } else controller.confirmDelete() }) { Text(stringResource(R.string.series_delete_confirm)) } },
        dismissButton = { TextButton(onClick = { controller.cancelDelete(); retryDelete = false }) { Text(stringResource(R.string.events_editor_cancel)) } })
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text(stringResource(R.string.series_discard_review)) },
        text = { Text(stringResource(R.string.series_discard_warning)) },
        confirmButton = { TextButton(enabled = onReview != null && controller.canReviewDraft, onClick = { val handler = onReview; if (handler != null) { discard = false; coroutine.launch { controller.discardAndReload()?.let(handler) } } }) { Text(stringResource(R.string.series_discard_button)) } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(R.string.events_editor_cancel)) } })
}

@Composable
private fun SeriesMetadataChoice(label: Int, value: String, choices: List<Pair<String, Int>>, enabled: Boolean, change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, enabled = enabled) { Text(stringResource(R.string.events_context_item,
            stringResource(label), stringResource(choices.firstOrNull { it.first == value }?.second ?: R.string.events_editor_metadata_unknown))) }
        DropdownMenu(expanded, { expanded = false }) { choices.forEach { (raw, text) -> DropdownMenuItem(text = { Text(stringResource(text)) }, onClick = { expanded = false; change(raw) }) } }
    }
}

internal fun scopeLabel(scope: RecurrenceScope) = when (scope) {
    RecurrenceScope.THIS -> R.string.series_scope_this
    RecurrenceScope.FOLLOWING -> R.string.series_scope_following
    RecurrenceScope.ALL -> R.string.series_scope_all
}
private fun scopeLimitation(limitation: SeriesActionLimitation?) = when (limitation) {
    SeriesActionLimitation.READ_ONLY -> R.string.series_scope_read_only
    SeriesActionLimitation.REVIEW_REQUIRED -> R.string.series_scope_review
    SeriesActionLimitation.UNSUPPORTED -> R.string.series_scope_unsupported
    else -> R.string.series_scope_unknown
}
private fun seriesProblem(problem: SeriesEditorProblem) = when (problem) {
    SeriesEditorProblem.DRAFT_UNAVAILABLE -> R.string.events_editor_draft_unavailable
    SeriesEditorProblem.SOURCE_CHANGED -> R.string.series_source_changed
    SeriesEditorProblem.UNSUPPORTED -> R.string.series_scope_unsupported
    SeriesEditorProblem.INVALID_INPUT -> R.string.series_invalid
    SeriesEditorProblem.NO_CHANGES -> R.string.series_no_changes
    SeriesEditorProblem.PERMISSION -> R.string.events_editor_permission
    SeriesEditorProblem.DENIED -> R.string.events_editor_denied
    SeriesEditorProblem.UNAVAILABLE -> R.string.events_editor_unavailable
    SeriesEditorProblem.NEEDS_REVIEW -> R.string.series_needs_review
    SeriesEditorProblem.UNCERTAIN -> R.string.events_editor_uncertain
    SeriesEditorProblem.SAFE_TO_RETRY -> R.string.events_editor_safe_retry
    SeriesEditorProblem.RETRY_UNAVAILABLE -> R.string.events_editor_retry_unavailable
}

@Composable
internal fun seriesDeletionBoundaryText(controller: SeriesEventEditorController): String? {
    val boundary = controller.deletionBoundary ?: return null
    val locale = LocalLocale.current.platformLocale
    val bidi = android.text.BidiFormatter.getInstance(locale)
    return when (boundary) {
        is RecurrenceKey.Date -> bidi.unicodeWrap(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(boundary.date))
        is RecurrenceKey.Timed -> {
            val zone = (controller.masterStart as? RecurrenceStart.Timed)?.zone ?: return null
            val local = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.MEDIUM).withLocale(locale).withZone(zone).format(boundary.instant)
            val name = if (zone.normalized() == java.time.ZoneOffset.UTC) stringResource(R.string.recurrence_utc)
                else DateTimeFormatter.ofPattern("zzzz", locale).withZone(zone).format(boundary.instant)
            stringResource(R.string.series_boundary_timed_value, bidi.unicodeWrap(local), bidi.unicodeWrap(name))
        }
    }
}
