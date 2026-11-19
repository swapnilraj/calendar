package dev.swapnilraj.calendar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.swapnilraj.calendar.R
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.android.navigation.isCalendarSeedNativeHandoffVerified
import dev.swapnilraj.calendar.android.navigation.CalendarEditReviewResult
import dev.swapnilraj.calendar.ui.events.*
import dev.swapnilraj.calendar.ui.events.meetings.create.MeetingCreationLaunch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
    val generation: String? = null,
) {
    fun matches(launch: EditorDraftLaunch): Boolean = draftId == launch.draftId && ref == launch.ref && generation == launch.generation

    fun encode(): String = JSONObject().put("draftId", draftId).put("date", date.toString())
        .put("zone", zone.id).put("calendar", calendarId?.value).put("delete", delete).put("recoveryOnly", recoveryOnly).apply {
            generation?.let { put("generation", it) }
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
                launch.ref, delete, launch.needsRecovery, launch.generation)
        }
        fun decode(raw: String): EventWriteRequest {
            require(raw.length <= 16_384)
            return JSONObject(raw).let { value ->
                require(value.keys().asSequence().all { it in setOf("draftId", "date", "zone", "calendar", "event", "delete", "recoveryOnly", "generation") })
                val ref = value.optJSONObject("event")?.let { event -> EventRef(CalendarId(event.getLong("calendar")), event.getLong("id"),
                    Instant.parse(event.getString("start")), Instant.parse(event.getString("end")),
                    if (event.has("master")) RecurrenceRef(event.getLong("master"), Instant.parse(event.getString("original"))) else null) }
                EventWriteRequest(value.getString("draftId"), LocalDate.parse(value.getString("date")), ZoneId.of(value.getString("zone")),
                    if (value.has("calendar") && !value.isNull("calendar")) CalendarId(value.getLong("calendar")) else null, ref, value.getBoolean("delete"), value.optBoolean("recoveryOnly", false),
                    if (value.has("generation")) (value.get("generation") as? String)?.also { require(UUID.fromString(it).toString() == it) } ?: error("Invalid generation") else null)
            }
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
    data class Ready(val launch: EditorDraftLaunch, val sourceIdentity: CalendarIdentity?) : StoredEditor
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
    recurringCreationSupported: Boolean = false,
    retirement: OperationRetirement? = null,
    requestWritePermission: (suspend () -> Boolean)? = null,
    onReplacedLaunch: ((EditorDraftLaunch) -> Unit)? = null,
    onPromotedMeeting: ((MeetingCreationLaunch) -> Unit)? = null,
    presentationVisible: Boolean = true,
    onControllerPresence: (EventEditorController, Boolean) -> Unit = { _, _ -> },
    incomingEditReview: CalendarEditReviewResult.Editor? = null,
) {
    var retry by remember(request) { mutableIntStateOf(0) }
    val latestPermission by rememberUpdatedState(requestWritePermission)
    val latestReplacement by rememberUpdatedState(onReplacedLaunch)
    val stored by produceState<StoredEditor>(StoredEditor.Loading, request, drafts, retry) {
        value = StoredEditor.Loading
        value = try {
            val document = withContext(Dispatchers.IO) {
                drafts.load(request.draftId)?.let { record ->
                    if (record.id.startsWith("incoming-edit:")) {
                        // Generic draft opens cannot bypass the private host's source pin.
                        require(incomingEditReview != null && request.matches(incomingEditReview.launch))
                    }
                    if (record.id.startsWith("incoming:")) require(isCalendarSeedNativeHandoffVerified(record,
                        drafts.load("incoming-review:${record.id.removePrefix("incoming:")}")))
                    OrdinaryDraftDocument.decode(record)
                }
            }
            val launch = document?.launch
            if (launch != null && request.matches(launch))
                StoredEditor.Ready(launch, requireNotNull(document).sourceIdentity)
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
    val sourceIdentity = (stored as StoredEditor.Ready).sourceIdentity
    val needsSource = request.ref != null && !recoveryOnly && sourceIdentity != null
    val read by produceState<EditReadResult?>(null, request, mutations, retry, needsSource) {
        value = null
        value = if (!needsSource || request.ref == null) null else try {
            mutations.loadForEdit(request.ref).let { result ->
                if (result is EditReadResult.Ready && (result.event.details.occurrence.ref != request.ref ||
                    result.event.details.calendar?.identity != sourceIdentity)) EditReadResult.Unavailable else result
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { EditReadResult.Unavailable }
    }
    val existing = (read as? EditReadResult.Ready)?.event
    if (needsSource && read == null) {
        ModalBottomSheet(onDismissRequest = onDismiss) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.events_loading))
                CircularProgressIndicator()
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.calendar_close_events)) }
            }
        }
        return
    }
    val initial = remember(request, existing, launch) {
        existing?.details?.editorSeed() ?: launch.seed(request.date, request.zone)
    }
    val scope = rememberCoroutineScope()
    val controller = remember(request, existing, mutations, drafts, recoveryOnly, recurringCreationSupported, retirement) {
        EventEditorController(initial, existing, request.draftId, mutations, drafts, scope, clock = clock,
            initialCalendarSelected = launch.calendarSelected, recoveryOnly = recoveryOnly,
            recoveryRef = launch.ref, requireStoredDraft = true,
            recurringCreationSupported = recurringCreationSupported, expectedGeneration = request.generation,
            retirement = retirement, requestWritePermission = if (requestWritePermission != null) ({ latestPermission?.invoke() == true }) else null,
            allowMeetingPromotion = onPromotedMeeting != null)
    }
    val controllerPresence by rememberUpdatedState(onControllerPresence)
    DisposableEffect(controller) {
        controllerPresence(controller, true)
        onDispose { controllerPresence(controller, false) }
    }
    LaunchedEffect(controller.state.loading) {
        val state = controller.state
        if (!state.loading && state.problem == null && !state.recoveryRequired && !state.reviewRequired && !state.draftReadFailed && request.delete)
            controller.requestDelete()
    }
    PhoneEventEditor(controller, calendars, onDismiss, onSaved, onDeleted,
        use24Hour = use24Hour,
        onReplacedLaunch = if (onReplacedLaunch != null) ({ value -> latestReplacement?.invoke(value) }) else null,
        onDiscarded = onDismiss, contentVisible = presentationVisible, onPromotedMeeting = onPromotedMeeting)
}

/** Ownership observation only: a host must not cancel an accepted write or its cleanup. */
internal fun EventEditorController.ownsPendingNativeOutcome(): Boolean = state.saving || state.localOutcome != null
