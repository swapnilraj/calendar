package dev.swapnilraj.calendar.core

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone
import org.dmfs.rfc5545.DateTime
import org.dmfs.rfc5545.recur.RecurrenceRule

sealed interface RecurrenceDurationChange {
    /** Retain the exact source DURATION, including nominal P1D versus elapsed PT24H semantics. */
    data object KeepRaw : RecurrenceDurationChange
    data class TimedElapsed(val duration: Duration) : RecurrenceDurationChange {
        init { require(!duration.isNegative && !duration.isZero && duration.nano == 0) }
    }
    data class AllDayDays(val days: Long) : RecurrenceDurationChange {
        init { require(days > 0) }
    }
}

/** Dirty fields are independent; changing a pattern must never implicitly reset its COUNT/UNTIL. */
data class RecurrenceRuleChanges(
    val pattern: FieldChange<RecurrencePattern> = FieldChange.Keep,
    val interval: FieldChange<Int> = FieldChange.Keep,
    val weekStart: FieldChange<DayOfWeek> = FieldChange.Keep,
    val end: FieldChange<RecurrenceEnd> = FieldChange.Keep,
)

sealed interface RecurrenceEditIntent {
    /** Editor dates come from master DTSTART, never from the selected occurrence's actual dates. */
    data class WholeSeriesEdit(
        val expectedMasterStart: RecurrenceStart,
        val fields: EventPatch = EventPatch(),
        val newMasterStart: FieldChange<RecurrenceStart> = FieldChange.Keep,
        val duration: RecurrenceDurationChange = RecurrenceDurationChange.KeepRaw,
        val rule: RecurrenceRuleChanges = RecurrenceRuleChanges(),
    ) : RecurrenceEditIntent

    /**
     * Keep uses the nominal original boundary in the event zone, not a moved exception's actual date.
     * [selectedExceptionPatch] contains only additional explicitly edited override fields/time.
     */
    data class FollowingSeriesEdit(
        val expectedOriginalBoundary: RecurrenceKey,
        val fields: EventPatch = EventPatch(),
        val newTailStart: FieldChange<RecurrenceStart> = FieldChange.Keep,
        val duration: RecurrenceDurationChange = RecurrenceDurationChange.KeepRaw,
        val rule: RecurrenceRuleChanges = RecurrenceRuleChanges(),
        val selectedExceptionPatch: EventPatch = EventPatch(),
    ) : RecurrenceEditIntent
}

/** Pure, finite ordinal reassociation. Android mutations remain the provider adapter's responsibility. */
object RecurrenceEditing {
    private val utc = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)

    fun plan(
        snapshot: RecurrenceSeriesSnapshot,
        selected: EventRef,
        intent: RecurrenceEditIntent,
        budget: RecurrencePlanningBudget = RecurrencePlanningBudget(),
    ): RecurrencePlanningResult {
        // Reuse B5's fresh selection/source consistency checks. This is pure: no preflight actions
        // are executed or returned. It also allows unrelated field edits to preserve raw rules.
        val validation = RecurrencePlanning.plan(snapshot, selected, RecurrenceScope.ALL,
            RecurrenceMutation.Edit(EventPatch()), budget)
        if (validation !is RecurrencePlanningResult.Ready) return validation
        val original = originalKey(snapshot.start, requireNotNull(selected.recurrence).originalStart)
        val following = intent is RecurrenceEditIntent.FollowingSeriesEdit
        val fields: EventPatch
        val anchor: FieldChange<RecurrenceStart>
        val duration: RecurrenceDurationChange
        val changes: RecurrenceRuleChanges
        val selectedPatch: EventPatch
        when (intent) {
            is RecurrenceEditIntent.WholeSeriesEdit -> {
                if (intent.expectedMasterStart != snapshot.start) return review("Master editing context changed")
                fields = intent.fields; anchor = intent.newMasterStart; duration = intent.duration
                changes = intent.rule; selectedPatch = EventPatch()
            }
            is RecurrenceEditIntent.FollowingSeriesEdit -> {
                if (intent.expectedOriginalBoundary != original) return review("Original boundary editing context changed")
                fields = intent.fields; anchor = intent.newTailStart; duration = intent.duration
                changes = intent.rule; selectedPatch = intent.selectedExceptionPatch
            }
        }
        if (fields.time != FieldChange.Keep || fields.eventZone != FieldChange.Keep)
            return invalid("Series anchors must use the scope-specific start, not EventPatch.time/eventZone")
        val existingSelected = snapshot.exceptions.singleOrNull { it.original == original }
        if (selectedPatch != EventPatch() && existingSelected == null) return invalid("There is no selected override to patch")
        val effectiveSelected = try { combine(fields, selectedPatch) }
            catch (_: IllegalArgumentException) { return invalid("Conflicting selected override fields") }
        val needsTransform = anchor != FieldChange.Keep || changes != RecurrenceRuleChanges()
        if (!needsTransform && duration == RecurrenceDurationChange.KeepRaw && selectedPatch == EventPatch()) {
            return RecurrencePlanning.plan(snapshot, selected,
                if (following) RecurrenceScope.FOLLOWING else RecurrenceScope.ALL, RecurrenceMutation.Edit(fields), budget)
        }
        if (!snapshot.rDatesRaw.isNullOrBlank() || !snapshot.exRuleRaw.isNullOrBlank())
            return RecurrencePlanningResult.Unsupported("RDATE/EXRULE editing requires a verified set transformation")
        if (!snapshot.exDatesRaw.isNullOrBlank() && !snapshot.exDatesDecoded) return review("EXDATE decoding is incomplete")
        if (snapshot.exDates.size > budget.maxInstances) return review("Exclusion mapping budget exceeded")
        val raw = snapshot.ruleRaw ?: return RecurrencePlanningResult.Unsupported("A single RRULE is required")
        val parsed = RecurrenceRules.parse(raw, snapshot.start) as? ParsedRecurrence.Supported
            ?: return RecurrencePlanningResult.Unsupported("The imported RRULE cannot be safely edited")
        val futureKeys = snapshot.exDates.filter { !following || !before(it, original) }.toSet() +
            snapshot.exceptions.filter { !following || !before(it.original, original) }.map { it.original }
        val scan = scanOriginals(raw, snapshot.start, futureKeys + original, original, budget.maxInstances)
            ?: return review("Original identities cannot be proven within the recurrence budget")
        val boundaryOrdinal = scan.ordinals.getValue(original)
        val cut = if (following) boundaryOrdinal else 0
        val baseStart = if (following) startAt(snapshot.start, original) else snapshot.start
        val newStart = anchor.resolve(baseStart)
        if ((newStart is RecurrenceStart.AllDay) != (snapshot.start is RecurrenceStart.AllDay))
            return review("Timed/all-day series conversion requires additional reviewed mapping")
        if (duration is RecurrenceDurationChange.TimedElapsed && newStart !is RecurrenceStart.Timed ||
            duration is RecurrenceDurationChange.AllDayDays && newStart !is RecurrenceStart.AllDay)
            return invalid("Duration kind does not match the series")
        try {
            when (duration) {
                RecurrenceDurationChange.KeepRaw -> Unit
                is RecurrenceDurationChange.TimedElapsed -> {
                    val timed = newStart as RecurrenceStart.Timed
                    require(timed.instant.plus(duration.duration).atZone(timed.zone).year in 1..9999)
                }
                is RecurrenceDurationChange.AllDayDays -> require(newStart.date.plusDays(duration.days).year in 1..9999)
            }
        } catch (_: Exception) { return invalid("Duration exceeds the supported date range") }
        if (following && cut > 0 && duration == RecurrenceDurationChange.KeepRaw && snapshot.durationRaw.isNullOrBlank())
            return review("A new tail requires the original duration")
        val oldEnd = preservedEnd(parsed.spec.end, raw, cut, snapshot.start)
        val spec = try { RecurrenceSpec(changes.pattern.resolve(parsed.spec.pattern),
            changes.interval.resolve(parsed.spec.interval), changes.end.resolve(oldEnd), changes.weekStart.resolve(parsed.spec.weekStart)) }
            catch (_: IllegalArgumentException) { return invalid("Invalid recurrence replacement") }
        val newRule = try { RecurrenceRules.build(spec, newStart) }
            catch (_: Exception) { return review("New start and recurrence pattern/limit are incompatible") }
        val neededOrdinals = futureKeys.associateWith { scan.ordinals.getValue(it) - cut }
        val newKeys = scanOrdinals(newRule, newStart, neededOrdinals.values.toSet() + 0, budget.maxInstances)
            ?: return review("Some exclusions or overrides do not map into the new series")
        val remap = neededOrdinals.mapValues { newKeys.getValue(it.value) }
        if (remap.values.toSet().size != remap.size) return review("Original identity mapping collides")
        val newExclusions = snapshot.exDates.filter { !following || !before(it, original) }.mapTo(linkedSetOf()) { remap.getValue(it) }
        val actions = mutableListOf<RecurrencePlanAction>()
        val target = if (following && cut > 0) SeriesTarget.TAIL else SeriesTarget.EXISTING
        if (target == SeriesTarget.TAIL) {
            val count = (parsed.spec.end as? RecurrenceEnd.Count)?.count
            val prefixRule = if (count != null) replacePart(raw, "COUNT", cut.toString())
                else replacePart(raw, "UNTIL", untilValue(requireNotNull(scan.previousBoundary)))
            actions += RecurrencePlanAction.UpdateMaster(rule = FieldChange.Set(prefixRule),
                exclusions = FieldChange.Set(snapshot.exDates.filterTo(linkedSetOf()) { before(it, original) }))
            actions += RecurrencePlanAction.InsertTail(newStart, newRule, newExclusions, fields, duration)
        } else {
            actions += RecurrencePlanAction.UpdateMaster(fields, FieldChange.Set(newRule), FieldChange.Set(newExclusions),
                if (anchor == FieldChange.Keep) FieldChange.Keep else FieldChange.Set(newStart), duration)
        }
        snapshot.exceptions.filter { !following || !before(it.original, original) }
            .sortedWith(compareBy({ ordinal(scan, it.original) }, { it.ref.eventId })).forEach { exception ->
                actions += RecurrencePlanAction.RemapException(target, exception, remap.getValue(exception.original),
                    if (following && exception.original == original) effectiveSelected else EventPatch())
            }
        return RecurrencePlanningResult.Ready(snapshot.identity, actions.toList())
    }

    private data class OriginalScan(val ordinals: Map<RecurrenceKey, Int>, val previousBoundary: RecurrenceKey?)

    private fun scanOriginals(raw: String, start: RecurrenceStart, required: Set<RecurrenceKey>, boundary: RecurrenceKey,
        budget: Int): OriginalScan? {
        return try {
            if (required.size > budget) return null
            val found = mutableMapOf<RecurrenceKey, Int>()
            val ordered = required.sortedWith { a, b -> if (before(a, b)) -1 else if (before(b, a)) 1 else 0 }
            var nextRequired = 0
            var previous: RecurrenceKey? = null
            var previousBoundary: RecurrenceKey? = null
            val iterator = iterator(raw, start)
            for (index in 0 until budget) {
                if (!iterator.hasNext()) return null
                val current = readKey(iterator.nextDateTime(), start)
                val wanted = ordered[nextRequired]
                if (current == wanted) { found[current] = index; nextRequired++ }
                else if (before(wanted, current)) return null
                if (current == boundary) previousBoundary = previous
                if (found.size == required.size) return OriginalScan(found.toMap(), previousBoundary)
                previous = current
            }
            null
        } catch (_: Exception) { null }
    }

    private fun scanOrdinals(raw: String, start: RecurrenceStart, required: Set<Int>, budget: Int): Map<Int, RecurrenceKey>? {
        return try {
            if (required.any { it < 0 || it >= budget }) return null
            val found = mutableMapOf<Int, RecurrenceKey>()
            val iterator = iterator(raw, start)
            for (index in 0 until budget) {
                if (!iterator.hasNext()) return null
                val current = readKey(iterator.nextDateTime(), start)
                if (index in required) found[index] = current
                if (found.size == required.size) return found.toMap()
            }
            null
        } catch (_: Exception) { null }
    }

    private fun iterator(raw: String, start: RecurrenceStart) = RecurrenceRule(raw, RecurrenceRule.RfcMode.RFC5545_STRICT)
        .iterator(when (start) {
            is RecurrenceStart.AllDay -> DateTime(start.date.year, start.date.monthValue - 1, start.date.dayOfMonth)
            is RecurrenceStart.Timed -> DateTime(TimeZone.getTimeZone(start.zone), start.instant.toEpochMilli())
        })

    private fun readKey(value: DateTime, start: RecurrenceStart): RecurrenceKey {
        require(value.year in 1..9999)
        val date = LocalDate.of(value.year, value.month + 1, value.dayOfMonth)
        return when (start) {
            is RecurrenceStart.AllDay -> RecurrenceKey.Date(date)
            is RecurrenceStart.Timed -> {
                val local = date.atTime(value.hours, value.minutes, value.seconds)
                // Do not guess at a library/provider disagreement around nonexistent local times.
                require(start.zone.rules.getValidOffsets(local).isNotEmpty())
                val instant = Instant.ofEpochMilli(value.timestamp)
                require(instant.atZone(start.zone).toLocalDateTime() == local)
                RecurrenceKey.Timed(instant)
            }
        }
    }

    private fun preservedEnd(end: RecurrenceEnd, raw: String, cut: Int, start: RecurrenceStart): RecurrenceEnd = when (end) {
        is RecurrenceEnd.Count -> RecurrenceEnd.Count(end.count - cut)
        is RecurrenceEnd.ThroughDate -> if (start is RecurrenceStart.AllDay) end else {
            val value = raw.split(';').single { it.startsWith("UNTIL=") }.substringAfter('=')
            RecurrenceEnd.ExactUntil(Instant.from(utc.parse(value)))
        }
        else -> end
    }

    private fun startAt(start: RecurrenceStart, key: RecurrenceKey): RecurrenceStart = when (start) {
        is RecurrenceStart.AllDay -> RecurrenceStart.AllDay((key as RecurrenceKey.Date).date)
        is RecurrenceStart.Timed -> (key as RecurrenceKey.Timed).instant.atZone(start.zone).let {
            RecurrenceStart.Timed(it.toLocalDateTime(), start.zone, it.offset)
        }
    }
    private fun originalKey(start: RecurrenceStart, instant: Instant): RecurrenceKey = when (start) {
        is RecurrenceStart.AllDay -> RecurrenceKey.Date(instant.atOffset(ZoneOffset.UTC).toLocalDate())
        is RecurrenceStart.Timed -> RecurrenceKey.Timed(instant)
    }
    private fun before(a: RecurrenceKey, b: RecurrenceKey) = when (a) {
        is RecurrenceKey.Date -> a.date < (b as RecurrenceKey.Date).date
        is RecurrenceKey.Timed -> a.instant < (b as RecurrenceKey.Timed).instant
    }
    private fun ordinal(scan: OriginalScan, key: RecurrenceKey) = scan.ordinals.getValue(key)
    private fun untilValue(key: RecurrenceKey) = when (key) {
        is RecurrenceKey.Date -> key.date.format(DateTimeFormatter.BASIC_ISO_DATE)
        is RecurrenceKey.Timed -> utc.format(key.instant)
    }
    private fun replacePart(raw: String, name: String, value: String): String = raw.split(';').let { parts ->
        if (parts.any { it.startsWith("$name=") }) parts.joinToString(";") { if (it.startsWith("$name=")) "$name=$value" else it }
        else "$raw;$name=$value"
    }
    private fun <T> FieldChange<T>.resolve(old: T): T = when (this) { FieldChange.Keep -> old; is FieldChange.Set -> value }
    private fun combine(base: EventPatch, extra: EventPatch): EventPatch {
        fun <T> field(a: FieldChange<T>, b: FieldChange<T>): FieldChange<T> {
            require(a == FieldChange.Keep || b == FieldChange.Keep || a == b)
            return if (b == FieldChange.Keep) a else b
        }
        return base.copy(
            title = field(base.title, extra.title),
            time = field(base.time, extra.time),
            eventZone = field(base.eventZone, extra.eventZone),
            location = field(base.location, extra.location),
            description = field(base.description, extra.description),
            alertMinutes = field(base.alertMinutes, extra.alertMinutes),
            availability = field(base.availability, extra.availability),
            privacy = field(base.privacy, extra.privacy),
        )
    }
    private fun invalid(reason: String) = RecurrencePlanningResult.Invalid(reason)
    private fun review(reason: String) = RecurrencePlanningResult.NeedsReview(reason)
}
