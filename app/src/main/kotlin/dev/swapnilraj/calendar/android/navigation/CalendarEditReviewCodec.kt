package dev.swapnilraj.calendar.android.navigation

import android.util.JsonReader
import android.util.JsonToken
import dev.swapnilraj.calendar.core.*
import java.io.StringReader
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Private typed transport: suggestions never supply the existing event's authority. */
object CalendarEditReviewCodec {
    private const val MAX_REQUEST = 65_536
    fun encode(request: CalendarEditReviewRequest): String {
        validate(request)
        return JSONObject().put("schema", 1).put("session", request.sessionId)
            .put("selected", CalendarEditJson.ref(request.selected)).put("binding", CalendarEditJson.binding(request.binding))
            .put("proposed", CalendarEditJson.seed(request.proposed)).put("reviewedZone", request.reviewedZone.id)
            .toString().also { require(it.length <= MAX_REQUEST) }
    }

    fun decode(raw: String?): CalendarEditReviewRequest? = try {
        val value = CalendarEditJson.objectValue(requireNotNull(raw), MAX_REQUEST)
        CalendarEditJson.keys(value, setOf("schema", "session", "selected", "binding", "proposed", "reviewedZone"))
        require(value.get("schema") == 1)
        CalendarEditReviewRequest(CalendarEditJson.uuid(value, "session"), CalendarEditJson.readRef(value.getJSONObject("selected")),
            CalendarEditJson.readBinding(value.getJSONObject("binding")), CalendarEditJson.readSeed(value.getJSONObject("proposed")),
            ZoneId.of(CalendarEditJson.text(value, "reviewedZone"))).also(::validate)
    } catch (_: Exception) { null }

    private fun validate(request: CalendarEditReviewRequest) {
        require(UUID.fromString(request.sessionId).toString() == request.sessionId)
        require(request.selected.recurrence == null && request.selected.eventId == request.binding.eventId && request.selected.calendarId == request.binding.calendar.calendarId)
        listOf(request.selected.instanceStart, request.selected.instanceEnd).forEach { require(it.atZone(request.reviewedZone).year in 1..9999) }
        val seed = request.proposed
        require(seed.guests.size <= 100)
        (listOfNotNull(seed.title, seed.location, seed.description, seed.recurrenceRule) + seed.guests).forEach { require(it.length <= 16_384) }
        require(seed.availability == null || seed.availability in 0..2)
        require(seed.accessLevel == null || seed.accessLevel in 0..3)
        val zone = if (seed.occurrence.allDay == true) ZoneOffset.UTC else seed.timeZone ?: request.reviewedZone
        listOfNotNull(seed.occurrence.begin, seed.occurrence.end).forEach { require(it.atZone(zone).year in 1..9999) }
        // Partial/backwards suggestions remain raw review inputs. They never choose the target,
        // become a normalized EventDraft here, or bypass the native editor's validation.
    }
}

internal object CalendarEditJson {
    fun objectValue(raw: String, limit: Int = 4_500_000): JSONObject {
        require(raw.length <= limit)
        JsonReader(StringReader(raw)).use { reader ->
            reader.isLenient = false
            fun inspect(depth: Int) {
                require(depth <= 12)
                when (reader.peek()) {
                    JsonToken.BEGIN_OBJECT -> {
                        reader.beginObject(); val names = mutableSetOf<String>()
                        while (reader.hasNext()) { require(names.add(reader.nextName())); inspect(depth + 1) }
                        reader.endObject()
                    }
                    JsonToken.BEGIN_ARRAY -> {
                        reader.beginArray(); var count = 0
                        while (reader.hasNext()) { require(++count <= 10_000); inspect(depth + 1) }
                        reader.endArray()
                    }
                    JsonToken.NUMBER -> require(Regex("-?(0|[1-9][0-9]*)").matches(reader.nextString()))
                    else -> reader.skipValue()
                }
            }
            inspect(0); require(reader.peek() == JsonToken.END_DOCUMENT)
        }
        return JSONObject(raw)
    }
    fun keys(value: JSONObject, expected: Set<String>) = require(value.keys().asSequence().toSet() == expected)
    fun text(value: JSONObject, key: String) = value.get(key) as? String ?: error("Invalid private input")
    fun nullableText(value: JSONObject, key: String) = if (value.isNull(key)) null else text(value, key)
    fun number(value: JSONObject, key: String): Long = when (val item = value.get(key)) {
        is Int -> item.toLong(); is Long -> item; else -> error("Invalid private number")
    }
    fun uuid(value: JSONObject, key: String) = text(value, key).also { require(UUID.fromString(it).toString() == it) }
    fun nullable(value: Any?): Any = value ?: JSONObject.NULL

    fun ref(value: EventRef) = JSONObject().put("calendar", value.calendarId.value).put("event", value.eventId)
        .put("start", value.instanceStart.toString()).put("end", value.instanceEnd.toString())
    fun readRef(value: JSONObject): EventRef {
        keys(value, setOf("calendar", "event", "start", "end"))
        return EventRef(CalendarId(number(value, "calendar")), number(value, "event"), Instant.parse(text(value, "start")), Instant.parse(text(value, "end")))
    }
    fun binding(value: CalendarEntryBinding) = JSONObject().put("event", value.eventId).put("calendar", value.calendar.calendarId.value)
        .put("accountName", value.calendar.account.name).put("accountType", value.calendar.account.type)
        .put("owner", nullable(value.calendar.owner)).put("calendarUid", nullable(value.calendar.providerId)).put("eventUid", nullable(value.providerUid))
    fun readBinding(value: JSONObject): CalendarEntryBinding {
        keys(value, setOf("event", "calendar", "accountName", "accountType", "owner", "calendarUid", "eventUid"))
        return CalendarEntryBinding(number(value, "event"), CalendarIdentity(CalendarId(number(value, "calendar")),
            CalendarAccount(text(value, "accountName"), text(value, "accountType")), nullableText(value, "owner"), nullableText(value, "calendarUid")), nullableText(value, "eventUid"))
    }
    fun seed(value: CalendarDraftSeed) = JSONObject().put("begin", nullable(value.occurrence.begin?.toString())).put("end", nullable(value.occurrence.end?.toString()))
        .put("allDay", nullable(value.occurrence.allDay)).put("title", nullable(value.title)).put("location", nullable(value.location))
        .put("description", nullable(value.description)).put("guests", JSONArray(value.guests)).put("rule", nullable(value.recurrenceRule))
        .put("zone", nullable(value.timeZone?.id)).put("availability", nullable(value.availability)).put("privacy", nullable(value.accessLevel))
    fun readSeed(value: JSONObject): CalendarDraftSeed {
        keys(value, setOf("begin", "end", "allDay", "title", "location", "description", "guests", "rule", "zone", "availability", "privacy"))
        fun integer(key: String) = if (value.isNull(key)) null else number(value, key).let { it.toInt().also { int -> require(int.toLong() == it) } }
        val guests = value.getJSONArray("guests"); require(guests.length() <= 100)
        return CalendarDraftSeed(CalendarOccurrenceHints(nullableText(value, "begin")?.let(Instant::parse), nullableText(value, "end")?.let(Instant::parse),
            if (value.isNull("allDay")) null else value.get("allDay") as? Boolean ?: error("Invalid all-day flag")),
            nullableText(value, "title"), nullableText(value, "location"), nullableText(value, "description"),
            (0 until guests.length()).map { guests.get(it) as? String ?: error("Invalid guest input") }, nullableText(value, "rule"),
            nullableText(value, "zone")?.let(ZoneId::of), integer("availability"), integer("privacy"))
    }
}
