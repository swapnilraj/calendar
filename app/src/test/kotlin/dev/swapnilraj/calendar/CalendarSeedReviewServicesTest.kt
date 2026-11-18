package dev.swapnilraj.calendar

import dev.swapnilraj.calendar.android.navigation.*
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.*
import java.time.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

internal class SeedReviewStore : AtomicDraftStore {
    val records = linkedMapOf<String, DraftRecord>()
    var ackUnavailable = false
    var failNative = false
    var failCleanup = false
    var failReceipt = false
    var hideReceiptReadback = false
    override suspend fun load(id: String) = records[id]?.takeUnless { hideReceiptReadback && isCalendarSeedHandoffReceipt(it) }
    override suspend fun list() = records.values.toList()
    override suspend fun upsert(record: DraftRecord) { error("Raw upsert forbidden") }
    override suspend fun delete(id: String) { error("Unconditional delete forbidden") }
    override suspend fun compareAndSet(id: String, expected: DraftRecord?, replacement: DraftRecord?): DraftChangeResult {
        if (failNative && id.startsWith("incoming:")) return DraftChangeResult.Unavailable
        if (failReceipt && replacement?.let(::isCalendarSeedHandoffReceipt) == true) return DraftChangeResult.Unavailable
        if (failCleanup && replacement == null) return DraftChangeResult.Unavailable
        if (records[id] != expected) return DraftChangeResult.Changed
        if (replacement == null) records.remove(id) else records[id] = replacement
        return if (ackUnavailable) DraftChangeResult.Unavailable else DraftChangeResult.Applied
    }
}

internal class SeedReviewMutations : CalendarMutations {
    var writes = 0
    var saved: EventDraft? = null
    override suspend fun loadForEdit(ref: EventRef): EditReadResult = error("Not an edit")
    override suspend fun create(operationId: String, draft: EventDraft): MutationResult {
        writes++; saved = draft
        val time = draft.time as EventTime.Timed
        return MutationResult.LocalSaved(EventRef(draft.calendarId, 99, time.start, time.end))
    }
    override suspend fun update(operationId: String, ref: EventRef, token: EditToken, patch: EventPatch): MutationResult = error("No edit")
    override suspend fun delete(operationId: String, ref: EventRef, token: EditToken): MutationResult = error("No delete")
    override suspend fun recover(operationId: String): MutationResult = MutationResult.Uncertain(operationId, null)
}

internal object SeedReviewRetirement : OperationRetirement {
    override suspend fun retire(request: RetirableOperation) = OperationRetirementResult.Unavailable
    override suspend fun inspect(request: RetirableOperation) = OperationRetirementResult.Unavailable
}

internal val seedReviewCalendar = CalendarInfo(CalendarId(3), "Synthetic calendar", 123, CalendarAccount("synthetic@example.invalid", "local"), "owner", ZoneOffset.UTC,
    700, true, true, CalendarCapabilities(createEvents = AccessDecision.ALLOWED, readDetails = AccessDecision.ALLOWED), "calendar-A")
internal val seedReviewEnvironment = CalendarSeedReviewEnvironment(listOf(seedReviewCalendar), ZoneOffset.UTC, true, seedReviewCalendar.identity,
    listOf(10), listOf(900), true)
internal fun seedReviewRequest(complete: Boolean = false) = CalendarSeedReviewRequest("bd644434-671f-4ec2-b2e4-3b77db826c12", CalendarDraftSeed(
    CalendarOccurrenceHints(if (complete) Instant.parse("2026-11-18T09:00:00Z") else null, if (complete) Instant.parse("2026-11-18T10:00:00Z") else null, false),
    "Synthetic incoming", "Room", "Notes", emptyList(), null, null, 1, 2), ZoneOffset.UTC)

internal fun freezeSeedReviewNative(store: SeedReviewStore, request: CalendarSeedReviewRequest) {
    val document = OrdinaryDraftDocument.decode(requireNotNull(store.records[request.draftId]))
    val command = FrozenEditorSubmission.Create(document.operationId, EventEditorController.normalizedDraft(document.fields))
    store.records[request.draftId] = document.copy(frozen = command, submitted = true, operationKind = command.kind,
        operationPayload = EventEditorController.encodeRawFields(document.fields).toString()).record(Instant.now())
}

@RunWith(RobolectricTestRunner::class)
class CalendarSeedReviewServicesTest {
    private val store = SeedReviewStore()
    private val mutations = SeedReviewMutations()
    private var environment: CalendarReadState<CalendarSeedReviewEnvironment> = CalendarReadState.Ready(seedReviewEnvironment)
    private val service = CalendarSeedReviewServices(store, mutations, SeedReviewRetirement, { environment }, MutableSharedFlow())

    @Test fun incompleteSeedPersistsBlankRawInputsWithoutInventingTime() = runBlocking {
        val request = seedReviewRequest()
        val result = service.open(request) as CalendarSeedReviewResult.Review
        assertEquals("", result.snapshot.fields.startDate); assertEquals("", result.snapshot.fields.endDate)
        assertEquals("", result.snapshot.fields.startTime); assertEquals("", result.snapshot.fields.endTime)
        assertNull(store.load(request.draftId)); assertEquals(0, mutations.writes)
        val reopened = service.open(request, result.snapshot.generation, true) as CalendarSeedReviewResult.Review
        assertEquals(result.snapshot, reopened.snapshot)
    }

    @Test fun completeSeedOpensActualOrdinaryDraftAndPreservesMetadataDefaults() = runBlocking {
        val request = seedReviewRequest(true)
        val result = service.open(request) as CalendarSeedReviewResult.Editor
        val raw = OrdinaryDraftDocument.decode(requireNotNull(store.load(request.draftId)))
        assertEquals("incoming:${request.sessionId}", result.launch.draftId)
        assertEquals("Synthetic incoming", raw.fields.title); assertEquals("Room", raw.fields.location); assertEquals("Notes", raw.fields.description)
        assertEquals("09:00", raw.fields.startTime); assertEquals("1", raw.fields.availability); assertEquals("2", raw.fields.privacy)
        assertEquals("10", raw.fields.alerts); assertEquals(seedReviewCalendar.identity, raw.fields.targetIdentity)
        assertEquals(0, mutations.writes)
    }

    @Test fun incompleteEditsSurviveReopenAndContinueUsesLatestExactRevision() = runBlocking {
        val request = seedReviewRequest()
        val initial = service.open(request) as CalendarSeedReviewResult.Review
        val updated = requireNotNull(service.update(initial.snapshot, initial.snapshot.fields.copy(startDate = "2026-12-01", endDate = "2026-12-01", startTime = "14:00", endTime = "15:00")))
        val reopened = service.open(request) as CalendarSeedReviewResult.Review
        assertEquals(updated, reopened.snapshot)
        assertTrue(service.prepare(initial.snapshot, initial.environment) is CalendarSeedReviewResult.Stale)
        val editor = service.prepare(reopened.snapshot, reopened.environment) as CalendarSeedReviewResult.Editor
        assertEquals("2026-12-01", editor.launch.rawDate)
        assertEquals(0, mutations.writes)
    }

    @Test fun existingOrdinaryRawAlwaysWinsAndIsNeverReplacedByOriginalSeed() = runBlocking {
        val request = seedReviewRequest(true)
        service.open(request)
        val old = requireNotNull(store.load(request.draftId)); val doc = OrdinaryDraftDocument.decode(old)
        val edited = doc.copy(fields = doc.fields.copy(title = "Retained edited title", startDate = "")).record(Instant.now())
        store.records[old.id] = edited
        val result = service.open(request) as CalendarSeedReviewResult.Editor
        assertEquals("Retained edited title", result.launch.title); assertEquals(edited, store.load(old.id))
        assertEquals(0, mutations.writes)
    }

    @Test fun allDaySeedUsesUtcDatesAndExclusiveEndBecomesInclusiveField() = runBlocking {
        val request = seedReviewRequest(true).let { it.copy(seed = it.seed.copy(occurrence = CalendarOccurrenceHints(
            Instant.parse("2026-11-18T00:00:00Z"), Instant.parse("2026-11-21T00:00:00Z"), true), timeZone = ZoneId.of("America/Los_Angeles"))) }
        service.open(request) as CalendarSeedReviewResult.Editor
        val raw = OrdinaryDraftDocument.decode(requireNotNull(store.load(request.draftId))).fields
        assertEquals("2026-11-18", raw.startDate); assertEquals("2026-11-20", raw.endDate); assertEquals("Z", raw.zone)
        assertEquals("900", raw.alerts)
    }

    @Test fun explicitSeedZoneAndAmbiguousOffsetArePreserved() = runBlocking {
        val request = seedReviewRequest(true).let { it.copy(seed = it.seed.copy(occurrence = CalendarOccurrenceHints(
            Instant.parse("2026-11-01T06:15:00Z"), Instant.parse("2026-11-01T06:45:00Z"), false), timeZone = ZoneId.of("America/New_York"))) }
        service.open(request) as CalendarSeedReviewResult.Editor
        val raw = OrdinaryDraftDocument.decode(requireNotNull(store.load(request.draftId))).fields
        assertEquals("01:15", raw.startTime); assertEquals("-05:00", raw.startOffset)
        assertEquals("America/New_York", raw.zone)
    }

    @Test fun supportedRuleBecomesTypedAuthoredRecurrenceWithoutOneoffFallback() = runBlocking {
        val request = seedReviewRequest(true).let { it.copy(seed = it.seed.copy(recurrenceRule = "FREQ=DAILY;COUNT=3")) }
        service.open(request) as CalendarSeedReviewResult.Editor
        val raw = OrdinaryDraftDocument.decode(requireNotNull(store.load(request.draftId))).fields
        assertEquals("DAILY", raw.recurrence.frequency); assertEquals("3", raw.recurrence.count)
    }

    @Test fun unsupportedRuleRemainsVisibleDurableAndRequiresExplicitRemoval() = runBlocking {
        val request = seedReviewRequest(true).let { it.copy(seed = it.seed.copy(recurrenceRule = "FREQ=HOURLY;COUNT=3")) }
        val initial = service.open(request) as CalendarSeedReviewResult.Review
        assertEquals(request.seed.recurrenceRule, initial.snapshot.fields.rule)
        assertTrue(service.prepare(initial.snapshot, initial.environment) is CalendarSeedReviewResult.Review)
        assertNull(store.load(request.draftId))
        val updated = requireNotNull(service.update(initial.snapshot, initial.snapshot.fields.copy(rule = "")))
        assertTrue(service.prepare(updated, initial.environment) is CalendarSeedReviewResult.Editor)
        assertEquals(0, mutations.writes)
    }

    @Test fun guestsNeverFallThroughToOrdinaryEditorAndRemainStored() = runBlocking {
        val request = seedReviewRequest(true).let { it.copy(seed = it.seed.copy(guests = listOf("guest@example.test"))) }
        val result = service.open(request) as CalendarSeedReviewResult.NeedsMeetingEditor
        assertEquals(request.seed.guests, result.snapshot.request.seed.guests)
        assertNotNull(store.load("incoming-review:${request.sessionId}")); assertNull(store.load(request.draftId))
        assertEquals(0, mutations.writes)
    }

    @Test fun unknownAcknowledgmentRequiresExactDurableReadback() = runBlocking {
        store.ackUnavailable = true
        assertTrue(service.open(seedReviewRequest(true)) is CalendarSeedReviewResult.Editor)
        assertEquals(0, mutations.writes)
    }

    @Test fun interruptedHandoffRetainsFrozenInputsAndRejectsStaleCompletionEdits() = runBlocking {
        store.failNative = true
        val request = seedReviewRequest(true)
        assertTrue(service.open(request) is CalendarSeedReviewResult.Unavailable)
        val record = requireNotNull(store.load("incoming-review:${request.sessionId}"))
        val document = CalendarSeedCompletionDocument.decode(record)
        assertNotNull(document.preparedFields)
        val snapshot = CalendarSeedReviewSnapshot(request, document.generation, document.fields, record)
        assertNull(service.update(snapshot, snapshot.fields.copy(startTime = "11:00")))
        store.failNative = false
        assertTrue(service.open(request) is CalendarSeedReviewResult.Editor)
        assertEquals(0, mutations.writes)
    }

    @Test fun namespaceReplacementAfterFormShownNeverPinsReplacementCalendar() = runBlocking {
        val initial = service.open(seedReviewRequest()) as CalendarSeedReviewResult.Review
        val updated = requireNotNull(service.update(initial.snapshot, initial.snapshot.fields.copy(startDate = "2026-12-01", endDate = "2026-12-01", startTime = "14:00", endTime = "15:00")))
        environment = CalendarReadState.Ready(seedReviewEnvironment.copy(calendars = listOf(seedReviewCalendar.copy(providerId = "calendar-B"))))
        assertTrue(service.prepare(updated, initial.environment) is CalendarSeedReviewResult.Stale)
        assertNull(store.load(updated.request.draftId))
    }

    @Test fun restoreMissingDraftAndMalformedPresentRecordNeverCreateFreshInputs() = runBlocking {
        val request = seedReviewRequest()
        assertTrue(service.open(request, restoreOnly = true) is CalendarSeedReviewResult.Stale)
        store.records["incoming-review:${request.sessionId}"] = DraftRecord("incoming-review:${request.sessionId}", 7, "{}", Instant.now())
        assertTrue(service.open(request) is CalendarSeedReviewResult.Unavailable)
        assertEquals("{}", store.load("incoming-review:${request.sessionId}")?.payload)
    }

    @Test fun readPermissionFailureDoesNotCreateLocalDraftOrWrite() = runBlocking {
        environment = CalendarReadState.PermissionRequired
        assertEquals(CalendarSeedReviewResult.PermissionRequired, service.open(seedReviewRequest(true)))
        assertTrue(store.records.isEmpty()); assertEquals(0, mutations.writes)
    }

    @Test fun permanentReceiptContainsNoSeedAndPreventsReplayAfterNativeSaveCleanup() = runBlocking {
        val request = seedReviewRequest(true)
        val editor = service.open(request) as CalendarSeedReviewResult.Editor
        val record = requireNotNull(store.load("incoming-review:${request.sessionId}"))
        assertTrue(isCalendarSeedHandoffReceipt(record)); assertNull(calendarSeedReviewPreview(record))
        assertFalse(record.payload.contains("Synthetic incoming")); assertFalse(record.payload.contains("Room")); assertFalse(record.payload.contains("Notes"))
        assertEquals(editor.launch.generation, CalendarSeedHandoffReceipt.decode(record).nativeGeneration)
        store.records.remove(request.draftId) // Native successful-save cleanup or explicit retirement.
        assertEquals(CalendarSeedReviewResult.Stale, service.open(request))
        assertEquals(record, store.load(record.id)); assertNull(store.load(request.draftId)); assertEquals(0, mutations.writes)
    }

    @Test fun receiptCommitIsRequiredBeforeEditorExposureAndRestartFinishesSameHandoff() = runBlocking {
        val request = seedReviewRequest(true)
        store.failReceipt = true
        assertEquals(CalendarSeedReviewResult.Unavailable, service.open(request))
        val native = requireNotNull(store.load(request.draftId))
        val before = OrdinaryDraftDocument.decode(native)
        assertFalse(before.submitted)
        assertNotNull(CalendarSeedCompletionDocument.decode(requireNotNull(store.load("incoming-review:${request.sessionId}"))).preparedFields)
        store.failReceipt = false
        val reopened = CalendarSeedReviewServices(store, mutations, SeedReviewRetirement, { environment }, MutableSharedFlow())
            .open(request) as CalendarSeedReviewResult.Editor
        assertEquals(before.generation, reopened.launch.generation); assertEquals(native, store.load(request.draftId))
        assertTrue(isCalendarSeedHandoffReceipt(requireNotNull(store.load("incoming-review:${request.sessionId}"))))
        assertEquals(0, mutations.writes)
    }

    @Test fun committedReceiptWithoutReadableAckNeverExposesSaveUntilVerified() = runBlocking {
        val request = seedReviewRequest(true)
        store.ackUnavailable = true; store.hideReceiptReadback = true
        assertEquals(CalendarSeedReviewResult.Unavailable, service.open(request))
        assertNotNull(store.records[request.draftId])
        assertTrue(isCalendarSeedHandoffReceipt(requireNotNull(store.records["incoming-review:${request.sessionId}"])))
        store.hideReceiptReadback = false
        assertTrue(service.open(request) is CalendarSeedReviewResult.Editor)
        assertEquals(0, mutations.writes)
    }

    @Test fun mismatchedReceiptGenerationBlocksNativeDraftWithoutReplacingEither() = runBlocking {
        val request = seedReviewRequest(true)
        service.open(request)
        val native = requireNotNull(store.load(request.draftId))
        val receiptRecord = requireNotNull(store.load("incoming-review:${request.sessionId}"))
        val changed = CalendarSeedHandoffReceipt.decode(receiptRecord).copy(nativeGeneration = "a843749d-a579-45be-ab6e-2e82f04c374b").record(Instant.now())
        store.records[changed.id] = changed
        assertEquals(CalendarSeedReviewResult.Stale, service.open(request))
        assertEquals(changed, store.load(changed.id)); assertEquals(native, store.load(request.draftId))
    }

    @Test fun strictPreviewAndRestoreHelperNeverRecognizeMalformedOrFutureReceipts() = runBlocking {
        val request = seedReviewRequest()
        val review = service.open(request) as CalendarSeedReviewResult.Review
        val record = requireNotNull(store.load("incoming-review:${request.sessionId}"))
        assertEquals(review.snapshot.generation, calendarSeedReviewPreview(record)?.generation)
        assertNotNull(CalendarSeedReviewActivity.intentFromDraft(org.robolectric.RuntimeEnvironment.getApplication(), record))
        val receipt = CalendarSeedHandoffReceipt(request.sessionId, review.snapshot.generation, "a843749d-a579-45be-ab6e-2e82f04c374b").record(Instant.now())
        assertTrue(isCalendarSeedHandoffReceipt(receipt))
        assertFalse(isCalendarSeedHandoffReceipt(receipt.copy(version = 8)))
        assertFalse(isCalendarSeedHandoffReceipt(receipt.copy(payload = receipt.payload.dropLast(1) + ",\"session\":\"${request.sessionId}\"}")))
        assertNull(CalendarSeedReviewActivity.intentFromDraft(org.robolectric.RuntimeEnvironment.getApplication(), receipt))
        assertNull(calendarSeedReviewPreview(receipt.copy(payload = "{".repeat(100))))
    }

    @Test fun fileBackedReopenPreservesRawInputsThenPermanentReceiptBlocksReplayedSession() = runBlocking {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val name = "incoming-review-${java.util.UUID.randomUUID()}.db"
        var sql = dev.swapnilraj.calendar.data.calendar.CalendarLocalStore(context, name)
        fun services() = CalendarSeedReviewServices(sql, mutations, SeedReviewRetirement, { environment }, MutableSharedFlow())
        try {
            val request = seedReviewRequest()
            val review = services().open(request) as CalendarSeedReviewResult.Review
            val first = requireNotNull(services().update(review.snapshot, review.snapshot.fields.copy(startDate = "2026-12-03")))
            sql.close(); sql = dev.swapnilraj.calendar.data.calendar.CalendarLocalStore(context, name)
            val restored = services().open(request, first.generation, true) as CalendarSeedReviewResult.Review
            assertEquals(first, restored.snapshot); assertEquals("", restored.snapshot.fields.startTime)
            val complete = requireNotNull(services().update(restored.snapshot, restored.snapshot.fields.copy(endDate = "2026-12-03", startTime = "09:30", endTime = "10:30")))
            val launched = services().prepare(complete, restored.environment) as CalendarSeedReviewResult.Editor
            val native = requireNotNull(sql.load(request.draftId))
            sql.close(); sql = dev.swapnilraj.calendar.data.calendar.CalendarLocalStore(context, name)
            val reopened = services().open(request) as CalendarSeedReviewResult.Editor
            assertEquals(launched.launch.generation, reopened.launch.generation)
            assertEquals(DraftChangeResult.Applied, sql.compareAndSet(native.id, native, null))
            assertEquals(CalendarSeedReviewResult.Stale, services().open(request))
            assertTrue(isCalendarSeedHandoffReceipt(requireNotNull(sql.load("incoming-review:${request.sessionId}"))))
            assertEquals(0, mutations.writes)
        } finally { sql.close(); context.deleteDatabase(name) }
    }

    @Test fun frozenNativeReopensWithoutCalendarReadUsingOnlyFreshSettingsAndNoCalendarMetadata() = runBlocking {
        val request = seedReviewRequest(true)
        service.open(request)
        environment = CalendarReadState.Ready(seedReviewEnvironment.copy(calendarReadGranted = false))
        assertEquals(CalendarSeedReviewResult.PermissionRequired, service.open(request))
        freezeSeedReviewNative(store, request)
        val before = requireNotNull(store.load(request.draftId))
        val reopened = service.open(request) as CalendarSeedReviewResult.Editor
        assertTrue(reopened.launch.needsRecovery)
        assertFalse(reopened.environment.calendarReadGranted); assertTrue(reopened.environment.calendars.isEmpty())
        assertNull(reopened.environment.defaultCalendar); assertEquals(before, store.load(request.draftId)); assertEquals(0, mutations.writes)
    }

    @Test fun browserNativeGateRequiresMatchingReceiptAndCanRepairPendingExactHandoff() = runBlocking {
        val request = seedReviewRequest(true)
        store.failReceipt = true
        service.open(request)
        val native = requireNotNull(store.load(request.draftId)); val completion = requireNotNull(store.load("incoming-review:${request.sessionId}"))
        assertFalse(isCalendarSeedNativeHandoffVerified(native, completion))
        assertNotNull(CalendarSeedReviewActivity.intentForPendingNative(org.robolectric.RuntimeEnvironment.getApplication(), native, completion))
        assertNull(CalendarSeedReviewActivity.intentForPendingNative(org.robolectric.RuntimeEnvironment.getApplication(), native.copy(id = "another"), completion))
        store.failReceipt = false
        service.open(request)
        val receipt = requireNotNull(store.load(completion.id))
        assertTrue(isCalendarSeedNativeHandoffVerified(native, receipt))
        assertFalse(isCalendarSeedNativeHandoffVerified(native, null))
        assertNull(CalendarSeedReviewActivity.intentForPendingNative(org.robolectric.RuntimeEnvironment.getApplication(), native, receipt))
    }

    @Test fun staleCompletionGenerationCannotAdoptNewNativeHandoffEvenWhenSessionMatches() = runBlocking {
        val request = seedReviewRequest(true)
        service.open(request)
        val native = store.records[request.draftId]
        val receipt = store.records["incoming-review:${request.sessionId}"]
        assertEquals(CalendarSeedReviewResult.Stale, service.open(request, "b99c433c-90d1-40eb-820d-862b4a38419d", true))
        assertEquals(native, store.records[request.draftId]); assertEquals(receipt, store.records["incoming-review:${request.sessionId}"])
    }
}
