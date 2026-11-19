package dev.swapnilraj.calendar.probe

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.CursorWrapper
import android.net.Uri
import android.os.CancellationSignal
import android.provider.CalendarContract
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.calendar.*
import java.time.*
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** Actual application adapters, with test-only forwarding/audit and a labelled post-ACK read fault.
 * No invented acknowledgements, local journal edits, provider implementation or UID discovery. */
internal class NativeCalendarWriterProbeCases(
    private val context: Context, private val state: NativeWriterProbeState,
    private val calendar: Long, private val identity: CalendarIdentity,
    private val beforeQuery: () -> Unit, private val beforeBatch: (Int) -> Unit,
    private val guard: () -> Unit, private val persist: () -> Unit,
    private val watchdog: ScheduledExecutorService, private val progress: (String) -> Unit,
) {
    private val native = context.contentResolver
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = CalendarLocalStore(context, state.database)
    private var active: Recipe? = null
    private var activeRecord: JSONObject? = null
    private var blockPostAck = false
    private var acknowledged = false
    private var bound = false
    private var nativeBatches = 0
    private var guardedChildQueries = 0
    private var membershipQueries = 0
    private var minimalBindings = 0
    private var readOnly = false
    private var forbiddenWrites = 0
    private val forwarding = object : ContentProvider() {
        override fun onCreate() = true
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = forbidden()
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = forbidden()
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = forbidden()
        override fun query(uri: Uri, fields: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? =
            query(uri, fields, selection, args, sort, null)
        override fun query(uri: Uri, fields: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?, cancellation: CancellationSignal?): Cursor? {
            beforeQuery()
            check(!blockPostAck || !acknowledged) { "INJECTED_POST_ACK_QUERY_FAILURE" }
            val projection = requireNotNull(fields).toList(); check(projection.isNotEmpty() && projection.size <= 128 && sort == null)
            validateQuery(uri, projection, selection, args?.toList())
            val signal = cancellation ?: CancellationSignal()
            val deadline = watchdog.schedule({ signal.cancel() }, 30, TimeUnit.SECONDS)
            var opened: Cursor? = null
            try {
                val cursor = requireNotNull(native.query(uri, fields, selection, args, sort, signal)).also { opened = it }
                check(cursor.columnCount <= 128 && cursor.count <= 32)
                if (uri.pathSegments == listOf("events") && projection.toSet() == setOf("_id", "calendar_id", "uid2445")) {
                    minimalBindings++
                    val c = requireNotNull(activeRecord)
                    bound = cursor.moveToFirst() && cursor.getLong(cursor.getColumnIndexOrThrow("_id")) == c.getLong("id") &&
                        cursor.getLong(cursor.getColumnIndexOrThrow("calendar_id")) == calendar && cursor.getString(cursor.getColumnIndexOrThrow("uid2445")) == c.getString("uid")
                    cursor.moveToPosition(-1)
                }
                return object : CursorWrapper(cursor) { override fun close() { try { super.close() } finally { deadline.cancel(false) } } }
            } catch (failure: Throwable) { try { opened?.close() } finally { deadline.cancel(false) }; throw failure }
        }
        override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
            if (readOnly) return forbidden()
            check(!acknowledged && nativeBatches < 6)
            guard()
            val recipe = requireNotNull(active)
            val operation = requireNotNull(store.operation(recipe.operation))
            check(operation.phase == "ATTEMPTED")
            val receipt = OrdinaryCreateReceipt.parse(operation, 1024 * 1024).second
            check(receipt.identity == identity && receipt.plan.mode == recipe.mode && receipt.plan.allDay == recipe.allDay)
            check(operations.size == 2 + recipe.alerts.size && operations[0].isAssertQuery && operations[0].uri == ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, calendar))
            check(operations.all { !it.isYieldAllowed && !it.isExceptionAllowed && !it.isCall })
            val dummy = arrayOf(ContentProviderResult(1), ContentProviderResult(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, Long.MAX_VALUE)))
            check(values(requireNotNull(operations[0].resolveValueBackReferences(dummy, 0))) == receipt.calendar)
            check(operations[1].isInsert && operations[1].uri == CalendarContract.Events.CONTENT_URI)
            check(values(requireNotNull(operations[1].resolveValueBackReferences(dummy, 0))) == receipt.plan.event)
            operations.drop(2).forEachIndexed { index, op ->
                check(op.isInsert && op.uri == CalendarContract.Reminders.CONTENT_URI)
                check(values(requireNotNull(op.resolveValueBackReferences(dummy, 2))) == receipt.plan.reminders[index] + ("event_id" to Long.MAX_VALUE.toString()))
            }
            beforeBatch(operations.count { it.isAssertQuery })
            nativeBatches++
            val result = native.applyBatch(CalendarContract.AUTHORITY, operations) // One original native transaction.
            acknowledged = true
            val id = canonicalId(result.getOrNull(1)?.uri, "events")
            check(result.size == operations.size && result[0].count == 1)
            val children = result.drop(2).map { canonicalId(it.uri, "reminders") }; check(children.distinct().size == children.size)
            val row = JSONObject().put("label", recipe.label).put("operation", recipe.operation).put("id", id)
                .put("uid", receipt.plan.uid).put("digest", receipt.prepared().getString("planDigest")).put("start", receipt.plan.firstStart.toEpochMilli())
                .put("end", receipt.plan.firstEnd.toEpochMilli()).put("reminders", JSONArray(children)).put("mode", recipe.mode)
                .put("allDay", recipe.allDay).put("fault", recipe.fault).put("phase", "COMMITTED").put("recoveries", 0)
            state.cases.put(row); activeRecord = row; persist()
            return result // All result facts/URIs/counts are unmodified.
        }
        private fun forbidden(): Nothing { forbiddenWrites++; error("RECOVERY_OR_NONBATCH_MUTATION_FORBIDDEN") }
    }.apply { attachInfo(context, ProviderInfo().apply { authority = CalendarContract.AUTHORITY }) }
    private val resolver = ContentResolver.wrap(forwarding)
    private val reader = CalendarReader(resolver, scope, stopTimeoutMillis = 0)
    private val writer = CalendarWriter(resolver, reader, store, { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED })

    private inner class Recipe(val label: String, val recurring: Boolean, val allDay: Boolean, val alerts: List<Int>, val fault: Boolean) {
        val operation get() = "native-probe:${state.run}:$label"
        val mode get() = if (recurring) "RECURRING" else "ONE_OFF"
        fun draft(): EventDraft {
            val start = Instant.parse(if (allDay) "2028-03-10T00:00:00Z" else "2028-03-10T14:00:00Z")
            val time = if (allDay) EventTime.AllDay(CalendarDateRange(LocalDate.of(2028, 3, 10), LocalDate.of(2028, 3, 12)))
                else EventTime.Timed(start, start.plusSeconds(5400))
            return EventDraft(CalendarId(calendar), time, ZoneId.of(if (allDay) "UTC" else "America/New_York"),
                "Daylight synthetic app receipt fixture", location = "Synthetic room", description = "Synthetic receipt verification",
                alertMinutes = alerts, targetIdentity = identity,
                recurrence = if (recurring) RecurrenceSpec(RecurrencePattern.Daily, end = RecurrenceEnd.Count(3)) else null)
        }
    }
    private fun recipes() = listOf(
        Recipe("one-off-timed-alerts", false, false, listOf(10, 30), false), Recipe("one-off-all-day-zero", false, true, emptyList(), false),
        Recipe("recurring-timed-alerts", true, false, listOf(10, 30), false), Recipe("recurring-all-day-zero", true, true, emptyList(), false),
        Recipe("one-off-known-commit", false, false, listOf(10, 30), true), Recipe("recurring-known-commit", true, false, listOf(10, 30), true),
    )
    fun create() = runBlocking {
        check(state.cases.length() == 0 && !readOnly)
        for (recipe in recipes()) {
            progress("app-create:${recipe.label}"); guard()
            active = recipe; activeRecord = null; acknowledged = false; bound = false; blockPostAck = recipe.fault
            val result = writer.create(recipe.operation, recipe.draft()) // Exactly one call, including uncertain outcomes.
            val op = requireNotNull(store.operation(recipe.operation)); val (proof, receipt) = OrdinaryCreateReceipt.parse(op, 1024 * 1024)
            val row = requireNotNull(activeRecord)
            check(receipt.plan.uid == row.getString("uid") && receipt.prepared().getString("planDigest") == row.getString("digest"))
            if (recipe.fault) check(result == MutationResult.Uncertain(recipe.operation, row.getLong("id")) && op.phase == "COMMITTED" && proof.has("ackFacts") && !proof.getBoolean("ackComplete"))
            else check(result == MutationResult.LocalSaved(receipt.plan.ref(identity.calendarId, row.getLong("id"))) && op.phase == "VERIFIED")
            check(CalendarCreateAcknowledgments.reminderIds(proof.optJSONObject("ackFacts"), recipe.alerts.size, row.getLong("id")) ==
                (0 until row.getJSONArray("reminders").length()).map { row.getJSONArray("reminders").getLong(it) })
            row.put("phase", op.phase); persist()
        }
        check(nativeBatches == 6 && state.cases.length() == 6 && forbiddenWrites == 0)
    }
    fun recover(): JSONObject = runBlocking {
        readOnly = true
        check(context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_DENIED)
        check(state.cases.length() == 6)
        writer.ordinaryCreateAuthoringProbe = { error("CURRENT_AUTHORING_FORBIDDEN_DURING_RECOVERY") }
        // No edit snapshots or command/journal rewriting; a freshly opened store is the authority.
        for (recipe in recipes()) {
            progress("app-recover:${recipe.label}"); guard()
            val row = state.cases.getJSONObject(recipes().indexOfFirst { it.label == recipe.label })
            check(row.getString("label") == recipe.label && row.getString("operation") == recipe.operation && row.getInt("recoveries") == 0)
            active = recipe; activeRecord = row; acknowledged = true; blockPostAck = false
            repeat(2) { index ->
                bound = false
                val result = writer.recover(recipe.operation)
                val op = requireNotNull(store.operation(recipe.operation)); val (_, receipt) = OrdinaryCreateReceipt.parse(op, 1024 * 1024)
                check(result == MutationResult.LocalSaved(receipt.plan.ref(identity.calendarId, row.getLong("id"))) && op.phase == "VERIFIED")
                check(receipt.plan.uid == row.getString("uid") && receipt.prepared().getString("planDigest") == row.getString("digest"))
                check(receipt.plan.firstStart.toEpochMilli() == row.getLong("start") && receipt.plan.firstEnd.toEpochMilli() == row.getLong("end"))
                row.put("phase", "VERIFIED").put("recoveries", index + 1); persist()
            }
        }
        check(nativeBatches == 0 && forbiddenWrites == 0 && minimalBindings == 24 && membershipQueries == 24 && guardedChildQueries == 16)
        JSONObject().put("readGranted", true).put("writeDenied", true).put("authoringTrapInstalled", true)
            .put("nativeWriteCalls", nativeBatches).put("forbiddenWriteAttempts", forbiddenWrites)
            .put("minimalBindingQueries", minimalBindings).put("membershipQueries", membershipQueries).put("guardedChildQueries", guardedChildQueries)
    }
    fun close() { scope.cancel(); store.close() }
    private fun validateQuery(uri: Uri, fields: List<String>, selection: String?, args: List<String>?) {
        check(uri.scheme == "content" && uri.authority == CalendarContract.AUTHORITY && uri.encodedQuery == null && uri.fragment == null)
        val path = uri.pathSegments
        if (path == listOf("calendars", calendar.toString())) return
        val row = requireNotNull(activeRecord); val id = row.getLong("id"); val uid = row.getString("uid")
        when (path.firstOrNull()) {
            "events" -> {
                check(path.size == 1 && selection == "_id = ? AND calendar_id = ? AND uid2445 = ?" && args == listOf(id.toString(), calendar.toString(), uid))
                if (fields.toSet() != setOf("_id", "calendar_id", "uid2445")) check(bound)
            }
            "reminders" -> {
                check(bound)
                if (path.size == 1) {
                    check(fields.toSet() == setOf("_id", "event_id") && selection == "event_id = ?" && args == listOf(id.toString())); membershipQueries++
                } else {
                    check(path.size == 2 && path[1].toLong() in (0 until row.getJSONArray("reminders").length()).map { row.getJSONArray("reminders").getLong(it) })
                    check(fields.toSet() == setOf("_id", "event_id", "minutes", "method") && selection == "event_id = ? AND calendar_id = ? AND uid2445 = ?" && args == listOf(id.toString(), calendar.toString(), uid)); guardedChildQueries++
                }
            }
            "instances" -> {
                check(bound && path == listOf("instances", "when", (row.getLong("start") - 1).toString(), (row.getLong("start") + 1).toString()))
                check(selection == "event_id = ? AND calendar_id = ? AND begin = ?" && args == listOf(id.toString(), calendar.toString(), row.getLong("start").toString()))
            }
            else -> error("UNOWNED_QUERY")
        }
    }
    companion object {
        private fun values(values: ContentValues): Map<String, String?> = values.keySet().associateWith { values.getAsString(it) }
        private fun canonicalId(uri: Uri?, table: String): Long {
            requireNotNull(uri); val id = requireNotNull(uri.lastPathSegment?.toLongOrNull()); check(id > 0)
            check(uri == ContentUris.withAppendedId(Uri.parse("content://${CalendarContract.AUTHORITY}/$table"), id)); return id
        }
        fun reportCases(state: NativeWriterProbeState): JSONArray = JSONArray((0 until state.cases.length()).map { index ->
            val c = state.cases.getJSONObject(index)
            JSONObject().put("label", c.getString("label")).put("mode", c.getString("mode")).put("allDay", c.getBoolean("allDay"))
                .put("alertCount", c.getJSONArray("reminders").length()).put("fault", if (c.getBoolean("fault")) "INJECTED_POST_ACK_QUERY_FAILURE" else "NONE")
                .put("phase", c.getString("phase")).put("recoveries", c.getInt("recoveries"))
                .put("uidStable", c.getString("phase") == "VERIFIED").put("planStable", true)
        })
    }
}
