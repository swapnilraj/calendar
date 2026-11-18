package dev.swapnilraj.calendar.ui.events

import dev.swapnilraj.calendar.core.*
import java.time.*
import org.json.JSONObject

/** Source context is immutable and comes from the master, never a selected expanded occurrence. */
internal data class SeriesMasterContext(val calendar: CalendarIdentity, val start: RecurrenceStart,
    val ruleRaw: String?, val durationRaw: String?) {
    val template: RecurrenceMasterTemplate? get() = (RecurrenceMasterTemplates.resolve(start, durationRaw) as? RecurrenceMasterTemplateResult.Ready)?.template
    val parsed: RecurrenceSpec? get() = ruleRaw?.let { (RecurrenceRules.parse(it, start) as? ParsedRecurrence.Supported)?.spec }
    companion object {
        fun from(source: EditableSeries): SeriesMasterContext {
            val identity = requireNotNull(source.selectedDetails.calendar?.identity)
            require(identity.calendarId == source.snapshot.identity.calendarId && identity.account.name.isNotBlank() && identity.account.type.isNotBlank())
            return SeriesMasterContext(identity, source.snapshot.start, source.snapshot.ruleRaw, source.snapshot.durationRaw)
        }
    }
}

data class SeriesTemporalFields(val startDate: String, val startTime: String = "", val zone: String = "", val offset: String = "",
    val durationMode: String = "KEEP", val hours: String = "", val minutes: String = "", val seconds: String = "", val days: String = "") {
    companion object {
        internal fun from(context: SeriesMasterContext): SeriesTemporalFields? {
            val template = context.template ?: return null
            return when (val start = context.start) {
                is RecurrenceStart.AllDay -> SeriesTemporalFields(start.date.toString(), days = (template.time as EventTime.AllDay).dates.let {
                    java.time.temporal.ChronoUnit.DAYS.between(it.start, it.endExclusive).toString() })
                is RecurrenceStart.Timed -> {
                    val seconds = (template.time as EventTime.Timed).duration.seconds
                    SeriesTemporalFields(start.date.toString(), start.localDateTime.toLocalTime().toString(), start.zone.id,
                        start.preferredOffset?.id.orEmpty(), hours = (seconds / 3600).toString(), minutes = (seconds % 3600 / 60).toString(), seconds = (seconds % 60).toString())
                }
            }
        }
    }
}

data class SeriesRuleFields(val values: RecurrenceEditorFields, val patternDirty: Boolean = false,
    val intervalDirty: Boolean = false, val weekStartDirty: Boolean = false, val endDirty: Boolean = false) {
    internal fun changes(original: RecurrenceSpec): RecurrenceRuleChanges {
        val pattern = if (patternDirty) values.copy(interval = "1", endMode = "NEVER", weekStart = "MONDAY").normalized()?.pattern
            ?: error("A series cannot be converted to a one-off here") else original.pattern
        val interval = if (intervalDirty) values.interval.toInt().also { require(it in 1..RecurrenceCreation.MAX_INTERVAL) } else original.interval
        val weekStart = if (weekStartDirty) DayOfWeek.valueOf(values.weekStart) else original.weekStart
        val end = if (!endDirty || values.endMode == "KEEP") original.end else when (values.endMode) {
            "NEVER" -> RecurrenceEnd.Never
            "COUNT" -> RecurrenceEnd.Count(values.count.toInt().also { require(it in 1..RecurrenceCreation.MAX_COUNT) })
            "DATE" -> RecurrenceEnd.ThroughDate(LocalDate.parse(values.throughDate))
            else -> error("Unsupported authored end")
        }
        fun <T> change(value: T, old: T): FieldChange<T> = if (value == old) FieldChange.Keep else FieldChange.Set(value)
        return RecurrenceRuleChanges(change(pattern, original.pattern), change(interval, original.interval), change(weekStart, original.weekStart), change(end, original.end))
    }
    companion object {
        internal fun from(context: SeriesMasterContext): SeriesRuleFields? = context.parsed?.let { original ->
            SeriesRuleFields(RecurrenceEditorFields.from(original.copy(end = RecurrenceEnd.Never)).copy(endMode = "KEEP",
                count = (original.end as? RecurrenceEnd.Count)?.count?.toString().orEmpty(),
                throughDate = (original.end as? RecurrenceEnd.ThroughDate)?.date?.toString().orEmpty()))
        }
    }
}

internal fun seriesTemporalIntent(context: SeriesMasterContext, fields: SeriesTemporalFields?, rights: SeriesScopeCapabilities): Pair<FieldChange<RecurrenceStart>, RecurrenceDurationChange> {
    if (fields == null) return FieldChange.Keep to RecurrenceDurationChange.KeepRaw
    val original = SeriesTemporalFields.from(context) ?: error("Master duration needs review")
    val startChanged = fields.startDate != original.startDate || fields.startTime != original.startTime || fields.zone != original.zone || fields.offset != original.offset
    val start: FieldChange<RecurrenceStart> = if (!startChanged) FieldChange.Keep else {
        require(EditableField.TIME in rights.fields)
        val date = LocalDate.parse(fields.startDate)
        val authored = when (val old = context.start) {
            is RecurrenceStart.AllDay -> RecurrenceStart.AllDay(date)
            is RecurrenceStart.Timed -> {
                val zone = ZoneId.of(fields.zone); require(zone == old.zone || EditableField.ZONE in rights.fields)
                val local = date.atTime(LocalTime.parse(fields.startTime))
                val offsets = zone.rules.getValidOffsets(local); require(offsets.isNotEmpty())
                val offset = if (offsets.size == 1) offsets.single() else offsets.singleOrNull { it.id == fields.offset } ?: error("Choose a repeated clock offset")
                RecurrenceStart.Timed(local, zone, offset)
            }
        }
        val equivalent = when {
            authored is RecurrenceStart.AllDay && context.start is RecurrenceStart.AllDay -> authored.date == context.start.date
            authored is RecurrenceStart.Timed && context.start is RecurrenceStart.Timed -> authored.localDateTime == context.start.localDateTime && authored.zone == context.start.zone && authored.instant == context.start.instant
            else -> false
        }
        if (equivalent) FieldChange.Keep else FieldChange.Set(authored)
    }
    val duration = when (fields.durationMode) {
        "KEEP" -> RecurrenceDurationChange.KeepRaw
        "ELAPSED" -> {
            require(context.start is RecurrenceStart.Timed && EditableField.TIME in rights.fields)
            val hours = fields.hours.toLong().also { require(it >= 0) }
            val minutes = fields.minutes.toLong().also { require(it in 0..59) }
            val seconds = fields.seconds.toLong().also { require(it in 0..59) }
            val total = Math.addExact(Math.addExact(Math.multiplyExact(hours, 3600L), Math.multiplyExact(minutes, 60L)), seconds)
            require(total in 1..RecurrenceMasterTemplates.MAX_PROVIDER_SECONDS)
            if ((context.template?.time as? EventTime.Timed)?.duration?.seconds == total) RecurrenceDurationChange.KeepRaw else RecurrenceDurationChange.TimedElapsed(Duration.ofSeconds(total))
        }
        "DAYS" -> {
            require(context.start is RecurrenceStart.AllDay && EditableField.TIME in rights.fields)
            val count = fields.days.toLong()
            val originalDays = (context.template?.time as? EventTime.AllDay)?.dates?.let { java.time.temporal.ChronoUnit.DAYS.between(it.start, it.endExclusive) }
            if (count == originalDays) RecurrenceDurationChange.KeepRaw else RecurrenceDurationChange.AllDayDays(count)
        }
        else -> error("Unsupported duration mode")
    }
    val anchor = (start as? FieldChange.Set)?.value ?: context.start
    val raw = when (duration) {
        RecurrenceDurationChange.KeepRaw -> context.durationRaw
        is RecurrenceDurationChange.TimedElapsed -> "PT${duration.duration.seconds}S"
        is RecurrenceDurationChange.AllDayDays -> "P${duration.days}D"
    }
    require(RecurrenceMasterTemplates.resolve(anchor, raw) is RecurrenceMasterTemplateResult.Ready)
    return start to duration
}

internal object SeriesTemporalJson {
    fun context(value: SeriesMasterContext) = JSONObject().put("calendar", CalendarIdentityJson.encode(value.calendar)).put("start", SeriesJson.start(value.start))
        .put("rule", value.ruleRaw ?: JSONObject.NULL).put("duration", value.durationRaw ?: JSONObject.NULL)
    fun context(value: JSONObject): SeriesMasterContext {
        exact(value, "calendar", "start", "rule", "duration")
        return SeriesMasterContext(requireNotNull(CalendarIdentityJson.optional(JSONObject().put("identity", value.getJSONObject("calendar")), "identity")),
            SeriesJson.start(value.getJSONObject("start")), nullable(value, "rule"), nullable(value, "duration"))
    }
    fun fields(value: SeriesTemporalFields) = JSONObject().apply {
        listOf("date" to value.startDate, "time" to value.startTime, "zone" to value.zone, "offset" to value.offset, "durationMode" to value.durationMode,
            "hours" to value.hours, "minutes" to value.minutes, "seconds" to value.seconds, "days" to value.days).forEach { (key, text) -> put(key, text) }
    }
    fun fields(value: JSONObject): SeriesTemporalFields {
        exact(value, "date", "time", "zone", "offset", "durationMode", "hours", "minutes", "seconds", "days")
        return SeriesTemporalFields(text(value, "date"), text(value, "time"), text(value, "zone"), text(value, "offset"), text(value, "durationMode"), text(value, "hours"), text(value, "minutes"), text(value, "seconds"), text(value, "days"))
    }
    fun rule(value: SeriesRuleFields) = JSONObject().put("values", RecurrenceEditorFieldsJson.encode(value.values)).put("patternDirty", value.patternDirty)
        .put("intervalDirty", value.intervalDirty).put("weekStartDirty", value.weekStartDirty).put("endDirty", value.endDirty)
    fun rule(value: JSONObject): SeriesRuleFields {
        exact(value, "values", "patternDirty", "intervalDirty", "weekStartDirty", "endDirty")
        fun flag(key: String) = value.get(key) as? Boolean ?: error("Invalid dirty flag")
        return SeriesRuleFields(RecurrenceEditorFieldsJson.decode(value.getJSONObject("values")), flag("patternDirty"), flag("intervalDirty"), flag("weekStartDirty"), flag("endDirty"))
    }
    fun exact(value: JSONObject, vararg expected: String) { require(value.keys().asSequence().toSet() == expected.toSet()) }
    fun text(value: JSONObject, key: String) = value.get(key) as? String ?: error("Invalid raw text")
    private fun nullable(value: JSONObject, key: String) = if (value.isNull(key)) null else text(value, key)
}
