package dev.swapnilraj.calendar.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import dev.swapnilraj.calendar.R
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.meetings.create.*
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** Navigation metadata only. Mounting always reloads the stored feature and generation. */
internal object MeetingCreationRouteCodec {
    fun encode(launch: MeetingCreationLaunch): String = JSONObject().put("id", launch.draftId)
        .put("generation", launch.generation).toString()
    fun decode(raw: String): MeetingCreationLaunch {
        require(raw.length <= 4096)
        val value = JSONObject(raw)
        require(value.keys().asSequence().toSet() == setOf("id", "generation"))
        val id = value.get("id") as? String ?: error("Invalid draft ID")
        val generation = value.get("generation") as? String ?: error("Invalid generation")
        require(id.isNotBlank() && id.length <= 2048 && UUID.fromString(generation).toString() == generation)
        return MeetingCreationLaunch(id, generation, false)
    }
}

private sealed interface MeetingCreationMount {
    data object Loading : MeetingCreationMount
    data object Unavailable : MeetingCreationMount
    data class Ready(val launch: MeetingCreationLaunch) : MeetingCreationMount
}

@Composable
internal fun MeetingCreationWorkflow(route: MeetingCreationLaunch, meetings: CalendarMeetings,
    store: AtomicDraftStore, calendars: List<CalendarInfo>, retirement: OperationRetirement?,
    requestWritePermission: suspend () -> Boolean, use24Hour: Boolean,
    onDismiss: () -> Unit, onSaved: (EventRef) -> Unit, clock: Clock = Clock.systemUTC(),
    guestSuggestions: dev.swapnilraj.calendar.contacts.DeviceContactSuggestionSource? = null,
    contactsPermissionGranted: Boolean = false, requestContactsPermission: () -> Unit = {}) {
    var retry by remember(route.draftId, route.generation) { mutableIntStateOf(0) }
    val permission by rememberUpdatedState(requestWritePermission)
    val mounted by produceState<MeetingCreationMount>(MeetingCreationMount.Loading, route.draftId, route.generation, store, retry) {
        value = MeetingCreationMount.Loading
        value = try {
            val launch = store.load(route.draftId)?.let(MeetingCreationDraftCodec::describe)
            if (launch != null && launch.draftId == route.draftId && launch.generation == route.generation)
                MeetingCreationMount.Ready(launch) else MeetingCreationMount.Unavailable
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { MeetingCreationMount.Unavailable }
    }
    val launch = (mounted as? MeetingCreationMount.Ready)?.launch
    if (launch == null) {
        AlertDialog(onDismissRequest = onDismiss,
            text = { Text(stringResource(if (mounted == MeetingCreationMount.Loading) R.string.events_drafts_loading else R.string.events_drafts_unreadable)) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.events_close)) } },
            dismissButton = { if (mounted == MeetingCreationMount.Unavailable) TextButton(onClick = { retry++ }) { Text(stringResource(R.string.events_retry)) } })
        return
    }
    val controller = remember(launch.draftId, launch.generation, meetings, store, retirement) {
        MeetingCreationController(launch, meetings, store, { permission() }, retirement, clock)
    }
    PhoneMeetingCreateEditor(controller, calendars, onDismiss, onSaved, onDiscarded = onDismiss, use24Hour = use24Hour,
        guestSuggestions = guestSuggestions, contactsPermissionGranted = contactsPermissionGranted,
        requestContactsPermission = requestContactsPermission)
}
