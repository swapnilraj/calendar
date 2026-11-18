package dev.swapnilraj.calendar.core

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle
import java.util.Locale

enum class AllDayDateTimePolicy { STRICT_DATE, PROVIDER_UTC_MIDNIGHT }

data class RecurrenceDateBudget(val maxValues: Int = 10_000, val maxChars: Int = 1_000_000) {
    init { require(maxValues in 1..100_000 && maxChars in 1..10_000_000) }
}

enum class RecurrenceDateProblem {
    INVALID_SYNTAX, INVALID_DATE, INVALID_TIME, TYPE_MISMATCH, CONTRADICTORY_PARAMETERS,
    UNKNOWN_TIME_ZONE, FLOATING_TIME, AMBIGUOUS_TIME, NONEXISTENT_TIME, PERIOD_VALUE,
    UNSUPPORTED_PARAMETER, UNSUPPORTED_PROPERTY, UNSUPPORTED_PRECISION, LEAP_SECOND,
    FOLDED_LINE, VALUE_LIMIT, INPUT_LIMIT,
}

/** Raw is retained exactly, including null, formatting and duplicate values, even on failure. */
sealed interface ParsedRecurrenceDates {
    val raw: String?
    data class Supported(override val raw: String?, val keys: Set<RecurrenceKey>) : ParsedRecurrenceDates
    data class Unsupported(override val raw: String?, val reason: RecurrenceDateProblem) : ParsedRecurrenceDates
    data class Invalid(override val raw: String?, val reason: RecurrenceDateProblem) : ParsedRecurrenceDates
}

/**
 * Finite RDATE/EXDATE values only, never a recurrence engine. The start determines DATE versus
 * timed identity; it does not supply a guessed zone for floating values. Explicit local values
 * require a known TZID/Android zone prefix and a unique valid offset. No display zone is involved.
 *
 * Only callers that know an all-day provider field uses UTC-midnight storage may opt into
 * PROVIDER_UTC_MIDNIGHT. Even then non-midnight, non-fixed-zero local zones are not coerced.
 * Unsupported inputs stay available as raw data for unrelated edits; no partial key set is returned.
 */
object RecurrenceDateCodec {
    private val date = Regex("[0-9]{8}")
    private val dateTime = Regex("[0-9]{8}T[0-9]{6}Z?", RegexOption.IGNORE_CASE)
    private val fraction = Regex("[0-9]{8}T[0-9]{6}\\.[0-9]+Z?", RegexOption.IGNORE_CASE)
    private val localFormatter = DateTimeFormatterBuilder().parseCaseInsensitive()
        .appendPattern("uuuuMMdd'T'HHmmss").toFormatter(Locale.ROOT).withResolverStyle(ResolverStyle.STRICT)

    fun parse(
        raw: String?,
        start: RecurrenceStart,
        budget: RecurrenceDateBudget = RecurrenceDateBudget(),
        allDayPolicy: AllDayDateTimePolicy = AllDayDateTimePolicy.STRICT_DATE,
    ): ParsedRecurrenceDates {
        if (raw == null || raw.isEmpty()) return ParsedRecurrenceDates.Supported(raw, emptySet())
        if (raw.length > budget.maxChars) return ParsedRecurrenceDates.Unsupported(raw, RecurrenceDateProblem.INPUT_LIMIT)
        // Count serialized values before deduplication; repeated identical dates still consume work.
        val lines = raw.count { it == '\n' } + 1
        if (raw.count { it == ',' } + lines - (if (raw.endsWith('\n')) 1 else 0) > budget.maxValues)
            return ParsedRecurrenceDates.Unsupported(raw, RecurrenceDateProblem.VALUE_LIMIT)
        return try {
            val normalized = raw.replace("\r\n", "\n")
            if ('\r' in normalized) invalid(RecurrenceDateProblem.INVALID_SYNTAX)
            val groups = normalized.removeSuffix("\n").split('\n')
            if (groups.any { it.startsWith(' ') || it.startsWith('\t') }) unsupported(RecurrenceDateProblem.FOLDED_LINE)
            val keys = linkedSetOf<RecurrenceKey>()
            var count = 0
            var propertyName: String? = null
            groups.forEach { line ->
                if (line.isEmpty()) invalid(RecurrenceDateProblem.INVALID_SYNTAX)
                val header = header(line)
                if (header.propertyName != null) {
                    if (propertyName != null && propertyName != header.propertyName) invalid(RecurrenceDateProblem.TYPE_MISMATCH)
                    propertyName = header.propertyName
                }
                if (header.kind == Kind.PERIOD) unsupported(RecurrenceDateProblem.PERIOD_VALUE)
                header.values.split(',').forEach { token ->
                    if (++count > budget.maxValues) unsupported(RecurrenceDateProblem.VALUE_LIMIT)
                    keys += decode(token, header, start, allDayPolicy)
                }
            }
            ParsedRecurrenceDates.Supported(raw, keys.toSet())
        } catch (rejected: Rejected) {
            if (rejected.unsupported) ParsedRecurrenceDates.Unsupported(raw, rejected.problem)
            else ParsedRecurrenceDates.Invalid(raw, rejected.problem)
        } catch (_: IllegalArgumentException) {
            ParsedRecurrenceDates.Invalid(raw, RecurrenceDateProblem.INVALID_SYNTAX)
        }
    }

    private enum class Kind { DATE, DATE_TIME, PERIOD }
    private data class Header(val values: String, val kind: Kind? = null, val zone: ZoneId? = null,
        val legacy: Boolean = false, val propertyName: String? = null)

    private fun header(line: String): Header {
        val property = Regex("^(RDATE|EXDATE)(:|;)", RegexOption.IGNORE_CASE).containsMatchIn(line)
        val named = line.startsWith("VALUE=", ignoreCase = true) || line.startsWith("TZID=", ignoreCase = true)
        if (property || named) {
            val colon = delimiter(line, ':')
            if (colon < 0) invalid(RecurrenceDateProblem.INVALID_SYNTAX)
            val parts = splitOutsideQuotes(line.substring(0, colon), ';').let { if (property) it.drop(1) else it }
            var kind: Kind? = null
            var zone: ZoneId? = null
            val seen = mutableSetOf<String>()
            parts.forEach { parameter ->
                if (parameter.count { it == '=' } != 1) invalid(RecurrenceDateProblem.INVALID_SYNTAX)
                val name = parameter.substringBefore('=').uppercase(Locale.ROOT)
                if (!seen.add(name)) invalid(RecurrenceDateProblem.CONTRADICTORY_PARAMETERS)
                val value = unquote(parameter.substringAfter('='))
                when (name) {
                    "VALUE" -> kind = when (value.uppercase(Locale.ROOT)) {
                        "DATE" -> Kind.DATE
                        "DATE-TIME" -> Kind.DATE_TIME
                        "PERIOD" -> Kind.PERIOD
                        else -> unsupported(RecurrenceDateProblem.UNSUPPORTED_PARAMETER)
                    }
                    "TZID" -> zone = knownZone(value)
                    else -> unsupported(RecurrenceDateProblem.UNSUPPORTED_PARAMETER)
                }
            }
            if (kind == Kind.DATE && zone != null) invalid(RecurrenceDateProblem.CONTRADICTORY_PARAMETERS)
            val propertyName = if (property) line.substringBefore(';').substringBefore(':').uppercase(Locale.ROOT) else null
            return Header(line.substring(colon + 1), kind, zone, propertyName = propertyName)
        }
        val semicolon = delimiter(line, ';')
        if (semicolon >= 0) {
            // Android CalendarContract stores a bare zone followed by ';', not RFC TZID syntax.
            val prefix = line.substring(0, semicolon)
            if ('=' in prefix) unsupported(RecurrenceDateProblem.UNSUPPORTED_PARAMETER)
            val values = line.substring(semicolon + 1)
            if (';' in values) invalid(RecurrenceDateProblem.INVALID_SYNTAX)
            return Header(values, zone = knownZone(unquote(prefix)), legacy = true)
        }
        if (delimiter(line, ':') >= 0) unsupported(RecurrenceDateProblem.UNSUPPORTED_PROPERTY)
        return Header(line)
    }

    private fun decode(token: String, header: Header, start: RecurrenceStart, policy: AllDayDateTimePolicy): RecurrenceKey {
        if (token.isEmpty() || token.any { it.isWhitespace() }) invalid(RecurrenceDateProblem.INVALID_SYNTAX)
        if ('/' in token) unsupported(RecurrenceDateProblem.PERIOD_VALUE)
        if (date.matches(token)) {
            if (header.kind == Kind.DATE_TIME || header.zone != null || start !is RecurrenceStart.AllDay)
                invalid(RecurrenceDateProblem.TYPE_MISMATCH)
            val parsed = try { LocalDate.parse(token, DateTimeFormatter.BASIC_ISO_DATE) }
                catch (_: DateTimeException) { invalid(RecurrenceDateProblem.INVALID_DATE) }
            if (parsed.year !in 1..9999) invalid(RecurrenceDateProblem.INVALID_DATE)
            return RecurrenceKey.Date(parsed)
        }
        if (fraction.matches(token)) unsupported(RecurrenceDateProblem.UNSUPPORTED_PRECISION)
        if (!dateTime.matches(token)) invalid(RecurrenceDateProblem.INVALID_SYNTAX)
        if (header.kind == Kind.DATE) invalid(RecurrenceDateProblem.TYPE_MISMATCH)
        if (start is RecurrenceStart.AllDay && policy == AllDayDateTimePolicy.STRICT_DATE)
            invalid(RecurrenceDateProblem.TYPE_MISMATCH)
        if (token.substring(13, 15) == "60") unsupported(RecurrenceDateProblem.LEAP_SECOND)
        val zulu = token.endsWith('Z', ignoreCase = true)
        val local = try { LocalDateTime.parse(if (zulu) token.dropLast(1) else token, localFormatter) }
            catch (_: DateTimeException) { invalid(RecurrenceDateProblem.INVALID_TIME) }
        if (local.year !in 1..9999) invalid(RecurrenceDateProblem.INVALID_DATE)
        // RFC TZID does not apply to UTC-Z values. Android's legacy zone prefix explicitly permits
        // a Z value to override that prefix, matching the platform's per-token parse behaviour.
        if (zulu && header.zone != null && !header.legacy) invalid(RecurrenceDateProblem.CONTRADICTORY_PARAMETERS)
        val explicitZero = header.zone?.let { it.rules.isFixedOffset && it.rules.getOffset(Instant.EPOCH) == ZoneOffset.UTC } == true
        if (start is RecurrenceStart.AllDay && (!zulu && !explicitZero || local.toLocalTime() != LocalTime.MIDNIGHT))
            invalid(RecurrenceDateProblem.TYPE_MISMATCH)
        val instant = if (zulu) local.toInstant(ZoneOffset.UTC) else {
            val zone = header.zone ?: unsupported(RecurrenceDateProblem.FLOATING_TIME)
            val offsets = zone.rules.getValidOffsets(local)
            if (offsets.isEmpty()) unsupported(RecurrenceDateProblem.NONEXISTENT_TIME)
            if (offsets.size != 1) unsupported(RecurrenceDateProblem.AMBIGUOUS_TIME)
            local.toInstant(offsets.single())
        }
        return if (start is RecurrenceStart.AllDay) RecurrenceKey.Date(instant.atOffset(ZoneOffset.UTC).toLocalDate())
            else RecurrenceKey.Timed(instant)
    }

    private fun knownZone(value: String): ZoneId = try { ZoneId.of(value) }
        catch (_: DateTimeException) { unsupported(RecurrenceDateProblem.UNKNOWN_TIME_ZONE) }

    private fun unquote(value: String): String {
        if (value.isEmpty()) invalid(RecurrenceDateProblem.INVALID_SYNTAX)
        if ('\\' in value) unsupported(RecurrenceDateProblem.UNSUPPORTED_PARAMETER)
        if (value.startsWith('"') && value.endsWith('"') && value.length > 2) return value.substring(1, value.length - 1)
        if ('"' in value) invalid(RecurrenceDateProblem.INVALID_SYNTAX)
        return value
    }

    private fun delimiter(value: String, wanted: Char): Int {
        var quoted = false
        value.forEachIndexed { index, char ->
            if (char == '"') quoted = !quoted
            else if (!quoted && char == wanted) return index
        }
        if (quoted) invalid(RecurrenceDateProblem.INVALID_SYNTAX)
        return -1
    }
    private fun splitOutsideQuotes(value: String, separator: Char): List<String> {
        val result = mutableListOf<String>()
        var begin = 0
        while (begin <= value.length) {
            val index = delimiter(value.substring(begin), separator)
            if (index < 0) { result += value.substring(begin); break }
            result += value.substring(begin, begin + index)
            begin += index + 1
        }
        return result
    }
    private class Rejected(val problem: RecurrenceDateProblem, val unsupported: Boolean) : RuntimeException()
    private fun invalid(problem: RecurrenceDateProblem): Nothing = throw Rejected(problem, false)
    private fun unsupported(problem: RecurrenceDateProblem): Nothing = throw Rejected(problem, true)
}
