package dev.swapnilraj.calendar.ui.events

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.swapnilraj.calendar.R
import dev.swapnilraj.calendar.core.DraftStore
import dev.swapnilraj.calendar.core.EventTime
import dev.swapnilraj.calendar.ui.events.meetings.MeetingResponseLaunch
import dev.swapnilraj.calendar.ui.events.meetings.create.MeetingCreationLaunch
import dev.swapnilraj.calendar.ui.events.meetings.owned.OwnedMeetingLaunch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

sealed interface DraftBrowserState {
    data object Loading : DraftBrowserState
    data class Ready(val items: List<DraftBrowserItem>) : DraftBrowserState
    data object Unavailable : DraftBrowserState
}

/** Listing/opening does not modify drafts, prepare a replacement, or submit a calendar mutation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DraftBrowserSheet(store: DraftStore, onDismiss: () -> Unit, onOpen: (EditorDraftLaunch) -> Unit,
    displayZone: ZoneId = ZoneId.systemDefault(), onOpenMeeting: ((MeetingResponseLaunch) -> Unit)? = null,
    onOpenMeetingCreation: ((MeetingCreationLaunch) -> Unit)? = null,
    onOpenOwnedMeeting: ((OwnedMeetingLaunch) -> Unit)? = null) {
    var revision by remember { mutableIntStateOf(0) }
    val state by produceState<DraftBrowserState>(DraftBrowserState.Loading, store, revision) {
        value = DraftBrowserState.Loading
        try {
            val items = withContext(Dispatchers.IO) {
                store.list().sortedWith(compareByDescending<dev.swapnilraj.calendar.core.DraftRecord> { it.updatedAt }.thenBy { it.id })
                    .map(DraftBrowserCodec::read)
            }
            currentCoroutineContext().ensureActive()
            value = DraftBrowserState.Ready(items)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { value = DraftBrowserState.Unavailable }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        DraftBrowserContent(state, onDismiss, onOpen, { revision++ }, displayZone, onOpenMeeting, onOpenMeetingCreation, onOpenOwnedMeeting)
    }
}

@Composable
fun DraftBrowserContent(state: DraftBrowserState, onDismiss: () -> Unit, onOpen: (EditorDraftLaunch) -> Unit,
    onRefresh: () -> Unit, displayZone: ZoneId = ZoneId.systemDefault(), onOpenMeeting: ((MeetingResponseLaunch) -> Unit)? = null,
    onOpenMeetingCreation: ((MeetingCreationLaunch) -> Unit)? = null,
    onOpenOwnedMeeting: ((OwnedMeetingLaunch) -> Unit)? = null) {
    val locale = LocalLocale.current.platformLocale
    val formatter = remember(locale, displayZone) { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(locale).withZone(displayZone) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
        Text(stringResource(R.string.events_drafts_title), style = MaterialTheme.typography.headlineSmall)
        when (state) {
            DraftBrowserState.Loading -> {
                Text(stringResource(R.string.events_drafts_loading)); CircularProgressIndicator()
            }
            DraftBrowserState.Unavailable -> {
                Text(stringResource(R.string.events_drafts_unavailable))
                TextButton(onClick = onRefresh) { Text(stringResource(R.string.events_retry)) }
            }
            is DraftBrowserState.Ready -> {
                if (state.items.isEmpty()) Text(stringResource(R.string.events_drafts_empty), Modifier.padding(vertical = 16.dp))
                LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(state.items, key = { index, item -> "$index:${item.recordId}" }) { _, item ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val launch = item.launch
                            val owned = item.ownedMeeting
                            val creation = item.meetingCreation
                            val meeting = item.meetingPreview
                            if (owned != null) {
                                Text(owned.title.ifBlank { stringResource(R.string.events_no_title) }, style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.owned_meeting_edit))
                                if (owned.launch.needsRecovery) Text(stringResource(R.string.events_drafts_needs_recovery))
                                if (onOpenOwnedMeeting != null) TextButton(onClick = { onOpenOwnedMeeting(owned.launch) }, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text(stringResource(if (owned.launch.needsRecovery) R.string.events_drafts_recover else R.string.events_drafts_open))
                                }
                            } else if (creation != null) {
                                Text(item.meetingCreationTitle.ifBlank { stringResource(R.string.events_no_title) }, style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.phone_meeting_create_title))
                                if (creation.needsRecovery) Text(stringResource(R.string.events_drafts_needs_recovery))
                                if (onOpenMeetingCreation != null) TextButton(onClick = { onOpenMeetingCreation(creation) }, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text(stringResource(if (creation.needsRecovery) R.string.events_drafts_recover else R.string.events_drafts_open))
                                }
                            } else if (meeting != null) {
                                Text(meeting.title.ifBlank { stringResource(R.string.events_no_title) }, style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.meeting_response_draft_label))
                                Text(when (val time = meeting.time) {
                                    is EventTime.AllDay -> allDayDateText(time, locale)
                                    is EventTime.Timed -> android.text.BidiFormatter.getInstance(locale).unicodeWrap(formatter.format(time.start))
                                })
                                if (meeting.launch.needsRecovery) Text(stringResource(R.string.events_drafts_needs_recovery))
                                if (onOpenMeeting != null) TextButton(onClick = { onOpenMeeting(meeting.launch) }, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text(stringResource(if (meeting.launch.needsRecovery) R.string.events_drafts_recover else R.string.events_drafts_open))
                                } else Text(stringResource(R.string.meeting_response_draft_host_unavailable))
                            } else if (launch != null) {
                                Text(launch.title.ifBlank { stringResource(R.string.events_no_title) }, style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(if (launch.kind == EditorDraftKind.CREATE) R.string.events_drafts_create else R.string.events_drafts_edit))
                                Text(if (launch.rawDate.isBlank()) stringResource(R.string.events_drafts_date_unfinished)
                                    else stringResource(R.string.events_drafts_raw_date, launch.rawDate))
                                if (launch.needsRecovery) Text(stringResource(R.string.events_drafts_needs_recovery))
                                TextButton(onClick = { onOpen(launch) }, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text(stringResource(if (launch.needsRecovery) R.string.events_drafts_recover else R.string.events_drafts_open))
                                }
                            } else {
                                Text(stringResource(R.string.events_drafts_unrecognized), style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(if (item.issue == DraftReadIssue.UNSUPPORTED_VERSION) R.string.events_drafts_version else R.string.events_drafts_unreadable))
                                Text(stringResource(R.string.events_drafts_retained))
                            }
                            Text(stringResource(R.string.events_drafts_saved_at, formatter.format(item.updatedAt)), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                TextButton(onClick = onRefresh) { Text(stringResource(R.string.events_drafts_refresh)) }
            }
        }
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.events_close)) }
    }
}
