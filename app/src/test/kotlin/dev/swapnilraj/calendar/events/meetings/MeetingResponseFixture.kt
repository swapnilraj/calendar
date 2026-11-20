package dev.swapnilraj.calendar.events.meetings

import android.provider.CalendarContract
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.meetings.*
import java.time.*
import kotlinx.coroutines.*

internal class AtomicMeetingStore : AtomicDraftStore {
    val rows = mutableMapOf<String, DraftRecord>()
    var legacyCalls = 0; var compares = 0; var unavailableBefore = false; var unavailableAfter = false; var failRead = false
    var beforeCompare: suspend (DraftRecord?, DraftRecord?) -> Unit = { _, _ -> }
    var afterCompare: suspend (DraftRecord?, DraftRecord?, DraftChangeResult) -> Unit = { _, _, _ -> }
    var beforeLoad: suspend (String) -> Unit = {}
    override suspend fun load(id: String): DraftRecord? { beforeLoad(id); if (failRead) error("fixture read unavailable"); return rows[id] }
    override suspend fun list() = rows.values.toList()
    override suspend fun upsert(record: DraftRecord) { legacyCalls++; error("blind writes forbidden") }
    override suspend fun delete(id: String) { legacyCalls++; error("blind deletes forbidden") }
    override suspend fun compareAndSet(id: String, expected: DraftRecord?, replacement: DraftRecord?): DraftChangeResult {
        compares++; beforeCompare(expected, replacement)
        if (unavailableBefore) return DraftChangeResult.Unavailable
        val result = synchronized(rows) {
            require(expected != null || replacement != null)
            require(expected == null || expected.id == id); require(replacement == null || replacement.id == id)
            if (rows[id] != expected) DraftChangeResult.Changed
            else { if (replacement == null) rows.remove(id) else rows[id] = replacement; DraftChangeResult.Applied }
        }
        afterCompare(expected, replacement, result)
        return if (unavailableAfter && result == DraftChangeResult.Applied) DraftChangeResult.Unavailable else result
    }
}

internal class MeetingResponseFixture {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val clock = Clock.fixed(Instant.parse("2026-11-18T00:00:00Z"), ZoneOffset.UTC)
    val start = Instant.parse("2028-06-15T09:00:00Z")
    val selected = EventRef(CalendarId(2), 42, start, start.plusSeconds(3600))
    val source = EditableMeeting(EventDetails(EventOccurrence(selected, EventTime.Timed(start, start.plusSeconds(3600)), "Planning invitation", 1),
        CalendarInfo(CalendarId(2), "Work", 1, CalendarAccount("self@example.test", "Fixture"), "self@example.test", ZoneOffset.UTC, 500, true, true),
        organizer = "host@example.test", eventZone = ZoneOffset.UTC, selfAttendeeStatus = CalendarContract.Attendees.ATTENDEE_STATUS_INVITED),
        EditToken("meeting-source-token"), mapOf(RecurrenceScope.THIS to MeetingScopeAccess(respond = AccessDecision.ALLOWED)))
    val store = AtomicMeetingStore()
    data class Call(val id: String, val selected: EventRef, val token: EditToken, val command: MeetingCommand)
    val calls = mutableListOf<Call>(); val recoveries = mutableListOf<String>()
    var reads = 0; var permissionCalls = 0; var createCalls = 0; var granted = true
    var permissionGate: CompletableDeferred<Unit>? = null; var mutationGate: CompletableDeferred<Unit>? = null
    var recoverResult: MeetingMutationResult = MeetingMutationResult.Common(MutationResult.Unavailable)
    var next: MeetingMutationResult = MeetingMutationResult.LocalSaved(selected, RecurrenceScope.THIS)
    var proof = false; var readResult: MeetingReadResult<EditableMeeting> = MeetingReadResult.Ready(source)
    var onRead: suspend () -> Unit = {}
    val meetings = object : CalendarMeetings {
        override suspend fun loadForCreate(target: CalendarIdentity): MeetingReadResult<MeetingCreation> = error("not a creation feature")
        override suspend fun loadForEdit(selected: EventRef): MeetingReadResult<EditableMeeting> { reads++; onRead(); return readResult }
        override suspend fun create(operationId: String, token: EditToken, draft: EventDraft, guests: List<MeetingGuestInput>): MeetingMutationResult { createCalls++; error("not a creation feature") }
        override suspend fun mutate(operationId: String, selected: EventRef, token: EditToken, command: MeetingCommand): MeetingMutationResult {
            calls += Call(operationId, selected, token, command); mutationGate?.await(); return next
        }
        override suspend fun recover(operationId: String): MeetingMutationResult {
            recoveries += operationId
            return if (proof) MeetingMutationResult.Common(MutationResult.SafeToRetry(operationId)) else recoverResult
        }
    }
    val permission: suspend () -> Boolean = { permissionCalls++; permissionGate?.await(); granted }
    suspend fun prepare(source: EditableMeeting = this.source, id: String = "response"): MeetingResponseLaunch =
        (prepareMeetingResponseDraft(source, id, store, clock) as MeetingResponsePreparation.Ready).launch
    fun controller(launch: MeetingResponseLaunch, source: EditableMeeting? = this.source) = MeetingResponseController(launch, source, meetings, store, scope, permission, clock = clock)
    fun choose(controller: MeetingResponseController, scope: RecurrenceScope = RecurrenceScope.THIS, response: MeetingResponse = MeetingResponse.YES) {
        controller.selectScope(scope); controller.selectResponse(response)
    }
    fun record() = store.rows.getValue("response")
    fun launch() = MeetingResponseDraftCodec.describe(record())!!
    suspend fun pending(): MeetingResponseController {
        val controller = controller(prepare()); choose(controller); next = MeetingMutationResult.Common(MutationResult.Uncertain("ignored", selected.eventId)); controller.save(); return controller
    }
}
