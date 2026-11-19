package dev.swapnilraj.calendar.data.calendar

import android.Manifest
import android.content.*
import android.net.Uri
import android.os.CancellationSignal
import android.os.Parcel
import android.provider.CalendarContract
import dev.swapnilraj.calendar.core.*
import java.util.UUID
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

internal class OrdinaryCreateRejected(val result: MutationResult) : Exception()

/** New operations only. Legacy routing occurs after this handler has released the shared mutex. */
internal class CalendarOrdinaryCreateWriterV6(
    private val resolver: ContentResolver,
    private val reader: CalendarReader,
    private val store: CalendarLocalStore,
    private val permission: (String) -> Boolean,
    private val mutex: Mutex,
    private val io: CoroutineDispatcher,
    private val budgets: ProviderReadBudgets,
    private val freezeNew: (String, JSONObject, EventDraft, String) -> OrdinaryCreateReceipt,
    private val currentCalendar: (CalendarId) -> Map<String, String?>?,
    private val newUid: () -> String = { UUID.randomUUID().toString() + "@daylight.invalid" },
) {
    /** Null means an existing legacy journal owns the operation; the host handles it outside lock. */
    suspend fun createIfNew(id: String, draft: EventDraft): MutationResult? = withContext(io + readBudgetContext(budgets.mutation)) { mutex.withLock {
        var attempted: CalendarLocalStore.Operation? = null
        var known: Long? = null
        try {
            require(id.isNotBlank() && id.length <= budgets.mutation.maxCellUnits)
            store.abandoned(id) { CalendarWriter.createFingerprint(draft, budgets.maxCanonicalBytes) }?.let { return@withLock it }
            val existing = store.operation(id)
            if (existing != null && !OrdinaryCreateReceipt.handles(existing)) return@withLock null
            val command = CalendarWriter.frozenDraftJson(draft, budgets.maxCanonicalBytes)
            val signature = CalendarWriter.createFingerprint(draft, budgets.maxCanonicalBytes).second
            val initial = if (existing != null) {
                if (existing.kind != "CREATE" || existing.signature != signature) return@withLock MutationResult.Conflict(null)
                val parsed = proof(existing) ?: return@withLock MutationResult.Uncertain(id, null)
                if (existing.phase !in setOf("PREPARED", "FAILED")) return@withLock recover(existing, parsed)
                parsed.second
            } else {
                if (!permissions()) return@withLock MutationResult.PermissionRequired
                freezeNew(id, command, draft, newUid()).also { it.validate() }
            }
            if (!permissions()) return@withLock MutationResult.PermissionRequired
            preflight(initial)
            currentCoroutineContext().ensureActive()
            store.prepare(id, signature, "CREATE", initial.prepared().toString())
            val winner = store.operation(id) ?: return@withLock MutationResult.Uncertain(id, null)
            if (winner.kind != "CREATE" || winner.signature != signature) return@withLock MutationResult.Conflict(null)
            if (winner.phase == "ABANDONED") return@withLock MutationResult.Retired(id)
            if (!OrdinaryCreateReceipt.handles(winner)) return@withLock null
            val parsed = proof(winner) ?: return@withLock MutationResult.Uncertain(id, null)
            if (winner.phase !in setOf("PREPARED", "FAILED")) return@withLock recover(winner, parsed)
            // A competing candidate may have a different UID/plan. Only the durable winner wins.
            val receipt = parsed.second
            preflight(receipt)
            freshDispatchCalendar(receipt)?.let { return@withLock it }
            if (!permissions()) return@withLock MutationResult.PermissionRequired
            currentCoroutineContext().ensureActive()
            if (!store.attempt(winner)) return@withLock latest(id)
            attempted = winner.copy(phase = "ATTEMPTED")
            if (!permissions()) {
                check(store.mark(requireNotNull(attempted), "FAILED"))
                return@withLock MutationResult.PermissionRequired
            }
            currentCoroutineContext().ensureActive()
            val operations = batch(receipt)
            val results = resolver.applyBatch(CalendarContract.AUTHORITY, operations)
            known = CalendarCreateAcknowledgments.insertId(results.getOrNull(1)?.uri, "events")
            val minimal = receipt.prepared().put("stage", "committed").put("master", known).put("ackComplete", false).toString()
            val committed = try {
                val facts = CalendarCreateAcknowledgments.capture(results, operations.size)
                if (facts == null) minimal else JSONObject(minimal).put("ackFacts", facts).toString().let { raw ->
                    if (raw.toByteArray(Charsets.UTF_8).size <= budgets.maxCanonicalBytes) raw else minimal
                }
            } catch (_: Exception) { minimal }
            check(store.mark(requireNotNull(attempted), "COMMITTED", committed))
            latest(id, justAcknowledged = true)
        } catch (rejected: OrdinaryCreateRejected) { if (attempted == null) rejected.result else MutationResult.Uncertain(id, known) }
        catch (_: SecurityException) { if (attempted == null) MutationResult.PermissionRequired else MutationResult.Uncertain(id, known) }
        catch (_: OperationApplicationException) {
            if (attempted == null) MutationResult.Unavailable else try {
                check(store.mark(requireNotNull(attempted), "FAILED")); MutationResult.Unavailable
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { MutationResult.Uncertain(id, known) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (attempted == null) MutationResult.Unavailable else MutationResult.Uncertain(id, known) }
    } }

    suspend fun recoverIfHandled(id: String): MutationResult? = withContext(io + readBudgetContext(budgets.mutation)) { mutex.withLock {
        try {
            if (store.operationHeader(id)?.phase == "ABANDONED") return@withLock MutationResult.Retired(id)
            val operation = store.operation(id) ?: return@withLock null
            if (!OrdinaryCreateReceipt.handles(operation)) return@withLock null
            if (!permission(Manifest.permission.READ_CALENDAR)) return@withLock MutationResult.PermissionRequired
            val parsed = proof(operation) ?: return@withLock MutationResult.Uncertain(id, null)
            recover(operation, parsed)
        } catch (_: SecurityException) { MutationResult.PermissionRequired }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { MutationResult.Unavailable }
    } }

    private fun proof(operation: CalendarLocalStore.Operation) = try {
        OrdinaryCreateReceipt.parse(operation, budgets.maxCanonicalBytes)
    } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
    private suspend fun latest(id: String, justAcknowledged: Boolean = false): MutationResult {
        val operation = store.operation(id) ?: return MutationResult.Uncertain(id, null)
        if (operation.phase == "ABANDONED") return MutationResult.Retired(id)
        val parsed = proof(operation) ?: return MutationResult.Uncertain(id, null)
        return recover(operation, parsed, justAcknowledged)
    }
    private fun permissions() = permission(Manifest.permission.READ_CALENDAR) && permission(Manifest.permission.WRITE_CALENDAR)
    private fun ensureRead() { if (!permission(Manifest.permission.READ_CALENDAR)) throw SecurityException() }
    private fun freshDispatchCalendar(receipt: OrdinaryCreateReceipt): MutationResult? {
        ensureRead()
        val current = currentCalendar(receipt.identity.calendarId) ?: return MutationResult.Conflict(null)
        if (CreateSixJson.calendarIdentity(current) != receipt.identity) return MutationResult.Conflict(null)
        if (current["calendar_access_level"]?.toIntOrNull() !in WriteLevels) return MutationResult.Denied(MutationDenial.CALENDAR_READ_ONLY)
        return if (current != receipt.calendar) MutationResult.Conflict(null) else null
    }

    internal fun batch(receipt: OrdinaryCreateReceipt): ArrayList<ContentProviderOperation> {
        val ops = arrayListOf(ContentProviderOperation.newAssertQuery(ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, receipt.identity.calendarId.value))
            .withValues(values(receipt.calendar)).withExpectedCount(1).withYieldAllowed(false).build())
        ops += ContentProviderOperation.newInsert(CalendarContract.Events.CONTENT_URI).withValues(values(receipt.plan.event)).withYieldAllowed(false).build()
        receipt.plan.reminders.forEach { row -> ops += ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI)
            .withValueBackReference("event_id", 1).withValues(values(row)).withYieldAllowed(false).build() }
        return ops
    }
    private fun preflight(receipt: OrdinaryCreateReceipt) {
        receipt.validate()
        val n = receipt.plan.reminders.size
        // Whole successful public call: initial+dispatch calendar, namespace twice, binding
        // twice, full parent twice, one instance, two memberships and each known child.
        val parent = expectedParent(receipt, Long.MAX_VALUE)
        val cells = 4L * receipt.calendar.size + 2L * 3 + 2L * parent.size + InstanceFields.size + 8L * n
        if (n > budgets.maxSeriesChildren || 9L + 3L * n > budgets.mutation.maxRows || cells > budgets.mutation.maxCells ||
            maxOf(receipt.calendar.size, parent.size, InstanceFields.size) > budgets.mutation.maxColumns) throw ProviderReadLimitExceeded()
        if ((receipt.calendar.values + parent.values + receipt.plan.reminders.flatMap { it.values }).any { (it?.length ?: 0) > budgets.mutation.maxCellUnits }) throw ProviderReadLimitExceeded()
        val ops = batch(receipt)
        val parcel = Parcel.obtain()
        try { ops.forEach { it.writeToParcel(parcel, 0) }; if (parcel.dataSize() > 256 * 1024) throw ProviderReadLimitExceeded() }
        finally { parcel.recycle() }
        val master = Long.MAX_VALUE
        val results = Array(ops.size) { index -> when (index) {
            0 -> ContentProviderResult(1)
            1 -> ContentProviderResult(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, master))
            else -> ContentProviderResult(ContentUris.withAppendedId(CalendarContract.Reminders.CONTENT_URI, master - index))
        } }
        val facts = CalendarCreateAcknowledgments.capture(results, ops.size)
            ?: throw OrdinaryCreateRejected(MutationResult.ValidationFailed(setOf(InvalidEventField.ALERTS)))
        val ids = requireNotNull(CalendarCreateAcknowledgments.reminderIds(facts, n, master))
        val prepared = receipt.prepared()
        checkStoredJson(prepared.toString(), budgets.maxCanonicalBytes)
        val minimal = JSONObject(prepared.toString()).put("stage", "committed").put("master", master).put("ackComplete", false)
        checkStoredJson(minimal.toString(), budgets.maxCanonicalBytes)
        val complete = JSONObject(minimal.toString()).put("ackComplete", true).put("ackFacts", facts).put("reminderIds", JSONArray(ids))
            .put("firstRef", CreateSixJson.ref(receipt.plan.ref(receipt.identity.calendarId, master)))
        val completeRaw = complete.toString()
        checkStoredJson(completeRaw, budgets.maxCanonicalBytes)
        // Remaining COMMITTED mark/load/decode and VERIFIED mark each scan at most this
        // worst-case receipt. Include provider text and already consumed authoring/JSON work.
        val providerText = 4L * receipt.calendar.values.sumOf { it?.length?.toLong() ?: 0L } +
            2L * parent.values.sumOf { it?.length?.toLong() ?: 0L } +
            2L * (38L + receipt.plan.uid.length) + 114L * n +
            receipt.plan.reminders.sumOf { row -> row.values.sumOf { it?.length?.toLong() ?: 0L } } + 256
        if ((currentReadBudget()?.consumedTextUnits ?: 0L) + 4L * completeRaw.length + providerText > budgets.mutation.maxTextUnits) throw ProviderReadLimitExceeded()
    }

    private fun expectedParent(receipt: OrdinaryCreateReceipt, master: Long): Map<String, String?> {
        val absent = NonRecurring.filter { receipt.plan.mode == "ONE_OFF" || it != "rrule" }.associateWith { null }
        return receipt.plan.event + absent + mapOf("_id" to master.toString(), "deleted" to "0")
    }

    private suspend fun recover(operation: CalendarLocalStore.Operation, parsed: Pair<JSONObject, OrdinaryCreateReceipt>, justAcknowledged: Boolean = false): MutationResult {
        if (!permission(Manifest.permission.READ_CALENDAR)) return MutationResult.PermissionRequired
        if (operation.phase in setOf("PREPARED", "FAILED")) return MutationResult.SafeToRetry(operation.id)
        if (operation.phase == "ATTEMPTED") return MutationResult.Uncertain(operation.id, null)
        val (proof, receipt) = parsed
        val master = CreateSixJson.number(proof, "master")
        var bound = false
        fun uncertain() = MutationResult.Uncertain(operation.id, master.takeIf { bound })
        return try {
            fun namespace(): Boolean {
                ensureRead()
                val current = currentCalendar(receipt.identity.calendarId) ?: return false
                return CreateSixJson.calendarIdentity(current) == receipt.identity && (current["calendar_access_level"]?.toIntOrNull() ?: -1) >= CalendarContract.Calendars.CAL_ACCESS_READ
            }
            if (!namespace()) return uncertain()
            val guard = "_id = ? AND calendar_id = ? AND uid2445 = ?"
            val args = arrayOf(master.toString(), receipt.identity.calendarId.value.toString(), receipt.plan.uid)
            suspend fun binding(): Boolean {
                val row = rows(CalendarContract.Events.CONTENT_URI, arrayOf("_id", "calendar_id", "uid2445"), guard, args, 1).singleOrNull() ?: return false
                return row["_id"] == master.toString() && row["calendar_id"] == receipt.identity.calendarId.value.toString() && row["uid2445"] == receipt.plan.uid
            }
            if (!binding()) return uncertain()
            bound = true
            val acknowledged = CalendarCreateAcknowledgments.reminderIds(proof.optJSONObject("ackFacts"), receipt.plan.reminders.size, master) ?: return uncertain()
            val expected = expectedParent(receipt, master)
            suspend fun parent(): Boolean = rows(CalendarContract.Events.CONTENT_URI, expected.keys.toTypedArray(), guard, args, 1).singleOrNull() == expected
            if (!parent()) return uncertain()
            suspend fun membership(): Boolean {
                val actual = rows(CalendarContract.Reminders.CONTENT_URI, arrayOf("_id", "event_id"), "event_id = ?", arrayOf(master.toString()), budgets.maxSeriesChildren)
                val ids = actual.map { it["_id"]?.toLongOrNull() ?: return false }
                return actual.all { it["event_id"] == master.toString() } && ids.size == acknowledged.size && ids.distinct().size == ids.size && ids.toSet() == acknowledged.toSet()
            }
            if (!membership()) return uncertain()
            for ((index, child) in acknowledged.withIndex()) {
                val row = rows(ContentUris.withAppendedId(CalendarContract.Reminders.CONTENT_URI, child), arrayOf("_id", "event_id", "minutes", "method"),
                    "event_id = ? AND calendar_id = ? AND uid2445 = ?", args, 1).singleOrNull() ?: return uncertain()
                if (row != receipt.plan.reminders[index] + mapOf("_id" to child.toString(), "event_id" to master.toString())) return uncertain()
            }
            val ref = receipt.plan.ref(receipt.identity.calendarId, master)
            val low = Math.subtractExact(ref.instanceStart.toEpochMilli(), 1)
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
                ContentUris.appendId(this, low); ContentUris.appendId(this, Math.addExact(ref.instanceStart.toEpochMilli(), 1))
            }.build()
            val instance = rows(uri, InstanceFields, "event_id = ? AND calendar_id = ? AND begin = ?", arrayOf(master.toString(), ref.calendarId.value.toString(), ref.instanceStart.toEpochMilli().toString()), 1).singleOrNull() ?: return uncertain()
            if (instance["event_id"] != master.toString() || instance["calendar_id"] != ref.calendarId.value.toString() || instance["begin"] != ref.instanceStart.toEpochMilli().toString() ||
                instance["end"] != ref.instanceEnd.toEpochMilli().toString() || instance["allDay"] != receipt.plan.event["allDay"] ||
                listOf("original_id", "original_sync_id", "originalInstanceTime").any { instance[it] != null }) return uncertain()
            if (!binding()) { bound = false; return uncertain() }
            if (!membership() || !parent()) return uncertain()
            if (!namespace()) { bound = false; return uncertain() }
            ensureRead()
            val verified = JSONObject(proof.toString()).put("ackComplete", true).put("reminderIds", JSONArray(acknowledged)).put("firstRef", CreateSixJson.ref(ref))
            check(store.mark(operation, "VERIFIED", verified.toString())); reader.refresh()
            MutationResult.LocalSaved(ref)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: SecurityException) { MutationResult.PermissionRequired }
        // A just-returned canonical native ACK is still known on an unavailable read.
        // Observed namespace/UID mismatches return above with bound=false instead.
        catch (_: Exception) { if (justAcknowledged) MutationResult.Uncertain(operation.id, master) else uncertain() }
    }

    private suspend fun rows(uri: Uri, fields: Array<String>, selection: String?, args: Array<String>?, maxRows: Int): List<Map<String, String?>> {
        ensureRead()
        val cursor = suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal(); continuation.invokeOnCancellation { signal.cancel() }
            try {
                ensureRead()
                val cursor = (resolver.query(uri, fields, selection, args, null, signal) ?: error("Provider unavailable")).bounded(maxRows)
                try { ensureRead() } catch (failure: Exception) { cursor.close(); throw failure }
                continuation.resume(cursor, onCancellation = { _, resource, _ -> resource.close() })
            } catch (failure: Exception) { continuation.resumeWithException(failure) }
        }
        return cursor.use { buildList { while (it.moveToNext()) {
            currentCoroutineContext().ensureActive(); ensureRead()
            add(fields.associateWith { key -> it.getColumnIndexOrThrow(key).let { column -> if (it.isNull(column)) null else it.getString(column) } })
        }; ensureRead() } }
    }
    private fun values(row: Map<String, String?>) = ContentValues().apply { row.forEach { (key, value) -> if (value == null) putNull(key) else put(key, value) } }
    private companion object {
        val WriteLevels = setOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR, CalendarContract.Calendars.CAL_ACCESS_EDITOR, CalendarContract.Calendars.CAL_ACCESS_OWNER, CalendarContract.Calendars.CAL_ACCESS_ROOT)
        val NonRecurring = listOf("rrule", "rdate", "exrule", "exdate", "original_id", "original_sync_id", "originalInstanceTime", "originalAllDay")
        val InstanceFields = arrayOf("event_id", "calendar_id", "begin", "end", "allDay", "original_id", "original_sync_id", "originalInstanceTime")
    }
}
