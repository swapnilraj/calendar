package dev.swapnilraj.calendar.ui.events

import dev.swapnilraj.calendar.core.*
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.MonthDay
import org.json.JSONArray
import org.json.JSONObject

/** Incomplete entries remain raw until explicit review; no RRULE is accepted from the form. */
data class RecurrenceEditorFields(
    val frequency: String = "NONE", val interval: String = "1", val weekdays: List<String> = emptyList(),
    val monthlyMode: String = "DATE", val monthDay: String = "", val ordinal: String = "", val weekday: String = "",
    val yearMonth: String = "", val yearDay: String = "", val weekStart: String = "MONDAY",
    val endMode: String = "NEVER", val count: String = "", val throughDate: String = "",
) {
    /** Called only by an explicit pattern choice, never by restore or save. */
    fun chooseFrequency(value: String, startDate: String): RecurrenceEditorFields {
        val date = try { LocalDate.parse(startDate) } catch (_: Exception) { null }
        return copy(frequency = value,
            weekdays = if (value == "WEEKLY" && weekdays.isEmpty() && date != null) listOf(date.dayOfWeek.name) else weekdays,
            monthDay = if (value == "MONTHLY" && monthDay.isBlank() && date != null) date.dayOfMonth.toString() else monthDay,
            ordinal = if (value == "MONTHLY" && ordinal.isBlank() && date != null) ((date.dayOfMonth - 1) / 7 + 1).toString() else ordinal,
            weekday = if (value == "MONTHLY" && weekday.isBlank() && date != null) date.dayOfWeek.name else weekday,
            yearMonth = if (value == "YEARLY" && yearMonth.isBlank() && date != null) date.monthValue.toString() else yearMonth,
            yearDay = if (value == "YEARLY" && yearDay.isBlank() && date != null) date.dayOfMonth.toString() else yearDay)
    }

    fun normalized(): RecurrenceSpec? {
        if (frequency == "NONE") return null
        val pattern = when (frequency) {
            "DAILY" -> RecurrencePattern.Daily
            "WEEKLY" -> RecurrencePattern.Weekly(weekdays.map(DayOfWeek::valueOf).toSet().also { require(it.size == weekdays.size) })
            "MONTHLY" -> when (monthlyMode) {
                "DATE" -> RecurrencePattern.MonthlyDate(monthDay.toInt())
                "WEEKDAY" -> RecurrencePattern.MonthlyWeekday(ordinal.toInt(), DayOfWeek.valueOf(weekday))
                else -> error("Unsupported monthly mode")
            }
            "YEARLY" -> RecurrencePattern.Yearly(MonthDay.of(yearMonth.toInt(), yearDay.toInt()))
            else -> error("Unsupported frequency")
        }
        val end = when (endMode) {
            "NEVER" -> RecurrenceEnd.Never
            "COUNT" -> RecurrenceEnd.Count(count.toInt())
            "DATE" -> RecurrenceEnd.ThroughDate(LocalDate.parse(throughDate))
            else -> error("Unsupported recurrence end")
        }
        return RecurrenceSpec(pattern, interval.toInt(), end, DayOfWeek.valueOf(weekStart))
    }

    companion object {
        fun from(spec: RecurrenceSpec?): RecurrenceEditorFields {
            if (spec == null) return RecurrenceEditorFields()
            var fields = RecurrenceEditorFields(interval = spec.interval.toString(), weekStart = spec.weekStart.name)
            fields = when (val pattern = spec.pattern) {
                RecurrencePattern.Daily -> fields.copy(frequency = "DAILY")
                is RecurrencePattern.Weekly -> fields.copy(frequency = "WEEKLY", weekdays = pattern.days.sortedBy { it.value }.map { it.name })
                is RecurrencePattern.MonthlyDate -> fields.copy(frequency = "MONTHLY", monthDay = pattern.day.toString())
                is RecurrencePattern.MonthlyWeekday -> fields.copy(frequency = "MONTHLY", monthlyMode = "WEEKDAY", ordinal = pattern.ordinal.toString(), weekday = pattern.day.name)
                is RecurrencePattern.Yearly -> fields.copy(frequency = "YEARLY", yearMonth = pattern.monthDay.monthValue.toString(), yearDay = pattern.monthDay.dayOfMonth.toString())
            }
            return when (val end = spec.end) {
                RecurrenceEnd.Never -> fields
                is RecurrenceEnd.Count -> fields.copy(endMode = "COUNT", count = end.count.toString())
                is RecurrenceEnd.ThroughDate -> fields.copy(endMode = "DATE", throughDate = end.date.toString())
                is RecurrenceEnd.ExactUntil -> error("Imported recurrence is not an authored draft")
            }
        }
    }
}

internal object RecurrenceEditorFieldsJson {
    private val keys = setOf("schema", "frequency", "interval", "weekdays", "monthlyMode", "monthDay", "ordinal", "weekday", "yearMonth", "yearDay", "weekStart", "endMode", "count", "throughDate")
    fun encode(fields: RecurrenceEditorFields) = JSONObject().apply {
        put("schema", 1); put("frequency", fields.frequency); put("interval", fields.interval)
        put("weekdays", JSONArray(fields.weekdays)); put("monthlyMode", fields.monthlyMode); put("monthDay", fields.monthDay)
        put("ordinal", fields.ordinal); put("weekday", fields.weekday); put("yearMonth", fields.yearMonth); put("yearDay", fields.yearDay)
        put("weekStart", fields.weekStart); put("endMode", fields.endMode); put("count", fields.count); put("throughDate", fields.throughDate)
    }
    fun decode(value: JSONObject): RecurrenceEditorFields {
        require(value.keys().asSequence().toSet() == keys && value.get("schema") == 1)
        fun text(key: String) = value.get(key) as? String ?: error("Invalid raw recurrence")
        val days = value.getJSONArray("weekdays")
        return RecurrenceEditorFields(text("frequency"), text("interval"), (0 until days.length()).map {
            days.get(it) as? String ?: error("Invalid raw weekday")
        }, text("monthlyMode"), text("monthDay"), text("ordinal"), text("weekday"), text("yearMonth"), text("yearDay"), text("weekStart"), text("endMode"), text("count"), text("throughDate"))
    }
}
