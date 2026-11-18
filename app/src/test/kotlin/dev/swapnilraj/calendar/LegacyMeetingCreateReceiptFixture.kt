package dev.swapnilraj.calendar.data.calendar

import dev.swapnilraj.calendar.core.*
import org.json.JSONObject

/** Authentic pre-upgrade receipt 1: deliberately has no source/plan/UID or partial-ACK fields. */
internal fun CalendarLocalStore.prepareLegacyMeetingCreate(
    id: String, token: EditToken, draft: EventDraft, guests: List<MeetingGuestInput>,
) {
    val command = CalendarMeetingCreateWriter.oneOffCommand(token, draft, guests, 1024 * 1024)
    val (kind, signature) = CalendarMeetingCreateWriter.fingerprint(token, draft, guests, 1024 * 1024)
    prepare(id, signature, kind, JSONObject().put("feature", "MEETING_CREATE_COMMAND_V1").put("kind", kind)
        .put("signature", signature).put("version", 1).put("stage", "prepared").put("command", command)
        .put("identity", meetingIdentityJson(requireNotNull(draft.targetIdentity))).toString())
}
