package dev.swapnilraj.calendar.ui.events.meetings.create

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.EventEditorController
import dev.swapnilraj.calendar.ui.events.EventEditorFields
import dev.swapnilraj.calendar.ui.events.EditorTemporalInput
import dev.swapnilraj.calendar.ui.events.EditorConflictBinding
import java.time.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class MeetingCreationProblem { DRAFT_UNAVAILABLE, DRAFT_REVIEW, STALE_DRAFT, CALENDAR_REQUIRED,
    SOURCE_CHANGED, PERMISSION, DENIED, LIMIT_EXCEEDED, UNAVAILABLE, INVALID_EVENT, INVALID_GUEST,
    GUESTS_REQUIRED, POLICY_REQUIRED, RECURRENCE_UNSUPPORTED, RECURRENCE_INVALID, RECURRENCE_REVIEW, UNCERTAIN, SAFE_TO_RETRY, RETIRED,
    RETIRE_UNAVAILABLE, RETIRE_BLOCKED_DISPATCH, RETIRE_BLOCKED_COMMIT, RETIRE_CONFLICT, RETIRE_UNSUPPORTED }

data class MeetingCreationState(val fields: MeetingCreationFields? = null, val loading: Boolean = true,
    val busy: Boolean = false, val problem: MeetingCreationProblem? = null, val policyAccepted: Boolean = false,
    val allowedGuestTypes: Set<MeetingGuestType> = emptySet(), val recoveryRequired: Boolean = false,
    val retryReady: Boolean = false, val confirmDiscard: Boolean = false, val discarded: Boolean = false,
    val localSaved: EventRef? = null, val acknowledged: Boolean = false,
    val recurringCreation: AccessDecision = AccessDecision.UNKNOWN,
    val recurrenceReview: MeetingCreationRecurrenceReview? = null)

/** Presentation of an already validated candidate; rendering never reparses the form. */
data class MeetingCreationRecurrenceReview(val plan: RecurrenceCreationPlan,val spec: RecurrenceSpec,val title: String)

/** Dedicated meeting-create state machine. Public actions are suspending; hosts launch them explicitly.
 * Exact CAS owns local data, not the provider transaction. Frozen authorization remains recoverable.
 */
class MeetingCreationController(val launch: MeetingCreationLaunch, private val meetings: CalendarMeetings,
    private val store: AtomicDraftStore, private val requestWritePermission: suspend () -> Boolean,
    private val retirement: OperationRetirement? = null, private val clock: Clock = Clock.systemUTC()) {
    var state by mutableStateOf(MeetingCreationState()); private set
    private val action = AtomicBoolean(false)
    private val storage = Mutex()
    private var document by mutableStateOf<MeetingCreationDocument?>(null)
    private var expected: DraftRecord? = null
    private var stopped = false
    private var verified = false
    private var retirementProven = false
    private var pendingRaw by mutableStateOf<MeetingCreationDocument?>(null)
    private var cleanupAttempt: DraftRecord? = null
    private var cleanupChanged = false
    private data class Reviewed(val generation: String,val revision: String,val operationId: String,
        val authority: MeetingCreationAuthority,val command: FrozenMeetingCreation)
    private var reviewed: Reviewed? = null
    /** Read-only lease correlation; the phone must also mask its optimistic raw projection. */
    fun conflictBinding(presentationVisible: Boolean): EditorConflictBinding? {
        val doc = document ?: return null
        if (!presentationVisible || stopped || state.loading || state.busy || pendingRaw != null || doc.frozen != null ||
            state.recoveryRequired || state.localSaved != null || state.acknowledged || state.discarded ||
            state.problem in setOf(MeetingCreationProblem.DRAFT_UNAVAILABLE, MeetingCreationProblem.DRAFT_REVIEW,
                MeetingCreationProblem.STALE_DRAFT, MeetingCreationProblem.SOURCE_CHANGED, MeetingCreationProblem.PERMISSION,
                MeetingCreationProblem.DENIED, MeetingCreationProblem.UNAVAILABLE) || doc.authority != null && !verified) return null
        return EditorConflictBinding(doc.generation, doc.revision, EditorTemporalInput.from(doc.fields.event),
            doc.fields.event.targetIdentity, null)
    }
    val hasPendingRawInput: Boolean get() = pendingRaw != null
    val frozen: Boolean get() = document?.frozen != null
    val hasFrozenRecurrence: Boolean get() = document?.frozen?.draft?.recurrence != null
    val canChange: Boolean get() = !state.busy && canChangeIgnoringBusy()
    val canEditRaw: Boolean get() = canAcceptRaw()
    val canEditRepeat: Boolean get() = canEditRaw && verified && document?.schema == 2 && state.recurringCreation == AccessDecision.ALLOWED
    val canSave: Boolean get() = canChange && verified && document?.authority != null && state.problem !in setOf(MeetingCreationProblem.STALE_DRAFT, MeetingCreationProblem.DRAFT_UNAVAILABLE)
    val canDiscard: Boolean get() = !stopped && !state.loading && !state.busy && document != null && state.localSaved == null && (!frozen || retirement != null)
    /** Fixed backend policy; presentation must disclose this before asking for acceptance. */
    val guestsCanModify = false
    val guestsCanInviteOthers = false
    val guestsCanSeeGuests = true

    suspend fun load() = exclusive {
        if (stopped || document != null) return@exclusive
        val record = (lookup() as? DraftLookup.Known)?.record ?: run { state = MeetingCreationState(loading = false, problem = MeetingCreationProblem.DRAFT_UNAVAILABLE); return@exclusive }
        val doc = try { MeetingCreationDraftCodec.decode(record) } catch (_: Exception) {
            state = MeetingCreationState(loading = false, problem = MeetingCreationProblem.DRAFT_REVIEW); return@exclusive
        }
        if (doc.id != launch.draftId || doc.generation != launch.generation) {
            state = MeetingCreationState(loading = false, problem = MeetingCreationProblem.STALE_DRAFT); return@exclusive
        }
        document = doc; expected = record
        state = MeetingCreationState(doc.fields, loading = false, busy = true, policyAccepted = doc.policyAccepted,
            allowedGuestTypes = doc.authority?.allowedTypes.orEmpty(), recoveryRequired = doc.frozen != null,
            recurringCreation = doc.authority?.recurringCreation ?: AccessDecision.UNKNOWN)
        // The persisted record, never the route hint, determines whether any source read is permitted.
        if (doc.frozen != null) return@exclusive
        val authority = doc.authority ?: run { state = state.copy(problem = MeetingCreationProblem.CALENDAR_REQUIRED); return@exclusive }
        val fresh = readSource(authority.identity)
        when (fresh) {
            is MeetingReadResult.Ready -> {
                val source = fresh.value
                verified = sameAuthority(source,authority,doc.schema)
                if (!verified) state = state.copy(problem = MeetingCreationProblem.SOURCE_CHANGED)
            }
            else -> state = state.copy(problem = readProblem(fresh))
        }
    }

    suspend fun changeFields(fields: EventEditorFields) = exclusive {
        if (!canAcceptRaw()) return@exclusive
        val doc = pendingRaw ?: document!!
        // Calendar authority can only come from selectCalendar's fresh backend response.
        val event = fields.copy(calendarId = doc.fields.event.calendarId, targetIdentity = doc.fields.event.targetIdentity)
        persistRaw(doc.copy(fields = doc.fields.copy(event = event)))
    }
    suspend fun changeGuests(guests: List<MeetingCreationGuestFields>) = exclusive {
        if (!canAcceptRaw() || guests.map { it.rowId }.distinct().size != guests.size) return@exclusive
        if (guests.any { runCatching { require(java.util.UUID.fromString(it.rowId).toString() == it.rowId) }.isFailure }) return@exclusive
        val doc = pendingRaw ?: document!!
        persistRaw(doc.copy(fields = doc.fields.copy(guests = guests.toList())))
    }
    suspend fun acceptFixedGuestPolicy(accepted: Boolean) = exclusive {
        if (canAcceptRaw()) persistRaw((pendingRaw ?: document!!).copy(policyAccepted = accepted))
    }
    suspend fun selectCalendar(calendar: CalendarInfo) = exclusive {
        if (!canChangeIgnoringBusy() || !validIdentity(calendar.identity)) return@exclusive
        when (val result = readSource(calendar.identity)) {
            is MeetingReadResult.Ready -> {
                val source = result.value
                if (source.calendar.identity != calendar.identity || source.token.value.isBlank() || source.allowedGuestTypes.isEmpty()) {
                    state = state.copy(problem = MeetingCreationProblem.SOURCE_CHANGED); verified = false; return@exclusive
                }
                val doc = document!!
                val authority = MeetingCreationAuthority(calendar.identity, source.token, source.allowedGuestTypes.toSet(),source.recurringCreation)
                if (persistRaw(doc.copy(schema = 2,authority = authority, fields = doc.fields.copy(event = doc.fields.event.copy(
                        calendarId = calendar.id.value.toString(), targetIdentity = calendar.identity))))) verified = true
            }
            else -> { verified = false; state = state.copy(problem = readProblem(result)) }
        }
    }

    suspend fun save() = exclusive {
        if (!canChangeIgnoringBusy() || !verified) return@exclusive
        val doc = document!!; val authority = doc.authority ?: return@exclusive
        val event = doc.fields.event
        val problem = when {
            event.recurrence.frequency != "NONE" && (doc.schema != 2 || authority.recurringCreation != AccessDecision.ALLOWED) -> MeetingCreationProblem.RECURRENCE_UNSUPPORTED
            event.targetIdentity != authority.identity || event.calendarId != authority.identity.calendarId.value.toString() -> MeetingCreationProblem.CALENDAR_REQUIRED
            !doc.policyAccepted -> MeetingCreationProblem.POLICY_REQUIRED
            doc.fields.guests.isEmpty() -> MeetingCreationProblem.GUESTS_REQUIRED
            else -> null
        }
        if (problem != null) { state = state.copy(problem = problem); return@exclusive }
        val draft = try { EventEditorController.normalizedDraft(event).also {
            require(it.privacy?.let { v -> v in 0..3 } != false && it.availability?.let { v -> v in 0..2 } != false)
            require(it.time !is EventTime.Timed || it.eventZone?.let(ProviderTimeZoneCompatibility::isCompatible) == true)
        } } catch (_: Exception) { state = state.copy(problem = MeetingCreationProblem.INVALID_EVENT); return@exclusive }
        val guests = try { typedGuests(doc.fields.guests, authority.allowedTypes).also {
            require(it.none { guest -> guest.email.equals(authority.identity.account.name, true) || guest.email.equals(authority.identity.owner, true) })
        } } catch (_: Exception) { state = state.copy(problem = MeetingCreationProblem.INVALID_GUEST); return@exclusive }
        val frozen = FrozenMeetingCreation(doc.operationId, authority.token, draft, guests, MeetingCreationDraftCodec.fingerprint(doc.fields))
        val recurrence = draft.recurrence
        if(recurrence != null) {
            val start=when(val time=draft.time) {
                is EventTime.AllDay -> RecurrenceStart.AllDay(time.dates.start)
                is EventTime.Timed -> time.start.atZone(requireNotNull(draft.eventZone)).let {RecurrenceStart.Timed(it.toLocalDateTime(),it.zone,it.offset)}
            }
            val result=RecurrenceCreation.compile(start,draft.time,recurrence,previewLimit=5)
            if(result !is RecurrenceCreationResult.Ready) {
                invalidateReview();state=state.copy(problem=if(result is RecurrenceCreationResult.NeedsReview) MeetingCreationProblem.RECURRENCE_REVIEW else MeetingCreationProblem.RECURRENCE_INVALID)
                return@exclusive
            }
            val seedMatches=when(val first=result.plan.preview.firstOrNull()) {
                is RecurrenceOccurrence.AllDay -> start is RecurrenceStart.AllDay && first.date==start.date
                is RecurrenceOccurrence.Timed -> start is RecurrenceStart.Timed && first.instant==start.instant
                null -> false
            }
            if(!seedMatches) {invalidateReview();state=state.copy(problem=MeetingCreationProblem.RECURRENCE_INVALID);return@exclusive}
            reviewed=Reviewed(doc.generation,doc.revision,doc.operationId,authority,frozen)
            state=state.copy(recurrenceReview=MeetingCreationRecurrenceReview(result.plan,recurrence,draft.title),problem=null)
            return@exclusive
        }
        if (!permission()) { state = state.copy(problem = MeetingCreationProblem.PERMISSION); return@exclusive }
        if (!replace(doc.copy(frozen = frozen))) return@exclusive
        dispatch(frozen)
    }

    fun dismissRecurrenceReview() { if(!action.get()) invalidateReview() }
    suspend fun confirmRecurringCreate() = exclusive {
        val candidate=reviewed ?: return@exclusive
        val doc=document ?: return@exclusive
        if(!canChangeIgnoringBusy() || !verified || !matchesReview(candidate,doc)) {invalidateReview();return@exclusive}
        if(!permission()) {state=state.copy(problem=MeetingCreationProblem.PERMISSION);return@exclusive}
        when(val fresh=readSource(candidate.authority.identity)) {
            is MeetingReadResult.Ready -> if(!sameAuthority(fresh.value,candidate.authority,doc.schema)) {
                verified=false;invalidateReview();state=state.copy(problem=MeetingCreationProblem.SOURCE_CHANGED);return@exclusive
            }
            else -> {invalidateReview();state=state.copy(problem=readProblem(fresh));return@exclusive}
        }
        if(!matchesReview(candidate,document ?: return@exclusive)) {invalidateReview();return@exclusive}
        invalidateReview()
        if(!replace(doc.copy(frozen=candidate.command))) return@exclusive
        dispatch(candidate.command)
    }
    private fun matchesReview(candidate:Reviewed,doc:MeetingCreationDocument)=doc.schema==2 && doc.frozen==null &&
        doc.generation==candidate.generation && doc.revision==candidate.revision && doc.operationId==candidate.operationId &&
        doc.authority==candidate.authority && candidate.authority.recurringCreation==AccessDecision.ALLOWED && doc.policyAccepted &&
        MeetingCreationDraftCodec.fingerprint(doc.fields)==candidate.command.rawFingerprint
    private fun invalidateReview() {reviewed=null;state=state.copy(recurrenceReview=null)}
    private fun sameAuthority(source:MeetingCreation,authority:MeetingCreationAuthority,schema:Int)=
        source.calendar.identity==authority.identity && source.token==authority.token && source.allowedGuestTypes==authority.allowedTypes &&
        (schema==1 || source.recurringCreation==authority.recurringCreation)
    suspend fun recover() = exclusive {
        val frozen = document?.frozen ?: return@exclusive
        if (stopped || state.localSaved != null || !ownsRecord()) return@exclusive
        val result = try { meetings.recover(frozen.operationId) } catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
        applyResult(result, recovery = true)
    }
    suspend fun retryOriginal() = exclusive {
        val frozen = document?.frozen ?: return@exclusive
        if (stopped || !state.retryReady || state.localSaved != null || !ownsRecord()) return@exclusive
        if (!permission()) { state = state.copy(problem = MeetingCreationProblem.PERMISSION); return@exclusive }
        state = state.copy(retryReady = false)
        dispatch(frozen)
    }
    fun requestDiscard() { if (canDiscard && !action.get()) state = state.copy(confirmDiscard = true) }
    fun cancelDiscard() { if (!action.get()) state = state.copy(confirmDiscard = false) }
    suspend fun confirmDiscard() = exclusive {
        if (!state.confirmDiscard || stopped || document == null || state.localSaved != null || (frozen && retirement == null)) return@exclusive
        state = state.copy(confirmDiscard = false)
        val frozen = document!!.frozen
        if (frozen != null && !retirementProven) {
            val port = retirement ?: return@exclusive
            val request = frozen.retirement()
            var result = try { port.retire(request) } catch (e: CancellationException) { throw e }
                catch (_: Exception) { OperationRetirementResult.Unavailable }
            if (result == OperationRetirementResult.Unavailable) result = try { port.inspect(request) }
                catch (e: CancellationException) { throw e } catch (_: Exception) { OperationRetirementResult.Unavailable }
            if (result !is OperationRetirementResult.Retired || result.operationId != frozen.operationId) {
                state = state.copy(problem = when(result) {
                    is OperationRetirementResult.Blocked -> if (result.reason == RetirementBlockReason.RECORDED_COMMIT) MeetingCreationProblem.RETIRE_BLOCKED_COMMIT else MeetingCreationProblem.RETIRE_BLOCKED_DISPATCH
                    is OperationRetirementResult.Conflict -> MeetingCreationProblem.RETIRE_CONFLICT
                    OperationRetirementResult.Unsupported -> MeetingCreationProblem.RETIRE_UNSUPPORTED
                    else -> MeetingCreationProblem.RETIRE_UNAVAILABLE
                }); return@exclusive
            }
            retirementProven = true; state = state.copy(retryReady = false, problem = MeetingCreationProblem.RETIRED)
        }
        if (deleteExact()) { stopped = true; pendingRaw = null; state = state.copy(discarded = true, retryReady = false) }
    }
    /** Commit acknowledgment is cleanup, not retirement and never another create. */
    suspend fun acknowledgeLocalSave() = exclusive {
        if (state.localSaved == null || stopped) return@exclusive
        if (deleteExact()) { stopped = true; state = state.copy(acknowledged = true) }
    }
    suspend fun flushDraft() = exclusive {
        val candidate = pendingRaw ?: return@exclusive
        if (!stopped && state.problem != MeetingCreationProblem.STALE_DRAFT && replace(candidate)) {
            pendingRaw = null
            if (!verified && candidate.authority != null) state = state.copy(problem = MeetingCreationProblem.SOURCE_CHANGED)
        }
    }
    suspend fun prepareDismiss(): Boolean = !action.get() && !state.loading && pendingRaw == null

    private suspend fun persistRaw(candidate: MeetingCreationDocument): Boolean {
        if(candidate == (pendingRaw ?: document)) return pendingRaw == null
        invalidateReview()
        pendingRaw = candidate
        state = state.copy(fields = candidate.fields, policyAccepted = candidate.policyAccepted,
            allowedGuestTypes = candidate.authority?.allowedTypes.orEmpty(),
            recurringCreation = candidate.authority?.recurringCreation ?: AccessDecision.UNKNOWN)
        if (state.problem == MeetingCreationProblem.STALE_DRAFT) return false
        return replace(candidate).also { if (it) pendingRaw = null }
    }

    private suspend fun dispatch(frozen: FrozenMeetingCreation) {
        if (stopped || retirementProven || !ownsRecord()) return
        // Local CAS adoption must finish after cancellation; provider dispatch must not begin.
        currentCoroutineContext().ensureActive()
        state = state.copy(recoveryRequired = true, retryReady = false, problem = null)
        val result = try { meetings.create(frozen.operationId, frozen.token, frozen.draft, frozen.guests) }
            catch (e: CancellationException) { state = state.copy(problem = MeetingCreationProblem.UNCERTAIN); throw e }
            catch (_: Exception) { null }
        applyResult(result, recovery = false)
    }
    private fun applyResult(result: MeetingMutationResult?, recovery: Boolean) {
        val frozen = document?.frozen ?: return
        state = state.copy(recoveryRequired = true, retryReady = false)
        when(result) {
            is MeetingMutationResult.LocalSaved -> {
                val bounds = when(val t = frozen.draft.time) {
                    is EventTime.Timed -> t.start to t.end
                    is EventTime.AllDay -> t.dates.start.atStartOfDay(ZoneOffset.UTC).toInstant() to t.dates.endExclusive.atStartOfDay(ZoneOffset.UTC).toInstant()
                }
                val ref = result.selected
                val scopeMatches = if(frozen.draft.recurrence == null) result.scope == RecurrenceScope.THIS && ref.recurrence == null
                    else result.scope == RecurrenceScope.ALL && ref.recurrence == RecurrenceRef(ref.eventId,bounds.first)
                if (scopeMatches && ref.eventId > 0 && ref.calendarId == frozen.draft.calendarId && ref.instanceStart == bounds.first && ref.instanceEnd == bounds.second)
                    state = state.copy(localSaved = ref, problem = null)
                else state = state.copy(problem = MeetingCreationProblem.UNCERTAIN)
            }
            is MeetingMutationResult.Common -> when(val outcome = result.outcome) {
                is MutationResult.SafeToRetry -> if (recovery && outcome.operationId == frozen.operationId && !retirementProven)
                    state = state.copy(problem = MeetingCreationProblem.SAFE_TO_RETRY, retryReady = true)
                    else state = state.copy(problem = MeetingCreationProblem.UNCERTAIN)
                is MutationResult.Retired -> if (outcome.operationId == frozen.operationId) {
                    retirementProven = true; state = state.copy(problem = MeetingCreationProblem.RETIRED)
                } else state = state.copy(problem = MeetingCreationProblem.UNCERTAIN)
                MutationResult.PermissionRequired -> state = state.copy(problem = MeetingCreationProblem.PERMISSION)
                is MutationResult.Denied -> state = state.copy(problem = MeetingCreationProblem.DENIED)
                is MutationResult.Conflict -> state = state.copy(problem = MeetingCreationProblem.SOURCE_CHANGED)
                else -> state = state.copy(problem = MeetingCreationProblem.UNCERTAIN)
            }
            is MeetingMutationResult.NeedsReview -> state = state.copy(problem = MeetingCreationProblem.SOURCE_CHANGED)
            is MeetingMutationResult.Invalid, is MeetingMutationResult.Unsupported -> state = state.copy(problem = MeetingCreationProblem.UNCERTAIN)
            else -> state = state.copy(problem = MeetingCreationProblem.UNCERTAIN)
        }
    }
    private suspend fun permission() = try { requestWritePermission() } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
    private suspend fun readSource(identity: CalendarIdentity): MeetingReadResult<MeetingCreation> = try { meetings.loadForCreate(identity) }
        catch (e: CancellationException) { throw e } catch (_: Exception) { MeetingReadResult.Unavailable }
    private fun readProblem(result: MeetingReadResult<*>) = when(result) {
        MeetingReadResult.PermissionRequired -> MeetingCreationProblem.PERMISSION
        is MeetingReadResult.Denied -> MeetingCreationProblem.DENIED
        MeetingReadResult.LimitExceeded -> MeetingCreationProblem.LIMIT_EXCEEDED
        is MeetingReadResult.NeedsReview -> MeetingCreationProblem.SOURCE_CHANGED
        else -> MeetingCreationProblem.UNAVAILABLE
    }
    private fun canAcceptRaw() = canChangeIgnoringBusy() || (!stopped && !state.loading && !frozen && pendingRaw != null &&
        expected != null && state.problem in setOf(MeetingCreationProblem.DRAFT_UNAVAILABLE, MeetingCreationProblem.STALE_DRAFT))
    private fun canChangeIgnoringBusy() = !stopped && !state.loading && document != null && !frozen && expected != null && state.problem !in setOf(MeetingCreationProblem.STALE_DRAFT, MeetingCreationProblem.DRAFT_UNAVAILABLE, MeetingCreationProblem.DRAFT_REVIEW)
    private sealed interface DraftLookup {
        data class Known(val record: DraftRecord?) : DraftLookup
        data object Unavailable : DraftLookup
    }
    private suspend fun lookup(): DraftLookup = try { DraftLookup.Known(store.load(launch.draftId)) }
        catch (e: CancellationException) { throw e } catch (_: Exception) { DraftLookup.Unavailable }
    private suspend fun ownsRecord(): Boolean {
        val current = try { store.load(launch.draftId) } catch (e: CancellationException) { throw e }
            catch (_: Exception) { state = state.copy(problem = MeetingCreationProblem.DRAFT_UNAVAILABLE, retryReady = false); return false }
        if (expected != null && current == expected) return true
        state = state.copy(problem = MeetingCreationProblem.STALE_DRAFT, retryReady = false); return false
    }
    private suspend fun replace(candidate: MeetingCreationDocument): Boolean = storage.withLock {
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            val old = expected ?: return@withContext false
            val next = candidate.copy(revision = uuid())
            val record = MeetingCreationDraftCodec.record(next, clock.instant())
            val result = try { store.compareAndSet(launch.draftId, old, record) } catch (_: Exception) { DraftChangeResult.Unavailable }
            if (result != DraftChangeResult.Changed && lookup() == DraftLookup.Known(record)) {
                expected = record; document = next
                state = state.copy(fields = next.fields, policyAccepted = next.policyAccepted,
                    allowedGuestTypes = next.authority?.allowedTypes.orEmpty(), problem = null, recoveryRequired = next.frozen != null,
                    recurringCreation = next.authority?.recurringCreation ?: AccessDecision.UNKNOWN)
                true
            } else { state = state.copy(problem = if(result == DraftChangeResult.Changed) MeetingCreationProblem.STALE_DRAFT else MeetingCreationProblem.DRAFT_UNAVAILABLE); false }
        }
    }
    private suspend fun deleteExact(): Boolean = storage.withLock { withContext(NonCancellable) {
        val old = expected ?: return@withContext false
        if (cleanupChanged) { state = state.copy(problem = MeetingCreationProblem.STALE_DRAFT); return@withContext false }
        // A later explicit action can confirm absence only for this exact earlier cleanup attempt.
        if (cleanupAttempt == old) when (val current = lookup()) {
            is DraftLookup.Known -> if (current.record == null) return@withContext completeCleanup()
                else if (current.record != old) { cleanupChanged = true; state = state.copy(problem = MeetingCreationProblem.STALE_DRAFT); return@withContext false }
            DraftLookup.Unavailable -> { state = state.copy(problem = MeetingCreationProblem.DRAFT_UNAVAILABLE); return@withContext false }
        }
        cleanupAttempt = old
        val result = try { store.compareAndSet(launch.draftId, old, null) } catch (_: Exception) { DraftChangeResult.Unavailable }
        if (result == DraftChangeResult.Changed) {
            cleanupChanged = true; state = state.copy(problem = MeetingCreationProblem.STALE_DRAFT); return@withContext false
        }
        when (val current = lookup()) {
            is DraftLookup.Known -> if (current.record == null) completeCleanup() else {
                cleanupChanged = current.record != old
                state = state.copy(problem = if (cleanupChanged) MeetingCreationProblem.STALE_DRAFT else MeetingCreationProblem.DRAFT_UNAVAILABLE); false
            }
            DraftLookup.Unavailable -> { state = state.copy(problem = MeetingCreationProblem.DRAFT_UNAVAILABLE); false }
        }
    } }
    private fun completeCleanup(): Boolean {
        expected = null; cleanupAttempt = null; cleanupChanged = false
        if (state.problem == MeetingCreationProblem.DRAFT_UNAVAILABLE) state = state.copy(problem = null)
        return true
    }
    private suspend fun exclusive(block: suspend () -> Unit) {
        if (!action.compareAndSet(false,true)) return
        state = state.copy(busy = true)
        try { block() } finally { state = state.copy(busy = false); action.set(false) }
    }
}
