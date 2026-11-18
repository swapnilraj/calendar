package dev.swapnilraj.calendar.ui.events.meetings

import dev.swapnilraj.calendar.core.*
import java.time.Instant
import java.util.UUID
import org.json.JSONObject

/** Navigation contains identity only; durable storage is the authority for recovery and choices. */
object MeetingResponseRouteCodec {
    fun encode(launch: MeetingResponseLaunch): String = JSONObject().put("draftId", launch.draftId)
        .put("generation", launch.generation).put("selected", ResponseJson.ref(launch.selected))
        .put("needsRecovery", launch.needsRecovery).toString().also { require(decode(it) != null) }

    fun decode(encoded: String): MeetingResponseLaunch? = try {
        require(encoded.length <= 8192)
        val value = JSONObject(encoded)
        require(value.keys().asSequence().toSet().let { it.containsAll(setOf("draftId", "generation", "selected")) &&
            it.all { key -> key in setOf("draftId", "generation", "selected", "needsRecovery") } })
        fun text(objectValue: JSONObject, key: String) = (objectValue.get(key) as? String) ?: error("Invalid route")
        fun number(objectValue: JSONObject, key: String): Long = when (val number = objectValue.get(key)) {
            is Int -> number.toLong(); is Long -> number; else -> error("Invalid route")
        }
        val id = text(value, "draftId").also { require(it.isNotBlank() && it.length <= 256) }
        val generation = text(value, "generation").also { require(UUID.fromString(it).toString() == it) }
        val selected = value.getJSONObject("selected")
        val keys = selected.keys().asSequence().toSet()
        require(keys == setOf("calendar", "event", "start", "end") || keys == setOf("calendar", "event", "start", "end", "master", "original"))
        val ref = EventRef(CalendarId(number(selected, "calendar")), number(selected, "event"),
            Instant.parse(text(selected, "start")), Instant.parse(text(selected, "end")),
            if ("master" in keys) RecurrenceRef(number(selected, "master"), Instant.parse(text(selected, "original"))) else null)
        val recovery = if (value.has("needsRecovery")) value.get("needsRecovery") as? Boolean ?: error("Invalid route") else false
        MeetingResponseLaunch(id, ref, recovery, generation)
    } catch (_: Exception) { null }
}

data class MeetingResponseDraftPreview(val launch: MeetingResponseLaunch, val title: String, val time: EventTime)

fun meetingResponseDraftPreview(record: DraftRecord): MeetingResponseDraftPreview? = try {
    val document = MeetingResponseDraftCodec.decode(record)
    MeetingResponseDraftPreview(document.launch(), document.title, document.time)
} catch (_: Exception) { null }
