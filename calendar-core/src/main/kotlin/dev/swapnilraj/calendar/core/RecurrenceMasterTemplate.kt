package dev.swapnilraj.calendar.core

import java.time.DateTimeException
import java.time.ZoneId
import java.time.ZoneOffset

/** A logical DTSTART+DURATION interval, never a fabricated provider-expanded occurrence. */
data class RecurrenceMasterTemplate(val time: EventTime, val eventZone: ZoneId, val durationRaw: String)

enum class RecurrenceMasterTemplateProblem {
    MISSING_DURATION, INPUT_BUDGET, INVALID_DURATION, UNSUPPORTED_DURATION,
    NON_POSITIVE_DURATION, DURATION_KIND, TIMED_NOMINAL_DURATION, AMBIGUOUS_START,
    PROVIDER_DURATION_OVERFLOW, DATE_RANGE,
}

sealed interface RecurrenceMasterTemplateResult {
    data class Ready(val template: RecurrenceMasterTemplate) : RecurrenceMasterTemplateResult
    data class NeedsReview(val problem: RecurrenceMasterTemplateProblem) : RecurrenceMasterTemplateResult
}

enum class RecurrenceMasterRuleProblem { MISSING_RULE, INPUT_BUDGET, ANCHOR, UNSUPPORTED_RULE, INCOMPATIBLE_RULE }
sealed interface RecurrenceMasterRuleResult {
    data class Ready(val ruleRaw: String) : RecurrenceMasterRuleResult
    data class NeedsReview(val problem: RecurrenceMasterRuleProblem) : RecurrenceMasterRuleResult
}

/**
 * Bounded temporal representation only. Ready grants no edit right, complete source coverage,
 * provider occurrence identity or proof of future expansion. The Android adapter must validate its
 * own token, source namespace, rule/exception feasibility and actual resulting instances.
 * RRULE and limits are intentionally untouched, including imported exact UTC UNTIL cutoffs.
 */
object RecurrenceMasterTemplates {
    const val MAX_DURATION_CHARS = 128
    const val MAX_PROVIDER_SECONDS: Long = Int.MAX_VALUE.toLong()
    const val MAX_RULE_CHARS = 4096

    /**
     * Select raw text only after the adapter has recomputed and validated its plan. This helper
     * grants no authority to caller-supplied actions. Keep retains literal source syntax when it has
     * the same supported meaning at the effective anchor; it never rounds an imported UTC cutoff.
     */
    fun effectiveRule(
        snapshot: RecurrenceSeriesSnapshot,
        intent: RecurrenceEditIntent.WholeSeriesEdit,
        action: RecurrencePlanAction.UpdateMaster,
    ): RecurrenceMasterRuleResult {
        fun review(problem: RecurrenceMasterRuleProblem) = RecurrenceMasterRuleResult.NeedsReview(problem)
        if (intent.expectedMasterStart != snapshot.start || intent.newMasterStart != action.anchor)
            return review(RecurrenceMasterRuleProblem.ANCHOR)
        val start = when (val anchor = action.anchor) {
            FieldChange.Keep -> snapshot.start
            is FieldChange.Set -> anchor.value
        }
        if ((start is RecurrenceStart.AllDay) != (snapshot.start is RecurrenceStart.AllDay))
            return review(RecurrenceMasterRuleProblem.ANCHOR)
        val old = snapshot.ruleRaw ?: return review(RecurrenceMasterRuleProblem.MISSING_RULE)
        val planned = when (val rule = action.rule) {
            FieldChange.Keep -> old
            is FieldChange.Set -> rule.value
        }
        if (old.length > MAX_RULE_CHARS || planned.length > MAX_RULE_CHARS)
            return review(RecurrenceMasterRuleProblem.INPUT_BUDGET)
        val replacement = RecurrenceRules.parse(planned, start) as? ParsedRecurrence.Supported
            ?: return review(RecurrenceMasterRuleProblem.UNSUPPORTED_RULE)
        if (intent.rule != RecurrenceRuleChanges()) {
            if (action.rule !is FieldChange.Set) return review(RecurrenceMasterRuleProblem.INCOMPATIBLE_RULE)
            return RecurrenceMasterRuleResult.Ready(planned)
        }
        val preserved = RecurrenceRules.parse(old, start) as? ParsedRecurrence.Supported
            ?: return review(RecurrenceMasterRuleProblem.UNSUPPORTED_RULE)
        if (preserved.spec != replacement.spec) return review(RecurrenceMasterRuleProblem.INCOMPATIBLE_RULE)
        return RecurrenceMasterRuleResult.Ready(old)
    }

    fun resolve(snapshot: RecurrenceSeriesSnapshot): RecurrenceMasterTemplateResult =
        resolve(snapshot.start, snapshot.durationRaw)

    fun resolve(start: RecurrenceStart, durationRaw: String?): RecurrenceMasterTemplateResult {
        fun review(problem: RecurrenceMasterTemplateProblem) = RecurrenceMasterTemplateResult.NeedsReview(problem)
        if (durationRaw == null || durationRaw.isEmpty()) return review(RecurrenceMasterTemplateProblem.MISSING_DURATION)
        if (durationRaw.length > MAX_DURATION_CHARS) return review(RecurrenceMasterTemplateProblem.INPUT_BUDGET)
        val parsed = when (val value = RecurrenceDurationCodec.parse(durationRaw, MAX_DURATION_CHARS)) {
            is ParsedRecurrenceDuration.Invalid -> return review(RecurrenceMasterTemplateProblem.INVALID_DURATION)
            is ParsedRecurrenceDuration.Unsupported -> return review(RecurrenceMasterTemplateProblem.UNSUPPORTED_DURATION)
            is ParsedRecurrenceDuration.Supported -> value
        }
        val p = parsed.parts
        if (p.sign < 0 || listOf(p.weeks, p.days, p.hours, p.minutes, p.seconds).all { it == 0L })
            return review(RecurrenceMasterTemplateProblem.NON_POSITIVE_DURATION)
        when (start) {
            is RecurrenceStart.AllDay -> if (p.hasTime) return review(RecurrenceMasterTemplateProblem.DURATION_KIND)
            is RecurrenceStart.Timed -> {
                if (p.weeks > 0 || p.days > 0) return review(RecurrenceMasterTemplateProblem.TIMED_NOMINAL_DURATION)
                if (start.zone.rules.getValidOffsets(start.localDateTime).size > 1 && start.preferredOffset == null)
                    return review(RecurrenceMasterTemplateProblem.AMBIGUOUS_START)
            }
        }
        return try {
            // AOSP Duration uses Int component accumulation and Int unit products/sums, followed
            // by Long milliseconds. Bounding the positive total also bounds every component/product.
            val seconds = Math.addExact(Math.addExact(Math.addExact(Math.addExact(
                Math.multiplyExact(p.weeks, 604800L), Math.multiplyExact(p.days, 86400L)),
                Math.multiplyExact(p.hours, 3600L)), Math.multiplyExact(p.minutes, 60L)), p.seconds)
            if (seconds > MAX_PROVIDER_SECONDS) return review(RecurrenceMasterTemplateProblem.PROVIDER_DURATION_OVERFLOW)
            val time = when (start) {
                is RecurrenceStart.AllDay -> {
                    val days = Math.addExact(Math.multiplyExact(p.weeks, 7L), p.days)
                    val end = start.date.plusDays(days)
                    if (end.year !in 1..9999) return review(RecurrenceMasterTemplateProblem.DATE_RANGE)
                    EventTime.AllDay(CalendarDateRange(start.date, end))
                }
                is RecurrenceStart.Timed -> {
                    // No nominal increments or offset re-resolution: source DTSTART is exact,
                    // including the explicitly stored choice of a repeated local clock time.
                    val instant = start.instant
                    val end = instant.plusSeconds(seconds)
                    if (instant.atOffset(ZoneOffset.UTC).year !in 1..9999 || end.atOffset(ZoneOffset.UTC).year !in 1..9999 ||
                        end.atZone(start.zone).year !in 1..9999) return review(RecurrenceMasterTemplateProblem.DATE_RANGE)
                    EventTime.Timed(instant, end)
                }
            }
            val zone = if (start is RecurrenceStart.Timed) start.zone else ZoneOffset.UTC
            RecurrenceMasterTemplateResult.Ready(RecurrenceMasterTemplate(time, zone, durationRaw))
        } catch (_: ArithmeticException) {
            review(RecurrenceMasterTemplateProblem.PROVIDER_DURATION_OVERFLOW)
        } catch (_: DateTimeException) {
            review(RecurrenceMasterTemplateProblem.DATE_RANGE)
        }
    }
}
