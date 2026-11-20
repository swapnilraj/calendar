package dev.swapnilraj.calendar.android.navigation

import android.util.JsonReader
import android.util.JsonToken
import dev.swapnilraj.calendar.core.DraftRecord
import java.io.StringReader
import java.time.Instant
import java.util.UUID
import org.json.JSONObject

/** Raw completion inputs have no normalized time and cannot authorize provider commands. */
data class CalendarSeedReviewFields(
    val startDate: String, val endDate: String, val startTime: String, val endTime: String,
    val allDay: Boolean, val zone: String, val rule: String,
    val startOffset: String = "", val endOffset: String = "",
)

data class CalendarSeedReviewPreview(val sessionId: String, val title: String, val rawDate: String, val generation: String, val hasGuests: Boolean)

internal data class CalendarSeedCompletionDocument(
    val request: CalendarSeedReviewRequest, val generation: String, val revision: String,
    val fields: CalendarSeedReviewFields, val preparedFields: String? = null,
) {
    val id get() = "incoming-review:${request.sessionId}"
    fun record(now: Instant): DraftRecord = DraftRecord(id, VERSION, JSONObject().apply {
        put("feature", "calendar-seed-review"); put("schema", 1); put("phase", "completion"); put("generation", generation); put("revision", revision)
        put("request", CalendarSeedReviewCodec.encode(request))
        put("preparedFields", preparedFields ?: JSONObject.NULL)
        put("fields", JSONObject().apply {
            put("startDate", fields.startDate); put("endDate", fields.endDate); put("startTime", fields.startTime); put("endTime", fields.endTime)
            put("allDay", fields.allDay); put("zone", fields.zone); put("rule", fields.rule)
            put("startOffset", fields.startOffset); put("endOffset", fields.endOffset)
        })
    }.toString().also { require(it.length <= 200_000) }, now)

    companion object {
        const val VERSION = 7
        private val top = setOf("feature", "schema", "phase", "generation", "revision", "request", "fields", "preparedFields")
        private val fieldKeys = setOf("startDate", "endDate", "startTime", "endTime", "allDay", "zone", "rule", "startOffset", "endOffset")
        fun decode(record: DraftRecord): CalendarSeedCompletionDocument {
            require(record.version == VERSION && record.payload.length <= 200_000)
            strictSeedDocument(record.payload)
            val json = JSONObject(record.payload)
            require(json.keys().asSequence().toSet() == top && json.get("feature") == "calendar-seed-review" && json.get("schema") == 1 && json.get("phase") == "completion")
            fun text(value: JSONObject, key: String) = (value.get(key) as? String ?: error("Invalid raw input")).also { require(it.length <= 65_536) }
            fun uuid(key: String) = text(json, key).also { require(UUID.fromString(it).toString() == it) }
            val request = requireNotNull(CalendarSeedReviewCodec.decode(text(json, "request")))
            val fields = json.getJSONObject("fields"); require(fields.keys().asSequence().toSet() == fieldKeys)
            return CalendarSeedCompletionDocument(request, uuid("generation"), uuid("revision"), CalendarSeedReviewFields(
                text(fields, "startDate"), text(fields, "endDate"), text(fields, "startTime"), text(fields, "endTime"),
                fields.get("allDay") as? Boolean ?: error("Invalid all-day input"), text(fields, "zone"), text(fields, "rule"),
                text(fields, "startOffset"), text(fields, "endOffset")),
                if (json.isNull("preparedFields")) null else text(json, "preparedFields")).also { require(record.id == it.id) }
        }
    }
}

/** Permanent local idempotency evidence; it deliberately contains no seed, title or calendar data. */
internal data class CalendarSeedHandoffReceipt(val session: String, val generation: String, val nativeGeneration: String) {
    fun record(now: Instant) = DraftRecord("incoming-review:$session", CalendarSeedCompletionDocument.VERSION, JSONObject()
        .put("feature", "calendar-seed-review").put("schema", 1).put("phase", "handed-off")
        .put("session", session).put("generation", generation).put("nativeGeneration", nativeGeneration).toString(), now)
    companion object {
        fun decode(record: DraftRecord): CalendarSeedHandoffReceipt {
            require(record.version == CalendarSeedCompletionDocument.VERSION && record.payload.length <= 1024)
            strictSeedDocument(record.payload)
            val value = JSONObject(record.payload)
            require(value.keys().asSequence().toSet() == setOf("feature", "schema", "phase", "session", "generation", "nativeGeneration"))
            require(value.get("feature") == "calendar-seed-review" && value.get("schema") == 1 && value.get("phase") == "handed-off")
            fun uuid(key: String) = (value.get(key) as? String ?: error("Invalid handoff identity")).also { require(UUID.fromString(it).toString() == it) }
            return CalendarSeedHandoffReceipt(uuid("session"), uuid("generation"), uuid("nativeGeneration")).also { require(record.id == "incoming-review:${it.session}") }
        }
    }
}

fun isCalendarSeedHandoffReceipt(record: DraftRecord): Boolean = try { CalendarSeedHandoffReceipt.decode(record); true } catch (_: Exception) { false }

/** Generic native routes must gate incoming rows with this proof before exposing Save. */
fun isCalendarSeedNativeHandoffVerified(nativeRecord: DraftRecord, receiptRecord: DraftRecord?): Boolean = try {
    val native = dev.swapnilraj.calendar.ui.events.OrdinaryDraftDocument.decode(nativeRecord)
    require(!native.legacy && native.launch.kind == dev.swapnilraj.calendar.ui.events.EditorDraftKind.CREATE && native.launch.ref == null)
    val receipt = CalendarSeedHandoffReceipt.decode(requireNotNull(receiptRecord))
    nativeRecord.id == "incoming:${receipt.session}" && native.generation == receipt.nativeGeneration
} catch (_: Exception) { false }

private fun strictSeedDocument(raw: String) {
    JsonReader(StringReader(raw)).use { reader ->
        reader.isLenient = false
        fun inspect(depth: Int) {
            require(depth <= 4)
            when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> {
                    reader.beginObject(); val keys = mutableSetOf<String>()
                    while (reader.hasNext()) { require(keys.add(reader.nextName())); inspect(depth + 1) }
                    reader.endObject()
                }
                JsonToken.BEGIN_ARRAY -> error("Unexpected collection")
                else -> reader.skipValue()
            }
        }
        inspect(0); require(reader.peek() == JsonToken.END_DOCUMENT)
    }
}

/** Browser recognition is exact and read-only; unsupported records remain retained. */
fun calendarSeedReviewPreview(record: DraftRecord): CalendarSeedReviewPreview? = try {
    val document = CalendarSeedCompletionDocument.decode(record)
    CalendarSeedReviewPreview(document.request.sessionId, document.request.seed.title.orEmpty(), document.fields.startDate,
        document.generation, document.request.seed.guests.isNotEmpty())
} catch (_: Exception) { null }
