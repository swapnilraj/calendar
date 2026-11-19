package dev.swapnilraj.calendar.data.calendar

import android.content.ContentValues
import dev.swapnilraj.calendar.core.*
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import org.json.JSONArray
import org.json.JSONObject

/** New ordinary edits only. Command signatures and historical receipts 2/3 are unchanged. */
internal data class OrdinaryEditReceipt(
    val kind: String, val command: JSONObject, val sourceRaw: String,
    val changes: Map<String, String?>, val alerts: List<Int>?, val result: EventRef,
) {
    val source: JSONObject get() = JSONObject(sourceRaw)
    val original: EventRef get() = ref(command)
    val event: Map<String, String?> get() = row(source.getJSONObject("event"))
    val calendar: Map<String, String?> get() = row(source.getJSONObject("calendar"))
    val reminders: List<Map<String, String?>> get() = rows(source.getJSONArray("alerts"))
    val attendees: List<Map<String, String?>> get() = rows(source.getJSONArray("attendees"))
    fun values() = ContentValues().apply { changes.forEach { (key, value) -> if (value == null) putNull(key) else put(key, value) } }
    private fun plan() = JSONObject().put("changes", json(changes)).put("alerts", alerts?.let(::JSONArray) ?: JSONObject.NULL).put("result", refJson(result))
    fun prepared(signature: String, identity: JSONObject) = JSONObject().put("version", 4).put("feature", Feature)
        .put("stage", "prepared").put("kind", kind).put("signature", signature).put("command", command)
        .put("identity", identity).put("sourceRaw", sourceRaw).put("plan", plan())
        .put("integrity", hash(Feature + kind + signature + sourceRaw + plan().toString()))

    /** Fixed v4 wire mapping. No current policy, display zone or timezone rules participate. */
    fun validate() {
        require(kind in setOf("UPDATE", "DELETE") && original.recurrence == null && result.recurrence == null)
        require(listOf(original, result).all { it.instanceStart.nano % 1_000_000 == 0 && it.instanceEnd.nano % 1_000_000 == 0 })
        require(original.eventId > 0 && original.calendarId == result.calendarId && original.eventId == result.eventId)
        require(event["_id"] == original.eventId.toString() && event["calendar_id"] == original.calendarId.value.toString())
        require(event["dtstart"]?.toLong() == original.instanceStart.toEpochMilli() && event["dtend"]?.toLong() == original.instanceEnd.toEpochMilli())
        require(signature("EDIT", JSONObject(sourceRaw)) == command.getString("token"))
        if (kind == "DELETE") {
            require(command.keys().asSequence().toSet() == setOf("calendar", "event", "start", "end", "token"))
            require(changes.isEmpty() && alerts == null && result == original)
            return
        }
        require(command.keys().asSequence().all { it in setOf("calendar", "event", "start", "end", "token", "title", "location", "description", "availability", "privacy", "time", "zone", "alerts") })
        val expected = linkedMapOf<String, String?>()
        mapOf("title" to "title", "location" to "eventLocation", "description" to "description").forEach { (input, native) ->
            if (command.has(input)) expected[native] = command.getString(input)
        }
        mapOf("availability" to "availability", "privacy" to "accessLevel").forEach { (input, native) ->
            if (command.has(input)) expected[native] = if (command.isNull(input)) "0" else command.getString(input)
        }
        if (command.has("time") || command.has("zone")) {
            val timed = changes["allDay"] == "0"
            require(timed || changes["allDay"] == "1")
            val time: EventTime = if (timed) EventTime.Timed(result.instanceStart, result.instanceEnd)
                else EventTime.AllDay.fromProvider(result.instanceStart, result.instanceEnd)
            if (time is EventTime.AllDay) {
                require(result.instanceStart == time.dates.start.atStartOfDay(ZoneOffset.UTC).toInstant() &&
                    result.instanceEnd == time.dates.endExclusive.atStartOfDay(ZoneOffset.UTC).toInstant())
            }
            if (command.has("time")) require(command.getString("time") == time.toString())
            else require(result == original && changes["allDay"] == event["allDay"])
            val zone = if (!timed) "UTC" else if (command.has("zone")) {
                require(!command.isNull("zone")); command.getString("zone")
            } else event["eventTimezone"] ?: error("Timed authoring needs an issued zone")
            expected.putAll(mapOf("dtstart" to result.instanceStart.toEpochMilli().toString(), "dtend" to result.instanceEnd.toEpochMilli().toString(),
                "allDay" to if (timed) "0" else "1", "eventTimezone" to zone, "eventEndTimezone" to zone, "duration" to null))
        } else require(result == original)
        if (command.has("alerts")) {
            require(alerts != null && command.getString("alerts") == alerts.toString())
            require(alerts.size <= 4000 && alerts.all { it >= 0 } && alerts.distinct().size == alerts.size)
            expected["hasAlarm"] = if (alerts.isNotEmpty() || reminders.any { it["method"] != "1" }) "1" else "0"
        } else require(alerts == null)
        require(changes == expected)
    }

    companion object {
        const val Feature = "ORDINARY_EDIT_RECEIPT_V4"
        fun handles(operation: CalendarLocalStore.Operation): Boolean = try {
            operation.kind in setOf("UPDATE", "DELETE") && JSONObject(operation.resultRef ?: "{}").optInt("version") == 4
        } catch (_: Exception) { false }
        fun parse(operation: CalendarLocalStore.Operation): Pair<JSONObject, OrdinaryEditReceipt> {
            val j = JSONObject(requireNotNull(operation.resultRef))
            require(j.getInt("version") == 4 && j.getString("feature") == Feature && j.getString("kind") == operation.kind)
            require(j.getString("signature") == operation.signature)
            val command = j.getJSONObject("command")
            require(signature(operation.kind, command) == operation.signature)
            val p = j.getJSONObject("plan")
            require(p.keys().asSequence().toSet() == setOf("changes", "alerts", "result"))
            val alerts = if (p.isNull("alerts")) null else p.getJSONArray("alerts").let { a -> require(a.length() <= 4000); List(a.length()) { a.getInt(it) } }
            val receipt = OrdinaryEditReceipt(operation.kind, command, j.getString("sourceRaw"), row(p.getJSONObject("changes")), alerts, ref(p.getJSONObject("result")))
            receipt.validate()
            require(receipt.prepared(operation.signature, j.getJSONObject("identity")).getString("integrity") == j.getString("integrity"))
            when (operation.phase) {
                "PREPARED", "FAILED", "ATTEMPTED" -> require(j.getString("stage") == "prepared" && !j.has("ackComplete") && !j.has("reminderIds") && !j.has("ref"))
                "COMMITTED", "VERIFIED" -> {
                    require(j.getString("stage") == "committed" && ref(j.getJSONObject("ref")) == receipt.result)
                    if (j.getBoolean("ackComplete")) {
                        val ids = j.getJSONArray("reminderIds").let { a -> List(a.length()) { a.getLong(it) } }
                        require(ids.size == (receipt.alerts?.size ?: 0) && ids.all { it > 0 } && ids.distinct().size == ids.size)
                    } else require(!j.has("reminderIds"))
                }
                else -> error("Unknown ordinary receipt phase")
            }
            return j to receipt
        }
        fun row(j: JSONObject): Map<String, String?> = j.keys().asSequence().associateWith { key ->
            if (j.isNull(key)) null else j.get(key) as? String ?: error("Native value must be string or null")
        }
        fun rows(a: JSONArray): List<Map<String, String?>> { require(a.length() <= 4000); return List(a.length()) { row(a.getJSONObject(it)) } }
        fun json(row: Map<String, String?>) = JSONObject().apply { row.toSortedMap().forEach { (k,v) -> put(k,v ?: JSONObject.NULL) } }
        fun refJson(ref: EventRef) = JSONObject().put("calendar", ref.calendarId.value).put("event", ref.eventId).put("start", ref.instanceStart.toString()).put("end", ref.instanceEnd.toString())
        fun ref(j: JSONObject) = EventRef(CalendarId(j.getLong("calendar")), j.getLong("event"), Instant.parse(j.getString("start")), Instant.parse(j.getString("end")))
        private fun signature(kind: String, command: JSONObject) = hash(kind + command.toString())
        private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
