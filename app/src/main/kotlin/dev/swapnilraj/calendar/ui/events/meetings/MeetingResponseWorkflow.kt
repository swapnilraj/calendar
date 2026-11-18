package dev.swapnilraj.calendar.ui.events.meetings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.swapnilraj.calendar.R
import dev.swapnilraj.calendar.core.*
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

sealed interface MeetingResponseOpening {
    data class Ready(val source: EditableMeeting?, val recoveryOnly: Boolean) : MeetingResponseOpening
    data object DraftReview : MeetingResponseOpening
    data class SourceUnavailable(val result: MeetingReadResult<EditableMeeting>) : MeetingResponseOpening
    data object Unavailable : MeetingResponseOpening
}

/** Opening never prepares a missing draft, requests write access, or submits an operation. */
suspend fun openMeetingResponseDraft(launch: MeetingResponseLaunch, meetings: CalendarMeetings,
    store: AtomicDraftStore): MeetingResponseOpening {
    suspend fun document(): MeetingResponseDocument? {
        val record = store.load(launch.draftId) ?: return null
        return try { MeetingResponseDraftCodec.decode(record).takeIf {
            it.id == launch.draftId && it.generation == launch.generation && it.selected == launch.selected
        } } catch (_: Exception) { null }
    }
    return try {
        val first = document() ?: return MeetingResponseOpening.DraftReview
        if (first.frozen != null) return MeetingResponseOpening.Ready(null, true)
        val source = try { meetings.loadForEdit(launch.selected) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { MeetingReadResult.Unavailable }
        // A concurrent submit may have frozen the record while the live read was pending.
        val current = document() ?: return MeetingResponseOpening.DraftReview
        if (current.frozen != null) MeetingResponseOpening.Ready(null, true)
        else if (source is MeetingReadResult.Ready && source.value.details.occurrence.ref == launch.selected)
            MeetingResponseOpening.Ready(source.value, false)
        else if (source is MeetingReadResult.Ready) MeetingResponseOpening.DraftReview
        else MeetingResponseOpening.SourceUnavailable(source)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { MeetingResponseOpening.Unavailable }
}

/** Called only after the old controller has atomically discarded and retired its unsubmitted draft. */
suspend fun prepareReviewedMeetingResponse(launch: MeetingResponseLaunch, result: MeetingReadResult<EditableMeeting>,
    store: AtomicDraftStore, expectedCalendarIdentity: CalendarIdentity? = null): MeetingResponsePreparation {
    val source = (result as? MeetingReadResult.Ready)?.value ?: return MeetingResponsePreparation.Unavailable
    if (source.details.occurrence.ref != launch.selected || !responseNamespaceMatches(expectedCalendarIdentity, source))
        return MeetingResponsePreparation.RetainedForReview
    return prepareMeetingResponseDraft(source, UUID.randomUUID().toString(), store)
}

@Composable
fun MeetingResponseWorkflow(launch: MeetingResponseLaunch, meetings: CalendarMeetings, store: AtomicDraftStore,
    requestWritePermission: suspend () -> Boolean, onDismiss: () -> Unit,
    onLocalSaved: (MeetingMutationResult.LocalSaved) -> Unit,
    onReplacedLaunch: ((MeetingResponseLaunch) -> Unit)? = null, displayZone: ZoneId = ZoneId.systemDefault()) {
    key(launch.draftId, launch.generation, launch.selected, meetings, store) {
        MeetingResponseWorkflowBody(launch, meetings, store, requestWritePermission, onDismiss, onLocalSaved, onReplacedLaunch, displayZone)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MeetingResponseWorkflowBody(launch: MeetingResponseLaunch, meetings: CalendarMeetings, store: AtomicDraftStore,
    requestWritePermission: suspend () -> Boolean, onDismiss: () -> Unit,
    onLocalSaved: (MeetingMutationResult.LocalSaved) -> Unit,
    onReplacedLaunch: ((MeetingResponseLaunch) -> Unit)?, displayZone: ZoneId) {
    val coroutine = rememberCoroutineScope()
    val permission by rememberUpdatedState(requestWritePermission)
    val replacementHandler by rememberUpdatedState(onReplacedLaunch)
    var revision by remember { mutableIntStateOf(0) }
    var replacing by remember { mutableStateOf(false) }
    var replacementFailed by remember { mutableStateOf(false) }
    val opening by produceState<MeetingResponseOpening?>(null, revision) {
        value = null
        value = openMeetingResponseDraft(launch, meetings, store)
    }
    val ready = opening as? MeetingResponseOpening.Ready
    if (ready != null && !replacing && !replacementFailed) {
        val controller = remember(ready) { MeetingResponseController(launch, ready.source, meetings, store, coroutine,
            requestWritePermission = { permission() }, recoveryOnly = ready.recoveryOnly) }
        MeetingResponseSheet(controller, onDismiss, onLocalSaved, displayZone,
            onReview = if (onReplacedLaunch == null) null else { result ->
                if (!replacing) {
                    replacing = true
                    coroutine.launch {
                        val prepared = prepareReviewedMeetingResponse(launch, result, store, controller.originalCalendarIdentity)
                        if (prepared is MeetingResponsePreparation.Ready && replacementHandler != null) {
                            replacementHandler?.invoke(prepared.launch)
                            // Keep the retired controller inaccessible until the host mounts its new route.
                        } else { replacementFailed = true; replacing = false }
                    }
                }
            })
    } else ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.meeting_response_title), style = MaterialTheme.typography.headlineSmall)
            if (opening == null || replacing) CircularProgressIndicator()
            else {
                val message = when (val state = opening) {
                    MeetingResponseOpening.DraftReview -> R.string.meeting_response_draft_review
                    is MeetingResponseOpening.SourceUnavailable -> when (state.result) {
                        MeetingReadResult.PermissionRequired -> R.string.meeting_response_permission
                        is MeetingReadResult.Denied -> R.string.meeting_response_denied
                        MeetingReadResult.LimitExceeded -> R.string.meeting_response_source_large
                        else -> R.string.meeting_response_needs_review
                    }
                    else -> R.string.meeting_response_workflow_unavailable
                }
                Text(stringResource(message))
                if (!replacementFailed) TextButton(onClick = { revision++ }) { Text(stringResource(R.string.events_retry)) }
            }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.events_close)) }
        }
    }
}
