package dev.swapnilraj.calendar.ui.events

import dev.swapnilraj.calendar.core.*
import java.time.*
import org.json.JSONObject

enum class EditorDraftKind { CREATE, EDIT }
enum class DraftReadIssue { UNSUPPORTED_VERSION, UNREADABLE }

/** Validated navigation metadata only. Controller still reloads the durable record before acting. */
@ConsistentCopyVisibility
data class EditorDraftLaunch internal constructor(
    val draftId: String,
    val kind: EditorDraftKind,
    val ref: EventRef?,
    val needsRecovery: Boolean,
    val title: String,
    val rawDate: String,
    val calendarSelected: Boolean,
    private val normalizedSeed: EventDraft?,
    val notices: List<EditorNotice> = emptyList(),
) {
    /** A seed never replaces stored raw fields. No current-date fallback is implicit. */
    fun seed(fallbackDate: LocalDate, fallbackZone: ZoneId): EventDraft = normalizedSeed ?: EventDraft(
        ref?.calendarId ?: CalendarId(0),
        EventTime.Timed(fallbackDate.atTime(9, 0).atZone(fallbackZone).toInstant(),
            fallbackDate.atTime(9, 0).atZone(fallbackZone).toInstant().plusSeconds(3600)),
        fallbackZone, title,
    )
}

data class DraftBrowserItem(val recordId: String, val updatedAt: Instant,
    val launch: EditorDraftLaunch?, val issue: DraftReadIssue?, val seriesLaunch: SeriesEditorLaunch? = null)

/** Reads the feature's versioned opaque payload without normalizing or saving incomplete fields. */
object DraftBrowserCodec {
    fun read(record: DraftRecord): DraftBrowserItem {
        if (record.version == SeriesDraftCodec.VERSION) {
            val launch = SeriesDraftCodec.describe(record)
            return DraftBrowserItem(record.id, record.updatedAt, null, if (launch == null) DraftReadIssue.UNREADABLE else null, launch)
        }
        if (record.version != EventEditorController.VERSION)
            return DraftBrowserItem(record.id, record.updatedAt, null, DraftReadIssue.UNSUPPORTED_VERSION)
        return try {
            require(record.id.isNotBlank())
            val raw = JSONObject(record.payload)
            val target = raw.getString("target")
            val kind = when {
                target == "create" -> EditorDraftKind.CREATE
                target.startsWith("edit:") -> EditorDraftKind.EDIT
                else -> throw IllegalArgumentException()
            }
            val operationId = raw.getString("operationId").also { require(it.isNotBlank()) }
            val operationKind = raw.getString("operationKind")
            val submitted = raw.getBoolean("submitted")
            require(operationKind in if (kind == EditorDraftKind.CREATE) setOf("", "create") else setOf("", "update", "delete"))
            require(!submitted || operationKind.isNotBlank())
            val fields = EventEditorController.readRawFields(raw)
            val retained = raw.opt("submission")?.takeUnless { it === JSONObject.NULL }
            val frozen = try {
                (retained as? JSONObject)?.let(FrozenEditorSubmission::decode)?.also {
                    require(it.operationId == operationId && it.kind == operationKind)
                }
            } catch (_: Exception) { null }
            require(retained == null || frozen != null)
            if (submitted && frozen != null) require(matchesFrozen(fields, frozen))
            val baseline = raw.optString("sourceBaseline").takeIf { it.isNotEmpty() }?.let(::JSONObject)
            val baselineRef = baseline?.let(::readBaselineRef)
            val frozenRef = when (frozen) {
                is FrozenEditorSubmission.Update -> frozen.ref
                is FrozenEditorSubmission.Delete -> frozen.ref
                else -> null
            }
            val ref = frozenRef ?: baselineRef
            require(if (kind == EditorDraftKind.CREATE) ref == null && frozen !is FrozenEditorSubmission.Update && frozen !is FrozenEditorSubmission.Delete
                else ref != null && target == editorDraftTarget(ref) && frozen !is FrozenEditorSubmission.Create)
            if (baselineRef != null && frozenRef != null) require(baselineRef == frozenRef)
            if (ref != null) require(raw.getString("calendarId") == ref.calendarId.value.toString())
            val seed = (frozen as? FrozenEditorSubmission.Create)?.draft ?: baseline?.let(::readSeed)
            val launch = EditorDraftLaunch(record.id, kind, ref,
                submitted || retained != null && frozen == null,
                raw.optString("title"), raw.optString("startDate"),
                raw.getString("calendarId").toLongOrNull()?.let { it >= 0 } == true, seed, EditorNoticeCodec.read(raw))
            DraftBrowserItem(record.id, record.updatedAt, launch, null)
        } catch (_: Exception) { DraftBrowserItem(record.id, record.updatedAt, null, DraftReadIssue.UNREADABLE) }
    }

    private fun matchesFrozen(fields: EventEditorFields, command: FrozenEditorSubmission): Boolean = try {
        when (command) {
            is FrozenEditorSubmission.Create -> EventEditorController.normalizedDraft(fields) == command.draft
            is FrozenEditorSubmission.Update -> {
                val patch = command.patch
                val normalized = EventEditorController.normalizedDraft(fields,
                    preserveTime = if (patch.time == FieldChange.Keep) EventTime.Timed(command.ref.instanceStart, command.ref.instanceEnd) else null,
                    preserveAlerts = if (patch.alertMinutes == FieldChange.Keep) emptyList() else null)
                fun <T> matches(change: FieldChange<T>, actual: T): Boolean = change == FieldChange.Keep || (change as? FieldChange.Set)?.value == actual
                matches(patch.title, normalized.title) && matches(patch.location, normalized.location) &&
                    matches(patch.description, normalized.description) && matches(patch.time, normalized.time) &&
                    matches(patch.eventZone, normalized.eventZone) && matches(patch.alertMinutes, normalized.alertMinutes) &&
                    matches(patch.availability, normalized.availability) && matches(patch.privacy, normalized.privacy)
            }
            is FrozenEditorSubmission.Delete -> true // Unsaved raw fields are not part of a deletion request.
        }
    } catch (_: Exception) { false }

    private fun readBaselineRef(value: JSONObject): EventRef? {
        if (!value.has("refCalendar")) return null
        return try {
            val master = value.getString("refMaster")
            val original = value.getString("refOriginal")
            require(master.isBlank() == original.isBlank())
            EventRef(CalendarId(value.getLong("refCalendar")), value.getLong("refEvent"),
                Instant.parse(value.getString("refStart")), Instant.parse(value.getString("refEnd")),
                if (master.isBlank()) null else RecurrenceRef(master.toLong(), Instant.parse(original)))
        } catch (_: Exception) { null }
    }

    private fun readSeed(value: JSONObject): EventDraft? = try {
        val time = when (value.getString("kind")) {
            "timed" -> EventTime.Timed(Instant.parse(value.getString("start")), Instant.parse(value.getString("end")))
            "allDay" -> EventTime.AllDay(CalendarDateRange(LocalDate.parse(value.getString("start")), LocalDate.parse(value.getString("end"))))
            else -> throw IllegalArgumentException()
        }
        val alerts = value.getJSONArray("alerts")
        EventDraft(CalendarId(value.getLong("calendarId")), time,
            value.optString("zone").takeIf(String::isNotBlank)?.let(ZoneId::of), value.getString("title"),
            value.getString("location"), value.getString("description"), (0 until alerts.length()).map(alerts::getInt),
            if (value.has("availability") && !value.isNull("availability")) value.getInt("availability") else null,
            if (value.has("privacy") && !value.isNull("privacy")) value.getInt("privacy") else null)
    } catch (_: Exception) { null }
}

internal fun editorDraftTarget(ref: EventRef?): String = ref?.let {
    "edit:${it.calendarId.value}:${it.eventId}:${it.recurrence}"
} ?: "create"
