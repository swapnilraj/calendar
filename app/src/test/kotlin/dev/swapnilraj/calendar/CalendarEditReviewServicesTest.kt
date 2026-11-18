package dev.swapnilraj.calendar

import dev.swapnilraj.calendar.android.navigation.*
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.*
import java.time.*
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CalendarEditReviewServicesTest {
    private val f = EditReviewFixture()
    private val services = f.services()
    private suspend fun review(request: CalendarEditReviewRequest = f.request()) = services.open(request) as CalendarEditReviewResult.Review

    @Test fun openingAndReopeningReviewRetainsSuggestionsWithoutNativeDraftOrProviderWrite() = runBlocking {
        val first = review()
        val again = review()
        assertEquals(first.snapshot, again.snapshot)
        assertEquals("Original synthetic event", first.snapshot.original?.title)
        assertEquals("Proposed synthetic title", first.snapshot.suggested?.title)
        assertNull(f.store.load(f.request().nativeId)); assertEquals(0, f.writes)
    }

    @Test fun explicitApplyOverlaysFieldsWithoutRebasingOriginalTokenOrSource() = runBlocking {
        val request = f.request(f.seed().copy(location = "Proposed room", description = "Proposed notes", availability = 1, accessLevel = 0,
            occurrence = CalendarOccurrenceHints(f.start.plusSeconds(3600), null, null)))
        val first = review(request)
        val result = services.applySuggestions(first.snapshot) as CalendarEditReviewResult.Editor
        val native = OrdinaryDraftDocument.decode(requireNotNull(f.store.load(request.nativeId)))
        assertEquals(request.selected, result.launch.ref); assertEquals(EditorDraftKind.EDIT, result.launch.kind)
        assertEquals("Proposed synthetic title", native.fields.title); assertEquals("Proposed room", native.fields.location)
        assertEquals("Proposed notes", native.fields.description); assertEquals("1", native.fields.availability); assertEquals("0", native.fields.privacy)
        assertEquals("10:00", native.fields.startTime); assertEquals("10:00", native.fields.endTime) // No invented end/duration.
        assertEquals(first.snapshot.original, CalendarEditReviewDocument.decode(first.snapshot.record).original)
        assertEquals(f.editable.token.value, native.sourceToken)
        assertTrue(native.sourceBaseline.contains("Original synthetic event")); assertFalse(native.sourceBaseline.contains("Proposed synthetic title"))
        assertFalse(native.hasRequest); assertEquals(0, f.writes)
    }

    @Test fun typeConversionWithoutProposedInstantsLeavesMissingDatesAndTimesBlank() = runBlocking {
        val request = f.request(f.seed().copy(occurrence = CalendarOccurrenceHints(null, null, true)))
        val first = review(request)
        val editor = services.applySuggestions(first.snapshot) as CalendarEditReviewResult.Editor
        val fields = OrdinaryDraftDocument.decode(requireNotNull(f.store.load(editor.launch.draftId))).fields
        assertTrue(fields.allDay); assertEquals("", fields.startDate); assertEquals("", fields.endDate)
        assertEquals("", fields.startTime); assertEquals("", fields.endTime)
        assertEquals(0, f.writes)
    }

    @Test fun allDayProposalsUseUtcDatesAndExclusiveEnd() = runBlocking {
        val request = f.request(f.seed().copy(occurrence = CalendarOccurrenceHints(Instant.parse("2028-06-17T00:00:00Z"), Instant.parse("2028-06-20T00:00:00Z"), true)))
        val first = review(request); services.applySuggestions(first.snapshot)
        val fields = OrdinaryDraftDocument.decode(requireNotNull(f.store.load(request.nativeId))).fields
        assertEquals("2028-06-17", fields.startDate); assertEquals("2028-06-19", fields.endDate); assertEquals("Z", fields.zone)
    }

    @Test fun unsupportedGuestsAndMalformedRuleStayDurableAndBlockOrdinaryHandoff() = runBlocking {
        val request = f.request(f.seed().copy(guests = listOf("invalid guest", "guest@example.invalid"), recurrenceRule = "FREQ=INVALID"))
        val result = services.open(request) as CalendarEditReviewResult.Unsupported
        assertEquals(setOf(CalendarEditReviewIssue.GUESTS, CalendarEditReviewIssue.RECURRENCE), result.reasons)
        assertEquals(request, CalendarEditReviewDocument.decode(requireNotNull(f.store.load(request.reviewId))).request)
        assertTrue(services.applySuggestions(result.snapshot) is CalendarEditReviewResult.Unsupported)
        assertNull(f.store.load(request.nativeId)); assertEquals(0, f.writes)
    }

    @Test fun disallowedZoneSuggestionIsNotSilentlyKeptByNativePatch() = runBlocking {
        f.editable = f.editable.copy(allowedFields = f.editable.allowedFields - EditableField.ZONE)
        val request = f.request(f.seed().copy(timeZone = ZoneId.of("Europe/London")))
        val result = services.open(request) as CalendarEditReviewResult.Unsupported
        assertTrue(CalendarEditReviewIssue.FIELD_NOT_ALLOWED in result.reasons)
        assertNull(f.store.load(request.nativeId)); assertEquals(0, f.writes)
    }

    @Test fun delegatedOrGuestOwnedEventIsNotAdmittedDespiteFakeBackendReady() = runBlocking {
        f.editable = f.editable.copy(details = f.editable.details.copy(isOrganizer = false))
        val result = services.open(f.request()) as CalendarEditReviewResult.Unsupported
        assertEquals(setOf(CalendarEditReviewIssue.SOURCE_NOT_ELIGIBLE), result.reasons)
        assertNull(result.snapshot.original); assertNull(f.store.load(f.request().nativeId)); assertEquals(0, f.writes)
    }

    @Test fun writerReadyMayAuthorizeReaderUnknownButNeverReaderDenied() = runBlocking {
        f.editable = f.editable.copy(details = f.editable.details.copy(canEdit = AccessDecision.UNKNOWN))
        assertTrue(services.open(f.request()) is CalendarEditReviewResult.Review)
        f.editable = f.editable.copy(details = f.editable.details.copy(canEdit = AccessDecision.DENIED))
        val denied = services.open(f.request()) as CalendarEditReviewResult.Unsupported
        assertEquals(setOf(CalendarEditReviewIssue.SOURCE_NOT_ELIGIBLE), denied.reasons)
        assertNull(f.store.load(f.request().nativeId)); assertEquals(0, f.writes)
    }

    @Test fun namespaceAndUidChangeAcrossEditSnapshotAwaitCannotIssueNewBaseline() = runBlocking {
        f.onEditRead = { f.info = f.info.copy(providerUid = "replacement-event") }
        assertEquals(CalendarEditReviewResult.Stale, services.open(f.request()))
        val doc = CalendarEditReviewDocument.decode(requireNotNull(f.store.load(f.request().reviewId)))
        assertNull(doc.sourceToken); assertNull(doc.prepared); assertEquals(0, f.writes)
    }

    @Test fun learnedUidInvalidatesUnfrozenReviewInsteadOfStrengtheningAuthority() = runBlocking {
        f.info = f.info.copy(providerUid = null)
        val request = f.request().let { it.copy(binding = it.binding.copy(providerUid = null)) }
        val first = review(request)
        f.info = f.info.copy(providerUid = "learned-uid")
        assertEquals(CalendarEditReviewResult.Stale, services.applySuggestions(first.snapshot))
        assertNull(f.store.load(request.nativeId)); assertEquals(first.snapshot.record, f.store.load(request.reviewId))
    }

    @Test fun changedTokenCannotRebaseSuggestionsEvenWhenDisplayedSourceLooksUnchanged() = runBlocking {
        val first = review()
        f.editable = f.editable.copy(token = EditToken("different-original-token"))
        assertEquals(CalendarEditReviewResult.Stale, services.applySuggestions(first.snapshot))
        assertEquals(first.snapshot.record, f.store.load(f.request().reviewId)); assertNull(f.store.load(f.request().nativeId))
    }

    @Test fun currentNativeRawWinsOverReplayedOriginalSuggestions() = runBlocking {
        val first = review(); services.applySuggestions(first.snapshot)
        val record = requireNotNull(f.store.load(f.request().nativeId)); val doc = OrdinaryDraftDocument.decode(record)
        val edited = doc.copy(fields = doc.fields.copy(title = "Later user input", startDate = "")).record(Instant.now())
        assertEquals(DraftChangeResult.Applied, f.store.compareAndSet(record.id, record, edited))
        val replay = services.open(f.request()) as CalendarEditReviewResult.Editor
        assertEquals("Later user input", replay.launch.title); assertEquals("", replay.launch.rawDate)
        assertEquals(edited, f.store.load(record.id)); assertEquals(0, f.writes)
    }

    @Test fun receiptIsPermanentBeforeEditorExposureAndReplayAfterSaveCleanupIsConsumed() = runBlocking {
        val request = f.request(); val first = review(request)
        val editor = services.applySuggestions(first.snapshot) as CalendarEditReviewResult.Editor
        val receipt = requireNotNull(f.store.load(request.reviewId))
        assertTrue(isCalendarEditHandoffReceipt(receipt)); assertNull(calendarEditReviewPreview(receipt))
        assertFalse(receipt.payload.contains("Proposed synthetic title")); assertFalse(receipt.payload.contains("Original synthetic event")); assertFalse(receipt.payload.contains("original-source-token"))
        val native = requireNotNull(f.store.load(editor.launch.draftId))
        assertEquals(DraftChangeResult.Applied, f.store.compareAndSet(native.id, native, null))
        val reads = f.infoReads
        assertEquals(CalendarEditReviewResult.Consumed, services.open(request))
        assertEquals(reads, f.infoReads); assertEquals(receipt, f.store.load(request.reviewId)); assertNull(f.store.load(request.nativeId))
    }

    @Test fun interruptedNativeCasRetriesExactPreparedGenerationAndOperationOnly() = runBlocking {
        val request = f.request(); val first = review(request)
        f.store.failNative = true
        assertEquals(CalendarEditReviewResult.Unavailable, services.applySuggestions(first.snapshot))
        val prepared = CalendarEditReviewDocument.decode(requireNotNull(f.store.load(request.reviewId)))
        val expected = OrdinaryDraftDocument.decode(requireNotNull(prepared.prepared))
        f.store.failNative = false
        val reopened = services.open(request) as CalendarEditReviewResult.Editor
        val actual = OrdinaryDraftDocument.decode(requireNotNull(f.store.load(request.nativeId)))
        assertEquals(expected.generation, reopened.launch.generation); assertEquals(expected.operationId, actual.operationId)
        assertEquals(0, f.writes)
    }

    @Test fun receiptFailureNeverExposesSaveAndExistingNativeRepairsSameHandoff() = runBlocking {
        val request = f.request(); val first = review(request)
        f.store.failReceipt = true
        assertEquals(CalendarEditReviewResult.Unavailable, services.applySuggestions(first.snapshot))
        val native = requireNotNull(f.store.load(request.nativeId))
        assertNotNull(CalendarEditReviewActivity.intentForNative(RuntimeEnvironment.getApplication(), native, f.store.load(request.reviewId)))
        f.store.failReceipt = false
        val repaired = services.open(request) as CalendarEditReviewResult.Editor
        assertEquals(OrdinaryDraftDocument.decode(native).generation, repaired.launch.generation); assertEquals(native, f.store.load(request.nativeId))
    }

    @Test fun unknownAcknowledgmentsRequireMatchingReadbackAndNeverGuessSuccess() = runBlocking {
        f.store.unavailableAck = true
        val first = review(); f.store.hideReceipt = true
        assertEquals(CalendarEditReviewResult.Unavailable, services.applySuggestions(first.snapshot))
        assertNotNull(f.store.rows[f.request().nativeId]); assertTrue(isCalendarEditHandoffReceipt(requireNotNull(f.store.rows[f.request().reviewId])))
        f.store.hideReceipt = false
        assertTrue(services.open(f.request()) is CalendarEditReviewResult.Editor)
        assertEquals(0, f.writes)
    }

    @Test fun frozenRecoverySkipsMutableEventUidPinsAndPreservesExactFrozenBytes() = runBlocking {
        val request = f.request(); val first = review(request); services.applySuggestions(first.snapshot)
        f.freeze(request)
        val before = requireNotNull(f.store.load(request.nativeId)); val oldReads = f.infoReads; val oldEdits = f.editReads
        f.info = f.info.copy(providerUid = "learned-after-known-commit")
        f.infoFailure = CalendarReadState.Unavailable
        f.environment = CalendarReadState.Ready((f.environment as CalendarReadState.Ready).value.copy(calendarReadGranted = false))
        val opened = services.open(request, calendarReadGranted = false) as CalendarEditReviewResult.Editor
        assertTrue(opened.sourceFree); assertTrue(opened.launch.needsRecovery); assertTrue(opened.environment.calendars.isEmpty())
        assertEquals(oldReads, f.infoReads); assertEquals(oldEdits, f.editReads); assertEquals(before, f.store.load(request.nativeId))
    }

    @Test fun malformedFutureReceiptsAreRetainedAndNeverHiddenOrRecreated() = runBlocking {
        val request = f.request(); val first = review(request); services.applySuggestions(first.snapshot)
        val receipt = requireNotNull(f.store.load(request.reviewId)); val future = receipt.copy(version = 9)
        f.store.rows[receipt.id] = future
        assertFalse(isCalendarEditHandoffReceipt(future)); assertNull(calendarEditReviewPreview(future))
        assertEquals(CalendarEditReviewResult.Unavailable, services.open(request))
        assertEquals(future, f.store.load(request.reviewId)); assertEquals(0, f.writes)
    }

    @Test fun cancelledSourceReadRetainsRawRequestWithoutPretendingPreparationSucceeded() = runBlocking {
        f.onEditRead = { throw CancellationException("Synthetic cancellation") }
        try { services.open(f.request()); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        assertNotNull(f.store.load(f.request().reviewId)); assertNull(f.store.load(f.request().nativeId)); assertEquals(0, f.writes)
    }

    @Test fun nullSourceZoneUsesCapturedReviewZoneAcrossDeviceZoneChangeWithoutAuthoringIt() = runBlocking {
        val prior = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            f.editable = f.editable.copy(details = f.editable.details.copy(eventZone = null))
            val request = f.request(zone = ZoneId.of("Asia/Tokyo")); val first = review(request)
            assertEquals("18:00", first.snapshot.original?.startTime); assertNull(first.snapshot.sourceZone)
            f.store.beforeChange = { id, _, _ -> if (id == request.nativeId) TimeZone.setDefault(TimeZone.getTimeZone("America/New_York")) }
            services.applySuggestions(first.snapshot) as CalendarEditReviewResult.Editor
            val native = OrdinaryDraftDocument.decode(requireNotNull(f.store.load(request.nativeId)))
            assertEquals("Asia/Tokyo", native.fields.zone); assertEquals("18:00", native.fields.startTime)
            assertEquals("Asia/Tokyo", org.json.JSONObject(native.sourceBaseline).getString(OrdinaryEditDisplayProof.KEY))
            assertEquals("", org.json.JSONObject(native.sourceBaseline).getString("zone")); assertEquals(f.editable.token.value, native.sourceToken)
        } finally { TimeZone.setDefault(prior) }
    }

    @Test fun explicitSameRendererZoneBecomesAuthoredOnlyAfterApplyWithOriginalMissingZoneBaseline() = runBlocking {
        f.editable = f.editable.copy(details = f.editable.details.copy(eventZone = null))
        val zone = ZoneId.of("Asia/Tokyo")
        val request = f.request(f.seed().copy(timeZone = zone, occurrence = CalendarOccurrenceHints(f.start.plusSeconds(3600), null, null)), zone)
        val result = services.open(request) as CalendarEditReviewResult.Review
        assertNull(f.store.load(request.nativeId)); assertEquals(request.proposed, result.snapshot.request.proposed)
        assertNull(result.snapshot.sourceZone); assertEquals(0, f.writes)
        services.applySuggestions(result.snapshot) as CalendarEditReviewResult.Editor
        val native = OrdinaryDraftDocument.decode(requireNotNull(f.store.load(request.nativeId)))
        assertTrue(native.zoneAuthored); assertEquals("Asia/Tokyo", native.fields.zone)
        assertEquals("", org.json.JSONObject(native.sourceBaseline).getString("zone"))
        assertEquals("", org.json.JSONObject(native.sourceBaseline).getString("sourceZone"))
        assertEquals(0, f.writes)
    }

    @Test fun secondNativeLoadCannotRefreshChangedTokenEvenWithIdenticalDisplayFields() = runBlocking {
        val first = review(); val editor = services.applySuggestions(first.snapshot) as CalendarEditReviewResult.Editor
        val native = f.store.load(f.request().nativeId)
        f.editable = f.editable.copy(token = EditToken("new-token-same-visible-source"))
        assertEquals(EditReadResult.Unavailable, services.loadForNative(editor))
        assertEquals(native, f.store.load(f.request().nativeId)); assertEquals(0, f.writes)
    }

    @Test fun receiptLocksOriginalTokenAndRejectsAnOutOfBandNativeRebase() = runBlocking {
        val first = review(); services.applySuggestions(first.snapshot)
        val record = requireNotNull(f.store.load(f.request().nativeId)); val native = OrdinaryDraftDocument.decode(record)
        val rebased = native.copy(sourceToken = "unexpected-rebased-token").record(Instant.now())
        f.store.rows[record.id] = rebased
        val reads = f.infoReads
        assertEquals(CalendarEditReviewResult.Stale, services.open(f.request()))
        assertEquals(reads, f.infoReads); assertEquals(rebased, f.store.load(record.id)); assertEquals(0, f.writes)
    }
}
