package dev.swapnilraj.calendar.data.calendar

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Parcel
import android.provider.CalendarContract
import dev.swapnilraj.calendar.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Independent, unwired native adapter. Default verifier supplies no installed-provider authority. */
internal class CalendarSeriesGraphDurationAdapter(
    private val context: Context,
    private val sourceReader: CalendarSeriesReader,
    private val store: CalendarLocalStore,
    private val hasPermission: (String) -> Boolean,
    private val verifier: SeriesGraphDurationNativeVerifier = SeriesGraphDurationNativeVerifier.Unverified,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val planning: RecurrencePlanningBudget = RecurrencePlanningBudget(),
    private val budgets: ProviderReadBudgets = ProviderReadBudgets(),
) : CalendarSeriesGraphDuration {
    private val mutex = Mutex()
    private val resolver get() = context.contentResolver
    private fun common(value: MutationResult) = SeriesMutationResult.Common(value)
    private fun uncertain(id: String, master: Long? = null) = common(MutationResult.Uncertain(id, master))
    private fun permissions() = hasPermission(Manifest.permission.READ_CALENDAR) && hasPermission(Manifest.permission.WRITE_CALENDAR)
    private suspend fun binding(): SeriesGraphDurationNativeBinding {
        val b = verifier.current() ?: throw SeriesSourceReview("The native duration effect contract is unverified")
        if (b.callerPackage != context.packageName || b.callerUid != context.applicationInfo.uid || b.sdk != Build.VERSION.SDK_INT) throw SeriesSourceReview("Native caller identity changed")
        return b
    }

    override suspend fun loadForEdit(selected: EventRef): SeriesGraphDurationReadResult = withContext(io + readBudgetContext(budgets.mutation)) {
        try {
            if (!permissions()) return@withContext SeriesGraphDurationReadResult.PermissionRequired
            val issued = binding()
            if (!permissions()) return@withContext SeriesGraphDurationReadResult.PermissionRequired
            val read = sourceReader.loadForEditDecorated(selected) { source, series ->
                val template = (RecurrenceMasterTemplates.resolve(series.snapshot) as? RecurrenceMasterTemplateResult.Ready)?.template
                    ?: throw SeriesSourceReview("The source master duration requires review")
                val duration = when (val time = template.time) {
                    is EventTime.Timed -> SeriesGraphDuration.TimedSeconds(time.duration.seconds)
                    is EventTime.AllDay -> SeriesGraphDuration.AllDayDays(java.time.temporal.ChronoUnit.DAYS.between(time.dates.start, time.dates.endExclusive))
                }
                val receipt = SeriesGraphDurationNativeReceipt.create("eligibility", SeriesGraphDurationCommand(selected, series.token, duration), source.canonical(), issued, budgets, planning)
                operations(receipt.plan(budgets, planning)) // Same complete transaction bounds as dispatch.
                series
            }
            if (binding() != issued) return@withContext SeriesGraphDurationReadResult.NeedsReview("Native provider binding changed")
            // The verifier may suspend; its result never preserves an earlier permission grant.
            if (!permissions()) return@withContext SeriesGraphDurationReadResult.PermissionRequired
            when (read) {
                is SeriesReadResult.Ready -> SeriesGraphDurationReadResult.Ready(SeriesGraphDurationAccess(read.series, AccessDecision.ALLOWED))
                is SeriesReadResult.NeedsReview -> SeriesGraphDurationReadResult.NeedsReview(read.reason)
                is SeriesReadResult.Denied -> SeriesGraphDurationReadResult.Denied(read.reason)
                SeriesReadResult.PermissionRequired -> SeriesGraphDurationReadResult.PermissionRequired
                SeriesReadResult.Unavailable -> SeriesGraphDurationReadResult.Unavailable
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (review: SeriesSourceReview) { SeriesGraphDurationReadResult.NeedsReview(review.reason) }
        catch (_: ProviderReadLimitExceeded) { SeriesGraphDurationReadResult.NeedsReview("Complete native duration proof exceeds the budget") }
        catch (_: SecurityException) { SeriesGraphDurationReadResult.PermissionRequired }
        catch (_: Exception) { SeriesGraphDurationReadResult.NeedsReview("Complete native duration admission requires review") }
    }

    override suspend fun mutate(operationId: String, command: SeriesGraphDurationCommand): SeriesMutationResult = withContext(io + readBudgetContext(budgets.mutation)) {
        mutex.withLock {
            var attempted: CalendarLocalStore.Operation? = null
            try {
                val signature = SeriesGraphDurationNativeReceipt.fingerprint(command)
                store.abandoned(operationId) { SeriesGraphDurationNativeReceipt.FAMILY to signature }?.let { return@withLock common(it) }
                if (operationId.isBlank()) return@withLock SeriesMutationResult.Invalid("Operation ID is required")
                val header = store.operationHeader(operationId)
                if (header != null) {
                    if (header.kind != SeriesGraphDurationNativeReceipt.FAMILY || header.signature != signature) return@withLock common(MutationResult.Conflict(null))
                    if (header.phase == "ATTEMPTED") return@withLock uncertain(operationId)
                    if (header.phase != "PREPARED") return@withLock recoverLocked(operationId)
                }
                if (!permissions()) return@withLock common(MutationResult.PermissionRequired)
                val issued = binding()
                if (!permissions()) return@withLock common(MutationResult.PermissionRequired)
                val source = sourceReader.checkedSource(command.selected, command.token)
                val proposed = SeriesGraphDurationNativeReceipt.create(operationId, command, source.canonical(), issued, budgets, planning)
                operations(proposed.plan(budgets, planning))
                val prepared = proposed.encode(budgets, planning)
                if (!permissions()) return@withLock common(MutationResult.PermissionRequired)
                if (binding() != issued) return@withLock SeriesMutationResult.PlanningReview("Native provider binding changed")
                if (!permissions()) return@withLock common(MutationResult.PermissionRequired)
                currentCoroutineContext().ensureActive()
                store.prepare(operationId, signature, SeriesGraphDurationNativeReceipt.FAMILY, prepared)
                val winner = store.operation(operationId) ?: error("Journal unavailable")
                if (winner.kind != SeriesGraphDurationNativeReceipt.FAMILY || winner.signature != signature) return@withLock common(MutationResult.Conflict(null))
                if (winner.phase != "PREPARED") return@withLock recoverLocked(operationId)
                val receipt = SeriesGraphDurationNativeReceipt.parse(winner, budgets, planning)
                val plan = receipt.plan(budgets, planning)
                if (receipt.binding != issued || plan.command != command || plan.sourceRaw != source.canonical()) return@withLock common(MutationResult.Conflict(null))
                // Never dispatch a local proposal when another prepare won; materialize its exact frame.
                val batch = operations(plan)
                if (sourceReader.checkedSource(command.selected, command.token).canonical() != plan.sourceRaw) return@withLock common(MutationResult.Conflict(null))
                if (!permissions()) return@withLock common(MutationResult.PermissionRequired)
                if (binding() != receipt.binding) return@withLock SeriesMutationResult.PlanningReview("Native provider binding changed")
                if (!permissions()) return@withLock common(MutationResult.PermissionRequired)
                currentCoroutineContext().ensureActive()
                if (!store.attempt(winner)) return@withLock recoverLocked(operationId)
                attempted = winner.copy(phase = "ATTEMPTED")
                // From this durable boundary onward every unknown exit is non-replayable.
                if (!permissions() || binding() != receipt.binding) return@withLock uncertain(operationId)
                if (!permissions()) return@withLock uncertain(operationId)
                currentCoroutineContext().ensureActive()
                val results = resolver.applyBatch(CalendarContract.AUTHORITY, batch)
                val counts = acknowledgement(results, plan)
                val committed = receipt.withPhase(SeriesGraphDurationNativeReceipt.Phase.COMMITTED, counts, budgets, planning)
                if (!store.mark(requireNotNull(attempted), "COMMITTED", committed.encode(budgets, planning))) return@withLock uncertain(operationId)
                recoverLocked(operationId)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: ProviderReadLimitExceeded) { if (attempted != null) uncertain(operationId) else SeriesMutationResult.PlanningReview("Complete native duration transaction exceeds the budget") }
            catch (_: SecurityException) { if (attempted != null) uncertain(operationId) else common(MutationResult.PermissionRequired) }
            catch (denied: SeriesSourceDenied) { if (attempted != null) uncertain(operationId) else common(MutationResult.Denied(denied.reason)) }
            catch (_: SeriesSourceConflict) { if (attempted != null) uncertain(operationId) else common(MutationResult.Conflict(null)) }
            catch (review: SeriesSourceReview) { if (attempted != null) uncertain(operationId) else SeriesMutationResult.PlanningReview(review.reason) }
            catch (_: Exception) { if (attempted != null) uncertain(operationId) else SeriesMutationResult.PlanningReview("The complete native duration proof is unavailable") }
        }
    }

    override suspend fun recover(operationId: String): SeriesMutationResult = withContext(io + readBudgetContext(budgets.mutation)) {
        mutex.withLock { recoverLocked(operationId) }
    }
    private suspend fun recoverLocked(id: String): SeriesMutationResult {
        try {
            val header = store.operationHeader(id) ?: return common(MutationResult.Unavailable)
            if (header.kind != SeriesGraphDurationNativeReceipt.FAMILY) return common(MutationResult.Unavailable)
            if (header.phase == "ABANDONED") return common(MutationResult.Retired(id))
            if (header.phase == "ATTEMPTED") return uncertain(id) // Stored PREPARED evidence is not proof of no native attempt.
            if (!hasPermission(Manifest.permission.READ_CALENDAR)) return common(MutationResult.PermissionRequired)
            val operation = store.operation(id) ?: return common(MutationResult.Unavailable)
            val receipt = SeriesGraphDurationNativeReceipt.parse(operation, budgets, planning)
            val plan = receipt.plan(budgets, planning)
            if (binding() != receipt.binding) return uncertain(id)
            if (!hasPermission(Manifest.permission.READ_CALENDAR)) return common(MutationResult.PermissionRequired)
            if (receipt.phase == SeriesGraphDurationNativeReceipt.Phase.PREPARED) {
                if (!permissions()) return common(MutationResult.PermissionRequired)
                if (sourceReader.checkedSource(plan.command.selected, plan.command.token).canonical() != plan.sourceRaw) return common(MutationResult.Conflict(null))
                operations(plan)
                if (!permissions()) return common(MutationResult.PermissionRequired)
                return common(MutationResult.SafeToRetry(id))
            }
            val current = sourceReader.source(plan.candidate)
            // Old selection is hash-comparison CONTEXT ONLY. Never plan, issue a token/right or
            // return this adjusted object. The candidate itself was freshly proven by source().
            val compared = current.copy(snapshot = current.snapshot.copy(validatedSelection = plan.command.selected)).canonical()
            if (compared != plan.expectedRaw || binding() != receipt.binding) return uncertain(id)
            // Known-commit recovery is read-only, including after the final suspending verifier.
            if (!hasPermission(Manifest.permission.READ_CALENDAR)) return common(MutationResult.PermissionRequired)
            val verified = receipt.withPhase(SeriesGraphDurationNativeReceipt.Phase.VERIFIED, plan.counts, budgets, planning)
            if (!store.mark(operation, "VERIFIED", verified.encode(budgets, planning))) return uncertain(id)
            if (!hasPermission(Manifest.permission.READ_CALENDAR)) return common(MutationResult.PermissionRequired)
            sourceReader.refresh()
            return SeriesMutationResult.LocalSaved(current.snapshot.identity, current.selectedDetails.occurrence.ref)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: SecurityException) { return common(MutationResult.PermissionRequired) }
        catch (_: Exception) { return uncertain(id) }
    }

    private fun operations(plan: SeriesGraphDurationNativeReceipt.Plan): ArrayList<ContentProviderOperation> {
        if (plan.counts.size > SeriesGraphDurationNativeReceipt.MAX_OPERATIONS) throw ProviderReadLimitExceeded()
        val operations = arrayListOf<ContentProviderOperation>()
        assertions(operations, NativeDurationJson.parse(plan.sourceRaw, budgets))
        require(operations.size == plan.updateIndex)
        val request = plan.value.getValue("request") as Map<*, *>
        operations += ContentProviderOperation.newUpdate(eventUri(requireNotNull(plan.command.selected.recurrence).masterEventId))
            .withValues(ContentValues().apply { put("duration", request["duration"] as String); put("dtstart", (request["dtstart"] as String).toLong()) })
            .withExpectedCount(1).withYieldAllowed(false).build()
        assertions(operations, NativeDurationJson.parse(plan.expectedRaw, budgets))
        require(operations.size == plan.counts.size)
        val parcel = Parcel.obtain()
        try { parcel.writeTypedList(operations); if (parcel.dataSize() > 256 * 1024) throw ProviderReadLimitExceeded() } finally { parcel.recycle() }
        return operations
    }
    private fun assertions(out: ArrayList<ContentProviderOperation>, source: JSONObject) {
        val calendar = NativeDurationJson.row(source.getJSONObject("calendar")); val master = NativeDurationJson.row(source.getJSONObject("master"))
        val events = listOf(master) + source.getJSONArray("exceptions").let { a -> List(a.length()) { NativeDurationJson.row(a.getJSONObject(it)) } }
        val id = master.getValue("_id")!!; val calendarId = calendar.getValue("_id")!!; val sync = master["_sync_id"]?.takeIf(String::isNotBlank)
        out += assertRow(ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, calendarId.toLong()), calendar)
        val membership = "deleted = 0 AND (_id = ? OR original_id = ?" + if (sync == null) ")" else " OR (calendar_id = ? AND original_sync_id = ?))"
        val args = listOf(id,id) + if (sync == null) emptyList() else listOf(calendarId,sync)
        out += ContentProviderOperation.newAssertQuery(CalendarContract.Events.CONTENT_URI).withSelection(membership,args.toTypedArray()).withExpectedCount(events.size).withYieldAllowed(false).build()
        if (sync == null) out += ContentProviderOperation.newAssertQuery(CalendarContract.Events.CONTENT_URI)
            .withSelection("calendar_id = ? AND original_id IS NULL AND original_sync_id IS NOT NULL AND deleted = 0",arrayOf(calendarId)).withExpectedCount(0).withYieldAllowed(false).build()
        events.forEach { out += assertRow(eventUri(it.getValue("_id")!!.toLong()),it) }
        for ((name,uri) in listOf("reminders" to CalendarContract.Reminders.CONTENT_URI,"attendees" to CalendarContract.Attendees.CONTENT_URI,"extendedProperties" to CalendarContract.ExtendedProperties.CONTENT_URI)) {
            val groups = source.getJSONArray(name)
            for (i in 0 until groups.length()) {
                val group = groups.getJSONObject(i)
                out += ContentProviderOperation.newAssertQuery(uri).withSelection("event_id = ?",arrayOf(group.getLong("parent").toString()))
                    .withExpectedCount(group.getJSONArray("rows").length()).withYieldAllowed(false).build()
            }
            for (i in 0 until groups.length()) {
                val rows = groups.getJSONObject(i).getJSONArray("rows")
                for (j in 0 until rows.length()) { val row = NativeDurationJson.row(rows.getJSONObject(j)); out += assertRow(ContentUris.withAppendedId(uri,row.getValue("_id")!!.toLong()),row) }
            }
        }
    }
    private fun assertRow(uri: Uri, row: Map<String,String?>) = ContentProviderOperation.newAssertQuery(uri).withValues(ContentValues().apply {
        row.forEach { (key,value) -> if (value == null) putNull(key) else put(key,value) }
    }).withExpectedCount(1).withYieldAllowed(false).build()
    private fun eventUri(id: Long) = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI,id)
    private fun acknowledgement(results: Array<ContentProviderResult>, plan: SeriesGraphDurationNativeReceipt.Plan): List<Int> {
        require(results.size == plan.counts.size)
        return results.mapIndexed { index,result ->
            require(result.uri == null && result.count == plan.counts[index])
            if (Build.VERSION.SDK_INT >= 30) require(result.exception == null && result.extras == null)
            requireNotNull(result.count)
        }.also { require(it[plan.updateIndex] == 1) }
    }
}
