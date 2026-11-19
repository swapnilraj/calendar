package dev.swapnilraj.calendar

import android.app.Application
import dev.swapnilraj.calendar.android.navigation.*
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.calendar.EventNavigationInfo
import dev.swapnilraj.calendar.ui.events.*
import java.time.*
import kotlinx.coroutines.flow.MutableSharedFlow

class CalendarEditReviewTestApplication : Application(), CalendarEditReviewOwner {
    override lateinit var calendarEditReviewServices: CalendarEditReviewServices
}

internal class EditReviewStore : AtomicDraftStore {
    val rows = linkedMapOf<String, DraftRecord>()
    var unavailableAck = false; var failNative = false; var failReceipt = false; var hideReceipt = false
    var beforeChange: (suspend (String, DraftRecord?, DraftRecord?) -> Unit)? = null
    var afterChange: (suspend (String, DraftRecord?, DraftRecord?) -> Unit)? = null
    var beforeLoad: (suspend (String) -> Unit)? = null
    override suspend fun load(id: String): DraftRecord? {
        beforeLoad?.invoke(id)
        return rows[id]?.takeUnless { hideReceipt && isCalendarEditHandoffReceipt(it) }
    }
    override suspend fun list() = rows.values.toList()
    override suspend fun upsert(record: DraftRecord) = error("No unconditional review upserts")
    override suspend fun delete(id: String) = error("No unconditional review deletion")
    override suspend fun compareAndSet(id: String, expected: DraftRecord?, replacement: DraftRecord?): DraftChangeResult {
        beforeChange?.invoke(id, expected, replacement)
        if (failNative && id.startsWith("incoming-edit:")) return DraftChangeResult.Unavailable
        if (failReceipt && replacement?.let(::isCalendarEditHandoffReceipt) == true) return DraftChangeResult.Unavailable
        if (rows[id] != expected) return DraftChangeResult.Changed
        if (replacement == null) rows.remove(id) else rows[id] = replacement
        afterChange?.invoke(id, expected, replacement)
        return if (unavailableAck) DraftChangeResult.Unavailable else DraftChangeResult.Applied
    }
}

internal class EditReviewFixture {
    val start = Instant.parse("2028-06-15T09:00:00Z")
    val calendar = CalendarInfo(CalendarId(3), "Synthetic owned calendar", 123, CalendarAccount("owner@example.invalid", "com.synthetic"),
        "owner@example.invalid", ZoneOffset.UTC, 700, false, true,
        CalendarCapabilities(readDetails = AccessDecision.ALLOWED, modifyEvents = AccessDecision.ALLOWED, createEvents = AccessDecision.ALLOWED), "calendar-A")
    val ref = EventRef(calendar.id, 7, start, start.plusSeconds(3600))
    var info = EventNavigationInfo(7, calendar.identity, "event-A", false, ref.instanceStart, ref.instanceEnd, false)
    var editable = EditableEvent(EventDetails(EventOccurrence(ref, EventTime.Timed(ref.instanceStart, ref.instanceEnd), "Original synthetic event", 123), calendar,
        location = "Original room", description = "Original notes", organizer = calendar.account.name, eventZone = ZoneOffset.UTC,
        reminders = listOf(EventReminder(10, 1)), canEdit = AccessDecision.ALLOWED, availability = 0, privacy = 2, isOrganizer = true),
        EditToken("original-source-token"), EditableField.entries.toSet())
    var environment: CalendarReadState<CalendarEditReviewEnvironment> = CalendarReadState.Ready(CalendarEditReviewEnvironment(listOf(calendar), ZoneOffset.UTC, true))
    val store = EditReviewStore()
    val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    var infoReads = 0; var editReads = 0; var writes = 0; var recoveries = 0
    val patches = mutableListOf<EventPatch>()
    var onInfo: (suspend () -> Unit)? = null
    var onEditRead: (suspend () -> Unit)? = null
    var onUpdated: (suspend () -> Unit)? = null
    var onRecover: (suspend (String) -> MutationResult)? = null
    var editFailure: EditReadResult? = null
    var infoFailure: CalendarReadState<EventNavigationInfo?>? = null
    var onEnvironment: (() -> Unit)? = null
    val source = object : IncomingCalendarSource {
        override suspend fun readEventInfo(eventId: Long): CalendarReadState<EventNavigationInfo?> { infoReads++; onInfo?.invoke(); return infoFailure ?: CalendarReadState.Ready(info) }
        override suspend fun readSnapshot(request: CalendarRequest): CalendarReadState<CalendarSnapshot> = error("No second occurrence engine")
        override suspend fun readDetails(ref: EventRef): CalendarReadState<EventDetails> = error("Backend edit snapshot owns details")
    }
    val mutations = object : CalendarMutations {
        override suspend fun loadForEdit(ref: EventRef): EditReadResult {
            editReads++; onEditRead?.invoke()
            return editFailure ?: if (ref == editable.details.occurrence.ref) EditReadResult.Ready(editable) else EditReadResult.Unavailable
        }
        override suspend fun create(operationId: String, draft: EventDraft): MutationResult = error("An EDIT must never create")
        override suspend fun update(operationId: String, ref: EventRef, token: EditToken, patch: EventPatch): MutationResult {
            writes++; patches += patch
            if (token != editable.token) return MutationResult.Conflict(editable.details)
            fun <T> changed(change: FieldChange<T>, old: T): T = when (change) { FieldChange.Keep -> old; is FieldChange.Set -> change.value }
            val details = editable.details; val time = changed(patch.time, details.occurrence.time)
            val bounds = when (time) {
                is EventTime.Timed -> time.start to time.end
                is EventTime.AllDay -> time.dates.start.atStartOfDay(ZoneOffset.UTC).toInstant() to time.dates.endExclusive.atStartOfDay(ZoneOffset.UTC).toInstant()
            }
            val savedRef = EventRef(ref.calendarId, ref.eventId, bounds.first, bounds.second)
            editable = editable.copy(details = details.copy(occurrence = details.occurrence.copy(ref = savedRef, time = time,
                title = changed(patch.title, details.occurrence.title)), location = changed(patch.location, details.location), description = changed(patch.description, details.description),
                eventZone = changed(patch.eventZone, details.eventZone)), token = EditToken("after-update-token"))
            info = info.copy(start = savedRef.instanceStart, endExclusive = savedRef.instanceEnd, allDay = time is EventTime.AllDay)
            onUpdated?.invoke()
            return MutationResult.LocalSaved(savedRef)
        }
        override suspend fun delete(operationId: String, ref: EventRef, token: EditToken): MutationResult { writes++; return MutationResult.LocalDeleted(ref) }
        override suspend fun recover(operationId: String): MutationResult { recoveries++; return onRecover?.invoke(operationId) ?: MutationResult.Uncertain(operationId, 7) }
    }
    val retirement = object : OperationRetirement {
        override suspend fun retire(request: RetirableOperation) = OperationRetirementResult.Retired(request.operationId)
        override suspend fun inspect(request: RetirableOperation) = OperationRetirementResult.NotRetired(request.operationId)
    }
    fun request(seed: CalendarDraftSeed = seed(), zone: ZoneId = ZoneOffset.UTC) = CalendarEditReviewRequest("dd1402dc-a358-4bbd-a656-49f154e31197", ref,
        CalendarEntryBinding(7, calendar.identity, "event-A"), seed, zone)
    fun seed() = CalendarDraftSeed(CalendarOccurrenceHints(null, null, null), "Proposed synthetic title", null, null, emptyList(), null, null, null, null)
    fun services(storage: AtomicDraftStore = store) = CalendarEditReviewServices(storage, mutations, retirement, source, { onEnvironment?.invoke(); environment }, changes,
        Clock.fixed(Instant.parse("2028-06-01T00:00:00.123456789Z"), ZoneOffset.UTC))
    fun freeze(request: CalendarEditReviewRequest, patch: EventPatch = EventPatch(title = FieldChange.Set("Frozen proposed title"))) {
        val record = requireNotNull(store.rows[request.nativeId]); val doc = OrdinaryDraftDocument.decode(record)
        val fields = doc.fields.copy(title = (patch.title as? FieldChange.Set)?.value ?: doc.fields.title)
        val command = FrozenEditorSubmission.Update(doc.operationId, request.selected, EditToken(doc.sourceToken), patch)
        store.rows[record.id] = doc.copy(fields = fields, frozen = command, submitted = true, operationKind = command.kind,
            operationPayload = EventEditorController.encodeRawFields(fields).toString()).record(Instant.parse("2028-06-01T00:00:01Z"))
    }
}
