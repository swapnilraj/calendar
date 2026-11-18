package dev.swapnilraj.calendar.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone
import org.dmfs.rfc5545.DateTime
import org.dmfs.rfc5545.recur.RecurrenceRule

data class SeriesIdentity(val calendarId: CalendarId, val masterEventId: Long) {
    init { require(masterEventId >= 0) }
}

/** Original scheduled identity, independent of an exception's current actual appointment time. */
sealed interface RecurrenceKey {
    data class Date(val date: LocalDate) : RecurrenceKey
    data class Timed(val instant: Instant) : RecurrenceKey
}

data class SeriesException(val ref: EventRef, val original: RecurrenceKey, val cancelled: Boolean = false)
enum class SeriesCoverage { INCOMPLETE, COMPLETE }

/**
 * A must assert master and every non-deleted exception row (including cancelled/moved rows) before execution.
 * COMPLETE is only valid after a non-truncated provider read; it is not inferred from visible events.
 * [validatedSelection] must come from A's fresh instance/bounds/original-identity validation.
 * A revalidates that selection plus master/affected exception assertions before executing any plan.
 * Unrepresented provider fields and child rows remain in A's source snapshot, never reconstructed here.
 */
data class RecurrenceSeriesSnapshot(
    val identity: SeriesIdentity,
    val start: RecurrenceStart,
    val ruleRaw: String?,
    val durationRaw: String? = null,
    val rDatesRaw: String? = null,
    val exRuleRaw: String? = null,
    val exDatesRaw: String? = null,
    val exDates: Set<RecurrenceKey> = emptySet(),
    val exDatesDecoded: Boolean = false,
    val exceptions: List<SeriesException> = emptyList(),
    val coverage: SeriesCoverage = SeriesCoverage.INCOMPLETE,
    val validatedSelection: EventRef? = null,
)

enum class RecurrenceScope { THIS, FOLLOWING, ALL }
sealed interface RecurrenceMutation {
    data class Edit(
        val patch: EventPatch,
        val replacement: FieldChange<RecurrenceSpec?> = FieldChange.Keep,
    ) : RecurrenceMutation
    data object Delete : RecurrenceMutation
}

/** Bounded work only. Exhaustion requires review; never treat a scanned prefix as the whole series. */
data class RecurrencePlanningBudget(val maxInstances: Int = 10_000, val maxExceptions: Int = 1_000) {
    init {
        require(maxInstances in 1..100_000)
        require(maxExceptions in 0..10_000)
    }
}

enum class SeriesTarget { EXISTING, TAIL }
sealed interface RecurrencePlanAction {
    /** Fields absent from this patch, including raw recurrence text, remain untouched. */
    data class UpdateMaster(
        val patch: EventPatch = EventPatch(),
        val rule: FieldChange<String> = FieldChange.Keep,
        val exclusions: FieldChange<Set<RecurrenceKey>> = FieldChange.Keep,
    ) : RecurrencePlanAction
    /** Clone eligible master payload and child rows; A must not copy cloud IDs/sync-only fields. */
    data class InsertTail(
        val start: RecurrenceStart,
        val rule: String,
        val exclusions: Set<RecurrenceKey>,
        val patch: EventPatch,
    ) : RecurrencePlanAction
    /** A writes a non-recurring exception with ORIGINAL identity/all-day flag, not a cloned RRULE. */
    data class UpsertException(
        val target: SeriesTarget,
        val original: RecurrenceKey,
        val existing: EventRef?,
        val patch: EventPatch = EventPatch(),
        val cancelled: Boolean = false,
    ) : RecurrencePlanAction
    /** Keep the entire exception payload and actual time; only its parent identity changes. */
    data class RebindExceptionToTail(
        val exception: SeriesException,
        val selectedPatch: EventPatch = EventPatch(),
    ) : RecurrencePlanAction
    data class RemoveException(val exception: SeriesException) : RecurrencePlanAction
    data class DeleteSeries(val exceptions: List<SeriesException>) : RecurrencePlanAction
}

sealed interface RecurrencePlanningResult {
    data class Ready(val series: SeriesIdentity, val actions: List<RecurrencePlanAction>) : RecurrencePlanningResult
    data class NeedsReview(val reason: String) : RecurrencePlanningResult
    data class Unsupported(val reason: String) : RecurrencePlanningResult
    data class Invalid(val reason: String) : RecurrencePlanningResult
}

/**
 * First bounded planner: THIS exceptions, ALL non-temporal edits/delete, and unchanged-pattern
 * single-RRULE FOLLOWING edits/delete. It does not execute Android writes or claim cloud success.
 * Time/pattern replacement, RDATE and EXRULE splitting await reviewed transformation support.
 */
object RecurrencePlanning {
    private val utc = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)

    fun plan(
        snapshot: RecurrenceSeriesSnapshot,
        selected: EventRef,
        scope: RecurrenceScope,
        mutation: RecurrenceMutation,
        budget: RecurrencePlanningBudget = RecurrencePlanningBudget(),
    ): RecurrencePlanningResult {
        if (snapshot.coverage != SeriesCoverage.COMPLETE) return review("Complete master and exception coverage is required")
        if (snapshot.exceptions.size > budget.maxExceptions) return review("Exception budget exceeded")
        if (snapshot.validatedSelection != selected) return review("Fresh provider validation of the exact selection is required")
        val recurrence = selected.recurrence ?: return invalid("Missing original occurrence identity")
        if (selected.calendarId != snapshot.identity.calendarId || recurrence.masterEventId != snapshot.identity.masterEventId)
            return invalid("Selection belongs to another series")
        val key = key(snapshot.start, recurrence.originalStart) ?: return invalid("Malformed all-day original identity")
        if (!validSnapshot(snapshot)) return review("Exception/exclusion identities are incomplete or inconsistent")
        val exceptions = snapshot.exceptions.sortedWith(compareBy({ keyOrder(it.original) }, { it.ref.eventId }))
        val existing = exceptions.singleOrNull { it.original == key }
        if (existing?.cancelled == true) return invalid("Selected occurrence is cancelled")
        if (selected.eventId != (existing?.ref?.eventId ?: snapshot.identity.masterEventId))
            return invalid("Selection does not match a known exception")
        if (mutation is RecurrenceMutation.Edit && mutation.replacement != FieldChange.Keep)
            return review("Recurrence pattern replacement requires reviewed original-key mapping")
        fun ready(vararg actions: RecurrencePlanAction) = RecurrencePlanningResult.Ready(snapshot.identity, actions.toList())

        if (scope == RecurrenceScope.THIS) return when (mutation) {
            RecurrenceMutation.Delete -> ready(RecurrencePlanAction.UpsertException(SeriesTarget.EXISTING, key, existing?.ref, cancelled = true))
            is RecurrenceMutation.Edit -> ready(RecurrencePlanAction.UpsertException(SeriesTarget.EXISTING, key, existing?.ref, mutation.patch))
        }
        if (mutation is RecurrenceMutation.Edit && (mutation.patch.time != FieldChange.Keep || mutation.patch.eventZone != FieldChange.Keep))
            return review("Series time/zone changes require reviewed exception mapping")
        if (scope == RecurrenceScope.ALL) return when (mutation) {
            RecurrenceMutation.Delete -> ready(RecurrencePlanAction.DeleteSeries(exceptions))
            is RecurrenceMutation.Edit -> ready(RecurrencePlanAction.UpdateMaster(patch = mutation.patch))
        }

        // No recurrence-set reinterpretation is needed above. A following split does require it.
        if (!snapshot.rDatesRaw.isNullOrBlank() || !snapshot.exRuleRaw.isNullOrBlank())
            return RecurrencePlanningResult.Unsupported("RDATE/EXRULE transforms are not implemented; raw fields must be preserved")
        if (!snapshot.exDatesRaw.isNullOrBlank() && !snapshot.exDatesDecoded)
            return review("All EXDATE values must be decoded before partitioning")
        val raw = snapshot.ruleRaw ?: return RecurrencePlanningResult.Unsupported("A single RRULE is required")
        val parsed = RecurrenceRules.parse(raw, snapshot.start)
        if (parsed !is ParsedRecurrence.Supported) return RecurrencePlanningResult.Unsupported("The RRULE cannot be safely transformed")
        val requiredKeys = exceptions.filter { !before(it.original, key) }.map { it.original }.toSet()
        val split = try { splitPoint(raw, snapshot.start, key, requiredKeys, budget.maxInstances) }
        catch (_: Exception) { return review("Recurrence iteration could not be proven within supported bounds") }
        if (split is Split.Invalid) return invalid("Original occurrence is not generated by the rule")
        if (split is Split.Exhausted) return review("Recurrence scan budget exceeded")
        if (split is Split.Orphaned) return review("Exception originals are not generated by the rule")
        split as Split.Found
        if (key in snapshot.exDates && existing == null) return invalid("Selected occurrence is excluded")
        if (split.before == 0) {
            // No invalid COUNT=0 prefix and no redundant replacement series.
            return when (mutation) {
                RecurrenceMutation.Delete -> ready(RecurrencePlanAction.DeleteSeries(exceptions))
                is RecurrenceMutation.Edit -> RecurrencePlanningResult.Ready(snapshot.identity, buildList {
                    add(RecurrencePlanAction.UpdateMaster(patch = mutation.patch))
                    if (existing != null) add(RecurrencePlanAction.UpsertException(SeriesTarget.EXISTING, key, existing.ref, mutation.patch))
                })
            }
        }
        val count = (parsed.spec.end as? RecurrenceEnd.Count)?.count
        if (count != null && split.before >= count) return invalid("Split exceeds the original COUNT")
        val prefixRule = if (count != null) replacePart(raw, "COUNT", split.before.toString())
            else replacePart(raw, "UNTIL", untilValue(requireNotNull(split.previous)))
        val prefixExclusions = snapshot.exDates.filterTo(linkedSetOf()) { before(it, key) }
        val followingExceptions = exceptions.filter { !before(it.original, key) }
        val actions = mutableListOf<RecurrencePlanAction>(RecurrencePlanAction.UpdateMaster(
            rule = FieldChange.Set(prefixRule), exclusions = FieldChange.Set(prefixExclusions),
        ))
        when (mutation) {
            RecurrenceMutation.Delete -> followingExceptions.forEach { actions += RecurrencePlanAction.RemoveException(it) }
            is RecurrenceMutation.Edit -> {
                val tailRule = if (count == null) raw else replacePart(raw, "COUNT", (count - split.before).toString())
                val tailExclusions = snapshot.exDates.filterTo(linkedSetOf()) { !before(it, key) }
                actions += RecurrencePlanAction.InsertTail(startAt(snapshot.start, key), tailRule, tailExclusions, mutation.patch)
                followingExceptions.forEach { exception -> actions += RecurrencePlanAction.RebindExceptionToTail(exception,
                    if (exception.original == key) mutation.patch else EventPatch()) }
            }
        }
        return RecurrencePlanningResult.Ready(snapshot.identity, actions.toList())
    }

    private sealed interface Split {
        data class Found(val before: Int, val previous: RecurrenceKey?) : Split
        data object Invalid : Split
        data object Exhausted : Split
        data object Orphaned : Split
    }

    private fun splitPoint(raw: String, start: RecurrenceStart, target: RecurrenceKey, requiredKeys: Set<RecurrenceKey>, budget: Int): Split {
        val seed = when (start) {
            is RecurrenceStart.AllDay -> DateTime(start.date.year, start.date.monthValue - 1, start.date.dayOfMonth)
            is RecurrenceStart.Timed -> DateTime(TimeZone.getTimeZone(start.zone), start.instant.toEpochMilli())
        }
        val iterator = RecurrenceRule(raw, RecurrenceRule.RfcMode.RFC5545_STRICT).iterator(seed)
        var previous: RecurrenceKey? = null
        var found: Split.Found? = null
        val pending = requiredKeys.toMutableSet()
        repeat(budget) { index ->
            if (!iterator.hasNext()) return if (found == null) Split.Invalid else Split.Orphaned
            val next = iterator.nextDateTime()
            if (next.year !in 1..9999) return if (found == null) Split.Invalid else Split.Orphaned
            val current = when (start) {
                is RecurrenceStart.AllDay -> RecurrenceKey.Date(LocalDate.of(next.year, next.month + 1, next.dayOfMonth))
                is RecurrenceStart.Timed -> RecurrenceKey.Timed(Instant.ofEpochMilli(next.timestamp))
            }
            pending.remove(current)
            if (current == target) found = Split.Found(index, previous)
            if (found != null && pending.isEmpty()) return found
            if (found == null && !before(current, target)) return Split.Invalid
            previous = current
        }
        return Split.Exhausted
    }

    private fun validSnapshot(snapshot: RecurrenceSeriesSnapshot): Boolean {
        if (snapshot.ruleRaw.isNullOrBlank() && snapshot.rDatesRaw.isNullOrBlank()) return false
        val expectedDate = snapshot.start is RecurrenceStart.AllDay
        fun sameType(key: RecurrenceKey) = (key is RecurrenceKey.Date) == expectedDate
        if (snapshot.exDates.any { !sameType(it) }) return false
        if (snapshot.exDatesRaw.isNullOrBlank() && snapshot.exDates.isNotEmpty()) return false
        if (snapshot.exceptions.map { it.original }.distinct().size != snapshot.exceptions.size ||
            snapshot.exceptions.map { it.ref.eventId }.distinct().size != snapshot.exceptions.size) return false
        return snapshot.exceptions.all { exception ->
            sameType(exception.original) && exception.ref.calendarId == snapshot.identity.calendarId &&
                exception.ref.eventId != snapshot.identity.masterEventId &&
                exception.ref.recurrence?.masterEventId == snapshot.identity.masterEventId &&
                key(snapshot.start, exception.ref.recurrence.originalStart) == exception.original
        }
    }

    private fun startAt(start: RecurrenceStart, key: RecurrenceKey): RecurrenceStart = when (start) {
        is RecurrenceStart.AllDay -> RecurrenceStart.AllDay((key as RecurrenceKey.Date).date)
        is RecurrenceStart.Timed -> (key as RecurrenceKey.Timed).instant.atZone(start.zone).let {
            RecurrenceStart.Timed(it.toLocalDateTime(), start.zone, it.offset)
        }
    }

    private fun key(start: RecurrenceStart, instant: Instant): RecurrenceKey? = when (start) {
        is RecurrenceStart.AllDay -> instant.atOffset(ZoneOffset.UTC).let {
            if (it.toLocalTime() == LocalTime.MIDNIGHT) RecurrenceKey.Date(it.toLocalDate()) else null
        }
        is RecurrenceStart.Timed -> RecurrenceKey.Timed(instant)
    }

    private fun before(a: RecurrenceKey, b: RecurrenceKey): Boolean = when (a) {
        is RecurrenceKey.Date -> a.date < (b as RecurrenceKey.Date).date
        is RecurrenceKey.Timed -> a.instant < (b as RecurrenceKey.Timed).instant
    }
    private fun keyOrder(key: RecurrenceKey): Instant = when (key) {
        is RecurrenceKey.Date -> key.date.atStartOfDay().toInstant(ZoneOffset.UTC)
        is RecurrenceKey.Timed -> key.instant
    }
    private fun untilValue(key: RecurrenceKey): String = when (key) {
        is RecurrenceKey.Date -> key.date.format(DateTimeFormatter.BASIC_ISO_DATE)
        is RecurrenceKey.Timed -> utc.format(key.instant)
    }
    private fun replacePart(raw: String, name: String, value: String): String {
        val parts = raw.split(';')
        return if (parts.any { it.startsWith("$name=") }) parts.joinToString(";") { if (it.startsWith("$name=")) "$name=$value" else it }
        else "$raw;$name=$value"
    }
    private fun review(reason: String) = RecurrencePlanningResult.NeedsReview(reason)
    private fun invalid(reason: String) = RecurrencePlanningResult.Invalid(reason)
}
