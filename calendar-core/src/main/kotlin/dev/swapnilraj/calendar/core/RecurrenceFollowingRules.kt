package dev.swapnilraj.calendar.core

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

enum class RecurrenceFollowingRuleProblem {
    INPUT_BUDGET, SOURCE_SHAPE, RULE, SELECTION, PLAN, ANCHOR, ORDINAL, INCOMPATIBLE_RULE,
}

sealed interface RecurrenceFollowingRuleResult {
    /** Ordinal zero edits the original master; no tail identity should be allocated. */
    data class Whole(val ruleRaw: String) : RecurrenceFollowingRuleResult
    data class Tail(val boundaryOrdinal: Int, val prefixRuleRaw: String,
        val tailStart: RecurrenceStart, val tailRuleRaw: String) : RecurrenceFollowingRuleResult
    data class NeedsReview(val problem: RecurrenceFollowingRuleProblem) : RecurrenceFollowingRuleResult
}

/**
 * Raw rule selection for the bounded exception/date-set-free FOLLOWING adapter. This checks a
 * recomputed pure plan, not provider authority. Callers must still prove ownership, source rows,
 * actual instance identity and resulting native expansion. No unbounded enumeration is performed.
 */
object RecurrenceFollowingRules {
    fun resolve(snapshot: RecurrenceSeriesSnapshot, selected: EventRef,
        intent: RecurrenceEditIntent.FollowingSeriesEdit, plan: RecurrencePlanningResult.Ready,
        budget: RecurrencePlanningBudget = RecurrencePlanningBudget()): RecurrenceFollowingRuleResult {
        fun review(problem: RecurrenceFollowingRuleProblem) = RecurrenceFollowingRuleResult.NeedsReview(problem)
        if (budget.maxInstances !in 1..RecurrenceOrdinalMapping.MAX_CANDIDATES_PER_SIDE)
            return review(RecurrenceFollowingRuleProblem.INPUT_BUDGET)
        val raw = snapshot.ruleRaw ?: return review(RecurrenceFollowingRuleProblem.RULE)
        if (raw.length > RecurrenceMasterTemplates.MAX_RULE_CHARS)
            return review(RecurrenceFollowingRuleProblem.INPUT_BUDGET)
        if (snapshot.coverage != SeriesCoverage.COMPLETE || snapshot.exceptions.isNotEmpty() ||
            snapshot.exDates.isNotEmpty() || listOf(snapshot.exDatesRaw, snapshot.rDatesRaw, snapshot.exRuleRaw).any { !it.isNullOrBlank() } ||
            intent.selectedExceptionPatch != EventPatch()) return review(RecurrenceFollowingRuleProblem.SOURCE_SHAPE)
        if (snapshot.validatedSelection != selected || selected.recurrence == null)
            return review(RecurrenceFollowingRuleProblem.SELECTION)
        val source = RecurrenceRules.parse(raw, snapshot.start) as? ParsedRecurrence.Supported
            ?: return review(RecurrenceFollowingRuleProblem.RULE)
        if (!portable(snapshot.start)) return review(RecurrenceFollowingRuleProblem.ANCHOR)
        val computed = RecurrenceEditing.plan(snapshot, selected, intent, budget)
        if (computed != plan) return review(RecurrenceFollowingRuleProblem.PLAN)
        val mapping = RecurrenceOrdinalMapping.map(raw, snapshot.start, intent.expectedOriginalBoundary,
            raw, snapshot.start, budget.maxInstances) as? RecurrenceOrdinalResult.Ready
            ?: return review(RecurrenceFollowingRuleProblem.ORDINAL)
        val cut = mapping.ordinal
        if (mapping.occurrence == null) return review(RecurrenceFollowingRuleProblem.ORDINAL)
        if (cut == 0) {
            val action = plan.actions.singleOrNull() as? RecurrencePlanAction.UpdateMaster
                ?: return review(RecurrenceFollowingRuleProblem.PLAN)
            val whole = RecurrenceEditIntent.WholeSeriesEdit(snapshot.start, intent.fields,
                intent.newTailStart, intent.duration, intent.rule)
            val result = RecurrenceMasterTemplates.effectiveRule(snapshot, whole, action)
                as? RecurrenceMasterRuleResult.Ready ?: return review(RecurrenceFollowingRuleProblem.INCOMPATIBLE_RULE)
            val start = intent.newTailStart.resolve(snapshot.start)
            if (!portable(start)) return review(RecurrenceFollowingRuleProblem.ANCHOR)
            return RecurrenceFollowingRuleResult.Whole(result.ruleRaw)
        }
        if (plan.actions.size != 2 || plan.actions[0] !is RecurrencePlanAction.UpdateMaster)
            return review(RecurrenceFollowingRuleProblem.PLAN)
        val prefix = plan.actions[0] as RecurrencePlanAction.UpdateMaster
        val tail = plan.actions[1] as? RecurrencePlanAction.InsertTail
            ?: return review(RecurrenceFollowingRuleProblem.PLAN)
        if ((tail.start is RecurrenceStart.AllDay) != (snapshot.start is RecurrenceStart.AllDay) || !portable(tail.start))
            return review(RecurrenceFollowingRuleProblem.ANCHOR)
        val plannedPrefix = (prefix.rule as? FieldChange.Set)?.value
            ?: return review(RecurrenceFollowingRuleProblem.PLAN)
        if (listOf(plannedPrefix, tail.rule).any { it.length > RecurrenceMasterTemplates.MAX_RULE_CHARS })
            return review(RecurrenceFollowingRuleProblem.INPUT_BUDGET)
        // Pure planners already replace only the necessary prefix limit token. Require the
        // literal remainder to survive, rather than allowing a canonicalized prefix through.
        val limitName = if (source.spec.end is RecurrenceEnd.Count) "COUNT" else "UNTIL"
        val prefixLimit = token(plannedPrefix, limitName) ?: return review(RecurrenceFollowingRuleProblem.RULE)
        val prefixRaw = replace(raw, limitName, prefixLimit)
        if (prefixRaw != plannedPrefix || RecurrenceRules.parse(prefixRaw, snapshot.start) !is ParsedRecurrence.Supported)
            return review(RecurrenceFollowingRuleProblem.INCOMPATIBLE_RULE)
        val remaining = when (val end = source.spec.end) {
            is RecurrenceEnd.Count -> if (end.count > cut) RecurrenceEnd.Count(end.count - cut)
                else return review(RecurrenceFollowingRuleProblem.ORDINAL)
            else -> if (snapshot.start is RecurrenceStart.Timed && token(raw, "UNTIL") != null)
                RecurrenceEnd.ExactUntil(Instant.from(DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'")
                    .withZone(ZoneOffset.UTC).parse(token(raw, "UNTIL")))) else end
        }
        val expected = try {
            val changes = intent.rule
            RecurrenceSpec(changes.pattern.resolve(source.spec.pattern), changes.interval.resolve(source.spec.interval),
                changes.end.resolve(remaining), changes.weekStart.resolve(source.spec.weekStart))
        } catch (_: IllegalArgumentException) { return review(RecurrenceFollowingRuleProblem.INCOMPATIBLE_RULE) }
        val expectedRaw = try { RecurrenceRules.build(expected, tail.start) }
            catch (_: Exception) { return review(RecurrenceFollowingRuleProblem.INCOMPATIBLE_RULE) }
        val planned = RecurrenceRules.parse(tail.rule, tail.start) as? ParsedRecurrence.Supported
            ?: return review(RecurrenceFollowingRuleProblem.RULE)
        val expectedParsed = RecurrenceRules.parse(expectedRaw, tail.start) as? ParsedRecurrence.Supported
            ?: return review(RecurrenceFollowingRuleProblem.INCOMPATIBLE_RULE)
        if (planned.spec != expectedParsed.spec) return review(RecurrenceFollowingRuleProblem.INCOMPATIBLE_RULE)
        val tailRaw = if (intent.rule == RecurrenceRuleChanges()) {
            if (remaining is RecurrenceEnd.Count) replace(raw, "COUNT", remaining.count.toString()) else raw
        } else tail.rule
        val preserved = RecurrenceRules.parse(tailRaw, tail.start) as? ParsedRecurrence.Supported
            ?: return review(RecurrenceFollowingRuleProblem.INCOMPATIBLE_RULE)
        if (preserved.spec != planned.spec) return review(RecurrenceFollowingRuleProblem.INCOMPATIBLE_RULE)
        return RecurrenceFollowingRuleResult.Tail(cut, prefixRaw, tail.start, tailRaw)
    }

    private fun portable(start: RecurrenceStart) = start !is RecurrenceStart.Timed ||
        ProviderTimeZoneCompatibility.isCompatible(start.zone) &&
        (start.zone.rules.getValidOffsets(start.localDateTime).size == 1 || start.preferredOffset != null)
    private fun token(raw: String, name: String) = raw.split(';').singleOrNull { it.startsWith("$name=") }?.substringAfter('=')
    private fun replace(raw: String, name: String, value: String) = if (token(raw, name) == null) "$raw;$name=$value"
        else raw.split(';').joinToString(";") { if (it.startsWith("$name=")) "$name=$value" else it }
    private fun <T> FieldChange<T>.resolve(old: T): T = when (this) { FieldChange.Keep -> old; is FieldChange.Set -> value }
}
