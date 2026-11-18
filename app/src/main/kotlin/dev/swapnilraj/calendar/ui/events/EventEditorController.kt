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
import kotlinx.coroutines.sync.withLock

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
    DENIED, CONFLICT, UNCERTAIN, UNAVAILABLE, DRAFT_UNAVAILABLE, DRAFT_RETIRED, UNSUPPORTED, DRAFT_VERSION, SAFE_TO_RETRY, RETRY_UNAVAILABLE, VISIBILITY_REQUIRED, CALENDAR_REQUIRED, RECURRENCE_UNSUPPORTED, RECURRENCE_INVALID, RECURRENCE_REVIEW, DRAFT_UPGRADE_REQUIRED, RETIRE_UNAVAILABLE, RETIRE_BLOCKED_DISPATCH, RETIRE_BLOCKED_COMMIT, RETIRE_CONFLICT, RETIRE_UNSUPPORTED }

data class EventEditorState(
    val fields: EventEditorFields, val loading: Boolean = true, val saving: Boolean = false,
    val problem: EditorProblem? = null, val result: MutationResult? = null,
    val confirmDelete: Boolean = false, val recoveryRequired: Boolean = false, val reviewRequired: Boolean = false,
    val reviewLatest: EventDetails? = null, val draftReadFailed: Boolean = false, val retryReady: Boolean = false,
    val notices: List<EditorNotice> = emptyList(),
    val recurrenceReview: RecurrenceCreationPlan? = null,
    val legacy: Boolean = false, val retirementConfirmed: Boolean = false, val localOutcome: MutationResult? = null,
)

/** A durable generation and exact-record CAS authorize local draft changes; ports authorize writes. */
class EventEditorController(
    private val initial: EventDraft, val existing: EditableEvent?, val draftId: String,
    private val mutations: CalendarMutations, private val drafts: DraftStore, private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(), initialCalendarSelected: Boolean = true,
    val recoveryOnly: Boolean = false, private val recoveryRef: EventRef? = null,
    private val requireStoredDraft: Boolean = false, val recurringCreationSupported: Boolean = false,
    private val expectedGeneration: String? = null, private val retirement: OperationRetirement? = null,
    private val requestWritePermission: (suspend () -> Boolean)? = null,
) {
    private val original = fieldsFor(initial).let { if (existing == null && !initialCalendarSelected) it.copy(calendarId = "", targetIdentity = null) else it }
    var state by mutableStateOf(EventEditorState(EventEditorFields("", "", "", "", "", "", false, "", "", "", "")))
        private set
    private val atomic = drafts as? AtomicDraftStore
    private val action = java.util.concurrent.atomic.AtomicBoolean(false)
    private val storage = kotlinx.coroutines.sync.Mutex()
    private var document: OrdinaryDraftDocument? = null
    private var expectedRecord: DraftRecord? = null
    private var persistence: Job? = null
    private var closed = false
    private var dispatchStopped = false
    private var retirementProven = false
    private var recurrenceReviewPayload = ""
    val pendingOperationId: String get() = document?.operationId.orEmpty()
    val pendingOperationKind: String get() = document?.operationKind.orEmpty()
    val generation: String? get() = document?.generation
    val isEditing: Boolean get() = document?.launch?.kind?.let { it == EditorDraftKind.EDIT } ?: (existing != null || recoveryRef != null)
    val hasFrozenSubmission: Boolean get() = document?.frozen != null
    val boundCalendarIdentity: CalendarIdentity? get() = document?.let { doc ->
        (doc.frozen as? FrozenEditorSubmission.Create)?.draft?.targetIdentity ?: if (doc.launch.kind == EditorDraftKind.EDIT) doc.sourceIdentity else doc.fields.targetIdentity
    }
    val visibilityChoiceRequired: Boolean get() = existing == null && state.notices.any { it == EditorNotice.VISIBILITY_REVIEW_REQUIRED || it == EditorNotice.CALENDAR_VISIBILITY_REVIEW_REQUIRED } && state.fields.privacy.toIntOrNull() !in 0..3
    val calendarSelectionRequired: Boolean get() = existing == null && !validTarget(state.fields.targetIdentity, state.fields.calendarId)
    private val unsupported = existing?.let { source ->
        val ownEmails = listOfNotNull(source.details.organizer, source.details.calendar?.account?.name)
        source.details.attendees.any { guest -> guest.relationship != CalendarContract.Attendees.RELATIONSHIP_ORGANIZER && ownEmails.none { guest.email.equals(it, true) } } ||
            !source.details.recurrenceRule.isNullOrBlank() || source.details.occurrence.ref.recurrence != null
    } ?: false
    val canUpgrade: Boolean get() = !closed && !state.saving && !state.loading && !state.draftReadFailed && atomic != null &&
        document?.let { it.legacy && !it.hasRequest && (it.launch.kind == EditorDraftKind.CREATE || it.sourceIdentity != null && !state.reviewRequired) } == true
    val canDiscard: Boolean get() = !closed && !state.saving && !state.loading && !state.draftReadFailed && atomic != null && state.localOutcome == null && state.problem != EditorProblem.RETIRE_BLOCKED_COMMIT &&
        document?.let { !it.hasRequest || it.frozen != null && retirement != null } == true
    val canReplace: Boolean get() = canDiscard && document?.let { it.launch.kind == EditorDraftKind.CREATE || it.sourceIdentity != null } == true
    val canInspectRetirement: Boolean get() = !closed && !state.saving && document?.frozen != null && retirement != null
    val canAcknowledge: Boolean get() = !closed && !state.saving && state.localOutcome != null && atomic != null
    val canReviewLatest: Boolean get() = !closed && !state.saving && document?.launch?.kind == EditorDraftKind.EDIT && document?.sourceIdentity != null && document?.hasRequest == false && !recoveryOnly

    init { scope.launch { restore() } }
    private suspend fun restore() {
        try {
            val record = drafts.load(draftId) ?: error("Missing durable draft")
            require(record.id == draftId)
            if (record.version !in setOf(LEGACY_VERSION, PREVIOUS_VERSION, VERSION)) {
                state = state.copy(loading = false, draftReadFailed = true, problem = EditorProblem.DRAFT_VERSION); return
            }
            var doc = OrdinaryDraftDocument.decode(record)
            require(doc.generation == expectedGeneration)
            require(doc.legacy || atomic != null)
            require(doc.launch.ref == (existing?.details?.occurrence?.ref ?: recoveryRef))
            require(!recoveryOnly || doc.hasRequest)
            document = doc; expectedRecord = record
            val sourceIdentity = doc.sourceIdentity
            if (!doc.legacy && !doc.hasRequest && existing != null && sourceIdentity != null && sameSourceNamespace(sourceIdentity, existing) &&
                doc.sourceToken != existing.token.value && doc.sourceBaseline == sourceBaseline(initial, existing)) {
                if (!replace(doc.copy(sourceToken = existing.token.value, operationId = UUID.randomUUID().toString()))) error("Draft changed during refresh")
                doc = requireNotNull(document)
            }
            val changed = doc.launch.kind == EditorDraftKind.EDIT && !doc.hasRequest && (existing == null || sourceIdentity == null ||
                !sameSourceNamespace(sourceIdentity, existing) || doc.sourceToken != existing.token.value || doc.sourceBaseline != sourceBaseline(initial, existing))
            document = doc
            var restoredFields = doc.fields
            var restoredNotices = doc.notices
            if (doc.launch.kind == EditorDraftKind.CREATE) {
                val destination = (doc.frozen as? FrozenEditorSubmission.Create)?.draft?.targetIdentity ?: doc.fields.targetIdentity
                if (!validTarget(destination, doc.fields.calendarId) || (doc.frozen as? FrozenEditorSubmission.Create)?.draft?.targetIdentity == null && doc.frozen is FrozenEditorSubmission.Create)
                    restoredNotices = (restoredNotices + if (doc.hasRequest) EditorNotice.DESTINATION_RECOVERY_ONLY else EditorNotice.CALENDAR_RESELECTION_REQUIRED).distinct()
                if (EditorNotice.DUPLICATE_AS_ONE_OFF in doc.notices && doc.fields.privacy.toIntOrNull() == 0 &&
                    (doc.fields.targetIdentity == null || doc.fields.defaultVisibilityIdentity != doc.fields.targetIdentity)) {
                    if (doc.hasRequest) restoredNotices = (restoredNotices + EditorNotice.VISIBILITY_RECOVERY_ONLY).distinct()
                    else {
                        restoredFields = restoredFields.copy(privacy = "", defaultVisibilityCalendar = "", defaultVisibilityIdentity = null)
                        restoredNotices = (restoredNotices + EditorNotice.CALENDAR_VISIBILITY_REVIEW_REQUIRED).distinct()
                    }
                }
            }
            state = state.copy(fields = restoredFields, notices = restoredNotices, loading = false, legacy = doc.legacy,
                recoveryRequired = doc.hasRequest, reviewRequired = changed,
                reviewLatest = if (changed && existing != null && doc.sourceIdentity?.let { sameSourceNamespace(it, existing) } == true) existing.details else null,
                problem = when { doc.hasRequest -> EditorProblem.UNCERTAIN; changed -> EditorProblem.CONFLICT; doc.legacy -> EditorProblem.DRAFT_UPGRADE_REQUIRED; unsupported -> EditorProblem.UNSUPPORTED; else -> null })
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { state = state.copy(loading = false, draftReadFailed = true, problem = EditorProblem.DRAFT_UNAVAILABLE) }
    }
    private fun writesBlocked(): Boolean = closed || dispatchStopped || recoveryOnly || state.legacy || state.loading || state.saving || state.draftReadFailed ||
        state.reviewRequired || document?.hasRequest != false || unsupported
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
            persistence = scope.launch { delay(300); persistFields() }
        }
    }

    suspend fun prepareDismiss(): Boolean {
        if (state.saving) return false
        val ok = if (closed || state.loading || state.draftReadFailed || state.reviewRequired || state.legacy || recoveryOnly) true else flushDraft()
        if (ok) closed = true
        return ok
    }
    suspend fun flushDraft(): Boolean {
        persistence?.cancelAndJoin()
        if (closed || state.loading || state.saving || state.draftReadFailed || state.reviewRequired) return false
        return if (document?.hasRequest == true || state.legacy) verifyLease() else persistFields()
    }
    private suspend fun verifyLease(): Boolean = if (closed) false else try {
        if (drafts.load(draftId) == expectedRecord) true else { stale(); false }
    } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { state = state.copy(problem = EditorProblem.DRAFT_UNAVAILABLE); false }
    private fun stale() { closed = true; state = state.copy(problem = EditorProblem.DRAFT_UNAVAILABLE, retryReady = false, recurrenceReview = null) }
    private suspend fun replace(next: OrdinaryDraftDocument): Boolean = storage.withLock {
        if (closed || next.legacy) return@withLock false
        val expected = expectedRecord ?: return@withLock false
        val replacement = next.record(clock.instant())
        val result = try { atomic?.compareAndSet(draftId, expected, replacement) ?: DraftChangeResult.Unavailable }
            catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { DraftChangeResult.Unavailable }
        val applied = when (result) {
            DraftChangeResult.Applied -> true
            DraftChangeResult.Changed -> { stale(); false }
            DraftChangeResult.Unavailable -> try { drafts.load(draftId) == replacement } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
        }
        if (applied) { document = OrdinaryDraftDocument.decode(replacement); expectedRecord = replacement }
        else if (!closed) state = state.copy(problem = EditorProblem.DRAFT_UNAVAILABLE)
        applied
    }
    private suspend fun persistFields(): Boolean {
        val doc = document ?: return false
        if (doc.legacy || doc.hasRequest || state.reviewRequired) return false
        return replace(doc.copy(fields = state.fields, notices = state.notices))
    }
    fun save() = saveReviewed(false)
    fun dismissRecurrenceReview() { recurrenceReviewPayload = ""; state = state.copy(recurrenceReview = null) }
    fun confirmRecurringCreate() { if (state.recurrenceReview != null) saveReviewed(true) }
    private fun saveReviewed(confirmed: Boolean) {
        if (writesBlocked()) return
        if (calendarSelectionRequired) { state = state.copy(problem = EditorProblem.CALENDAR_REQUIRED); return }
        if (visibilityChoiceRequired) { state = state.copy(problem = EditorProblem.VISIBILITY_REQUIRED); return }
        val normalized = try { normalizedDraft(state.fields,
            preserveTime = if (existing != null && !timeChanged(state.fields)) initial.time else null,
            preserveAlerts = if (existing != null && state.fields.alerts == original.alerts) initial.alertMinutes else null) }
            catch (invalid: InvalidInput) { state = state.copy(problem = invalid.problem); return }
        val recurrence = normalized.recurrence
        if (recurrence != null) {
            if (existing != null || !recurringCreationSupported) { state = state.copy(problem = EditorProblem.RECURRENCE_UNSUPPORTED); return }
            val start = when (val time = normalized.time) {
                is EventTime.AllDay -> RecurrenceStart.AllDay(time.dates.start)
                is EventTime.Timed -> {
                    val zone = normalized.eventZone ?: run { state = state.copy(problem = EditorProblem.RECURRENCE_INVALID); return }
                    val local = time.start.atZone(zone); RecurrenceStart.Timed(local.toLocalDateTime(), zone, local.offset)
                }
            }
            val result = RecurrenceCreation.compile(start, normalized.time, recurrence, previewLimit = 5)
            if (result !is RecurrenceCreationResult.Ready) { state = state.copy(recurrenceReview = null,
                problem = if (result is RecurrenceCreationResult.NeedsReview) EditorProblem.RECURRENCE_REVIEW else EditorProblem.RECURRENCE_INVALID); return }
            val payload = encodeRawFields(state.fields).toString()
            if (!confirmed || recurrenceReviewPayload != payload) { recurrenceReviewPayload = payload; state = state.copy(recurrenceReview = result.plan, problem = null); return }
        } else if (confirmed) return
        val doc = document ?: return
        val command = if (existing == null) FrozenEditorSubmission.Create(doc.operationId, normalized)
            else FrozenEditorSubmission.Update(doc.operationId, requireNotNull(doc.launch.ref), EditToken(doc.sourceToken), patch(normalized))
        startSubmission(command)
    }
    private fun startSubmission(command: FrozenEditorSubmission) {
        if (!action.compareAndSet(false, true)) return
        state = state.copy(saving = true, problem = null, recurrenceReview = null, confirmDelete = false)
        scope.launch { try {
            persistence?.cancelAndJoin()
            if (!persistFields() || !permission()) return@launch
            val doc = document ?: return@launch
            if (!verifyLease() || doc.hasRequest) return@launch
            if (!replace(doc.copy(fields = state.fields, notices = state.notices, frozen = command, submitted = true,
                operationKind = command.kind, operationPayload = encodeRawFields(state.fields).toString()))) return@launch
            state = state.copy(recoveryRequired = true)
            dispatch(command)
        } finally { state = state.copy(saving = false); action.set(false) } }
    }
    private suspend fun permission(): Boolean {
        val granted = try { requestWritePermission?.invoke() == true } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
        if (!granted) state = state.copy(problem = EditorProblem.PERMISSION)
        return granted
    }
    private suspend fun dispatch(command: FrozenEditorSubmission) {
        if (dispatchStopped || !verifyLease()) return
        currentCoroutineContext().ensureActive()
        val result = try { command.execute(mutations) } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { MutationResult.Uncertain(command.operationId, document?.launch?.ref?.eventId) }
        finish(result)
    }
    fun requestDelete() { if (existing != null && !writesBlocked()) state = state.copy(confirmDelete = true) }
    fun cancelDelete() { state = state.copy(confirmDelete = false) }
    fun confirmDelete() {
        val doc = document ?: return
        if (!state.confirmDelete || existing == null || writesBlocked()) return
        startSubmission(FrozenEditorSubmission.Delete(doc.operationId, requireNotNull(doc.launch.ref), EditToken(doc.sourceToken)))
    }
    private fun retryValid(doc: OrdinaryDraftDocument): Boolean {
        val command = doc.frozen ?: return false
        return when (command) {
            is FrozenEditorSubmission.Create -> validTarget(command.draft.targetIdentity, doc.fields.calendarId) && command.draft.targetIdentity == doc.fields.targetIdentity &&
                !(EditorNotice.DUPLICATE_AS_ONE_OFF in doc.notices && doc.fields.privacy.toIntOrNull() == 0 && doc.fields.defaultVisibilityIdentity != doc.fields.targetIdentity)
            is FrozenEditorSubmission.Update, is FrozenEditorSubmission.Delete -> doc.sourceIdentity?.let { it.account.name.isNotBlank() && it.account.type.isNotBlank() && it.calendarId == doc.launch.ref?.calendarId } == true
        }
    }
    fun recover() {
        val doc = document ?: return
        if (closed || state.saving || !doc.hasRequest || !action.compareAndSet(false, true)) return
        state = state.copy(saving = true, retryReady = false)
        scope.launch { try {
            if (!verifyLease()) return@launch
            val result = try { mutations.recover(doc.operationId) } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { MutationResult.Uncertain(doc.operationId, doc.launch.ref?.eventId) }
            if (verifyLease()) finish(result, recovery = true)
        } finally { state = state.copy(saving = false); action.set(false) } }
    }
    fun retryOriginal() {
        val doc = document ?: return; val command = doc.frozen ?: return
        if (closed || dispatchStopped || !state.retryReady || !retryValid(doc) || state.saving || !action.compareAndSet(false, true)) return
        if (command is FrozenEditorSubmission.Create && command.draft.recurrence != null && !recurringCreationSupported) {
            action.set(false); state = state.copy(problem = EditorProblem.RECURRENCE_UNSUPPORTED); return
        }
        state = state.copy(saving = true, retryReady = false, problem = null)
        scope.launch { try { if (verifyLease() && permission()) dispatch(command) }
            finally { state = state.copy(saving = false); action.set(false) } }
    }
    private suspend fun finish(received: MutationResult, recovery: Boolean = false) {
        val doc = document ?: return
        if (received is MutationResult.Retired && received.operationId == doc.operationId) {
            dispatchStopped = true
            state = state.copy(problem = EditorProblem.DRAFT_RETIRED, saving = false, retryReady = false, recoveryRequired = true, result = received)
            return
        }
        val proof = !dispatchStopped && recovery && received is MutationResult.SafeToRetry && received.operationId == doc.operationId && retryValid(doc)
        val result = if (received is MutationResult.Retired || received is MutationResult.SafeToRetry && !proof) MutationResult.Uncertain(doc.operationId, doc.launch.ref?.eventId) else received
        val success = when (result) {
            is MutationResult.LocalSaved -> when (val command = doc.frozen) {
                is FrozenEditorSubmission.Create -> result.ref.calendarId == command.draft.calendarId
                is FrozenEditorSubmission.Update -> result.ref.calendarId == command.ref.calendarId && result.ref.eventId == command.ref.eventId
                else -> false
            }
            is MutationResult.LocalDeleted -> doc.operationKind == "delete" && result.ref == doc.launch.ref
            else -> false
        }
        if (result is MutationResult.LocalSaved || result is MutationResult.LocalDeleted) {
            if (!success) { state = state.copy(problem = EditorProblem.UNCERTAIN, recoveryRequired = true); return }
            dispatchStopped = true; state = state.copy(localOutcome = result)
            if (removeExact()) { closed = true; state = state.copy(result = result, problem = null, recoveryRequired = false, saving = false) }
            else state = state.copy(problem = EditorProblem.DRAFT_UNAVAILABLE, saving = false, result = null)
            return
        }
        state = state.copy(recoveryRequired = !proof, retryReady = proof, result = result, problem = when (result) {
            MutationResult.PermissionRequired -> EditorProblem.PERMISSION
            is MutationResult.Denied -> EditorProblem.DENIED
            is MutationResult.ValidationFailed -> EditorProblem.INVALID_INPUT
            is MutationResult.Conflict -> EditorProblem.CONFLICT
            is MutationResult.Uncertain -> EditorProblem.UNCERTAIN
            is MutationResult.SafeToRetry -> EditorProblem.SAFE_TO_RETRY
            MutationResult.Unavailable -> EditorProblem.UNAVAILABLE
            else -> EditorProblem.UNCERTAIN
        })
    }
    suspend fun acknowledgeLocalOutcome(): Boolean {
        if (!canAcknowledge || !action.compareAndSet(false, true)) return false
        state = state.copy(saving = true)
        try { if (!removeExact()) return false; closed = true; state = state.copy(result = state.localOutcome, problem = null); return true }
        finally { state = state.copy(saving = false); action.set(false) }
    }
    private suspend fun removeExact(): Boolean = storage.withLock {
        val expected = expectedRecord ?: return@withLock false
        val result = try { atomic?.compareAndSet(draftId, expected, null) ?: DraftChangeResult.Unavailable }
            catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { DraftChangeResult.Unavailable }
        when (result) {
            DraftChangeResult.Applied -> true
            DraftChangeResult.Changed -> { stale(); false }
            DraftChangeResult.Unavailable -> try { drafts.load(draftId) == null } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
        }
    }
    suspend fun reloadExisting(): EditReadResult? {
        if (document?.hasRequest == true || recoveryOnly) return null
        return loadReplacementSource()
    }
    private suspend fun loadReplacementSource(): EditReadResult? {
        val doc = document ?: return null; val ref = doc.launch.ref ?: return null; val pin = doc.sourceIdentity ?: return EditReadResult.Unavailable
        val result = try { mutations.loadForEdit(ref) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { EditReadResult.Unavailable }
        if (result is EditReadResult.Ready && (result.event.details.occurrence.ref != ref || !sameSourceNamespace(pin, result.event))) {
            state = state.copy(reviewLatest = null, problem = EditorProblem.CONFLICT); return EditReadResult.Unavailable
        }
        state = state.copy(reviewLatest = (result as? EditReadResult.Ready)?.event?.details)
        return result
    }
    suspend fun inspectRetirement() {
        val doc = document ?: return; val request = doc.frozen?.retirementRequest() ?: return
        if (!canInspectRetirement || !action.compareAndSet(false, true)) return
        state = state.copy(saving = true)
        try { retirementResult(retirement!!.inspect(request), request.operationId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { state = state.copy(problem = EditorProblem.RETIRE_UNAVAILABLE) }
        finally { state = state.copy(saving = false); action.set(false) }
    }
    private fun retirementResult(result: OperationRetirementResult, id: String): Boolean {
        if (result is OperationRetirementResult.Retired && result.operationId == id) {
            retirementProven = true; dispatchStopped = true
            state = state.copy(retirementConfirmed = true, retryReady = false, problem = EditorProblem.DRAFT_RETIRED)
            return true
        }
        state = state.copy(problem = when (result) {
            is OperationRetirementResult.Blocked -> if (result.reason == RetirementBlockReason.RECORDED_COMMIT) EditorProblem.RETIRE_BLOCKED_COMMIT else EditorProblem.RETIRE_BLOCKED_DISPATCH
            is OperationRetirementResult.Conflict, is OperationRetirementResult.Retired -> EditorProblem.RETIRE_CONFLICT
            OperationRetirementResult.Unsupported -> EditorProblem.RETIRE_UNSUPPORTED
            else -> EditorProblem.RETIRE_UNAVAILABLE
        })
        return false
    }
    private suspend fun fenceOriginal(): Boolean {
        val doc = document ?: return false
        if (!doc.hasRequest) return true
        val command = doc.frozen ?: return false
        if (retirementProven) return true
        val port = retirement ?: return false
        val request = command.retirementRequest()
        val result = try { port.retire(request) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { OperationRetirementResult.Unavailable }
        if (retirementResult(result, doc.operationId)) return true
        if (result == OperationRetirementResult.Unavailable) {
            val inspection = try { port.inspect(request) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { OperationRetirementResult.Unavailable }
            return retirementResult(inspection, doc.operationId)
        }
        return false
    }
    suspend fun discardDraft(): Boolean = discardOrReplace(false) != null
    suspend fun replaceDraft(): EditorDraftLaunch? = discardOrReplace(true)?.launch
    private suspend fun discardOrReplace(replace: Boolean): DraftPreparationResult.Ready? {
        if (!(if (replace) canReplace else canDiscard) || !action.compareAndSet(false, true)) return null
        state = state.copy(saving = true)
        try {
            persistence?.cancelAndJoin()
            val doc = document ?: return null
            var fresh: EditableEvent? = null
            if (replace && doc.launch.kind == EditorDraftKind.EDIT) {
                fresh = (loadReplacementSource() as? EditReadResult.Ready)?.event ?: return null
            }
            if (!verifyLease() || !fenceOriginal() || !removeExact()) return null
            closed = true
            if (!replace) return DraftPreparationResult.Ready(doc.launch)
            val prepared = prepareOrdinaryReplacement(doc.copy(fields = state.fields, notices = state.notices), initial, fresh, requireNotNull(atomic), clock)
            if (prepared == null) { state = state.copy(problem = EditorProblem.DRAFT_UNAVAILABLE); return null }
            return DraftPreparationResult.Ready(prepared)
        } finally { state = state.copy(saving = false); action.set(false) }
    }
    suspend fun upgradeLegacyDraft(): EditorDraftLaunch? {
        if (!canUpgrade || !action.compareAndSet(false, true)) return null
        state = state.copy(saving = true)
        try {
            val doc = document ?: return null; val expected = expectedRecord ?: return null
            if (!verifyLease()) return null
            val needsVisibility = EditorNotice.DUPLICATE_AS_ONE_OFF in doc.notices && doc.fields.privacy.toIntOrNull() == 0 &&
                (doc.fields.targetIdentity == null || doc.fields.defaultVisibilityIdentity != doc.fields.targetIdentity)
            val next = doc.copy(version = VERSION, generation = UUID.randomUUID().toString(), revision = UUID.randomUUID().toString(),
                fields = if (needsVisibility) state.fields.copy(privacy = "", defaultVisibilityCalendar = "", defaultVisibilityIdentity = null) else state.fields,
                notices = if (needsVisibility) (state.notices + EditorNotice.CALENDAR_VISIBILITY_REVIEW_REQUIRED).distinct() else state.notices)
            val record = next.record(clock.instant())
            val result = try { atomic!!.compareAndSet(draftId, expected, record) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { DraftChangeResult.Unavailable }
            val applied = result == DraftChangeResult.Applied || result == DraftChangeResult.Unavailable && drafts.load(draftId) == record
            if (!applied) { if (result == DraftChangeResult.Changed) stale() else state = state.copy(problem = EditorProblem.DRAFT_UNAVAILABLE); return null }
            closed = true
            return DraftBrowserCodec.read(record).launch
        } finally { state = state.copy(saving = false); action.set(false) }
    }
    /** Retained for source compatibility; destructive review now returns a new launch via replaceDraft. */
    suspend fun discardAndReload(): EditReadResult? = reloadExisting()
    private fun sameSourceNamespace(expected: CalendarIdentity, source: EditableEvent): Boolean =
        expected.account.name.isNotBlank() && expected.account.type.isNotBlank() && expected.calendarId == source.details.occurrence.ref.calendarId && expected == source.details.calendar?.identity
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

    companion object {
        const val LEGACY_VERSION = 1
        const val PREVIOUS_VERSION = 4
        const val VERSION = 5

        /** Explicit new-launch preparation only; restoration must require the existing record. */
        internal fun initialRecord(initial: EventDraft, existing: EditableEvent?, id: String,
            selected: Boolean, clock: Clock, notices: List<EditorNotice> = emptyList(), defaultVisibilityCalendar: String = "", defaultVisibilityIdentity: CalendarIdentity? = null): DraftRecord {
            require(id.isNotBlank())
            existing?.let { source ->
                val identity = requireNotNull(source.details.calendar?.identity)
                require(identity.calendarId == source.details.occurrence.ref.calendarId && identity.account.name.isNotBlank() && identity.account.type.isNotBlank())
                require(initial.calendarId == identity.calendarId)
            }
            val fields = fieldsFor(initial).let { if (existing == null && !selected) it.copy(calendarId = "", targetIdentity = null) else it }.copy(defaultVisibilityCalendar = defaultVisibilityCalendar, defaultVisibilityIdentity = defaultVisibilityIdentity)
            val payload = encodeRawFields(fields).apply {
                put("feature", "ordinary-editor"); put("schema", 1)
                put("generation", UUID.randomUUID().toString()); put("revision", UUID.randomUUID().toString())
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
        internal fun sourceBaseline(initial: EventDraft, existing: EditableEvent?): String = JSONObject().apply {
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

        internal fun encodeRawFields(f: EventEditorFields) = JSONObject().apply {
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
