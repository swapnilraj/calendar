package dev.swapnilraj.calendar.core

import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

enum class RecurrenceCreationProblem {
    INTERVAL, COUNT, END_LIMIT, PREVIEW_BUDGET, START_TIME, START_OFFSET,
    DURATION, DURATION_RANGE, PATTERN_OR_RULE, DATE_RANGE, NOMINAL_ENDPOINT, PROVIDER_NOMINAL_DURATION,
    PREVIEW_TRANSITION,
}

/**
 * Validated authored RFC intent, not proof of provider expansion or invitation delivery.
 * Recurring provider rows use DURATION, never DTEND. [firstTime] remains the author's full interval.
 * Android adapters must verify actual instance bounds; in particular, some providers expand timed
 * nominal P1D as 86400 seconds even across DST. [durationRaw] is retained, never silently converted.
 * [preview] covers only its finite returned prefix, never proves the full series/provider policy.
 */
data class RecurrenceCreationPlan(
    val start: RecurrenceStart,
    val dtstart: Instant,
    val allDay: Boolean,
    val eventZone: ZoneId,
    val ruleRaw: String,
    val durationRaw: String,
    val firstTime: EventTime,
    val preview: List<RecurrenceOccurrence>,
)

sealed interface RecurrenceCreationResult {
    data class Ready(val plan: RecurrenceCreationPlan) : RecurrenceCreationResult
    data class Invalid(val problem: RecurrenceCreationProblem) : RecurrenceCreationResult
    data class NeedsReview(val problem: RecurrenceCreationProblem) : RecurrenceCreationResult
}

/** New-series authoring only. Imported rules and unrelated edits continue to retain their raw data. */
object RecurrenceCreation {
    const val MAX_INTERVAL = 1000
    const val MAX_COUNT = 10000
    const val MAX_PREVIEW = 50

    /**
     * Default timed duration is elapsed PTseconds; all-day duration is nominal whole DATE days.
     * An explicit [durationRaw] preserves units and must resolve to exactly [firstTime]. Timed
     * nominal days/weeks require review because provider expansion may differ from RFC semantics.
     * No floating display zone, default offset choice, imported ExactUntil, defaults for
     * invalid limits, or unbounded recurrence enumeration is introduced here.
     */
    fun compile(
        start: RecurrenceStart,
        firstTime: EventTime,
        spec: RecurrenceSpec,
        durationRaw: String? = null,
        previewLimit: Int = 10,
    ): RecurrenceCreationResult {
        fun invalid(problem: RecurrenceCreationProblem) = RecurrenceCreationResult.Invalid(problem)
        if (spec.interval !in 1..MAX_INTERVAL) return invalid(RecurrenceCreationProblem.INTERVAL)
        if (spec.end is RecurrenceEnd.Count && spec.end.count !in 1..MAX_COUNT)
            return invalid(RecurrenceCreationProblem.COUNT)
        if (spec.end is RecurrenceEnd.ExactUntil) return invalid(RecurrenceCreationProblem.END_LIMIT)
        if (spec.end is RecurrenceEnd.ThroughDate && spec.end.date < start.date)
            return invalid(RecurrenceCreationProblem.END_LIMIT)
        if (previewLimit !in 1..MAX_PREVIEW) return invalid(RecurrenceCreationProblem.PREVIEW_BUDGET)
        return try {
            val dtstart: Instant
            val zone: ZoneId
            val defaultDuration: String
            when (start) {
                is RecurrenceStart.AllDay -> {
                    val time = firstTime as? EventTime.AllDay ?: return invalid(RecurrenceCreationProblem.START_TIME)
                    if (time.dates.start != start.date) return invalid(RecurrenceCreationProblem.START_TIME)
                    if (time.dates.endExclusive.year !in 1..9999) return invalid(RecurrenceCreationProblem.DATE_RANGE)
                    val days = ChronoUnit.DAYS.between(time.dates.start, time.dates.endExclusive)
                    if (days <= 0) return invalid(RecurrenceCreationProblem.DURATION)
                    defaultDuration = "P${days}D"
                    zone = ZoneOffset.UTC
                    dtstart = start.date.atStartOfDay().toInstant(ZoneOffset.UTC)
                }
                is RecurrenceStart.Timed -> {
                    val time = firstTime as? EventTime.Timed ?: return invalid(RecurrenceCreationProblem.START_TIME)
                    if (start.zone.rules.getValidOffsets(start.localDateTime).size > 1 && start.preferredOffset == null)
                        return invalid(RecurrenceCreationProblem.START_OFFSET)
                    if (time.start != start.instant) return invalid(RecurrenceCreationProblem.START_TIME)
                    if (time.start.nano != 0 || time.end.nano != 0 || time.duration.isZero)
                        return invalid(RecurrenceCreationProblem.DURATION)
                    if (time.end.atZone(start.zone).year !in 1..9999) return invalid(RecurrenceCreationProblem.DATE_RANGE)
                    defaultDuration = "PT${time.duration.seconds}S"
                    zone = start.zone
                    dtstart = start.instant
                }
            }
            val raw = durationRaw ?: defaultDuration
            val parsed = RecurrenceDurationCodec.parse(raw) as? ParsedRecurrenceDuration.Supported
                ?: return invalid(RecurrenceCreationProblem.DURATION)
            val p = parsed.parts
            if (p.sign < 0 || start is RecurrenceStart.AllDay && p.hasTime)
                return invalid(RecurrenceCreationProblem.DURATION)
            if (start is RecurrenceStart.Timed && (p.weeks > 0 || p.days > 0))
                return RecurrenceCreationResult.NeedsReview(RecurrenceCreationProblem.PROVIDER_NOMINAL_DURATION)
            // AOSP's duration parser accumulates components and sums unit products as Int. Refuse
            // overflow before provider insertion; preserving raw must not silently change its value.
            val total = Math.addExact(Math.addExact(Math.addExact(Math.addExact(
                Math.multiplyExact(p.weeks, 604800L), Math.multiplyExact(p.days, 86400L)),
                Math.multiplyExact(p.hours, 3600L)), Math.multiplyExact(p.minutes, 60L)), p.seconds)
            if (total !in 1..Int.MAX_VALUE.toLong()) return invalid(RecurrenceCreationProblem.DURATION_RANGE)
            when (val resolved = RecurrenceDurationCodec.resolve(parsed, start)) {
                is ResolvedRecurrenceDuration.NeedsReview -> return RecurrenceCreationResult.NeedsReview(RecurrenceCreationProblem.NOMINAL_ENDPOINT)
                is ResolvedRecurrenceDuration.Resolved -> if (resolved.time != firstTime)
                    return invalid(RecurrenceCreationProblem.DURATION)
            }
            val rule = try { RecurrenceRules.build(spec, start) }
                catch (_: IllegalArgumentException) { return invalid(RecurrenceCreationProblem.PATTERN_OR_RULE) }
            var preview = RecurrenceRules.preview(rule, start, previewLimit)
            if (preview.isEmpty()) return invalid(RecurrenceCreationProblem.PATTERN_OR_RULE)
            if (start is RecurrenceStart.Timed) {
                val checked = ArrayList<RecurrenceOccurrence>(preview.size)
                preview.forEachIndexed { index, occurrence ->
                    val candidate = occurrence as RecurrenceOccurrence.Timed
                    if (index == 0 && candidate.localDateTime == start.localDateTime) {
                        // lib-recur may reconstruct an ambiguous seed with the other offset. The
                        // explicit DTSTART instant is authoritative for this first nominal slot.
                        checked += candidate.copy(instant = start.instant)
                    } else {
                        val offsets = start.zone.rules.getValidOffsets(candidate.localDateTime)
                        if (offsets.size != 1 || candidate.localDateTime.toLocalTime() != start.localDateTime.toLocalTime() ||
                            candidate.localDateTime.toInstant(offsets.single()) != candidate.instant)
                            return RecurrenceCreationResult.NeedsReview(RecurrenceCreationProblem.PREVIEW_TRANSITION)
                        checked += candidate
                    }
                }
                preview = checked
            }
            RecurrenceCreationResult.Ready(RecurrenceCreationPlan(start, dtstart, start is RecurrenceStart.AllDay,
                zone, rule, raw, firstTime, preview))
        } catch (_: ArithmeticException) {
            invalid(RecurrenceCreationProblem.DURATION_RANGE)
        } catch (_: DateTimeException) {
            invalid(RecurrenceCreationProblem.DATE_RANGE)
        } catch (_: IllegalArgumentException) {
            invalid(RecurrenceCreationProblem.PATTERN_OR_RULE)
        }
    }
}
