package dev.swapnilraj.calendar.data.calendar

import android.content.ContentValues
import dev.swapnilraj.calendar.core.*
import java.security.MessageDigest
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Protocol6 is private journal material. Neither UID nor native values are caller authority. */
internal data class OrdinaryCreateReceipt(
    val operationId: String,
    val commandRaw: String,
    val calendar: Map<String, String?>,
    val plan: OrdinaryCreatePlan,
) {
    val command: JSONObject get() = JSONObject(commandRaw)
    val identity: CalendarIdentity get() = CreateSixJson.identity(command.getJSONObject("targetIdentity"))
    val signature: String get() = CreateSixJson.hash("CREATE" + commandRaw)
    private fun layout() = CalendarCreateAcknowledgments.layout(plan.reminders.size)
    private fun digest(): String = CreateSixJson.hash("ORDINARY_CREATE_RECEIPT_V6\n1\n" + operationId + "\n" + signature + "\n" + commandRaw + "\n" +
        CreateSixJson.canonical(CreateSixJson.identity(identity)) + "\n" + CreateSixJson.canonical(CreateSixJson.row(calendar)) + "\n" +
        CreateSixJson.canonical(plan.json()) + "\n" + CreateSixJson.canonical(layout()))
    fun prepared() = JSONObject().put("version", 6).put("feature", Feature).put("kind", "CREATE").put("stage", "prepared")
        .put("signature", signature).put("command", command).put("identity", CreateSixJson.identity(identity))
        .put("calendarSnapshot", CreateSixJson.row(calendar)).put("plan", plan.json()).put("layout", layout())
        .put("planDigestVersion", 1).put("planDigest", digest())
    fun validate() {
        val c = command
        val required = setOf("calendar", "event", "start", "end", "allDay", "zone", "title", "location", "description", "alerts", "targetIdentity")
        require(c.keys().asSequence().toSet().containsAll(required) && c.keys().asSequence().all { it in required + setOf("availability", "privacy", "recurrence") })
        require(CreateSixJson.number(c, "event") == 0L && CreateSixJson.number(c, "calendar") == identity.calendarId.value)
        require(identity.calendarId.value >= 0 && identity.account.name.isNotBlank() && identity.account.type.isNotBlank())
        require(calendar.keys == CalendarFields && CreateSixJson.calendarIdentity(calendar) == identity)
        require(operationId.isNotBlank() && operationId.length <= 64 * 1024 && c.toString() == commandRaw)
        plan.validate(c)
    }
    companion object {
        const val Feature = "ORDINARY_CREATE_RECEIPT_V6"
        val CalendarFields = setOf("_id", "account_name", "account_type", "ownerAccount", "calendar_access_level", "allowedReminders", "maxReminders", "canModifyTimeZone", "calendar_timezone", "allowedAvailability", "_sync_id")
        fun handles(operation: CalendarLocalStore.Operation): Boolean = try {
            operation.kind == "CREATE" && JSONObject(operation.resultRef ?: "{}").optInt("version") == 6
        } catch (_: Exception) { false }
        fun parse(operation: CalendarLocalStore.Operation, maxBytes: Int): Pair<JSONObject, OrdinaryCreateReceipt> {
            val raw = requireNotNull(operation.resultRef); checkStoredJson(raw, maxBytes)
            val j = JSONObject(raw)
            val base = setOf("version", "feature", "kind", "stage", "signature", "command", "identity", "calendarSnapshot", "plan", "layout", "planDigestVersion", "planDigest")
            val prepared = operation.phase in setOf("PREPARED", "FAILED", "ATTEMPTED")
            val committed = operation.phase in setOf("COMMITTED", "VERIFIED")
            require(prepared || committed)
            val required = if (prepared) base else base + setOf("master", "ackComplete") + if (operation.phase == "VERIFIED") setOf("reminderIds", "firstRef") else emptySet()
            val allowed = required + if (committed) setOf("ackFacts") else emptySet()
            val keys = j.keys().asSequence().toSet(); require(keys.containsAll(required) && keys.all { it in allowed })
            require(CreateSixJson.number(j, "version") == 6L && CreateSixJson.text(j, "feature") == Feature && CreateSixJson.text(j, "kind") == "CREATE" && operation.kind == "CREATE")
            require(CreateSixJson.text(j, "stage") == if (prepared) "prepared" else "committed")
            require(CreateSixJson.number(j, "planDigestVersion") == 1L)
            val command = j.getJSONObject("command").toString()
            val receipt = OrdinaryCreateReceipt(operation.id, command, CreateSixJson.row(j.getJSONObject("calendarSnapshot")), OrdinaryCreatePlan.parse(j.getJSONObject("plan")))
            receipt.validate()
            require(receipt.signature == operation.signature && CreateSixJson.text(j, "signature") == operation.signature)
            require(CreateSixJson.identity(j.getJSONObject("identity")) == receipt.identity)
            require(j.getJSONObject("layout").toString() == receipt.layout().toString())
            require(CreateSixJson.text(j, "planDigest") == receipt.digest())
            if (committed) {
                val master = CreateSixJson.number(j, "master"); require(master > 0)
                if (j.has("ackFacts")) CreateSixJson.facts(j.getJSONObject("ackFacts"))
                val complete = CreateSixJson.bool(j, "ackComplete")
                require(complete == (operation.phase == "VERIFIED"))
                if (complete) {
                    val ids = requireNotNull(CalendarCreateAcknowledgments.reminderIds(j.optJSONObject("ackFacts"), receipt.plan.reminders.size, master))
                    require(CalendarCreateAcknowledgments.positiveIds(j.getJSONArray("reminderIds")) == ids)
                    require(CreateSixJson.ref(j.getJSONObject("firstRef")) == receipt.plan.ref(receipt.identity.calendarId, master))
                }
            }
            return j to receipt
        }
    }
}

internal data class OrdinaryCreatePlan(val mode: String, val uid: String, val event: Map<String, String?>,
    val reminders: List<Map<String, String?>>, val firstStart: Instant, val firstEnd: Instant, val allDay: Boolean,
    val recurrenceFacts: OrdinaryCreateRecurrenceFacts?) {
    fun json() = JSONObject().put("version", 1).put("mode", mode).put("uid", uid).put("event", CreateSixJson.row(event))
        .put("reminders", JSONArray(reminders.map(CreateSixJson::row))).put("firstStart", firstStart.toString()).put("firstEnd", firstEnd.toString())
        .put("allDay", allDay).put("recurrenceFacts", recurrenceFacts?.json() ?: JSONObject.NULL)
    fun ref(calendar: CalendarId, id: Long) = EventRef(calendar, id, firstStart, firstEnd, if (mode == "RECURRING") RecurrenceRef(id, firstStart) else null)
    fun validate(command: JSONObject) {
        val id = uid.removeSuffix("@daylight.invalid")
        require(uid == UUID.fromString(id).toString() + "@daylight.invalid")
        require(mode in setOf("ONE_OFF", "RECURRING") && (mode == "RECURRING") == command.has("recurrence"))
        require(firstStart == CreateSixJson.instant(command, "start") && firstEnd == CreateSixJson.instant(command, "end") && firstEnd >= firstStart)
        require(firstStart == Instant.ofEpochMilli(firstStart.toEpochMilli()) && firstEnd == Instant.ofEpochMilli(firstEnd.toEpochMilli()))
        require(allDay == CreateSixJson.bool(command, "allDay"))
        if (allDay) require(firstStart.atOffset(ZoneOffset.UTC).toLocalTime() == LocalTime.MIDNIGHT && firstEnd.atOffset(ZoneOffset.UTC).toLocalTime() == LocalTime.MIDNIGHT && firstEnd > firstStart)
        val commandZone = CreateSixJson.nullableText(command, "zone")
        val zone = if (allDay) "UTC" else commandZone ?: error("Missing frozen zone")
        val expected = linkedMapOf<String, String?>("calendar_id" to CreateSixJson.number(command, "calendar").toString(),
            "dtstart" to firstStart.toEpochMilli().toString(), "dtend" to if (mode == "ONE_OFF") firstEnd.toEpochMilli().toString() else null,
            "allDay" to if (allDay) "1" else "0", "eventTimezone" to zone, "eventEndTimezone" to zone,
            "duration" to if (mode == "ONE_OFF") null else event["duration"], "title" to CreateSixJson.text(command, "title"),
            "eventLocation" to CreateSixJson.text(command, "location"), "description" to CreateSixJson.text(command, "description"), "uid2445" to uid)
        val alerts = CreateSixJson.alerts(command.getJSONArray("alerts"))
        expected["hasAlarm"] = if (alerts.isEmpty()) "0" else "1"
        for ((input, native) in listOf("availability" to "availability", "privacy" to "accessLevel")) if (command.has(input)) expected[native] = CreateSixJson.number(command, input).toString()
        require(reminders == alerts.map { mapOf("minutes" to it.toString(), "method" to "1") })
        if (mode == "RECURRING") {
            expected["rrule"] = event["rrule"]
            requireNotNull(recurrenceFacts).validate(command.getJSONObject("recurrence"), firstStart, firstEnd, allDay, requireNotNull(event["rrule"]), requireNotNull(event["duration"]))
        } else require(recurrenceFacts == null)
        require(event == expected)
    }
    companion object {
        fun freeze(values: ContentValues, draft: EventDraft, recurrence: RecurrenceCreationPlan?, uid: String): OrdinaryCreatePlan {
            val bounds = when (val time = draft.time) {
                is EventTime.Timed -> time.start to time.end
                is EventTime.AllDay -> time.dates.start.atStartOfDay(ZoneOffset.UTC).toInstant() to time.dates.endExclusive.atStartOfDay(ZoneOffset.UTC).toInstant()
            }
            return OrdinaryCreatePlan(if (recurrence == null) "ONE_OFF" else "RECURRING", uid, values.keySet().associateWith { values.get(it)?.toString() } + ("uid2445" to uid),
                draft.alertMinutes.map { mapOf("minutes" to it.toString(), "method" to "1") }, bounds.first, bounds.second, draft.time is EventTime.AllDay,
                recurrence?.let { OrdinaryCreateRecurrenceFacts.freeze(it, draft) })
        }
        fun parse(j: JSONObject): OrdinaryCreatePlan {
            CreateSixJson.keys(j, "version", "mode", "uid", "event", "reminders", "firstStart", "firstEnd", "allDay", "recurrenceFacts")
            require(CreateSixJson.number(j, "version") == 1L)
            val rows = j.getJSONArray("reminders"); require(rows.length() <= 4000)
            return OrdinaryCreatePlan(CreateSixJson.text(j, "mode"), CreateSixJson.text(j, "uid"), CreateSixJson.row(j.getJSONObject("event")),
                List(rows.length()) { CreateSixJson.row(rows.getJSONObject(it)) }, CreateSixJson.instant(j, "firstStart"), CreateSixJson.instant(j, "firstEnd"), CreateSixJson.bool(j, "allDay"),
                if (j.isNull("recurrenceFacts")) null else OrdinaryCreateRecurrenceFacts.parse(j.getJSONObject("recurrenceFacts")))
        }
    }
}

/** A fixed protocol witness, not today's recurrence compiler or timezone policy. */
internal data class OrdinaryCreateRecurrenceFacts(val startLocal: String?, val startOffset: Int?, val untilBoundaryLocal: String?, val untilBoundaryOffset: Int?) {
    fun json() = JSONObject().put("startLocal", startLocal ?: JSONObject.NULL).put("startOffset", startOffset ?: JSONObject.NULL)
        .put("untilBoundaryLocal", untilBoundaryLocal ?: JSONObject.NULL).put("untilBoundaryOffset", untilBoundaryOffset ?: JSONObject.NULL)
    fun validate(spec: JSONObject, start: Instant, end: Instant, allDay: Boolean, rule: String, duration: String) {
        CreateSixJson.keys(spec, "pattern", "interval", "end", "weekStart")
        require(start.nano == 0 && end.nano == 0 && start.atOffset(ZoneOffset.UTC).year in 1..9999 && end.atOffset(ZoneOffset.UTC).year in 1..9999)
        val seconds = Math.subtractExact(end.epochSecond, start.epochSecond); require(seconds in 1..Int.MAX_VALUE.toLong())
        if (allDay) require(startLocal == null && startOffset == null && seconds % 86400 == 0L && duration == "P${seconds / 86400}D")
        else require(startLocal != null && startOffset != null && LocalDateTime.parse(startLocal).toInstant(ZoneOffset.ofTotalSeconds(startOffset)) == start && duration == "PT${seconds}S")
        val p = spec.getJSONObject("pattern")
        val parts = when (CreateSixJson.text(p, "type")) {
            "daily" -> { CreateSixJson.keys(p, "type"); mutableListOf("FREQ=DAILY") }
            "weekly" -> {
                CreateSixJson.keys(p, "type", "days"); val a = p.getJSONArray("days"); require(a.length() in 1..7)
                val days = List(a.length()) { DayOfWeek.valueOf(a.get(it) as? String ?: error("Invalid weekday")) }
                require(days.distinct() == days && days.sortedBy { it.value } == days)
                mutableListOf("FREQ=WEEKLY", "BYDAY=" + days.joinToString(",") { it.name.take(2) })
            }
            "monthlyDate" -> { CreateSixJson.keys(p, "type", "day"); val day = CreateSixJson.number(p, "day"); require(day in 1..31); mutableListOf("FREQ=MONTHLY", "BYMONTHDAY=$day") }
            "monthlyWeekday" -> {
                CreateSixJson.keys(p, "type", "ordinal", "day"); val ordinal = CreateSixJson.number(p, "ordinal"); require(ordinal in -5..5 && ordinal != 0L)
                mutableListOf("FREQ=MONTHLY", "BYDAY=$ordinal" + DayOfWeek.valueOf(CreateSixJson.text(p, "day")).name.take(2))
            }
            "yearly" -> { CreateSixJson.keys(p, "type", "monthDay"); val day = MonthDay.parse(CreateSixJson.text(p, "monthDay")); mutableListOf("FREQ=YEARLY", "BYMONTH=${day.monthValue}", "BYMONTHDAY=${day.dayOfMonth}") }
            else -> error("Unknown frozen pattern")
        }
        val interval = CreateSixJson.number(spec, "interval"); require(interval in 1..1000)
        parts += "INTERVAL=$interval"; parts += "WKST=" + DayOfWeek.valueOf(CreateSixJson.text(spec, "weekStart")).name.take(2)
        val limit = spec.getJSONObject("end")
        when (CreateSixJson.text(limit, "type")) {
            "never" -> { CreateSixJson.keys(limit, "type"); require(untilBoundaryLocal == null && untilBoundaryOffset == null) }
            "count" -> { CreateSixJson.keys(limit, "type", "count"); val count = CreateSixJson.number(limit, "count"); require(count in 1..10000 && untilBoundaryLocal == null && untilBoundaryOffset == null); parts += "COUNT=$count" }
            "through" -> {
                CreateSixJson.keys(limit, "type", "date"); val date = LocalDate.parse(CreateSixJson.text(limit, "date")); require(date.year in 1..9999)
                if (allDay) { require(untilBoundaryLocal == null && untilBoundaryOffset == null); parts += "UNTIL=" + date.format(DateTimeFormatter.BASIC_ISO_DATE) }
                else {
                    val boundary = LocalDateTime.parse(requireNotNull(untilBoundaryLocal)); val offset = requireNotNull(untilBoundaryOffset)
                    require(!boundary.isBefore(date.plusDays(1).atStartOfDay()) && boundary.isBefore(date.plusDays(3).atStartOfDay()))
                    val cutoff = boundary.toInstant(ZoneOffset.ofTotalSeconds(offset)).minusSeconds(1); require(cutoff >= start)
                    parts += "UNTIL=" + DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(cutoff)
                }
            }
            else -> error("Imported or unknown end")
        }
        require(rule == parts.joinToString(";"))
    }
    companion object {
        fun freeze(plan: RecurrenceCreationPlan, draft: EventDraft): OrdinaryCreateRecurrenceFacts {
            val timed = plan.start as? RecurrenceStart.Timed
            val boundary = (draft.recurrence?.end as? RecurrenceEnd.ThroughDate)?.takeIf { !plan.allDay }?.date?.plusDays(1)?.atStartOfDay(plan.eventZone)
            return OrdinaryCreateRecurrenceFacts(timed?.localDateTime?.toString(), timed?.preferredOffset?.totalSeconds, boundary?.toLocalDateTime()?.toString(), boundary?.offset?.totalSeconds)
        }
        fun parse(j: JSONObject): OrdinaryCreateRecurrenceFacts {
            CreateSixJson.keys(j, "startLocal", "startOffset", "untilBoundaryLocal", "untilBoundaryOffset")
            return OrdinaryCreateRecurrenceFacts(CreateSixJson.nullableText(j, "startLocal"), CreateSixJson.nullableInt(j, "startOffset"), CreateSixJson.nullableText(j, "untilBoundaryLocal"), CreateSixJson.nullableInt(j, "untilBoundaryOffset"))
        }
    }
}

internal object CreateSixJson {
    fun keys(j: JSONObject, vararg names: String) { require(j.keys().asSequence().toSet() == names.toSet()) }
    fun text(j: JSONObject, key: String) = j.get(key) as? String ?: error("Invalid text")
    fun nullableText(j: JSONObject, key: String): String? = if (j.get(key) === JSONObject.NULL) null else text(j, key)
    fun bool(j: JSONObject, key: String) = j.get(key) as? Boolean ?: error("Invalid Boolean")
    fun number(j: JSONObject, key: String) = CalendarCreateAcknowledgments.integer(j.get(key))
    fun nullableInt(j: JSONObject, key: String): Int? = if (j.get(key) === JSONObject.NULL) null else number(j, key).toInt().also { require(it.toLong() == number(j, key)) }
    fun instant(j: JSONObject, key: String): Instant = text(j, key).let { Instant.parse(it).also { parsed -> require(parsed.toString() == it) } }
    fun alerts(a: JSONArray): List<Int> {
        require(a.length() <= 4000)
        return List(a.length()) { CalendarCreateAcknowledgments.integer(a.get(it)).let { n -> n.toInt().also { require(it.toLong() == n && it != -1) } } }.also { require(it.distinct() == it) }
    }
    fun row(values: Map<String, String?>) = JSONObject().apply { values.toSortedMap().forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } }
    fun row(j: JSONObject): Map<String, String?> = j.keys().asSequence().associateWith { nullableText(j, it) }
    fun identity(i: CalendarIdentity) = JSONObject().put("calendar", i.calendarId.value).put("name", i.account.name).put("type", i.account.type)
        .put("owner", i.owner ?: JSONObject.NULL).put("providerId", i.providerId ?: JSONObject.NULL)
    fun identity(j: JSONObject): CalendarIdentity {
        keys(j, "calendar", "name", "type", "owner", "providerId")
        return CalendarIdentity(CalendarId(number(j, "calendar")), CalendarAccount(text(j, "name"), text(j, "type")), nullableText(j, "owner"), nullableText(j, "providerId"))
    }
    fun calendarIdentity(c: Map<String, String?>) = CalendarIdentity(CalendarId(requireNotNull(c["_id"]).toLong()), CalendarAccount(c["account_name"].orEmpty(), c["account_type"].orEmpty()),
        c["ownerAccount"]?.takeIf(String::isNotBlank), c["_sync_id"]?.takeIf(String::isNotBlank))
    fun ref(ref: EventRef) = JSONObject().put("calendar", ref.calendarId.value).put("event", ref.eventId).put("start", ref.instanceStart.toString()).put("end", ref.instanceEnd.toString()).apply {
        ref.recurrence?.let { put("master", it.masterEventId); put("original", it.originalStart.toString()) }
    }
    fun ref(j: JSONObject): EventRef {
        val recurring = j.has("master") || j.has("original")
        keys(j, *(listOf("calendar", "event", "start", "end") + if (recurring) listOf("master", "original") else emptyList()).toTypedArray())
        return EventRef(CalendarId(number(j, "calendar")), number(j, "event"), instant(j, "start"), instant(j, "end"), if (recurring) RecurrenceRef(number(j, "master"), instant(j, "original")) else null)
    }
    fun facts(j: JSONObject) {
        checkStoredJson(j.toString(), 64 * 1024)
        keys(j, "resultCount", "results"); number(j, "resultCount")
        val rows = j.getJSONArray("results"); require(rows.length() <= 4002)
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index); keys(row, "index", "uri", "count"); number(row, "index")
            nullableText(row, "uri")?.let { require(it.length <= 256 && it.toByteArray(Charsets.UTF_8).size <= 256) }
            if (row.get("count") !== JSONObject.NULL) number(row, "count")
        }
    }
    fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        is Boolean, is Int, is Long -> value.toString()
        else -> error("Unsupported digest scalar")
    }
    fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
