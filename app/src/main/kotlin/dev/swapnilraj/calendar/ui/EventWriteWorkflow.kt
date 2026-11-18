package dev.swapnilraj.calendar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.swapnilraj.calendar.R
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.*
import java.util.UUID
import org.json.JSONObject

/** Saved navigation data is private app state. It never authorizes a provider mutation. */
internal data class EventWriteRequest(
    val draftId: String,
    val date: LocalDate,
    val zone: ZoneId,
    val calendarId: CalendarId?,
    val ref: EventRef? = null,
    val delete: Boolean = false,
    val recoveryOnly: Boolean = false,
) {
    fun encode(): String = JSONObject().put("draftId", draftId).put("date", date.toString())
        .put("zone", zone.id).put("calendar", calendarId?.value).put("delete", delete).put("recoveryOnly", recoveryOnly).apply {
            ref?.let { value -> put("event", JSONObject().put("calendar", value.calendarId.value)
                .put("id", value.eventId).put("start", value.instanceStart.toString()).put("end", value.instanceEnd.toString()).apply {
                    value.recurrence?.let { put("master", it.masterEventId); put("original", it.originalStart.toString()) }
                }) }
        }.toString()

    companion object {
        fun create(date: LocalDate, zone: ZoneId, calendarId: CalendarId?) =
            EventWriteRequest(UUID.randomUUID().toString(), date, zone, calendarId)
        fun edit(ref: EventRef, zone: ZoneId, delete: Boolean) = EventWriteRequest(
            "edit:${ref.calendarId.value}:${ref.eventId}:${ref.instanceStart}", ref.instanceStart.atZone(zone).toLocalDate(), zone, ref.calendarId, ref, delete)
        fun fromLaunch(launch: EditorDraftLaunch, fallbackDate: LocalDate, zone: ZoneId, delete: Boolean = false): EventWriteRequest {
            val date = runCatching { LocalDate.parse(launch.rawDate) }.getOrDefault(fallbackDate)
            return EventWriteRequest(launch.draftId, date, zone, launch.seed(date, zone).calendarId,
                launch.ref, delete, launch.needsRecovery)
        }
        fun decode(raw: String): EventWriteRequest = JSONObject(raw).let { value ->
            val ref = value.optJSONObject("event")?.let { event -> EventRef(CalendarId(event.getLong("calendar")), event.getLong("id"),
                Instant.parse(event.getString("start")), Instant.parse(event.getString("end")),
                if (event.has("master")) RecurrenceRef(event.getLong("master"), Instant.parse(event.getString("original"))) else null) }
            EventWriteRequest(value.getString("draftId"), LocalDate.parse(value.getString("date")), ZoneId.of(value.getString("zone")),
                if (value.has("calendar") && !value.isNull("calendar")) CalendarId(value.getLong("calendar")) else null, ref, value.getBoolean("delete"), value.optBoolean("recoveryOnly", false))
        }
    }
}

internal fun EventDetails.editorSeed() = EventDraft(occurrence.ref.calendarId, occurrence.time, eventZone,
    occurrence.title, location, description,
    reminders.filter { it.method == android.provider.CalendarContract.Reminders.METHOD_ALERT }.map { it.minutesBefore },
    availability = availability, privacy = privacy)

private sealed interface StoredEditor {
    data object Loading : StoredEditor
    data object Unavailable : StoredEditor
    data class Ready(val launch: EditorDraftLaunch) : StoredEditor
}

/** Permission is requested only after Save/Delete, with no automatic submission after a grant. */
internal class PermissionAwareMutations(
    private val delegate: CalendarMutations,
    private val hasWritePermission: () -> Boolean,
    private val requestWritePermission: () -> Unit,
) : CalendarMutations by delegate {
    private fun allowed(): Boolean = hasWritePermission().also { if (!it) requestWritePermission() }
    override suspend fun create(operationId: String, draft: EventDraft): MutationResult =
        if (allowed()) delegate.create(operationId, draft) else MutationResult.PermissionRequired
    override suspend fun update(operationId: String, ref: EventRef, token: EditToken, patch: EventPatch): MutationResult =
        if (allowed()) delegate.update(operationId, ref, token, patch) else MutationResult.PermissionRequired
    override suspend fun delete(operationId: String, ref: EventRef, token: EditToken): MutationResult =
        if (allowed()) delegate.delete(operationId, ref, token) else MutationResult.PermissionRequired
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EventWriteWorkflow(
    request: EventWriteRequest,
    mutations: CalendarMutations,
    drafts: DraftStore,
    calendars: List<CalendarInfo>,
    use24Hour: Boolean,
    onDismiss: () -> Unit,
    onSaved: (EventRef) -> Unit,
    onDeleted: (EventRef) -> Unit,
    clock: Clock = Clock.systemUTC(),
) {
    var retry by remember(request) { mutableIntStateOf(0) }
    var replacement by remember(request) { mutableStateOf<EditableEvent?>(null) }
    var replacementVersion by remember(request) { mutableIntStateOf(0) }
    val stored by produceState<StoredEditor>(StoredEditor.Loading, request, drafts, retry) {
        value = StoredEditor.Loading
        value = try {
            val launch = withContext(Dispatchers.IO) { drafts.load(request.draftId)?.let(DraftBrowserCodec::read)?.launch }
            if (launch != null && launch.ref == request.ref && launch.draftId == request.draftId) StoredEditor.Ready(launch)
            else StoredEditor.Unavailable
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { StoredEditor.Unavailable }
    }
    val launch = (stored as? StoredEditor.Ready)?.launch
    if (launch == null) {
        ModalBottomSheet(onDismissRequest = onDismiss) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(if (stored == StoredEditor.Loading) R.string.events_drafts_loading else R.string.events_drafts_unreadable))
                if (stored == StoredEditor.Loading) CircularProgressIndicator()
                else TextButton(onClick = { retry++ }) { Text(stringResource(R.string.events_retry)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.events_close)) }
            }
        }
        return
    }
    val recoveryOnly = request.recoveryOnly || launch.needsRecovery
    val read by produceState<EditReadResult?>(null, request, mutations, retry, recoveryOnly) {
        value = null
        value = if (request.ref == null || recoveryOnly) null else mutations.loadForEdit(request.ref).let {
            if (it is EditReadResult.Ready && it.event.details.occurrence.ref != request.ref) EditReadResult.Unavailable else it
        }
    }
    val existing = replacement ?: (read as? EditReadResult.Ready)?.event
    if (request.ref != null && !recoveryOnly && existing == null) {
        ModalBottomSheet(onDismissRequest = onDismiss) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(when (read) {
                    null -> R.string.events_loading
                    EditReadResult.PermissionRequired -> R.string.events_permission_required
                    is EditReadResult.Denied -> R.string.navigation_edit_unavailable
                    EditReadResult.LimitExceeded -> R.string.navigation_edit_read_limit
                    else -> R.string.events_unavailable
                }))
                if (read == null) CircularProgressIndicator()
                else if (read == EditReadResult.Unavailable) TextButton(onClick = { retry++ }) { Text(stringResource(R.string.events_retry)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.calendar_close_events)) }
            }
        }
        return
    }
    val initial = remember(request, existing, launch) {
        existing?.details?.editorSeed() ?: launch.seed(request.date, request.zone)
    }
    val scope = rememberCoroutineScope()
    val controller = remember(request, existing, replacementVersion, mutations, drafts, recoveryOnly) {
        EventEditorController(initial, existing, request.draftId, mutations, drafts, scope, clock = clock,
            initialCalendarSelected = launch.calendarSelected, recoveryOnly = recoveryOnly,
            recoveryRef = if (recoveryOnly) launch.ref else null, requireStoredDraft = true)
    }
    var preparingReview by remember(controller) { mutableStateOf(false) }
    var preparationFailed by remember(controller) { mutableStateOf(false) }
    LaunchedEffect(controller.state.loading) {
        val state = controller.state
        if (!state.loading && state.problem == null && !state.recoveryRequired && !state.reviewRequired && !state.draftReadFailed && request.delete)
            controller.requestDelete()
    }
    EventEditorSheet(controller, calendars, onDismiss, onSaved, onDeleted,
        onReviewExisting = { result ->
            if (result is EditReadResult.Ready && !preparingReview) scope.launch {
                preparingReview = true
                val prepared = prepareEditDraft(result.event.details.editorSeed(), result.event, request.draftId, drafts)
                if (prepared is DraftPreparationResult.Ready) { replacement = result.event; replacementVersion++ }
                else preparationFailed = true
                preparingReview = false
            }
        }, use24Hour = use24Hour)
    if (preparationFailed) AlertDialog(onDismissRequest = { preparationFailed = false },
        text = { Text(stringResource(R.string.navigation_draft_unavailable)) },
        confirmButton = { TextButton(onClick = { preparationFailed = false }) { Text(stringResource(R.string.events_close)) } })
}
