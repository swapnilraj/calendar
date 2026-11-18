package dev.swapnilraj.calendar.ui.events

import org.json.JSONArray
import org.json.JSONObject

/** Persisted copy semantics; enum names are stable v1 draft payload identifiers. */
enum class EditorNotice {
    DUPLICATE_AS_ONE_OFF, GUESTS_NOT_COPIED, RECURRENCE_NOT_COPIED,
    DEFAULT_REMINDERS_UNRESOLVED, REMINDERS_NOT_COPIED,
    VISIBILITY_REVIEW_REQUIRED, AVAILABILITY_REVIEW, CALENDAR_VISIBILITY_REVIEW_REQUIRED, VISIBILITY_RECOVERY_ONLY, CALENDAR_RESELECTION_REQUIRED, DESTINATION_RECOVERY_ONLY,
}

internal object EditorNoticeCodec {
    fun read(value: JSONObject): List<EditorNotice> {
        if (!value.has("notices")) return emptyList()
        val notices = value.getJSONArray("notices")
        return (0 until notices.length()).map { EditorNotice.valueOf(notices.getString(it)) }.distinct()
    }
    fun write(value: JSONObject, notices: List<EditorNotice>) {
        if (notices.isNotEmpty()) value.put("notices", JSONArray(notices.distinct().map { it.name }))
    }
}
