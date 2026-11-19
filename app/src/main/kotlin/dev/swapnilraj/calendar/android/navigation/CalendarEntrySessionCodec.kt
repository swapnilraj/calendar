package dev.swapnilraj.calendar.android.navigation

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import dev.swapnilraj.calendar.core.CalendarAccount
import dev.swapnilraj.calendar.core.CalendarId
import dev.swapnilraj.calendar.core.CalendarIdentity
import java.io.StringReader
import java.io.StringWriter
import java.security.MessageDigest
import java.util.UUID

sealed interface CalendarEntryRestore {
    data class Ready(val session: CalendarEntrySession) : CalendarEntryRestore
    data object Unavailable : CalendarEntryRestore
}

/** Private saved-state evidence only. Unknown/malformed/present-but-null never means a fresh pin.
 * The request digest contains no readable caller title/notes/guest payload; no details are saved.
 */
object CalendarEntrySessionCodec {
    fun encode(session: CalendarEntrySession): String {
        val text = StringWriter()
        JsonWriter(text).use { w ->
            w.beginObject().name("version").value(1L).name("session").value(session.id)
                .name("request").value(requestKey(session.route)).name("binding")
            val pin = session.binding
            if (pin == null) w.nullValue() else {
                w.beginObject().name("event").value(pin.eventId).name("calendar").value(pin.calendar.calendarId.value)
                    .name("accountName").value(pin.calendar.account.name).name("accountType").value(pin.calendar.account.type)
                    .name("owner").value(pin.calendar.owner).name("calendarUid").value(pin.calendar.providerId)
                    .name("eventUid").value(pin.providerUid).endObject()
            }
            w.endObject()
        }
        return text.toString()
    }

    fun restore(route: CalendarIntentRoute, text: String?): CalendarEntryRestore = try {
        requireNotNull(text)
        JsonReader(StringReader(text)).use { r ->
            r.isLenient = false
            var version: Long? = null; var session: String? = null; var request: String? = null; var binding: CalendarEntryBinding? = null
            val fields = fields(r) { when (it) {
                "version" -> version = number(r)
                "session" -> session = string(r)
                "request" -> request = string(r)
                "binding" -> binding = if (r.peek() == JsonToken.NULL) { r.nextNull(); null } else binding(r)
                else -> r.skipValue()
            } }
            require(fields == setOf("version", "session", "request", "binding") && version == 1L)
            val id = requireNotNull(session); require(UUID.fromString(id).toString() == id)
            require(request == requestKey(route) && r.peek() == JsonToken.END_DOCUMENT)
            val event = when (route) {
                is CalendarIntentRoute.OpenEvent -> route.eventId
                is CalendarIntentRoute.EditEvent -> route.eventId
                else -> null
            }
            require(binding == null || binding?.eventId == event)
            CalendarEntryRestore.Ready(CalendarEntrySession(route, id, binding))
        }
    } catch (_: Exception) { CalendarEntryRestore.Unavailable }

    private fun binding(r: JsonReader): CalendarEntryBinding {
        var event: Long? = null; var calendar: Long? = null; var name: String? = null; var type: String? = null
        var owner: String? = null; var calendarUid: String? = null; var eventUid: String? = null
        fun nullable(): String? = if (r.peek() == JsonToken.NULL) { r.nextNull(); null } else string(r).also { require(it.isNotBlank()) }
        val names = fields(r) { when (it) {
            "event" -> event = number(r)
            "calendar" -> calendar = number(r)
            "accountName" -> name = string(r)
            "accountType" -> type = string(r)
            "owner" -> owner = nullable()
            "calendarUid" -> calendarUid = nullable()
            "eventUid" -> eventUid = nullable()
            else -> r.skipValue()
        } }
        require(names == setOf("event", "calendar", "accountName", "accountType", "owner", "calendarUid", "eventUid"))
        return CalendarEntryBinding(requireNotNull(event), CalendarIdentity(CalendarId(requireNotNull(calendar)),
            CalendarAccount(requireNotNull(name), requireNotNull(type)), owner, calendarUid), eventUid)
    }

    private fun fields(r: JsonReader, next: (String) -> Unit): Set<String> {
        val names = mutableSetOf<String>(); r.beginObject()
        while (r.hasNext()) { val name = r.nextName(); require(names.add(name)); next(name) }
        r.endObject(); return names
    }
    private fun string(r: JsonReader): String { require(r.peek() == JsonToken.STRING); return r.nextString() }
    private fun number(r: JsonReader): Long {
        require(r.peek() == JsonToken.NUMBER); val value = r.nextString()
        require(Regex("-?(0|[1-9][0-9]*)").matches(value)); return value.toLong()
    }
    private fun requestKey(route: CalendarIntentRoute): String {
        val values = mutableListOf<String?>()
        fun hints(value: CalendarOccurrenceHints) { values += value.begin?.toString(); values += value.end?.toString(); values += value.allDay?.toString() }
        fun seed(value: CalendarDraftSeed) {
            hints(value.occurrence)
            values += value.title; values += value.location; values += value.description
            values += value.guests.size.toString(); values.addAll(value.guests)
            values += value.recurrenceRule; values += value.timeZone?.id; values += value.availability?.toString(); values += value.accessLevel?.toString()
        }
        when (route) {
            is CalendarIntentRoute.OpenDate -> { values += "date"; values += route.instant.toString() }
            is CalendarIntentRoute.OpenEvent -> { values += "event"; values += route.eventId.toString(); hints(route.occurrence) }
            is CalendarIntentRoute.CreateDraftSeed -> { values += "create"; seed(route.seed) }
            is CalendarIntentRoute.EditEvent -> { values += "edit"; values += route.eventId.toString(); seed(route.seed) }
            is CalendarIntentRoute.Invalid -> { values += "invalid"; values += route.reason.name }
            CalendarIntentRoute.Unsupported -> values += "unsupported"
        }
        val canonical = values.joinToString("") { value -> value?.let { "${it.toByteArray(Charsets.UTF_8).size}:$it" } ?: "-1:" }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
