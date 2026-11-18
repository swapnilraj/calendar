package dev.swapnilraj.calendar.android.navigation

import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.*
import java.time.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

data class CalendarSeedReviewEnvironment(
    val calendars: List<CalendarInfo>, val displayZone: ZoneId, val use24Hour: Boolean,
    val defaultCalendar: CalendarIdentity? = null,
    val timedAlertMinutes: List<Int> = emptyList(), val allDayAlertMinutes: List<Int> = emptyList(),
    val recurringCreationSupported: Boolean = false, val calendarReadGranted: Boolean = true,
)

interface CalendarSeedReviewOwner { val calendarSeedReviewServices: CalendarSeedReviewServices }

@ConsistentCopyVisibility
data class CalendarSeedReviewSnapshot internal constructor(
    val request: CalendarSeedReviewRequest, val generation: String, val fields: CalendarSeedReviewFields,
    internal val record: DraftRecord,
)

sealed interface CalendarSeedReviewResult {
    data class Review(val snapshot: CalendarSeedReviewSnapshot, val environment: CalendarSeedReviewEnvironment, val problem: String? = null,
        val unverifiedInput: Boolean = false) : CalendarSeedReviewResult
    data class Editor(val launch: EditorDraftLaunch, val environment: CalendarSeedReviewEnvironment) : CalendarSeedReviewResult
    data class NeedsMeetingEditor(val snapshot: CalendarSeedReviewSnapshot) : CalendarSeedReviewResult
    data object PermissionRequired : CalendarSeedReviewResult
    data object Stale : CalendarSeedReviewResult
    data object Unavailable : CalendarSeedReviewResult
}

/** Root supplies the real atomic store, mutation/retirement ports and fresh validated settings. */
class CalendarSeedReviewServices(
    val drafts: AtomicDraftStore, val mutations: CalendarMutations, val retirement: OperationRetirement,
    val readEnvironment: suspend () -> CalendarReadState<CalendarSeedReviewEnvironment>,
    val changes: Flow<Unit>, private val clock: Clock = Clock.systemUTC(),
) {
    /** Existing ordinary raw/frozen state always wins over the original inbound seed. */
    suspend fun open(request: CalendarSeedReviewRequest, restoreGeneration: String? = null, restoreOnly: Boolean = false,
        calendarReadGranted: Boolean = true): CalendarSeedReviewResult = guarded {
        require(CalendarSeedReviewCodec.decode(CalendarSeedReviewCodec.encode(request)) == request)
        val environment = when (val state = readEnvironment()) {
            CalendarReadState.PermissionRequired -> return@guarded CalendarSeedReviewResult.PermissionRequired
            is CalendarReadState.Ready -> scopedEnvironment(state.value, calendarReadGranted)
            else -> return@guarded CalendarSeedReviewResult.Unavailable
        }
        ordinary(request.draftId)?.let {
            if (!environment.calendarReadGranted && !it.needsRecovery) return@guarded CalendarSeedReviewResult.PermissionRequired
            return@guarded exposeEditor(request, it, environment, expectedGeneration = restoreGeneration)
        }
        if (!environment.calendarReadGranted) return@guarded CalendarSeedReviewResult.PermissionRequired
        val id = "incoming-review:${request.sessionId}"
        val existing = drafts.load(id)
        if (existing != null && isCalendarSeedHandoffReceipt(existing)) return@guarded CalendarSeedReviewResult.Stale
        val record = if (existing != null) existing else {
            if (restoreGeneration != null || restoreOnly) return@guarded CalendarSeedReviewResult.Stale
            val initial = CalendarSeedCompletionDocument(request, UUID.randomUUID().toString(), UUID.randomUUID().toString(), initialFields(request, environment))
            val candidate = initial.record(clock.instant())
            val outcome = change(id, null, candidate)
            if (outcome == DraftChangeResult.Changed || drafts.load(id) != candidate) return@guarded CalendarSeedReviewResult.Unavailable
            candidate
        }
        val doc = CalendarSeedCompletionDocument.decode(record)
        if (doc.request != request || restoreGeneration != null && restoreGeneration != doc.generation) return@guarded CalendarSeedReviewResult.Stale
        val snapshot = snapshot(doc, record)
        if (request.seed.guests.isNotEmpty()) CalendarSeedReviewResult.NeedsMeetingEditor(snapshot)
        else prepare(snapshot, environment, explicitContinue = false)
    }

    internal suspend fun resumeEditor(id: String, generation: String, calendarReadGranted: Boolean = true): CalendarSeedReviewResult = guarded {
        require(id.isNotBlank() && id.length <= 4096 && UUID.fromString(generation).toString() == generation)
        val environment = when (val state = readEnvironment()) {
            CalendarReadState.PermissionRequired -> return@guarded CalendarSeedReviewResult.PermissionRequired
            is CalendarReadState.Ready -> scopedEnvironment(state.value, calendarReadGranted)
            else -> return@guarded CalendarSeedReviewResult.Unavailable
        }
        val launch = ordinary(id) ?: return@guarded CalendarSeedReviewResult.Stale
        if (!environment.calendarReadGranted && !launch.needsRecovery) return@guarded CalendarSeedReviewResult.PermissionRequired
        if (launch.generation != generation) return@guarded CalendarSeedReviewResult.Stale
        if (id.startsWith("incoming:")) {
            val record = drafts.load("incoming-review:${id.removePrefix("incoming:")}") ?: return@guarded CalendarSeedReviewResult.Unavailable
            val receipt = CalendarSeedHandoffReceipt.decode(record)
            if (receipt.nativeGeneration != generation) return@guarded CalendarSeedReviewResult.Stale
        }
        CalendarSeedReviewResult.Editor(launch, environment)
    }

    /** Exact revision CAS; neither a new process nor an unavailable ACK can overwrite newer input. */
    suspend fun update(snapshot: CalendarSeedReviewSnapshot, fields: CalendarSeedReviewFields): CalendarSeedReviewSnapshot? = try {
        val current = CalendarSeedCompletionDocument.decode(snapshot.record)
        require(current.generation == snapshot.generation && current.request == snapshot.request)
        require(current.preparedFields == null)
        val next = current.copy(revision = UUID.randomUUID().toString(), fields = fields)
        val candidate = next.record(clock.instant())
        CalendarSeedCompletionDocument.decode(candidate)
        val outcome = change(candidate.id, snapshot.record, candidate)
        if (outcome != DraftChangeResult.Changed && drafts.load(candidate.id) == candidate) snapshot(next, candidate) else null
    } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }

    suspend fun prepare(snapshot: CalendarSeedReviewSnapshot, authorized: CalendarSeedReviewEnvironment,
        explicitContinue: Boolean = true): CalendarSeedReviewResult = guarded {
        if (!authorized.calendarReadGranted) return@guarded CalendarSeedReviewResult.PermissionRequired
        ordinary(snapshot.request.draftId)?.let { launch ->
            val latest = when (val state = readEnvironment()) {
                CalendarReadState.PermissionRequired -> return@guarded CalendarSeedReviewResult.PermissionRequired
                is CalendarReadState.Ready -> scopedEnvironment(state.value, true)
                else -> return@guarded CalendarSeedReviewResult.Unavailable
            }
            if (!latest.calendarReadGranted && !launch.needsRecovery) return@guarded CalendarSeedReviewResult.PermissionRequired
            return@guarded exposeEditor(snapshot.request, launch, latest, expectedGeneration = snapshot.generation)
        }
        val current = drafts.load(snapshot.record.id)
        if (current != snapshot.record) return@guarded CalendarSeedReviewResult.Stale
        if (snapshot.request.seed.guests.isNotEmpty()) return@guarded CalendarSeedReviewResult.NeedsMeetingEditor(snapshot)
        val document = CalendarSeedCompletionDocument.decode(snapshot.record)
        val normalized = try { document.preparedFields?.let { payload ->
            val fields = EventEditorController.readRawFields(JSONObject(payload))
            require(EventEditorController.encodeRawFields(fields).toString() == payload)
            EventEditorController.normalizedDraft(fields.copy(calendarId = fields.calendarId.ifBlank { "0" })) to fields
        } ?: normalizedFields(snapshot, authorized) } catch (_: Exception) {
            return@guarded CalendarSeedReviewResult.Review(snapshot, authorized, if (explicitContinue) "Complete the dates, times and supported repeat rule before continuing." else null)
        }
        if (!explicitContinue && document.preparedFields == null && snapshot.fields != initialFields(snapshot.request, authorized)) {
            // A retained completion form is reviewed explicitly, even when its latest inputs are valid.
            return@guarded CalendarSeedReviewResult.Review(snapshot, authorized)
        }
        val fresh = when (val state = readEnvironment()) {
            CalendarReadState.PermissionRequired -> return@guarded CalendarSeedReviewResult.PermissionRequired
            is CalendarReadState.Ready -> state.value
            else -> return@guarded CalendarSeedReviewResult.Unavailable
        }
        if (!fresh.calendarReadGranted) return@guarded CalendarSeedReviewResult.PermissionRequired
        if (document.preparedFields == null && !sameAuthority(authorized, fresh, normalized.first.targetIdentity) ||
            !selectedAllowed(fresh, normalized.first.targetIdentity)) return@guarded CalendarSeedReviewResult.Stale
        val handoffRecord = if (document.preparedFields != null) snapshot.record else {
            val candidate = document.copy(revision = UUID.randomUUID().toString(), preparedFields = EventEditorController.encodeRawFields(normalized.second).toString()).record(clock.instant())
            val result = change(candidate.id, snapshot.record, candidate)
            if (result == DraftChangeResult.Changed || drafts.load(candidate.id) != candidate) return@guarded CalendarSeedReviewResult.Stale
            candidate
        }
        val result = prepareEditorFieldsDraft(normalized.first, null, snapshot.request.draftId,
            normalized.first.targetIdentity != null, normalized.second, drafts, clock)
        val launch = (result as? DraftPreparationResult.Ready)?.launch ?: return@guarded CalendarSeedReviewResult.Unavailable
        require(launch.draftId == snapshot.request.draftId && launch.ref == null && launch.generation != null)
        // The permanent receipt must commit before the native editor can expose Save.
        exposeEditor(snapshot.request, launch, fresh, handoffRecord, snapshot.generation)
    }

    private suspend fun exposeEditor(request: CalendarSeedReviewRequest, launch: EditorDraftLaunch,
        environment: CalendarSeedReviewEnvironment, knownRecord: DraftRecord? = null, expectedGeneration: String? = null): CalendarSeedReviewResult {
        val id = "incoming-review:${request.sessionId}"
        val record = knownRecord ?: drafts.load(id) ?: return CalendarSeedReviewResult.Unavailable
        if (isCalendarSeedHandoffReceipt(record)) {
            val receipt = CalendarSeedHandoffReceipt.decode(record)
            return if (receipt.session == request.sessionId && receipt.nativeGeneration == launch.generation &&
                (expectedGeneration == null || expectedGeneration == receipt.generation))
                CalendarSeedReviewResult.Editor(launch, environment) else CalendarSeedReviewResult.Stale
        }
        val doc = CalendarSeedCompletionDocument.decode(record)
        if (expectedGeneration != null && doc.generation != expectedGeneration) return CalendarSeedReviewResult.Stale
        require(doc.request == request && doc.preparedFields != null)
        val receipt = CalendarSeedHandoffReceipt(request.sessionId, doc.generation, requireNotNull(launch.generation)).record(clock.instant())
        val outcome = change(id, record, receipt)
        return if (outcome != DraftChangeResult.Changed && drafts.load(id) == receipt) CalendarSeedReviewResult.Editor(launch, environment)
            else CalendarSeedReviewResult.Unavailable
    }

    private suspend fun ordinary(id: String): EditorDraftLaunch? {
        val record = drafts.load(id) ?: return null
        val doc = OrdinaryDraftDocument.decode(record)
        require(!doc.legacy && doc.launch.kind == EditorDraftKind.CREATE && doc.launch.ref == null && doc.generation != null)
        return doc.launch
    }

    private fun sameAuthority(old: CalendarSeedReviewEnvironment, fresh: CalendarSeedReviewEnvironment, selected: CalendarIdentity?): Boolean =
        old.displayZone == fresh.displayZone && old.defaultCalendar == fresh.defaultCalendar &&
            old.timedAlertMinutes == fresh.timedAlertMinutes && old.allDayAlertMinutes == fresh.allDayAlertMinutes &&
            old.recurringCreationSupported == fresh.recurringCreationSupported && (selected == null ||
            fresh.calendars.singleOrNull { it.id == selected.calendarId }?.let { it.identity == selected && it.capabilities.createEvents == AccessDecision.ALLOWED } == true)

    private fun selectedAllowed(environment: CalendarSeedReviewEnvironment, selected: CalendarIdentity?): Boolean = selected == null ||
        selected.account.name.isNotBlank() && selected.account.type.isNotBlank() && environment.calendars.singleOrNull { it.id == selected.calendarId }
            ?.let { it.identity == selected && it.capabilities.createEvents == AccessDecision.ALLOWED } == true

    private fun scopedEnvironment(environment: CalendarSeedReviewEnvironment, platformRead: Boolean) =
        if (environment.calendarReadGranted && platformRead) environment else environment.copy(calendars = emptyList(), defaultCalendar = null, calendarReadGranted = false)

    private fun normalizedFields(snapshot: CalendarSeedReviewSnapshot, environment: CalendarSeedReviewEnvironment): Pair<EventDraft, EventEditorFields> {
        val raw = snapshot.fields; val seed = snapshot.request.seed
        val destination = environment.defaultCalendar?.takeIf { pin -> pin.account.name.isNotBlank() && pin.account.type.isNotBlank() &&
            environment.calendars.singleOrNull { it.id == pin.calendarId }?.let { it.identity == pin && it.capabilities.createEvents == AccessDecision.ALLOWED } == true }
        val date = LocalDate.parse(raw.startDate); require(date.year in 1..9999)
        val end = LocalDate.parse(raw.endDate); require(end.year in 1..9999)
        val zone = ZoneId.of(raw.zone)
        val recurrence = if (raw.rule.isEmpty()) null else {
            require(environment.recurringCreationSupported)
            val start = if (raw.allDay) RecurrenceStart.AllDay(date) else RecurrenceStart.Timed(date.atTime(LocalTime.parse(raw.startTime)), zone,
                raw.startOffset.takeIf(String::isNotBlank)?.let(ZoneOffset::of))
            (RecurrenceRules.parse(raw.rule, start) as? ParsedRecurrence.Supported)?.spec ?: error("Repeat rule requires review")
        }
        val recurrenceFields = RecurrenceEditorFields.from(recurrence) // Imported exact UNTIL remains review-only, never shortened.
        val alerts = if (raw.allDay) environment.allDayAlertMinutes else environment.timedAlertMinutes
        require(alerts.none { it == -1 })
        val fields = EventEditorFields(destination?.calendarId?.value?.toString().orEmpty(), seed.title.orEmpty(), raw.startDate, raw.endDate,
            raw.startTime, raw.endTime, raw.allDay, if (raw.allDay) ZoneOffset.UTC.id else raw.zone, seed.location.orEmpty(), seed.description.orEmpty(),
            alerts.joinToString(","), raw.startOffset, raw.endOffset, seed.availability?.toString().orEmpty(), seed.accessLevel?.toString().orEmpty(),
            if (seed.accessLevel == 0) destination?.calendarId?.value?.toString().orEmpty() else "", destination,
            if (seed.accessLevel == 0) destination else null, recurrenceFields)
        val normalized = EventEditorController.normalizedDraft(fields.copy(calendarId = destination?.calendarId?.value?.toString() ?: "0"))
        return normalized to fields
    }

    private suspend fun change(id: String, old: DraftRecord?, next: DraftRecord?): DraftChangeResult = try { drafts.compareAndSet(id, old, next) }
        catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { DraftChangeResult.Unavailable }
    private suspend fun guarded(block: suspend () -> CalendarSeedReviewResult): CalendarSeedReviewResult = try { block() }
        catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { CalendarSeedReviewResult.Unavailable }
    private fun snapshot(doc: CalendarSeedCompletionDocument, record: DraftRecord) = CalendarSeedReviewSnapshot(doc.request, doc.generation, doc.fields, record)

    companion object {
        internal fun initialFields(request: CalendarSeedReviewRequest, environment: CalendarSeedReviewEnvironment): CalendarSeedReviewFields {
            val seed = request.seed; val allDay = seed.occurrence.allDay == true
            val zone = if (allDay) ZoneOffset.UTC else seed.timeZone ?: environment.displayZone
            val start = seed.occurrence.begin?.atZone(zone); val end = seed.occurrence.end?.atZone(zone)
            return CalendarSeedReviewFields(start?.toLocalDate()?.toString().orEmpty(),
                end?.toLocalDate()?.let { if (allDay) it.minusDays(1) else it }?.toString().orEmpty(),
                if (allDay) "" else start?.toLocalTime()?.toString().orEmpty(), if (allDay) "" else end?.toLocalTime()?.toString().orEmpty(),
                allDay, zone.id, seed.recurrenceRule.orEmpty(), start?.offset?.id.orEmpty(), end?.offset?.id.orEmpty())
        }
    }
}
