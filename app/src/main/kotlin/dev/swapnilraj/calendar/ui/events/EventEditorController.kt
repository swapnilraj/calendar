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
    val availability: String = "", val privacy: String = "",
    val defaultVisibilityCalendar: String = "",
    val targetIdentity: CalendarIdentity? = null, val defaultVisibilityIdentity: CalendarIdentity? = null,
    val recurrence: RecurrenceEditorFields = RecurrenceEditorFields(),
)

enum class EditorProblem { INVALID_INPUT, AMBIGUOUS_TIME, NONEXISTENT_TIME, PERMISSION,
    DENIED, CONFLICT, UNCERTAIN, UNAVAILABLE, DRAFT_UNAVAILABLE, DRAFT_RETIRED, UNSUPPORTED, DRAFT_VERSION, SAFE_TO_RETRY, RETRY_UNAVAILABLE, VISIBILITY_REQUIRED, CALENDAR_REQUIRED, RECURRENCE_UNSUPPORTED, RECURRENCE_INVALID, RECURRENCE_REVIEW }

data class EventEditorState(
    val fields: EventEditorFields, val loading: Boolean = true, val saving: Boolean = false,
    val problem: EditorProblem? = null, val result: MutationResult? = null,
    val confirmDelete: Boolean = false, val recoveryRequired: Boolean = false, val reviewRequired: Boolean = false,
    val reviewLatest: EventDetails? = null, val draftReadFailed: Boolean = false, val retryReady: Boolean = false,
    val notices: List<EditorNotice> = emptyList(),
    val recurrenceReview: RecurrenceCreationPlan? = null,
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
    initialCalendarSelected: Boolean = true,
    val recoveryOnly: Boolean = false,
    private val recoveryRef: EventRef? = null,
    private val requireStoredDraft: Boolean = false,
    val recurringCreationSupported: Boolean = false,
) {
    private val original = fieldsFor(initial).let {
        if (existing == null && !initialCalendarSelected) it.copy(calendarId = "", targetIdentity = null) else it
    }
    var state by mutableStateOf(EventEditorState(original))
        private set
    val visibilityChoiceRequired: Boolean get() = existing == null && state.notices.any { it == EditorNotice.VISIBILITY_REVIEW_REQUIRED || it == EditorNotice.CALENDAR_VISIBILITY_REVIEW_REQUIRED } &&
        state.fields.privacy.toIntOrNull() !in 0..3
    val calendarSelectionRequired: Boolean get() = existing == null && !validTarget(state.fields.targetIdentity, state.fields.calendarId)
    private var recurrenceReviewPayload = ""
    private var operationId = UUID.randomUUID().toString()
    val pendingOperationId: String get() = operationId
    private var operationKind = ""
    val pendingOperationKind: String get() = operationKind
    private var submitted = false
    private var completed = false
    private var operationPayload = ""
    private var uncertain = false
    private var retryLocked = false
    private var frozenSubmission: FrozenEditorSubmission? = null
    private var retainedSubmission: Any? = null
    private var recoveryRecord: DraftRecord? = null
    private var legacyDefaultVisibilityUnsafe = false
    private var legacyTargetUnsafe = false
    private var persistence: Job? = null
    private var reviewSourceIdentity: CalendarIdentity? = null
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
            check(saved != null || !requireStoredDraft && !recoveryOnly) { "Draft no longer available" }
            if (saved != null) {
                if (recoveryOnly) {
                    val launch = DraftBrowserCodec.read(saved).launch ?: throw IllegalArgumentException()
                    require(launch.needsRecovery && launch.ref == recoveryRef)
                    recoveryRecord = saved
                }
                if (saved.version !in setOf(LEGACY_VERSION, VERSION) || saved.version == LEGACY_VERSION && JSONObject(saved.payload).has("recurrence")) {
                    state = state.copy(loading = false, problem = EditorProblem.DRAFT_VERSION)
                    return
                }
                val values = JSONObject(saved.payload)
                reviewSourceIdentity = sourceIdentity(values)
                require(saved.version != VERSION || values.has("recurrence"))
                require(values.optString("target") == targetIdentity())
                operationId = values.getString("operationId")
                operationKind = values.optString("operationKind", "")
                submitted = values.getBoolean("submitted")
                operationPayload = values.optString("operationPayload", "")
                retainedSubmission = values.opt("submission")?.takeUnless { it === JSONObject.NULL }
                frozenSubmission = try {
                    values.optJSONObject("submission")?.let(FrozenEditorSubmission::decode)?.takeIf {
                        it.operationId == operationId && it.kind == operationKind
                    }
                } catch (_: Exception) { null }
                submitted = submitted || retainedSubmission != null && frozenSubmission == null
                uncertain = submitted
                val sourceChanged = !submitted && existing != null &&
                    (!sourceNamespaceMatches(values) || values.optString("sourceBaseline") != sourceBaseline())
                if (!submitted && !sourceChanged && values.optString("sourceToken") != existing?.token?.value.orEmpty()) {
                    // Only identical canonical source values, identity and rights permit token adoption.
                    operationId = UUID.randomUUID().toString(); operationPayload = ""
                    frozenSubmission = null; retainedSubmission = null
                }
                var restoredFields = decode(values)
                var restoredNotices = EditorNoticeCodec.read(values)
                val frozenTarget = (frozenSubmission as? FrozenEditorSubmission.Create)?.draft?.targetIdentity
                val unboundTarget = values.optString("target") == "create" && (!validTarget(restoredFields.targetIdentity, restoredFields.calendarId) ||
                    submitted && (!validTarget(frozenTarget, restoredFields.calendarId) || frozenTarget != restoredFields.targetIdentity))
                if (unboundTarget && submitted) {
                    legacyTargetUnsafe = true; recoveryRecord = saved
                    restoredNotices = (restoredNotices + EditorNotice.DESTINATION_RECOVERY_ONLY).distinct()
                } else if (unboundTarget) {
                    restoredFields = restoredFields.copy(targetIdentity = null)
                    restoredNotices = (restoredNotices + EditorNotice.CALENDAR_RESELECTION_REQUIRED).distinct()
                }
                val unprovenDefault = EditorNotice.DUPLICATE_AS_ONE_OFF in restoredNotices && restoredFields.privacy.toIntOrNull() == 0 &&
                    (restoredFields.targetIdentity == null || restoredFields.defaultVisibilityIdentity != restoredFields.targetIdentity ||
                        restoredFields.targetIdentity?.calendarId?.value?.toString() != restoredFields.calendarId)
                if (unprovenDefault && submitted) {
                    legacyDefaultVisibilityUnsafe = true
                    recoveryRecord = saved
                    restoredNotices = (restoredNotices + EditorNotice.VISIBILITY_RECOVERY_ONLY).distinct()
                } else if (unprovenDefault) {
                    restoredFields = restoredFields.copy(privacy = "", defaultVisibilityCalendar = "", defaultVisibilityIdentity = null)
                    restoredNotices = (restoredNotices + EditorNotice.CALENDAR_VISIBILITY_REVIEW_REQUIRED).distinct()
                }
                state = state.copy(fields = restoredFields, notices = restoredNotices, recoveryRequired = submitted,
                    reviewRequired = sourceChanged, reviewLatest = if (sourceChanged && sourceNamespaceMatches(values)) existing?.details else null,
                    problem = if (submitted) EditorProblem.UNCERTAIN else if (sourceChanged) EditorProblem.CONFLICT else null)
            }
            state = state.copy(loading = false, problem = if (unsupported) EditorProblem.UNSUPPORTED else state.problem)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { state = state.copy(loading = false, draftReadFailed = true, problem = EditorProblem.DRAFT_UNAVAILABLE) }
    }

    private fun writesBlocked(): Boolean = recoveryOnly || state.loading || state.saving || state.draftReadFailed || state.reviewRequired ||
        uncertain || retryLocked || completed || unsupported || state.problem == EditorProblem.DRAFT_VERSION

    fun change(transform: (EventEditorFields) -> EventEditorFields) = changeFields(transform)

    /** Explicit calendar choice issues a new proof even when a reused provider ID is unchanged. */
    fun selectCalendar(calendar: CalendarInfo) {
        if (existing != null || calendar.capabilities.createEvents != AccessDecision.ALLOWED || !validTarget(calendar.identity, calendar.id.value.toString())) return
        changeFields({ it.copy(calendarId = calendar.id.value.toString()) }, calendar.identity)
    }

    /** Observe namespace drift, never adopt current metadata as an old draft's authority. */
    fun observeCalendars(calendars: List<CalendarInfo>) {
        if (existing != null || writesBlocked()) return
        val pinned = state.fields.targetIdentity ?: return
        if (calendars.any { it.identity == pinned && it.capabilities.createEvents == AccessDecision.ALLOWED }) return
        changeFields({ it }, invalidateTarget = true, persist = false)
        state = state.copy(problem = EditorProblem.CALENDAR_REQUIRED)
    }

    private fun changeFields(transform: (EventEditorFields) -> EventEditorFields,
        selected: CalendarIdentity? = null, invalidateTarget: Boolean = false, persist: Boolean = true) {
        if (writesBlocked()) return
        val old = state.fields
        var next = transform(old).copy(targetIdentity = old.targetIdentity, defaultVisibilityIdentity = old.defaultVisibilityIdentity)
        val target = when {
            existing != null -> old.targetIdentity
            selected != null -> selected
            invalidateTarget || next.calendarId != old.calendarId -> null
            else -> old.targetIdentity
        }
        next = next.copy(targetIdentity = target)
        var notices = if (selected != null) state.notices.filterNot { it == EditorNotice.CALENDAR_RESELECTION_REQUIRED } else state.notices
        if (EditorNotice.DUPLICATE_AS_ONE_OFF in notices) {
            if ((next.calendarId != old.calendarId || next.targetIdentity != old.targetIdentity) && next.privacy.toIntOrNull() !in 1..3) {
                next = next.copy(privacy = "", defaultVisibilityCalendar = "", defaultVisibilityIdentity = null)
                notices = (notices + EditorNotice.CALENDAR_VISIBILITY_REVIEW_REQUIRED).distinct()
            } else {
                val approved = if (next.privacy.toIntOrNull() != 0) null
                    else if (next.privacy != old.privacy && next.targetIdentity?.calendarId?.value?.toString() == next.calendarId) next.targetIdentity
                    else old.defaultVisibilityIdentity
                next = next.copy(defaultVisibilityCalendar = approved?.calendarId?.value?.toString().orEmpty(), defaultVisibilityIdentity = approved)
            }
        }
        if (existing == null && (next.targetIdentity == null || next.targetIdentity?.calendarId?.value?.toString() != next.calendarId))
            notices = (notices + EditorNotice.CALENDAR_RESELECTION_REQUIRED).distinct()
        if (next.startDate != old.startDate || next.startTime != old.startTime || next.zone != old.zone || next.allDay != old.allDay)
            next = next.copy(startOffset = "")
        if (next.endDate != old.endDate || next.endTime != old.endTime || next.zone != old.zone || next.allDay != old.allDay)
            next = next.copy(endOffset = "")
        recurrenceReviewPayload = ""
        state = state.copy(fields = next, notices = notices, recurrenceReview = null, problem = if (state.reviewRequired) EditorProblem.CONFLICT else null, result = null)
        if (persist) {
            persistence?.cancel()
            persistence = scope.launch { delay(300); persistSafely() }
        }
    }

    suspend fun prepareDismiss(): Boolean = if (recoveryOnly || state.loading || completed || unsupported || state.draftReadFailed || state.reviewRequired ||
        state.problem == EditorProblem.DRAFT_VERSION) true else flushDraft()

    suspend fun flushDraft(): Boolean {
        persistence?.cancelAndJoin()
        if (state.loading || state.draftReadFailed || completed || state.problem == EditorProblem.DRAFT_VERSION) return false
        return persistSafely()
    }

    private suspend fun persistSafely(): Boolean {
        if (recoveryOnly || legacyDefaultVisibilityUnsafe || legacyTargetUnsafe) {
            // No source baseline may be rebuilt from a fallback seed or missing live edit source.
            return try {
                val expected = recoveryRecord ?: return false
                val current = drafts.load(draftId)
                val unchanged = current != null && current.version == expected.version && current.payload == expected.payload
                if (!unchanged) state = state.copy(problem = EditorProblem.DRAFT_UNAVAILABLE)
                unchanged
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { state = state.copy(problem = EditorProblem.DRAFT_UNAVAILABLE); false }
        }
        if (state.reviewRequired || state.draftReadFailed || state.problem == EditorProblem.DRAFT_VERSION) return false
        return try {
        val values = encode(state.fields).apply {
            EditorNoticeCodec.write(this, state.notices)
            put("operationId", operationId); put("operationKind", operationKind)
            put("operationPayload", operationPayload); put("target", targetIdentity())
            put("sourceToken", existing?.token?.value.orEmpty())
            put("sourceBaseline", sourceBaseline())
            put("submitted", submitted)
            (frozenSubmission?.encode() ?: retainedSubmission)?.let { put("submission", it) }
        }
        val record = DraftRecord(draftId, VERSION, values.toString(), clock.instant())
        drafts.upsert(record)
        if (existing != null && reviewSourceIdentity == null) {
            val stored = drafts.load(draftId)
            if (stored != null && stored.id == record.id && stored.version == record.version && stored.payload == record.payload)
                reviewSourceIdentity = sourceIdentity(values)
        }
        true
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { state = state.copy(problem = EditorProblem.DRAFT_UNAVAILABLE); false }
    }

    fun save() = saveReviewed(false)

    fun dismissRecurrenceReview() { recurrenceReviewPayload = ""; state = state.copy(recurrenceReview = null) }
    fun confirmRecurringCreate() {
        if (state.recurrenceReview == null) return
        saveReviewed(true)
    }

    private fun saveReviewed(confirmed: Boolean) {
        if (writesBlocked()) return
        if (calendarSelectionRequired) { state = state.copy(problem = EditorProblem.CALENDAR_REQUIRED); return }
        if (visibilityChoiceRequired) { state = state.copy(problem = EditorProblem.VISIBILITY_REQUIRED); return }
        val normalized = try { normalizedDraft(state.fields,
            preserveTime = if (existing != null && !timeChanged(state.fields)) initial.time else null,
            preserveAlerts = if (existing != null && state.fields.alerts == original.alerts) initial.alertMinutes else null) }
        catch (invalid: InvalidInput) { state = state.copy(problem = invalid.problem); return }
        val authoredRecurrence = normalized.recurrence
        if (authoredRecurrence != null) {
            if (existing != null || !recurringCreationSupported) { state = state.copy(problem = EditorProblem.RECURRENCE_UNSUPPORTED); return }
            val start = when (val time = normalized.time) {
                is EventTime.AllDay -> RecurrenceStart.AllDay(time.dates.start)
                is EventTime.Timed -> {
                    val zone = normalized.eventZone ?: run { state = state.copy(problem = EditorProblem.RECURRENCE_INVALID); return }
                    val local = time.start.atZone(zone)
                    try { RecurrenceStart.Timed(local.toLocalDateTime(), zone, local.offset) }
                    catch (_: Exception) { state = state.copy(problem = EditorProblem.RECURRENCE_INVALID); return }
                }
            }
            val compilation = RecurrenceCreation.compile(start, normalized.time, authoredRecurrence, previewLimit = 5)
            if (compilation !is RecurrenceCreationResult.Ready) {
                state = state.copy(recurrenceReview = null, problem = if (compilation is RecurrenceCreationResult.NeedsReview)
                    EditorProblem.RECURRENCE_REVIEW else EditorProblem.RECURRENCE_INVALID)
                return
            }
            val payload = encode(state.fields).toString()
            if (!confirmed || recurrenceReviewPayload != payload) {
                recurrenceReviewPayload = payload
                state = state.copy(recurrenceReview = compilation.plan, problem = null)
                return
            }
        } else if (confirmed) return
        recurrenceReviewPayload = ""
        state = state.copy(saving = true, problem = null, recurrenceReview = null)
        scope.launch {
            persistence?.cancelAndJoin()
            // An operation ID is tied to the submitted payload; never reuse it for a changed command.
            val payload = encode(state.fields).toString()
            val kind = if (existing == null) "create" else "update"
            if (operationPayload.isNotEmpty() && operationPayload != payload || operationKind.isNotEmpty() && operationKind != kind)
                operationId = UUID.randomUUID().toString()
            operationPayload = payload
            operationKind = kind
            val command = if (existing == null) FrozenEditorSubmission.Create(operationId, normalized)
                else FrozenEditorSubmission.Update(operationId, existing.details.occurrence.ref, existing.token, patch(normalized))
            frozenSubmission = command
            submitted = true
            if (!persistSafely()) { state = state.copy(saving = false); return@launch }
            val result = try {
                command.execute(mutations)
            } catch (cancelled: CancellationException) { uncertain = true; throw cancelled }
            catch (_: Exception) { MutationResult.Uncertain(operationId, existing?.details?.occurrence?.ref?.eventId) }
            finish(result)
        }
    }

    fun requestDelete() {
        if (existing != null && !writesBlocked()) state = state.copy(confirmDelete = true)
    }
    fun cancelDelete() { state = state.copy(confirmDelete = false) }
    fun confirmDelete() {
        val event = existing ?: return
        if (!state.confirmDelete || writesBlocked()) return
        state = state.copy(saving = true, confirmDelete = false, problem = null)
        scope.launch {
            persistence?.cancelAndJoin()
            if (operationKind != "delete") operationId = UUID.randomUUID().toString()
            operationKind = "delete"; submitted = true
            val command = FrozenEditorSubmission.Delete(operationId, event.details.occurrence.ref, event.token)
            frozenSubmission = command
            if (!persistSafely()) { state = state.copy(saving = false); return@launch }
            val result = try { command.execute(mutations) }
            catch (cancelled: CancellationException) { uncertain = true; throw cancelled }
            catch (_: Exception) { MutationResult.Uncertain(operationId, event.details.occurrence.ref.eventId) }
            finish(result)
        }
    }

    /** A recovery proof only enables a separate user action; it never dispatches a write. */
    fun retryOriginal() {
        val command = frozenSubmission ?: return
        if (command is FrozenEditorSubmission.Create && command.draft.recurrence != null && !recurringCreationSupported) {
            state = state.copy(problem = EditorProblem.RECURRENCE_UNSUPPORTED)
            return
        }
        if (legacyDefaultVisibilityUnsafe || legacyTargetUnsafe || !state.retryReady || state.loading || state.draftReadFailed || state.problem == EditorProblem.DRAFT_VERSION || state.saving || completed || command.operationId != operationId || command.kind != operationKind) return
        state = state.copy(saving = true, retryReady = false, problem = null, result = null)
        scope.launch {
            submitted = true
            if (!persistSafely()) { state = state.copy(saving = false, retryReady = true); return@launch }
            val result = try { command.execute(mutations) }
            catch (cancelled: CancellationException) { uncertain = true; throw cancelled }
            catch (_: Exception) { MutationResult.Uncertain(operationId, existing?.details?.occurrence?.ref?.eventId) }
            finish(result)
        }
    }

    fun recover() {
        if ((!uncertain && !retryLocked) || state.saving || completed) return
        state = state.copy(saving = true)
        scope.launch {
            val result = try { mutations.recover(operationId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { MutationResult.Uncertain(operationId, existing?.details?.occurrence?.ref?.eventId) }
            finish(result, recovery = true)
        }
    }

    /** A numeric occurrence reference never authorizes replacing its persisted calendar namespace. */
    suspend fun reloadExisting(): EditReadResult? = existing?.let { original ->
        val expected = reviewSourceIdentity
        if (expected == null) {
            state = state.copy(reviewRequired = true, reviewLatest = null, problem = EditorProblem.CONFLICT)
            return@let EditReadResult.Unavailable
        }
        val latest = try { mutations.loadForEdit(original.details.occurrence.ref) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { EditReadResult.Unavailable }
        if (latest is EditReadResult.Ready &&
            (latest.event.details.occurrence.ref != original.details.occurrence.ref ||
                !sameSourceNamespace(expected, latest.event))) {
            state = state.copy(reviewRequired = true, reviewLatest = null, problem = EditorProblem.CONFLICT)
            EditReadResult.Unavailable
        } else latest
    }

    /** Only call after explicit user confirmation. Never discard an unresolved operation. */
    suspend fun discardAndReload(): EditReadResult? {
        if (recoveryOnly || uncertain || retryLocked || state.loading || state.saving || state.draftReadFailed ||
            state.problem == EditorProblem.DRAFT_VERSION) return null
        val latest = reloadExisting() ?: return null
        if (latest is EditReadResult.Ready) {
            persistence?.cancelAndJoin()
            try { drafts.delete(draftId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { state = state.copy(problem = EditorProblem.DRAFT_UNAVAILABLE); return null }
        }
        return latest
    }

    private suspend fun finish(received: MutationResult, recovery: Boolean = false) {
        if (received is MutationResult.Retired && received.operationId == operationId) {
            // Another holder closed this exact request. Do not resurrect or delete its draft here.
            completed = true
            persistence?.cancel()
            state = state.copy(saving = false, confirmDelete = false, recoveryRequired = false,
                retryReady = false, reviewRequired = false, result = received, problem = EditorProblem.DRAFT_RETIRED)
            return
        }
        val matchingProof = received is MutationResult.SafeToRetry && received.operationId == operationId
        val validCommand = !legacyDefaultVisibilityUnsafe && !legacyTargetUnsafe && !state.draftReadFailed && state.problem != EditorProblem.DRAFT_VERSION &&
            frozenSubmission?.let { it.operationId == operationId && it.kind == operationKind } == true
        val result = if (received is MutationResult.SafeToRetry && !matchingProof || received is MutationResult.Retired)
            MutationResult.Uncertain(operationId, existing?.details?.occurrence?.ref?.eventId) else received
        val success = result is MutationResult.LocalSaved || result is MutationResult.LocalDeleted
        val proofWithoutCommand = matchingProof && !validCommand
        if (matchingProof) retryLocked = true
        uncertain = result is MutationResult.Uncertain || recovery && !success && !matchingProof || proofWithoutCommand
        val retryReady = !success && !uncertain && retryLocked && validCommand
        if (success) {
            completed = true
            try { drafts.delete(draftId) } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* A submitted record stays locked on restoration. */ }
        } else {
            // An unfinished retry remains bound to its original ID and frozen command on disk.
            submitted = uncertain || retryLocked
            persistSafely()
        }
        state = state.copy(saving = false, recoveryRequired = uncertain, retryReady = retryReady,
            reviewRequired = result is MutationResult.Conflict && existing != null && !retryLocked,
            reviewLatest = (result as? MutationResult.Conflict)?.latest, result = result, problem = when {
                proofWithoutCommand -> EditorProblem.RETRY_UNAVAILABLE
                else -> when (result) {
                    is MutationResult.LocalSaved, is MutationResult.LocalDeleted -> null
                    MutationResult.PermissionRequired -> EditorProblem.PERMISSION
                    is MutationResult.Denied -> EditorProblem.DENIED
                    is MutationResult.ValidationFailed -> EditorProblem.INVALID_INPUT
                    is MutationResult.Conflict -> EditorProblem.CONFLICT
                    is MutationResult.Uncertain -> EditorProblem.UNCERTAIN
                    MutationResult.Unavailable -> EditorProblem.UNAVAILABLE
                    is MutationResult.SafeToRetry -> EditorProblem.SAFE_TO_RETRY
                    is MutationResult.Retired -> EditorProblem.DRAFT_RETIRED
                }
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
            availability = changed(EditableField.AVAILABILITY, fields.availability != original.availability, draft.availability),
            privacy = changed(EditableField.PRIVACY, fields.privacy != original.privacy, draft.privacy),
        )
    }

    private fun timeChanged(fields: EventEditorFields): Boolean =
        listOf(fields.startDate, fields.endDate, fields.startTime, fields.endTime, fields.allDay, fields.zone,
            fields.startOffset, fields.endOffset) != listOf(original.startDate, original.endDate,
            original.startTime, original.endTime, original.allDay, original.zone, original.startOffset, original.endOffset)

    /** Source identity is durable authority, never inferred from current metadata after restore. */
    private fun sourceIdentity(values: JSONObject): CalendarIdentity? = try {
        CalendarIdentityJson.optional(JSONObject(values.getString("sourceBaseline")), "sourceCalendarIdentity")
    } catch (_: Exception) { null }
    private fun sameSourceNamespace(expected: CalendarIdentity, source: EditableEvent): Boolean =
        expected.account.name.isNotBlank() && expected.account.type.isNotBlank() &&
            expected.calendarId == source.details.occurrence.ref.calendarId && expected == source.details.calendar?.identity
    private fun sourceNamespaceMatches(values: JSONObject): Boolean {
        val source = existing ?: return true
        return sourceIdentity(values)?.let { sameSourceNamespace(it, source) } == true
    }

    private fun sourceBaseline(): String = sourceBaseline(initial, existing)

    private fun targetIdentity(): String = editorDraftTarget(existing?.details?.occurrence?.ref ?: if (recoveryOnly) recoveryRef else null)

    companion object {
        const val LEGACY_VERSION = 1
        const val VERSION = 4

        /** Explicit new-launch preparation only; restoration must require the existing record. */
        internal fun initialRecord(initial: EventDraft, existing: EditableEvent?, id: String,
            selected: Boolean, clock: Clock, notices: List<EditorNotice> = emptyList(), defaultVisibilityCalendar: String = "", defaultVisibilityIdentity: CalendarIdentity? = null): DraftRecord {
            require(id.isNotBlank())
            val fields = fieldsFor(initial).let { if (existing == null && !selected) it.copy(calendarId = "", targetIdentity = null) else it }.copy(defaultVisibilityCalendar = defaultVisibilityCalendar, defaultVisibilityIdentity = defaultVisibilityIdentity)
            val payload = encode(fields).apply {
                EditorNoticeCodec.write(this, notices)
                put("operationId", UUID.randomUUID().toString()); put("operationKind", "")
                put("operationPayload", ""); put("submitted", false)
                put("target", editorDraftTarget(existing?.details?.occurrence?.ref))
                put("sourceToken", existing?.token?.value.orEmpty())
                put("sourceBaseline", sourceBaseline(initial, existing))
            }
            return DraftRecord(id, VERSION, payload.toString(), clock.instant())
        }

        /** Canonical source values, never localized labels or the edited raw form. */
        private fun sourceBaseline(initial: EventDraft, existing: EditableEvent?): String = JSONObject().apply {
            put("calendarId", initial.calendarId.value)
            put("title", initial.title); put("location", initial.location); put("description", initial.description)
            put("zone", initial.eventZone?.id.orEmpty())
            put("alerts", org.json.JSONArray(initial.alertMinutes))
            if (existing == null) initial.targetIdentity?.let { put("targetIdentity", CalendarIdentityJson.encode(it)) }
            put("availability", initial.availability ?: JSONObject.NULL); put("privacy", initial.privacy ?: JSONObject.NULL)
            initial.recurrence?.let { put("recurrence", AuthoredRecurrenceJson.encode(it)) }
            when (val time = initial.time) {
                is EventTime.Timed -> { put("kind", "timed"); put("start", time.start.toString()); put("end", time.end.toString()) }
                is EventTime.AllDay -> { put("kind", "allDay"); put("start", time.dates.start.toString()); put("end", time.dates.endExclusive.toString()) }
            }
            existing?.let { source ->
                source.details.calendar?.identity?.let { put("sourceCalendarIdentity", CalendarIdentityJson.encode(it)) }
                put("sourceTitle", source.details.occurrence.title)
                put("sourceAvailability", source.details.availability ?: JSONObject.NULL)
                put("sourcePrivacy", source.details.privacy ?: JSONObject.NULL)
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
                start?.offset?.id.orEmpty(), end?.offset?.id.orEmpty(), draft.availability?.toString().orEmpty(), draft.privacy?.toString().orEmpty(), targetIdentity = draft.targetIdentity, recurrence = RecurrenceEditorFields.from(draft.recurrence))
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
                if (value == CalendarContract.Reminders.MINUTES_DEFAULT) throw InvalidInput(EditorProblem.INVALID_INPUT)
            } }.distinct()
            val recurrence = try { fields.recurrence.normalized() }
                catch (_: Exception) { throw InvalidInput(EditorProblem.RECURRENCE_INVALID) }
            EventDraft(CalendarId(fields.calendarId.toLong()), time, if (fields.allDay) ZoneOffset.UTC else zone,
                fields.title, fields.location, fields.description, alerts,
                fields.availability.takeIf(String::isNotBlank)?.toInt(), fields.privacy.takeIf(String::isNotBlank)?.toInt(), fields.targetIdentity, recurrence)
        } catch (invalid: InvalidInput) { throw invalid }
        catch (_: Exception) { throw InvalidInput(EditorProblem.INVALID_INPUT) }

        private fun encode(f: EventEditorFields) = JSONObject().apply {
            put("recurrence", RecurrenceEditorFieldsJson.encode(f.recurrence))
            f.targetIdentity?.let { put("targetIdentity", CalendarIdentityJson.encode(it)) }
            f.defaultVisibilityIdentity?.let { put("defaultVisibilityIdentity", CalendarIdentityJson.encode(it)) }
            listOf("calendarId" to f.calendarId, "title" to f.title, "startDate" to f.startDate, "endDate" to f.endDate,
                "startTime" to f.startTime, "endTime" to f.endTime, "allDay" to f.allDay.toString(), "zone" to f.zone,
                "location" to f.location, "description" to f.description, "alerts" to f.alerts,
                "startOffset" to f.startOffset, "endOffset" to f.endOffset,
                "availability" to f.availability, "privacy" to f.privacy, "defaultVisibilityCalendar" to f.defaultVisibilityCalendar).forEach { (key, value) -> put(key, value) }
        }
        internal fun readRawFields(p: JSONObject): EventEditorFields = decode(p)

        private fun validTarget(value: CalendarIdentity?, id: String): Boolean = value != null && value.calendarId.value.toString() == id &&
            value.account.name.isNotBlank() && value.account.type.isNotBlank()
        private fun validCalendar(value: String): Boolean = value.toLongOrNull()?.let { it >= 0 } == true
        private fun readVisibilityCalendar(p: JSONObject): String {
            if (!p.has("defaultVisibilityCalendar")) return ""
            return p.get("defaultVisibilityCalendar").let { require(it is String); it }
        }

        private fun decode(p: JSONObject) = EventEditorFields(
            p.getString("calendarId"), p.optString("title", ""),
            p.optString("startDate", ""), p.optString("endDate", ""), p.optString("startTime", ""),
            p.optString("endTime", ""), p.optString("allDay", "false").toBooleanStrict(), p.optString("zone", ""),
            p.optString("location", ""), p.optString("description", ""), p.optString("alerts", ""),
            p.optString("startOffset", ""), p.optString("endOffset", ""),
            p.optString("availability", ""), p.optString("privacy", ""), readVisibilityCalendar(p),
            CalendarIdentityJson.optional(p, "targetIdentity"), CalendarIdentityJson.optional(p, "defaultVisibilityIdentity"),
            if (p.has("recurrence")) RecurrenceEditorFieldsJson.decode(p.getJSONObject("recurrence")) else RecurrenceEditorFields())
    }
}

class InvalidInput(val problem: EditorProblem) : IllegalArgumentException()
