package dev.swapnilraj.calendar.probe

import android.content.Context
import android.app.Application
import android.os.Process
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
    /** A phase is single-use. Native identity, rather than adb-client lifetime, fences reuse. */
    fun claimProcess(phase: String): Pair<JSONObject, JSONObject> {
        val processes = value.getJSONObject("processes")
        check(!processes.has(phase))
        val priorKey = when (phase) { "CREATE_STAGE" -> null; "RECOVER_READ_ONLY" -> "CREATE_STAGE"; "CLEANUP" -> "RECOVER_READ_ONLY"; else -> error("Invalid phase") }
        val previous = priorKey?.let { processes.getJSONObject(it) }
        val priorProcesses = JSONObject(processes.toString())
        val current = currentProcess()
        previous?.let {
            check(current.getLong("startElapsedRealtime") > it.getLong("startElapsedRealtime"))
            check(current.getInt("pid") != it.getInt("pid") || current.getLong("startElapsedRealtime") != it.getLong("startElapsedRealtime"))
        }
        processes.put(phase, current)
        return current to priorProcesses
    }
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
            "calendar", "queries", "assertions", "cases", "processes")
        private data class CaseSpec(val mode: String, val allDay: Boolean, val alerts: List<Int>, val fault: Boolean)
        private val caseSpecs = linkedMapOf(
            "one-off-timed-alerts" to CaseSpec("ONE_OFF", false, listOf(10, 30), false),
            "one-off-all-day-zero" to CaseSpec("ONE_OFF", true, emptyList(), false),
            "recurring-timed-alerts" to CaseSpec("RECURRING", false, listOf(10, 30), false),
            "recurring-all-day-zero" to CaseSpec("RECURRING", true, emptyList(), false),
            "one-off-known-commit" to CaseSpec("ONE_OFF", false, listOf(10, 30), true),
            "recurring-known-commit" to CaseSpec("RECURRING", false, listOf(10, 30), true),
        )
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
                .put("queries", 0).put("assertions", 0).put("cases", JSONArray()).put("processes", JSONObject())).also { it.save() }
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
            val processes = v.getJSONObject("processes")
            check(processes.keys().asSequence().all { it in setOf("CREATE_STAGE", "RECOVER_READ_ONLY", "CLEANUP") })
            var previousStart = -1L
            var missingPrior = false
            for (phase in listOf("CREATE_STAGE", "RECOVER_READ_ONLY", "CLEANUP")) {
                if (!processes.has(phase)) { missingPrior = true; continue }
                check(!missingPrior)
                val process = processes.getJSONObject(phase); validateProcess(process)
                val elapsed = number(process, "startElapsedRealtime"); check(elapsed > previousStart); previousStart = elapsed
            }
            val labels = mutableSetOf<String>(); val ids = mutableSetOf<Long>(); val uids = mutableSetOf<String>()
            val childIds = mutableSetOf<Long>()
            for (index in 0 until v.getJSONArray("cases").length()) {
                val c = v.getJSONArray("cases").getJSONObject(index)
                check(c.keys().asSequence().toSet() == setOf("label", "operation", "id", "uid", "digest", "start", "end", "reminders", "alertMinutes", "mode", "allDay", "fault", "phase", "recoveries"))
                check(labels.add(c.get("label") as String) && c.getString("operation") == "native-probe:${v.getString("run")}:${c.getString("label")}")
                check(ids.add(number(c, "id")) && number(c, "id") > 0)
                val uid = c.get("uid") as String; uuid(uid.removeSuffix("@daylight.invalid")); check(uid.endsWith("@daylight.invalid") && uids.add(uid))
                check(Regex("[0-9a-f]{64}").matches(c.get("digest") as String))
                check(c.get("mode") in setOf("ONE_OFF", "RECURRING") && c.get("allDay") is Boolean && c.get("fault") is Boolean)
                val spec = requireNotNull(caseSpecs[c.getString("label")])
                check(c.getString("label") == caseSpecs.keys.elementAt(index))
                check(c.getString("mode") == spec.mode && c.getBoolean("allDay") == spec.allDay && c.getBoolean("fault") == spec.fault)
                check(c.get("phase") in setOf("COMMITTED", "VERIFIED") && number(c, "recoveries") in 0..2)
                check(number(c, "end") > number(c, "start"))
                val alerts = c.getJSONArray("alertMinutes"); check(alerts.length() == spec.alerts.size)
                for (i in 0 until alerts.length()) {
                    val alert = when (val n = alerts.get(i)) { is Int -> n.toLong(); is Long -> n; else -> error("Invalid alert minute") }
                    check(alert == spec.alerts[i].toLong())
                }
                val children = c.getJSONArray("reminders"); check(children.length() == spec.alerts.size)
                val seen = mutableSetOf<Long>()
                for (i in 0 until children.length()) {
                    val child = when (val n = children.get(i)) { is Int -> n.toLong(); is Long -> n; else -> error("Invalid child ID") }
                    check(child > 0 && seen.add(child) && childIds.add(child))
                }
                when (v.getString("phase")) {
                    "STAGED" -> check(c.getString("phase") == (if (spec.fault) "COMMITTED" else "VERIFIED") && number(c, "recoveries") == 0L)
                    "RECOVERED", "CLEANED" -> check(c.getString("phase") == "VERIFIED" && number(c, "recoveries") == 2L)
                }
            }
            if (v.getString("phase") != "CREATING") check(labels == caseSpecs.keys)
        }
        fun number(v: JSONObject, key: String): Long = when (val n = v.get(key)) { is Int -> n.toLong(); is Long -> n; else -> error("Invalid probe state number") }
        fun currentProcess(): JSONObject = JSONObject().put("pid", Process.myPid())
            .put("startElapsedRealtime", Process.getStartElapsedRealtime()).put("name", Application.getProcessName()).also(::validateProcess)
        private fun validateProcess(v: JSONObject) {
            check(v.keys().asSequence().toSet() == setOf("pid", "startElapsedRealtime", "name"))
            check(number(v, "pid") in 1..Int.MAX_VALUE.toLong())
            check(number(v, "startElapsedRealtime") in 1..SystemClock.elapsedRealtime())
            check(v.get("name") == "dev.swapnilraj.calendar")
        }
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
