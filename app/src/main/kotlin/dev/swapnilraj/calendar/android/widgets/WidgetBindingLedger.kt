package dev.swapnilraj.calendar.android.widgets

import android.util.AtomicFile
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import java.io.File
import java.io.StringReader
import java.io.StringWriter
import java.util.UUID

internal enum class WidgetBindingPhase { CANDIDATE, ACTIVE, QUARANTINED }
internal data class WidgetBindingRecord(val epoch: WidgetEpoch, val phase: WidgetBindingPhase)
internal data class WidgetBindingSnapshot(val namespace: String, val records: Map<Int, WidgetBindingRecord>)

/** Small synchronous durability fence, not a nonblocking API. AtomicFile failures poison this
 * instance. Missing/malformed storage never regenerates a namespace around existing config data.
 */
internal class WidgetBindingLedger private constructor(private val file: AtomicFile, initial: WidgetBindingSnapshot) {
    private var value = initial
    private var unavailable = false
    @Synchronized fun snapshot(): WidgetBindingSnapshot { check(!unavailable); return value.copy(records = value.records.toMap()) }
    @Synchronized fun quarantine(ids: Set<Int>): Map<Int, WidgetEpoch> {
        require(ids.all { it > 0 })
        val epochs = ids.associateWith { WidgetEpoch(it, UUID.randomUUID().toString()) }
        persist(value.copy(records = value.records + epochs.mapValues { WidgetBindingRecord(it.value, WidgetBindingPhase.QUARANTINED) }))
        return epochs
    }
    @Synchronized fun transition(epoch: WidgetEpoch, from: WidgetBindingPhase, to: WidgetBindingPhase) {
        check(value.records[epoch.widgetId] == WidgetBindingRecord(epoch, from))
        persist(value.copy(records = value.records + (epoch.widgetId to WidgetBindingRecord(epoch, to))))
    }
    private fun persist(next: WidgetBindingSnapshot) {
        check(!unavailable)
        var stream: java.io.FileOutputStream? = null
        try {
            val bytes = encode(next).toByteArray(Charsets.UTF_8)
            stream = file.startWrite(); stream.write(bytes)
            stream.fd.sync() // AtomicFile can merely log a sync failure; this failure must revoke authority.
            file.finishWrite(stream)
            check(file.openRead().use { it.readBytes() }.contentEquals(bytes))
            value = next
        } catch (failure: Exception) {
            unavailable = true
            stream?.let { try { file.failWrite(it) } catch (_: Exception) { } }
            throw failure
        }
    }
    companion object {
        const val FILE_NAME = "agenda_widget_bindings.json"
        /** Root calls on IO during bootstrap. Only a genuinely empty installation creates identity. */
        @Synchronized fun open(noBackupDirectory: File): WidgetBindingLedger? = try {
            val base = File(noBackupDirectory, FILE_NAME)
            val artifacts = if (noBackupDirectory.exists()) requireNotNull(noBackupDirectory.listFiles()).map { it.name } else emptyList()
            val ledgerExists = artifacts.any { it == FILE_NAME || it.startsWith("$FILE_NAME.") }
            val configExists = artifacts.any { it.startsWith("agenda_widgets.preferences_pb") }
            val atomic = AtomicFile(base)
            if (!ledgerExists) {
                if (configExists) null else {
                    noBackupDirectory.mkdirs()
                    val empty = WidgetBindingSnapshot(UUID.randomUUID().toString(), emptyMap())
                    WidgetBindingLedger(atomic, empty).also { it.persist(empty) }
                }
            } else WidgetBindingLedger(atomic, decode(atomic.openRead().use { it.readBytes().toString(Charsets.UTF_8) }))
        } catch (_: Exception) { null }
        private fun encode(snapshot: WidgetBindingSnapshot): String {
            val text = StringWriter()
            JsonWriter(text).use { w ->
                w.beginObject().name("version").value(1L).name("namespace").value(snapshot.namespace).name("records").beginArray()
                snapshot.records.toSortedMap().forEach { (id, record) ->
                    w.beginObject().name("id").value(id.toLong()).name("epoch").value(record.epoch.token)
                        .name("phase").value(record.phase.name).endObject()
                }
                w.endArray().endObject()
            }
            return text.toString()
        }
        private fun decode(text: String): WidgetBindingSnapshot = JsonReader(StringReader(text)).use { r ->
            r.isLenient = false
            var version: Long? = null; var namespace: String? = null; var records: Map<Int, WidgetBindingRecord>? = null
            val fields = fields(r) { when (it) {
                "version" -> version = number(r)
                "namespace" -> namespace = string(r)
                "records" -> {
                    val result = mutableMapOf<Int, WidgetBindingRecord>(); val tokens = mutableSetOf<String>(); r.beginArray()
                    while (r.hasNext()) {
                        var id: Long? = null; var token: String? = null; var phase: WidgetBindingPhase? = null
                        val names = fields(r) { name -> when (name) {
                            "id" -> id = number(r)
                            "epoch" -> token = string(r)
                            "phase" -> phase = WidgetBindingPhase.valueOf(string(r))
                            else -> r.skipValue()
                        } }
                        require(names == setOf("id", "epoch", "phase"))
                        val raw = requireNotNull(id); require(raw in 1..Int.MAX_VALUE.toLong())
                        val epoch = WidgetEpoch(raw.toInt(), requireNotNull(token))
                        require(epoch.widgetId !in result && tokens.add(epoch.token))
                        result[epoch.widgetId] = WidgetBindingRecord(epoch, requireNotNull(phase))
                    }
                    r.endArray(); records = result.toMap()
                }
                else -> r.skipValue()
            } }
            require(fields == setOf("version", "namespace", "records") && version == 1L && !namespace.isNullOrBlank())
            require(r.peek() == JsonToken.END_DOCUMENT)
            WidgetBindingSnapshot(requireNotNull(namespace), requireNotNull(records))
        }
        private fun fields(r: JsonReader, consume: (String) -> Unit): Set<String> {
            val seen = mutableSetOf<String>(); r.beginObject()
            while (r.hasNext()) { val name = r.nextName(); require(seen.add(name)); consume(name) }
            r.endObject(); return seen
        }
        private fun string(r: JsonReader): String { require(r.peek() == JsonToken.STRING); return r.nextString() }
        private fun number(r: JsonReader): Long {
            require(r.peek() == JsonToken.NUMBER); val text = r.nextString()
            require(Regex("-?(0|[1-9][0-9]*)").matches(text)); return text.toLong()
        }
    }
}
