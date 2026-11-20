package dev.swapnilraj.calendar.core

import java.time.LocalDate
import java.util.TimeZone
import org.dmfs.rfc5545.DateTime
import org.dmfs.rfc5545.recur.RecurrenceRule

enum class RecurrenceOrdinalProblem { INPUT_BUDGET, RULE, KIND, ORIGINAL_NOT_FOUND, CANDIDATE_BUDGET, CLOCK_UNCERTAINTY, DATE_RANGE }
sealed interface RecurrenceOrdinalResult {
    /** Zero-based nominal ordinal, before exclusions. Null means proven new iterator exhaustion. */
    data class Ready(val ordinal: Int, val occurrence: RecurrenceOccurrence?) : RecurrenceOrdinalResult
    data class NeedsReview(val problem: RecurrenceOrdinalProblem) : RecurrenceOrdinalResult
}

/** Candidate identity only. The Android adapter must read an actual instance before returning a ref. */
object RecurrenceOrdinalMapping {
    const val MAX_CANDIDATES_PER_SIDE = 10000

    fun map(
        oldRuleRaw: String,
        oldStart: RecurrenceStart,
        original: RecurrenceKey,
        newRuleRaw: String,
        newStart: RecurrenceStart,
        maxCandidatesPerSide: Int = MAX_CANDIDATES_PER_SIDE,
    ): RecurrenceOrdinalResult {
        fun review(problem: RecurrenceOrdinalProblem) = RecurrenceOrdinalResult.NeedsReview(problem)
        if (maxCandidatesPerSide !in 1..MAX_CANDIDATES_PER_SIDE ||
            oldRuleRaw.length > RecurrenceMasterTemplates.MAX_RULE_CHARS || newRuleRaw.length > RecurrenceMasterTemplates.MAX_RULE_CHARS)
            return review(RecurrenceOrdinalProblem.INPUT_BUDGET)
        if ((oldStart is RecurrenceStart.AllDay) != (newStart is RecurrenceStart.AllDay) ||
            (oldStart is RecurrenceStart.AllDay) != (original is RecurrenceKey.Date)) return review(RecurrenceOrdinalProblem.KIND)
        // Common supported rules only. Never prepend DTSTART to an excluded-start/unknown rule.
        if (RecurrenceRules.parse(oldRuleRaw, oldStart) !is ParsedRecurrence.Supported ||
            RecurrenceRules.parse(newRuleRaw, newStart) !is ParsedRecurrence.Supported) return review(RecurrenceOrdinalProblem.RULE)
        return try {
            val old = iterator(oldRuleRaw, oldStart)
            var ordinal: Int? = null
            var visited = 0
            while (visited < maxCandidatesPerSide && old.hasNext()) {
                val item = occurrence(old.nextDateTime(), oldStart, visited)
                val key = key(item)
                if (key == original) { ordinal = visited; break }
                if (after(key, original)) return review(RecurrenceOrdinalProblem.ORIGINAL_NOT_FOUND)
                visited++
            }
            val index = ordinal ?: return review(if (old.hasNext()) RecurrenceOrdinalProblem.CANDIDATE_BUDGET else RecurrenceOrdinalProblem.ORIGINAL_NOT_FOUND)
            val replacement = iterator(newRuleRaw, newStart)
            var next = 0
            while (next < maxCandidatesPerSide) {
                if (!replacement.hasNext()) return RecurrenceOrdinalResult.Ready(index, null)
                val item = occurrence(replacement.nextDateTime(), newStart, next)
                if (next == index) return RecurrenceOrdinalResult.Ready(index, item)
                next++
            }
            review(RecurrenceOrdinalProblem.CANDIDATE_BUDGET)
        } catch (problem: ClockUncertainty) {
            review(RecurrenceOrdinalProblem.CLOCK_UNCERTAINTY)
        } catch (problem: DateRange) {
            review(RecurrenceOrdinalProblem.DATE_RANGE)
        } catch (_: Exception) {
            review(RecurrenceOrdinalProblem.RULE)
        }
    }

    private fun iterator(raw: String, start: RecurrenceStart) =
        RecurrenceRule(raw, RecurrenceRule.RfcMode.RFC5545_STRICT).iterator(when (start) {
            is RecurrenceStart.AllDay -> DateTime(start.date.year, start.date.monthValue - 1, start.date.dayOfMonth)
            is RecurrenceStart.Timed -> DateTime(TimeZone.getTimeZone(start.zone), start.instant.toEpochMilli())
        })

    private fun occurrence(value: DateTime, start: RecurrenceStart, ordinal: Int): RecurrenceOccurrence {
        if (value.year !in 1..9999) throw DateRange()
        val date = LocalDate.of(value.year, value.month + 1, value.dayOfMonth)
        if (start is RecurrenceStart.AllDay) return RecurrenceOccurrence.AllDay(date)
        start as RecurrenceStart.Timed
        val local = date.atTime(value.hours, value.minutes, value.seconds)
        val offsets = start.zone.rules.getValidOffsets(local)
        if (ordinal == 0 && local == start.localDateTime) {
            if (offsets.size > 1 && start.preferredOffset == null) throw ClockUncertainty()
            return RecurrenceOccurrence.Timed(local, start.instant)
        }
        if (offsets.size != 1 || local.toLocalTime() != start.localDateTime.toLocalTime()) throw ClockUncertainty()
        val instant = local.toInstant(offsets.single())
        if (instant.toEpochMilli() != value.timestamp) throw ClockUncertainty()
        return RecurrenceOccurrence.Timed(local, instant)
    }

    private fun key(value: RecurrenceOccurrence): RecurrenceKey = when (value) {
        is RecurrenceOccurrence.AllDay -> RecurrenceKey.Date(value.date)
        is RecurrenceOccurrence.Timed -> RecurrenceKey.Timed(value.instant)
    }
    private fun after(first: RecurrenceKey, second: RecurrenceKey) = when (first) {
        is RecurrenceKey.Date -> first.date > (second as RecurrenceKey.Date).date
        is RecurrenceKey.Timed -> first.instant > (second as RecurrenceKey.Timed).instant
    }
    private class ClockUncertainty : Exception()
    private class DateRange : Exception()
}
