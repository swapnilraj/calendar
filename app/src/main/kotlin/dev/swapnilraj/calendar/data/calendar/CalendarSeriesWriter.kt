package dev.swapnilraj.calendar.data.calendar

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.OperationApplicationException
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.Parcel
import android.provider.CalendarContract
import dev.swapnilraj.calendar.core.*
import java.security.MessageDigest
import java.time.Instant
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** First scoped executor: preserving field edits/deletion only. Never inserts or re-parents rows. */
class CalendarSeriesWriter(
    private val resolver: ContentResolver,
    private val sourceReader: CalendarSeriesReader,
    private val store: CalendarLocalStore,
    private val hasPermission: (String) -> Boolean,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val budget: RecurrencePlanningBudget = RecurrencePlanningBudget(),
) : CalendarSeriesMutations {
    private val mutex = Mutex()
    private class Unsupported(val reason: String) : Exception()
    private class Invalid(val fields: Set<InvalidEventField>) : Exception()
    private data class Prepared(val operations: ArrayList<ContentProviderOperation>, val receipt: JSONObject, val source: CalendarSeriesSource)

    override suspend fun loadForEdit(selected: EventRef): SeriesReadResult = when (val result = sourceReader.loadForEdit(selected)) {
        is SeriesReadResult.Ready -> {
            val fields = result.series.allowedFields - EditableField.TIME - EditableField.ZONE
            val supported = SeriesScopeCapabilities(AccessDecision.ALLOWED, fields, AccessDecision.ALLOWED)
            val unsupported = SeriesScopeCapabilities(AccessDecision.DENIED, emptySet(), AccessDecision.DENIED, SeriesActionLimitation.UNSUPPORTED)
            val existing = result.series.snapshot.exceptions.any { it.ref == selected && !it.cancelled }
            result.copy(series = result.series.copy(allowedFields = fields, scopes = mapOf(RecurrenceScope.ALL to supported,
                RecurrenceScope.THIS to if (existing) supported else unsupported, RecurrenceScope.FOLLOWING to unsupported)))
        }
        else -> result
    }

    override suspend fun mutate(operationId: String, selected: EventRef, token: EditToken, command: SeriesCommand): SeriesMutationResult = withContext(io) {
        mutex.withLock {
            if (!permissions()) return@withLock common(MutationResult.PermissionRequired)
            var attempted = false
            var submitted: Prepared? = null
            try {
                if (operationId.isBlank()) return@withLock SeriesMutationResult.Invalid("Operation ID is required")
                val kind = kind(command)
                val signature = hash(JSONObject().put("version", 1).put("selected", refJson(selected)).put("token", token.value).put("command", commandJson(command)).toString())
                val existing = store.operation(operationId)
                if (existing != null) {
                    if (existing.kind != kind || existing.signature != signature) return@withLock common(MutationResult.Conflict(null))
                    if (existing.phase !in setOf("PREPARED", "FAILED")) return@withLock recover(existing, false)
                    if (existing.resultRef != null) return@withLock common(MutationResult.Uncertain(operationId, null))
                }
                val source = sourceReader.checkedSource(selected, token)
                val planned = when (command) {
                    is SeriesCommand.OccurrenceEdit -> RecurrencePlanning.plan(source.snapshot, selected, RecurrenceScope.THIS, RecurrenceMutation.Edit(command.patch), budget)
                    is SeriesCommand.WholeEdit -> RecurrenceEditing.plan(source.snapshot, selected, command.intent, budget)
                    is SeriesCommand.FollowingEdit -> RecurrenceEditing.plan(source.snapshot, selected, command.intent, budget)
                    is SeriesCommand.Delete -> RecurrencePlanning.plan(source.snapshot, selected, command.scope, RecurrenceMutation.Delete, budget)
                }
                when (planned) {
                    is RecurrencePlanningResult.NeedsReview -> return@withLock SeriesMutationResult.PlanningReview(planned.reason)
                    is RecurrencePlanningResult.Unsupported -> return@withLock SeriesMutationResult.Unsupported(planned.reason)
                    is RecurrencePlanningResult.Invalid -> return@withLock SeriesMutationResult.Invalid(planned.reason)
                    is RecurrencePlanningResult.Ready -> Unit
                }
                if (command is SeriesCommand.FollowingEdit || command is SeriesCommand.Delete && command.scope == RecurrenceScope.FOLLOWING)
                    throw Unsupported("Following scopes require a separately verified split and transfer executor")
                val prepared = prepare(source, token, command, planned)
                val parcel = Parcel.obtain()
                try {
                    parcel.writeTypedList(prepared.operations)
                    if (parcel.dataSize() > 256 * 1024) throw SeriesSourceReview("The complete series transaction exceeds the safe local size budget")
                } finally { parcel.recycle() }
                if (!permissions()) return@withLock common(MutationResult.PermissionRequired)
                currentCoroutineContext().ensureActive()
                store.prepare(operationId, signature, kind)
                val persisted = store.operation(operationId) ?: error("Journal unavailable")
                if (persisted.kind != kind || persisted.signature != signature) return@withLock common(MutationResult.Conflict(null))
                if (!store.attempt(operationId)) return@withLock recover(store.operation(operationId)!!, false)
                attempted = true
                submitted = prepared
                resolver.applyBatch(CalendarContract.AUTHORITY, prepared.operations)
                store.mark(operationId, "COMMITTED", prepared.receipt.toString())
                recover(store.operation(operationId)!!, false)
            } catch (_: SecurityException) {
                if (attempted) common(MutationResult.Uncertain(operationId, submitted?.source?.snapshot?.identity?.masterEventId))
                else common(MutationResult.PermissionRequired)
            } catch (denied: SeriesSourceDenied) { common(MutationResult.Denied(denied.reason)) }
            catch (review: SeriesSourceReview) { SeriesMutationResult.PlanningReview(review.reason) }
            catch (_: SeriesSourceConflict) { common(MutationResult.Conflict(null)) }
            catch (unsupported: Unsupported) { SeriesMutationResult.Unsupported(unsupported.reason) }
            catch (invalid: Invalid) { common(MutationResult.ValidationFailed(invalid.fields)) }
            catch (_: OperationApplicationException) {
                if (!attempted) common(MutationResult.Unavailable) else try {
                    store.mark(operationId, "FAILED")
                    val changed = try { sourceReader.source(selected).canonical() != submitted?.source?.canonical() }
                        catch (_: SeriesSourceConflict) { true }
                    common(if (changed) MutationResult.Conflict(null) else MutationResult.Unavailable)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { common(MutationResult.Uncertain(operationId, null)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { common(if (attempted) MutationResult.Uncertain(operationId, submitted?.source?.snapshot?.identity?.masterEventId) else MutationResult.Unavailable) }
        }
    }

    private fun prepare(source: CalendarSeriesSource, token: EditToken, command: SeriesCommand, plan: RecurrencePlanningResult.Ready): Prepared {
        check(plan.series == source.snapshot.identity)
        if (plan.actions.size != 1) throw Unsupported("This executor requires a single preserving series action")
        val operations = assertions(source)
        var deleted = false
        val targets: List<Long>
        when (val action = plan.actions.single()) {
            is RecurrencePlanAction.UpdateMaster -> {
                if (command !is SeriesCommand.WholeEdit || action.rule != FieldChange.Keep || action.exclusions != FieldChange.Keep ||
                    action.anchor != FieldChange.Keep || action.duration != RecurrenceDurationChange.KeepRaw)
                    throw Unsupported("Rule, anchor and duration changes are not yet supported by the provider executor")
                nonTemporal(action.patch)
                targets = listOf(source.snapshot.identity.masterEventId)
                edit(operations, source, source.master, action.patch)
            }
            is RecurrencePlanAction.UpsertException -> {
                if (action.target != SeriesTarget.EXISTING || action.existing == null) throw Unsupported("Creating a new exception requires a separately verified clone policy")
                val existing = checkNotNull(action.existing)
                val row = source.exceptions.singleOrNull { it["_id"]?.toLongOrNull() == existing.eventId } ?: throw SeriesSourceConflict()
                if (source.snapshot.exceptions.none { it.ref == existing && it.original == action.original }) throw SeriesSourceConflict()
                targets = listOf(existing.eventId)
                nonTemporal(action.patch)
                if (action.cancelled) {
                    deleted = true
                    operations += ContentProviderOperation.newUpdate(eventUri(existing.eventId)).withValue(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CANCELED)
                        .withExpectedCount(1).withYieldAllowed(false).build()
                } else edit(operations, source, row, action.patch)
            }
            is RecurrencePlanAction.DeleteSeries -> {
                if (command != SeriesCommand.Delete(RecurrenceScope.ALL)) throw Unsupported("Only explicit whole-series deletion is supported")
                deleted = true
                targets = source.exceptions.map { it["_id"]!!.toLong() } + source.snapshot.identity.masterEventId
                targets.forEach { id -> operations += ContentProviderOperation.newDelete(eventUri(id)).withExpectedCount(1).withYieldAllowed(false).build() }
            }
            else -> throw Unsupported("Creating or transferring series rows requires a separately verified executor")
        }
        val scope = when (command) { is SeriesCommand.OccurrenceEdit -> RecurrenceScope.THIS; is SeriesCommand.Delete -> command.scope; else -> RecurrenceScope.ALL }
        val rows = (listOf(source.master) + source.exceptions).associateBy { it["_id"]!!.toLong() }
        val receipt = JSONObject().put("version", 1).put("calendar", source.snapshot.identity.calendarId.value)
            .put("token", token.value)
            .put("command", commandJson(command))
            .put("master", source.snapshot.identity.masterEventId).put("selected", refJson(source.snapshot.validatedSelection!!))
            .put("scope", scope.name).put("deleted", deleted).put("namespace", namespace(source.calendar))
            .put("targets", JSONArray(targets)).put("syncIds", JSONObject().apply { targets.forEach { put(it.toString(), rows.getValue(it)["_sync_id"]?.takeIf(String::isNotBlank) ?: JSONObject.NULL) } })
            .put("eventUids", JSONObject().apply { targets.forEach { put(it.toString(), rows.getValue(it)[CalendarContract.Events.UID_2445]?.takeIf(String::isNotBlank)?.let(::hash) ?: JSONObject.NULL) } })
        return Prepared(operations, receipt, source)
    }

    private fun nonTemporal(patch: EventPatch) {
        if (patch.time != FieldChange.Keep || patch.eventZone != FieldChange.Keep)
            throw Unsupported("Time and zone changes require a separately verified series executor")
    }
    private fun edit(operations: ArrayList<ContentProviderOperation>, source: CalendarSeriesSource, row: Map<String, String?>, patch: EventPatch) {
        val id = row["_id"]!!.toLong()
        val changes = ContentValues()
        (patch.title as? FieldChange.Set)?.let { changes.put(CalendarContract.Events.TITLE, it.value) }
        (patch.location as? FieldChange.Set)?.let { changes.put(CalendarContract.Events.EVENT_LOCATION, it.value) }
        (patch.description as? FieldChange.Set)?.let { changes.put(CalendarContract.Events.DESCRIPTION, it.value) }
        val invalid = mutableSetOf<InvalidEventField>()
        (patch.availability as? FieldChange.Set)?.let {
            val value = it.value ?: 0
            val allowed = source.calendar[CalendarContract.Calendars.ALLOWED_AVAILABILITY]?.takeIf(String::isNotBlank)?.split(',')?.map { raw -> raw.trim().toIntOrNull() }
            if (value !in 0..2 || allowed == null || allowed.any { raw -> raw == null || raw !in 0..2 } || value !in allowed) invalid += InvalidEventField.AVAILABILITY
            else changes.put(CalendarContract.Events.AVAILABILITY, value)
        }
        (patch.privacy as? FieldChange.Set)?.let {
            val value = it.value ?: 0
            if (value !in 0..3) invalid += InvalidEventField.PRIVACY else changes.put(CalendarContract.Events.ACCESS_LEVEL, value)
        }
        val alerts = (patch.alertMinutes as? FieldChange.Set)?.value
        if (alerts != null) {
            val preserved = source.reminders[id].orEmpty().count { it["method"] != CalendarContract.Reminders.METHOD_ALERT.toString() }
            val allowed = source.calendar[CalendarContract.Calendars.ALLOWED_REMINDERS]?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
            val maximum = source.calendar[CalendarContract.Calendars.MAX_REMINDERS]?.toIntOrNull()
            if (alerts.any { it == CalendarContract.Reminders.MINUTES_DEFAULT } || alerts.distinct().size != alerts.size ||
                (alerts.isNotEmpty() && (CalendarContract.Reminders.METHOD_ALERT !in allowed.orEmpty() || maximum == null || maximum < alerts.size + preserved)))
                invalid += InvalidEventField.ALERTS
            changes.put(CalendarContract.Events.HAS_ALARM, if (alerts.isNotEmpty() || preserved > 0) 1 else 0)
        }
        if (invalid.isNotEmpty()) throw Invalid(invalid)
        if (changes.size() == 0) throw Unsupported("No supported fields were changed")
        operations += ContentProviderOperation.newUpdate(eventUri(id)).withValues(changes).withExpectedCount(1).withYieldAllowed(false).build()
        if (alerts != null) {
            operations += ContentProviderOperation.newDelete(CalendarContract.Reminders.CONTENT_URI).withSelection("event_id = ? AND method = ?",
                arrayOf(id.toString(), CalendarContract.Reminders.METHOD_ALERT.toString())).withYieldAllowed(false).build()
            alerts.forEach { minutes -> operations += ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI).withValue("event_id", id)
                .withValue("minutes", minutes).withValue("method", CalendarContract.Reminders.METHOD_ALERT).withYieldAllowed(false).build() }
        }
    }

    private fun assertions(source: CalendarSeriesSource): ArrayList<ContentProviderOperation> = arrayListOf(assertRow(
        ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, source.snapshot.identity.calendarId.value), source.calendar)).apply {
        val id = source.snapshot.identity.masterEventId.toString()
        val calendar = source.snapshot.identity.calendarId.value.toString()
        val sync = source.master["_sync_id"]?.takeIf { it.isNotBlank() }
        val selection = "deleted = 0 AND (_id = ? OR original_id = ?" + if (sync == null) ")" else " OR (calendar_id = ? AND original_sync_id = ?))"
        val args = listOf(id, id) + if (sync == null) emptyList() else listOf(calendar, sync)
        add(ContentProviderOperation.newAssertQuery(CalendarContract.Events.CONTENT_URI).withSelection(selection, args.toTypedArray())
            .withExpectedCount(source.exceptions.size + 1).withYieldAllowed(false).build())
        if (sync == null) add(ContentProviderOperation.newAssertQuery(CalendarContract.Events.CONTENT_URI)
            .withSelection("calendar_id = ? AND original_id IS NULL AND original_sync_id IS NOT NULL AND deleted = 0", arrayOf(calendar))
            .withExpectedCount(0).withYieldAllowed(false).build())
        (listOf(source.master) + source.exceptions).forEach { add(assertRow(eventUri(it["_id"]!!.toLong()), it)) }
        listOf(CalendarContract.Reminders.CONTENT_URI to source.reminders, CalendarContract.Attendees.CONTENT_URI to source.attendees).forEach { (uri, grouped) ->
            grouped.keys.toList().chunked(200).forEach { chunk ->
                add(ContentProviderOperation.newAssertQuery(uri).withSelection("event_id IN (${chunk.joinToString(",") { "?" }})", chunk.map { it.toString() }.toTypedArray())
                    .withExpectedCount(chunk.sumOf { grouped.getValue(it).size }).withYieldAllowed(false).build())
            }
            grouped.values.flatten().forEach { add(assertRow(ContentUris.withAppendedId(uri, it["_id"]!!.toLong()), it)) }
        }
    }
    private fun assertRow(uri: Uri, row: Map<String, String?>) = ContentProviderOperation.newAssertQuery(uri).withValues(ContentValues().apply {
        row.forEach { (column, value) -> if (value == null) putNull(column) else put(column, value) }
    }).withExpectedCount(1).withYieldAllowed(false).build()

    override suspend fun recover(operationId: String): SeriesMutationResult = withContext(io) { mutex.withLock {
        if (!hasPermission(Manifest.permission.READ_CALENDAR)) return@withLock common(MutationResult.PermissionRequired)
        try { store.operation(operationId)?.let { operation ->
            try { recover(operation, true) }
            catch (denied: SecurityException) { throw denied }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { common(MutationResult.Uncertain(operationId, null)) }
        } ?: common(MutationResult.Unavailable) }
        catch (_: SecurityException) { common(MutationResult.PermissionRequired) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { common(MutationResult.Unavailable) }
    } }
    private suspend fun recover(operation: CalendarLocalStore.Operation, safeRetry: Boolean): SeriesMutationResult {
        fun uncertain(master: Long? = null) = common(MutationResult.Uncertain(operation.id, master))
        if (operation.kind !in kinds) return uncertain()
        if (operation.phase in setOf("PREPARED", "FAILED")) return if (operation.resultRef != null) uncertain()
            else if (safeRetry) common(MutationResult.SafeToRetry(operation.id)) else common(MutationResult.Unavailable)
        if (operation.phase !in setOf("COMMITTED", "VERIFIED") || operation.resultRef == null) return uncertain()
        val receipt = try { JSONObject(operation.resultRef).also { require(it.getInt("version") == 1) } } catch (_: Exception) { return uncertain() }
        val identity = SeriesIdentity(CalendarId(receipt.getLong("calendar")), receipt.getLong("master"))
        val selected = parseRef(receipt.getJSONObject("selected"))
        val scope = RecurrenceScope.valueOf(receipt.getString("scope"))
        if (selected.calendarId != identity.calendarId || selected.recurrence?.masterEventId != identity.masterEventId) return uncertain()
        val deleted = receipt.getBoolean("deleted")
        if (scope == RecurrenceScope.FOLLOWING || operation.kind != "SERIES_V1_${scope.name}_${if (deleted) "DELETE" else "EDIT"}") return uncertain()
        val original = store.edit(receipt.getString("token")) ?: return uncertain(identity.masterEventId)
        if (hash("SERIES_EDIT_V1" + original) != receipt.getString("token")) return uncertain(identity.masterEventId)
        val bound = JSONObject(original)
        if (parseRef(bound.getJSONObject("selection")) != selected || bound.getJSONObject("master").getString("_id").toLong() != identity.masterEventId)
            return uncertain(identity.masterEventId)
        val command = receipt.getJSONObject("command")
        if (hash(JSONObject().put("version", 1).put("selected", refJson(selected)).put("token", receipt.getString("token")).put("command", command).toString()) != operation.signature)
            return uncertain(identity.masterEventId)
        val boundCalendar = bound.getJSONObject("calendar").let { json -> json.keys().asSequence().associateWith { json.nullable(it) } }
        if (namespace(boundCalendar) != receipt.getString("namespace")) return uncertain(identity.masterEventId)
        val calendar = rows(ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, identity.calendarId.value), calendarProof).singleOrNull()
            ?: return uncertain(identity.masterEventId)
        if (calendar["_id"]?.toLongOrNull() != identity.calendarId.value || namespace(calendar) != receipt.getString("namespace") ||
            (calendar[CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL]?.toIntOrNull() ?: -1) < CalendarContract.Calendars.CAL_ACCESS_READ) return uncertain(identity.masterEventId)
        val targets = receipt.getJSONArray("targets").let { array -> List(array.length()) { array.getLong(it) } }
        if (targets.isEmpty() || targets.toSet().size != targets.size || targets.any { it < 0 }) return uncertain(identity.masterEventId)
        val originalExceptions = bound.getJSONArray("exceptions").let { array -> List(array.length()) { array.getJSONObject(it).getString("_id").toLong() } }
        val boundRows = (listOf(bound.getJSONObject("master")) + bound.getJSONArray("exceptions").let { array -> List(array.length()) { array.getJSONObject(it) } })
            .associateBy { it.getString("_id").toLong() }
        val expectedTargets = when {
            scope == RecurrenceScope.ALL && deleted -> originalExceptions.toSet() + identity.masterEventId
            scope == RecurrenceScope.ALL -> setOf(identity.masterEventId)
            selected.eventId in originalExceptions -> setOf(selected.eventId)
            else -> return uncertain(identity.masterEventId)
        }
        if (targets.toSet() != expectedTargets) return uncertain(identity.masterEventId)
        for (id in targets) {
            val boundRow = boundRows.getValue(id)
            if (receipt.getJSONObject("syncIds").nullable(id.toString()) != boundRow.nullable("_sync_id")?.takeIf(String::isNotBlank) ||
                receipt.getJSONObject("eventUids").nullable(id.toString()) != boundRow.nullable(CalendarContract.Events.UID_2445)?.takeIf(String::isNotBlank)?.let(::hash))
                return uncertain(identity.masterEventId)
        }
        val events = mutableMapOf<Long, Map<String, String?>>()
        targets.chunked(200).forEach { chunk -> recoveryRows(chunk).forEach { row ->
            val id = row["_id"]?.toLongOrNull() ?: return uncertain(identity.masterEventId)
            if (id !in chunk || events.put(id, row) != null) return uncertain(identity.masterEventId)
        } }
        for (id in targets) {
            val event = events[id]
            if (event != null) {
                if (event["calendar_id"]?.toLongOrNull() != identity.calendarId.value) return uncertain(identity.masterEventId)
                val expectedSync = receipt.getJSONObject("syncIds").nullable(id.toString())
                if (expectedSync != null && !event["_sync_id"].isNullOrBlank() && event["_sync_id"] != expectedSync) return uncertain(identity.masterEventId)
                val expectedUid = receipt.getJSONObject("eventUids").nullable(id.toString())
                if (expectedUid != null && !event[CalendarContract.Events.UID_2445].isNullOrBlank() && hash(event.getValue(CalendarContract.Events.UID_2445)!!) != expectedUid) return uncertain(identity.masterEventId)
            }
            if (deleted) {
                val gone = event == null || event["deleted"] == "1" || (scope == RecurrenceScope.THIS && event["eventStatus"]?.toIntOrNull() == CalendarContract.Events.STATUS_CANCELED)
                if (!gone) return uncertain(identity.masterEventId)
            } else if (event == null || event["deleted"] != "0") return uncertain(identity.masterEventId)
        }
        val currentSelected = if (deleted) null else sourceReader.selectedReference(selected)
        ensureRead()
        store.mark(operation.id, "VERIFIED")
        sourceReader.refresh()
        return if (deleted) SeriesMutationResult.LocalDeleted(identity, scope, selected) else SeriesMutationResult.LocalSaved(identity, currentSelected)
    }

    private fun permissions() = hasPermission(Manifest.permission.READ_CALENDAR) && hasPermission(Manifest.permission.WRITE_CALENDAR)
    private suspend fun recoveryRows(chunk: List<Long>): List<Map<String, String?>> {
        val selection = "_id IN (${chunk.joinToString(",") { "?" }})"
        val args = chunk.map { it.toString() }.toTypedArray()
        return try { rows(CalendarContract.Events.CONTENT_URI, recoveryFields, selection, args) }
        catch (_: IllegalArgumentException) { rows(CalendarContract.Events.CONTENT_URI, recoveryFields.filter { it != CalendarContract.Events.UID_2445 }.toTypedArray(), selection, args) }
    }
    private fun ensureRead() { if (!hasPermission(Manifest.permission.READ_CALENDAR)) throw SecurityException() }
    private suspend fun rows(uri: Uri, fields: Array<String>, selection: String? = null, args: Array<String>? = null): List<Map<String, String?>> {
        ensureRead()
        val cursor = suspendCancellableCoroutine<Cursor> { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            try {
                val value = resolver.query(uri, fields, selection, args, null, signal) ?: error("Provider unavailable")
                try { ensureRead() } catch (failure: Exception) { value.close(); throw failure }
                continuation.resume(value, onCancellation = { _, resource, _ -> resource.close() })
            } catch (failure: Exception) { continuation.resumeWithException(failure) }
        }
        return cursor.use { buildList { while (it.moveToNext()) { currentCoroutineContext().ensureActive(); add(fields.associateWith { field ->
            val index = it.getColumnIndexOrThrow(field); if (it.isNull(index)) null else it.getString(index)
        }) } } }
    }

    companion object {
        private val kinds = setOf("SERIES_V1_ALL_EDIT", "SERIES_V1_THIS_EDIT", "SERIES_V1_ALL_DELETE", "SERIES_V1_THIS_DELETE")
        private fun kind(command: SeriesCommand) = when (command) {
            is SeriesCommand.OccurrenceEdit -> "SERIES_V1_THIS_EDIT"
            is SeriesCommand.WholeEdit -> "SERIES_V1_ALL_EDIT"
            is SeriesCommand.FollowingEdit -> "SERIES_V1_FOLLOWING_EDIT"
            is SeriesCommand.Delete -> "SERIES_V1_${command.scope.name}_DELETE"
        }
        private fun common(outcome: MutationResult) = SeriesMutationResult.Common(outcome)
        private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        private fun eventUri(id: Long) = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
        private fun namespace(calendar: Map<String, String?>) = hash(JSONObject().apply {
            listOf("_id", CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.Calendars.OWNER_ACCOUNT).forEach { put(it, calendar[it] ?: JSONObject.NULL) }
        }.toString())
        private fun JSONObject.nullable(key: String): String? = if (isNull(key)) null else getString(key)
        private fun refJson(ref: EventRef) = JSONObject().put("calendar", ref.calendarId.value).put("event", ref.eventId).put("start", ref.instanceStart.toString()).put("end", ref.instanceEnd.toString()).apply {
            ref.recurrence?.let { put("master", it.masterEventId); put("original", it.originalStart.toString()) }
        }
        private fun parseRef(json: JSONObject) = EventRef(CalendarId(json.getLong("calendar")), json.getLong("event"), Instant.parse(json.getString("start")), Instant.parse(json.getString("end")),
            RecurrenceRef(json.getLong("master"), Instant.parse(json.getString("original"))))
        private fun startJson(start: RecurrenceStart): JSONObject = when (start) {
            is RecurrenceStart.AllDay -> JSONObject().put("date", start.date.toString())
            is RecurrenceStart.Timed -> JSONObject().put("local", start.localDateTime.toString()).put("zone", start.zone.id).put("offset", start.preferredOffset?.id ?: JSONObject.NULL)
        }
        private fun patchJson(patch: EventPatch) = JSONObject().apply {
            fun <T> change(name: String, value: FieldChange<T>, encode: (T) -> Any?) { if (value is FieldChange.Set) put(name, encode(value.value) ?: JSONObject.NULL) }
            change("title", patch.title) { it }; change("location", patch.location) { it }; change("description", patch.description) { it }
            change("alerts", patch.alertMinutes) { JSONArray(it) }; change("availability", patch.availability) { it }; change("privacy", patch.privacy) { it }
            change("zone", patch.eventZone) { it?.id }; change("time", patch.time) { when (it) {
                is EventTime.Timed -> JSONObject().put("start", it.start.toString()).put("end", it.end.toString())
                is EventTime.AllDay -> JSONObject().put("startDate", it.dates.start.toString()).put("endDate", it.dates.endExclusive.toString())
            } }
        }
        private fun ruleJson(rule: RecurrenceRuleChanges) = JSONObject().apply {
            (rule.pattern as? FieldChange.Set)?.let { set -> put("pattern", when (val p = set.value) {
                RecurrencePattern.Daily -> JSONObject().put("type", "daily")
                is RecurrencePattern.Weekly -> JSONObject().put("type", "weekly").put("days", JSONArray(p.days.map { it.name }.sorted()))
                is RecurrencePattern.MonthlyDate -> JSONObject().put("type", "monthlyDate").put("day", p.day)
                is RecurrencePattern.MonthlyWeekday -> JSONObject().put("type", "monthlyWeekday").put("ordinal", p.ordinal).put("day", p.day.name)
                is RecurrencePattern.Yearly -> JSONObject().put("type", "yearly").put("date", p.monthDay.toString())
            }) }
            (rule.interval as? FieldChange.Set)?.let { put("interval", it.value) }
            (rule.weekStart as? FieldChange.Set)?.let { put("weekStart", it.value.name) }
            (rule.end as? FieldChange.Set)?.let { put("end", when (val e = it.value) {
                RecurrenceEnd.Never -> JSONObject().put("type", "never")
                is RecurrenceEnd.Count -> JSONObject().put("count", e.count)
                is RecurrenceEnd.ThroughDate -> JSONObject().put("date", e.date.toString())
                is RecurrenceEnd.ExactUntil -> JSONObject().put("instant", e.instant.toString())
            }) }
        }
        private fun commandJson(command: SeriesCommand): JSONObject {
            fun duration(value: RecurrenceDurationChange): Any = when (value) {
                RecurrenceDurationChange.KeepRaw -> "keep"
                is RecurrenceDurationChange.TimedElapsed -> JSONObject().put("seconds", value.duration.seconds)
                is RecurrenceDurationChange.AllDayDays -> JSONObject().put("days", value.days)
            }
            return when (command) {
                is SeriesCommand.OccurrenceEdit -> JSONObject().put("type", "this").put("patch", patchJson(command.patch))
                is SeriesCommand.Delete -> JSONObject().put("type", "delete").put("scope", command.scope.name)
                is SeriesCommand.WholeEdit -> JSONObject().put("type", "whole").put("expected", startJson(command.intent.expectedMasterStart)).put("patch", patchJson(command.intent.fields))
                    .put("duration", duration(command.intent.duration)).put("rule", ruleJson(command.intent.rule)).apply {
                        (command.intent.newMasterStart as? FieldChange.Set)?.let { put("start", startJson(it.value)) }
                    }
                is SeriesCommand.FollowingEdit -> JSONObject().put("type", "following").put("expected", when (val key = command.intent.expectedOriginalBoundary) {
                    is RecurrenceKey.Date -> JSONObject().put("date", key.date.toString())
                    is RecurrenceKey.Timed -> JSONObject().put("instant", key.instant.toString())
                }).put("patch", patchJson(command.intent.fields))
                    .put("duration", duration(command.intent.duration)).put("rule", ruleJson(command.intent.rule)).put("selectedPatch", patchJson(command.intent.selectedExceptionPatch)).apply {
                        (command.intent.newTailStart as? FieldChange.Set)?.let { put("start", startJson(it.value)) }
                    }
            }
        }
        private val calendarProof = arrayOf("_id", CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.Calendars.OWNER_ACCOUNT, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
        private val recoveryFields = arrayOf("_id", "calendar_id", "deleted", "eventStatus", "_sync_id", CalendarContract.Events.UID_2445)
    }
}
