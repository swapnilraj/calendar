package dev.swapnilraj.calendar.core

import java.util.Collections

/** Requests for semantic conservation only; none of these values authorize provider execution. */
sealed interface RecurrenceGraphRequest {
    data class Scoped(val scope: RecurrenceScope, val mutation: RecurrenceMutation) : RecurrenceGraphRequest
    data class Edited(val intent: RecurrenceEditIntent) : RecurrenceGraphRequest
}

enum class RecurrenceGraphProblem {
    INCOMPLETE_SOURCE, INVALID_SELECTION, SOURCE_GRAPH_INVALID, INPUT_BUDGET,
    PLANNING_NOT_READY, PLAN_MISMATCH, FOREIGN_EXCEPTION, DUPLICATE_EXCEPTION_ACTION,
    MISSING_EXCEPTION_ACTION, TARGET_PARENT_MISSING, TARGET_ORIGINAL_COLLISION,
    EXCLUSION_MAPPING_UNPROVED, EXCEPTION_MAPPING_UNPROVED, RDATE_MAPPING_UNPROVED,
}

sealed interface RecurrenceGraphProofResult {
    data class Complete(val proof: RecurrenceGraphProofValue) : RecurrenceGraphProofResult
    data class NeedsReview(val problem: RecurrenceGraphProblem) : RecurrenceGraphProofResult
}

/** Source actual bounds/cancellation remain evidence; authored deltas never replace that evidence. */
sealed interface RecurrenceExceptionDisposition {
    val source: SeriesException
    data class Retain(override val source: SeriesException, val explicitPatch: EventPatch = EventPatch(),
        val cancellation: FieldChange<Boolean> = FieldChange.Keep) : RecurrenceExceptionDisposition
    data class Rebind(override val source: SeriesException, val parent: SeriesTarget, val original: RecurrenceKey,
        val explicitPatch: EventPatch = EventPatch()) : RecurrenceExceptionDisposition
    data class Remap(override val source: SeriesException, val parent: SeriesTarget, val original: RecurrenceKey,
        val explicitPatch: EventPatch = EventPatch()) : RecurrenceExceptionDisposition
    data class Remove(override val source: SeriesException) : RecurrenceExceptionDisposition
}

data class RecurrenceNewExceptionProof(val parent: SeriesTarget, val original: RecurrenceKey,
    val explicitPatch: EventPatch, val cancelled: Boolean)

sealed interface RecurrenceExclusionProof {
    data class PreserveRaw(val raw: String?, val decoded: Boolean, val knownKeys: Set<RecurrenceKey>) : RecurrenceExclusionProof
    /** Total semantic association; native serialization/representability is deliberately unproved. */
    data class Transform(val sourceRaw: String?, val mappings: List<RecurrenceExclusionMapping>) : RecurrenceExclusionProof
}

/** Both target values are null only when an explicitly deleted portion removes this exclusion. */
data class RecurrenceExclusionMapping(val source: RecurrenceKey, val targetParent: SeriesTarget?, val targetOriginal: RecurrenceKey?)
data class RecurrenceRawDateProvenance(val rDatesRaw: String?, val exDatesRaw: String?, val exRuleRaw: String?)

class RecurrenceGraphProofValue internal constructor(
    val identity: SeriesIdentity, val selected: EventRef, val plan: RecurrencePlanningResult.Ready,
    val exceptions: List<RecurrenceExceptionDisposition>, val newExceptions: List<RecurrenceNewExceptionProof>,
    val exclusions: RecurrenceExclusionProof, val rawDates: RecurrenceRawDateProvenance,
)

/**
 * Closes the finite exception/exclusion graph around EXISTING planners. Complete is not native
 * preservation, clone admission, a receipt, or a capability. Existing production guards stay intact.
 */
object RecurrenceGraphProof {
    /** Aggregate input cells, including repeated aliases; defensive copies have bounded overhead. */
    internal const val MAX_COPIED_ELEMENTS = 100_000
    fun prove(snapshot: RecurrenceSeriesSnapshot, selected: EventRef, request: RecurrenceGraphRequest,
        plan: RecurrencePlanningResult.Ready, budget: RecurrencePlanningBudget = RecurrencePlanningBudget()): RecurrenceGraphProofResult = try {
        if (snapshot.coverage != SeriesCoverage.COMPLETE) fail(RecurrenceGraphProblem.INCOMPLETE_SOURCE)
        if (snapshot.validatedSelection != selected || selected.recurrence?.masterEventId != snapshot.identity.masterEventId ||
            selected.calendarId != snapshot.identity.calendarId) fail(RecurrenceGraphProblem.INVALID_SELECTION)
        if (snapshot.exceptions.size > budget.maxExceptions || snapshot.exDates.size > budget.maxInstances ||
            plan.actions.size > budget.maxExceptions + 2 ||
            listOfNotNull(snapshot.ruleRaw, snapshot.rDatesRaw, snapshot.exDatesRaw, snapshot.exRuleRaw).any { it.length > 1_000_000 })
            fail(RecurrenceGraphProblem.INPUT_BUDGET)
        admitCopyWork(snapshot, request, plan)
        // Copies include nested mutable patch reminder lists and weekly weekday sets, not just the
        // outer action list. Neither mutation of the caller nor a cast of our output can alter proof.
        val source = snapshot.copy(exceptions = frozenList(snapshot.exceptions), exDates = frozenSet(snapshot.exDates))
        source.exceptions.firstOrNull { it.ref.eventId == selected.eventId }?.let {
            if (it.ref != selected) fail(RecurrenceGraphProblem.INVALID_SELECTION)
        }
        val command = copyRequest(request, budget)
        val supplied = copyPlan(plan, budget)
        if (source.exceptions.map { it.ref.eventId }.toSet().size != source.exceptions.size ||
            source.exceptions.map { it.original }.toSet().size != source.exceptions.size)
            fail(RecurrenceGraphProblem.SOURCE_GRAPH_INVALID)
        if (RecurrencePlanning.plan(source, selected, RecurrenceScope.ALL, RecurrenceMutation.Edit(EventPatch()), budget)
            !is RecurrencePlanningResult.Ready) fail(RecurrenceGraphProblem.SOURCE_GRAPH_INVALID)
        if (!source.rDatesRaw.isNullOrEmpty() && transformsDates(command)) fail(RecurrenceGraphProblem.RDATE_MAPPING_UNPROVED)
        if (!source.exRuleRaw.isNullOrEmpty() && transformsDates(command)) fail(RecurrenceGraphProblem.PLANNING_NOT_READY)
        if (supplied.series != source.identity) fail(RecurrenceGraphProblem.PLAN_MISMATCH)

        val byId = source.exceptions.associateBy { it.ref.eventId }
        val dispositions = linkedMapOf<Long, RecurrenceExceptionDisposition>()
        val inserted = mutableListOf<RecurrenceNewExceptionProof>()
        val tails = supplied.actions.filterIsInstance<RecurrencePlanAction.InsertTail>()
        if (tails.size > 1) fail(RecurrenceGraphProblem.PLAN_MISMATCH)
        fun parent(value: SeriesTarget) {
            if (value == SeriesTarget.TAIL && tails.size != 1) fail(RecurrenceGraphProblem.TARGET_PARENT_MISSING)
        }
        fun known(value: SeriesException): SeriesException = byId[value.ref.eventId]?.takeIf { it == value }
            ?: fail(RecurrenceGraphProblem.FOREIGN_EXCEPTION)
        fun put(value: RecurrenceExceptionDisposition) {
            known(value.source)
            if (dispositions.put(value.source.ref.eventId, value) != null) fail(RecurrenceGraphProblem.DUPLICATE_EXCEPTION_ACTION)
        }
        for (action in supplied.actions) when (action) {
            is RecurrencePlanAction.UpsertException -> {
                parent(action.target)
                val ref = action.existing
                if (ref == null) inserted += RecurrenceNewExceptionProof(action.target, action.original, action.patch, action.cancelled)
                else {
                    val value = byId[ref.eventId]?.takeIf { it.ref == ref && it.original == action.original }
                        ?: fail(RecurrenceGraphProblem.FOREIGN_EXCEPTION)
                    if (action.target != SeriesTarget.EXISTING) fail(RecurrenceGraphProblem.PLAN_MISMATCH)
                    put(RecurrenceExceptionDisposition.Retain(value, action.patch,
                        if (action.cancelled) FieldChange.Set(true) else FieldChange.Keep))
                }
            }
            is RecurrencePlanAction.RebindExceptionToTail -> {
                parent(SeriesTarget.TAIL)
                put(RecurrenceExceptionDisposition.Rebind(known(action.exception), SeriesTarget.TAIL,
                    action.exception.original, action.selectedPatch))
            }
            is RecurrencePlanAction.RemapException -> {
                parent(action.target)
                put(RecurrenceExceptionDisposition.Remap(known(action.exception), action.target, action.newOriginal, action.selectedPatch))
            }
            is RecurrencePlanAction.RemoveException -> put(RecurrenceExceptionDisposition.Remove(known(action.exception)))
            is RecurrencePlanAction.DeleteSeries -> action.exceptions.forEach { put(RecurrenceExceptionDisposition.Remove(known(it))) }
            is RecurrencePlanAction.UpdateMaster, is RecurrencePlanAction.InsertTail -> Unit
        }
        val all = source.exceptions.map { dispositions[it.ref.eventId] ?: RecurrenceExceptionDisposition.Retain(it) }
        val targets = mutableSetOf<Pair<SeriesTarget, RecurrenceKey>>()
        for (value in all) {
            val target = when (value) {
                is RecurrenceExceptionDisposition.Retain -> SeriesTarget.EXISTING to value.source.original
                is RecurrenceExceptionDisposition.Rebind -> value.parent to value.original
                is RecurrenceExceptionDisposition.Remap -> value.parent to value.original
                is RecurrenceExceptionDisposition.Remove -> null
            }
            if (target != null && !targets.add(target)) fail(RecurrenceGraphProblem.TARGET_ORIGINAL_COLLISION)
        }
        inserted.forEach { if (!targets.add(it.parent to it.original)) fail(RecurrenceGraphProblem.TARGET_ORIGINAL_COLLISION) }

        val computed = when (command) {
            is RecurrenceGraphRequest.Scoped -> RecurrencePlanning.plan(source, selected, command.scope, command.mutation, budget)
            is RecurrenceGraphRequest.Edited -> RecurrenceEditing.plan(source, selected, command.intent, budget)
        } as? RecurrencePlanningResult.Ready ?: fail(RecurrenceGraphProblem.PLANNING_NOT_READY)
        // Omitting a required transfer/remap/removal must not silently become the Retain default.
        for (action in computed.actions) for (id in touched(action)) {
            if (id !in dispositions) fail(RecurrenceGraphProblem.MISSING_EXCEPTION_ACTION)
        }
        if (supplied != computed) fail(RecurrenceGraphProblem.PLAN_MISMATCH)
        val ordinals = OrdinalProof(source, selected, supplied, budget)
        all.forEach { value -> when (value) {
            is RecurrenceExceptionDisposition.Rebind -> ordinals.verify(value.source.original, value.parent, value.original,
                RecurrenceGraphProblem.EXCEPTION_MAPPING_UNPROVED)
            is RecurrenceExceptionDisposition.Remap -> ordinals.verify(value.source.original, value.parent, value.original,
                RecurrenceGraphProblem.EXCEPTION_MAPPING_UNPROVED)
            else -> Unit // Untouched ALL metadata does not reinterpret the original schedule.
        } }
        val exclusions = exclusions(source, selected, supplied, command, budget, ordinals)
        RecurrenceGraphProofResult.Complete(RecurrenceGraphProofValue(source.identity, selected, supplied,
            frozenList(all), frozenList(inserted), exclusions,
            RecurrenceRawDateProvenance(source.rDatesRaw, source.exDatesRaw, source.exRuleRaw)))
    } catch (problem: ProofFailure) { RecurrenceGraphProofResult.NeedsReview(problem.problem) }
    catch (_: Exception) { RecurrenceGraphProofResult.NeedsReview(RecurrenceGraphProblem.SOURCE_GRAPH_INVALID) }

    private fun exclusions(source: RecurrenceSeriesSnapshot, selected: EventRef,
        plan: RecurrencePlanningResult.Ready, request: RecurrenceGraphRequest,
        budget: RecurrencePlanningBudget, ordinals: OrdinalProof): RecurrenceExclusionProof {
        val updates = plan.actions.filterIsInstance<RecurrencePlanAction.UpdateMaster>()
        if (updates.size > 1) fail(RecurrenceGraphProblem.PLAN_MISMATCH)
        val tail = plan.actions.filterIsInstance<RecurrencePlanAction.InsertTail>().singleOrNull()
        val removed = plan.actions.any { it is RecurrencePlanAction.DeleteSeries }
        val change = updates.singleOrNull()?.exclusions ?: FieldChange.Keep
        if (!removed && change == FieldChange.Keep && tail == null)
            return RecurrenceExclusionProof.PreserveRaw(source.exDatesRaw, source.exDatesDecoded, source.exDates)
        if (!source.exDatesDecoded && (!source.exDatesRaw.isNullOrEmpty() || source.exDates.isNotEmpty()))
            fail(RecurrenceGraphProblem.EXCLUSION_MAPPING_UNPROVED)
        val decoded = RecurrenceDateCodec.parse(source.exDatesRaw, source.start,
            RecurrenceDateBudget(maxValues = budget.maxInstances),
            AllDayDateTimePolicy.PROVIDER_UTC_MIDNIGHT) as? ParsedRecurrenceDates.Supported
            ?: fail(RecurrenceGraphProblem.EXCLUSION_MAPPING_UNPROVED)
        if (decoded.keys != source.exDates || source.exDatesRaw?.lineSequence()?.any { it.startsWith("RDATE", ignoreCase = true) } == true)
            fail(RecurrenceGraphProblem.EXCLUSION_MAPPING_UNPROVED)
        val result = mutableListOf<RecurrenceExclusionMapping>()
        if (removed) source.exDates.sortedWith(keyOrder).forEach { result += RecurrenceExclusionMapping(it, null, null) }
        else {
            val existing = (change as? FieldChange.Set)?.value ?: source.exDates
            val boundary = original(source.start, requireNotNull(selected.recurrence).originalStart)
            // Order only proposes an association. The existing strict ordinal mapper must also
            // prove it, including clock certainty while traversing intervening candidates.
            if (tail != null) {
                val past = source.exDates.filter { keyOrder.compare(it, boundary) < 0 }.toSet()
                if (existing != past) fail(RecurrenceGraphProblem.EXCLUSION_MAPPING_UNPROVED)
                past.sortedWith(keyOrder).forEach { result += RecurrenceExclusionMapping(it, SeriesTarget.EXISTING, it) }
                associate(source.exDates - past, tail.exclusions, SeriesTarget.TAIL, result)
            } else if (request is RecurrenceGraphRequest.Scoped && request.scope == RecurrenceScope.FOLLOWING &&
                request.mutation == RecurrenceMutation.Delete) {
                // FOLLOWING deletion may have zero future exception rows; exclusions still need
                // one explicit disposition each, including suppressed originals with no row.
                source.exDates.sortedWith(keyOrder).forEach {
                    result += if (it in existing) RecurrenceExclusionMapping(it, SeriesTarget.EXISTING, it)
                    else RecurrenceExclusionMapping(it, null, null)
                }
            } else associate(source.exDates, existing, SeriesTarget.EXISTING, result)
        }
        if (result.map { it.source }.toSet() != source.exDates || result.size != source.exDates.size)
            fail(RecurrenceGraphProblem.EXCLUSION_MAPPING_UNPROVED)
        result.forEach {
            if (it.targetParent != null && it.targetOriginal != null)
                ordinals.verify(it.source, it.targetParent, it.targetOriginal)
            else if (!removed) ordinals.sourceOrdinal(it.source)
        }
        return RecurrenceExclusionProof.Transform(source.exDatesRaw, frozenList(result))
    }

    /** Shared strict mapper, bounded across the graph, not an independent recurrence engine. */
    private class OrdinalProof(val source: RecurrenceSeriesSnapshot, val selected: EventRef,
        val plan: RecurrencePlanningResult.Ready, val budget: RecurrencePlanningBudget) {
        private data class Key(val raw: String, val start: RecurrenceStart, val original: RecurrenceKey)
        private val cache = mutableMapOf<Key, Int>()
        private var remaining = 100_000
        private fun ordinal(raw: String, start: RecurrenceStart, original: RecurrenceKey, problem: RecurrenceGraphProblem): Int {
            val key = Key(raw, start, original)
            cache[key]?.let { return it }
            val limit = minOf(budget.maxInstances, RecurrenceOrdinalMapping.MAX_CANDIDATES_PER_SIDE, remaining / 2)
            if (limit < 1) fail(problem)
            val mapped = RecurrenceOrdinalMapping.map(raw, start, original, raw, start, limit)
                as? RecurrenceOrdinalResult.Ready ?: fail(problem)
            val occurrence = when (val value = mapped.occurrence) {
                is RecurrenceOccurrence.AllDay -> RecurrenceKey.Date(value.date)
                is RecurrenceOccurrence.Timed -> RecurrenceKey.Timed(value.instant)
                null -> fail(problem)
            }
            if (occurrence != original) fail(problem)
            remaining -= 2 * (mapped.ordinal + 1)
            cache[key] = mapped.ordinal
            return mapped.ordinal
        }
        fun sourceOrdinal(original: RecurrenceKey,
            problem: RecurrenceGraphProblem = RecurrenceGraphProblem.EXCLUSION_MAPPING_UNPROVED): Int = ordinal(
            source.ruleRaw ?: fail(problem), source.start, original, problem)
        fun verify(before: RecurrenceKey, parent: SeriesTarget, after: RecurrenceKey,
            problem: RecurrenceGraphProblem = RecurrenceGraphProblem.EXCLUSION_MAPPING_UNPROVED) {
            val old = sourceOrdinal(before, problem)
            val update = plan.actions.filterIsInstance<RecurrencePlanAction.UpdateMaster>().singleOrNull()
            val targetStart: RecurrenceStart
            val targetRule: String
            val cut: Int
            if (parent == SeriesTarget.TAIL) {
                val tail = plan.actions.filterIsInstance<RecurrencePlanAction.InsertTail>().singleOrNull()
                    ?: fail(problem)
                targetStart = tail.start; targetRule = tail.rule
                cut = sourceOrdinal(original(source.start, requireNotNull(selected.recurrence).originalStart), problem)
            } else {
                targetStart = (update?.anchor as? FieldChange.Set)?.value ?: source.start
                targetRule = (update?.rule as? FieldChange.Set)?.value ?: source.ruleRaw
                    ?: fail(problem)
                cut = 0
            }
            if (old < cut || ordinal(targetRule, targetStart, after, problem) != old - cut)
                fail(problem)
        }
    }

    private fun associate(old: Set<RecurrenceKey>, new: Set<RecurrenceKey>, parent: SeriesTarget,
        output: MutableList<RecurrenceExclusionMapping>) {
        if (old.size != new.size) fail(RecurrenceGraphProblem.EXCLUSION_MAPPING_UNPROVED)
        old.sortedWith(keyOrder).zip(new.sortedWith(keyOrder)).forEach { (before, after) ->
            output += RecurrenceExclusionMapping(before, parent, after)
        }
    }
    private fun transformsDates(request: RecurrenceGraphRequest): Boolean = when (request) {
        is RecurrenceGraphRequest.Scoped -> request.scope == RecurrenceScope.FOLLOWING
        is RecurrenceGraphRequest.Edited -> when (val intent = request.intent) {
            is RecurrenceEditIntent.FollowingSeriesEdit -> true
            is RecurrenceEditIntent.WholeSeriesEdit -> intent.newMasterStart != FieldChange.Keep ||
                intent.duration != RecurrenceDurationChange.KeepRaw || intent.rule != RecurrenceRuleChanges()
        }
    }
    private fun touched(action: RecurrencePlanAction): List<Long> = when (action) {
        is RecurrencePlanAction.UpsertException -> listOfNotNull(action.existing?.eventId)
        is RecurrencePlanAction.RebindExceptionToTail -> listOf(action.exception.ref.eventId)
        is RecurrencePlanAction.RemapException -> listOf(action.exception.ref.eventId)
        is RecurrencePlanAction.RemoveException -> listOf(action.exception.ref.eventId)
        is RecurrencePlanAction.DeleteSeries -> action.exceptions.map { it.ref.eventId }
        else -> emptyList()
    }
    private val keyOrder = Comparator<RecurrenceKey> { a, b -> when {
        a is RecurrenceKey.Date && b is RecurrenceKey.Date -> a.date.compareTo(b.date)
        a is RecurrenceKey.Timed && b is RecurrenceKey.Timed -> a.instant.compareTo(b.instant)
        else -> fail(RecurrenceGraphProblem.SOURCE_GRAPH_INVALID)
    } }
    private fun original(start: RecurrenceStart, instant: java.time.Instant): RecurrenceKey = when (start) {
        is RecurrenceStart.AllDay -> RecurrenceKey.Date(instant.atOffset(java.time.ZoneOffset.UTC).toLocalDate())
        is RecurrenceStart.Timed -> RecurrenceKey.Timed(instant)
    }
    private class ProofFailure(val problem: RecurrenceGraphProblem) : RuntimeException()
    private fun fail(problem: RecurrenceGraphProblem): Nothing = throw ProofFailure(problem)
    private fun <T> frozenList(values: Collection<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
    private fun <T> frozenSet(values: Collection<T>): Set<T> = Collections.unmodifiableSet(LinkedHashSet(values))
    /** Inspect nested sizes before copying ANY contents, so shared lists cannot amplify allocation. */
    private fun admitCopyWork(source: RecurrenceSeriesSnapshot, request: RecurrenceGraphRequest,
        plan: RecurrencePlanningResult.Ready) {
        var remaining = MAX_COPIED_ELEMENTS.toLong()
        fun cells(count: Int) {
            if (count < 0 || count.toLong() > remaining) fail(RecurrenceGraphProblem.INPUT_BUDGET)
            remaining -= count
        }
        fun patchSize(value: EventPatch) { (value.alertMinutes as? FieldChange.Set)?.let { cells(it.value.size) } }
        fun patternSize(value: RecurrencePattern) { if (value is RecurrencePattern.Weekly) cells(value.days.size) }
        fun ruleSize(value: RecurrenceRuleChanges) { (value.pattern as? FieldChange.Set)?.let { patternSize(it.value) } }
        cells(source.exceptions.size); cells(source.exDates.size); cells(plan.actions.size)
        when (request) {
            is RecurrenceGraphRequest.Scoped -> (request.mutation as? RecurrenceMutation.Edit)?.let {
                patchSize(it.patch)
                (it.replacement as? FieldChange.Set)?.value?.let { spec -> patternSize(spec.pattern) }
            }
            is RecurrenceGraphRequest.Edited -> when (val intent = request.intent) {
                is RecurrenceEditIntent.WholeSeriesEdit -> { patchSize(intent.fields); ruleSize(intent.rule) }
                is RecurrenceEditIntent.FollowingSeriesEdit -> {
                    patchSize(intent.fields); patchSize(intent.selectedExceptionPatch); ruleSize(intent.rule)
                }
            }
        }
        plan.actions.forEach { action -> when (action) {
            is RecurrencePlanAction.UpdateMaster -> {
                patchSize(action.patch); (action.exclusions as? FieldChange.Set)?.let { cells(it.value.size) }
            }
            is RecurrencePlanAction.InsertTail -> { patchSize(action.patch); cells(action.exclusions.size) }
            is RecurrencePlanAction.UpsertException -> patchSize(action.patch)
            is RecurrencePlanAction.RebindExceptionToTail -> patchSize(action.selectedPatch)
            is RecurrencePlanAction.RemapException -> patchSize(action.selectedPatch)
            is RecurrencePlanAction.DeleteSeries -> cells(action.exceptions.size)
            is RecurrencePlanAction.RemoveException -> Unit
        } }
    }
    private fun patch(value: EventPatch, budget: RecurrencePlanningBudget): EventPatch = value.copy(alertMinutes = when (val alerts = value.alertMinutes) {
        FieldChange.Keep -> FieldChange.Keep
        is FieldChange.Set -> {
            if (alerts.value.size > budget.maxInstances) fail(RecurrenceGraphProblem.INPUT_BUDGET)
            FieldChange.Set(frozenList(alerts.value))
        }
    })
    private fun rule(value: RecurrenceRuleChanges): RecurrenceRuleChanges = value.copy(pattern = when (val p = value.pattern) {
        FieldChange.Keep -> FieldChange.Keep
        is FieldChange.Set -> FieldChange.Set(pattern(p.value))
    })
    private fun pattern(value: RecurrencePattern): RecurrencePattern = when (value) {
        is RecurrencePattern.Weekly -> value.copy(days = frozenSet(value.days))
        else -> value
    }
    private fun spec(value: RecurrenceSpec) = value.copy(pattern = pattern(value.pattern))
    private fun copyRequest(value: RecurrenceGraphRequest, budget: RecurrencePlanningBudget): RecurrenceGraphRequest = when (value) {
        is RecurrenceGraphRequest.Scoped -> value.copy(mutation = when (val mutation = value.mutation) {
            RecurrenceMutation.Delete -> mutation
            is RecurrenceMutation.Edit -> mutation.copy(patch = patch(mutation.patch, budget), replacement = when (val replacement = mutation.replacement) {
                FieldChange.Keep -> FieldChange.Keep
                is FieldChange.Set -> FieldChange.Set(replacement.value?.let(::spec))
            })
        })
        is RecurrenceGraphRequest.Edited -> value.copy(intent = when (val intent = value.intent) {
            is RecurrenceEditIntent.WholeSeriesEdit -> intent.copy(fields = patch(intent.fields, budget), rule = rule(intent.rule))
            is RecurrenceEditIntent.FollowingSeriesEdit -> intent.copy(fields = patch(intent.fields, budget),
                selectedExceptionPatch = patch(intent.selectedExceptionPatch, budget), rule = rule(intent.rule))
        })
    }
    private fun copyPlan(value: RecurrencePlanningResult.Ready, budget: RecurrencePlanningBudget) = value.copy(actions = frozenList(value.actions.map { action -> when (action) {
        is RecurrencePlanAction.UpdateMaster -> action.copy(patch = patch(action.patch, budget), exclusions = when (val dates = action.exclusions) {
            FieldChange.Keep -> FieldChange.Keep
            is FieldChange.Set -> {
                if (dates.value.size > budget.maxInstances) fail(RecurrenceGraphProblem.INPUT_BUDGET)
                FieldChange.Set(frozenSet(dates.value))
            }
        })
        is RecurrencePlanAction.InsertTail -> {
            if (action.exclusions.size > budget.maxInstances) fail(RecurrenceGraphProblem.INPUT_BUDGET)
            action.copy(exclusions = frozenSet(action.exclusions), patch = patch(action.patch, budget))
        }
        is RecurrencePlanAction.UpsertException -> action.copy(patch = patch(action.patch, budget))
        is RecurrencePlanAction.RebindExceptionToTail -> action.copy(selectedPatch = patch(action.selectedPatch, budget))
        is RecurrencePlanAction.RemapException -> action.copy(selectedPatch = patch(action.selectedPatch, budget))
        is RecurrencePlanAction.RemoveException -> action
        is RecurrencePlanAction.DeleteSeries -> {
            if (action.exceptions.size > budget.maxExceptions) fail(RecurrenceGraphProblem.INPUT_BUDGET)
            action.copy(exceptions = frozenList(action.exceptions))
        }
    } }))
}
