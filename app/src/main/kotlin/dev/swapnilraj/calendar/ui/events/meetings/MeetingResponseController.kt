package dev.swapnilraj.calendar.ui.events.meetings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.swapnilraj.calendar.core.*
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class MeetingResponseProblem { DRAFT_REVIEW, DRAFT_CHANGED, DRAFT_UNAVAILABLE, SOURCE_CHANGED,
    SELECTION_REQUIRED, PERMISSION, DENIED, UNAVAILABLE, NEEDS_REVIEW, UNSUPPORTED, INVALID, UNCERTAIN, SAFE_TO_RETRY }

data class MeetingResponseState(val fields: MeetingResponseFields = MeetingResponseFields(), val loading: Boolean = true,
    val busy: Boolean = false, val problem: MeetingResponseProblem? = null, val reviewRequired: Boolean = false,
    val recoveryRequired: Boolean = false, val retryReady: Boolean = false, val draftUnreadable: Boolean = false,
    val localOutcome: MeetingMutationResult.LocalSaved? = null, val result: MeetingMutationResult.LocalSaved? = null)

/** Response-only port. Frozen requests cannot be discarded, rebased, or converted into cancellation. */
class MeetingResponseController(val launch: MeetingResponseLaunch, private val source: EditableMeeting?,
    private val meetings: CalendarMeetings, private val drafts: AtomicDraftStore, private val scope: CoroutineScope,
    private val requestWritePermission: suspend () -> Boolean, val recoveryOnly: Boolean = source == null,
    private val clock: Clock = Clock.systemUTC()) {
    var state by mutableStateOf(MeetingResponseState())
        private set
    private var document: MeetingResponseDocument? = null
    private var expected: DraftRecord? = null
    private var persistence: Job? = null
    private val storage = Mutex()
    private val action = AtomicBoolean(false)
    private var retired = false
    val originalCalendarIdentity: CalendarIdentity? get() = document?.sourceCalendarIdentity
    val operationId: String? get() = document?.operationId
    val title: String get() = document?.title.orEmpty()
    val time: EventTime? get() = document?.time
    val accountLabel: String? get() = document?.accountLabel
    val calendarLabel: String? get() = document?.calendarLabel
    val originalStatus: Int? get() = document?.originalStatus
    val recurring: Boolean get() = document?.recurring == true
    val frozen: Boolean get() = document?.frozen != null
    val canChange: Boolean get() = !retired && !recoveryOnly && !state.loading && !state.busy &&
        !state.reviewRequired && !state.draftUnreadable && !frozen
    val canRecover: Boolean get() = !retired && frozen && !state.loading && !state.busy && !state.draftUnreadable
    val canSave: Boolean get() = canChange && selectedCommand() != null
    val canReview: Boolean get() = !retired && !state.busy && !state.loading && !state.draftUnreadable && !frozen && !recoveryOnly
    fun canSelectScope(value: RecurrenceScope): Boolean = canChange && value in responseScopes && document?.rights?.get(value) == AccessDecision.ALLOWED

    init { scope.launch { restore() } }
    private suspend fun restore() {
        try {
            val record = drafts.load(launch.draftId) ?: error("Missing response draft")
            require(record.id == launch.draftId)
            val loaded = MeetingResponseDraftCodec.decode(record)
            require(loaded.generation == launch.generation && loaded.selected == launch.selected)
            require(!recoveryOnly || loaded.frozen != null)
            document = loaded; expected = record
            val changed = loaded.frozen == null && (source == null || !responseNamespaceMatches(loaded.sourceCalendarIdentity, source) || source.token != loaded.token ||
                source.details.occurrence.ref != loaded.selected || responseRights(source) != loaded.rights)
            val invalid = loaded.fields.scope.isNotBlank() && loaded.fields.scope !in responseScopes.map { it.name } ||
                loaded.fields.response.isNotBlank() && loaded.fields.response !in MeetingResponse.entries.map { it.name }
            state = state.copy(fields = loaded.fields, loading = false, reviewRequired = changed, recoveryRequired = loaded.frozen != null,
                problem = when { loaded.frozen != null -> MeetingResponseProblem.UNCERTAIN; changed -> MeetingResponseProblem.SOURCE_CHANGED
                    invalid -> MeetingResponseProblem.SELECTION_REQUIRED; else -> null })
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { state = state.copy(loading = false, draftUnreadable = true, reviewRequired = true, problem = MeetingResponseProblem.DRAFT_REVIEW) }
    }
    fun selectScope(value: RecurrenceScope) {
        if (!canSelectScope(value)) return
        change(state.fields.copy(scope = value.name))
    }
    fun selectResponse(value: MeetingResponse) {
        if (!canChange) return
        change(state.fields.copy(response = value.name))
    }
    private fun change(fields: MeetingResponseFields) {
        state = state.copy(fields = fields, problem = null)
        persistence?.cancel(); persistence = scope.launch { delay(300); persistFields() }
    }
    private fun selectedCommand(): MeetingCommand.Respond? {
        val doc = document ?: return null
        val scope = responseScopes.firstOrNull { it.name == state.fields.scope } ?: return null
        val response = MeetingResponse.entries.firstOrNull { it.name == state.fields.response } ?: return null
        if (doc.rights[scope] != AccessDecision.ALLOWED) return null
        return MeetingCommand.Respond(scope, response)
    }
    private fun stale() {
        retired = true
        state = state.copy(reviewRequired = true, retryReady = false, problem = MeetingResponseProblem.DRAFT_CHANGED)
    }
    /** Every replacement has a unique revision, even for identical selections and a fixed clock. */
    private suspend fun replace(candidate: MeetingResponseDocument): Boolean = storage.withLock {
        withContext(NonCancellable) {
            if (retired) return@withContext false
            val old = expected ?: return@withContext false
            val next = candidate.copy(revision = UUID.randomUUID().toString())
            val record = MeetingResponseDraftCodec.record(next, clock.instant())
            val result = try { drafts.compareAndSet(launch.draftId, old, record) } catch (_: Exception) { DraftChangeResult.Unavailable }
            val applied = when (result) {
                DraftChangeResult.Applied -> true
                DraftChangeResult.Changed -> { stale(); false }
                DraftChangeResult.Unavailable -> {
                    try {
                        val current = drafts.load(launch.draftId)
                        if (current == record) true else {
                            if (current != old) stale() else state = state.copy(problem = MeetingResponseProblem.DRAFT_UNAVAILABLE)
                            false
                        }
                    } catch (_: Exception) { state = state.copy(problem = MeetingResponseProblem.DRAFT_UNAVAILABLE); false }
                }
            }
            if (applied) {
                document = next; expected = record
                state = state.copy(recoveryRequired = next.frozen != null)
            }
            applied
        }
    }
    private suspend fun persistFields(): Boolean {
        val doc = document ?: return false
        if (retired || doc.frozen != null || state.reviewRequired || state.draftUnreadable) return false
        return replace(doc.copy(fields = state.fields))
    }
    /** No provider call on close. Successful dismissal retires this controller before a replacement can mount. */
    suspend fun prepareDismiss(): Boolean {
        if (!action.compareAndSet(false, true)) return false
        try {
            if (state.busy) return false
            if (state.loading || retired || state.draftUnreadable || state.reviewRequired || frozen) { retired = true; return true }
            state = state.copy(busy = true)
            persistence?.cancelAndJoin()
            val saved = persistFields()
            if (saved) retired = true
            return saved
        } finally { state = state.copy(busy = false); action.set(false) }
    }
    fun save() {
        if (!canChange) return
        val command = selectedCommand() ?: run { state = state.copy(problem = MeetingResponseProblem.SELECTION_REQUIRED); return }
        if (!action.compareAndSet(false, true)) return
        state = state.copy(busy = true, problem = null)
        scope.launch {
            try {
                persistence?.cancelAndJoin()
                if (!persistFields()) return@launch
                if (!permission()) return@launch
                val doc = document ?: return@launch
                val frozen = FrozenMeetingResponse(doc.operationId, doc.selected, doc.token, command)
                if (!replace(doc.copy(fields = state.fields, frozen = frozen))) return@launch
                dispatch(frozen)
            } finally { state = state.copy(busy = false); action.set(false) }
        }
    }
    private suspend fun permission(): Boolean {
        val allowed = try { requestWritePermission() } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
        if (!allowed) state = state.copy(problem = MeetingResponseProblem.PERMISSION)
        return allowed
    }
    private suspend fun verifyLease(): Boolean {
        if (retired) return false
        return try {
            if (drafts.load(launch.draftId) == expected) true else { stale(); false }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { state = state.copy(problem = MeetingResponseProblem.DRAFT_UNAVAILABLE); false }
    }
    private suspend fun dispatch(frozen: FrozenMeetingResponse) {
        if (!verifyLease()) return
        currentCoroutineContext().ensureActive()
        // This check cannot make storage and a provider atomic. The authorized frozen operation
        // remains durable, and the backend's operation journal fences a racing worker's same command.
        val result = try { meetings.mutate(frozen.operationId, frozen.selected, frozen.token, frozen.command) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { MeetingMutationResult.Common(MutationResult.Uncertain(frozen.operationId, frozen.selected.eventId)) }
        finish(result)
    }
    fun recover() {
        if (retired || !frozen || state.loading || state.busy || state.draftUnreadable || !action.compareAndSet(false, true)) return
        state = state.copy(busy = true, retryReady = false, problem = null)
        scope.launch {
            try {
                if (!verifyLease()) return@launch
                val doc = document ?: return@launch
                val result = try { meetings.recover(doc.operationId) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { MeetingMutationResult.Common(MutationResult.Uncertain(doc.operationId, doc.selected.eventId)) }
                if (verifyLease()) finish(result, recovery = true)
            } finally { state = state.copy(busy = false); action.set(false) }
        }
    }
    fun retryOriginal() {
        val frozen = document?.frozen ?: return
        if (retired || !state.retryReady || state.busy || !action.compareAndSet(false, true)) return
        state = state.copy(busy = true, retryReady = false, problem = null)
        scope.launch {
            try {
                val doc = document ?: return@launch
                if (!replace(doc)) return@launch
                if (!permission()) { state = state.copy(retryReady = true); return@launch }
                dispatch(frozen)
            } finally { state = state.copy(busy = false); action.set(false) }
        }
    }
    private suspend fun finish(result: MeetingMutationResult, recovery: Boolean = false) {
        val doc = document ?: return
        val frozen = doc.frozen ?: return
        if (result is MeetingMutationResult.LocalSaved && result.selected == frozen.selected && result.scope == frozen.command.scope) {
            state = state.copy(localOutcome = result, retryReady = false)
            complete(result)
            return
        }
        val outcome = (result as? MeetingMutationResult.Common)?.outcome
        val proof = recovery && outcome is MutationResult.SafeToRetry && outcome.operationId == frozen.operationId
        state = state.copy(recoveryRequired = true, retryReady = proof, problem = when {
            proof -> MeetingResponseProblem.SAFE_TO_RETRY
            result is MeetingMutationResult.LocalSaved || result is MeetingMutationResult.LocalCancelled -> MeetingResponseProblem.UNCERTAIN
            result is MeetingMutationResult.NeedsReview -> MeetingResponseProblem.NEEDS_REVIEW
            result is MeetingMutationResult.Unsupported -> MeetingResponseProblem.UNSUPPORTED
            result is MeetingMutationResult.Invalid -> MeetingResponseProblem.INVALID
            outcome == MutationResult.PermissionRequired -> MeetingResponseProblem.PERMISSION
            outcome is MutationResult.Denied -> MeetingResponseProblem.DENIED
            outcome is MutationResult.Conflict -> MeetingResponseProblem.SOURCE_CHANGED
            outcome is MutationResult.ValidationFailed -> MeetingResponseProblem.INVALID
            outcome == MutationResult.Unavailable -> MeetingResponseProblem.UNAVAILABLE
            else -> MeetingResponseProblem.UNCERTAIN
        })
    }
    private suspend fun complete(result: MeetingMutationResult.LocalSaved) = storage.withLock {
        withContext(NonCancellable) {
            val old = expected ?: return@withContext
            val deleted = try { drafts.compareAndSet(launch.draftId, old, null) } catch (_: Exception) { DraftChangeResult.Unavailable }
            val safeClose = when (deleted) {
                DraftChangeResult.Applied -> true
                DraftChangeResult.Changed -> { stale(); false }
                DraftChangeResult.Unavailable -> try {
                    val current = drafts.load(launch.draftId)
                    if (current == null || current == old) true else { stale(); false }
                } catch (_: Exception) { state = state.copy(problem = MeetingResponseProblem.DRAFT_UNAVAILABLE); false }
            }
            if (safeClose) { retired = true; state = state.copy(result = result, recoveryRequired = false, problem = null) }
        }
    }
    /** Only an unsubmitted draft can be discarded. A frozen command needs a future journal abandonment fence. */
    suspend fun discardAndReload(): MeetingReadResult<EditableMeeting>? {
        if (!canReview || !action.compareAndSet(false, true)) return null
        state = state.copy(busy = true)
        try {
            persistence?.cancelAndJoin()
            val old = expected ?: return null
            val original = document ?: return null
            if (original.sourceCalendarIdentity == null) {
                state = state.copy(reviewRequired = true, problem = MeetingResponseProblem.SOURCE_CHANGED)
                return null
            }
            val fresh = try { meetings.loadForEdit(launch.selected) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { MeetingReadResult.Unavailable }
            if (fresh !is MeetingReadResult.Ready || fresh.value.details.occurrence.ref != original.selected ||
                !responseNamespaceMatches(original.sourceCalendarIdentity, fresh.value)) {
                state = state.copy(reviewRequired = true, problem = MeetingResponseProblem.SOURCE_CHANGED)
                return null
            }
            val deleted = try { drafts.compareAndSet(launch.draftId, old, null) } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { DraftChangeResult.Unavailable }
            val removed = when (deleted) {
                DraftChangeResult.Applied -> true
                DraftChangeResult.Changed -> { stale(); false }
                DraftChangeResult.Unavailable -> try { if (drafts.load(launch.draftId) == null) true else { state = state.copy(problem = MeetingResponseProblem.DRAFT_UNAVAILABLE); false } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { state = state.copy(problem = MeetingResponseProblem.DRAFT_UNAVAILABLE); false }
            }
            if (!removed) return null
            retired = true
            return fresh
        } finally { state = state.copy(busy = false); action.set(false) }
    }
}
