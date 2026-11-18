package dev.swapnilraj.calendar.android.navigation

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import java.io.StringReader
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** App-private CREATE handoff. A session identifies one durable draft, never an existing event. */
data class CalendarSeedReviewRequest(val sessionId: String, val seed: CalendarDraftSeed, val reviewedZone: ZoneId) {
    val draftId: String get() = "incoming:$sessionId"
}

/** Bounded, exact typed transport. No truncation, default time, or lenient numeric coercion. */
object CalendarSeedReviewCodec {
    private const val MAX_DOCUMENT = 65_536
    private const val MAX_TEXT = 16_384
    private val keys = setOf("version", "session", "reviewedZone", "begin", "end", "allDay", "title", "location", "description", "guests", "rrule", "zone", "availability", "privacy")

    fun encode(request: CalendarSeedReviewRequest): String {
        validate(request)
        val text = StringWriter()
        JsonWriter(text).use { w ->
            w.beginObject().name("version").value(1L).name("session").value(request.sessionId)
                .name("reviewedZone").value(request.reviewedZone.id)
            val seed = request.seed
            w.name("begin").value(seed.occurrence.begin?.toString()).name("end").value(seed.occurrence.end?.toString()).name("allDay")
            seed.occurrence.allDay?.let { w.value(it) } ?: w.nullValue()
            w.name("title").value(seed.title).name("location").value(seed.location).name("description").value(seed.description)
                .name("guests").beginArray()
            seed.guests.forEach { w.value(it) }
            w.endArray().name("rrule").value(seed.recurrenceRule).name("zone").value(seed.timeZone?.id)
                .name("availability").value(seed.availability?.toLong()).name("privacy").value(seed.accessLevel?.toLong()).endObject()
        }
        return text.toString().also { require(it.length <= MAX_DOCUMENT) }
    }

    fun decode(raw: String?): CalendarSeedReviewRequest? = try {
        requireNotNull(raw); require(raw.length <= MAX_DOCUMENT)
        JsonReader(StringReader(raw)).use { r ->
            r.isLenient = false
            val values = mutableMapOf<String, Any?>()
            r.beginObject()
            while (r.hasNext()) {
                val name = r.nextName(); require(name in keys && !values.containsKey(name))
                values[name] = when (name) {
                    "version", "availability", "privacy" -> nullable(r) { number(r) }
                    "allDay" -> nullable(r) { require(r.peek() == JsonToken.BOOLEAN); r.nextBoolean() }
                    "guests" -> buildList {
                        r.beginArray()
                        while (r.hasNext()) { require(size < 100); add(string(r)) }
                        r.endArray()
                    }
                    else -> nullable(r) { string(r) }
                }
            }
            r.endObject(); require(r.peek() == JsonToken.END_DOCUMENT && values.keys == keys && values["version"] == 1L)
            fun text(key: String) = values[key] as String?
            fun int(key: String) = (values[key] as Long?)?.let { value -> value.toInt().also { require(it.toLong() == value) } }
            @Suppress("UNCHECKED_CAST")
            CalendarSeedReviewRequest(requireNotNull(text("session")), CalendarDraftSeed(
                CalendarOccurrenceHints(text("begin")?.let(Instant::parse), text("end")?.let(Instant::parse), values["allDay"] as Boolean?),
                text("title"), text("location"), text("description"), values["guests"] as List<String>, text("rrule"),
                text("zone")?.let(ZoneId::of), int("availability"), int("privacy")),
                ZoneId.of(requireNotNull(text("reviewedZone")))).also(::validate)
        }
    } catch (_: Exception) { null }

    private fun validate(request: CalendarSeedReviewRequest) {
        require(UUID.fromString(request.sessionId).toString() == request.sessionId)
        val seed = request.seed
        require(seed.guests.size <= 100)
        (listOfNotNull(seed.title, seed.location, seed.description, seed.recurrenceRule) + seed.guests).forEach { require(it.length <= MAX_TEXT) }
        require(seed.availability == null || seed.availability in 0..2)
        require(seed.accessLevel == null || seed.accessLevel in 0..3)
        require(seed.occurrence.end == null || seed.occurrence.begin != null)
        if (seed.occurrence.begin != null && seed.occurrence.end != null) require(seed.occurrence.end >= seed.occurrence.begin)
        if (seed.occurrence.allDay == true && seed.occurrence.begin != null && seed.occurrence.end != null) {
            require(seed.occurrence.end.atOffset(java.time.ZoneOffset.UTC).toLocalDate() > seed.occurrence.begin.atOffset(java.time.ZoneOffset.UTC).toLocalDate())
        }
        val zone = if (seed.occurrence.allDay == true) java.time.ZoneOffset.UTC else seed.timeZone ?: request.reviewedZone
        listOfNotNull(seed.occurrence.begin, seed.occurrence.end).forEach { require(it.atZone(zone).year in 1..9999) }
    }

    private inline fun <T> nullable(r: JsonReader, read: () -> T): T? = if (r.peek() == JsonToken.NULL) { r.nextNull(); null } else read()
    private fun string(r: JsonReader): String { require(r.peek() == JsonToken.STRING); return r.nextString().also { require(it.length <= MAX_TEXT) } }
    private fun number(r: JsonReader): Long {
        require(r.peek() == JsonToken.NUMBER)
        return r.nextString().also { require(Regex("-?(0|[1-9][0-9]*)").matches(it)) }.toLong()
    }
}
