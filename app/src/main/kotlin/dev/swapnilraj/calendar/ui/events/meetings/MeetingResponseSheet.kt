package dev.swapnilraj.calendar.ui.events.meetings

import android.provider.CalendarContract
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.swapnilraj.calendar.R
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.allDayDateText
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeetingResponseSheet(controller: MeetingResponseController, onDismiss: () -> Unit,
    onLocalSaved: (MeetingMutationResult.LocalSaved) -> Unit, displayZone: ZoneId = ZoneId.systemDefault(),
    onReview: ((MeetingReadResult<EditableMeeting>) -> Unit)? = null) {
    val coroutine = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(confirmValueChange = { it != SheetValue.Hidden || !controller.state.busy })
    fun close() { coroutine.launch { if (controller.prepareDismiss()) onDismiss() else sheet.show() } }
    LaunchedEffect(controller.state.result) { controller.state.result?.let(onLocalSaved) }
    ModalBottomSheet(onDismissRequest = ::close, sheetState = sheet) {
        MeetingResponseContent(controller, ::close, displayZone, onReview)
    }
}

@Composable
fun MeetingResponseContent(controller: MeetingResponseController, onClose: () -> Unit,
    displayZone: ZoneId = ZoneId.systemDefault(), onReview: ((MeetingReadResult<EditableMeeting>) -> Unit)? = null) {
    val state = controller.state; val coroutine = rememberCoroutineScope()
    var discard by remember { mutableStateOf(false) }
    val locale = LocalLocale.current.platformLocale
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.meeting_response_title), style = MaterialTheme.typography.headlineSmall)
        if (state.loading) CircularProgressIndicator()
        Text(controller.title.ifBlank { stringResource(R.string.events_no_title) }, style = MaterialTheme.typography.titleMedium)
        controller.time?.let { time -> Text(when (time) {
            is EventTime.AllDay -> allDayDateText(time, locale)
            is EventTime.Timed -> {
                val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).withZone(displayZone)
                val bidi = android.text.BidiFormatter.getInstance(locale)
                stringResource(R.string.meeting_response_time, bidi.unicodeWrap(formatter.format(time.start)), bidi.unicodeWrap(formatter.format(time.end)))
            }
        }) }
        controller.calendarLabel?.let { Text(it) }
        controller.accountLabel?.let { Text(it) }
        if (!state.draftUnreadable && !state.loading) Text(stringResource(R.string.meeting_response_previous,
            stringResource(when (controller.originalStatus) {
                CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED -> R.string.meeting_response_yes
                CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE -> R.string.meeting_response_maybe
                CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED -> R.string.meeting_response_no
                CalendarContract.Attendees.ATTENDEE_STATUS_NONE, CalendarContract.Attendees.ATTENDEE_STATUS_INVITED -> R.string.meeting_response_unanswered
                else -> R.string.meeting_response_unknown
            })))
        state.problem?.let { Text(stringResource(problemText(it)), color = MaterialTheme.colorScheme.error) }
        if (controller.frozen && state.localOutcome == null) Text(stringResource(R.string.meeting_response_frozen))
        if (controller.frozen) TextButton(onClick = controller::recover, enabled = controller.canRecover) { Text(stringResource(R.string.meeting_response_recover)) }
        if (state.retryReady) Button(onClick = controller::retryOriginal, enabled = !state.busy) { Text(stringResource(R.string.meeting_response_retry)) }
        if (state.reviewRequired && onReview != null && controller.canReview) TextButton(onClick = { discard = true }) { Text(stringResource(R.string.meeting_response_discard_review)) }
        if (state.localOutcome != null) Text(stringResource(R.string.meeting_response_local_saved))
        Text(stringResource(R.string.meeting_response_scope), style = MaterialTheme.typography.titleSmall)
        Column(Modifier.selectableGroup()) {
            responseScopes.forEach { value ->
                val allowed = controller.canSelectScope(value)
                ResponseChoice(state.fields.scope == value.name, allowed, { controller.selectScope(value) }, stringResource(when (value) {
                    RecurrenceScope.THIS -> if (controller.recurring) R.string.meeting_response_this_occurrence else R.string.meeting_response_this_event
                    else -> R.string.meeting_response_all
                }))
                if (!allowed && controller.canChange) Text(stringResource(R.string.meeting_response_scope_unavailable), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (state.fields.scope == RecurrenceScope.ALL.name) Text(stringResource(R.string.meeting_response_all_effect))
        Text(stringResource(R.string.meeting_response_choice), style = MaterialTheme.typography.titleSmall)
        Column(Modifier.selectableGroup()) {
            MeetingResponse.entries.forEach { value -> ResponseChoice(state.fields.response == value.name, controller.canChange,
                { controller.selectResponse(value) }, stringResource(when (value) {
                    MeetingResponse.YES -> R.string.meeting_response_yes
                    MeetingResponse.MAYBE -> R.string.meeting_response_maybe
                    MeetingResponse.NO -> R.string.meeting_response_no
                })) }
        }
        if (state.fields.response == MeetingResponse.NO.name) Text(stringResource(R.string.meeting_response_decline))
        Text(stringResource(R.string.meeting_response_delivery), style = MaterialTheme.typography.bodySmall)
        if (state.busy) CircularProgressIndicator()
        Button(onClick = controller::save, enabled = controller.canSave) { Text(stringResource(R.string.meeting_response_save)) }
        TextButton(onClick = onClose, enabled = !state.busy) { Text(stringResource(R.string.events_close)) }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text(stringResource(R.string.meeting_response_discard_review)) },
        text = { Text(stringResource(R.string.meeting_response_discard_help)) },
        confirmButton = { TextButton(enabled = onReview != null && controller.canReview, onClick = {
            val handler = onReview
            if (handler != null) { discard = false; coroutine.launch { controller.discardAndReload()?.let(handler) } }
        }) { Text(stringResource(R.string.meeting_response_discard)) } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(R.string.events_close)) } })
}

@Composable
private fun ResponseChoice(selected: Boolean, enabled: Boolean, onClick: () -> Unit, text: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically) { RadioButton(selected, onClick = null, enabled = enabled); Text(text, Modifier.padding(start = 12.dp)) }
}
private fun problemText(value: MeetingResponseProblem) = when (value) {
    MeetingResponseProblem.DRAFT_REVIEW -> R.string.meeting_response_draft_review
    MeetingResponseProblem.DRAFT_CHANGED -> R.string.meeting_response_draft_changed
    MeetingResponseProblem.DRAFT_UNAVAILABLE -> R.string.meeting_response_draft_unavailable
    MeetingResponseProblem.SOURCE_CHANGED -> R.string.meeting_response_source_changed
    MeetingResponseProblem.SELECTION_REQUIRED -> R.string.meeting_response_required
    MeetingResponseProblem.PERMISSION -> R.string.meeting_response_permission
    MeetingResponseProblem.DENIED -> R.string.meeting_response_denied
    MeetingResponseProblem.UNAVAILABLE -> R.string.meeting_response_unavailable
    MeetingResponseProblem.NEEDS_REVIEW -> R.string.meeting_response_needs_review
    MeetingResponseProblem.UNSUPPORTED -> R.string.meeting_response_unsupported
    MeetingResponseProblem.INVALID -> R.string.meeting_response_invalid
    MeetingResponseProblem.UNCERTAIN -> R.string.meeting_response_uncertain
    MeetingResponseProblem.SAFE_TO_RETRY -> R.string.meeting_response_safe_retry
}
