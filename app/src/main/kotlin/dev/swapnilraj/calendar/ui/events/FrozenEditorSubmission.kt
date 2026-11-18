package dev.swapnilraj.calendar.ui.events

import dev.swapnilraj.calendar.core.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

/** Exact typed request, captured before dispatch. Never rebuild a retry from a fresh edit source. */
internal sealed interface FrozenEditorSubmission {
    val operationId: String
    val kind: String
    suspend fun execute(port: CalendarMutations): MutationResult
    fun encode(): JSONObject

    data class Create(override val operationId: String, val draft: EventDraft) : FrozenEditorSubmission {
        override val kind = "create"
        override suspend fun execute(port: CalendarMutations) = port.create(operationId, draft)
        override fun encode(): JSONObject = header(this).put("draft", JSONObject().put("calendar", draft.calendarId.value)
            .put("time", time(draft.time)).put("zone", draft.eventZone?.id ?: JSONObject.NULL)
            .put("title", draft.title).put("location", draft.location).put("description", draft.description)
            .put("alerts", JSONArray(draft.alertMinutes)).apply {
                draft.availability?.let { put("availability", it) }; draft.privacy?.let { put("privacy", it) }
            })
    }
    data class Update(override val operationId: String, val ref: EventRef, val token: EditToken, val patch: EventPatch) : FrozenEditorSubmission {
        override val kind = "update"
        override suspend fun execute(port: CalendarMutations) = port.update(operationId, ref, token, patch)
        override fun encode(): JSONObject = header(this).put("ref", reference(ref)).put("token", token.value).put("patch", JSONObject().apply {
            (patch.title as? FieldChange.Set)?.let { put("title", it.value) }
            (patch.location as? FieldChange.Set)?.let { put("location", it.value) }
            (patch.description as? FieldChange.Set)?.let { put("description", it.value) }
            (patch.time as? FieldChange.Set)?.let { put("time", time(it.value)) }
            (patch.eventZone as? FieldChange.Set)?.let { put("zone", it.value?.id ?: JSONObject.NULL) }
            (patch.alertMinutes as? FieldChange.Set)?.let { put("alerts", JSONArray(it.value)) }
            (patch.availability as? FieldChange.Set)?.let { put("availability", it.value ?: JSONObject.NULL) }
            (patch.privacy as? FieldChange.Set)?.let { put("privacy", it.value ?: JSONObject.NULL) }
        })
    }
    data class Delete(override val operationId: String, val ref: EventRef, val token: EditToken) : FrozenEditorSubmission {
        override val kind = "delete"
        override suspend fun execute(port: CalendarMutations) = port.delete(operationId, ref, token)
        override fun encode(): JSONObject = header(this).put("ref", reference(ref)).put("token", token.value)
    }

    companion object {
        private fun header(value: FrozenEditorSubmission) = JSONObject().put("version", 1)
            .put("operationId", value.operationId).put("kind", value.kind)
        fun decode(value: JSONObject): FrozenEditorSubmission {
            require(value.getInt("version") == 1)
            val id = value.getString("operationId").also { require(it.isNotBlank()) }
            return when (value.getString("kind")) {
                "create" -> value.getJSONObject("draft").let { draft -> Create(id, EventDraft(CalendarId(draft.getLong("calendar")),
                    parseTime(draft.getJSONObject("time")), parseZone(draft, "zone"), draft.getString("title"),
                    draft.getString("location"), draft.getString("description"), ints(draft.getJSONArray("alerts")), optionalInt(draft, "availability"), optionalInt(draft, "privacy"))) }
                "update" -> {
                    val patch = value.getJSONObject("patch")
                    fun text(key: String): FieldChange<String> = if (patch.has(key)) FieldChange.Set(patch.getString(key)) else FieldChange.Keep
                    Update(id, parseRef(value.getJSONObject("ref")), EditToken(value.getString("token")), EventPatch(
                        title = text("title"), location = text("location"), description = text("description"),
                        time = if (patch.has("time")) FieldChange.Set(parseTime(patch.getJSONObject("time"))) else FieldChange.Keep,
                        eventZone = if (patch.has("zone")) FieldChange.Set(parseZone(patch, "zone")) else FieldChange.Keep,
                        alertMinutes = if (patch.has("alerts")) FieldChange.Set(ints(patch.getJSONArray("alerts"))) else FieldChange.Keep,
                        availability = if (patch.has("availability")) FieldChange.Set(optionalInt(patch, "availability")) else FieldChange.Keep,
                        privacy = if (patch.has("privacy")) FieldChange.Set(optionalInt(patch, "privacy")) else FieldChange.Keep,
                    ))
                }
                "delete" -> Delete(id, parseRef(value.getJSONObject("ref")), EditToken(value.getString("token")))
                else -> throw IllegalArgumentException("Unsupported editor submission")
            }
        }
        private fun optionalInt(value: JSONObject, key: String): Int? = if (!value.has(key) || value.isNull(key)) null else value.getInt(key)
        private fun parseZone(value: JSONObject, key: String): ZoneId? {
            require(value.has(key))
            return if (value.isNull(key)) null else ZoneId.of(value.getString(key))
        }
        private fun ints(value: JSONArray): List<Int> = (0 until value.length()).map(value::getInt)
        private fun time(value: EventTime): JSONObject = when (value) {
            is EventTime.Timed -> JSONObject().put("kind", "timed").put("start", value.start.toString()).put("end", value.end.toString())
            is EventTime.AllDay -> JSONObject().put("kind", "allDay").put("start", value.dates.start.toString()).put("end", value.dates.endExclusive.toString())
        }
        private fun parseTime(value: JSONObject): EventTime = when (value.getString("kind")) {
            "timed" -> EventTime.Timed(Instant.parse(value.getString("start")), Instant.parse(value.getString("end")))
            "allDay" -> EventTime.AllDay(CalendarDateRange(LocalDate.parse(value.getString("start")), LocalDate.parse(value.getString("end"))))
            else -> throw IllegalArgumentException("Unsupported event interval")
        }
        private fun reference(ref: EventRef): JSONObject = JSONObject().put("calendar", ref.calendarId.value).put("event", ref.eventId)
            .put("start", ref.instanceStart.toString()).put("end", ref.instanceEnd.toString()).apply {
                ref.recurrence?.let { put("master", it.masterEventId); put("original", it.originalStart.toString()) }
            }
        private fun parseRef(value: JSONObject): EventRef {
            require(value.has("master") == value.has("original"))
            return EventRef(CalendarId(value.getLong("calendar")), value.getLong("event"),
                Instant.parse(value.getString("start")), Instant.parse(value.getString("end")),
                if (value.has("master")) RecurrenceRef(value.getLong("master"), Instant.parse(value.getString("original"))) else null)
        }
    }
}
