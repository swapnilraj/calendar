package dev.swapnilraj.calendar.data.calendar

import android.content.ContentValues
import dev.swapnilraj.calendar.core.*
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Receipt protocol 2. The command/fingerprint remains MEETING_CREATE_COMMAND_V1. */
internal data class MeetingOneOffCreationReceipt(
    val command: JSONObject,
    val sourceRaw: String,
    val calendar: Map<String, String?>,
    val draft: EventDraft,
    val plan: MeetingOneOffFrozenPlan,
) {
    fun json(signature: String) = JSONObject().put("feature", Feature).put("version", 2)
        .put("kind", Kind).put("signature", signature).put("command", command)
        .put("identity", meetingIdentityJson(meetingIdentity(calendar))).put("source", JSONObject(sourceRaw))
        .put("plan", plan.json()).put("integrity", integrity(signature)).put("stage", "prepared")

    private fun integrity(signature: String) = meetingHash(Feature + signature + sourceRaw + plan.json().toString())

    companion object {
        const val Feature = "MEETING_ONEOFF_CREATE_RECEIPT_V2"
        const val Kind = "MEETING_V1_CREATE"

        fun parse(operation: CalendarLocalStore.Operation, maxBytes: Int): Pair<JSONObject, MeetingOneOffCreationReceipt> {
            require(operation.kind == Kind)
            val j = JSONObject(requireNotNull(operation.resultRef))
            require(j.getString("feature") == Feature && j.getInt("version") == 2 && j.getString("kind") == Kind)
            val command = j.getJSONObject("command")
            val token = EditToken(command.getString("token"))
            val draft = CalendarMeetingCreateWriter.oneOffDraft(command.getJSONObject("draft"))
            val guests = CalendarMeetingCreateWriter.oneOffGuests(command.getJSONArray("guests"))
            require(CalendarMeetingCreateWriter.oneOffCommand(token, draft, guests, maxBytes).toString() == command.toString())
            require(CalendarMeetingCreateWriter.fingerprint(token, draft, guests, maxBytes) == (Kind to operation.signature))
            require(j.getString("signature") == operation.signature)
            val raw = j.getJSONObject("source").toString()
            val calendar = CalendarMeetingCreateWriter.decodeIssuedSource(raw, token, maxBytes)
            val target = meetingIdentity(calendar)
            require(draft.targetIdentity == target && draft.calendarId == target.calendarId)
            require(meetingParseIdentity(j.getJSONObject("identity")) == target)
            val plan = MeetingOneOffFrozenPlan.parse(j.getJSONObject("plan"))
            // This is a fixed protocol mapping, not today's authoring validator/provider policy.
            plan.validate(draft, target, guests)
            val receipt = MeetingOneOffCreationReceipt(command, raw, calendar, draft, plan)
            require(receipt.integrity(operation.signature) == j.getString("integrity"))
            when (operation.phase) {
                "PREPARED", "FAILED" -> require(j.getString("stage") == "prepared" && !j.has("event") && !j.has("ackComplete") &&
                    !j.has("attendees") && !j.has("reminders") && !j.has("firstRef") && !j.has("_sync_id"))
                "COMMITTED", "VERIFIED" -> {
                    require(j.getString("stage") == "committed" && j.getLong("event") > 0)
                    if (j.getBoolean("ackComplete")) {
                        val attendees = j.getJSONArray("attendees").positiveIds()
                        val reminders = j.getJSONArray("reminders").positiveIds()
                        require(attendees.size == plan.attendees.size && reminders.size == plan.reminders.size)
                    } else require(!j.has("attendees") && !j.has("reminders") && !j.has("firstRef"))
                    if (j.has("firstRef")) require(meetingParseRef(j.getJSONObject("firstRef")) == plan.ref(draft.calendarId, j.getLong("event")))
                }
                else -> error("No acknowledged provider identity")
            }
            return j to receipt
        }

        private fun JSONArray.positiveIds() = List(length()) { getLong(it) }.also { ids ->
            require(ids.all { it > 0 } && ids.distinct().size == ids.size)
        }
    }
}

/** Native values freeze once. No timezone rules, recurrence compiler or live policy on decode. */
internal data class MeetingOneOffFrozenPlan(
    val event: Map<String, String?>,
    val attendees: List<Map<String, String?>>,
    val reminders: List<Map<String, String?>>,
    val start: Instant,
    val end: Instant,
) {
    val uid: String get() = requireNotNull(event["uid2445"])
    fun ref(calendar: CalendarId, id: Long) = EventRef(calendar, id, start, end)
    fun json() = JSONObject().put("version", 1).put("event", meetingRowJson(event))
        .put("attendees", JSONArray(attendees.map(::meetingRowJson))).put("reminders", JSONArray(reminders.map(::meetingRowJson)))
        .put("start", start.toString()).put("end", end.toString())

    fun validate(draft: EventDraft, identity: CalendarIdentity, inputs: List<MeetingGuestInput>) {
        require(draft.recurrence == null && identity.owner != null && identity.owner == identity.account.name)
        require(uid.endsWith("@daylight.invalid") && UUID.fromString(uid.removeSuffix("@daylight.invalid")).toString() + "@daylight.invalid" == uid)
        val expectedBounds = when (val time = draft.time) {
            is EventTime.Timed -> time.start to time.end
            is EventTime.AllDay -> time.dates.start.atStartOfDay(ZoneOffset.UTC).toInstant() to time.dates.endExclusive.atStartOfDay(ZoneOffset.UTC).toInstant()
        }
        require(expectedBounds == (start to end) && start < end)
        require(Instant.ofEpochMilli(start.toEpochMilli()) == start && Instant.ofEpochMilli(end.toEpochMilli()) == end)
        val zone = if (draft.time is EventTime.AllDay) "UTC" else requireNotNull(draft.eventZone).id
        val expected = linkedMapOf<String, String?>("dtstart" to start.toEpochMilli().toString(), "dtend" to end.toEpochMilli().toString(),
            "allDay" to if (draft.time is EventTime.AllDay) "1" else "0", "eventTimezone" to zone, "eventEndTimezone" to zone, "duration" to null,
            "calendar_id" to draft.calendarId.value.toString(), "title" to draft.title, "eventLocation" to draft.location, "description" to draft.description,
            "organizer" to identity.owner, "eventStatus" to "1", "hasAttendeeData" to "1", "guestsCanModify" to "0", "guestsCanInviteOthers" to "0", "guestsCanSeeGuests" to "1", "uid2445" to uid)
        draft.availability?.let { expected["availability"] = it.toString() }; draft.privacy?.let { expected["accessLevel"] = it.toString() }
        require(event == expected)
        val self = mapOf("attendeeEmail" to identity.owner, "attendeeRelationship" to "2", "attendeeType" to "0", "attendeeStatus" to "1")
        // Freeze normalization version 1 separately from mutable author validation. No current email
        // regex, allowed-type policy or name validator is invoked for an existing prepared receipt.
        val normalized = linkedMapOf<String, Map<String, String?>>()
        for (input in inputs) {
            val email = input.email.trim(); val name = input.name?.trim()?.takeIf(String::isNotEmpty)
            val row = linkedMapOf<String, String?>("attendeeEmail" to email, "attendeeRelationship" to "1", "attendeeType" to when (input.type) {
                MeetingGuestType.UNSPECIFIED -> "0"; MeetingGuestType.REQUIRED -> "1"; MeetingGuestType.OPTIONAL -> "2"
            }, "attendeeStatus" to "3").apply { name?.let { put("attendeeName", it) } }
            val key = email.lowercase(Locale.ROOT); val prior = normalized[key]
            require(prior == null || prior.filterKeys { it != "attendeeEmail" } == row.filterKeys { it != "attendeeEmail" })
            if (prior == null) normalized[key] = row
        }
        require(attendees == listOf(self) + normalized.values)
        require(reminders == draft.alertMinutes.map { mapOf("minutes" to it.toString(), "method" to "1") })
    }

    companion object {
        fun freeze(event: ContentValues, attendees: List<ContentValues>, draft: EventDraft, uid: String): MeetingOneOffFrozenPlan {
            val bounds = when (val time = draft.time) {
                is EventTime.Timed -> time.start to time.end
                is EventTime.AllDay -> time.dates.start.atStartOfDay(ZoneOffset.UTC).toInstant() to time.dates.endExclusive.atStartOfDay(ZoneOffset.UTC).toInstant()
            }
            return MeetingOneOffFrozenPlan(row(event) + ("uid2445" to uid), attendees.map(::row),
                draft.alertMinutes.map { mapOf("minutes" to it.toString(), "method" to "1") }, bounds.first, bounds.second)
        }
        fun parse(j: JSONObject): MeetingOneOffFrozenPlan {
            require(j.getInt("version") == 1)
            fun rows(key: String): List<Map<String, String?>> = j.getJSONArray(key).let { array ->
                require(array.length() <= if (key == "attendees") 201 else 4000); List(array.length()) { meetingRow(array.getJSONObject(it)) }
            }
            return MeetingOneOffFrozenPlan(meetingRow(j.getJSONObject("event")), rows("attendees"), rows("reminders"),
                Instant.parse(j.getString("start")), Instant.parse(j.getString("end"))).also { require(it.json().toString() == j.toString()) }
        }
        private fun row(values: ContentValues) = values.keySet().associateWith { values.get(it)?.toString() }
    }
}
