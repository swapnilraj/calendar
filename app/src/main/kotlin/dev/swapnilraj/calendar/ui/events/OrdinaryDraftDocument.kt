package dev.swapnilraj.calendar.ui.events

import dev.swapnilraj.calendar.core.*
import java.time.Instant
import java.util.UUID
import org.json.JSONObject

internal object OrdinaryDraftEnvelope {
    fun generation(value: JSONObject): String {
        require(value.get("feature") == "ordinary-editor" && value.get("schema") == 1)
        val required = setOf("feature", "schema", "generation", "revision", "calendarId", "title", "startDate", "endDate", "startTime", "endTime", "allDay", "zone", "location", "description", "alerts", "startOffset", "endOffset", "availability", "privacy", "defaultVisibilityCalendar", "recurrence", "operationId", "operationKind", "operationPayload", "submitted", "target", "sourceToken", "sourceBaseline")
        val keys = value.keys().asSequence().toSet()
        require(keys.containsAll(required) && keys.all { it in required + setOf("notices", "targetIdentity", "defaultVisibilityIdentity", "submission", "zoneAuthored") })
        val structured = setOf("schema", "recurrence", "submitted")
        require(required.filterNot { it in structured }.all { value.get(it) is String })
        require(value.get("submitted") is Boolean && value.get("recurrence") is JSONObject)
        require(value.getString("operationId").isNotBlank())
        zoneAuthored(value)
        uuid(value, "revision")
        return uuid(value, "generation")
    }
    private fun uuid(value: JSONObject, key: String): String = (value.get(key) as? String ?: error("Invalid draft identity"))
        .also { require(UUID.fromString(it).toString() == it) }
    fun zoneAuthored(value: JSONObject): Boolean = if(value.has("zoneAuthored")) value.get("zoneAuthored") as? Boolean
        ?: error("Invalid zone intent") else false
}

internal data class OrdinaryDraftDocument(val id: String, val version: Int, val generation: String?, val revision: String?,
    val fields: EventEditorFields, val notices: List<EditorNotice>, val operationId: String, val operationKind: String,
    val operationPayload: String, val submitted: Boolean, val target: String, val sourceToken: String, val sourceBaseline: String,
    val frozen: FrozenEditorSubmission?, val launch: EditorDraftLaunch, val zoneAuthored: Boolean = false) {
    val hasRequest: Boolean get() = frozen != null || submitted || operationKind.isNotBlank() || operationPayload.isNotBlank()
    val legacy: Boolean get() = version != EventEditorController.VERSION
    val sourceIdentity: CalendarIdentity? get() = try {
        CalendarIdentityJson.optional(JSONObject(sourceBaseline), "sourceCalendarIdentity")
    } catch (_: Exception) { null }
    val hasNullSourceZone: Boolean get() = launch.kind == EditorDraftKind.EDIT && runCatching {
        val source = JSONObject(sourceBaseline)
        source.get("zone") == "" && source.get("sourceZone") == ""
    }.getOrDefault(false)
    fun record(now: Instant, nextRevision: String = UUID.randomUUID().toString()): DraftRecord {
        require(!legacy && generation != null)
        require(!zoneAuthored || hasNullSourceZone)
        val value = EventEditorController.encodeRawFields(fields).apply {
            put("feature", "ordinary-editor"); put("schema", 1); put("generation", generation); put("revision", nextRevision)
            EditorNoticeCodec.write(this, notices)
            put("operationId", operationId); put("operationKind", operationKind); put("operationPayload", operationPayload)
            put("submitted", submitted); put("target", target); put("sourceToken", sourceToken); put("sourceBaseline", sourceBaseline)
            frozen?.let { put("submission", it.encode()) }
            if(zoneAuthored) put("zoneAuthored",true)
        }
        return DraftRecord(id, EventEditorController.VERSION, value.toString(), now)
    }
    companion object {
        fun decode(record: DraftRecord): OrdinaryDraftDocument {
            val launch = DraftBrowserCodec.read(record).launch ?: error("Unrecognized ordinary draft")
            val value = JSONObject(record.payload)
            require(record.version == EventEditorController.VERSION || !value.has("zoneAuthored"))
            if (record.version == EventEditorController.VERSION) OrdinaryDraftEnvelope.generation(value)
            val frozen = if (value.has("submission") && !value.isNull("submission")) FrozenEditorSubmission.decode(value.getJSONObject("submission")) else null
            val kind = value.getString("operationKind")
            val submitted = value.getBoolean("submitted")
            if (record.version == EventEditorController.VERSION) {
                require(submitted == (frozen != null))
                require(frozen != null || kind.isEmpty() && value.getString("operationPayload").isEmpty())
            }
            val result = OrdinaryDraftDocument(record.id, record.version, launch.generation,
                if (record.version == EventEditorController.VERSION) value.getString("revision") else null,
                EventEditorController.readRawFields(value), EditorNoticeCodec.read(value), value.getString("operationId"), kind,
                value.getString("operationPayload"), submitted, value.getString("target"), value.getString("sourceToken"),
                value.getString("sourceBaseline"), frozen, launch, OrdinaryDraftEnvelope.zoneAuthored(value))
            require(!result.zoneAuthored || result.hasNullSourceZone)
            return result
        }
    }
}
