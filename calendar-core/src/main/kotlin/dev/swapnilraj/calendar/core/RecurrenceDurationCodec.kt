package dev.swapnilraj.calendar.core

import java.time.DateTimeException
import java.time.ZoneOffset

/** Nominal weeks/days must not be normalized into elapsed seconds. [hasTime] retains zero time units. */
data class RfcDurationParts(
    val sign: Int = 1,
    val weeks: Long = 0,
    val days: Long = 0,
    val hours: Long = 0,
    val minutes: Long = 0,
    val seconds: Long = 0,
    val hasTime: Boolean = false,
) {
    init {
        require(sign == 1 || sign == -1)
        require(listOf(weeks, days, hours, minutes, seconds).all { it >= 0 })
        require(weeks == 0L || days == 0L && !hasTime)
        require(hasTime || hours == 0L && minutes == 0L && seconds == 0L)
    }
}

/** Retain [raw] for unrelated edits, including unsupported or invalid imported source values. */
sealed interface ParsedRecurrenceDuration {
    val raw: String
    data class Supported(override val raw: String, val parts: RfcDurationParts) : ParsedRecurrenceDuration
    data class Unsupported(override val raw: String, val reason: String) : ParsedRecurrenceDuration
    data class Invalid(override val raw: String, val reason: String) : ParsedRecurrenceDuration
}

sealed interface ResolvedRecurrenceDuration {
    data class Resolved(val time: EventTime) : ResolvedRecurrenceDuration
    data class NeedsReview(val reason: String) : ResolvedRecurrenceDuration
}

/** Pure RFC 5545 DURATION handling; no provider writes or implicit all-day storage conversions. */
object RecurrenceDurationCodec {
    private val grammar = Regex("^([+-]?)P(?:(\\d+)W|(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?)$")
    private val extension = Regex("^[+-]?P(?=.+)(?:\\d+(?:[.,]\\d+)?[YMDW])*(?:T(?:\\d+(?:[.,]\\d+)?[HMS])*)?$")

    fun parse(raw: String, maxChars: Int = 128): ParsedRecurrenceDuration {
        require(maxChars > 0)
        if (raw.length > maxChars) return ParsedRecurrenceDuration.Unsupported(raw, "Duration input budget exceeded")
        val match = grammar.matchEntire(raw)
            ?: return if (extension.matches(raw) && (raw.any { it == '.' || it == ',' || it == 'Y' } ||
                    raw.substringBefore('T').contains('M'))) {
                ParsedRecurrenceDuration.Unsupported(raw, "Years, months and fractional units are not RFC event durations")
            } else ParsedRecurrenceDuration.Invalid(raw, "Malformed RFC duration")
        val values = match.groupValues
        if ((2..6).all { values[it].isEmpty() } || raw.endsWith('T'))
            return ParsedRecurrenceDuration.Invalid(raw, "Duration requires a unit after P or T")
        // RFC 5545 dur-hour may contain dur-minute, which may then contain dur-second.
        if (values[4].isNotEmpty() && values[6].isNotEmpty() && values[5].isEmpty())
            return ParsedRecurrenceDuration.Invalid(raw, "Seconds after hours require a minutes component")
        return try {
            fun number(index: Int) = values[index].takeIf { it.isNotEmpty() }?.toLong() ?: 0L
            ParsedRecurrenceDuration.Supported(raw, RfcDurationParts(
                sign = if (values[1] == "-") -1 else 1,
                weeks = number(2), days = number(3), hours = number(4), minutes = number(5),
                seconds = number(6), hasTime = raw.contains('T'),
            ))
        } catch (_: NumberFormatException) {
            ParsedRecurrenceDuration.Invalid(raw, "Duration component exceeds supported integer range")
        }
    }

    /**
     * Only positive event durations resolve. Timed nominal units advance the local date first;
     * an ambiguous/nonexistent nominal endpoint needs review, then HMS advances elapsed time.
     * Elapsed-only durations preserve the chosen starting overlap offset. All-day dates have no zone.
     */
    fun resolve(value: ParsedRecurrenceDuration.Supported, start: RecurrenceStart): ResolvedRecurrenceDuration {
        val p = value.parts
        if (p.sign < 0 || listOf(p.weeks, p.days, p.hours, p.minutes, p.seconds).all { it == 0L })
            return ResolvedRecurrenceDuration.NeedsReview("Event duration must be positive")
        return try {
            val days = Math.addExact(Math.multiplyExact(p.weeks, 7L), p.days)
            val elapsed = Math.addExact(Math.addExact(Math.multiplyExact(p.hours, 3600L),
                Math.multiplyExact(p.minutes, 60L)), p.seconds)
            val time = when (start) {
                is RecurrenceStart.AllDay -> {
                    if (p.hasTime) return ResolvedRecurrenceDuration.NeedsReview("All-day duration requires date units only")
                    val end = start.date.plusDays(days)
                    require(end.year in 1..9999)
                    EventTime.AllDay(CalendarDateRange(start.date, end))
                }
                is RecurrenceStart.Timed -> {
                    val nominalEnd = start.localDateTime.plusDays(days)
                    require(nominalEnd.year in 1..9999)
                    val base = if (days == 0L) start.instant else {
                        val offsets = start.zone.rules.getValidOffsets(nominalEnd)
                        if (offsets.size != 1) return ResolvedRecurrenceDuration.NeedsReview("Nominal endpoint falls in a clock gap or overlap")
                        nominalEnd.toInstant(offsets.single())
                    }
                    val end = base.plusSeconds(elapsed)
                    require(end.atZone(start.zone).year in 1..9999)
                    require(end.atOffset(ZoneOffset.UTC).year in 1..9999)
                    EventTime.Timed(start.instant, end)
                }
            }
            ResolvedRecurrenceDuration.Resolved(time)
        } catch (_: ArithmeticException) {
            ResolvedRecurrenceDuration.NeedsReview("Duration arithmetic overflow")
        } catch (_: DateTimeException) {
            ResolvedRecurrenceDuration.NeedsReview("Duration endpoint outside supported dates")
        } catch (_: IllegalArgumentException) {
            ResolvedRecurrenceDuration.NeedsReview("Duration endpoint outside supported dates")
        }
    }

    /** Null means retain source raw DURATION; only explicitly changed durations are serialized. */
    fun encode(change: RecurrenceDurationChange): String? = when (change) {
        RecurrenceDurationChange.KeepRaw -> null
        is RecurrenceDurationChange.TimedElapsed -> "PT${change.duration.seconds}S"
        is RecurrenceDurationChange.AllDayDays -> "P${change.days}D"
    }
}
