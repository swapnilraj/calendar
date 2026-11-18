package dev.swapnilraj.calendar.ui.events

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.swapnilraj.calendar.R
import dev.swapnilraj.calendar.core.*
import kotlinx.coroutines.launch

/** Phone presentation adapter. It never prepares, writes, retries or replaces a draft on its own. */
@Composable
fun PhoneEventEditor(
    controller: EventEditorController,
    writableCalendars: List<CalendarInfo>,
    onDismiss: () -> Unit,
    onSaved: (EventRef) -> Unit,
    onDeleted: (EventRef) -> Unit,
    onReviewExisting: ((EditReadResult) -> Unit)? = null,
    use24Hour: Boolean = true,
    onReplacedLaunch: ((EditorDraftLaunch) -> Unit)? = null,
    onDiscarded: (() -> Unit)? = onDismiss,
    contentVisible: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    val result = controller.state.result
    LaunchedEffect(result) { when (result) {
        is MutationResult.LocalSaved -> onSaved(result.ref)
        is MutationResult.LocalDeleted -> onDeleted(result.ref)
        else -> Unit
    } }
    fun close() { scope.launch { if (controller.prepareDismiss()) onDismiss() } }
    Dialog(onDismissRequest = { if (contentVisible) close() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false, dismissOnBackPress = contentVisible)) {
        if (!contentVisible) {
            // Keep the controller/scope and outcome effects mounted while hiding every field,
            // child picker and action during a fresh authority check.
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                    TextButton(onClick = ::close, enabled = !controller.state.saving) {
                        Text(stringResource(R.string.events_close))
                    }
                }
            }
        } else PhoneEventEditorContent(controller, writableCalendars, ::close, use24Hour,
            onReview = if (onReviewExisting != null) ({ scope.launch { controller.reloadExisting()?.let(onReviewExisting) } }) else null,
            onUpgrade = if (onReplacedLaunch != null) ({ scope.launch { controller.upgradeLegacyDraft()?.let(onReplacedLaunch) } }) else null,
            onReplace = if (onReplacedLaunch != null) ({ scope.launch { controller.replaceDraft()?.let(onReplacedLaunch) } }) else null,
            onDiscard = if (onDiscarded != null) ({ scope.launch { if (controller.discardDraft()) onDiscarded() } }) else null,
            onInspectRetirement = { scope.launch { controller.inspectRetirement() } },
            onAcknowledgeOutcome = { scope.launch { controller.acknowledgeLocalOutcome() } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhoneEventEditorContent(
    controller: EventEditorController, calendars: List<CalendarInfo>, onClose: () -> Unit,
    use24Hour: Boolean = true, onReview: (() -> Unit)? = null, onUpgrade: (() -> Unit)? = null,
    onReplace: (() -> Unit)? = null, onDiscard: (() -> Unit)? = null,
    onInspectRetirement: (() -> Unit)? = null, onAcknowledgeOutcome: (() -> Unit)? = null,
) {
    val view = PhoneEventEditorPresentation(controller, calendars)
    val state = view.state; val fields = view.fields
    val locale = LocalLocale.current.platformLocale
    val bidi = android.text.BidiFormatter.getInstance(locale)
    val scope = rememberCoroutineScope()
    var panel by remember { mutableStateOf<PhoneEditorPanel?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()
    LaunchedEffect(controller, calendars, state.loading, state.saving, state.recoveryRequired, state.retryReady) { controller.observeCalendars(calendars) }
    LaunchedEffect(state.problem) { if (state.problem != null) scroll.animateScrollTo(0) }
    Surface(Modifier.fillMaxSize()) {
        Scaffold(topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val closeLabel = stringResource(R.string.phone_event_close)
                IconButton(onClick = onClose, enabled = !state.saving, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = closeLabel }) { PhoneEventIcon(PhoneEventGlyph.CLOSE) }
                Text(stringResource(if (controller.recoveryOnly) (if (controller.pendingOperationKind == "delete") R.string.phone_event_check_delete else R.string.phone_event_check_save) else if (controller.isEditing) R.string.events_editor_edit else R.string.events_editor_create),
                    style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { heading() })
                TextButton(onClick = controller::save, enabled = view.canSave, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.events_editor_save)) }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(scroll).padding(bottom = 32.dp)) {
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                PhoneEditorStatus(controller, onReview, onUpgrade, onReplace = { confirm = "replace" }.takeIf { onReplace != null },
                    onDiscard = { confirm = "discard" }.takeIf { onDiscard != null }, onRetryDelete = { confirm = "retryDelete" }, use24Hour = use24Hour,
                    onInspectRetirement = onInspectRetirement ?: { scope.launch { controller.inspectRetirement() }; Unit },
                    onAcknowledgeOutcome = onAcknowledgeOutcome ?: { scope.launch { controller.acknowledgeLocalOutcome() }; Unit })
                val titleLabel = stringResource(R.string.phone_event_title)
                TextField(fields.title, { value -> controller.change { it.copy(title = value) } }, enabled = view.canEdit(EditableField.TITLE),
                    placeholder = { Text(stringResource(R.string.phone_event_add_title)) }, textStyle = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.fillMaxWidth().padding(start = 48.dp, end = 12.dp).heightIn(min = 88.dp).semantics { contentDescription = titleLabel },
                    colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent))
                PhoneEditorRow(PhoneEventGlyph.CALENDAR, view.calendar?.displayName ?: stringResource(if (controller.isEditing) R.string.events_drafts_calendar_unavailable else R.string.phone_event_choose_calendar),
                    view.calendar?.account?.name ?: controller.boundCalendarIdentity?.account?.name,
                    enabled = view.canChooseCalendar, onClick = { panel = PhoneEditorPanel.CALENDAR }, swatch = view.calendar?.color?.let { Color(it or 0xff000000.toInt()) })
                HorizontalDivider(Modifier.padding(start = 64.dp))
                Row(Modifier.fillMaxWidth().toggleable(value = fields.allDay, enabled = view.canEdit(EditableField.TIME), role = Role.Switch, onValueChange = { value -> controller.change { it.copy(allDay = value) } }).padding(start = 64.dp, end = 24.dp).heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.events_all_day), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(fields.allDay, onCheckedChange = null, enabled = view.canEdit(EditableField.TIME))
                }
                PhoneEditorDateRow(stringResource(R.string.phone_event_starts), fields.startDate, fields.startTime, fields.allDay, use24Hour,
                    view.canEdit(EditableField.TIME), { panel = PhoneEditorPanel.START_DATE }, { panel = PhoneEditorPanel.START_TIME })
                PhoneEditorDateRow(stringResource(R.string.phone_event_ends), fields.endDate, fields.endTime, fields.allDay, use24Hour,
                    view.canEdit(EditableField.TIME), { panel = PhoneEditorPanel.END_DATE }, { panel = PhoneEditorPanel.END_TIME })
                if (!fields.allDay) {
                    if (!view.blocked && view.requiresProviderZoneChoice) Text(stringResource(R.string.phone_event_region_zone), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 64.dp, end = 24.dp))
                    PhoneEditorRow(PhoneEventGlyph.ZONE, stringResource(R.string.phone_event_zone),
                        PhoneEventEditorText.zone(fields.zone)?.let(bidi::unicodeWrap) ?: stringResource(R.string.phone_event_unfinished, fields.zone), view.canEdit(EditableField.ZONE), { panel = PhoneEditorPanel.ZONE })
                    PhoneEditorOffsetChoices(fields.startDate, fields.startTime, fields.zone, fields.startOffset, view.canEdit(EditableField.TIME), stringResource(R.string.phone_event_start_time)) { value -> controller.change { it.copy(startOffset = value) } }
                    PhoneEditorOffsetChoices(fields.endDate, fields.endTime, fields.zone, fields.endOffset, view.canEdit(EditableField.TIME), stringResource(R.string.phone_event_end_time)) { value -> controller.change { it.copy(endOffset = value) } }
                }
                HorizontalDivider(Modifier.padding(start = 64.dp))
                PhoneEditorRow(PhoneEventGlyph.PLACE, fields.location.ifBlank { stringResource(R.string.phone_event_add_location) }, enabled = view.canEdit(EditableField.LOCATION), onClick = { panel = PhoneEditorPanel.LOCATION })
                PhoneEditorRow(PhoneEventGlyph.ALERT, phoneAlertsSummary(fields.alerts), enabled = view.canEdit(EditableField.ALERTS), onClick = { panel = PhoneEditorPanel.ALERTS })
                PhoneEditorRow(PhoneEventGlyph.NOTES, fields.description.ifBlank { stringResource(R.string.phone_event_add_notes) }, enabled = view.canEdit(EditableField.DESCRIPTION), onClick = { panel = PhoneEditorPanel.DESCRIPTION })
                HorizontalDivider(Modifier.padding(start = 64.dp))
                PhoneEditorRow(PhoneEventGlyph.AVAILABILITY, stringResource(R.string.events_availability), phoneMetadataLabel(fields.availability, false), view.canEdit(EditableField.AVAILABILITY), { panel = PhoneEditorPanel.AVAILABILITY })
                PhoneEditorRow(PhoneEventGlyph.VISIBILITY, stringResource(R.string.events_visibility), phoneMetadataLabel(fields.privacy, true), view.canEdit(EditableField.PRIVACY), { panel = PhoneEditorPanel.PRIVACY })
                if (!view.needsAttention && controller.canReplace && onReplace != null) TextButton(onClick = { confirm = "replace" }, modifier = Modifier.padding(start = 52.dp)) { Text(stringResource(R.string.phone_event_replace)) }
                if (!view.needsAttention && controller.canDiscard && onDiscard != null) TextButton(onClick = { confirm = "discard" }, modifier = Modifier.padding(start = 52.dp)) { Text(stringResource(R.string.phone_event_discard)) }
                Text(stringResource(R.string.phone_event_local_save), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 64.dp, end = 24.dp, top = 20.dp))
                if (controller.isEditing && controller.existing != null && !controller.recoveryOnly) TextButton(onClick = controller::requestDelete, enabled = !view.blocked, modifier = Modifier.padding(start = 52.dp)) { Text(stringResource(R.string.events_editor_delete)) }
            }
        }
    }
    panel?.let { current -> PhoneEditorPanelContent(current, view, use24Hour) { panel = null } }
    if (state.confirmDelete) AlertDialog(onDismissRequest = controller::cancelDelete, title = { Text(stringResource(R.string.events_editor_confirm_delete)) }, text = { Text(stringResource(R.string.events_editor_delete_description)) },
        confirmButton = { TextButton(onClick = controller::confirmDelete) { Text(stringResource(R.string.events_editor_delete)) } }, dismissButton = { TextButton(onClick = controller::cancelDelete) { Text(stringResource(R.string.events_editor_cancel)) } })
    confirm?.let { action -> AlertDialog(onDismissRequest = { confirm = null },
        title = { Text(stringResource(when (action) { "replace" -> R.string.phone_event_replace; "retryDelete" -> R.string.phone_event_retry_delete; else -> R.string.phone_event_discard })) },
        text = { Text(stringResource(when { action == "replace" -> if (controller.isEditing) R.string.phone_event_replace_edit_body else R.string.phone_event_replace_body; action == "retryDelete" -> R.string.events_editor_delete_description; controller.hasFrozenSubmission -> R.string.phone_event_stop_discard_body; else -> R.string.phone_event_discard_body })) },
        confirmButton = { TextButton(enabled = !state.saving, onClick = { confirm = null; when (action) { "replace" -> onReplace?.invoke(); "retryDelete" -> controller.retryOriginal(); else -> onDiscard?.invoke() } }) { Text(stringResource(R.string.phone_event_confirm)) } },
        dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.events_editor_cancel)) } }) }
}

@Composable
internal fun PhoneEditorRow(icon: PhoneEventGlyph, title: String, subtitle: String? = null, enabled: Boolean = false,
    onClick: (() -> Unit)? = null, swatch: Color? = null) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
        .padding(horizontal = 24.dp, vertical = 12.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        PhoneEventIcon(icon)
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.ContentOrLtr)); if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.ContentOrLtr), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        swatch?.let { Box(Modifier.size(16.dp).background(it, CircleShape)) }
    }
}

@Composable
private fun PhoneEditorStatus(controller: EventEditorController, onReview: (() -> Unit)?, onUpgrade: (() -> Unit)?, onReplace: (() -> Unit)?, onDiscard: (() -> Unit)?, onRetryDelete: () -> Unit, use24Hour: Boolean,
    onInspectRetirement: () -> Unit, onAcknowledgeOutcome: () -> Unit) {
    val state = controller.state
    val attention = state.legacy || state.reviewRequired || state.recoveryRequired || controller.hasFrozenSubmission || state.draftReadFailed || state.localOutcome != null
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        state.problem?.let { Text(stringResource(if (it == EditorProblem.PERMISSION && controller.hasFrozenSubmission) R.string.phone_event_pending_permission else phoneEditorProblem(it)), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
        state.localOutcome?.let { Text(stringResource(if (it is MutationResult.LocalDeleted) R.string.phone_event_local_deleted else R.string.phone_event_local_saved), style = MaterialTheme.typography.bodyMedium) }
        state.reviewLatest?.let { PhoneLatestEventReview(it, use24Hour) }
        state.notices.forEach { Text(stringResource(phoneEditorNotice(it)), style = MaterialTheme.typography.bodyMedium) }
        if (state.recoveryRequired && !state.draftReadFailed) TextButton(onClick = controller::recover, enabled = !state.saving) { Text(stringResource(if (controller.pendingOperationKind == "delete") R.string.phone_event_check_delete else R.string.phone_event_check_save)) }
        if (state.retryReady) TextButton(onClick = { if (controller.pendingOperationKind == "delete") onRetryDelete() else controller.retryOriginal() }, enabled = !state.saving) { Text(stringResource(if (controller.pendingOperationKind == "delete") R.string.phone_event_retry_delete else R.string.phone_event_retry_save)) }
        if (state.reviewRequired && controller.canReviewLatest && onReview != null) TextButton(onClick = onReview) { Text(stringResource(R.string.events_editor_review)) }
        if (controller.canUpgrade && onUpgrade != null) TextButton(onClick = onUpgrade) { Text(stringResource(R.string.phone_event_continue)) }
        if (attention && controller.canReplace && onReplace != null) TextButton(onClick = onReplace) { Text(stringResource(R.string.phone_event_replace)) }
        if (attention && controller.canDiscard && onDiscard != null) TextButton(onClick = onDiscard) { Text(stringResource(R.string.phone_event_discard)) }
        if (controller.canInspectRetirement) TextButton(onClick = onInspectRetirement, enabled = !state.saving) { Text(stringResource(R.string.phone_event_check_discard)) }
        if (controller.canAcknowledge) TextButton(onClick = onAcknowledgeOutcome) { Text(stringResource(R.string.phone_event_finish_close)) }
    }
}

@Composable
private fun PhoneLatestEventReview(details: EventDetails, use24Hour: Boolean) {
    val locale = LocalLocale.current.platformLocale
    val zone = details.eventZone ?: java.time.ZoneOffset.UTC
    val time = when (val value = details.occurrence.time) {
        is EventTime.AllDay -> allDayDateText(value, locale)
        is EventTime.Timed -> timedDateText(value, zone, locale, use24Hour)
    }
    OutlinedCard(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.events_editor_latest), style = MaterialTheme.typography.titleSmall)
            Text(details.occurrence.title.ifBlank { stringResource(R.string.events_no_title) })
            Text(time)
            if (details.occurrence.time is EventTime.Timed) Text(zone.id, style = MaterialTheme.typography.bodySmall)
            if (details.location.isNotBlank()) Text(details.location)
            if (details.description.isNotBlank()) Text(details.description)
        }
    }
}
