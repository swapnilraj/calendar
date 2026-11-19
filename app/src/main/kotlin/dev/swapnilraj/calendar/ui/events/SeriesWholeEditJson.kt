package dev.swapnilraj.calendar.ui.events

import dev.swapnilraj.calendar.core.*
import java.time.*
import org.json.JSONObject

/** Exact authored intent, with absent keys meaning Keep. No complete replacement is synthesized. */
internal object SeriesWholeEditJson {
    fun encode(intent: RecurrenceEditIntent.WholeSeriesEdit, selected: EventRef, token: EditToken): JSONObject = JSONObject().apply {
        put("expected", SeriesJson.start(intent.expectedMasterStart)); put("fields", SeriesJson.patch(intent.fields, selected, token))
        (intent.newMasterStart as? FieldChange.Set)?.let { put("start", SeriesJson.start(it.value)) }
        when (val duration = intent.duration) {
            RecurrenceDurationChange.KeepRaw -> Unit
            is RecurrenceDurationChange.TimedElapsed -> put("duration", JSONObject().put("kind", "elapsed").put("seconds", duration.duration.seconds))
            is RecurrenceDurationChange.AllDayDays -> put("duration", JSONObject().put("kind", "days").put("days", duration.days))
        }
        put("rule", JSONObject().apply {
            (intent.rule.pattern as? FieldChange.Set)?.let { put("pattern", AuthoredRecurrenceJson.encode(RecurrenceSpec(it.value)).getJSONObject("pattern")) }
            (intent.rule.interval as? FieldChange.Set)?.let { put("interval", it.value) }
            (intent.rule.weekStart as? FieldChange.Set)?.let { put("weekStart", it.value.name) }
            (intent.rule.end as? FieldChange.Set)?.let { end -> put("end", when (val value = end.value) {
                is RecurrenceEnd.ExactUntil -> JSONObject().put("kind", "EXACT").put("instant", value.instant.toString())
                else -> AuthoredRecurrenceJson.encode(RecurrenceSpec(RecurrencePattern.Daily, end = value)).getJSONObject("end")
            }) }
        })
    }
    fun decode(value: JSONObject, selected: EventRef, token: EditToken): RecurrenceEditIntent.WholeSeriesEdit {
        val keys = value.keys().asSequence().toSet()
        require(keys.containsAll(setOf("expected", "fields", "rule")) && keys.all { it in setOf("expected", "fields", "rule", "start", "duration") })
        val start = SeriesJson.start(value.getJSONObject("expected"))
        val duration = if (!value.has("duration")) RecurrenceDurationChange.KeepRaw else value.getJSONObject("duration").let {
            when (SeriesTemporalJson.text(it, "kind")) {
                "elapsed" -> { SeriesTemporalJson.exact(it, "kind", "seconds"); RecurrenceDurationChange.TimedElapsed(Duration.ofSeconds(number(it, "seconds"))) }
                "days" -> { SeriesTemporalJson.exact(it, "kind", "days"); RecurrenceDurationChange.AllDayDays(number(it, "days")) }
                else -> error("Unsupported duration")
            }
        }
        val rule = value.getJSONObject("rule")
        require(rule.keys().asSequence().all { it in setOf("pattern", "interval", "weekStart", "end") })
        val pattern = if (rule.has("pattern")) {
            val wrapper = AuthoredRecurrenceJson.encode(RecurrenceSpec(RecurrencePattern.Daily)).put("pattern", rule.getJSONObject("pattern"))
            FieldChange.Set(AuthoredRecurrenceJson.decode(wrapper).pattern)
        } else FieldChange.Keep
        val end = if (rule.has("end")) {
            val encoded = rule.getJSONObject("end")
            FieldChange.Set(if (SeriesTemporalJson.text(encoded, "kind") == "EXACT") {
                SeriesTemporalJson.exact(encoded, "kind", "instant"); RecurrenceEnd.ExactUntil(Instant.parse(SeriesTemporalJson.text(encoded, "instant")))
            } else AuthoredRecurrenceJson.decode(AuthoredRecurrenceJson.encode(RecurrenceSpec(RecurrencePattern.Daily)).put("end", encoded)).end)
        } else FieldChange.Keep
        val interval = if (rule.has("interval")) FieldChange.Set(number(rule, "interval").let { raw -> raw.toInt().also { require(it.toLong() == raw && it in 1..RecurrenceCreation.MAX_INTERVAL) } }) else FieldChange.Keep
        return RecurrenceEditIntent.WholeSeriesEdit(start, SeriesJson.patch(value.getJSONObject("fields"), selected, token),
            if (value.has("start")) FieldChange.Set(SeriesJson.start(value.getJSONObject("start"))) else FieldChange.Keep, duration,
            RecurrenceRuleChanges(pattern, interval, if (rule.has("weekStart")) FieldChange.Set(DayOfWeek.valueOf(SeriesTemporalJson.text(rule, "weekStart"))) else FieldChange.Keep, end))
    }
    private fun number(value: JSONObject, key: String): Long = when (val raw = value.get(key)) { is Int -> raw.toLong(); is Long -> raw; else -> error("Invalid numeric intent") }
}

/** Dedicated schema3 payload; it cannot be decoded as an occurrence or whole-series command. */
internal object SeriesFollowingEditJson {
    fun encode(intent: RecurrenceEditIntent.FollowingSeriesEdit, master: RecurrenceStart,
        selected: EventRef, token: EditToken): JSONObject {
        require(intent.selectedExceptionPatch == EventPatch())
        require(seriesOriginalBoundary(selected, master) == intent.expectedOriginalBoundary)
        return JSONObject().put("boundary", when (val boundary = intent.expectedOriginalBoundary) {
            is RecurrenceKey.Date -> JSONObject().put("kind", "date").put("date", boundary.date.toString())
            is RecurrenceKey.Timed -> JSONObject().put("kind", "timed").put("instant", boundary.instant.toString())
        }).put("tail", SeriesWholeEditJson.encode(RecurrenceEditIntent.WholeSeriesEdit(master, intent.fields,
            intent.newTailStart, intent.duration, intent.rule), selected, token))
    }
    fun decode(value: JSONObject, selected: EventRef, token: EditToken): Pair<RecurrenceEditIntent.FollowingSeriesEdit, RecurrenceStart> {
        SeriesTemporalJson.exact(value, "boundary", "tail")
        val encoded = value.getJSONObject("boundary")
        val boundary = when (SeriesTemporalJson.text(encoded, "kind")) {
            "date" -> { SeriesTemporalJson.exact(encoded, "kind", "date"); RecurrenceKey.Date(LocalDate.parse(SeriesTemporalJson.text(encoded, "date"))) }
            "timed" -> { SeriesTemporalJson.exact(encoded, "kind", "instant"); RecurrenceKey.Timed(Instant.parse(SeriesTemporalJson.text(encoded, "instant"))) }
            else -> error("Unknown original boundary")
        }
        val tail = SeriesWholeEditJson.decode(value.getJSONObject("tail"), selected, token)
        require(seriesOriginalBoundary(selected, tail.expectedMasterStart) == boundary)
        return RecurrenceEditIntent.FollowingSeriesEdit(boundary, tail.fields, tail.newMasterStart,
            tail.duration, tail.rule) to tail.expectedMasterStart
    }
}
