package dev.swapnilraj.calendar.data.reminders

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.swapnilraj.calendar.core.*
import java.io.StringReader
import java.io.StringWriter

/** Pure, in-memory Preferences transformation. Root owns DataStore edits, IO, flows and retry policy.
 * A present bad value is never repaired or converted into disabled settings. No sensitive input logs.
 */
object ReminderSettingsCodec {
    val key = stringPreferencesKey("app_owned_reminder_settings")
    private const val Version = 1L
    private val integer = Regex("-?(0|[1-9][0-9]*)")
    private class Invalid(val problem: ReminderSettingsProblem) : RuntimeException()

    fun read(values: Preferences): ReminderSettingsRead = try { decode(values[key]) }
        catch (_: Exception) { ReminderSettingsRead.Unavailable(ReminderSettingsProblem.MALFORMED) }

    fun decode(encoded: String?): ReminderSettingsRead {
        if (encoded == null) return ReminderSettingsRead.Ready(SavedReminderSettings())
        return try {
            val reader = JsonReader(StringReader(encoded)).apply { isLenient = false }
            val settings = reader.use {
                var version: Long? = null
                var enabled: Boolean? = null
                var pins: Map<CalendarId, ReminderCalendarIdentity>? = null
                var defaults: Map<CalendarId, ReminderDefaults>? = null
                val fields = fields(it) { name -> when (name) {
                    "version" -> version = long(it)
                    "enabled" -> { require(it.peek() == JsonToken.BOOLEAN); enabled = it.nextBoolean() }
                    "pins" -> pins = ReminderSettingsCodec.pins(it)
                    "defaults" -> defaults = ReminderSettingsCodec.defaults(it)
                    else -> it.skipValue()
                } }
                if (version != null && version != Version) throw Invalid(ReminderSettingsProblem.UNSUPPORTED_VERSION)
                require(fields == setOf("version", "enabled", "pins", "defaults"))
                require(it.peek() == JsonToken.END_DOCUMENT)
                SavedReminderSettings(requireNotNull(enabled), requireNotNull(pins), requireNotNull(defaults)).also(::validate)
            }
            ReminderSettingsRead.Ready(settings)
        } catch (failure: Invalid) { ReminderSettingsRead.Unavailable(failure.problem) }
        catch (_: Exception) { ReminderSettingsRead.Unavailable(ReminderSettingsProblem.MALFORMED) }
    }

    /** Validate and encode fully before the single assignment. An invalid snapshot changes no key.
     * The snapshot represents explicit setup choices; discovery may only use strengthen(), never
     * replace pins. Call within root's DataStore.edit; storage failure must propagate from that edit.
     */
    fun write(values: MutablePreferences, snapshot: SavedReminderSettings): ReminderSettingsSave {
        val (stable, encoded) = try {
            val stable = snapshot.copy(expectedCalendarIdentities = snapshot.expectedCalendarIdentities.toMap(),
                explicitDefaults = snapshot.explicitDefaults.toMap())
            validate(stable)
            stable to encode(stable)
        } catch (_: Exception) { return ReminderSettingsSave.Invalid(ReminderSettingsProblem.INVALID_SNAPSHOT) }
        values[key] = encoded
        return ReminderSettingsSave.Saved(stable)
    }

    private fun encode(settings: SavedReminderSettings): String {
        val output = StringWriter()
        JsonWriter(output).use { writer ->
            writer.beginObject().name("version").value(Version).name("enabled").value(settings.enabled)
                .name("pins").beginArray()
            settings.expectedCalendarIdentities.toSortedMap(compareBy { it.value }).forEach { (id, identity) ->
                writer.beginObject().name("id").value(id.value).name("fingerprint").value(identity.fingerprint).name("uid")
                if (identity.providerUidFingerprint == null) writer.nullValue() else writer.value(identity.providerUidFingerprint)
                writer.endObject()
            }
            writer.endArray().name("defaults").beginArray()
            settings.explicitDefaults.toSortedMap(compareBy { it.value }).forEach { (id, defaults) ->
                val timed = defaults.timedMinutesBefore
                val allDay = defaults.allDayMinutesBefore
                writer.beginObject().name("id").value(id.value).name("timedMinutes")
                if (timed == null) writer.nullValue() else writer.value(timed.toLong())
                writer.name("allDayMinutes")
                if (allDay == null) writer.nullValue() else writer.value(allDay.toLong())
                writer.name("method").value(defaults.defaultMethod.name).endObject()
            }
            writer.endArray().endObject()
        }
        return output.toString()
    }

    private fun validate(settings: SavedReminderSettings) {
        if (!settings.explicitDefaults.keys.all { it in settings.expectedCalendarIdentities } ||
            settings.expectedCalendarIdentities.values.any { it.fingerprint.isBlank() || it.providerUidFingerprint?.isBlank() == true } ||
            settings.explicitDefaults.values.any { it.timedMinutesBefore == -1 || it.allDayMinutesBefore == -1 })
            throw Invalid(ReminderSettingsProblem.INVALID_SNAPSHOT)
    }

    private fun pins(reader: JsonReader): Map<CalendarId, ReminderCalendarIdentity> {
        val result = linkedMapOf<CalendarId, ReminderCalendarIdentity>()
        reader.beginArray()
        while (reader.hasNext()) {
            var id: Long? = null; var fingerprint: String? = null; var uid: String? = null
            val fields = fields(reader) { name -> when (name) {
                "id" -> id = long(reader)
                "fingerprint" -> fingerprint = string(reader)
                "uid" -> uid = nullableString(reader)
                else -> reader.skipValue()
            } }
            require(fields == setOf("id", "fingerprint", "uid"))
            val calendar = CalendarId(requireNotNull(id))
            require(calendar !in result)
            result[calendar] = ReminderCalendarIdentity(requireNotNull(fingerprint), uid)
        }
        reader.endArray()
        return result
    }

    private fun defaults(reader: JsonReader): Map<CalendarId, ReminderDefaults> {
        val result = linkedMapOf<CalendarId, ReminderDefaults>()
        reader.beginArray()
        while (reader.hasNext()) {
            var id: Long? = null; var timed: Int? = null; var allDay: Int? = null; var method: ReminderDefaultMethod? = null
            val fields = fields(reader) { name -> when (name) {
                "id" -> id = long(reader)
                "timedMinutes" -> timed = nullableInt(reader)
                "allDayMinutes" -> allDay = nullableInt(reader)
                "method" -> method = ReminderDefaultMethod.valueOf(string(reader))
                else -> reader.skipValue()
            } }
            require(fields == setOf("id", "timedMinutes", "allDayMinutes", "method"))
            val calendar = CalendarId(requireNotNull(id))
            require(calendar !in result)
            result[calendar] = ReminderDefaults(timed, allDay, requireNotNull(method))
        }
        reader.endArray()
        return result
    }

    private fun fields(reader: JsonReader, consume: (String) -> Unit): Set<String> {
        val seen = mutableSetOf<String>()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            require(seen.add(name))
            consume(name)
        }
        reader.endObject()
        return seen
    }
    private fun string(reader: JsonReader): String { require(reader.peek() == JsonToken.STRING); return reader.nextString() }
    private fun nullableString(reader: JsonReader): String? = if (reader.peek() == JsonToken.NULL) { reader.nextNull(); null } else string(reader)
    private fun long(reader: JsonReader): Long {
        require(reader.peek() == JsonToken.NUMBER)
        val text = reader.nextString(); require(integer.matches(text)); return text.toLong()
    }
    private fun nullableInt(reader: JsonReader): Int? {
        if (reader.peek() == JsonToken.NULL) { reader.nextNull(); return null }
        return long(reader).also { require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) }.toInt()
    }
}
