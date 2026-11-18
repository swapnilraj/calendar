package dev.swapnilraj.calendar.ui.events

import dev.swapnilraj.calendar.core.EventDraft
import java.time.ZoneId
import org.json.JSONObject

/** Renderer provenance only. Never fills a missing source/provider event zone or a frozen command. */
internal object OrdinaryEditDisplayProof {
    const val KEY = "originalDisplayZone"
    fun sameSource(stored: String, current: String, nullableSourceZone: Boolean): Boolean = try {
        val before = JSONObject(stored); val after = JSONObject(current)
        if (nullableSourceZone) { before.remove(KEY); after.remove(KEY) }
        before.toString() == after.toString()
    } catch (_: Exception) { false }

    /** Caller first verifies the full canonical source, namespace and exact occurrence identity. */
    fun originalFields(initial: EventDraft, baseline: String): EventEditorFields? = try {
        if (initial.eventZone != null) EventEditorController.fieldsFor(initial)
        else {
            val raw = JSONObject(baseline).get(KEY)
            require(raw is String && raw.isNotBlank())
            EventEditorController.fieldsFor(initial.copy(eventZone = ZoneId.of(raw)))
        }
    } catch (_: Exception) { null }
}
