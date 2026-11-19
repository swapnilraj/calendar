package dev.swapnilraj.calendar.probe

import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import android.util.JsonReader
import android.util.JsonToken
import java.io.File
import java.io.StringReader
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Private test-only ownership/phase fence. Never searches for or adopts an older run. */
internal class NativeWriterProbeState private constructor(private val file: AtomicFile, val value: JSONObject) {
    val run: String get() = value.getString("run")
    val database: String get() = "daylight-native-app-probe-$run.db"
    var phase: String
        get() = value.getString("phase")
        set(next) { value.put("phase", next) }
    val cases: JSONArray get() = value.getJSONArray("cases")
    fun save() {
        validate(value)
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        check(bytes.size <= 65_536)
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (failure: Throwable) { file.failWrite(stream); throw failure }
    }
    fun assertPersisted(context: Context) {
        check(open(context, run, value.getString("boot"), JSONObject(value.getString("pins"))).value.toString() == value.toString())
    }
    fun deleteAfterVerifiedCleanup(context: Context) {
        check(phase == "CLEANED")
        // The exact exclusive run database only; no production store or caller-supplied path.
        context.deleteDatabase(database)
        check(listOf("", "-wal", "-shm", "-journal").none { File(context.getDatabasePath(database).path + it).exists() })
        file.delete()
        check(listOf("", ".bak", ".new").none { File(file.baseFile.path + it).exists() })
    }
    companion object {
        private val keys = setOf("schema", "run", "boot", "pins", "started", "phase", "preflightAbsent", "creationAttempted",
            "calendar", "queries", "assertions", "cases")
        fun create(context: Context, run: String, boot: String, pins: JSONObject): NativeWriterProbeState {
            uuid(run); uuid(boot)
            val file = location(context, run)
            check(!file.exists() && !File(file.path + ".bak").exists() && !File(file.path + ".new").exists())
            val database = context.getDatabasePath("daylight-native-app-probe-$run.db")
            check(listOf("", "-wal", "-shm", "-journal").none { File(database.path + it).exists() })
            check(file.createNewFile()) // A crash before the first complete write remains corrupt, never fresh.
            return NativeWriterProbeState(AtomicFile(file), JSONObject().put("schema", 1).put("run", run).put("boot", boot)
                .put("pins", pins.toString()).put("started", SystemClock.elapsedRealtime()).put("phase", "CREATING")
                .put("preflightAbsent", false).put("creationAttempted", false).put("calendar", JSONObject.NULL)
                .put("queries", 0).put("assertions", 0).put("cases", JSONArray())).also { it.save() }
        }
        fun open(context: Context, run: String, boot: String, pins: JSONObject): NativeWriterProbeState {
            uuid(run); uuid(boot)
            val atomic = AtomicFile(location(context, run))
            val raw = atomic.openRead().use { input ->
                val bytes = input.readNBytes(65_537); check(bytes.size <= 65_536); bytes.toString(Charsets.UTF_8)
            }
            strict(raw)
            val value = JSONObject(raw); validate(value)
            check(value.getString("run") == run && value.getString("boot") == boot && value.getString("pins") == pins.toString())
            return NativeWriterProbeState(atomic, value)
        }
        private fun location(context: Context, run: String) = File(context.noBackupFilesDir, "native-writer-probe-$run.json")
        private fun uuid(value: String) { check(UUID.fromString(value).toString() == value) }
        private fun validate(v: JSONObject) {
            check(v.keys().asSequence().toSet() == keys && v.get("schema") == 1)
            uuid(v.get("run") as String); uuid(v.get("boot") as String)
            check(v.get("pins") is String && v.getString("pins").length <= 16_384)
            check(v.get("phase") in setOf("CREATING", "STAGED", "RECOVERING", "RECOVERED", "CLEANED"))
            check(v.get("preflightAbsent") is Boolean && v.get("creationAttempted") is Boolean)
            val start = number(v, "started"); check(start >= 0 && start <= SystemClock.elapsedRealtime())
            check(number(v, "queries") in 0..320 && number(v, "assertions") in 0..128)
            check(v.isNull("calendar") || number(v, "calendar") > 0)
            check(v.getJSONArray("cases").length() <= 6)
            val labels = mutableSetOf<String>(); val ids = mutableSetOf<Long>(); val uids = mutableSetOf<String>()
            for (index in 0 until v.getJSONArray("cases").length()) {
                val c = v.getJSONArray("cases").getJSONObject(index)
                check(c.keys().asSequence().toSet() == setOf("label", "operation", "id", "uid", "digest", "start", "end", "reminders", "mode", "allDay", "fault", "phase", "recoveries"))
                check(labels.add(c.get("label") as String) && c.getString("operation") == "native-probe:${v.getString("run")}:${c.getString("label")}")
                check(ids.add(number(c, "id")) && number(c, "id") > 0)
                val uid = c.get("uid") as String; uuid(uid.removeSuffix("@daylight.invalid")); check(uid.endsWith("@daylight.invalid") && uids.add(uid))
                check(Regex("[0-9a-f]{64}").matches(c.get("digest") as String))
                check(c.get("mode") in setOf("ONE_OFF", "RECURRING") && c.get("allDay") is Boolean && c.get("fault") is Boolean)
                check(c.get("phase") in setOf("COMMITTED", "VERIFIED") && number(c, "recoveries") in 0..2)
                check(number(c, "end") > number(c, "start"))
                val children = c.getJSONArray("reminders"); check(children.length() <= 2)
                val seen = mutableSetOf<Long>()
                for (i in 0 until children.length()) { val child = children.get(i); check(child is Number && child.toLong() > 0 && seen.add(child.toLong())) }
            }
        }
        fun number(v: JSONObject, key: String): Long = when (val n = v.get(key)) { is Int -> n.toLong(); is Long -> n; else -> error("Invalid probe state number") }
        private fun strict(raw: String) {
            JsonReader(StringReader(raw)).use { r ->
                r.isLenient = false
                fun read(depth: Int) {
                    check(depth <= 8)
                    when (r.peek()) {
                        JsonToken.BEGIN_OBJECT -> { r.beginObject(); val names = mutableSetOf<String>(); while (r.hasNext()) { check(names.add(r.nextName())); read(depth + 1) }; r.endObject() }
                        JsonToken.BEGIN_ARRAY -> { r.beginArray(); var n = 0; while (r.hasNext()) { check(++n <= 64); read(depth + 1) }; r.endArray() }
                        JsonToken.NUMBER -> check(Regex("-?(0|[1-9][0-9]*)").matches(r.nextString()))
                        else -> r.skipValue()
                    }
                }
                read(0); check(r.peek() == JsonToken.END_DOCUMENT)
            }
        }
    }
}
