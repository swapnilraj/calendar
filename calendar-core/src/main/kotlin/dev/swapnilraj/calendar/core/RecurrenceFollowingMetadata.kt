package dev.swapnilraj.calendar.core

import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone
import org.dmfs.rfc5545.DateTime
import org.dmfs.rfc5545.recur.RecurrenceRule

enum class RecurrenceFollowingMetadataProblem {
    INPUT_BUDGET, SOURCE, INTENT, RULE, EXCLUSIONS, SELECTION, ORIGINALS, CLOCK, DURATION,
}

sealed interface RecurrenceFollowingMetadataResult {
    data class Whole(
        val plan: RecurrencePlanningResult.Ready,
        val ruleRaw: String,
        val exDatesRaw: String?,
    ) : RecurrenceFollowingMetadataResult
    data class Split(
        val plan: RecurrencePlanningResult.Ready,
        val boundaryOrdinal: Int,
        val prefixRuleRaw: String,
        val tailStart: RecurrenceStart,
        val tailRuleRaw: String,
        val prefixExDatesRaw: String?,
        val tailExDatesRaw: String?,
    ) : RecurrenceFollowingMetadataResult
    data class NeedsReview(val problem: RecurrenceFollowingMetadataProblem) : RecurrenceFollowingMetadataResult
}

/**
 * Metadata-only semantic proof, not a provider execution capability. No caller-supplied plan is
 * accepted. An adapter must repeat this proof against its fresh complete source and separately
 * prove native exception transfer, cache invalidation, child preservation and clone admission.
 * The master/tail anchor, zone, raw duration and pattern must be kept. Explicit selected-override
 * fields (including its appointment) retain the existing intent semantics and never rebase the tail.
 * Nonselected exception appointments, cancellation and payloads remain unchanged.
 */
object RecurrenceFollowingMetadata {
    private val utc = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)
    private val dateToken = Regex("[0-9]{8}")
    private val utcToken = Regex("[0-9]{8}T[0-9]{6}Z")

    fun resolve(
        snapshot: RecurrenceSeriesSnapshot,
        selected: EventRef,
        intent: RecurrenceEditIntent.FollowingSeriesEdit,
        budget: RecurrencePlanningBudget = RecurrencePlanningBudget(),
        dateBudget: RecurrenceDateBudget = RecurrenceDateBudget(),
    ): RecurrenceFollowingMetadataResult {
        fun review(problem: RecurrenceFollowingMetadataProblem) = RecurrenceFollowingMetadataResult.NeedsReview(problem)
        if (budget.maxInstances > RecurrenceOrdinalMapping.MAX_CANDIDATES_PER_SIDE ||
            snapshot.exceptions.size > budget.maxExceptions || snapshot.exDates.size > dateBudget.maxValues)
            return review(RecurrenceFollowingMetadataProblem.INPUT_BUDGET)
        val raw = snapshot.ruleRaw ?: return review(RecurrenceFollowingMetadataProblem.RULE)
        if (raw.length > RecurrenceMasterTemplates.MAX_RULE_CHARS ||
            snapshot.exDatesRaw?.length?.let { it > dateBudget.maxChars } == true)
            return review(RecurrenceFollowingMetadataProblem.INPUT_BUDGET)
        if (intent.newTailStart != FieldChange.Keep || intent.duration != RecurrenceDurationChange.KeepRaw ||
            intent.rule != RecurrenceRuleChanges() || intent.fields.time != FieldChange.Keep ||
            intent.fields.eventZone != FieldChange.Keep)
            return review(RecurrenceFollowingMetadataProblem.INTENT)
        if (!snapshot.rDatesRaw.isNullOrEmpty() || !snapshot.exRuleRaw.isNullOrEmpty())
            return review(RecurrenceFollowingMetadataProblem.SOURCE)
        if (RecurrencePlanning.plan(snapshot, selected, RecurrenceScope.ALL,
                RecurrenceMutation.Edit(EventPatch()), budget) !is RecurrencePlanningResult.Ready)
            return review(RecurrenceFollowingMetadataProblem.SOURCE)
        val parsed = RecurrenceRules.parse(raw, snapshot.start) as? ParsedRecurrence.Supported
            ?: return review(RecurrenceFollowingMetadataProblem.RULE)
        val boundary = original(snapshot.start, requireNotNull(selected.recurrence).originalStart)
        if (boundary != intent.expectedOriginalBoundary) return review(RecurrenceFollowingMetadataProblem.SELECTION)
        val selectedException = snapshot.exceptions.singleOrNull { it.original == boundary }
        if (selectedException != null && selectedException.ref != selected ||
            selectedException == null && intent.selectedExceptionPatch != EventPatch())
            return review(RecurrenceFollowingMetadataProblem.SELECTION)
        val selectedPatch = merge(intent.fields, intent.selectedExceptionPatch)
            ?: return review(RecurrenceFollowingMetadataProblem.INTENT)
        val exclusions = nativeDates(snapshot, dateBudget)
            ?: return review(RecurrenceFollowingMetadataProblem.EXCLUSIONS)
        if (boundary in snapshot.exDates && selectedException == null)
            return review(RecurrenceFollowingMetadataProblem.SELECTION)
        val scan = try {
            scan(raw, snapshot.start, boundary, snapshot.exceptions.map { it.original }.toSet(), budget.maxInstances)
        } catch (_: ClockUncertainty) {
            return review(RecurrenceFollowingMetadataProblem.CLOCK)
        } catch (_: Exception) {
            return review(RecurrenceFollowingMetadataProblem.ORIGINALS)
        } ?: return review(RecurrenceFollowingMetadataProblem.ORIGINALS)
        val ordered = snapshot.exceptions.sortedWith(compareBy({ scan.ordinals.getValue(it.original) }, { it.ref.eventId }))
        if (scan.cut == 0) {
            val actions = buildList {
                add(RecurrencePlanAction.UpdateMaster(patch = intent.fields))
                if (selectedException != null) add(RecurrencePlanAction.UpsertException(
                    SeriesTarget.EXISTING, boundary, selectedException.ref, selectedPatch))
            }
            return RecurrenceFollowingMetadataResult.Whole(
                RecurrencePlanningResult.Ready(snapshot.identity, actions), raw, snapshot.exDatesRaw)
        }
        val tailStart = startAt(snapshot.start, boundary)
        if (RecurrenceMasterTemplates.resolve(snapshot) !is RecurrenceMasterTemplateResult.Ready ||
            RecurrenceMasterTemplates.resolve(tailStart, snapshot.durationRaw) !is RecurrenceMasterTemplateResult.Ready)
            return review(RecurrenceFollowingMetadataProblem.DURATION)
        val count = (parsed.spec.end as? RecurrenceEnd.Count)?.count
        if (count != null && count <= scan.cut) return review(RecurrenceFollowingMetadataProblem.ORIGINALS)
        val prefixRule = if (count != null) replace(raw, "COUNT", scan.cut.toString())
            else replace(raw, "UNTIL", serialize(requireNotNull(scan.previous)))
        val tailRule = if (count != null) replace(raw, "COUNT", (count - scan.cut).toString()) else raw
        val prefixKeys = snapshot.exDates.filterTo(linkedSetOf()) { before(it, boundary) }
        val tailKeys = snapshot.exDates.filterTo(linkedSetOf()) { !before(it, boundary) }
        val actions = buildList {
            add(RecurrencePlanAction.UpdateMaster(rule = FieldChange.Set(prefixRule), exclusions = FieldChange.Set(prefixKeys)))
            add(RecurrencePlanAction.InsertTail(tailStart, tailRule, tailKeys, intent.fields))
            ordered.filter { !before(it.original, boundary) }.forEach { exception ->
                add(RecurrencePlanAction.RebindExceptionToTail(exception,
                    if (exception.original == boundary) selectedPatch else EventPatch()))
            }
        }
        return RecurrenceFollowingMetadataResult.Split(RecurrencePlanningResult.Ready(snapshot.identity, actions),
            scan.cut, prefixRule, tailStart, tailRule, exclusions.partition { before(it, boundary) },
            exclusions.partition { !before(it, boundary) })
    }

    private data class Token(val raw: String, val key: RecurrenceKey)
    private data class Group(val prefix: String, val tokens: List<Token>)
    private data class NativeDates(val originalRaw: String?, val groups: List<Group>, val trailingLf: Boolean) {
        fun partition(keep: (RecurrenceKey) -> Boolean): String? {
            if (groups.isEmpty()) return originalRaw
            val lines = groups.mapNotNull { group ->
                group.tokens.filter { keep(it.key) }.takeIf { it.isNotEmpty() }
                    ?.joinToString(",", group.prefix) { it.raw }
            }
            return if (lines.isEmpty()) null else lines.joinToString("\n") + if (trailingLf) "\n" else ""
        }
    }

    /** Native CalendarCommon syntax only; the broader RFC date codec alone is not write proof. */
    private fun nativeDates(snapshot: RecurrenceSeriesSnapshot, budget: RecurrenceDateBudget): NativeDates? {
        val raw = snapshot.exDatesRaw
        if (raw.isNullOrEmpty()) return if (snapshot.exDates.isEmpty()) NativeDates(raw, emptyList(), false) else null
        if (!snapshot.exDatesDecoded || raw.length > budget.maxChars || '\r' in raw) return null
        val groupCount = raw.count { it == '\n' } + 1 - if (raw.endsWith('\n')) 1 else 0
        if (raw.count { it == ',' } + groupCount > budget.maxValues) return null
        val lines = raw.removeSuffix("\n").split('\n')
        val groups = mutableListOf<Group>()
        val keys = linkedSetOf<RecurrenceKey>()
        for (line in lines) {
            val prefix = if (line.startsWith("UTC;")) "UTC;" else ""
            val values = line.removePrefix(prefix).split(',')
            val tokens = mutableListOf<Token>()
            for (value in values) {
                if (!utcToken.matches(value) && !(snapshot.start is RecurrenceStart.AllDay && dateToken.matches(value))) return null
                val decoded = RecurrenceDateCodec.parse(value, snapshot.start, budget,
                    AllDayDateTimePolicy.PROVIDER_UTC_MIDNIGHT) as? ParsedRecurrenceDates.Supported ?: return null
                val key = decoded.keys.singleOrNull() ?: return null
                keys += key
                tokens += Token(value, key)
            }
            groups += Group(prefix, tokens)
        }
        if (keys != snapshot.exDates) return null
        return NativeDates(raw, groups, raw.endsWith('\n'))
    }

    private data class Scan(val cut: Int, val previous: RecurrenceKey?, val ordinals: Map<RecurrenceKey, Int>)
    private fun scan(raw: String, start: RecurrenceStart, boundary: RecurrenceKey,
        required: Set<RecurrenceKey>, budget: Int): Scan? {
        if (start is RecurrenceStart.Timed && (!ProviderTimeZoneCompatibility.isCompatible(start.zone) ||
                start.zone.rules.getValidOffsets(start.localDateTime).size > 1 && start.preferredOffset == null)) throw ClockUncertainty()
        val iterator = RecurrenceRule(raw, RecurrenceRule.RfcMode.RFC5545_STRICT).iterator(when (start) {
            is RecurrenceStart.AllDay -> DateTime(start.date.year, start.date.monthValue - 1, start.date.dayOfMonth)
            is RecurrenceStart.Timed -> DateTime(TimeZone.getTimeZone(start.zone), start.instant.toEpochMilli())
        })
        val needed = (required + boundary).sortedWith { a, b ->
            if (before(a, b)) -1 else if (before(b, a)) 1 else 0
        }
        var nextNeeded = 0
        val ordinals = mutableMapOf<RecurrenceKey, Int>()
        var previous: RecurrenceKey? = null
        var beforeBoundary: RecurrenceKey? = null
        var cut: Int? = null
        repeat(budget) { index ->
            if (!iterator.hasNext()) return null
            val value = iterator.nextDateTime()
            if (value.year !in 1..9999) return null
            val date = LocalDate.of(value.year, value.month + 1, value.dayOfMonth)
            val current = when (start) {
                is RecurrenceStart.AllDay -> RecurrenceKey.Date(date)
                is RecurrenceStart.Timed -> {
                    val local = date.atTime(value.hours, value.minutes, value.seconds)
                    val offsets = start.zone.rules.getValidOffsets(local)
                    val instant = if (index == 0 && local == start.localDateTime) start.instant else {
                        if (offsets.size != 1 || local.toLocalTime() != start.localDateTime.toLocalTime()) throw ClockUncertainty()
                        local.toInstant(offsets.single())
                    }
                    if (instant.toEpochMilli() != value.timestamp) throw ClockUncertainty()
                    RecurrenceKey.Timed(instant)
                }
            }
            if (previous != null && !before(previous!!, current)) return null
            val wanted = needed[nextNeeded]
            if (before(wanted, current)) return null
            if (wanted == current) { ordinals[current] = index; nextNeeded++ }
            if (current == boundary) { cut = index; beforeBoundary = previous }
            if (nextNeeded == needed.size) return Scan(requireNotNull(cut), beforeBoundary, ordinals.toMap())
            previous = current
        }
        return null
    }

    private fun merge(base: EventPatch, extra: EventPatch): EventPatch? = try {
        fun <T> field(a: FieldChange<T>, b: FieldChange<T>): FieldChange<T> {
            require(a == FieldChange.Keep || b == FieldChange.Keep || a == b)
            return if (b == FieldChange.Keep) a else b
        }
        base.copy(title = field(base.title, extra.title), time = field(base.time, extra.time),
            eventZone = field(base.eventZone, extra.eventZone), location = field(base.location, extra.location),
            description = field(base.description, extra.description), alertMinutes = field(base.alertMinutes, extra.alertMinutes),
            availability = field(base.availability, extra.availability), privacy = field(base.privacy, extra.privacy))
    } catch (_: IllegalArgumentException) { null }

    private fun original(start: RecurrenceStart, instant: java.time.Instant): RecurrenceKey = when (start) {
        is RecurrenceStart.AllDay -> RecurrenceKey.Date(instant.atOffset(ZoneOffset.UTC).toLocalDate())
        is RecurrenceStart.Timed -> RecurrenceKey.Timed(instant)
    }
    private fun startAt(start: RecurrenceStart, key: RecurrenceKey): RecurrenceStart = when (start) {
        is RecurrenceStart.AllDay -> RecurrenceStart.AllDay((key as RecurrenceKey.Date).date)
        is RecurrenceStart.Timed -> (key as RecurrenceKey.Timed).instant.atZone(start.zone).let {
            RecurrenceStart.Timed(it.toLocalDateTime(), start.zone, it.offset)
        }
    }
    private fun before(a: RecurrenceKey, b: RecurrenceKey): Boolean = when (a) {
        is RecurrenceKey.Date -> a.date < (b as RecurrenceKey.Date).date
        is RecurrenceKey.Timed -> a.instant < (b as RecurrenceKey.Timed).instant
    }
    private fun serialize(key: RecurrenceKey): String = when (key) {
        is RecurrenceKey.Date -> key.date.format(DateTimeFormatter.BASIC_ISO_DATE)
        is RecurrenceKey.Timed -> utc.format(key.instant)
    }
    private fun replace(raw: String, name: String, value: String): String = raw.split(';').let { parts ->
        if (parts.any { it.startsWith("$name=") }) parts.joinToString(";") { if (it.startsWith("$name=")) "$name=$value" else it }
        else "$raw;$name=$value"
    }
    private class ClockUncertainty : Exception()
}
