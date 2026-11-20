package dev.swapnilraj.calendar.data.calendar

import android.Manifest
import android.content.*
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import dev.swapnilraj.calendar.core.*
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/** Real journal byte cap, with a cancelled high-budget calibration and no native device calls. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35])
class OrdinaryEditReceiptBudgetTest(private val adoptedWinner: Boolean, private val kind: String, private val capCase: String) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "adoptedWinner={0},kind={1},cap={2}")
        fun scenarios(): List<Array<Any>> = listOf(false, true).flatMap { adopted ->
            listOf("UPDATE", "DELETE").flatMap { kind ->
                listOf("BELOW_MINIMAL", "BETWEEN_MINIMAL_AND_FULL", "EXACT_FULL").map { cap -> arrayOf<Any>(adopted, kind, cap) }
            }
        }
    }
    private class Provider : ContentProvider() {
        val calendar = mutableMapOf<String, String?>("_id" to "2", "account_name" to "owner@example.invalid", "account_type" to "com.google",
            "ownerAccount" to "owner@example.invalid", "calendar_displayName" to "Synthetic", "calendar_color" to "12345",
            "calendar_timezone" to "Europe/London", "calendar_access_level" to "700", "allowedReminders" to "0,1,2", "maxReminders" to "5",
            "visible" to "1", "sync_events" to "1", "allowedAvailability" to "0,1,2", "_sync_id" to "calendar-A")
        val events = mutableListOf<MutableMap<String, String?>>()
        val reminders = mutableListOf<MutableMap<String, String?>>()
        val attendees = mutableListOf<MutableMap<String, String?>>()
        var nextId = 10L
        var batchCount = 0
        var failReminder = false
        var lostAcknowledgement = false
        var malformedReminderAcknowledgement = false
        var failReadsAfterCommit = false
        var committed = false
        var beforeBatch: (() -> Unit)? = null
        var onQuery: (() -> Unit)? = null
        val queryCounts = mutableMapOf<String, Int>()
        fun resetQueryCounts() = queryCounts.clear()
        override fun onCreate() = true
        override fun getType(uri: Uri): String? = null
        private fun table(uri: Uri): MutableList<MutableMap<String, String?>> = when (uri.pathSegments.first()) {
            "events" -> events
            "reminders" -> reminders
            "attendees" -> attendees
            else -> error("Unexpected write table")
        }
        private fun selected(row: Map<String, String?>, uri: Uri, selection: String?, args: Array<out String>?): Boolean {
            uri.pathSegments.lastOrNull()?.toLongOrNull()?.let { if (row["_id"] != it.toString()) return false }
            if (selection == null) return true
            val columns = Regex("([A-Za-z_][A-Za-z_0-9]*)\\s*=\\s*\\?").findAll(selection).map { it.groupValues[1] }.toList()
            val parent = row["event_id"]?.let { id -> events.singleOrNull { it["_id"] == id } }
            fun value(key: String) = if (row.containsKey(key)) row[key] else parent?.get(key)
            if (Regex("([A-Za-z_][A-Za-z_0-9]*)\\s+IS NULL").findAll(selection).any { value(it.groupValues[1]) != null }) return false
            return columns.withIndex().all { (index, key) -> value(key) == args?.get(index) }
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
            onQuery?.invoke()
            if (failReadsAfterCommit && committed) error("Synthetic post-commit provider unavailable")
            val tableName = uri.pathSegments.first()
            queryCounts[tableName] = (queryCounts[tableName] ?: 0) + 1
            val rows: List<Map<String, String?>> = when (uri.pathSegments.first()) {
                "calendars" -> listOf(calendar).filter { selected(it, uri, selection, args) }
                "instances" -> events.filter { it["deleted"] != "1" }.map { event -> event + mapOf(
                    "event_id" to event["_id"], "begin" to event["dtstart"], "end" to event["dtend"],
                    "displayColor" to "12345", "selfAttendeeStatus" to "0") }.filter { row ->
                        val columns = Regex("([A-Za-z_][A-Za-z_0-9]*)\\s*=\\s*\\?").findAll(selection.orEmpty()).map { it.groupValues[1] }.toList()
                        columns.withIndex().all { (index, key) -> row[key] == args?.get(index) }
                    }
                else -> table(uri).filter { selected(it, uri, selection, args) }
            }
            val fields = projection ?: arrayOf("_id")
            return MatrixCursor(fields).apply { rows.forEach { row -> addRow(fields.map { row[it] }.toTypedArray()) } }
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            if (uri.pathSegments.first() == "reminders" && failReminder) throw OperationApplicationException("Synthetic rollback")
            val id = nextId++
            val row = mutableMapOf<String, String?>("_id" to id.toString())
            values!!.keySet().forEach { row[it] = values.get(it)?.toString() }
            if (uri.pathSegments.first() == "events") {
                row.putIfAbsent("organizer", "owner@example.invalid"); row["isOrganizer"] = "1"
                row["hasAttendeeData"] = "0"; row["guestsCanSeeGuests"] = "1"; row["deleted"] = "0"; row["eventStatus"] = "1"
            }
            table(uri).add(row)
            return ContentUris.withAppendedId(uri, id)
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int {
            val rows = table(uri).filter { selected(it, uri, selection, args) }
            rows.forEach { row -> values!!.keySet().forEach { row[it] = values.get(it)?.toString() } }
            return rows.size
        }
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int {
            val rows = table(uri).filter { selected(it, uri, selection, args) }.toSet()
            if (uri.pathSegments.first() == "events") rows.forEach { it["deleted"] = "1" }
            else table(uri).removeAll(rows)
            return rows.size
        }
        override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
            batchCount++
            beforeBatch?.also { beforeBatch = null }?.invoke()
            val oldEvents = events.map { it.toMutableMap() }
            val oldReminders = reminders.map { it.toMutableMap() }
            val oldAttendees = attendees.map { it.toMutableMap() }
            val oldId = nextId
            val results = try { super.applyBatch(operations) } catch (failure: Exception) {
                events.clear(); events.addAll(oldEvents); reminders.clear(); reminders.addAll(oldReminders)
                attendees.clear(); attendees.addAll(oldAttendees); nextId = oldId
                throw failure
            }
            committed = true
            if (lostAcknowledgement) throw IllegalStateException("Synthetic lost acknowledgement after commit")
            return if (malformedReminderAcknowledgement) results.map { result ->
                if (result.uri?.pathSegments?.firstOrNull() == "reminders") ContentProviderResult(Uri.parse("content://foreign/reminders/999")) else result
            }.toTypedArray() else results
        }
    }

    private val draft = EventDraft(CalendarId(2),
        EventTime.Timed(Instant.parse("2026-11-17T09:00:00Z"), Instant.parse("2026-11-17T10:00:00Z")),
        ZoneId.of("Europe/London"), "Synthetic budget fixture", description = "\u0001".repeat(8192), alertMinutes = listOf(10),
        targetIdentity = CalendarIdentity(CalendarId(2), CalendarAccount("owner@example.invalid", "com.google"), "owner@example.invalid", "calendar-A"))

    @Test fun unrecordableCommittedProofNeverCrossesProviderBoundary() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val provider = Provider()
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        val name = "ordinary-edit-budget-${UUID.randomUUID()}.db"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val high = CalendarLocalStore(context, name)
        var tight: CalendarLocalStore? = null
        val permissions = setOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        val reader = CalendarReader(context.contentResolver, scope, stopTimeoutMillis = 0)
        val highWriter = CalendarWriter(context.contentResolver, reader, high, permissions::contains)
        try {
            assertEquals(8192, draft.description.length)
            assertTrue("Synthetic source cell stays below the real cell limit", draft.description.length < ProviderReadBudgets().mutation.maxCellUnits)
            val ref = (highWriter.create("seed", draft) as MutationResult.LocalSaved).ref
            val token = (highWriter.loadForEdit(ref) as EditReadResult.Ready).event.token
            val patch = EventPatch(title = FieldChange.Set("Budget constrained"), alertMinutes = FieldChange.Set(listOf(15, 30, 45)))

            // Capture an actual prepared receipt using the existing cancellation boundary. This
            // avoids mirroring source/command/identity serialization to calculate the byte cap.
            val calibration = async(start = CoroutineStart.LAZY) {
                if (kind == "DELETE") highWriter.delete("calibration", ref, token) else highWriter.update("calibration", ref, token, patch)
            }
            provider.onQuery = { if (high.operationHeader("calibration")?.phase == "PREPARED") calibration.cancel() }
            calibration.start()
            try { calibration.await(); fail("Calibration must stop after durable preparation") }
            catch (_: CancellationException) { }
            finally { provider.onQuery = null }
            val template = requireNotNull(high.operation("calibration"))
            assertEquals("PREPARED", template.phase)
            assertEquals(1, provider.batchCount) // Only the seed create; calibration never dispatched.
            val prepared = requireNotNull(template.resultRef)
            val preparedBytes = prepared.toByteArray(Charsets.UTF_8).size
            val (proof, receipt) = OrdinaryEditReceipt.parse(template)
            // Escaped imported text keeps this cap above the existing conservative six-byte
            // source bound; otherwise that earlier guard can hide the committed-proof boundary.
            val sourceBytes = checkCanonical(mapOf("event" to receipt.event, "calendar" to receipt.calendar,
                "alerts" to receipt.reminders, "attendees" to receipt.attendees), Int.MAX_VALUE)
            val minimal = JSONObject(proof.toString()).put("stage", "committed")
                .put("ref", OrdinaryEditReceipt.refJson(receipt.result)).put("ackComplete", false)
            val worst = JSONObject(minimal.toString()).put("ackComplete", true)
                .put("reminderIds", JSONArray(List(receipt.alerts?.size ?: 0) { Long.MAX_VALUE - it }))
            val minimalBytes = minimal.toString().toByteArray(Charsets.UTF_8).size
            val worstBytes = worst.toString().toByteArray(Charsets.UTF_8).size
            assertEquals(kind, receipt.kind)
            assertEquals(if (kind == "DELETE") 0 else 3, receipt.alerts?.size ?: 0)
            assertTrue("Calibration must distinguish PREPARED from a minimal commit", minimalBytes > preparedBytes)
            assertTrue("Complete ACK must reserve child IDs or the empty ID array", worstBytes > minimalBytes + 1)
            val limit = when (capCase) {
                "BELOW_MINIMAL" -> preparedBytes
                "BETWEEN_MINIMAL_AND_FULL" -> minimalBytes + (worstBytes - minimalBytes) / 2
                "EXACT_FULL" -> worstBytes
                else -> error("Unknown cap case")
            }
            assertTrue("Source guard must accept this calibration: source=$sourceBytes cap=$limit", sourceBytes <= limit)
            checkStoredJson(prepared, limit)

            val budgets = ProviderReadBudgets(maxCanonicalBytes = limit)
            val boundedStore = CalendarLocalStore(context, name, readBudgets = budgets).also { tight = it }
            if (adoptedWinner) {
                boundedStore.prepare("candidate", template.signature, template.kind, prepared)
                assertEquals(prepared, boundedStore.operation("candidate")!!.resultRef)
            } else assertNull(boundedStore.operationHeader("candidate"))
            val writer = CalendarWriter(context.contentResolver, reader, boundedStore, permissions::contains, readBudgets = budgets)
            val beforeEvents = provider.events.map { it.toMap() }
            val beforeReminders = provider.reminders.map { it.toMap() }
            provider.batchCount = 0
            var phaseAtBatch: String? = null
            provider.beforeBatch = { phaseAtBatch = boundedStore.operationHeader("candidate")?.phase }
            val result = if (kind == "DELETE") writer.delete("candidate", ref, token) else writer.update("candidate", ref, token, patch)
            val phase = boundedStore.operationHeader("candidate")?.phase
            val evidence = "adopted=$adoptedWinner kind=$kind case=$capCase cap=$limit minimal=$minimalBytes worst=$worstBytes result=${result.javaClass.simpleName} phase=$phase phaseAtBatch=$phaseAtBatch"
            println("Receipt4 budget boundary: $evidence source=$sourceBytes batches=${provider.batchCount}")
            if (capCase != "EXACT_FULL") {
                assertEquals("Predictably unrecordable commit dispatched: $evidence", 0, provider.batchCount)
                assertEquals(MutationResult.Unavailable, result)
                assertTrue("Unattempted receipt must remain absent or PREPARED", phase == null || phase == "PREPARED")
                assertTrue("Provider event rows changed", beforeEvents == provider.events.map { it.toMap() })
                assertTrue("Provider reminder rows changed", beforeReminders == provider.reminders.map { it.toMap() })
                assertNull(phaseAtBatch)
            } else {
                assertEquals("Exact worst-case reservation must allow one dispatch: $evidence", 1, provider.batchCount)
                assertEquals(if (kind == "DELETE") MutationResult.LocalDeleted(ref) else MutationResult.LocalSaved(ref), result)
                assertEquals("ATTEMPTED", phaseAtBatch); assertEquals("VERIFIED", phase)
                val committed = JSONObject(requireNotNull(boundedStore.operation("candidate")!!.resultRef))
                assertTrue(committed.getBoolean("ackComplete"))
                assertEquals(if (kind == "DELETE") 0 else 3, committed.getJSONArray("reminderIds").length())
                if (kind == "DELETE") assertEquals("1", provider.events.single()["deleted"])
                else {
                    assertEquals("Budget constrained", provider.events.single()["title"])
                    assertEquals(listOf("15", "30", "45"), provider.reminders.map { it["minutes"] })
                }
            }
        } finally {
            provider.onQuery = null; provider.beforeBatch = null
            scope.cancel(); scope.coroutineContext[Job]!!.join(); tight?.close(); high.close(); context.deleteDatabase(name)
        }
    }
}
