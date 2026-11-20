package dev.swapnilraj.calendar.ui.events

import dev.swapnilraj.calendar.core.*
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.MonthDay
import org.json.JSONArray
import org.json.JSONObject

/** Typed authored CREATE payload. Imported rules/ExactUntil never pass this codec. */
internal object AuthoredRecurrenceJson {
    fun encode(spec: RecurrenceSpec): JSONObject = JSONObject().apply {
        put("schema", 1); put("interval", spec.interval); put("weekStart", spec.weekStart.name)
        put("pattern", JSONObject().apply { when (val pattern = spec.pattern) {
            RecurrencePattern.Daily -> put("kind", "DAILY")
            is RecurrencePattern.Weekly -> { put("kind", "WEEKLY"); put("days", JSONArray(pattern.days.sortedBy { it.value }.map { it.name })) }
            is RecurrencePattern.MonthlyDate -> { put("kind", "MONTHLY_DATE"); put("day", pattern.day) }
            is RecurrencePattern.MonthlyWeekday -> { put("kind", "MONTHLY_WEEKDAY"); put("ordinal", pattern.ordinal); put("day", pattern.day.name) }
            is RecurrencePattern.Yearly -> { put("kind", "YEARLY"); put("month", pattern.monthDay.monthValue); put("day", pattern.monthDay.dayOfMonth) }
        } })
        put("end", JSONObject().apply { when (val end = spec.end) {
            RecurrenceEnd.Never -> put("kind", "NEVER")
            is RecurrenceEnd.Count -> { put("kind", "COUNT"); put("count", end.count) }
            is RecurrenceEnd.ThroughDate -> { put("kind", "DATE"); put("date", end.date.toString()) }
            is RecurrenceEnd.ExactUntil -> error("Imported end is not an authored recurrence")
        } })
    }
    fun decode(value: JSONObject): RecurrenceSpec {
        value.exact("schema", "interval", "weekStart", "pattern", "end")
        require(value.integer("schema") == 1)
        val pattern = value.getJSONObject("pattern")
        val parsedPattern = when (pattern.text("kind")) {
            "DAILY" -> { pattern.exact("kind"); RecurrencePattern.Daily }
            "WEEKLY" -> {
                pattern.exact("kind", "days"); val days = pattern.getJSONArray("days")
                val mapped = (0 until days.length()).map { DayOfWeek.valueOf(days.get(it) as? String ?: error("Invalid weekday")) }
                require(mapped.distinct().size == mapped.size)
                RecurrencePattern.Weekly(mapped.toSet())
            }
            "MONTHLY_DATE" -> { pattern.exact("kind", "day"); RecurrencePattern.MonthlyDate(pattern.integer("day")) }
            "MONTHLY_WEEKDAY" -> { pattern.exact("kind", "ordinal", "day"); RecurrencePattern.MonthlyWeekday(pattern.integer("ordinal"), DayOfWeek.valueOf(pattern.text("day"))) }
            "YEARLY" -> { pattern.exact("kind", "month", "day"); RecurrencePattern.Yearly(MonthDay.of(pattern.integer("month"), pattern.integer("day"))) }
            else -> error("Unknown authored pattern")
        }
        val end = value.getJSONObject("end")
        val parsedEnd = when (end.text("kind")) {
            "NEVER" -> { end.exact("kind"); RecurrenceEnd.Never }
            "COUNT" -> { end.exact("kind", "count"); RecurrenceEnd.Count(end.integer("count")) }
            "DATE" -> { end.exact("kind", "date"); RecurrenceEnd.ThroughDate(LocalDate.parse(end.text("date"))) }
            else -> error("Unknown authored end")
        }
        val interval = value.integer("interval").also { require(it in 1..RecurrenceCreation.MAX_INTERVAL) }
        if (parsedEnd is RecurrenceEnd.Count) require(parsedEnd.count in 1..RecurrenceCreation.MAX_COUNT)
        return RecurrenceSpec(parsedPattern, interval, parsedEnd, DayOfWeek.valueOf(value.text("weekStart")))
    }
    private fun JSONObject.exact(vararg expected: String) { require(keys().asSequence().toSet() == expected.toSet()) }
    private fun JSONObject.text(key: String) = get(key) as? String ?: error("Invalid recurrence text")
    private fun JSONObject.integer(key: String): Int = when (val value = get(key)) {
        is Int -> value
        is Long -> value.toInt().also { require(it.toLong() == value) }
        else -> error("Invalid recurrence integer")
    }
}
