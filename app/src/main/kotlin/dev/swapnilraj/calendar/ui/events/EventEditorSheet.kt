package dev.swapnilraj.calendar.ui.events

import android.provider.CalendarContract
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.swapnilraj.calendar.R
import dev.swapnilraj.calendar.core.*
import java.time.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventEditorSheet(
    controller: EventEditorController,
    writableCalendars: List<CalendarInfo>,
    onDismiss: () -> Unit,
    onSaved: (EventRef) -> Unit,
    onDeleted: (EventRef) -> Unit,
    onReviewExisting: ((EditReadResult) -> Unit)? = null,
    use24Hour: Boolean = true,
    onReplacedLaunch: ((EditorDraftLaunch) -> Unit)? = null,
    onDiscarded: (() -> Unit)? = onDismiss,
) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(confirmValueChange = { it != SheetValue.Hidden || !controller.state.saving })
    val result = controller.state.result
    LaunchedEffect(result) {
        when (result) {
            is MutationResult.LocalSaved -> onSaved(result.ref)
            is MutationResult.LocalDeleted -> onDeleted(result.ref)
            else -> Unit
        }
    }
    fun close() {
        scope.launch {
            if (controller.prepareDismiss()) onDismiss()
            else sheetState.show() // A swipe/back may already have hidden it; keep failed drafts visible.
        }
    }
    ModalBottomSheet(onDismissRequest = ::close, sheetState = sheetState) {
        EventEditorContent(controller, writableCalendars, ::close,
            onReviewExisting = if (controller.canReviewLatest) ({ scope.launch { controller.reloadExisting()?.let { onReviewExisting?.invoke(it) } } }) else null,
            onDiscardAndReload = null, use24Hour = use24Hour,
            onUpgradeDraft = if (onReplacedLaunch != null) ({ scope.launch { controller.upgradeLegacyDraft()?.let(onReplacedLaunch) } }) else null,
            onReplaceDraft = if (onReplacedLaunch != null) ({ scope.launch { controller.replaceDraft()?.let(onReplacedLaunch) } }) else null,
            onDiscardDraft = if (onDiscarded != null) ({ scope.launch { if (controller.discardDraft()) onDiscarded() } }) else null)
    }
}

/** Content-only entry point allows isolated rendering and keyboard/controller tests. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventEditorContent(
    controller: EventEditorController,
    writableCalendars: List<CalendarInfo>,
    onClose: () -> Unit,
    onReviewExisting: (() -> Unit)? = null,
    onDiscardAndReload: (() -> Unit)? = null,
    use24Hour: Boolean = true,
    onUpgradeDraft: (() -> Unit)? = null,
    onReplaceDraft: (() -> Unit)? = null,
    onDiscardDraft: (() -> Unit)? = null,
) {
    val state = controller.state
    val fields = state.fields
    val existing = controller.existing
    val scroll = rememberScrollState()
    val actionScope = rememberCoroutineScope()
    LaunchedEffect(state.problem) { if (state.problem != null) scroll.animateScrollTo(0) }
    var dateField by remember { mutableStateOf<String?>(null) }
    var timeField by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var draftAction by remember { mutableStateOf<String?>(null) }
    var confirmRetryDelete by remember { mutableStateOf(false) }
    LaunchedEffect(controller, writableCalendars, state.loading, state.saving, state.recoveryRequired, state.retryReady) {
        controller.observeCalendars(writableCalendars)
    }
    val blocked = state.legacy || controller.recoveryOnly || state.loading || state.saving || state.draftReadFailed || state.reviewRequired || state.recoveryRequired || state.retryReady || state.problem in setOf(EditorProblem.UNCERTAIN,
        EditorProblem.UNSUPPORTED, EditorProblem.DRAFT_VERSION, EditorProblem.DRAFT_RETIRED) || state.result is MutationResult.LocalSaved || state.result is MutationResult.LocalDeleted
    fun canEdit(field: EditableField) = !blocked && (existing == null || field in existing.allowedFields)
    Column(Modifier.fillMaxWidth().imePadding().verticalScroll(scroll).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(if (controller.recoveryOnly) R.string.events_drafts_recover
            else if (controller.isEditing) R.string.events_editor_edit else R.string.events_editor_create),
            style = MaterialTheme.typography.headlineSmall)
        if (controller.recoveryOnly) {
            Text(stringResource(R.string.events_drafts_recovery_only), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.events_drafts_recovery_description))
        }
        state.notices.forEach { notice ->
            Text(stringResource(noticeResource(notice)), style = MaterialTheme.typography.bodyMedium)
        }
        if (state.loading) CircularProgressIndicator()
        state.problem?.let { problem ->
            if (problem != EditorProblem.CALENDAR_REQUIRED || EditorNotice.CALENDAR_RESELECTION_REQUIRED !in state.notices)
                Text(stringResource(problemResource(problem)), color = MaterialTheme.colorScheme.error)
            if (state.recoveryRequired) TextButton(onClick = controller::recover, enabled = !state.saving) {
                Text(stringResource(R.string.events_editor_recover))
            }
            if (state.retryReady) {
                Text(stringResource(R.string.events_editor_retry_locked))
                Button(onClick = {
                    if (controller.pendingOperationKind == "delete") confirmRetryDelete = true else controller.retryOriginal()
                }, enabled = !state.saving) {
                    Text(stringResource(if (controller.pendingOperationKind == "delete") R.string.events_editor_retry_delete else R.string.events_editor_retry_save))
                }
            }
            if (problem == EditorProblem.CONFLICT && onReviewExisting != null && controller.canReviewLatest && !state.recoveryRequired) TextButton(onClick = onReviewExisting) {
                Text(stringResource(R.string.events_editor_review))
            }
        }
        if (state.reviewRequired) {
            Text(stringResource(R.string.events_editor_stale_draft))
            state.reviewLatest?.let { latest -> LatestEventForReview(latest) }
            if (onDiscardAndReload != null) TextButton(onClick = { confirmDiscard = true }, enabled = !state.saving) {
                Text(stringResource(R.string.events_editor_discard_reload))
            }
        }
        OutlinedTextField(fields.title, { value -> controller.change { it.copy(title = value) } },
            label = { Text(stringResource(R.string.events_editor_title)) }, enabled = canEdit(EditableField.TITLE), modifier = Modifier.fillMaxWidth())
        if (!controller.isEditing && !controller.recoveryOnly) {
            Text(stringResource(R.string.events_calendar))
            writableCalendars.filter { it.capabilities.createEvents == AccessDecision.ALLOWED }.forEach { calendar ->
                FilterChip(selected = fields.targetIdentity == calendar.identity && fields.calendarId == calendar.id.value.toString(), enabled = !blocked && calendar.account.name.isNotBlank() && calendar.account.type.isNotBlank(),
                    onClick = { controller.selectCalendar(calendar) },
                    label = { Text("${calendar.displayName} · ${calendar.account.name}") })
            }
            if (writableCalendars.none { it.capabilities.createEvents == AccessDecision.ALLOWED })
                Text(stringResource(R.string.events_editor_no_calendar))
        } else Text(existing?.details?.calendar?.displayName ?: writableCalendars.firstOrNull {
            it.identity == fields.targetIdentity && it.id.value.toString() == fields.calendarId
        }?.displayName ?: stringResource(R.string.events_drafts_calendar_unavailable))
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = fields.allDay,
            enabled = canEdit(EditableField.TIME), role = Role.Checkbox,
            onValueChange = { value -> controller.change { it.copy(allDay = value) } }),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(fields.allDay, onCheckedChange = null, enabled = canEdit(EditableField.TIME), modifier = Modifier.padding(12.dp))
            Text(stringResource(R.string.events_all_day))
        }
        OutlinedTextField(fields.startDate, { value -> controller.change { it.copy(startDate = value) } },
            label = { Text(stringResource(R.string.events_editor_start_date)) }, enabled = canEdit(EditableField.TIME), singleLine = true,
            modifier = Modifier.fillMaxWidth(), trailingIcon = { TextButton(enabled = canEdit(EditableField.TIME), onClick = { dateField = "start" }) {
                Text(stringResource(R.string.events_editor_choose)) } })
        OutlinedTextField(fields.endDate, { value -> controller.change { it.copy(endDate = value) } },
            label = { Text(stringResource(if (fields.allDay) R.string.events_editor_last_date else R.string.events_editor_end_date)) },
            enabled = canEdit(EditableField.TIME), singleLine = true, modifier = Modifier.fillMaxWidth(),
            trailingIcon = { TextButton(enabled = canEdit(EditableField.TIME), onClick = { dateField = "end" }) {
                Text(stringResource(R.string.events_editor_choose)) } })
        if (!fields.allDay) {
            OutlinedTextField(fields.startTime, { value -> controller.change { it.copy(startTime = value) } },
                label = { Text(stringResource(R.string.events_editor_start_time)) }, enabled = canEdit(EditableField.TIME), singleLine = true,
                modifier = Modifier.fillMaxWidth(), trailingIcon = { TextButton(enabled = canEdit(EditableField.TIME), onClick = { timeField = "start" }) {
                    Text(stringResource(R.string.events_editor_choose)) } })
            OffsetChoices(fields.startDate, fields.startTime, fields.zone, fields.startOffset, canEdit(EditableField.TIME)) { value ->
                controller.change { it.copy(startOffset = value) }
            }
            OutlinedTextField(fields.endTime, { value -> controller.change { it.copy(endTime = value) } },
                label = { Text(stringResource(R.string.events_editor_end_time)) }, enabled = canEdit(EditableField.TIME), singleLine = true,
                modifier = Modifier.fillMaxWidth(), trailingIcon = { TextButton(enabled = canEdit(EditableField.TIME), onClick = { timeField = "end" }) {
                    Text(stringResource(R.string.events_editor_choose)) } })
            OffsetChoices(fields.endDate, fields.endTime, fields.zone, fields.endOffset, canEdit(EditableField.TIME)) { value ->
                controller.change { it.copy(endOffset = value) }
            }
            OutlinedTextField(fields.zone, { value -> controller.change { it.copy(zone = value) } },
                label = { Text(stringResource(R.string.events_editor_zone)) }, enabled = canEdit(EditableField.ZONE),
                singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        OutlinedTextField(fields.location, { value -> controller.change { it.copy(location = value) } },
            label = { Text(stringResource(R.string.events_location)) }, enabled = canEdit(EditableField.LOCATION), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(fields.description, { value -> controller.change { it.copy(description = value) } },
            label = { Text(stringResource(R.string.events_notes)) }, enabled = canEdit(EditableField.DESCRIPTION), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(fields.alerts, { value -> controller.change { it.copy(alerts = value) } },
            label = { Text(stringResource(R.string.events_editor_alerts)) }, enabled = canEdit(EditableField.ALERTS), modifier = Modifier.fillMaxWidth())
        MetadataChoice(R.string.events_availability, fields.availability, listOf(
            "" to R.string.events_not_provided,
            CalendarContract.Events.AVAILABILITY_BUSY.toString() to R.string.events_busy,
            CalendarContract.Events.AVAILABILITY_FREE.toString() to R.string.events_free,
            CalendarContract.Events.AVAILABILITY_TENTATIVE.toString() to R.string.events_event_tentative,
        ), canEdit(EditableField.AVAILABILITY)) { value -> controller.change { it.copy(availability = value) } }
        MetadataChoice(R.string.events_visibility, fields.privacy, listOf(
            "" to R.string.events_not_provided,
            CalendarContract.Events.ACCESS_DEFAULT.toString() to R.string.events_calendar_default_visibility,
            CalendarContract.Events.ACCESS_PRIVATE.toString() to R.string.events_private,
            CalendarContract.Events.ACCESS_PUBLIC.toString() to R.string.events_public,
            CalendarContract.Events.ACCESS_CONFIDENTIAL.toString() to R.string.events_confidential,
        ), canEdit(EditableField.PRIVACY)) { value -> controller.change { it.copy(privacy = value) } }
        Text(stringResource(R.string.events_editor_local_save), style = MaterialTheme.typography.bodySmall)
        Button(onClick = controller::save, enabled = !blocked && !state.reviewRequired && !controller.calendarSelectionRequired && !controller.visibilityChoiceRequired && (existing != null || writableCalendars.any {
            it.identity == fields.targetIdentity && it.id.value.toString() == fields.calendarId && it.capabilities.createEvents == AccessDecision.ALLOWED
        }), modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.events_editor_save)) }
        if (existing != null && !controller.recoveryOnly) TextButton(onClick = controller::requestDelete, enabled = !blocked && !state.reviewRequired) {
            Text(stringResource(R.string.events_editor_delete))
        }
        if (state.recoveryRequired || state.reviewRequired) Text(controller.boundCalendarIdentity?.account?.name ?: stringResource(R.string.editor_original_namespace_unknown))
        if (state.localOutcome != null) Text(stringResource(R.string.editor_local_outcome_known))
        if (controller.canAcknowledge) TextButton(onClick = { actionScope.launch { controller.acknowledgeLocalOutcome() } }) { Text(stringResource(R.string.editor_acknowledge_cleanup)) }
        if (controller.canUpgrade && onUpgradeDraft != null) TextButton(onClick = onUpgradeDraft) { Text(stringResource(R.string.editor_upgrade_draft)) }
        if (controller.canReplace && onReplaceDraft != null) TextButton(onClick = { draftAction = "replace" }) { Text(stringResource(R.string.editor_replace_draft)) }
        if (controller.canDiscard && onDiscardDraft != null) TextButton(onClick = { draftAction = "discard" }) { Text(stringResource(if (controller.hasFrozenSubmission) R.string.editor_retire_discard else R.string.editor_discard_only)) }
        if (controller.canInspectRetirement) TextButton(onClick = { actionScope.launch { controller.inspectRetirement() } }) { Text(stringResource(R.string.editor_inspect_retirement)) }
        TextButton(onClick = onClose, enabled = !state.saving, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.events_editor_cancel)) }
    }
    dateField?.let { target ->
        val date = runCatching { LocalDate.parse(if (target == "start") fields.startDate else fields.endDate) }.getOrNull()
        val picker = rememberDatePickerState(yearRange = 1..9999,
            initialSelectedDateMillis = date?.takeIf { it.year in 1..9999 }?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli())
        DatePickerDialog(onDismissRequest = { dateField = null }, confirmButton = {
            TextButton(enabled = picker.selectedDateMillis != null, onClick = {
                picker.selectedDateMillis?.let { millis ->
                    val selected = Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC).toLocalDate().toString()
                    controller.change { if (target == "start") it.copy(startDate = selected) else it.copy(endDate = selected) }
                }; dateField = null
            }) { Text(stringResource(R.string.events_editor_use_date)) }
        }) { DatePicker(picker) }
    }
    timeField?.let { target ->
        val parsed = runCatching { LocalTime.parse(if (target == "start") fields.startTime else fields.endTime) }.getOrNull()
        val picker = rememberTimePickerState(initialHour = parsed?.hour ?: 9, initialMinute = parsed?.minute ?: 0, is24Hour = use24Hour)
        AlertDialog(onDismissRequest = { timeField = null }, text = { TimeInput(picker) }, confirmButton = {
            TextButton(onClick = {
                val selected = LocalTime.of(picker.hour, picker.minute).toString()
                controller.change { if (target == "start") it.copy(startTime = selected) else it.copy(endTime = selected) }
                timeField = null
            }) { Text(stringResource(R.string.events_editor_use_time)) }
        }, dismissButton = { TextButton(onClick = { timeField = null }) { Text(stringResource(R.string.events_editor_cancel)) } })
    }
    if (draftAction != null) AlertDialog(onDismissRequest = { draftAction = null },
        title = { Text(stringResource(if (draftAction == "replace") R.string.editor_replace_draft else if (controller.hasFrozenSubmission) R.string.editor_retire_discard else R.string.editor_discard_only)) },
        text = { Text(stringResource(if (draftAction == "replace") R.string.editor_replace_help else if (controller.hasFrozenSubmission) R.string.editor_retire_help else R.string.editor_discard_help)) },
        confirmButton = { TextButton(enabled = !state.saving, onClick = { val chosen = draftAction; draftAction = null; if (chosen == "replace") onReplaceDraft?.invoke() else onDiscardDraft?.invoke() }) { Text(stringResource(R.string.editor_confirm_draft_action)) } },
        dismissButton = { TextButton(onClick = { draftAction = null }) { Text(stringResource(R.string.events_editor_cancel)) } })
    if (confirmRetryDelete) AlertDialog(onDismissRequest = { confirmRetryDelete = false },
        title = { Text(stringResource(R.string.events_editor_retry_delete_confirm)) },
        text = { Text(stringResource(R.string.events_editor_retry_locked)) },
        confirmButton = { TextButton(onClick = { confirmRetryDelete = false; controller.retryOriginal() }) {
            Text(stringResource(R.string.events_editor_retry_delete)) } },
        dismissButton = { TextButton(onClick = { confirmRetryDelete = false }) { Text(stringResource(R.string.events_editor_cancel)) } })
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false },
        title = { Text(stringResource(R.string.events_editor_discard_confirm)) },
        text = { Text(stringResource(R.string.events_editor_discard_description)) },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; onDiscardAndReload?.invoke() }) {
            Text(stringResource(R.string.events_editor_discard_reload)) } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.events_editor_cancel)) } })
    if (state.confirmDelete) AlertDialog(onDismissRequest = controller::cancelDelete,
        title = { Text(stringResource(R.string.events_editor_confirm_delete)) },
        text = { Text(stringResource(R.string.events_editor_delete_description)) },
        confirmButton = { TextButton(onClick = controller::confirmDelete) { Text(stringResource(R.string.events_editor_delete)) } },
        dismissButton = { TextButton(onClick = controller::cancelDelete) { Text(stringResource(R.string.events_editor_cancel)) } })
}

@Composable
private fun LatestEventForReview(details: EventDetails) {
    val locale = androidx.compose.ui.platform.LocalLocale.current.platformLocale
    val zone = details.eventZone ?: ZoneId.systemDefault()
    val time = when (val value = details.occurrence.time) {
        is EventTime.AllDay -> allDayDateText(value, locale)
        is EventTime.Timed -> timedDateText(value, zone, locale, true)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.events_editor_latest), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.events_editor_latest_field, stringResource(R.string.events_editor_title), details.occurrence.title))
        Text(time)
        Text(stringResource(R.string.events_editor_latest_field, stringResource(R.string.events_editor_zone), zone.id))
        Text(stringResource(R.string.events_editor_latest_field, stringResource(R.string.events_location), details.location))
        Text(stringResource(R.string.events_editor_latest_field, stringResource(R.string.events_notes), details.description))
        Text(stringResource(R.string.events_reminders))
        details.reminders.forEach { reminder ->
            val timing = if (reminder.minutesBefore == android.provider.CalendarContract.Reminders.MINUTES_DEFAULT)
                stringResource(R.string.events_default_timing)
            else if (reminder.minutesBefore >= 0) androidx.compose.ui.res.pluralStringResource(
                R.plurals.events_minutes_before, reminder.minutesBefore, reminder.minutesBefore)
            else androidx.compose.ui.res.pluralStringResource(R.plurals.events_minutes_after,
                (-reminder.minutesBefore.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), -reminder.minutesBefore.toLong())
            Text(timing)
        }
    }
}

@Composable
private fun OffsetChoices(date: String, time: String, zone: String, selected: String, enabled: Boolean, onChoose: (String) -> Unit) {
    val offsets = runCatching { ZoneId.of(zone).rules.getValidOffsets(LocalDate.parse(date).atTime(LocalTime.parse(time))) }.getOrDefault(emptyList())
    if (offsets.size > 1) {
        Text(stringResource(R.string.events_editor_choose_offset))
        offsets.forEachIndexed { index, offset ->
            FilterChip(selected = selected == offset.id, enabled = enabled, onClick = { onChoose(offset.id) },
                label = { Text(stringResource(if (index == 0) R.string.events_editor_first_offset else R.string.events_editor_second_offset, offset.id)) })
        }
    }
}

private fun problemResource(problem: EditorProblem) = when (problem) {
    EditorProblem.INVALID_INPUT -> R.string.events_editor_invalid
    EditorProblem.AMBIGUOUS_TIME -> R.string.events_editor_ambiguous
    EditorProblem.NONEXISTENT_TIME -> R.string.events_editor_nonexistent
    EditorProblem.PERMISSION -> R.string.events_editor_permission
    EditorProblem.DENIED -> R.string.events_editor_denied
    EditorProblem.CONFLICT -> R.string.events_editor_conflict
    EditorProblem.UNCERTAIN -> R.string.events_editor_uncertain
    EditorProblem.UNAVAILABLE -> R.string.events_editor_unavailable
    EditorProblem.DRAFT_UNAVAILABLE -> R.string.events_editor_draft_unavailable
    EditorProblem.DRAFT_RETIRED -> R.string.events_editor_draft_retired
    EditorProblem.DRAFT_UPGRADE_REQUIRED -> R.string.editor_upgrade_needed
    EditorProblem.RETIRE_UNAVAILABLE -> R.string.editor_retirement_unavailable
    EditorProblem.RETIRE_BLOCKED_DISPATCH -> R.string.editor_retirement_dispatched
    EditorProblem.RETIRE_BLOCKED_COMMIT -> R.string.editor_retirement_committed
    EditorProblem.RETIRE_CONFLICT -> R.string.editor_retirement_conflict
    EditorProblem.RETIRE_UNSUPPORTED -> R.string.editor_retirement_unsupported
    EditorProblem.UNSUPPORTED -> R.string.events_editor_unsupported
    EditorProblem.DRAFT_VERSION -> R.string.events_editor_draft_version
    EditorProblem.SAFE_TO_RETRY -> R.string.events_editor_safe_retry
    EditorProblem.RETRY_UNAVAILABLE -> R.string.events_editor_retry_unavailable
    EditorProblem.VISIBILITY_REQUIRED -> R.string.events_copy_visibility_required
    EditorProblem.CALENDAR_REQUIRED -> R.string.events_calendar_reselection
    EditorProblem.RECURRENCE_UNSUPPORTED -> R.string.events_editor_unsupported
    EditorProblem.RECURRENCE_INVALID -> R.string.events_editor_invalid
    EditorProblem.RECURRENCE_REVIEW -> R.string.events_editor_unsupported
}

@Composable
private fun MetadataChoice(label: Int, current: String, options: List<Pair<String, Int>>, enabled: Boolean, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = options.firstOrNull { it.first == current }?.second ?: R.string.events_editor_metadata_unknown
    Box {
        TextButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.events_context_item, stringResource(label), stringResource(selected)))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, text) -> DropdownMenuItem(text = { Text(stringResource(text)) }, onClick = {
                expanded = false; onChange(value)
            }) }
        }
    }
}

private fun noticeResource(notice: EditorNotice) = when (notice) {
    EditorNotice.DUPLICATE_AS_ONE_OFF -> R.string.events_copy_one_off
    EditorNotice.GUESTS_NOT_COPIED -> R.string.events_copy_guests
    EditorNotice.RECURRENCE_NOT_COPIED -> R.string.events_copy_recurrence
    EditorNotice.DEFAULT_REMINDERS_UNRESOLVED -> R.string.events_copy_default_reminders
    EditorNotice.REMINDERS_NOT_COPIED -> R.string.events_copy_other_reminders
    EditorNotice.VISIBILITY_REVIEW_REQUIRED -> R.string.events_copy_visibility_review
    EditorNotice.AVAILABILITY_REVIEW -> R.string.events_copy_availability_review
    EditorNotice.CALENDAR_VISIBILITY_REVIEW_REQUIRED -> R.string.events_copy_calendar_visibility_review
    EditorNotice.VISIBILITY_RECOVERY_ONLY -> R.string.events_copy_visibility_recovery_only
    EditorNotice.CALENDAR_RESELECTION_REQUIRED -> R.string.events_calendar_reselection
    EditorNotice.DESTINATION_RECOVERY_ONLY -> R.string.events_calendar_recovery_only
}
