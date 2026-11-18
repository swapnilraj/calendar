package dev.swapnilraj.calendar

import dev.swapnilraj.calendar.android.navigation.*
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.*
import java.time.*
import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Explicit incoming intent authority, independent of unchanged raw renderer strings. */
@RunWith(RobolectricTestRunner::class)
class CalendarEditZoneAuthoringTest {
    private val f = EditReviewFixture().apply { editable = editable.copy(details = editable.details.copy(eventZone = null)) }
    private val zone = ZoneId.of("Asia/Tokyo")
    private fun request() = f.request(f.seed().copy(title = null, timeZone = zone), zone)
    private suspend fun review(request: CalendarEditReviewRequest = request()) = f.services().open(request) as CalendarEditReviewResult.Review
    private suspend fun native(request: CalendarEditReviewRequest = request()) = OrdinaryDraftDocument.decode(requireNotNull(f.store.load(request.nativeId)))

    @Test fun arrivalAndReopeningNeverAuthorNativeZoneOrDispatch() = runBlocking {
        val first = review(); val again = review()
        assertEquals(first.snapshot, again.snapshot); assertEquals(first.snapshot.original, first.snapshot.suggested)
        assertNull(first.snapshot.sourceZone); assertNull(f.store.load(request().nativeId))
        assertFalse(first.snapshot.record.payload.contains("zoneAuthored")); assertEquals(0, f.writes)
    }

    @Test fun sameRendererZoneStillRequiresZoneRightEvenWhenTimeRightIsAbsentAndRawFieldsEqual() = runBlocking {
        f.editable = f.editable.copy(allowedFields = emptySet())
        val result = f.services().open(request()) as CalendarEditReviewResult.Unsupported
        assertEquals(result.snapshot.original, result.snapshot.suggested)
        assertEquals(setOf(CalendarEditReviewIssue.FIELD_NOT_ALLOWED), result.reasons)
        assertTrue(f.services().applySuggestions(result.snapshot) is CalendarEditReviewResult.Unsupported)
        assertNull(f.store.load(request().nativeId)); assertEquals(0, f.writes)
    }

    @Test fun zoneOnlyDoesNotNeedTimeRightAndKeepsOriginalOffsets() = runBlocking {
        f.editable = f.editable.copy(allowedFields = setOf(EditableField.ZONE))
        val review = review(); f.services().applySuggestions(review.snapshot) as CalendarEditReviewResult.Editor
        assertTrue(native().zoneAuthored); assertEquals(review.snapshot.original, native().fields)
        assertEquals("+09:00", native().fields.startOffset); assertEquals("+09:00", native().fields.endOffset)
        assertEquals(0, f.writes)
    }

    @Test fun noProposedZoneWithChangedTimesRemainsReviewOnly() = runBlocking {
        val request = f.request(f.seed().copy(title = null, occurrence = CalendarOccurrenceHints(f.start.plusSeconds(3600), f.start.plusSeconds(7200), false)), zone)
        val result = f.services().open(request) as CalendarEditReviewResult.Unsupported
        assertEquals(setOf(CalendarEditReviewIssue.ZONE_AUTHORING_REVIEW), result.reasons)
        assertTrue(f.services().applySuggestions(result.snapshot) is CalendarEditReviewResult.Unsupported)
        assertNull(f.store.load(request.nativeId)); assertEquals(0, f.writes)
    }

    @Test fun knownSourceSameZoneKeepsNoOpSemanticsWithoutTimeOrZoneRights() = runBlocking {
        f.editable = f.editable.copy(details = f.editable.details.copy(eventZone = zone), allowedFields = emptySet())
        val review = review(); f.services().applySuggestions(review.snapshot) as CalendarEditReviewResult.Editor
        assertFalse(native().zoneAuthored); assertEquals(review.snapshot.original, native().fields)
        assertFalse(requireNotNull(f.store.load(request().nativeId)).payload.contains("zoneAuthored")); assertEquals(0, f.writes)
    }

    @Test fun allDayConversionUsesExistingRawChangePathWithoutNewIntent() = runBlocking {
        val request = f.request(f.seed().copy(title = null, timeZone = ZoneId.of("UTC"),
            occurrence = CalendarOccurrenceHints(Instant.parse("2028-06-16T00:00:00Z"), Instant.parse("2028-06-17T00:00:00Z"), true)), zone)
        val review = review(request); f.services().applySuggestions(review.snapshot) as CalendarEditReviewResult.Editor
        assertTrue(native(request).fields.allDay); assertFalse(native(request).zoneAuthored); assertEquals(0, f.writes)
    }

    @Test fun readPermissionReturnOnlyRestoresReviewUntilAnotherExplicitApply() = runBlocking {
        f.environment = CalendarReadState.Ready((f.environment as CalendarReadState.Ready).value.copy(calendarReadGranted = false))
        assertEquals(CalendarEditReviewResult.PermissionRequired, f.services().open(request()))
        assertNull(f.store.load(request().nativeId))
        f.environment = CalendarReadState.Ready((f.environment as CalendarReadState.Ready).value.copy(calendarReadGranted = true))
        review(); assertNull(f.store.load(request().nativeId)); assertEquals(0, f.writes)
    }

    @Test fun changedReviewGenerationCannotFreezeTheOldSuggestionOrSeedAFlag() = runBlocking {
        val review = review(); val old = review.snapshot.record
        val replacement = CalendarEditReviewDocument.decode(old).copy(generation = java.util.UUID.randomUUID().toString()).record(old.updatedAt)
        f.store.rows[old.id] = replacement
        assertEquals(CalendarEditReviewResult.Stale, f.services().applySuggestions(review.snapshot))
        assertEquals(replacement, f.store.load(old.id)); assertNull(f.store.load(request().nativeId)); assertEquals(0, f.writes)
    }

    @Test fun changedUidCannotAuthorZoneOnAnOtherwiseEqualSource() = runBlocking {
        val review = review(); f.info = f.info.copy(providerUid = "replacement-uid")
        assertEquals(CalendarEditReviewResult.Stale, f.services().applySuggestions(review.snapshot))
        assertEquals(review.snapshot.record, f.store.load(request().reviewId)); assertNull(f.store.load(request().nativeId)); assertEquals(0, f.writes)
    }

    @Test fun changedCalendarNamespaceCannotAuthorZoneOnAnOtherwiseEqualSource() = runBlocking {
        val review = review(); f.info = f.info.copy(calendar = f.info.calendar.copy(providerId = "replacement-calendar"))
        assertEquals(CalendarEditReviewResult.Stale, f.services().applySuggestions(review.snapshot))
        assertNull(f.store.load(request().nativeId)); assertEquals(0, f.writes)
    }

    @Test fun lostCasAcknowledgmentAdoptsOnlyExactPreparedCandidateIncludingZoneIntent() = runBlocking {
        val review = review(); f.store.unavailableAck = true
        val result = f.services().applySuggestions(review.snapshot) as CalendarEditReviewResult.Editor
        assertTrue(native().zoneAuthored); assertEquals(result.launch.generation, native().generation)
        assertTrue(isCalendarEditHandoffReceipt(requireNotNull(f.store.load(request().reviewId)))); assertEquals(0, f.writes)
    }

    @Test fun foreignNativeGenerationAfterCasIsRetainedAndCannotBecomeOurEditor() = runBlocking {
        val review = review(); var successor: DraftRecord? = null
        f.store.afterChange = { id, _, replacement ->
            if (id == request().nativeId && replacement != null) {
                val raw = JSONObject(replacement.payload).put("generation", java.util.UUID.randomUUID().toString())
                successor = replacement.copy(payload = raw.toString()); f.store.rows[id] = requireNotNull(successor)
            }
        }
        assertEquals(CalendarEditReviewResult.Unavailable, f.services().applySuggestions(review.snapshot))
        assertEquals(successor, f.store.load(request().nativeId)); assertEquals(0, f.writes)
    }

    @Test fun historicalPreparedCandidateIsNotReinterpretedToInventNewIntent() = runBlocking {
        val request = f.request(f.seed().copy(title = null, timeZone = ZoneId.of("Europe/London")), zone)
        val review = review(request); f.store.failNative = true
        assertEquals(CalendarEditReviewResult.Unavailable, f.services().applySuggestions(review.snapshot))
        val prepared = CalendarEditReviewDocument.decode(requireNotNull(f.store.load(request.reviewId)))
        val candidate = requireNotNull(prepared.prepared)
        val oldCandidate = OrdinaryDraftDocument.decode(candidate).copy(zoneAuthored = false).record(candidate.updatedAt)
        val historical = prepared.copy(prepared = oldCandidate).record(review.snapshot.record.updatedAt)
        f.store.rows[request.reviewId] = historical; f.store.failNative = false
        f.services().open(request) as CalendarEditReviewResult.Editor
        assertEquals(oldCandidate, f.store.load(request.nativeId)); assertFalse(native(request).zoneAuthored); assertEquals(0, f.writes)
    }

    @Test fun frozenCandidateIsNeverReauthoredFromCallerZoneSuggestion() = runBlocking {
        val request = request(); val review = review(); f.services().applySuggestions(review.snapshot)
        val before = native().copy(zoneAuthored = false).record(Instant.parse("2028-06-01T00:00:01Z"))
        f.store.rows[request.nativeId] = before; f.freeze(request, EventPatch(title = FieldChange.Set("Frozen title")))
        val frozen = requireNotNull(f.store.load(request.nativeId))
        f.environment = CalendarReadState.Ready((f.environment as CalendarReadState.Ready).value.copy(calendarReadGranted = false))
        assertTrue((f.services().open(request, calendarReadGranted = false) as CalendarEditReviewResult.Editor).sourceFree)
        assertEquals(frozen, f.store.load(request.nativeId)); assertFalse(native().zoneAuthored); assertEquals(0, f.writes)
    }
    @Test fun sameRenderedFoldZonePreservesIssuedOffsetChoiceRatherThanCreatingTimeDelta() {
        val original = EventEditorController.fieldsFor(EventDraft(f.calendar.id,
            EventTime.Timed(Instant.parse("2028-11-05T05:15:00Z"), Instant.parse("2028-11-05T05:45:00Z")),
            ZoneId.of("America/New_York"), "Synthetic fold", "", "", emptyList()))
        val request = f.request(f.seed().copy(title = null, timeZone = ZoneId.of("America/New_York")), ZoneId.of("America/New_York"))
        val suggested = CalendarEditSuggestions.overlay(original, request)
        assertEquals(original, suggested); assertEquals("-04:00", suggested.startOffset)
        assertEquals(EventTime.Timed(Instant.parse("2028-11-05T05:15:00Z"), Instant.parse("2028-11-05T05:45:00Z")), EventEditorController.normalizedDraft(suggested).time)
    }

    @Test fun changingZoneIntoFoldStillRequiresExplicitOffsetAndNeverBorrowsPriorChoice() {
        val original = EventEditorController.fieldsFor(EventDraft(f.calendar.id,
            EventTime.Timed(Instant.parse("2028-11-05T01:15:00Z"), Instant.parse("2028-11-05T01:45:00Z")),
            ZoneId.of("Europe/London"), "Synthetic fold", "", "", emptyList()))
        val request = f.request(f.seed().copy(title = null, timeZone = ZoneId.of("America/New_York")), ZoneId.of("Europe/London"))
        val suggested = CalendarEditSuggestions.overlay(original, request)
        assertEquals("", suggested.startOffset); assertEquals("", suggested.endOffset)
        val failed = assertThrows(InvalidInput::class.java) { EventEditorController.normalizedDraft(suggested) }
        assertEquals(EditorProblem.AMBIGUOUS_TIME, failed.problem); assertEquals(0, f.writes)
    }

    @Test fun legacyPreparedNativeVersionIsRetainedWithoutUpgradeOrIntentInference() = runBlocking {
        val review = review(); f.store.failNative = true
        assertEquals(CalendarEditReviewResult.Unavailable, f.services().applySuggestions(review.snapshot))
        val doc = CalendarEditReviewDocument.decode(requireNotNull(f.store.load(request().reviewId)))
        val candidate = requireNotNull(doc.prepared)
        val old = OrdinaryDraftDocument.decode(candidate).copy(zoneAuthored = false).record(candidate.updatedAt).copy(version = EventEditorController.PREVIOUS_VERSION)
        val retained = doc.copy(prepared = old).record(candidate.updatedAt)
        f.store.rows[retained.id] = retained; f.store.failNative = false
        assertEquals(CalendarEditReviewResult.Unavailable, f.services().open(request()))
        assertEquals(retained, f.store.load(retained.id)); assertNull(f.store.load(request().nativeId)); assertEquals(0, f.writes)
    }

}
