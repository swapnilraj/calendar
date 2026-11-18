package dev.swapnilraj.calendar.ui.events

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.swapnilraj.calendar.core.*
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

enum class SeriesEditorProblem { DRAFT_UNAVAILABLE, SOURCE_CHANGED, UNSUPPORTED, INVALID_INPUT, NO_CHANGES,
    PERMISSION, DENIED, UNAVAILABLE, NEEDS_REVIEW, UNCERTAIN, SAFE_TO_RETRY, RETRY_UNAVAILABLE }

data class SeriesEventEditorState(val fields: SeriesEditorFields = SeriesEditorFields(), val loading: Boolean = true,
    val saving: Boolean = false, val problem: SeriesEditorProblem? = null, val reviewRequired: Boolean = false,
    val recoveryRequired: Boolean = false, val retryReady: Boolean = false, val confirmDelete: Boolean = false,
    val result: SeriesMutationResult? = null, val draftReadFailed: Boolean = false, val reviewing: Boolean = false)

/** Immutable scope and field provenance. A series draft is never interpreted as a one-off edit. */
class SeriesEventEditorController(val launch: SeriesEditorLaunch, val source: EditableSeries?,
    private val mutations: CalendarSeriesMutations, private val drafts: DraftStore, private val scope: CoroutineScope,
    val recoveryOnly: Boolean = source == null, private val clock: Clock = Clock.systemUTC()) {
    var state by mutableStateOf(SeriesEventEditorState())
        private set
    private var document: SeriesDraftDocument? = null
    private var expectedRecord: DraftRecord? = null
    private var persistence: Job? = null
    private var completed = false
    private var retryLocked = false
    private val discardInFlight = AtomicBoolean(false)
    val operationId: String? get() = document?.operationId
    val hasDefaultAlerts: Boolean get() = document?.hasDefaultAlerts == true
    val masterStart: RecurrenceStart? get() = document?.masterStart
    val deletionBoundary: RecurrenceKey? get() = document?.takeIf { it.scope == RecurrenceScope.FOLLOWING && it.action == SeriesEditorAction.DELETE }
        ?.let { seriesOriginalBoundary(it.selected, it.masterStart) }
    val fieldsAllowed: Set<EditableField> get() = document?.let { it.rights.fields.intersect(it.allowedFields).intersect(seriesUiFields) }.orEmpty()
    val canReviewDraft: Boolean get() = !completed && !state.reviewing && !state.saving && state.reviewRequired &&
        !state.recoveryRequired && !retryLocked && !recoveryOnly && !state.draftReadFailed
    val canChange: Boolean get() = launch.action == SeriesEditorAction.EDIT && !locked()
    val hasChanges: Boolean get() = document?.let { doc ->
        try { val command = seriesCommand(doc.copy(fields = state.fields)); when (command) {
            is SeriesCommand.WholeEdit -> command.intent.fields != EventPatch()
            is SeriesCommand.OccurrenceEdit -> command.patch != EventPatch()
            else -> false
        } } catch (_: Exception) { true }
    } ?: false

    init { scope.launch { restore() } }
    private suspend fun restore() {
        try {
            val saved = drafts.load(launch.draftId) ?: error("Missing draft")
            require(saved.id == launch.draftId)
            val loaded = SeriesDraftCodec.decode(saved)
            require(loaded.selected == launch.selected && loaded.scope == launch.scope && loaded.action == launch.action)
            require(!recoveryOnly || loaded.submitted)
            document = loaded; expectedRecord = saved
            val sourceChanged = !loaded.submitted && (loaded.reviewRequired || source == null || source.token != loaded.token ||
                source.selectedDetails.occurrence.ref != loaded.selected || source.snapshot.validatedSelection != loaded.selected ||
                source.snapshot.identity != loaded.identity || source.snapshot.start != loaded.masterStart ||
                source.scopes[loaded.scope] != loaded.rights || source.allowedFields != loaded.allowedFields ||
                !canPrepareSeriesScope(source, loaded.scope, loaded.action))
            state = state.copy(fields = loaded.fields, loading = false, recoveryRequired = loaded.submitted,
                reviewRequired = sourceChanged, problem = when {
                    loaded.submitted -> SeriesEditorProblem.UNCERTAIN
                    sourceChanged -> SeriesEditorProblem.SOURCE_CHANGED
                    else -> null
                })
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { state = state.copy(loading = false, draftReadFailed = true, problem = SeriesEditorProblem.DRAFT_UNAVAILABLE) }
    }
    private fun locked() = recoveryOnly || completed || state.loading || state.saving || state.reviewing || state.draftReadFailed ||
        state.reviewRequired || state.recoveryRequired || state.retryReady || retryLocked

    fun change(transform: (SeriesEditorFields) -> SeriesEditorFields) {
        if (!canChange) return
        val old = state.fields; val proposed = transform(old); val rights = fieldsAllowed
        val next = proposed.copy(
            title = if (EditableField.TITLE in rights) proposed.title else old.title,
            location = if (EditableField.LOCATION in rights) proposed.location else old.location,
            description = if (EditableField.DESCRIPTION in rights) proposed.description else old.description,
            alerts = if (EditableField.ALERTS in rights) proposed.alerts else old.alerts,
            replaceAlerts = if (EditableField.ALERTS in rights) proposed.replaceAlerts else old.replaceAlerts,
            availability = if (EditableField.AVAILABILITY in rights) proposed.availability else old.availability,
            privacy = if (EditableField.PRIVACY in rights) proposed.privacy else old.privacy,
        )
        state = state.copy(fields = next, problem = null, result = null)
        persistence?.cancel(); persistence = scope.launch { delay(300); persist() }
    }
    suspend fun flushDraft(): Boolean {
        persistence?.cancelAndJoin()
        if (state.loading || state.reviewing || state.draftReadFailed || completed || state.reviewRequired) return false
        return persist()
    }
    suspend fun prepareDismiss(): Boolean = if (state.saving || state.reviewing) false else if (state.loading || state.draftReadFailed || completed || state.reviewRequired || recoveryOnly)
        true else flushDraft()

    private suspend fun persist(): Boolean {
        val current = document ?: return false
        val expected = expectedRecord ?: return false
        return try {
            val saved = drafts.load(launch.draftId)
            check(saved != null && saved.version == expected.version && saved.payload == expected.payload)
            if (recoveryOnly) return true
            val next = current.copy(fields = state.fields)
            val record = SeriesDraftCodec.record(next, clock.instant())
            drafts.upsert(record)
            val durable = drafts.load(launch.draftId)
            check(durable != null && durable.version == record.version && durable.payload == record.payload)
            document = next; expectedRecord = durable
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { state = state.copy(problem = SeriesEditorProblem.DRAFT_UNAVAILABLE); false }
    }

    fun save() {
        if (!canChange) return
        if (!hasChanges) { state = state.copy(problem = SeriesEditorProblem.NO_CHANGES); return }
        val doc = document ?: return
        val command = try { seriesCommand(doc.copy(fields = state.fields)) }
            catch (_: Exception) { state = state.copy(problem = SeriesEditorProblem.INVALID_INPUT); return }
        submit(command)
    }
    fun requestDelete() {
        if (launch.action == SeriesEditorAction.DELETE && !locked() && document?.rights?.delete == AccessDecision.ALLOWED)
            state = state.copy(confirmDelete = true)
    }
    fun cancelDelete() { state = state.copy(confirmDelete = false) }
    fun confirmDelete() {
        if (!state.confirmDelete || locked() || launch.action != SeriesEditorAction.DELETE || document?.rights?.delete != AccessDecision.ALLOWED) return
        submit(SeriesCommand.Delete(launch.scope))
    }
    private fun submit(command: SeriesCommand) {
        val doc = document ?: return
        var frozen = FrozenSeriesSubmission(doc.operationId, doc.selected, doc.identity, doc.token, doc.scope, doc.action, command,
            if (doc.scope == RecurrenceScope.FOLLOWING && doc.action == SeriesEditorAction.DELETE) doc.masterStart else null)
        if (doc.frozen != null && doc.frozen != frozen) frozen = frozen.copy(operationId = UUID.randomUUID().toString())
        document = doc.copy(fields = state.fields, operationId = frozen.operationId, frozen = frozen, submitted = true)
        state = state.copy(saving = true, confirmDelete = false, problem = null, result = null)
        scope.launch {
            persistence?.cancelAndJoin()
            if (!persist()) {
                document = document?.copy(submitted = false)
                state = state.copy(saving = false, recoveryRequired = false)
                return@launch
            }
            val result = try { frozen.execute(mutations) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { SeriesMutationResult.Common(MutationResult.Uncertain(frozen.operationId, frozen.selected.eventId)) }
            finish(result)
        }
    }
    fun recover() {
        val doc = document ?: return
        if ((!state.recoveryRequired && !retryLocked) || state.loading || state.saving || state.draftReadFailed || completed) return
        state = state.copy(saving = true, problem = null)
        scope.launch {
            val result = try { mutations.recover(doc.operationId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { SeriesMutationResult.Common(MutationResult.Uncertain(doc.operationId, doc.selected.eventId)) }
            finish(result, recovery = true)
        }
    }
    fun retryOriginal() {
        val doc = document ?: return; val frozen = doc.frozen ?: return
        if (!state.retryReady || state.saving || state.loading || state.draftReadFailed || completed) return
        state = state.copy(saving = true, retryReady = false, problem = null, result = null)
        scope.launch {
            if (!persist()) { state = state.copy(saving = false, retryReady = true); return@launch }
            val result = try { frozen.execute(mutations) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { SeriesMutationResult.Common(MutationResult.Uncertain(doc.operationId, doc.selected.eventId)) }
            finish(result)
        }
    }
    private suspend fun finish(received: SeriesMutationResult, recovery: Boolean = false) {
        val doc = document ?: return
        val expectedSuccess = when (received) {
            is SeriesMutationResult.LocalSaved -> doc.action == SeriesEditorAction.EDIT && received.identity == doc.identity
            is SeriesMutationResult.LocalDeleted -> doc.action == SeriesEditorAction.DELETE && received.identity == doc.identity && received.scope == doc.scope && received.selected == doc.selected
            else -> true
        }
        val outcome = (received as? SeriesMutationResult.Common)?.outcome
        val proof = recovery && outcome is MutationResult.SafeToRetry && outcome.operationId == doc.operationId && doc.frozen != null
        val wrongProof = outcome is MutationResult.SafeToRetry && !proof
        val result = if (!expectedSuccess || wrongProof) SeriesMutationResult.Common(MutationResult.Uncertain(doc.operationId, doc.selected.eventId)) else received
        val success = result is SeriesMutationResult.LocalSaved || result is SeriesMutationResult.LocalDeleted
        if (proof) retryLocked = true
        val uncertain = (result as? SeriesMutationResult.Common)?.outcome is MutationResult.Uncertain || recovery && !success && !proof
        val review = !uncertain && !retryLocked && when (result) {
            is SeriesMutationResult.PlanningReview, is SeriesMutationResult.Unsupported, is SeriesMutationResult.Invalid -> true
            is SeriesMutationResult.Common -> result.outcome is MutationResult.Conflict || result.outcome is MutationResult.Denied
            else -> false
        }
        document = doc.copy(submitted = uncertain || retryLocked, reviewRequired = review)
        if (success) {
            completed = true
            try { drafts.delete(launch.draftId) } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* A retained submitted record stays recovery-only. */ }
        } else persist()
        state = state.copy(saving = false, recoveryRequired = uncertain, retryReady = !success && !uncertain && retryLocked,
            reviewRequired = review, result = result, problem = when {
                success -> null
                uncertain -> SeriesEditorProblem.UNCERTAIN
                proof -> SeriesEditorProblem.SAFE_TO_RETRY
                result is SeriesMutationResult.PlanningReview -> SeriesEditorProblem.NEEDS_REVIEW
                result is SeriesMutationResult.Unsupported -> SeriesEditorProblem.UNSUPPORTED
                result is SeriesMutationResult.Invalid -> SeriesEditorProblem.INVALID_INPUT
                result is SeriesMutationResult.Common -> when (result.outcome) {
                    MutationResult.PermissionRequired -> SeriesEditorProblem.PERMISSION
                    is MutationResult.Denied -> SeriesEditorProblem.DENIED
                    is MutationResult.Conflict -> SeriesEditorProblem.SOURCE_CHANGED
                    is MutationResult.ValidationFailed -> SeriesEditorProblem.INVALID_INPUT
                    MutationResult.Unavailable -> SeriesEditorProblem.UNAVAILABLE
                    else -> SeriesEditorProblem.RETRY_UNAVAILABLE
                }
                else -> SeriesEditorProblem.UNAVAILABLE
            })
    }
    suspend fun reloadForReview(): SeriesReadResult {
        if (completed) return SeriesReadResult.Unavailable
        return try { mutations.loadForEdit(launch.selected) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { SeriesReadResult.Unavailable }
    }
    /** Explicit discard only; unresolved operations are never abandoned or rebased. */
    suspend fun discardAndReload(): SeriesReadResult? {
        // Acquire before the first suspension: another confirmed action must never outlive replacement.
        if (!discardInFlight.compareAndSet(false, true)) return null
        var retired = false
        try {
            if (locked() && (!state.reviewRequired || state.recoveryRequired || retryLocked) || state.loading || state.saving ||
                state.draftReadFailed || completed || recoveryOnly) return null
            state = state.copy(reviewing = true)
            persistence?.cancelAndJoin()
            val latest = reloadForReview()
            if (latest is SeriesReadResult.Ready && (latest.series.selectedDetails.occurrence.ref != launch.selected || latest.series.snapshot.validatedSelection != launch.selected))
                return SeriesReadResult.Unavailable
            if (latest is SeriesReadResult.Ready) {
                try {
                    val expected = expectedRecord ?: error("Missing expected draft")
                    val current = drafts.load(launch.draftId)
                    check(current != null && current.id == expected.id && current.version == expected.version && current.payload == expected.payload)
                    // DraftStore has no CAS deletion. This fence detects observed replacement; single-flight
                    // and retirement prevent this controller from deleting a subsequently prepared draft.
                    drafts.delete(launch.draftId)
                    completed = true
                    retired = true
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { state = state.copy(problem = SeriesEditorProblem.DRAFT_UNAVAILABLE); return null }
            }
            return latest
        } finally {
            state = state.copy(reviewing = false)
            if (!retired) discardInFlight.set(false)
        }
    }
}
