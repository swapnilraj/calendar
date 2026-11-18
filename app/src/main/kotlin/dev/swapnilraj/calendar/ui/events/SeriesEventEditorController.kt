package dev.swapnilraj.calendar.ui.events

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

enum class SeriesEditorProblem { DRAFT_UNAVAILABLE, SOURCE_CHANGED, UNSUPPORTED, INVALID_INPUT, NO_CHANGES,
    PERMISSION, DENIED, UNAVAILABLE, NEEDS_REVIEW, UNCERTAIN, SAFE_TO_RETRY, RETRY_UNAVAILABLE }

data class SeriesWholeReview(val before: RecurrenceMasterTemplate, val after: RecurrenceMasterTemplate,
    val recurrence: RecurrenceSpec, val command: SeriesCommand.WholeEdit)

data class SeriesFollowingReview(val before: RecurrenceMasterTemplate, val after: RecurrenceMasterTemplate,
    val recurrence: RecurrenceSpec, val command: SeriesCommand.FollowingEdit, val boundary: RecurrenceKey, val earlierCount: Int)

data class SeriesEventEditorState(val fields: SeriesEditorFields = SeriesEditorFields(), val loading: Boolean = true,
    val saving: Boolean = false, val problem: SeriesEditorProblem? = null, val reviewRequired: Boolean = false,
    val recoveryRequired: Boolean = false, val retryReady: Boolean = false, val confirmDelete: Boolean = false,
    val result: SeriesMutationResult? = null, val draftReadFailed: Boolean = false, val reviewing: Boolean = false,
    val wholeReview: SeriesWholeReview? = null, val localOutcome: SeriesMutationResult? = null,
    val followingReview: SeriesFollowingReview? = null)

/** Current series drafts use exact record CAS. Submitted operations are never retired/reissued. */
class SeriesEventEditorController(val launch: SeriesEditorLaunch, val source: EditableSeries?,
    private val mutations: CalendarSeriesMutations, private val drafts: DraftStore, private val scope: CoroutineScope,
    val recoveryOnly: Boolean = source == null, private val clock: Clock = Clock.systemUTC(),
    private val requestWritePermission: (suspend () -> Boolean)? = null) {
    var state by mutableStateOf(SeriesEventEditorState())
        private set
    private val atomic = drafts as? AtomicDraftStore
    private var document: SeriesDraftDocument? = null
    private var expectedRecord: DraftRecord? = null
    private var persistence: Job? = null
    private var retired = false
    private val action = AtomicBoolean(false)
    private val storage = Mutex()
    private var reviewedFields: SeriesEditorFields? = null
    private var reviewedRevision: String? = null
    val operationId: String? get() = document?.operationId
    val hasDefaultAlerts: Boolean get() = document?.hasDefaultAlerts == true
    val masterStart: RecurrenceStart? get() = document?.masterStart
    internal val masterContext: SeriesMasterContext? get() = document?.context
    internal val editingContext: SeriesMasterContext? get() = document?.let { if (it.scope == RecurrenceScope.FOLLOWING && it.action == SeriesEditorAction.EDIT) it.context?.nominalTail(it.selected) else it.context }
    val deletionBoundary: RecurrenceKey? get() = document?.takeIf { it.scope == RecurrenceScope.FOLLOWING && it.action == SeriesEditorAction.DELETE }
        ?.let { seriesOriginalBoundary(it.selected, it.masterStart) }
    val fieldsAllowed: Set<EditableField> get() = document?.let { doc -> doc.rights.fields.intersect(doc.allowedFields)
        .intersect(seriesUiFields + if (doc.scope != RecurrenceScope.THIS) setOf(EditableField.TIME, EditableField.ZONE) else emptySet()) }.orEmpty()
    val temporalPresented: Boolean get() = launch.scope != RecurrenceScope.THIS && EditableField.TIME in fieldsAllowed && editingContext?.template != null
    val rulePresented: Boolean get() = launch.scope != RecurrenceScope.THIS && document?.rights?.recurrence == AccessDecision.ALLOWED && editingContext?.template != null && masterContext?.parsed != null
    val canChangeRule: Boolean get() = canChange && rulePresented
    val canChangeTime: Boolean get() = canChange && temporalPresented
    val canChangeZone: Boolean get() = canChangeTime && EditableField.ZONE in fieldsAllowed
    val canReviewDraft: Boolean get() = !retired && !state.reviewing && !state.saving && state.reviewRequired &&
        document?.frozen == null && document?.context?.calendar != null && !recoveryOnly && !state.draftReadFailed
    val canChange: Boolean get() = launch.action == SeriesEditorAction.EDIT && !locked()
    val hasChanges: Boolean get() = document?.let { doc -> try { val command = seriesCommand(doc.copy(fields = state.fields)); when (command) {
        is SeriesCommand.WholeEdit -> command.intent.fields != EventPatch() || command.intent.newMasterStart != FieldChange.Keep || command.intent.duration != RecurrenceDurationChange.KeepRaw || command.intent.rule != RecurrenceRuleChanges()
        is SeriesCommand.OccurrenceEdit -> command.patch != EventPatch()
        is SeriesCommand.FollowingEdit -> command.intent.fields != EventPatch() || command.intent.newTailStart != FieldChange.Keep || command.intent.duration != RecurrenceDurationChange.KeepRaw || command.intent.rule != RecurrenceRuleChanges()
        else -> false
    } } catch (_: Exception) { true } } ?: false

    init { scope.launch { restore() } }
    private suspend fun restore() {
        try {
            val saved = drafts.load(launch.draftId) ?: error("Missing draft")
            require(saved.id == launch.draftId)
            val loaded = SeriesDraftCodec.decode(saved)
            require(loaded.selected == launch.selected && loaded.scope == launch.scope && loaded.action == launch.action)
            require(loaded.generation == launch.generation)
            require(!recoveryOnly || loaded.submitted)
            require(loaded.schema == 1 || atomic != null)
            val changed = !loaded.submitted && (loaded.schema == 1 || loaded.reviewRequired || source == null || source.token != loaded.token ||
                source.selectedDetails.occurrence.ref != loaded.selected || source.snapshot.validatedSelection != loaded.selected ||
                source.snapshot.identity != loaded.identity || source.snapshot.start != loaded.masterStart ||
                source.scopes[loaded.scope] != loaded.rights || source.allowedFields != loaded.allowedFields ||
                loaded.context != SeriesMasterContext.from(source) || !canPrepareSeriesScope(source, loaded.scope, loaded.action))
            document = loaded; expectedRecord = saved
            state = state.copy(fields = loaded.fields, loading = false, recoveryRequired = loaded.submitted,
                reviewRequired = changed, problem = when { loaded.submitted -> SeriesEditorProblem.UNCERTAIN; changed -> SeriesEditorProblem.SOURCE_CHANGED; else -> null })
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { state = state.copy(loading = false, draftReadFailed = true, problem = SeriesEditorProblem.DRAFT_UNAVAILABLE) }
    }
    private fun locked() = recoveryOnly || retired || state.loading || state.saving || state.reviewing || state.draftReadFailed || state.reviewRequired || document?.frozen != null

    fun change(transform: (SeriesEditorFields) -> SeriesEditorFields) {
        if (!canChange) return
        val old = state.fields; val proposed = transform(old); val rights = fieldsAllowed
        var temporal = old.temporal
        if (canChangeTime && old.temporal != null && proposed.temporal != null) {
            val previous = old.temporal; val authored = proposed.temporal
            temporal = authored.copy(zone = if (canChangeZone) authored.zone else previous.zone)
            if (temporal.startDate != previous.startDate || temporal.startTime != previous.startTime || temporal.zone != previous.zone)
                temporal = temporal.copy(offset = "")
        }
        val next = proposed.copy(title = if (EditableField.TITLE in rights) proposed.title else old.title,
            location = if (EditableField.LOCATION in rights) proposed.location else old.location,
            description = if (EditableField.DESCRIPTION in rights) proposed.description else old.description,
            alerts = if (EditableField.ALERTS in rights) proposed.alerts else old.alerts,
            replaceAlerts = if (EditableField.ALERTS in rights) proposed.replaceAlerts else old.replaceAlerts,
            availability = if (EditableField.AVAILABILITY in rights) proposed.availability else old.availability,
            privacy = if (EditableField.PRIVACY in rights) proposed.privacy else old.privacy,
            temporal = temporal, recurrence = if (canChangeRule) proposed.recurrence else old.recurrence)
        // Check after rights/offset clamping: an ignored or repeated value does not change review.
        if (next == old) return
        reviewedFields = null; reviewedRevision = null
        state = state.copy(fields = next, wholeReview = null, followingReview = null, problem = null, result = null)
        persistence?.cancel(); persistence = scope.launch { delay(300); persistFields() }
    }
    fun changeRule(values: RecurrenceEditorFields) {
        val current = state.fields.recurrence ?: return
        if (!canChangeRule) return
        val old = current.values
        val patternChanged = values.frequency != old.frequency || values.weekdays != old.weekdays || values.monthlyMode != old.monthlyMode ||
            values.monthDay != old.monthDay || values.ordinal != old.ordinal || values.weekday != old.weekday || values.yearMonth != old.yearMonth || values.yearDay != old.yearDay
        change { it.copy(recurrence = current.copy(values = values, patternDirty = current.patternDirty || patternChanged,
            intervalDirty = current.intervalDirty || values.interval != old.interval, weekStartDirty = current.weekStartDirty || values.weekStart != old.weekStart,
            endDirty = current.endDirty || values.endMode != old.endMode || values.count != old.count || values.throughDate != old.throughDate)) }
    }
    suspend fun flushDraft(): Boolean {
        persistence?.cancelAndJoin()
        if (state.loading || state.reviewing || state.draftReadFailed || retired || state.reviewRequired) return false
        return if (document?.frozen != null) verifyLease() else persistFields()
    }
    suspend fun prepareDismiss(): Boolean {
        if (state.saving || state.reviewing) return false
        val closed = if (state.loading || state.draftReadFailed || retired || state.reviewRequired || recoveryOnly) true else flushDraft()
        // Retire this UI instance only; the submitted durable operation is retained for recovery.
        if (closed) retired = true
        return closed
    }

    private suspend fun replace(next: SeriesDraftDocument): Boolean = storage.withLock {
        if (retired || next.schema !in 2..3) return@withLock false
        val expected = expectedRecord ?: return@withLock false
        val replacement = SeriesDraftCodec.record(next.copy(revision = UUID.randomUUID().toString()), clock.instant())
        val result = try { atomic?.compareAndSet(launch.draftId, expected, replacement) ?: DraftChangeResult.Unavailable }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { DraftChangeResult.Unavailable }
        val applied = when (result) {
            DraftChangeResult.Applied -> true
            DraftChangeResult.Changed -> { stale(); false }
            DraftChangeResult.Unavailable -> try { drafts.load(launch.draftId) == replacement }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
        }
        if (applied) { document = SeriesDraftCodec.decode(replacement); expectedRecord = replacement }
        else if (!retired) state = state.copy(problem = SeriesEditorProblem.DRAFT_UNAVAILABLE)
        applied
    }
    private suspend fun persistFields(): Boolean {
        val doc = document ?: return false
        if (doc.schema == 1 || state.reviewRequired || doc.frozen != null) return verifyLease() && doc.submitted
        return replace(doc.copy(fields = state.fields))
    }
    private fun stale() { retired = true; state = state.copy(problem = SeriesEditorProblem.DRAFT_UNAVAILABLE, retryReady = false, wholeReview = null, followingReview = null) }
    private suspend fun verifyLease(): Boolean = if (retired) false else try {
        if (drafts.load(launch.draftId) == expectedRecord) true else { stale(); false }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { state = state.copy(problem = SeriesEditorProblem.DRAFT_UNAVAILABLE); false }

    fun save() {
        if (!canChange || !action.compareAndSet(false, true)) return
        if (!hasChanges) { action.set(false); state = state.copy(problem = SeriesEditorProblem.NO_CHANGES); return }
        val doc = document ?: run { action.set(false); return }
        val command = try { seriesCommand(doc.copy(fields = state.fields)) }
            catch (_: Exception) { action.set(false); state = state.copy(problem = SeriesEditorProblem.INVALID_INPUT); return }
        state = state.copy(saving = true, problem = null)
        scope.launch {
            try {
                persistence?.cancelAndJoin(); if (!persistFields()) return@launch
                if (command is SeriesCommand.WholeEdit && (command.intent.newMasterStart != FieldChange.Keep || command.intent.duration != RecurrenceDurationChange.KeepRaw || command.intent.rule != RecurrenceRuleChanges())) {
                    val review = wholeReview(command) ?: run { state = state.copy(problem = SeriesEditorProblem.NEEDS_REVIEW); return@launch }
                    reviewedFields = state.fields; reviewedRevision = document?.revision
                    state = state.copy(wholeReview = review)
                } else if (command is SeriesCommand.FollowingEdit) {
                    val review = followingReview(command) ?: run { state = state.copy(problem = SeriesEditorProblem.NEEDS_REVIEW); return@launch }
                    reviewedFields = state.fields; reviewedRevision = document?.revision
                    state = state.copy(followingReview = review)
                } else submitPrepared(command)
            } finally { state = state.copy(saving = false); action.set(false) }
        }
    }
    private fun wholeReview(command: SeriesCommand.WholeEdit): SeriesWholeReview? { return try {
        val context = document?.context ?: return null
        val live = source ?: return null
        val plan = RecurrenceEditing.plan(live.snapshot, launch.selected, command.intent) as? RecurrencePlanningResult.Ready ?: return null
        val update = plan.actions.singleOrNull() as? RecurrencePlanAction.UpdateMaster ?: return null
        val rule = (RecurrenceMasterTemplates.effectiveRule(live.snapshot, command.intent, update) as? RecurrenceMasterRuleResult.Ready)?.ruleRaw ?: return null
        val anchor = (command.intent.newMasterStart as? FieldChange.Set)?.value ?: context.start
        val duration = when (val change = command.intent.duration) { RecurrenceDurationChange.KeepRaw -> context.durationRaw
            is RecurrenceDurationChange.TimedElapsed -> "PT${change.duration.seconds}S"; is RecurrenceDurationChange.AllDayDays -> "P${change.days}D" }
        val after = (RecurrenceMasterTemplates.resolve(anchor, duration) as? RecurrenceMasterTemplateResult.Ready)?.template ?: return null
        val parsed = RecurrenceRules.parse(rule, anchor) as? ParsedRecurrence.Supported ?: return null
        SeriesWholeReview(requireNotNull(context.template), after, parsed.spec, command)
    } catch (_: Exception) { null } }
    fun dismissWholeReview() { reviewedFields = null; reviewedRevision = null; state = state.copy(wholeReview = null, followingReview = null) }
    fun confirmWholeReview() {
        val review = state.wholeReview ?: return
        if (!canChange || reviewedFields != state.fields || reviewedRevision != document?.revision || !action.compareAndSet(false, true)) return
        state = state.copy(saving = true, wholeReview = null, followingReview = null, problem = null)
        scope.launch { try { submitPrepared(review.command) } finally { state = state.copy(saving = false); action.set(false) } }
    }
    private fun followingReview(command: SeriesCommand.FollowingEdit): SeriesFollowingReview? = try {
        val live = source ?: error("Missing source")
        val context = requireNotNull(document?.context)
        val before = requireNotNull(context.nominalTail(launch.selected)?.template)
        val plan = RecurrenceEditing.plan(live.snapshot, launch.selected, command.intent) as? RecurrencePlanningResult.Ready ?: error("Review plan")
        val resolved = RecurrenceFollowingRules.resolve(live.snapshot, launch.selected, command.intent, plan)
        val nominal = requireNotNull(context.nominalTail(launch.selected)).start
        val anchor: RecurrenceStart
        val raw: String
        val earlier: Int
        when (resolved) {
            is RecurrenceFollowingRuleResult.Tail -> { anchor = resolved.tailStart; raw = resolved.tailRuleRaw; earlier = resolved.boundaryOrdinal }
            is RecurrenceFollowingRuleResult.Whole -> { anchor = (command.intent.newTailStart as? FieldChange.Set)?.value ?: nominal; raw = resolved.ruleRaw; earlier = 0 }
            is RecurrenceFollowingRuleResult.NeedsReview -> error("Unsupported tail")
        }
        val duration = when (val change = command.intent.duration) { RecurrenceDurationChange.KeepRaw -> context.durationRaw
            is RecurrenceDurationChange.TimedElapsed -> "PT${change.duration.seconds}S"; is RecurrenceDurationChange.AllDayDays -> "P${change.days}D" }
        val after = (RecurrenceMasterTemplates.resolve(anchor, duration) as? RecurrenceMasterTemplateResult.Ready)?.template ?: error("Tail interval")
        val parsed = RecurrenceRules.parse(raw, anchor) as? ParsedRecurrence.Supported ?: error("Tail rule")
        SeriesFollowingReview(before, after, parsed.spec, command, command.intent.expectedOriginalBoundary, earlier)
    } catch (_: Exception) { null }
    fun dismissFollowingReview() { reviewedFields = null; reviewedRevision = null; state = state.copy(followingReview = null) }
    fun confirmFollowingReview() {
        val review = state.followingReview ?: return
        if (!canChange || reviewedFields != state.fields || reviewedRevision != document?.revision || !action.compareAndSet(false, true)) return
        state = state.copy(saving = true, followingReview = null, problem = null)
        scope.launch { try { submitPrepared(review.command) } finally { state = state.copy(saving = false); action.set(false) } }
    }
    fun requestDelete() { if (launch.action == SeriesEditorAction.DELETE && !locked() && document?.rights?.delete == AccessDecision.ALLOWED) state = state.copy(confirmDelete = true) }
    fun cancelDelete() { state = state.copy(confirmDelete = false) }
    fun confirmDelete() {
        if (!state.confirmDelete || locked() || launch.action != SeriesEditorAction.DELETE || document?.rights?.delete != AccessDecision.ALLOWED || !action.compareAndSet(false, true)) return
        state = state.copy(saving = true, confirmDelete = false, problem = null)
        scope.launch { try { persistence?.cancelAndJoin(); if (persistFields()) submitPrepared(SeriesCommand.Delete(launch.scope)) }
            finally { state = state.copy(saving = false); action.set(false) } }
    }
    private suspend fun permission(): Boolean {
        val granted = try { requestWritePermission?.invoke() == true } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
        if (!granted) state = state.copy(problem = SeriesEditorProblem.PERMISSION)
        return granted
    }
    private suspend fun submitPrepared(command: SeriesCommand) {
        if (!verifyLease() || !permission()) return
        val doc = document ?: return
        if (doc.schema !in 2..3 || doc.frozen != null) return
        val frozen = FrozenSeriesSubmission(doc.operationId, doc.selected, doc.identity, doc.token, doc.scope, doc.action, command,
            if (doc.scope == RecurrenceScope.FOLLOWING && doc.action == SeriesEditorAction.DELETE) doc.masterStart else null,
            if (command is SeriesCommand.FollowingEdit) doc.masterStart else null)
        if (!replace(doc.copy(fields = state.fields, frozen = frozen, submitted = true))) return
        state = state.copy(recoveryRequired = true)
        dispatch(frozen)
    }
    private suspend fun dispatch(frozen: FrozenSeriesSubmission) {
        if (!verifyLease()) return
        currentCoroutineContext().ensureActive()
        val result = try { frozen.execute(mutations) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { SeriesMutationResult.Common(MutationResult.Uncertain(frozen.operationId, frozen.selected.eventId)) }
        finish(result)
    }
    fun recover() {
        val doc = document ?: return
        if (doc.frozen == null || retired || state.loading || state.saving || state.draftReadFailed || !action.compareAndSet(false, true)) return
        state = state.copy(saving = true, retryReady = false, problem = null)
        scope.launch { try {
            if (!verifyLease()) return@launch
            val result = try { mutations.recover(doc.operationId) } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { SeriesMutationResult.Common(MutationResult.Uncertain(doc.operationId, doc.selected.eventId)) }
            if (verifyLease()) finish(result, recovery = true)
        } finally { state = state.copy(saving = false); action.set(false) } }
    }
    fun retryOriginal() {
        val frozen = document?.frozen ?: return
        if (!state.retryReady || retired || state.loading || state.saving || state.draftReadFailed || !action.compareAndSet(false, true)) return
        state = state.copy(saving = true, retryReady = false, problem = null)
        scope.launch { try {
            if (!verifyLease()) return@launch
            if (!permission()) { state = state.copy(retryReady = true); return@launch }
            dispatch(frozen)
        } finally { state = state.copy(saving = false); action.set(false) } }
    }
    private suspend fun finish(received: SeriesMutationResult, recovery: Boolean = false) {
        val doc = document ?: return
        val successMatches = when (received) {
            is SeriesMutationResult.LocalSaved -> doc.action == SeriesEditorAction.EDIT &&
                if (doc.scope == RecurrenceScope.FOLLOWING) followingResultMatches(doc, received) else received.identity == doc.identity
            is SeriesMutationResult.LocalDeleted -> doc.action == SeriesEditorAction.DELETE && received.identity == doc.identity && received.scope == doc.scope && received.selected == doc.selected
            else -> true
        }
        val outcome = (received as? SeriesMutationResult.Common)?.outcome
        val proof = recovery && outcome is MutationResult.SafeToRetry && outcome.operationId == doc.operationId
        val result = if (!successMatches || outcome is MutationResult.SafeToRetry && !proof) SeriesMutationResult.Common(MutationResult.Uncertain(doc.operationId, doc.selected.eventId)) else received
        val success = result is SeriesMutationResult.LocalSaved || result is SeriesMutationResult.LocalDeleted
        if (success) {
            state = state.copy(localOutcome = result)
            val expected = expectedRecord ?: return
            val removed = removeExact(expected)
            retired = true
            state = state.copy(result = if (removed) result else null, saving = false, recoveryRequired = !removed,
                problem = if (removed) null else SeriesEditorProblem.DRAFT_UNAVAILABLE)
            return
        }
        state = state.copy(saving = false, recoveryRequired = true, retryReady = proof, wholeReview = null, followingReview = null, result = result,
            problem = when {
                proof -> SeriesEditorProblem.SAFE_TO_RETRY
                result is SeriesMutationResult.PlanningReview -> SeriesEditorProblem.NEEDS_REVIEW
                result is SeriesMutationResult.Unsupported -> SeriesEditorProblem.UNSUPPORTED
                result is SeriesMutationResult.Invalid -> SeriesEditorProblem.INVALID_INPUT
                result is SeriesMutationResult.Common -> when (result.outcome) {
                    MutationResult.PermissionRequired -> SeriesEditorProblem.PERMISSION
                    is MutationResult.Denied -> SeriesEditorProblem.DENIED
                    is MutationResult.Conflict -> SeriesEditorProblem.SOURCE_CHANGED
                    is MutationResult.ValidationFailed -> SeriesEditorProblem.INVALID_INPUT
                    else -> SeriesEditorProblem.UNCERTAIN
                }
                else -> SeriesEditorProblem.UNCERTAIN
            })
    }
    /** Following edits may allocate a new master. Prove the scope from the frozen original context,
     * including during source-free recovery, without predicting a provider-generated tail ID. */
    private fun followingResultMatches(doc: SeriesDraftDocument, result: SeriesMutationResult.LocalSaved): Boolean { return try {
        val frozen = requireNotNull(doc.frozen)
        val command = frozen.command as? SeriesCommand.FollowingEdit ?: return false
        val context = requireNotNull(doc.context)
        require(frozen.scope == RecurrenceScope.FOLLOWING && frozen.action == SeriesEditorAction.EDIT)
        require(frozen.followingMasterStart == doc.masterStart && context.start == doc.masterStart)
        require(context.calendar.calendarId == doc.identity.calendarId)
        val boundary = requireNotNull(seriesOriginalBoundary(doc.selected, doc.masterStart))
        require(command.intent.expectedOriginalBoundary == boundary)
        val raw = requireNotNull(context.ruleRaw)
        val mapping = RecurrenceOrdinalMapping.map(raw, context.start, boundary, raw, context.start) as? RecurrenceOrdinalResult.Ready ?: return false
        require(mapping.occurrence != null)
        if (result.identity.calendarId != doc.identity.calendarId) return false
        if (mapping.ordinal == 0) {
            if (result.identity != doc.identity) return false
        } else if (result.identity.masterEventId <= 0 || result.identity.masterEventId == doc.identity.masterEventId) return false
        // The bounded following adapter verifies its first native template and always returns it.
        // Generic whole-series ordinal exhaustion may return null; this scope never does.
        val selected = requireNotNull(result.selected)
        val recurrence = requireNotNull(selected.recurrence)
        val start = (command.intent.newTailStart as? FieldChange.Set)?.value ?: requireNotNull(context.nominalTail(doc.selected)).start
        val duration = when (val change = command.intent.duration) {
            RecurrenceDurationChange.KeepRaw -> context.durationRaw
            is RecurrenceDurationChange.TimedElapsed -> "PT${change.duration.seconds}S"
            is RecurrenceDurationChange.AllDayDays -> "P${change.days}D"
        }
        val template = (RecurrenceMasterTemplates.resolve(start, duration) as? RecurrenceMasterTemplateResult.Ready)?.template ?: return false
        val bounds = when (val time = template.time) {
            is EventTime.Timed -> time.start to time.end
            is EventTime.AllDay -> time.dates.start.atStartOfDay(java.time.ZoneOffset.UTC).toInstant() to
                time.dates.endExclusive.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
        }
        require(selected.calendarId == result.identity.calendarId && selected.eventId == result.identity.masterEventId &&
            recurrence.masterEventId == result.identity.masterEventId && recurrence.originalStart == bounds.first &&
            selected.instanceStart == bounds.first && selected.instanceEnd == bounds.second)
        true
    } catch (_: Exception) { false } }
    private suspend fun removeExact(expected: DraftRecord): Boolean = storage.withLock {
        val result = try { atomic?.compareAndSet(launch.draftId, expected, null) ?: DraftChangeResult.Unavailable }
            catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { DraftChangeResult.Unavailable }
        when (result) {
            DraftChangeResult.Applied -> true
            DraftChangeResult.Changed -> { stale(); false }
            DraftChangeResult.Unavailable -> try { drafts.load(launch.draftId) == null } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
        }
    }
    suspend fun reloadForReview(): SeriesReadResult {
        if (retired || document?.frozen != null) return SeriesReadResult.Unavailable
        return try { mutations.loadForEdit(launch.selected) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { SeriesReadResult.Unavailable }
    }
    suspend fun discardAndReload(): SeriesReadResult? {
        if (!canReviewDraft || !action.compareAndSet(false, true)) return null
        state = state.copy(reviewing = true)
        try {
            persistence?.cancelAndJoin()
            val latest = reloadForReview()
            if (latest !is SeriesReadResult.Ready) return latest
            if (latest.series.selectedDetails.occurrence.ref != launch.selected || latest.series.snapshot.validatedSelection != launch.selected) return SeriesReadResult.Unavailable
            // The live constructor source may already be a reused ID in another account. Only
            // the ORIGINAL durable namespace can authorize continuing this draft's review flow.
            val original = document?.context?.calendar ?: return null
            if (latest.series.selectedDetails.calendar?.identity != original || latest.series.snapshot.identity != document?.identity) {
                state = state.copy(problem = SeriesEditorProblem.SOURCE_CHANGED)
                return SeriesReadResult.NeedsReview("The original source namespace is no longer available")
            }
            val expected = expectedRecord ?: return null
            if (!removeExact(expected)) { state = state.copy(problem = SeriesEditorProblem.DRAFT_UNAVAILABLE); return null }
            retired = true
            return latest
        } finally { state = state.copy(reviewing = false); action.set(false) }
    }
}
