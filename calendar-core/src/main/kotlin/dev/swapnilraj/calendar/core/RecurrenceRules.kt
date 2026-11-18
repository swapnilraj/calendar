package dev.swapnilraj.calendar.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.MonthDay
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import java.util.TimeZone
import org.dmfs.rfc5545.DateTime
import org.dmfs.rfc5545.recur.RecurrenceRule

sealed interface RecurrenceStart {
    val date: LocalDate

    data class AllDay(override val date: LocalDate) : RecurrenceStart {
        init { requireRfcDate(date) }
    }

    /** Gap inputs are rejected. Ambiguous inputs use [preferredOffset], or the earlier offset. */
    data class Timed(
        val localDateTime: LocalDateTime,
        val zone: ZoneId,
        val preferredOffset: ZoneOffset? = null,
    ) : RecurrenceStart {
        override val date: LocalDate get() = localDateTime.toLocalDate()

        init {
            requireRfcDate(date)
            require(localDateTime.nano == 0) { "Recurrence starts require second precision" }
            val offsets = zone.rules.getValidOffsets(localDateTime)
            require(offsets.isNotEmpty()) { "The recurrence start falls in a clock gap" }
            require(preferredOffset == null || preferredOffset in offsets) { "Invalid start offset for event zone" }
        }

        val instant: Instant get() = ZonedDateTime.ofLocal(localDateTime, zone, preferredOffset).toInstant()
    }
}

sealed interface RecurrencePattern {
    data object Daily : RecurrencePattern
    data class Weekly(val days: Set<DayOfWeek>) : RecurrencePattern {
        init { require(days.isNotEmpty()) { "Choose at least one weekday" } }
    }
    data class MonthlyDate(val day: Int) : RecurrencePattern {
        init { require(day in 1..31) { "Invalid monthly date" } }
    }
    data class MonthlyWeekday(val ordinal: Int, val day: DayOfWeek) : RecurrencePattern {
        init { require(ordinal in -5..5 && ordinal != 0) { "Invalid monthly weekday ordinal" } }
    }
    data class Yearly(val monthDay: MonthDay) : RecurrencePattern
}

sealed interface RecurrenceEnd {
    data object Never : RecurrenceEnd
    data class Count(val count: Int) : RecurrenceEnd {
        init { require(count > 0) { "Occurrence count must be positive" } }
    }
    data class ThroughDate(val date: LocalDate) : RecurrenceEnd {
        init { requireRfcDate(date) }
    }
    /** Imported timed UNTIL may cut off mid-day. Preserve it rather than widening to a full date. */
    data class ExactUntil(val instant: Instant) : RecurrenceEnd {
        init {
            requireRfcDate(instant.atOffset(ZoneOffset.UTC).toLocalDate())
            require(instant.nano == 0) { "UNTIL requires second precision" }
        }
    }
}

data class RecurrenceSpec(
    val pattern: RecurrencePattern,
    val interval: Int = 1,
    val end: RecurrenceEnd = RecurrenceEnd.Never,
    val weekStart: DayOfWeek = DayOfWeek.MONDAY,
) {
    init { require(interval > 0) { "Recurrence interval must be positive" } }
}

/** [raw] is always retained exactly: unrelated event edits must save it, not rebuild [spec]. */
sealed interface ParsedRecurrence {
    val raw: String
    data class Supported(override val raw: String, val spec: RecurrenceSpec) : ParsedRecurrence
    data class Unsupported(override val raw: String, val reasons: List<String>) : ParsedRecurrence
    data class Invalid(override val raw: String, val reason: String) : ParsedRecurrence
}

sealed interface RecurrenceOccurrence {
    data class AllDay(val date: LocalDate) : RecurrenceOccurrence
    data class Timed(val localDateTime: LocalDateTime, val instant: Instant) : RecurrenceOccurrence
}

/** Common rule authoring only. This does not split provider series or write recurrence exceptions. */
object RecurrenceRules {
    private val dateFormat = DateTimeFormatter.BASIC_ISO_DATE
    private val utcFormat = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)
    private val weekdays = DayOfWeek.entries.associateBy { it.name.take(2) }
    private val supportedParts = setOf("FREQ", "INTERVAL", "COUNT", "UNTIL", "BYDAY", "BYMONTHDAY", "BYMONTH", "WKST")

    fun build(spec: RecurrenceSpec, start: RecurrenceStart): String {
        require(matches(spec.pattern, start.date)) { "DTSTART must match the selected recurrence pattern" }
        val parts = when (val pattern = spec.pattern) {
            RecurrencePattern.Daily -> mutableListOf("FREQ=DAILY")
            is RecurrencePattern.Weekly -> mutableListOf("FREQ=WEEKLY", "BYDAY=" + pattern.days.sortedBy { it.value }.joinToString(",") { it.code() })
            is RecurrencePattern.MonthlyDate -> mutableListOf("FREQ=MONTHLY", "BYMONTHDAY=${pattern.day}")
            is RecurrencePattern.MonthlyWeekday -> mutableListOf("FREQ=MONTHLY", "BYDAY=${pattern.ordinal}${pattern.day.code()}")
            is RecurrencePattern.Yearly -> mutableListOf("FREQ=YEARLY", "BYMONTH=${pattern.monthDay.monthValue}", "BYMONTHDAY=${pattern.monthDay.dayOfMonth}")
        }
        parts += "INTERVAL=${spec.interval}"
        parts += "WKST=${spec.weekStart.code()}"
        when (val end = spec.end) {
            RecurrenceEnd.Never -> Unit
            is RecurrenceEnd.Count -> parts += "COUNT=${end.count}"
            is RecurrenceEnd.ThroughDate -> {
                require(end.date >= start.date) { "Recurrence end precedes its start" }
                parts += "UNTIL=" + until(end.date, start)
            }
            is RecurrenceEnd.ExactUntil -> {
                require(start is RecurrenceStart.Timed) { "All-day UNTIL must be a DATE" }
                require(end.instant >= start.instant) { "Recurrence end precedes its start" }
                parts += "UNTIL=" + utcFormat.format(end.instant)
            }
        }
        return parts.joinToString(";").also { strictRule(it) }
    }

    fun parse(raw: String, start: RecurrenceStart): ParsedRecurrence {
        val tokens = raw.split(';')
        if (tokens.any { it.count { c -> c == '=' } != 1 }) return ParsedRecurrence.Invalid(raw, "Malformed recurrence parts")
        val pairs = tokens.map { it.substringBefore('=') to it.substringAfter('=') }
        if (pairs.map { it.first }.distinct().size != pairs.size) return ParsedRecurrence.Invalid(raw, "Duplicate recurrence parts")
        val parts = pairs.toMap()
        try {
            strictRule(raw)
        } catch (_: Exception) {
            return ParsedRecurrence.Invalid(raw, "Invalid RFC 5545 recurrence rule")
        }
        val unknown = parts.keys - supportedParts
        if (unknown.isNotEmpty()) return ParsedRecurrence.Unsupported(raw, listOf("Unsupported parts: ${unknown.sorted().joinToString() }"))
        return try {
            val expected = mutableSetOf("FREQ", "INTERVAL", "COUNT", "UNTIL", "WKST")
            val pattern = when (parts["FREQ"]) {
                "DAILY" -> RecurrencePattern.Daily
                "WEEKLY" -> {
                    expected += "BYDAY"
                    val days = parts["BYDAY"]?.split(',')?.map { weekdays[it] ?: return unsupported(raw, "Unsupported weekly BYDAY") }?.toSet()
                        ?: setOf(start.date.dayOfWeek)
                    RecurrencePattern.Weekly(days)
                }
                "MONTHLY" -> {
                    if ("BYDAY" in parts && "BYMONTHDAY" in parts) return unsupported(raw, "Combined monthly constraints")
                    expected += setOf("BYDAY", "BYMONTHDAY")
                    if ("BYDAY" in parts) {
                        val match = Regex("(-?[1-5])(MO|TU|WE|TH|FR|SA|SU)").matchEntire(parts.getValue("BYDAY"))
                            ?: return unsupported(raw, "Unsupported monthly BYDAY")
                        RecurrencePattern.MonthlyWeekday(match.groupValues[1].toInt(), weekdays.getValue(match.groupValues[2]))
                    } else RecurrencePattern.MonthlyDate(parts["BYMONTHDAY"]?.toIntOrNull()
                        ?: if ("BYMONTHDAY" in parts) return unsupported(raw, "Multiple or negative monthly dates") else start.date.dayOfMonth)
                }
                "YEARLY" -> {
                    expected += setOf("BYMONTH", "BYMONTHDAY")
                    val month = parts["BYMONTH"]?.toIntOrNull()
                        ?: if ("BYMONTH" in parts) return unsupported(raw, "Multiple yearly months") else start.date.monthValue
                    val day = parts["BYMONTHDAY"]?.toIntOrNull()
                        ?: if ("BYMONTHDAY" in parts) return unsupported(raw, "Multiple or negative yearly dates") else start.date.dayOfMonth
                    RecurrencePattern.Yearly(MonthDay.of(month, day))
                }
                else -> return unsupported(raw, "Unsupported recurrence frequency")
            }
            if ((parts.keys - expected).isNotEmpty()) return unsupported(raw, "Additional recurrence constraints")
            if (!matches(pattern, start.date)) return unsupported(raw, "DTSTART does not match the rule pattern")
            val end = when {
                "COUNT" in parts && "UNTIL" in parts -> return ParsedRecurrence.Invalid(raw, "COUNT and UNTIL are mutually exclusive")
                "COUNT" in parts -> RecurrenceEnd.Count(parts.getValue("COUNT").toInt())
                "UNTIL" in parts -> {
                    val value = parts.getValue("UNTIL")
                    when (start) {
                        is RecurrenceStart.AllDay -> {
                            if (!Regex("[0-9]{8}").matches(value)) return unsupported(raw, "All-day UNTIL must be a DATE")
                            val date = LocalDate.parse(value, dateFormat)
                            if (date < start.date) return unsupported(raw, "Recurrence ends before DTSTART")
                            RecurrenceEnd.ThroughDate(date)
                        }
                        is RecurrenceStart.Timed -> {
                            if (!Regex("[0-9]{8}T[0-9]{6}Z").matches(value)) return unsupported(raw, "Timed UNTIL must be UTC")
                            val instant = Instant.from(utcFormat.parse(value))
                            if (instant < start.instant) return unsupported(raw, "Recurrence ends before DTSTART")
                            val localDate = instant.atZone(start.zone).toLocalDate()
                            if (until(localDate, start) == value) RecurrenceEnd.ThroughDate(localDate)
                            else RecurrenceEnd.ExactUntil(instant)
                        }
                    }
                }
                else -> RecurrenceEnd.Never
            }
            val weekStart = parts["WKST"]?.let { weekdays[it] ?: return ParsedRecurrence.Invalid(raw, "Invalid WKST") }
                ?: DayOfWeek.MONDAY
            ParsedRecurrence.Supported(raw, RecurrenceSpec(pattern, parts["INTERVAL"]?.toInt() ?: 1, end, weekStart))
        } catch (_: Exception) {
            // A library-valid construct that our typed editor cannot represent must stay raw.
            unsupported(raw, "Rule values cannot be represented by the common recurrence editor")
        }
    }

    /** Explicit finite preview only; unsupported imported constructs remain provider-displayable. */
    fun preview(raw: String, start: RecurrenceStart, limit: Int): List<RecurrenceOccurrence> {
        require(limit in 1..1000) { "Preview limit must be between 1 and 1000" }
        require(parse(raw, start) is ParsedRecurrence.Supported) { "Cannot preview unsupported or invalid recurrence" }
        val seed = when (start) {
            is RecurrenceStart.AllDay -> DateTime(start.date.year, start.date.monthValue - 1, start.date.dayOfMonth)
            is RecurrenceStart.Timed -> DateTime(TimeZone.getTimeZone(start.zone), start.instant.toEpochMilli())
        }
        val iterator = strictRule(raw).iterator(seed)
        return buildList {
            repeat(limit) {
                if (!iterator.hasNext()) return@buildList
                val next = iterator.nextDateTime()
                if (next.year !in 1..9999) return@buildList
                val date = LocalDate.of(next.year, next.month + 1, next.dayOfMonth)
                add(when (start) {
                    is RecurrenceStart.AllDay -> RecurrenceOccurrence.AllDay(date)
                    is RecurrenceStart.Timed -> RecurrenceOccurrence.Timed(
                        date.atTime(next.hours, next.minutes, next.seconds), Instant.ofEpochMilli(next.timestamp),
                    )
                })
            }
        }
    }

    private fun until(date: LocalDate, start: RecurrenceStart): String = when (start) {
        is RecurrenceStart.AllDay -> date.format(dateFormat)
        is RecurrenceStart.Timed -> utcFormat.format(date.plusDays(1).atStartOfDay(start.zone).toInstant().minusSeconds(1))
    }

    private fun strictRule(raw: String) = RecurrenceRule(raw, RecurrenceRule.RfcMode.RFC5545_STRICT)
    private fun unsupported(raw: String, reason: String) = ParsedRecurrence.Unsupported(raw, listOf(reason))

    private fun matches(pattern: RecurrencePattern, date: LocalDate): Boolean = when (pattern) {
        RecurrencePattern.Daily -> true
        is RecurrencePattern.Weekly -> date.dayOfWeek in pattern.days
        is RecurrencePattern.MonthlyDate -> date.dayOfMonth == pattern.day
        is RecurrencePattern.MonthlyWeekday -> date == date.with(TemporalAdjusters.dayOfWeekInMonth(pattern.ordinal, pattern.day))
        is RecurrencePattern.Yearly -> MonthDay.from(date) == pattern.monthDay
    }

    private fun DayOfWeek.code(): String = name.take(2)
}

private fun requireRfcDate(date: LocalDate) {
    require(date.year in 1..9999) { "RFC date year must have four digits" }
}
