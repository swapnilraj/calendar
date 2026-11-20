package dev.swapnilraj.calendar.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import dev.swapnilraj.calendar.R
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.PhoneEditorConflictSnapshot
import dev.swapnilraj.calendar.ui.events.meetings.owned.*
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** The route carries no source authority. Reopening always reads the exact durable generation. */
internal data class OwnedMeetingRoute(val draftId: String, val generation: String)

internal object OwnedMeetingRouteCodec {
    fun encode(launch: OwnedMeetingLaunch): String = JSONObject().put("id", launch.draftId)
        .put("generation", launch.generation).toString()
    fun decode(raw: String): OwnedMeetingRoute {
        require(raw.length <= 4096)
        val value = JSONObject(raw)
        require(value.keys().asSequence().toSet() == setOf("id", "generation"))
        val id = value.get("id") as? String ?: error("Invalid draft ID")
        val generation = value.get("generation") as? String ?: error("Invalid generation")
        require(id.isNotBlank() && id.length <= 2048 && UUID.fromString(generation).toString() == generation)
        return OwnedMeetingRoute(id, generation)
    }
}

private sealed interface OwnedMeetingMount {
    data object Loading : OwnedMeetingMount
    data object Unavailable : OwnedMeetingMount
    data class Ready(val launch: OwnedMeetingLaunch) : OwnedMeetingMount
}

@Composable
internal fun OwnedMeetingWorkflow(route: OwnedMeetingRoute, meetings: CalendarMeetings,
    store: AtomicDraftStore, retirement: OperationRetirement?, requestWritePermission: suspend () -> Boolean,
    use24Hour: Boolean, presentationVisible: Boolean, onDismiss: () -> Unit,
    onSaved: (EventRef) -> Unit, onCancelled: (EventRef) -> Unit, clock: Clock = Clock.systemUTC(),
    guestSuggestions: dev.swapnilraj.calendar.contacts.DeviceContactSuggestionSource? = null,
    contactsPermissionGranted: Boolean = false, requestContactsPermission: () -> Unit = {},
    conflictAdvisory: (@Composable (PhoneEditorConflictSnapshot) -> Unit)? = null) {
    var retry by remember(route) { mutableIntStateOf(0) }
    val permission by rememberUpdatedState(requestWritePermission)
    val mounted by produceState<OwnedMeetingMount>(OwnedMeetingMount.Loading, route, store, retry) {
        value = OwnedMeetingMount.Loading
        value = try {
            val launch = store.load(route.draftId)?.let(OwnedMeetingDraftCodec::describe)
            if (launch != null && launch.draftId == route.draftId && launch.generation == route.generation)
                OwnedMeetingMount.Ready(launch) else OwnedMeetingMount.Unavailable
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { OwnedMeetingMount.Unavailable }
    }
    val launch = (mounted as? OwnedMeetingMount.Ready)?.launch
    if (launch == null) {
        AlertDialog(onDismissRequest = onDismiss,
            text = { Text(stringResource(if (mounted == OwnedMeetingMount.Loading) R.string.events_drafts_loading else R.string.events_drafts_unreadable)) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.events_close)) } },
            dismissButton = { if (mounted == OwnedMeetingMount.Unavailable) TextButton(onClick = { retry++ }) { Text(stringResource(R.string.events_retry)) } })
        return
    }
    val controller = remember(launch.draftId, launch.generation, meetings, store, retirement) {
        OwnedMeetingEditorController(launch, meetings, store, { permission() }, retirement, clock)
    }
    PhoneOwnedMeetingEditor(controller, onDismiss, onSaved, onCancelled, onDiscarded = onDismiss,
        use24Hour = use24Hour, presentationVisible = presentationVisible,
        guestSuggestions = guestSuggestions, contactsPermissionGranted = contactsPermissionGranted,
        requestContactsPermission = requestContactsPermission, conflictAdvisory = conflictAdvisory)
}
