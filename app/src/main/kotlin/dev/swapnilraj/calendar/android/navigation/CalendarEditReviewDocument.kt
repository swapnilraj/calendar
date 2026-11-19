package dev.swapnilraj.calendar.android.navigation

import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.*
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import org.json.JSONObject

internal data class CalendarEditReviewDocument(
    val request: CalendarEditReviewRequest, val generation: String, val revision: String,
    val sourceToken: String? = null, val sourceBaseline: String? = null,
    val original: EventEditorFields? = null, val prepared: DraftRecord? = null,
) {
    fun record(now: Instant): DraftRecord = DraftRecord(request.reviewId, VERSION, JSONObject()
        .put("feature", FEATURE).put("schema", 1).put("phase", if (prepared == null) "review" else "prepared")
        .put("request", CalendarEditReviewCodec.encode(request)).put("generation", generation).put("revision", revision)
        .put("sourceToken", CalendarEditJson.nullable(sourceToken)).put("sourceBaseline", CalendarEditJson.nullable(sourceBaseline))
        .put("original", original?.let { EventEditorController.encodeRawFields(it) } ?: JSONObject.NULL)
        .put("prepared", prepared?.let { JSONObject().put("id", it.id).put("version", it.version).put("payload", it.payload).put("updated", it.updatedAt.toString()) } ?: JSONObject.NULL)
        .toString().also { require(it.length <= 4_500_000) }, now)

    companion object {
        const val VERSION = 8
        const val FEATURE = "calendar-edit-review"
        fun decode(record: DraftRecord): CalendarEditReviewDocument {
            require(record.version == VERSION)
            val value = CalendarEditJson.objectValue(record.payload)
            CalendarEditJson.keys(value, setOf("feature", "schema", "phase", "request", "generation", "revision", "sourceToken", "sourceBaseline", "original", "prepared"))
            require(value.get("feature") == FEATURE && value.get("schema") == 1)
            val request = requireNotNull(CalendarEditReviewCodec.decode(CalendarEditJson.text(value, "request")))
            val original = if (value.isNull("original")) null else value.getJSONObject("original").let { fields ->
                EventEditorController.readRawFields(fields).also { require(EventEditorController.encodeRawFields(it).toString() == fields.toString()) }
            }
            val prepared = if (value.isNull("prepared")) null else value.getJSONObject("prepared").let {
                CalendarEditJson.keys(it, setOf("id", "version", "payload", "updated"))
                val version = CalendarEditJson.number(it, "version"); require(version in 1L..Int.MAX_VALUE.toLong())
                DraftRecord(CalendarEditJson.text(it, "id"), version.toInt(), CalendarEditJson.text(it, "payload"), Instant.parse(CalendarEditJson.text(it, "updated")))
            }
            val doc = CalendarEditReviewDocument(request, CalendarEditJson.uuid(value, "generation"), CalendarEditJson.uuid(value, "revision"),
                CalendarEditJson.nullableText(value, "sourceToken"), CalendarEditJson.nullableText(value, "sourceBaseline"), original, prepared)
            require(record.id == request.reviewId && (doc.sourceToken == null) == (doc.sourceBaseline == null) && (doc.sourceToken == null) == (original == null))
            require(doc.sourceToken == null || doc.sourceToken.isNotBlank())
            require(value.get("phase") == if (prepared == null) "review" else "prepared")
            prepared?.let { candidate ->
                require(doc.sourceToken != null && original != null && candidate.id == request.nativeId)
                CalendarEditJson.objectValue(candidate.payload)
                val native = OrdinaryDraftDocument.decode(candidate)
                require(!native.legacy && !native.hasRequest && native.launch.kind == EditorDraftKind.EDIT && native.launch.ref == request.selected)
                require(native.sourceToken == doc.sourceToken && native.sourceBaseline == doc.sourceBaseline && native.sourceIdentity == request.binding.calendar)
            }
            return doc
        }
    }
}

/** Consumed evidence has no caller seed, source title/notes, or backend token. */
internal data class CalendarEditHandoffReceipt(
    val session: String, val generation: String, val nativeGeneration: String,
    val selected: EventRef, val binding: CalendarEntryBinding, val reviewedZone: ZoneId, val baselineHash: String, val tokenHash: String,
) {
    val nativeId get() = "incoming-edit:$session"
    val reviewId get() = "incoming-edit-review:$session"
    fun record(now: Instant) = DraftRecord(reviewId, CalendarEditReviewDocument.VERSION, JSONObject()
        .put("feature", CalendarEditReviewDocument.FEATURE).put("schema", 1).put("phase", "handed-off")
        .put("session", session).put("generation", generation).put("nativeGeneration", nativeGeneration)
        .put("selected", CalendarEditJson.ref(selected)).put("binding", CalendarEditJson.binding(binding))
        .put("reviewedZone", reviewedZone.id).put("baselineHash", baselineHash).put("tokenHash", tokenHash).toString(), now)
    fun matches(record: DraftRecord): Boolean = try {
        CalendarEditJson.objectValue(record.payload)
        val native = OrdinaryDraftDocument.decode(record)
        !native.legacy && record.id == nativeId && native.generation == nativeGeneration && native.launch.kind == EditorDraftKind.EDIT &&
            native.launch.ref == selected && native.sourceIdentity == binding.calendar && hash(native.sourceBaseline) == baselineHash && hash(native.sourceToken) == tokenHash
    } catch (_: Exception) { false }

    companion object {
        fun decode(record: DraftRecord): CalendarEditHandoffReceipt {
            require(record.version == CalendarEditReviewDocument.VERSION)
            val value = CalendarEditJson.objectValue(record.payload, 16_384)
            CalendarEditJson.keys(value, setOf("feature", "schema", "phase", "session", "generation", "nativeGeneration", "selected", "binding", "reviewedZone", "baselineHash", "tokenHash"))
            require(value.get("feature") == CalendarEditReviewDocument.FEATURE && value.get("schema") == 1 && value.get("phase") == "handed-off")
            val receipt = CalendarEditHandoffReceipt(CalendarEditJson.uuid(value, "session"), CalendarEditJson.uuid(value, "generation"), CalendarEditJson.uuid(value, "nativeGeneration"),
                CalendarEditJson.readRef(value.getJSONObject("selected")), CalendarEditJson.readBinding(value.getJSONObject("binding")),
                ZoneId.of(CalendarEditJson.text(value, "reviewedZone")), CalendarEditJson.text(value, "baselineHash"), CalendarEditJson.text(value, "tokenHash"))
            require(record.id == receipt.reviewId && receipt.selected.eventId == receipt.binding.eventId && receipt.selected.calendarId == receipt.binding.calendar.calendarId)
            require(Regex("[a-f0-9]{64}").matches(receipt.baselineHash) && Regex("[a-f0-9]{64}").matches(receipt.tokenHash))
            return receipt
        }
        fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }
}

fun calendarEditReviewPreview(record: DraftRecord): CalendarEditReviewPreview? = try {
    val doc = CalendarEditReviewDocument.decode(record)
    CalendarEditReviewPreview(doc.request.sessionId, doc.request.proposed.title.orEmpty(), doc.generation)
} catch (_: Exception) { null }

/** Only strict receipts may be hidden. Malformed/future data remains retained for review. */
fun isCalendarEditHandoffReceipt(record: DraftRecord): Boolean = try { CalendarEditHandoffReceipt.decode(record); true } catch (_: Exception) { false }
