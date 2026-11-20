package dev.swapnilraj.calendar.data.calendar

import dev.swapnilraj.calendar.core.*
import org.json.JSONArray
import org.json.JSONObject

/** Composite authority is private. ALL consumes its validated base, never silently a guest clone. */
internal data class MeetingSeriesAuthority(val base: MeetingSeriesSource, val baseRaw: String, val generated: MeetingGeneratedSource?) {
    companion object {
        fun decode(raw: String, token: EditToken, maxBytes: Int): MeetingSeriesAuthority {
            checkStoredJson(raw, maxBytes)
            val json = JSONObject(raw)
            return if (json.getString("feature") == MeetingGeneratedSource.Feature) {
                require(MeetingGeneratedSource.token(raw) == token.value)
                val generated = MeetingGeneratedSource.decode(raw, maxBytes)
                MeetingSeriesAuthority(generated.base, generated.baseRaw, generated)
            } else {
                require(json.getString("feature") == MeetingSeriesSource.Feature && MeetingSeriesSource.token(raw) == token.value)
                val base = MeetingSeriesSource.decode(raw, maxBytes)
                require(base.canonical(maxBytes) == raw)
                validateBase(base)
                MeetingSeriesAuthority(base, raw, null)
            }
        }

        /** Validate the private decoded graph before any saved row IDs can drive provider reads.
         * This mirrors capture invariants; it never upgrades ambiguous own evidence to authority. */
        fun validateBase(base: MeetingSeriesSource) {
            val recurrence = requireNotNull(base.selected.recurrence)
            val namespace = meetingIdentity(base.calendar)
            val owner = requireNotNull(namespace.owner)
            require(namespace.calendarId == base.selected.calendarId && owner.isNotBlank() && owner == namespace.account.name && namespace.account.type.isNotBlank())
            require((base.calendar["calendar_access_level"]?.toIntOrNull() ?: -1) >= 300)
            val calendar = base.selected.calendarId.value
            fun ids(rows: List<Map<String, String?>>): Set<Long> {
                val ids = rows.map { requireNotNull(it.meetingLong("_id")).also { id -> require(id > 0) } }
                require(ids.toSet().size == ids.size)
                require(rows.all { it.meetingLong("calendar_id") == calendar })
                return ids.toSet()
            }
            ids(base.membership)
            require(base.membership.all { it["deleted"] in setOf("0", "1") })
            val eventIds = ids(base.events)
            require(base.events.all { it["deleted"] == "0" })
            require(eventIds == base.membership.filter { it["deleted"] == "0" }.map { it.meetingLong("_id") }.toSet())
            base.events.forEach { event ->
                val member = base.membership.single { it["_id"] == event["_id"] }
                require(member.all { (key, value) -> event[key] == value })
            }
            val master = base.master
            require(master["original_id"].isNullOrBlank() && master["original_sync_id"].isNullOrBlank())
            require(!master["rrule"].isNullOrBlank() || !master["rdate"].isNullOrBlank())
            require(master["allDay"] in setOf("0", "1"))
            val sync = master["_sync_id"]?.takeIf(String::isNotBlank)
            val originals = mutableSetOf<Long>()
            for (event in base.events.filter { it.meetingLong("_id") != recurrence.masterEventId }) {
                val parent = event.meetingLong("original_id")
                val parentSync = event["original_sync_id"]?.takeIf(String::isNotBlank)
                require(event["original_id"].isNullOrBlank() || parent != null)
                require(parent == null || parent == recurrence.masterEventId)
                require(parentSync == null || parentSync == sync)
                require(parent != null || parentSync != null)
                require(event["originalAllDay"] == master["allDay"])
                require(listOf("rrule", "rdate", "exrule", "exdate").all { event[it].isNullOrBlank() })
                require(originals.add(requireNotNull(event.meetingLong("originalInstanceTime"))))
            }
            val selected = base.events.single { it.meetingLong("_id") == base.selected.eventId }
            require(selected["eventStatus"] != "2")
            if (base.selected.eventId == recurrence.masterEventId) {
                require(recurrence.originalStart == base.selected.instanceStart && recurrence.originalStart.toEpochMilli() !in originals)
            } else {
                require(selected.meetingLong("originalInstanceTime") == recurrence.originalStart.toEpochMilli())
                require(selected.meetingLong("dtstart") == base.selected.instanceStart.toEpochMilli())
                require(selected.meetingLong("dtend")?.let { it == base.selected.instanceEnd.toEpochMilli() } != false)
            }
            fun invited(row: Map<String, String?>) = row["isOrganizer"] == "0" && !row["organizer"].isNullOrBlank() &&
                !row["organizer"]!!.equals(owner, true) && row["eventStatus"] in setOf(null, "0", "1") &&
                row["customAppPackage"].isNullOrBlank() && row["customAppUri"].isNullOrBlank()
            require(invited(master))
            require(base.candidates.keys == base.events.filter(::invited).map { it.meetingLong("_id")!! }.toSet())
            base.candidates.forEach { (event, rows) -> rows.forEach { row ->
                require(row.keys == setOf("_id", "event_id", "attendeeEmail") && row.meetingLong("_id") != null && row.meetingLong("event_id") == event)
                require(row["attendeeEmail"]?.trim()?.equals(owner.trim(), true) == true)
            } }
            val exact = base.candidates.filterValues { it.size == 1 && it.single()["attendeeEmail"] == owner }
            require(base.ownRows.keys == exact.keys)
            require(base.ownRows.values.map { it["_id"] }.toSet().size == base.ownRows.size)
            base.ownRows.forEach { (event, row) ->
                require(row.keys == CalendarMeetingWriter.attendeeFields.toSet())
                require(row.meetingLong("event_id") == event && row["_id"] == exact.getValue(event).single()["_id"] && row["attendeeEmail"] == owner)
            }
            val byId = base.events.associateBy { it.meetingLong("_id")!! }
            val eligible = base.ownRows.filter { (event, row) -> row["attendeeRelationship"] == "1" && row["attendeeType"] in setOf("0", "1", "2") &&
                row["attendeeStatus"] in setOf("0", "1", "2", "3", "4") && row["attendeeStatus"] == byId.getValue(event)["selfAttendeeStatus"] &&
                row["attendeeIdentity"].isNullOrBlank() == row["attendeeIdNamespace"].isNullOrBlank() }.keys
            require(base.eligible == eligible)
        }
    }
}

internal data class MeetingGeneratedSource(
    val base: MeetingSeriesSource,
    val baseRaw: String,
    val attendees: List<Map<String, String?>>,
    val reminders: List<Map<String, String?>>,
) {
    private var encoded: String? = null
    fun canonical(maxBytes: Int): String {
        encoded?.let { return it }
        checkCanonical(listOf(base.calendar, base.membership, base.events, base.candidates, base.ownRows, attendees, reminders), maxBytes, 16384)
        return JSONObject().put("feature", Feature).put("version", 1).put("base", JSONObject(baseRaw))
            .put("attendees", JSONArray(attendees.map(::meetingRowJson))).put("reminders", JSONArray(reminders.map(::meetingRowJson)))
            .toString().also { checkStoredJson(it, maxBytes); encoded = it }
    }
    companion object {
        const val Feature = "MEETING_GENERATED_SOURCE_V1"
        fun token(raw: String) = "meeting-generated-edit-v1:" + meetingHash(Feature + raw)
        fun decode(raw: String, maxBytes: Int): MeetingGeneratedSource {
            checkStoredJson(raw, maxBytes)
            val json = JSONObject(raw)
            require(json.getString("feature") == Feature && json.getInt("version") == 1)
            val baseRaw = json.getJSONObject("base").toString()
            val base = MeetingSeriesSource.decode(baseRaw, maxBytes)
            require(base.canonical(maxBytes) == baseRaw)
            MeetingSeriesAuthority.validateBase(base)
            fun rows(key: String) = json.getJSONArray(key).let { array -> List(array.length()) { meetingRow(array.getJSONObject(it)) } }
            val result = MeetingGeneratedSource(base, baseRaw, rows("attendees"), rows("reminders"))
            require(result.canonical(maxBytes) == raw)
            return result
        }
    }
}
