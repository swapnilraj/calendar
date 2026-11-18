package dev.swapnilraj.calendar.ui.events

import android.provider.CalendarContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.swapnilraj.calendar.core.*
import java.time.*
import org.json.JSONObject
import java.util.UUID
import kotlinx.coroutines.*

/** Raw fields remain recoverable even when a date, time or alert entry is incomplete. */
data class EventEditorFields(
    val calendarId: String, val title: String, val startDate: String, val endDate: String,
    val startTime: String, val endTime: String, val allDay: Boolean, val zone: String,
    val location: String, val description: String, val alerts: String,
    val startOffset: String = "", val endOffset: String = "",
)

enum class EditorProblem { INVALID_INPUT, AMBIGUOUS_TIME, NONEXISTENT_TIME, PERMISSION,
    DENIED, CONFLICT, UNCERTAIN, UNAVAILABLE, DRAFT_UNAVAILABLE, UNSUPPORTED, DRAFT_VERSION }

data class EventEditorState(
    val fields: EventEditorFields, val loading: Boolean = true, val saving: Boolean = false,
    val problem: EditorProblem? = null, val result: MutationResult? = null,
    val confirmDelete: Boolean = false, val recoveryRequired: Boolean = false, val reviewRequired: Boolean = false,
    val reviewLatest: EventDetails? = null, val draftReadFailed: Boolean = false,
)

/** Injected ports are the only write path. Dismissal and restoration never submit an event. */
class EventEditorController(
    private val initial: EventDraft,
    val existing: EditableEvent?,
    val draftId: String,
    private val mutations: CalendarMutations,
    private val drafts: DraftStore,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
) {
    var state by mutableStateOf(EventEditorState(fieldsFor(initial)))
        private set
    private var operationId = UUID.randomUUID().toString()
    val pendingOperationId: String get() = operationId
    private var operationKind = ""
    private var submitted = false
    private var completed = false
    private var operationPayload = ""
    private var uncertain = false
    private var persistence: Job? = null
    private val original = fieldsFor(initial)
    private val unsupported = existing?.let { source ->
        val ownEmails = listOfNotNull(source.details.organizer, source.details.calendar?.account?.name)
        source.details.attendees.any { guest -> guest.relationship != CalendarContract.Attendees.RELATIONSHIP_ORGANIZER &&
            ownEmails.none { guest.email.equals(it, ignoreCase = true) } } || !source.details.recurrenceRule.isNullOrBlank() ||
            source.details.occurrence.ref.recurrence != null
    } ?: false

    init { scope.launch { restore() } }

    private suspend fun restore() {
        try {
            val saved = drafts.load(draftId)
            if (saved != null) {
                if (saved.version != VERSION) {
                    state = state.copy(loading = false, problem = EditorProblem.DRAFT_VERSION)
                    return
                }
                val values = JSONObject(saved.payload)
                require(values.optString("target") == targetIdentity())
                operationId = values.getString("operationId")
                operationKind = values.optString("operationKind", "")
                submitted = values.getBoolean("submitted")
                operationPayload = values.optString("operationPayload", "")
                uncertain = submitted
                val sourceChanged = !submitted && existing != null && values.optString("sourceBaseline") != sourceBaseline()
                if (!submitted && !sourceChanged && values.optString("sourceToken") != existing?.token?.value.orEmpty()) {
                    // Only identical canonical source values, identity and rights permit token adoption.
                    operationId = UUID.randomUUID().toString(); operationPayload = ""
                }
                state = state.copy(fields = decode(values), recoveryRequired = submitted,
                    reviewRequired = sourceChanged, reviewLatest = if (sourceChanged) existing?.details else null,
                    problem = if (submitted) EditorProblem.UNCERTAIN else if (sourceChanged) EditorProblem.CONFLICT else null)
            }
            state = state.copy(loading = false, problem = if (unsupported) EditorProblem.UNSUPPORTED else state.problem)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { state = state.copy(loading = false, draftReadFailed = true, problem = EditorProblem.DRAFT_UNAVAILABLE) }
    }

    fun change(transform: (EventEditorFields) -> EventEditorFields) {
        if (state.loading || state.saving || state.draftReadFailed || state.reviewRequired || uncertain || completed || unsupported || state.problem == EditorProblem.DRAFT_VERSION) return
        val old = state.fields
        var next = transform(old)
        if (next.startDate != old.startDate || next.startTime != old.startTime || next.zone != old.zone || next.allDay != old.allDay)
            next = next.copy(startOffset = "")
        if (next.endDate != old.endDate || next.endTime != old.endTime || next.zone != old.zone || next.allDay != old.allDay)
            next = next.copy(endOffset = "")
        state = state.copy(fields = next, problem = if (state.reviewRequired) EditorProblem.CONFLICT else null, result = null)
        persistence?.cancel()
        persistence = scope.launch { delay(300); persistSafely() }
    }

    suspend fun prepareDismiss(): Boolean = if (state.loading || completed || unsupported || state.draftReadFailed || state.reviewRequired ||
        state.problem == EditorProblem.DRAFT_VERSION) true else flushDraft()

    suspend fun flushDraft(): Boolean {
        persistence?.cancelAndJoin()
        if (state.loading || state.draftReadFailed || completed || state.problem == EditorProblem.DRAFT_VERSION) return false
        return persistSafely()
    }

    private suspend fun persistSafely(): Boolean {
        if (state.reviewRequired) return false
        return try {
        val values = encode(state.fields).apply {
            put("operationId", operationId); put("operationKind", operationKind)
            put("operationPayload", operationPayload); put("target", targetIdentity())
            put("sourceToken", existing?.token?.value.orEmpty())
            put("sourceBaseline", sourceBaseline())
            put("submitted", submitted)
        }
        drafts.upsert(DraftRecord(draftId, VERSION, values.toString(), clock.instant()))
        true
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { state = state.copy(problem = EditorProblem.DRAFT_UNAVAILABLE); false }
    }

    fun save() {
        if (state.loading || state.saving || state.draftReadFailed || state.reviewRequired || uncertain || completed || unsupported || state.problem == EditorProblem.DRAFT_VERSION) return
        if (state.reviewRequired) return
        val normalized = try { normalizedDraft(state.fields,
            preserveTime = if (existing != null && !timeChanged(state.fields)) initial.time else null,
            preserveAlerts = if (existing != null && state.fields.alerts == original.alerts) initial.alertMinutes else null) }
        catch (invalid: InvalidInput) { state = state.copy(problem = invalid.problem); return }
        state = state.copy(saving = true, problem = null)
        scope.launch {
            persistence?.cancelAndJoin()
            // An operation ID is tied to the submitted payload; never reuse it for a changed command.
            val payload = encode(state.fields).toString()
            val kind = if (existing == null) "create" else "update"
            if (operationPayload.isNotEmpty() && operationPayload != payload || operationKind.isNotEmpty() && operationKind != kind)
                operationId = UUID.randomUUID().toString()
            operationPayload = payload
            operationKind = kind
            submitted = true
            if (!persistSafely()) { state = state.copy(saving = false); return@launch }
            val result = try {
                if (existing == null) mutations.create(operationId, normalized)
                else mutations.update(operationId, existing.details.occurrence.ref, existing.token, patch(normalized))
            } catch (cancelled: CancellationException) { uncertain = true; throw cancelled }
            catch (_: Exception) { MutationResult.Uncertain(operationId, existing?.details?.occurrence?.ref?.eventId) }
            finish(result)
        }
    }

    fun requestDelete() {
        if (existing != null && !state.loading && !state.saving && !uncertain && !completed && !unsupported && !state.reviewRequired) state = state.copy(confirmDelete = true)
    }
    fun cancelDelete() { state = state.copy(confirmDelete = false) }
    fun confirmDelete() {
        val event = existing ?: return
        if (!state.confirmDelete || state.saving || uncertain || completed || unsupported || state.reviewRequired) return
        state = state.copy(saving = true, confirmDelete = false, problem = null)
        scope.launch {
            persistence?.cancelAndJoin()
            if (operationKind != "delete") operationId = UUID.randomUUID().toString()
            operationKind = "delete"; submitted = true
            if (!persistSafely()) { state = state.copy(saving = false); return@launch }
            val result = try { mutations.delete(operationId, event.details.occurrence.ref, event.token) }
            catch (cancelled: CancellationException) { uncertain = true; throw cancelled }
            catch (_: Exception) { MutationResult.Uncertain(operationId, event.details.occurrence.ref.eventId) }
            finish(result)
        }
    }

    fun recover() {
        if (!uncertain || state.saving || completed) return
        state = state.copy(saving = true)
        scope.launch {
            val result = try { mutations.recover(operationId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { MutationResult.Uncertain(operationId, existing?.details?.occurrence?.ref?.eventId) }
            finish(result, recovery = true)
        }
    }

    /** Load latest for review; this never discards or rebases raw draft fields. */
    suspend fun reloadExisting(): EditReadResult? = existing?.let {
        try { mutations.loadForEdit(it.details.occurrence.ref) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { EditReadResult.Unavailable }
    }

    /** Only call after explicit user confirmation. Never discard an unresolved operation. */
    suspend fun discardAndReload(): EditReadResult? {
        if (uncertain || state.saving) return null
        val latest = reloadExisting() ?: return null
        if (latest is EditReadResult.Ready) {
            persistence?.cancelAndJoin()
            try { drafts.delete(draftId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { state = state.copy(problem = EditorProblem.DRAFT_UNAVAILABLE); return null }
        }
        return latest
    }

    private suspend fun finish(result: MutationResult, recovery: Boolean = false) {
        val success = result is MutationResult.LocalSaved || result is MutationResult.LocalDeleted
        uncertain = result is MutationResult.Uncertain || recovery && !success
        if (success) {
            completed = true
            try { drafts.delete(draftId) } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* A submitted record is safe: restore requires recovery, never resubmission. */ }
        } else {
            // Known failures can be edited/retried; a crash during that persistence stays conservative.
            submitted = uncertain
            persistSafely()
        }
        state = state.copy(saving = false, recoveryRequired = uncertain,
            reviewRequired = result is MutationResult.Conflict && existing != null,
            reviewLatest = (result as? MutationResult.Conflict)?.latest, result = result, problem = when (result) {
            is MutationResult.LocalSaved, is MutationResult.LocalDeleted -> null
            MutationResult.PermissionRequired -> EditorProblem.PERMISSION
            is MutationResult.Denied -> EditorProblem.DENIED
            is MutationResult.ValidationFailed -> EditorProblem.INVALID_INPUT
            is MutationResult.Conflict -> EditorProblem.CONFLICT
            is MutationResult.Uncertain -> EditorProblem.UNCERTAIN
            MutationResult.Unavailable -> EditorProblem.UNAVAILABLE
            is MutationResult.SafeToRetry -> EditorProblem.UNCERTAIN // Locked until exact-command retry UI is implemented.
        })
    }

    private fun patch(draft: EventDraft): EventPatch {
        val fields = state.fields
        fun <T> changed(field: EditableField, differs: Boolean, value: T): FieldChange<T> =
            if (field in existing!!.allowedFields && differs) FieldChange.Set(value) else FieldChange.Keep
        return EventPatch(
            title = changed(EditableField.TITLE, fields.title != original.title, draft.title),
            location = changed(EditableField.LOCATION, fields.location != original.location, draft.location),
            description = changed(EditableField.DESCRIPTION, fields.description != original.description, draft.description),
            time = changed(EditableField.TIME, timeChanged(fields), draft.time),
            eventZone = changed(EditableField.ZONE, fields.zone != original.zone || fields.allDay != original.allDay, draft.eventZone),
            alertMinutes = changed(EditableField.ALERTS, fields.alerts != original.alerts, draft.alertMinutes),
        )
    }

    private fun timeChanged(fields: EventEditorFields): Boolean =
        listOf(fields.startDate, fields.endDate, fields.startTime, fields.endTime, fields.allDay, fields.zone,
            fields.startOffset, fields.endOffset) != listOf(original.startDate, original.endDate,
            original.startTime, original.endTime, original.allDay, original.zone, original.startOffset, original.endOffset)

    /** Canonical source values, never localized labels or the edited raw form. */
    private fun sourceBaseline(): String = JSONObject().apply {
        put("calendarId", initial.calendarId.value)
        put("title", initial.title); put("location", initial.location); put("description", initial.description)
        put("zone", initial.eventZone?.id.orEmpty())
        put("alerts", org.json.JSONArray(initial.alertMinutes))
        when (val time = initial.time) {
            is EventTime.Timed -> { put("kind", "timed"); put("start", time.start.toString()); put("end", time.end.toString()) }
            is EventTime.AllDay -> { put("kind", "allDay"); put("start", time.dates.start.toString()); put("end", time.dates.endExclusive.toString()) }
        }
        existing?.let { source ->
            put("sourceTitle", source.details.occurrence.title)
            put("sourceLocation", source.details.location); put("sourceDescription", source.details.description)
            put("sourceZone", source.details.eventZone?.id.orEmpty())
            when (val sourceTime = source.details.occurrence.time) {
                is EventTime.Timed -> {
                    put("sourceKind", "timed"); put("sourceStart", sourceTime.start.toString()); put("sourceEnd", sourceTime.end.toString())
                }
                is EventTime.AllDay -> {
                    put("sourceKind", "allDay"); put("sourceStart", sourceTime.dates.start.toString()); put("sourceEnd", sourceTime.dates.endExclusive.toString())
                }
            }
            put("sourceReminders", org.json.JSONArray(source.details.reminders.map { "${it.method}:${it.minutesBefore}" }.sorted()))
            val ref = source.details.occurrence.ref
            put("refCalendar", ref.calendarId.value); put("refEvent", ref.eventId)
            put("refStart", ref.instanceStart.toString()); put("refEnd", ref.instanceEnd.toString())
            put("refMaster", ref.recurrence?.masterEventId?.toString().orEmpty())
            put("refOriginal", ref.recurrence?.originalStart?.toString().orEmpty())
            put("allowed", org.json.JSONArray(source.allowedFields.map { it.name }.sorted()))
        }
    }.toString()

    private fun targetIdentity(): String = existing?.details?.occurrence?.ref?.let {
        "edit:${it.calendarId.value}:${it.eventId}:${it.recurrence}"
    } ?: "create"

    companion object {
        const val VERSION = 1
        fun fieldsFor(draft: EventDraft): EventEditorFields {
            val zone = draft.eventZone ?: ZoneId.systemDefault()
            val time = draft.time
            val start = if (time is EventTime.Timed) time.start.atZone(zone) else null
            val end = if (time is EventTime.Timed) time.end.atZone(zone) else null
            val allDay = time as? EventTime.AllDay
            return EventEditorFields(draft.calendarId.value.toString(), draft.title,
                allDay?.dates?.start?.toString() ?: start!!.toLocalDate().toString(),
                allDay?.dates?.endExclusive?.minusDays(1)?.toString() ?: end!!.toLocalDate().toString(),
                start?.toLocalTime()?.toString() ?: "09:00", end?.toLocalTime()?.toString() ?: "10:00", allDay != null,
                zone.id, draft.location, draft.description, draft.alertMinutes.joinToString(","),
                start?.offset?.id.orEmpty(), end?.offset?.id.orEmpty())
        }
        fun normalizedDraft(fields: EventEditorFields, preserveTime: EventTime? = null, preserveAlerts: List<Int>? = null): EventDraft = try {
            val startDate = LocalDate.parse(fields.startDate); val endDate = LocalDate.parse(fields.endDate)
            val zone = ZoneId.of(fields.zone)
            val time = preserveTime ?: if (fields.allDay) {
                if (endDate < startDate) throw InvalidInput(EditorProblem.INVALID_INPUT)
                EventTime.AllDay(CalendarDateRange(startDate, endDate.plusDays(1)))
            } else {
                fun instant(date: LocalDate, rawTime: String, choice: String): Instant {
                    val local = date.atTime(LocalTime.parse(rawTime))
                    val offsets = zone.rules.getValidOffsets(local)
                    if (offsets.isEmpty()) throw InvalidInput(EditorProblem.NONEXISTENT_TIME)
                    val offset = if (offsets.size == 1) offsets.single() else offsets.firstOrNull { it.id == choice }
                        ?: throw InvalidInput(EditorProblem.AMBIGUOUS_TIME)
                    return local.toInstant(offset)
                }
                val start = instant(startDate, fields.startTime, fields.startOffset)
                val end = instant(endDate, fields.endTime, fields.endOffset)
                if (end <= start) throw InvalidInput(EditorProblem.INVALID_INPUT)
                EventTime.Timed(start, end)
            }
            val alerts = preserveAlerts ?: if (fields.alerts.isBlank()) emptyList() else fields.alerts.split(',').map { it.trim().toInt().also { value ->
                if (value < 0) throw InvalidInput(EditorProblem.INVALID_INPUT)
            } }.distinct()
            EventDraft(CalendarId(fields.calendarId.toLong()), time, if (fields.allDay) ZoneOffset.UTC else zone,
                fields.title, fields.location, fields.description, alerts)
        } catch (invalid: InvalidInput) { throw invalid }
        catch (_: Exception) { throw InvalidInput(EditorProblem.INVALID_INPUT) }

        private fun encode(f: EventEditorFields) = JSONObject().apply {
            listOf("calendarId" to f.calendarId, "title" to f.title, "startDate" to f.startDate, "endDate" to f.endDate,
                "startTime" to f.startTime, "endTime" to f.endTime, "allDay" to f.allDay.toString(), "zone" to f.zone,
                "location" to f.location, "description" to f.description, "alerts" to f.alerts,
                "startOffset" to f.startOffset, "endOffset" to f.endOffset).forEach { (key, value) -> put(key, value) }
        }
        private fun decode(p: JSONObject) = EventEditorFields(
            p.getString("calendarId"), p.optString("title", ""),
            p.optString("startDate", ""), p.optString("endDate", ""), p.optString("startTime", ""),
            p.optString("endTime", ""), p.optString("allDay", "false").toBooleanStrict(), p.optString("zone", ""),
            p.optString("location", ""), p.optString("description", ""), p.optString("alerts", ""),
            p.optString("startOffset", ""), p.optString("endOffset", ""))
    }
}

class InvalidInput(val problem: EditorProblem) : IllegalArgumentException()
