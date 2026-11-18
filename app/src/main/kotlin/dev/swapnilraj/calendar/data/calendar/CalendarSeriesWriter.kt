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
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Scoped preserving edits; new THIS rows use the audited public single-exception transaction. */
class CalendarSeriesWriter(
    private val resolver: ContentResolver,
    private val sourceReader: CalendarSeriesReader,
    private val store: CalendarLocalStore,
    private val hasPermission: (String) -> Boolean,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val budget: RecurrencePlanningBudget = RecurrencePlanningBudget(),
    private val readBudgets: ProviderReadBudgets = ProviderReadBudgets(),
) : CalendarSeriesMutations {
    private val mutex = Mutex()
    private class Unsupported(val reason: String) : Exception()
    private class Invalid(val fields: Set<InvalidEventField>) : Exception()
    private data class Prepared(val operations: ArrayList<ContentProviderOperation>, val receipt: JSONObject, val source: CalendarSeriesSource,
        val insertedAt: Int? = null)
    private data class ExceptionTemplate(val ref: EventRef, val allDay: Boolean, val zone: String, val duration: String)
    private data class FollowingDelete(val whole: Boolean, val prefixRule: String?, val future: List<SeriesException>)

    override suspend fun loadForEdit(selected: EventRef): SeriesReadResult = try {
        sourceReader.loadForEditDecorated(selected) { source, series ->
            val fields = series.allowedFields - EditableField.TIME - EditableField.ZONE
            val supported = SeriesScopeCapabilities(AccessDecision.ALLOWED, fields, AccessDecision.ALLOWED)
            val unsupported = SeriesScopeCapabilities(AccessDecision.DENIED, emptySet(), AccessDecision.DENIED, SeriesActionLimitation.UNSUPPORTED)
            val existing = series.snapshot.exceptions.any { it.ref == selected && !it.cancelled }
            val thisCapabilities = if (existing) supported else try {
                newTemplate(source)
                supported
            } catch (_: Unsupported) { unsupported }
            catch (_: SeriesSourceReview) { unsupported.copy(limitation = SeriesActionLimitation.REVIEW_REQUIRED) }
            val following = when (val plan = RecurrencePlanning.plan(series.snapshot, selected, RecurrenceScope.FOLLOWING, RecurrenceMutation.Delete, budget)) {
                is RecurrencePlanningResult.Ready -> try {
                    followingDelete(series.snapshot, plan)
                    if (plan.actions.singleOrNull() !is RecurrencePlanAction.DeleteSeries)
                        followingContext(source.master)
                    unsupported.copy(delete = AccessDecision.ALLOWED, limitation = SeriesActionLimitation.UNKNOWN)
                } catch (_: Unsupported) { unsupported }
                catch (_: SeriesSourceReview) { unsupported.copy(limitation = SeriesActionLimitation.REVIEW_REQUIRED) }
                is RecurrencePlanningResult.Unsupported -> unsupported
                else -> unsupported.copy(limitation = SeriesActionLimitation.REVIEW_REQUIRED)
            }
            series.copy(allowedFields = fields, scopes = mapOf(RecurrenceScope.ALL to supported,
                RecurrenceScope.THIS to thisCapabilities, RecurrenceScope.FOLLOWING to following))
        }
    } catch (_: SecurityException) { SeriesReadResult.PermissionRequired }
    catch (denied: SeriesSourceDenied) { SeriesReadResult.Denied(denied.reason) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { SeriesReadResult.Unavailable }

    override suspend fun mutate(operationId: String, selected: EventRef, token: EditToken, command: SeriesCommand): SeriesMutationResult = withContext(io + readBudgetContext(readBudgets.mutation)) {
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
                if (command is SeriesCommand.FollowingEdit)
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
                val results = resolver.applyBatch(CalendarContract.AUTHORITY, prepared.operations)
                prepared.insertedAt?.let { index ->
                    val id = results.getOrNull(index)?.uri?.let(ContentUris::parseId) ?: error("Created row was not identified")
                    check(id >= 0 && (listOf(prepared.source.master) + prepared.source.exceptions).none { it["_id"]?.toLongOrNull() == id })
                    prepared.receipt.put("targets", JSONArray(listOf(id))).put("resultSelected", refJson(selected.copy(eventId = id)))
                        .put("syncIds", JSONObject().put(id.toString(), JSONObject.NULL)).put("eventUids", JSONObject().put(id.toString(), JSONObject.NULL))
                }
                store.mark(operationId, "COMMITTED", prepared.receipt.toString())
                recover(store.operation(operationId)!!, false)
            } catch (_: ProviderReadLimitExceeded) {
                if (attempted) common(MutationResult.Uncertain(operationId, null))
                else SeriesMutationResult.PlanningReview("Complete source exceeds the read budget")
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
        if (command == SeriesCommand.Delete(RecurrenceScope.FOLLOWING)) return prepareFollowing(source, token, command, plan)
        if (plan.actions.size != 1) throw Unsupported("This executor requires a single preserving series action")
        val operations = assertions(source)
        var deleted = false
        var insertedAt: Int? = null
        var template: ExceptionTemplate? = null
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
                if (action.target != SeriesTarget.EXISTING) throw Unsupported("Transferring exceptions requires a separately verified executor")
                nonTemporal(action.patch)
                if (action.existing == null) {
                    template = newTemplate(source)
                    deleted = action.cancelled
                    insertedAt = insertException(operations, source, template, action.patch, action.cancelled)
                    targets = emptyList() // Unknown until the provider returns the insertion URI.
                } else {
                val existing = checkNotNull(action.existing)
                val row = source.exceptions.singleOrNull { it["_id"]?.toLongOrNull() == existing.eventId } ?: throw SeriesSourceConflict()
                if (source.snapshot.exceptions.none { it.ref == existing && it.original == action.original }) throw SeriesSourceConflict()
                targets = listOf(existing.eventId)
                if (action.cancelled) {
                    deleted = true
                    operations += ContentProviderOperation.newUpdate(eventUri(existing.eventId)).withValue(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CANCELED)
                        .withExpectedCount(1).withYieldAllowed(false).build()
                } else edit(operations, source, row, action.patch)
                }
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
            .put("created", insertedAt != null)
            .put("targets", JSONArray(targets)).put("syncIds", JSONObject().apply { targets.forEach { put(it.toString(), rows.getValue(it)["_sync_id"]?.takeIf(String::isNotBlank) ?: JSONObject.NULL) } })
            .put("eventUids", JSONObject().apply { targets.forEach { put(it.toString(), rows.getValue(it)[CalendarContract.Events.UID_2445]?.takeIf(String::isNotBlank)?.let(::hash) ?: JSONObject.NULL) } })
        template?.let { receipt.put("createdAllDay", it.allDay).put("createdZone", it.zone) }
        return Prepared(operations, receipt, source, insertedAt)
    }

    private fun followingDelete(snapshot: RecurrenceSeriesSnapshot, plan: RecurrencePlanningResult.Ready): FollowingDelete {
        check(plan.series == snapshot.identity)
        val selected = requireNotNull(snapshot.validatedSelection)
        val cutoff = requireNotNull(selected.recurrence).originalStart
        fun before(key: RecurrenceKey) = when (key) {
            is RecurrenceKey.Timed -> key.instant < cutoff
            is RecurrenceKey.Date -> key.date < cutoff.atOffset(ZoneOffset.UTC).toLocalDate()
        }
        val whole = plan.actions.singleOrNull() as? RecurrencePlanAction.DeleteSeries
        if (whole != null) {
            check(whole.exceptions.toSet() == snapshot.exceptions.toSet())
            return FollowingDelete(true, null, whole.exceptions)
        }
        val master = plan.actions.firstOrNull() as? RecurrencePlanAction.UpdateMaster ?: throw Unsupported("Unsupported following deletion plan")
        val rule = (master.rule as? FieldChange.Set)?.value ?: throw Unsupported("Following deletion requires an exact prefix rule")
        val exclusions = (master.exclusions as? FieldChange.Set)?.value ?: throw Unsupported("Following deletion requires decoded exclusion coverage")
        if (master.patch != EventPatch() || master.anchor != FieldChange.Keep || master.duration != RecurrenceDurationChange.KeepRaw ||
            !snapshot.rDatesRaw.isNullOrBlank() || !snapshot.exRuleRaw.isNullOrBlank()) throw Unsupported("Unsupported following deletion transformation")
        // A single RRULE prefix contains no starts at/after the cutoff. Keeping the exact raw EXDATE
        // is set-equivalent: future exclusions are already outside that prefix. Do not normalize
        // imported local/TZID tokens, or discard their meaning if a user later extends the rule.
        if (!snapshot.exDatesRaw.isNullOrBlank() && !snapshot.exDatesDecoded) throw SeriesSourceReview("Exclusion coverage is incomplete")
        check(exclusions == snapshot.exDates.filterTo(linkedSetOf(), ::before))
        val removed = plan.actions.drop(1).map { (it as? RecurrencePlanAction.RemoveException)?.exception ?: throw Unsupported("Unsupported following deletion action") }
        val future = snapshot.exceptions.filter { !before(it.original) }
        check(removed.toSet() == future.toSet() && removed.size == future.size)
        return FollowingDelete(false, rule, future)
    }

    private fun followingContext(master: Map<String, String?>): ContentValues {
        if (master["dtstart"]?.toLongOrNull() == null || master["allDay"] !in setOf("0", "1") || master["eventTimezone"].isNullOrBlank() ||
            master["duration"].isNullOrBlank() || master["dtend"] != null) throw SeriesSourceReview("Recurring master context cannot be preserved safely")
        return ContentValues().apply {
            // Repeat unchanged context to seed raw times when updating RRULE, never interpret or
            // re-encode the source DURATION. No actual event-time/zone field is being edited.
            listOf("dtstart", "dtend", "duration", "eventTimezone", "allDay", "originalInstanceTime", "originalAllDay").forEach { key ->
                master[key]?.let { put(key, it) } ?: putNull(key)
            }
        }
    }

    private fun prepareFollowing(source: CalendarSeriesSource, token: EditToken, command: SeriesCommand, plan: RecurrencePlanningResult.Ready): Prepared {
        val deletion = followingDelete(source.snapshot, plan)
        val operations = assertions(source)
        val allRows = (listOf(source.master) + source.exceptions).associateBy { it["_id"]!!.toLong() }
        val removed = deletion.future.map { it.ref.eventId }
        if (!deletion.whole) operations += ContentProviderOperation.newUpdate(eventUri(source.snapshot.identity.masterEventId))
            .withValues(followingContext(source.master).apply { put("rrule", deletion.prefixRule) }).withExpectedCount(1).withYieldAllowed(false).build()
        val targets = if (deletion.whole) removed + source.snapshot.identity.masterEventId else listOf(source.snapshot.identity.masterEventId) + removed
        (if (deletion.whole) targets else removed).forEach { id ->
            operations += ContentProviderOperation.newDelete(eventUri(id)).withExpectedCount(1).withYieldAllowed(false).build()
            val row = allRows.getValue(id)
            if (row["_sync_id"].isNullOrBlank()) {
                operations += ContentProviderOperation.newAssertQuery(eventUri(id)).withExpectedCount(0).withYieldAllowed(false).build()
            } else {
                val tombstone = row.filterKeys { it in tombstoneIdentityColumns }.toMutableMap().apply { put("deleted", "1") }
                operations += assertRow(eventUri(id), tombstone)
            }
        }
        if (!deletion.whole) {
            val expected = source.master.filterKeys { it !in setOf("dirty", "mutators", "lastDate", "lastSynced") }.toMutableMap()
            expected["rrule"] = deletion.prefixRule
            operations += assertRow(eventUri(source.snapshot.identity.masterEventId), expected)
            val past = source.exceptions.filter { it["_id"]!!.toLong() !in removed }
            past.forEach { operations += assertRow(eventUri(it["_id"]!!.toLong()), it) }
            // Native deletion may remove only future children. All retained provider payload,
            // including unknown/OEM columns, must survive unchanged.
            val kept = past.map { it["_id"]!!.toLong() }.toSet() + source.snapshot.identity.masterEventId
            listOf(CalendarContract.Reminders.CONTENT_URI to source.reminders, CalendarContract.Attendees.CONTENT_URI to source.attendees,
                CalendarContract.ExtendedProperties.CONTENT_URI to source.extendedProperties).forEach { (uri, grouped) ->
                kept.forEach { id ->
                    val rows = grouped[id].orEmpty()
                    operations += ContentProviderOperation.newAssertQuery(uri).withSelection("event_id = ?", arrayOf(id.toString()))
                        .withExpectedCount(rows.size).withYieldAllowed(false).build()
                    rows.forEach { operations += assertRow(ContentUris.withAppendedId(uri, it["_id"]!!.toLong()), it) }
                }
            }
        }
        val receipt = JSONObject().put("version", 1).put("token", token.value).put("command", commandJson(command))
            .put("calendar", source.snapshot.identity.calendarId.value).put("master", source.snapshot.identity.masterEventId)
            .put("selected", refJson(source.snapshot.validatedSelection!!)).put("scope", RecurrenceScope.FOLLOWING.name)
            .put("deleted", true).put("created", false).put("following", true).put("whole", deletion.whole)
            .put("prefixRule", deletion.prefixRule ?: JSONObject.NULL).put("exDatesRaw", source.master["exdate"] ?: JSONObject.NULL)
            .put("namespace", namespace(source.calendar)).put("targets", JSONArray(targets))
            .put("syncIds", JSONObject().apply { targets.forEach { put(it.toString(), allRows.getValue(it)["_sync_id"]?.takeIf(String::isNotBlank) ?: JSONObject.NULL) } })
            .put("eventUids", JSONObject().apply { targets.forEach { put(it.toString(), allRows.getValue(it)["uid2445"]?.takeIf(String::isNotBlank)?.let(::hash) ?: JSONObject.NULL) } })
        return Prepared(operations, receipt, source)
    }

    private fun newTemplate(source: CalendarSeriesSource): ExceptionTemplate {
        if (source.snapshot.ruleRaw.isNullOrBlank()) throw Unsupported("The public exception API requires an RRULE master")
        val ref = source.snapshot.validatedSelection ?: throw SeriesSourceConflict()
        if (ref.eventId != source.snapshot.identity.masterEventId || ref.recurrence?.originalStart != ref.instanceStart) throw SeriesSourceConflict()
        val allDay = source.snapshot.start is RecurrenceStart.AllDay
        val observed = source.selectedDetails.occurrence.time
        if (allDay != (observed is EventTime.AllDay)) throw SeriesSourceReview("Observed occurrence time mode changed")
        val zone = source.master[CalendarContract.Events.EVENT_TIMEZONE]?.takeIf { it.isNotBlank() }
            ?: throw SeriesSourceReview("Occurrence zone is unavailable")
        val duration = try {
            require(ref.instanceStart.nano == 0 && ref.instanceEnd.nano == 0)
            require(ref.instanceStart.atOffset(ZoneOffset.UTC).year in 1..9999 && ref.instanceEnd.atOffset(ZoneOffset.UTC).year in 1..9999)
            val seconds = Math.subtractExact(ref.instanceEnd.epochSecond, ref.instanceStart.epochSecond)
            require(seconds in 1..Int.MAX_VALUE.toLong()) // Android Duration sums seconds in an int.
            require(Math.addExact(ref.instanceStart.toEpochMilli(), Math.multiplyExact(seconds, 1000)) == ref.instanceEnd.toEpochMilli())
            if (allDay) {
                val from = ref.instanceStart.atOffset(ZoneOffset.UTC); val until = ref.instanceEnd.atOffset(ZoneOffset.UTC)
                require(from.toLocalTime() == java.time.LocalTime.MIDNIGHT && until.toLocalTime() == java.time.LocalTime.MIDNIGHT)
                val rules = ZoneId.of(zone).rules
                require(rules.isFixedOffset && rules.getOffset(ref.instanceStart) == ZoneOffset.UTC)
                val days = ChronoUnit.DAYS.between(from.toLocalDate(), until.toLocalDate())
                require(days in 1..(Int.MAX_VALUE / 86_400).toLong())
                "P${days}D"
            } else "PT${seconds}S"
        } catch (_: Exception) { throw SeriesSourceReview("Observed occurrence bounds cannot be preserved safely by the public exception API") }
        val master = source.snapshot.identity.masterEventId
        listOf(source.reminders[master].orEmpty() to reminderCloneColumns,
            source.attendees[master].orEmpty() to attendeeCloneColumns,
            source.extendedProperties[master].orEmpty() to propertyCloneColumns).forEach { (rows, columns) ->
            if (rows.any { row -> row.any { (name, value) -> name !in columns && name !in setOf("_id", "event_id") && value != null } })
                throw SeriesSourceReview("Provider-specific child data has no verified clone policy")
        }
        if (source.master.any { (name, value) -> value != null && (name.startsWith("sync_") || name.startsWith("_sync_")) && name !in clearedSyncColumns })
            throw SeriesSourceReview("Provider-specific synchronization identity has no verified clone policy")
        return ExceptionTemplate(ref, allDay, zone, duration)
    }

    private fun insertException(operations: ArrayList<ContentProviderOperation>, source: CalendarSeriesSource, template: ExceptionTemplate,
        patch: EventPatch, cancelled: Boolean): Int {
        // Android 16 CalendarProvider 77bed8f9 handleInsertException forbids insert DTEND, clones
        // documented child columns and derives DTEND from Duration using int arithmetic. Preserve
        // observed bounds, not a guessed correction of nominal P1D semantics; the master stays raw.
        val authored = changes(source, source.master, patch)
        if (!cancelled && authored.size() == 0) throw Unsupported("No supported fields were changed")
        val recurrence = requireNotNull(template.ref.recurrence)
        val insert = ContentValues(authored).apply {
            put("originalInstanceTime", recurrence.originalStart.toEpochMilli())
            put("dtstart", template.ref.instanceStart.toEpochMilli()); put("duration", template.duration)
            put("eventTimezone", template.zone); put("allDay", if (template.allDay) 1 else 0)
            // Pinned provider handleInsertException defaults only when the key is absent, not
            // when it carries NULL. Preserve legitimate unspecified status without inventing one.
            if (cancelled) put("eventStatus", CalendarContract.Events.STATUS_CANCELED)
            else source.master["eventStatus"]?.let { put("eventStatus", it.toInt()) } ?: putNull("eventStatus")
        }
        val index = operations.size
        operations += ContentProviderOperation.newInsert(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_EXCEPTION_URI,
            source.snapshot.identity.masterEventId)).withValues(insert).withYieldAllowed(false).build()
        val context = ContentValues().apply {
            put("dtstart", template.ref.instanceStart.toEpochMilli()); put("dtend", template.ref.instanceEnd.toEpochMilli())
            put("eventTimezone", template.zone); put("allDay", if (template.allDay) 1 else 0)
            put("originalInstanceTime", recurrence.originalStart.toEpochMilli()); put("originalAllDay", if (template.allDay) 1 else 0)
        }
        val guard = "_id = ? AND calendar_id = ? AND original_id = ? AND originalInstanceTime = ? AND dtstart = ? AND originalAllDay = ?"
        val args = arrayOf("0", source.snapshot.identity.calendarId.value.toString(), source.snapshot.identity.masterEventId.toString(),
            recurrence.originalStart.toEpochMilli().toString(), template.ref.instanceStart.toEpochMilli().toString(), if (template.allDay) "1" else "0")
        // Repeating the full unchanged context seeds EventsRawTimes; a DTEND-only update would
        // replace it with incomplete UTC-default values (CalendarProvider updateEventRawTimesLocked).
        operations += ContentProviderOperation.newUpdate(CalendarContract.Events.CONTENT_URI).withValues(context).withSelection(guard, args)
            .withSelectionBackReference(0, index).withExpectedCount(1).withYieldAllowed(false).build()
        val alerts = (patch.alertMinutes as? FieldChange.Set)?.value
        if (alerts != null) {
            operations += ContentProviderOperation.newDelete(CalendarContract.Reminders.CONTENT_URI)
                .withSelection("event_id = ? AND method = ?", arrayOf("0", "1")).withSelectionBackReference(0, index).withYieldAllowed(false).build()
            alerts.forEach { minutes -> operations += ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI)
                .withValueBackReference("event_id", index).withValue("minutes", minutes).withValue("method", 1).withYieldAllowed(false).build() }
        }
        val expected = source.master.filterKeys { it !in transformedEventColumns && it !in clearedSyncColumns }.toMutableMap()
        authored.keySet().forEach { expected[it] = authored.get(it)?.toString() }
        context.keySet().forEach { expected[it] = context.get(it)?.toString() }
        expected["calendar_id"] = source.snapshot.identity.calendarId.value.toString()
        expected["original_id"] = source.snapshot.identity.masterEventId.toString()
        expected["original_sync_id"] = source.master["_sync_id"]
        expected["eventStatus"] = insert.getAsString("eventStatus")
        listOf("rrule", "rdate", "exrule", "exdate", "duration").forEach { expected[it] = null }
        clearedSyncColumns.forEach { expected[it] = null }
        operations += ContentProviderOperation.newAssertQuery(CalendarContract.Events.CONTENT_URI).withSelection("_id = ?", arrayOf("0"))
            .withSelectionBackReference(0, index).withValues(ContentValues().apply { expected.forEach { (key, value) -> if (value == null) putNull(key) else put(key, value) } })
            .withExpectedCount(1).withYieldAllowed(false).build()
        val masterId = source.snapshot.identity.masterEventId
        val reminderRows = source.reminders[masterId].orEmpty().map { row -> reminderCloneColumns.associateWith { row[it] } }.let { rows ->
            if (alerts == null) rows else rows.filter { it["method"] != "1" } + alerts.map { mapOf("minutes" to it.toString(), "method" to "1") }
        }
        assertClonedChildren(operations, CalendarContract.Reminders.CONTENT_URI, index, reminderRows)
        assertClonedChildren(operations, CalendarContract.Attendees.CONTENT_URI, index,
            source.attendees[masterId].orEmpty().map { row -> attendeeCloneColumns.associateWith { row[it] } })
        assertClonedChildren(operations, CalendarContract.ExtendedProperties.CONTENT_URI, index,
            source.extendedProperties[masterId].orEmpty().map { row -> propertyCloneColumns.associateWith { row[it] } })
        return index
    }

    private fun assertClonedChildren(operations: ArrayList<ContentProviderOperation>, uri: Uri, insert: Int, rows: List<Map<String, String?>>) {
        operations += ContentProviderOperation.newAssertQuery(uri).withSelection("event_id = ?", arrayOf("0"))
            .withSelectionBackReference(0, insert).withExpectedCount(rows.size).withYieldAllowed(false).build()
        rows.groupingBy { it }.eachCount().forEach { (row, count) ->
            val args = mutableListOf("0")
            val selection = "event_id = ?" + row.toSortedMap().entries.joinToString("") { (column, value) ->
                if (value == null) " AND $column IS NULL" else { args += value; " AND $column = ?" }
            }
            operations += ContentProviderOperation.newAssertQuery(uri).withSelection(selection, args.toTypedArray())
                .withSelectionBackReference(0, insert).withExpectedCount(count).withYieldAllowed(false).build()
        }
    }

    private fun nonTemporal(patch: EventPatch) {
        if (patch.time != FieldChange.Keep || patch.eventZone != FieldChange.Keep)
            throw Unsupported("Time and zone changes require a separately verified series executor")
    }
    private fun changes(source: CalendarSeriesSource, row: Map<String, String?>, patch: EventPatch): ContentValues {
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
        return changes
    }
    private fun edit(operations: ArrayList<ContentProviderOperation>, source: CalendarSeriesSource, row: Map<String, String?>, patch: EventPatch) {
        val id = row["_id"]!!.toLong()
        val changes = changes(source, row, patch)
        val alerts = (patch.alertMinutes as? FieldChange.Set)?.value
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
        listOf(CalendarContract.Reminders.CONTENT_URI to source.reminders, CalendarContract.Attendees.CONTENT_URI to source.attendees,
            CalendarContract.ExtendedProperties.CONTENT_URI to source.extendedProperties).forEach { (uri, grouped) ->
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

    override suspend fun recover(operationId: String): SeriesMutationResult = withContext(io + readBudgetContext(readBudgets.mutation)) { mutex.withLock {
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
        if (operation.kind != "SERIES_V1_${scope.name}_${if (deleted) "DELETE" else "EDIT"}") return uncertain()
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
        if (scope == RecurrenceScope.FOLLOWING) return recoverFollowing(operation, receipt, identity, selected, bound, boundRows, targets)
        if (receipt.optBoolean("created", false)) return recoverCreated(operation, receipt, identity, selected, scope, deleted, bound, boundRows, targets)
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

    private suspend fun recoverFollowing(operation: CalendarLocalStore.Operation, receipt: JSONObject, identity: SeriesIdentity, selected: EventRef,
        bound: JSONObject, boundRows: Map<Long, JSONObject>, targets: List<Long>): SeriesMutationResult {
        fun uncertain() = common(MutationResult.Uncertain(operation.id, identity.masterEventId))
        if (!receipt.optBoolean("following", false) || receipt.optBoolean("created", false) || !receipt.getBoolean("deleted")) return uncertain()
        val snapshot = sourceReader.frozenSnapshot(store.edit(receipt.getString("token")) ?: return uncertain())
        if (snapshot.identity != identity || snapshot.validatedSelection != selected) return uncertain()
        val planned = RecurrencePlanning.plan(snapshot, selected, RecurrenceScope.FOLLOWING, RecurrenceMutation.Delete, budget)
            as? RecurrencePlanningResult.Ready ?: return uncertain()
        val deletion = followingDelete(snapshot, planned)
        if (receipt.getBoolean("whole") != deletion.whole || receipt.nullable("prefixRule") != deletion.prefixRule ||
            receipt.nullable("exDatesRaw") != bound.getJSONObject("master").nullable("exdate")) return uncertain()
        val expectedTargets = deletion.future.map { it.ref.eventId }.toSet() + identity.masterEventId
        if (targets.toSet() != expectedTargets) return uncertain()
        for (id in targets) {
            val original = boundRows.getValue(id)
            if (receipt.getJSONObject("syncIds").nullable(id.toString()) != original.nullable("_sync_id")?.takeIf(String::isNotBlank) ||
                receipt.getJSONObject("eventUids").nullable(id.toString()) != original.nullable("uid2445")?.takeIf(String::isNotBlank)?.let(::hash)) return uncertain()
        }
        val current = mutableMapOf<Long, Map<String, String?>>()
        for (chunk in targets.chunked(200)) for (row in recoveryRows(chunk)) {
            val id = row["_id"]?.toLongOrNull() ?: return uncertain()
            if (id !in chunk || current.put(id, row) != null) return uncertain()
        }
        val deleted = if (deletion.whole) targets.toSet() else deletion.future.map { it.ref.eventId }.toSet()
        for (id in targets) {
            val row = current[id]
            if (row != null) {
                if (row["calendar_id"]?.toLongOrNull() != identity.calendarId.value) return uncertain()
                val sync = receipt.getJSONObject("syncIds").nullable(id.toString())
                val uid = receipt.getJSONObject("eventUids").nullable(id.toString())
                if (sync != null && !row["_sync_id"].isNullOrBlank() && row["_sync_id"] != sync) return uncertain()
                if (uid != null && !row["uid2445"].isNullOrBlank() && hash(row.getValue("uid2445")!!) != uid) return uncertain()
            }
            if (id in deleted) {
                if (row != null && row["deleted"] != "1") return uncertain()
                if (row != null) for (column in listOf("original_id", "original_sync_id", "originalInstanceTime", "originalAllDay"))
                    if (row[column] != boundRows.getValue(id).nullable(column)) return uncertain()
            } else {
                if (row == null || row["deleted"] != "0" || row["rrule"] != deletion.prefixRule || row["exdate"] != snapshot.exDatesRaw) return uncertain()
                for (column in listOf("dtstart", "dtend", "duration", "eventTimezone", "allDay"))
                    if (row[column] != boundRows.getValue(id).nullable(column)) return uncertain()
            }
        }
        ensureRead()
        store.mark(operation.id, "VERIFIED")
        sourceReader.refresh()
        return SeriesMutationResult.LocalDeleted(identity, RecurrenceScope.FOLLOWING, selected)
    }

    private suspend fun recoverCreated(operation: CalendarLocalStore.Operation, receipt: JSONObject, identity: SeriesIdentity, selected: EventRef,
        scope: RecurrenceScope, deleted: Boolean, bound: JSONObject, boundRows: Map<Long, JSONObject>, targets: List<Long>): SeriesMutationResult {
        fun uncertain() = common(MutationResult.Uncertain(operation.id, targets.singleOrNull()))
        if (scope != RecurrenceScope.THIS || selected.eventId != identity.masterEventId || targets.size != 1 || targets.single() in boundRows ||
            selected.recurrence?.originalStart != selected.instanceStart || !bound.has("extendedProperties")) return uncertain()
        val original = requireNotNull(selected.recurrence).originalStart.toEpochMilli()
        if (boundRows.values.any { it.nullable("originalInstanceTime")?.toLongOrNull() == original }) return uncertain()
        val master = boundRows.getValue(identity.masterEventId)
        if (master.nullable("rrule").isNullOrBlank()) return uncertain()
        val allDay = receipt.getBoolean("createdAllDay")
        if (master.nullable("allDay") != if (allDay) "1" else "0") return uncertain()
        val zone = receipt.getString("createdZone")
        if (zone != master.nullable("eventTimezone")) return uncertain()
        val ref = parseRef(receipt.getJSONObject("resultSelected"))
        if (ref.eventId != targets.single() || ref.copy(eventId = selected.eventId) != selected) return uncertain()
        val queried = recoveryRows(listOf(identity.masterEventId, ref.eventId))
        if (queried.size != 2 || queried.map { it["_id"] }.toSet().size != 2) return uncertain()
        val byId = queried.associateBy { it["_id"]?.toLongOrNull() }
        val parent = byId[identity.masterEventId] ?: return uncertain()
        val event = byId[ref.eventId] ?: return uncertain()
        if (listOf(parent, event).any { it["calendar_id"]?.toLongOrNull() != identity.calendarId.value || it["deleted"] != "0" }) return uncertain()
        fun changed(expected: String?, current: String?) = !expected.isNullOrBlank() && !current.isNullOrBlank() && expected != current
        if (changed(master.nullable("_sync_id"), parent["_sync_id"]) || changed(master.nullable("uid2445"), parent["uid2445"])) return uncertain()
        val numeric = event["original_id"]?.toLongOrNull()
        val sync = event["original_sync_id"]?.takeIf(String::isNotBlank)
        val parentSync = parent["_sync_id"]?.takeIf(String::isNotBlank) ?: master.nullable("_sync_id")?.takeIf(String::isNotBlank)
        if ((!event["original_id"].isNullOrBlank() && numeric == null) || (numeric != null && numeric != identity.masterEventId) ||
            (sync != null && sync != parentSync) || (numeric == null && (sync == null || sync != parentSync))) return uncertain()
        if (event["originalInstanceTime"]?.toLongOrNull() != original || event["originalAllDay"] != if (allDay) "1" else "0") return uncertain()
        if (event["allDay"] != if (allDay) "1" else "0") return uncertain()
        if (event["dtstart"]?.toLongOrNull() != ref.instanceStart.toEpochMilli() || event["dtend"]?.toLongOrNull() != ref.instanceEnd.toEpochMilli() ||
            event["eventTimezone"] != zone) return uncertain()
        val expectedStatus = if (deleted) CalendarContract.Events.STATUS_CANCELED else master.nullable("eventStatus")?.toIntOrNull()
        val status = event["eventStatus"]?.toIntOrNull()
        val parentStatus = parent["eventStatus"]?.toIntOrNull()
        if ((event["eventStatus"] != null && status == null) || (parent["eventStatus"] != null && parentStatus !in setOf(0, 1)) || status != expectedStatus) return uncertain()
        if (changed(receipt.nullable("createdSync"), event["_sync_id"]) || changed(receipt.nullable("createdUid"), event["uid2445"]?.takeIf(String::isNotBlank)?.let(::hash))) return uncertain()
        val current = if (deleted) null else (sourceReader.selectedReference(ref) ?: return uncertain())
        ensureRead()
        receipt.put("createdSync", receipt.nullable("createdSync") ?: event["_sync_id"]?.takeIf(String::isNotBlank) ?: JSONObject.NULL)
            .put("createdUid", receipt.nullable("createdUid") ?: event["uid2445"]?.takeIf(String::isNotBlank)?.let(::hash) ?: JSONObject.NULL)
        store.mark(operation.id, "VERIFIED", receipt.toString())
        sourceReader.refresh()
        return if (deleted) SeriesMutationResult.LocalDeleted(identity, scope, selected) else SeriesMutationResult.LocalSaved(identity, current)
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
        return cursor.bounded(readBudgets.mutation.maxRows).use { buildList { while (it.moveToNext()) { currentCoroutineContext().ensureActive(); add(fields.associateWith { field ->
            val index = it.getColumnIndexOrThrow(field); if (it.isNull(index)) null else it.getString(index)
        }) } } }
    }

    companion object {
        private val tombstoneIdentityColumns = setOf("_id", "calendar_id", "_sync_id", "uid2445", "original_id", "original_sync_id", "originalInstanceTime", "originalAllDay")
        private val reminderCloneColumns = setOf("minutes", "method")
        private val attendeeCloneColumns = setOf("attendeeName", "attendeeEmail", "attendeeStatus", "attendeeRelationship", "attendeeType", "attendeeIdentity", "attendeeIdNamespace")
        private val propertyCloneColumns = setOf("name", "value")
        private val clearedSyncColumns = setOf("_sync_id") + (1..10).map { "sync_data$it" }
        private val transformedEventColumns = setOf("_id", "dtstart", "dtend", "duration", "rrule", "rdate", "exrule", "exdate", "original_id", "original_sync_id",
            "originalInstanceTime", "originalAllDay", "lastDate", "dirty", "mutators", "lastSynced")
        private val kinds = setOf("SERIES_V1_ALL_EDIT", "SERIES_V1_THIS_EDIT", "SERIES_V1_ALL_DELETE", "SERIES_V1_THIS_DELETE", "SERIES_V1_FOLLOWING_DELETE")
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
        private fun patchJson(patch: EventPatch): JSONObject {
            checkPatchInput(patch, 1024 * 1024)
            return JSONObject().apply {
            fun <T> change(name: String, value: FieldChange<T>, encode: (T) -> Any?) { if (value is FieldChange.Set) put(name, encode(value.value) ?: JSONObject.NULL) }
            change("title", patch.title) { it }; change("location", patch.location) { it }; change("description", patch.description) { it }
            change("alerts", patch.alertMinutes) { JSONArray(it) }; change("availability", patch.availability) { it }; change("privacy", patch.privacy) { it }
            change("zone", patch.eventZone) { it?.id }; change("time", patch.time) { when (it) {
                is EventTime.Timed -> JSONObject().put("start", it.start.toString()).put("end", it.end.toString())
                is EventTime.AllDay -> JSONObject().put("startDate", it.dates.start.toString()).put("endDate", it.dates.endExclusive.toString())
            } }
        }
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
        private val recoveryFields = arrayOf("_id", "calendar_id", "deleted", "eventStatus", "_sync_id", CalendarContract.Events.UID_2445,
            "original_id", "original_sync_id", "originalInstanceTime", "originalAllDay", "dtstart", "dtend", "allDay", "eventTimezone", "duration", "rrule", "exdate")
    }
}
