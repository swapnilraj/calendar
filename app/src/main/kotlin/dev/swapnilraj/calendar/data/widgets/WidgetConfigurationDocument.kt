package dev.swapnilraj.calendar.data.widgets

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.swapnilraj.calendar.android.widgets.*
import dev.swapnilraj.calendar.core.CalendarId
import dev.swapnilraj.calendar.core.CalendarIdentity
import dev.swapnilraj.calendar.core.CalendarAccount
import java.io.StringReader
import java.io.StringWriter

internal data class WidgetConfigurationRow(val epoch: WidgetEpoch, val configuration: AgendaWidgetConfiguration?)
internal data class WidgetConfigurationDocument(val namespace: String, val rows: Map<Int, WidgetConfigurationRow> = emptyMap())
internal sealed interface WidgetDocumentRead {
    data class Ready(val document: WidgetConfigurationDocument) : WidgetDocumentRead
    data object Unavailable : WidgetDocumentRead
}

/** Strict in-memory codec. Absent is an empty document, never proof of a live widget binding. */
internal object WidgetConfigurationDocuments {
    val key = stringPreferencesKey("widget_configuration_document")
    fun decode(text: String?, expectedNamespace: String): WidgetDocumentRead = try {
        require(expectedNamespace.isNotBlank())
        if (text == null) WidgetDocumentRead.Ready(WidgetConfigurationDocument(expectedNamespace))
        else JsonReader(StringReader(text)).use { reader ->
            reader.isLenient = false
            var version: Long? = null; var namespace: String? = null; var rows: Map<Int, WidgetConfigurationRow>? = null
            val fields = fields(reader) { when (it) {
                "version" -> version = number(reader)
                "namespace" -> namespace = string(reader)
                "rows" -> rows = WidgetConfigurationDocuments.rows(reader)
                else -> reader.skipValue()
            } }
            require(fields == setOf("version", "namespace", "rows") && version == 1L && namespace == expectedNamespace)
            require(reader.peek() == JsonToken.END_DOCUMENT)
            WidgetDocumentRead.Ready(WidgetConfigurationDocument(expectedNamespace, requireNotNull(rows)))
        }
    } catch (_: Exception) { WidgetDocumentRead.Unavailable }

    fun encode(document: WidgetConfigurationDocument): String {
        require(document.namespace.isNotBlank())
        val output = StringWriter()
        JsonWriter(output).use { w ->
            w.beginObject().name("version").value(1L).name("namespace").value(document.namespace).name("rows").beginArray()
            document.rows.toSortedMap().forEach { (id, row) ->
                require(id == row.epoch.widgetId)
                w.beginObject().name("id").value(id.toLong()).name("epoch").value(row.epoch.token).name("configuration")
                val config = row.configuration
                if (config == null) w.nullValue() else {
                    require(config.generation.isNotBlank())
                    require(config.expectedCalendarIdentities.isEmpty() || validWidgetPins(config.selection, config.expectedCalendarIdentities))
                    w.beginObject().name("generation").value(config.generation).name("privacy").value(config.privacy.name)
                        .name("selection").beginObject()
                    when (val selected = config.selection) {
                        WidgetCalendarSelection.FollowApp -> w.name("kind").value("FOLLOW_APP").name("ids").nullValue()
                        is WidgetCalendarSelection.Explicit -> {
                            w.name("kind").value("EXPLICIT").name("ids").beginArray()
                            selected.ids.sortedBy { it.value }.forEach { w.value(it.value) }; w.endArray()
                        }
                    }
                    w.endObject().name("pins").beginArray()
                    config.expectedCalendarIdentities.toSortedMap(compareBy { it.value }).forEach { (id, pin) ->
                        require(id == pin.calendarId)
                        w.beginObject().name("id").value(id.value).name("accountName").value(pin.account.name)
                            .name("accountType").value(pin.account.type).name("owner").value(pin.owner)
                            .name("providerId").value(pin.providerId).endObject()
                    }
                    w.endArray().endObject()
                }
                w.endObject()
            }
            w.endArray().endObject()
        }
        return output.toString()
    }
    private fun rows(r: JsonReader): Map<Int, WidgetConfigurationRow> {
        val result = linkedMapOf<Int, WidgetConfigurationRow>(); r.beginArray()
        while (r.hasNext()) {
            var id: Long? = null; var epoch: String? = null; var config: AgendaWidgetConfiguration? = null
            val names = fields(r) { when (it) {
                "id" -> id = number(r)
                "epoch" -> epoch = string(r)
                "configuration" -> config = if (r.peek() == JsonToken.NULL) { r.nextNull(); null } else configuration(r)
                else -> r.skipValue()
            } }
            require(names == setOf("id", "epoch", "configuration"))
            val raw = requireNotNull(id); require(raw in 1..Int.MAX_VALUE.toLong())
            val key = raw.toInt(); require(key !in result)
            result[key] = WidgetConfigurationRow(WidgetEpoch(key, requireNotNull(epoch)), config)
        }
        r.endArray(); return result
    }
    private fun configuration(r: JsonReader): AgendaWidgetConfiguration {
        var generation: String? = null; var privacy: WidgetPrivacy? = null; var selection: WidgetCalendarSelection? = null
        var pins = emptyMap<CalendarId, CalendarIdentity>()
        val names = fields(r) { when (it) {
            "generation" -> generation = string(r)
            "privacy" -> privacy = WidgetPrivacy.valueOf(string(r))
            "selection" -> selection = WidgetConfigurationDocuments.selection(r)
            "pins" -> pins = pins(r)
            else -> r.skipValue()
        } }
        require(names == setOf("generation", "privacy", "selection") || names == setOf("generation", "privacy", "selection", "pins"))
        require(pins.isEmpty() || validWidgetPins(requireNotNull(selection), pins))
        return AgendaWidgetConfiguration(requireNotNull(generation), requireNotNull(selection), requireNotNull(privacy), pins)
    }
    private fun pins(r: JsonReader): Map<CalendarId, CalendarIdentity> {
        val result = mutableMapOf<CalendarId, CalendarIdentity>(); r.beginArray()
        while (r.hasNext()) {
            var id: CalendarId? = null; var name: String? = null; var type: String? = null
            var owner: String? = null; var provider: String? = null
            fun nullable(): String? = if (r.peek() == JsonToken.NULL) { r.nextNull(); null } else string(r).also { require(it.isNotBlank()) }
            val names = fields(r) { when (it) {
                "id" -> id = CalendarId(number(r))
                "accountName" -> name = string(r).also { require(it.isNotBlank()) }
                "accountType" -> type = string(r).also { require(it.isNotBlank()) }
                "owner" -> owner = nullable()
                "providerId" -> provider = nullable()
                else -> r.skipValue()
            } }
            require(names == setOf("id", "accountName", "accountType", "owner", "providerId"))
            val key = requireNotNull(id); require(key !in result)
            result[key] = CalendarIdentity(key, CalendarAccount(requireNotNull(name), requireNotNull(type)), owner, provider)
        }
        r.endArray(); return result.toMap()
    }
    private fun selection(r: JsonReader): WidgetCalendarSelection {
        var kind: String? = null; var ids: Set<CalendarId>? = null
        val names = fields(r) { when (it) {
            "kind" -> kind = string(r)
            "ids" -> {
                if (r.peek() == JsonToken.NULL) r.nextNull() else {
                    val found = mutableSetOf<CalendarId>(); r.beginArray()
                    while (r.hasNext()) require(found.add(CalendarId(number(r))))
                    r.endArray(); ids = found.toSet()
                }
            }
            else -> r.skipValue()
        } }
        require(names == setOf("kind", "ids"))
        return when (kind) {
            "FOLLOW_APP" -> { require(ids == null); WidgetCalendarSelection.FollowApp }
            "EXPLICIT" -> WidgetCalendarSelection.Explicit(requireNotNull(ids))
            else -> error("Unknown selection")
        }
    }
    private fun fields(r: JsonReader, read: (String) -> Unit): Set<String> {
        val names = mutableSetOf<String>(); r.beginObject()
        while (r.hasNext()) { val name = r.nextName(); require(names.add(name)); read(name) }
        r.endObject(); return names
    }
    private fun string(r: JsonReader): String { require(r.peek() == JsonToken.STRING); return r.nextString() }
    private fun number(r: JsonReader): Long {
        require(r.peek() == JsonToken.NUMBER); val text = r.nextString()
        require(Regex("-?(0|[1-9][0-9]*)").matches(text)); return text.toLong()
    }
}
