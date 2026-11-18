package dev.swapnilraj.calendar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.swapnilraj.calendar.R
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.EventEditorController
import dev.swapnilraj.calendar.ui.events.EventEditorSheet
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
) {
    fun encode(): String = JSONObject().put("draftId", draftId).put("date", date.toString())
        .put("zone", zone.id).put("calendar", calendarId?.value).put("delete", delete).apply {
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
        fun decode(raw: String): EventWriteRequest = JSONObject(raw).let { value ->
            val ref = value.optJSONObject("event")?.let { event -> EventRef(CalendarId(event.getLong("calendar")), event.getLong("id"),
                Instant.parse(event.getString("start")), Instant.parse(event.getString("end")),
                if (event.has("master")) RecurrenceRef(event.getLong("master"), Instant.parse(event.getString("original"))) else null) }
            EventWriteRequest(value.getString("draftId"), LocalDate.parse(value.getString("date")), ZoneId.of(value.getString("zone")),
                if (value.has("calendar") && !value.isNull("calendar")) CalendarId(value.getLong("calendar")) else null, ref, value.getBoolean("delete"))
        }
    }
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
) {
    var retry by remember(request) { mutableIntStateOf(0) }
    var replacement by remember(request) { mutableStateOf<EditableEvent?>(null) }
    var replacementVersion by remember(request) { mutableIntStateOf(0) }
    val read by produceState<EditReadResult?>(null, request, mutations, retry) {
        value = null
        value = if (request.ref == null) null else mutations.loadForEdit(request.ref)
    }
    val existing = replacement ?: (read as? EditReadResult.Ready)?.event
    if (request.ref != null && existing == null) {
        ModalBottomSheet(onDismissRequest = onDismiss) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(when (read) {
                    null -> R.string.events_loading
                    EditReadResult.PermissionRequired -> R.string.events_permission_required
                    is EditReadResult.Denied -> R.string.navigation_edit_unavailable
                    else -> R.string.events_unavailable
                }))
                if (read == null) CircularProgressIndicator()
                else if (read == EditReadResult.Unavailable) TextButton(onClick = { retry++ }) { Text(stringResource(R.string.events_retry)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.calendar_close_events)) }
            }
        }
        return
    }
    val eligibleDefault = calendars.firstOrNull { it.id == request.calendarId && it.capabilities.createEvents == AccessDecision.ALLOWED }?.id
    val initial = remember(request, existing) {
        existing?.details?.let { details -> EventDraft(details.occurrence.ref.calendarId, details.occurrence.time, details.eventZone,
            details.occurrence.title, details.location, details.description,
            details.reminders.filter { it.method == android.provider.CalendarContract.Reminders.METHOD_ALERT }.map { it.minutesBefore }) }
            ?: EventDraft(eligibleDefault ?: CalendarId(0), EventTime.AllDay(CalendarDateRange.day(request.date)), null, "")
    }
    val scope = rememberCoroutineScope()
    val controller = remember(request, existing, replacementVersion, mutations, drafts) {
        EventEditorController(initial, existing, request.draftId, mutations, drafts, scope,
            initialCalendarSelected = existing != null || eligibleDefault != null)
    }
    LaunchedEffect(controller.state.loading) {
        val state = controller.state
        if (!state.loading && state.problem == null && !state.recoveryRequired && !state.reviewRequired && !state.draftReadFailed && request.delete)
            controller.requestDelete()
    }
    EventEditorSheet(controller, calendars, onDismiss, onSaved, onDeleted,
        onReviewExisting = { result ->
            if (result is EditReadResult.Ready) { replacement = result.event; replacementVersion++ }
        }, use24Hour = use24Hour)
}
