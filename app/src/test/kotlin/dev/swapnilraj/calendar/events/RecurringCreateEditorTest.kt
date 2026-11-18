package dev.swapnilraj.calendar.events

import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.*
import java.time.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecurringCreateEditorTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val store = EventEditorControllerTest.Store()
    private val initial = EventDraft(CalendarId(2), EventTime.Timed(Instant.parse("2028-06-15T09:00:00Z"), Instant.parse("2028-06-15T10:00:00Z")),
        ZoneOffset.UTC, "Recurring fixture", targetIdentity = eventTestIdentity())
    private val ref = EventRef(CalendarId(2), 42, (initial.time as EventTime.Timed).start, (initial.time as EventTime.Timed).end)
    private val calls = mutableListOf<Pair<String, EventDraft>>()
    private var next: MutationResult = MutationResult.Uncertain("fixture", null)
    private var safe = false
    private var hold: CompletableDeferred<Unit>? = null
    private val writer = object : CalendarMutations {
        override suspend fun loadForEdit(ref: EventRef): EditReadResult = error("not an edit")
        override suspend fun create(operationId: String, draft: EventDraft): MutationResult { calls += operationId to draft; hold?.await(); return next }
        override suspend fun update(operationId: String, ref: EventRef, token: EditToken, patch: EventPatch): MutationResult = error("not an edit")
        override suspend fun delete(operationId: String, ref: EventRef, token: EditToken): MutationResult = error("not a deletion")
        override suspend fun recover(operationId: String): MutationResult = if (safe) MutationResult.SafeToRetry(operationId) else next
    }
    private fun editor(enabled: Boolean = true) = preparedEditor(initial, null, "recurring", writer, store, scope,
        Clock.fixed(Instant.parse("2026-11-18T00:00:00Z"), ZoneOffset.UTC), recurringCreationSupported = enabled)
    private fun repeat(editor: EventEditorController, raw: RecurrenceEditorFields = RecurrenceEditorFields(frequency = "DAILY")) {
        editor.change { it.copy(recurrence = raw) }
    }
    @After fun stop() { scope.cancel() }

    @Test fun partialRepeatFieldsSurviveProcessReopenAndBrowserWithoutWrite() = runBlocking {
        val first = editor(); val raw = RecurrenceEditorFields(frequency = "WEEKLY", interval = "", weekdays = emptyList(), endMode = "DATE", throughDate = "2028-")
        repeat(first, raw); assertTrue(first.flushDraft())
        val record = store.records.getValue("recurring"); assertEquals(EventEditorController.VERSION, record.version)
        val launch = DraftBrowserCodec.read(record).launch!!
        val restored = editor(); assertEquals(raw, restored.state.fields.recurrence); assertEquals("recurring", launch.draftId)
        restored.save(); assertEquals(EditorProblem.RECURRENCE_INVALID, restored.state.problem); assertTrue(calls.isEmpty())
    }
    @Test fun legacyOneoffStillLoadsAndFrozenBytesOmitNewField() = runBlocking {
        val first = editor(); first.flushDraft()
        val record = store.records.getValue("recurring")
        val old = record.copy(version = 1, payload = JSONObject(record.payload).apply { remove("recurrence") }.toString())
        store.records["recurring"] = old
        assertNotNull(DraftBrowserCodec.read(old).launch)
        val restored = editor(); assertFalse(restored.state.draftReadFailed); assertNull(restored.state.fields.recurrence.normalized())
        restored.save(); assertTrue(calls.isEmpty()); assertTrue(restored.canUpgrade)
        assertNotNull(restored.upgradeLegacyDraft())
        editor().save(); assertEquals(1, calls.size)
        val frozen = JSONObject(store.records.getValue("recurring").payload).getJSONObject("submission")
        assertEquals(1, frozen.getInt("version")); assertFalse(frozen.getJSONObject("draft").has("recurrence"))
        assertEquals(initial, (FrozenEditorSubmission.decode(frozen) as FrozenEditorSubmission.Create).draft)
    }
    @Test fun corruptUnknownOrMissingNewSchemaIsRetainedAndNeverDowngraded() = runBlocking {
        val first = editor(); first.flushDraft(); val record = store.records.getValue("recurring")
        val cases = listOf(JSONObject(record.payload).apply { getJSONObject("recurrence").put("schema", 99) },
            JSONObject(record.payload).apply { remove("recurrence") })
        for (raw in cases) {
            val bad = record.copy(payload = raw.toString()); store.records["recurring"] = bad
            assertNull(DraftBrowserCodec.read(bad).launch)
            val restored = editor(); assertTrue(restored.state.draftReadFailed); restored.save(); assertFalse(restored.flushDraft())
            assertEquals(bad, store.records.getValue("recurring"))
        }
        assertTrue(calls.isEmpty())
    }
    @Test fun recurringWriterIsExplicitlyDisabledByDefault() {
        val controller = editor(false); repeat(controller); controller.save()
        assertEquals(EditorProblem.RECURRENCE_UNSUPPORTED, controller.state.problem); assertNull(controller.state.recurrenceReview); assertTrue(calls.isEmpty())
    }
    @Test fun reviewIsRequiredAndDoubleConfirmationSubmitsExactlyOneFrozenTypedCreate() {
        val controller = editor(); repeat(controller); controller.save(); assertNotNull(controller.state.recurrenceReview); assertTrue(calls.isEmpty())
        hold = CompletableDeferred(); controller.confirmRecurringCreate(); controller.confirmRecurringCreate()
        assertEquals(1, calls.size); assertEquals(RecurrenceSpec(RecurrencePattern.Daily), calls.single().second.recurrence)
        val frozen = JSONObject(store.records.getValue("recurring").payload).getJSONObject("submission")
        assertEquals(2, frozen.getInt("version"))
        assertEquals(calls.single().second, (FrozenEditorSubmission.decode(frozen) as FrozenEditorSubmission.Create).draft)
        hold!!.complete(Unit)
    }
    @Test fun anyFieldChangeInvalidatesReviewWithoutWriting() {
        val controller = editor(); repeat(controller); controller.save(); controller.change { it.copy(title = "Changed after preview") }
        assertNull(controller.state.recurrenceReview); controller.confirmRecurringCreate(); assertTrue(calls.isEmpty())
        controller.save(); assertNotNull(controller.state.recurrenceReview)
    }
    @Test fun authorBoundsAndPatternMismatchCannotReachReviewOrWriter() {
        for (raw in listOf(RecurrenceEditorFields(frequency = "DAILY", interval = "1001"),
            RecurrenceEditorFields(frequency = "DAILY", endMode = "COUNT", count = "10001"),
            RecurrenceEditorFields(frequency = "WEEKLY", weekdays = listOf("MONDAY")))) {
            val controller = editor(); repeat(controller, raw); controller.save()
            assertEquals(EditorProblem.RECURRENCE_INVALID, controller.state.problem); assertNull(controller.state.recurrenceReview)
        }
        assertTrue(calls.isEmpty())
    }
    @Test fun allDayThroughDateReviewRetainsUtcDatesAndExclusiveEnd() {
        val controller = editor(); controller.change { it.copy(allDay = true, startDate = "2028-06-15", endDate = "2028-06-16",
            recurrence = RecurrenceEditorFields(frequency = "DAILY", endMode = "DATE", throughDate = "2028-06-18")) }
        controller.save(); val plan = controller.state.recurrenceReview!!
        assertEquals(ZoneOffset.UTC, plan.eventZone); assertEquals(4, plan.preview.size)
        assertEquals(EventTime.AllDay(CalendarDateRange(LocalDate.parse("2028-06-15"), LocalDate.parse("2028-06-17"))), plan.firstTime)
        assertTrue(calls.isEmpty())
    }
    @Test fun dstGapAndOverlapRequireExplicitInputAndTransitionPreviewBlocksSave() {
        val controller = editor(); controller.change { it.copy(zone = "Europe/London", startDate = "2028-03-26", endDate = "2028-03-26", startTime = "01:30", endTime = "03:00",
            recurrence = RecurrenceEditorFields(frequency = "DAILY")) }; controller.save()
        assertEquals(EditorProblem.NONEXISTENT_TIME, controller.state.problem)
        controller.change { it.copy(startDate = "2028-10-29", endDate = "2028-10-29", startTime = "01:30", endTime = "03:00") }; controller.save()
        assertEquals(EditorProblem.AMBIGUOUS_TIME, controller.state.problem)
        controller.change { it.copy(startOffset = "+01:00") }; controller.save(); assertNotNull(controller.state.recurrenceReview)
        controller.dismissRecurrenceReview()
        controller.change { it.copy(startDate = "2028-03-25", endDate = "2028-03-25", startTime = "01:30", endTime = "02:30") }; controller.save()
        assertEquals(EditorProblem.RECURRENCE_REVIEW, controller.state.problem); assertTrue(calls.isEmpty())
    }
    @Test fun frozenRecurrenceRecoveryNeverRebuildsOrResubmitsUntilExactRetryProof() {
        val first = editor(); repeat(first, RecurrenceEditorFields(frequency = "WEEKLY", weekdays = listOf("THURSDAY"), endMode = "COUNT", count = "3"))
        first.save(); first.confirmRecurringCreate(); val original = calls.single()
        val restored = editor(); restored.save(); assertEquals(1, calls.size)
        safe = true; restored.recover(); assertTrue(restored.state.retryReady); assertEquals(1, calls.size)
        restored.retryOriginal(); assertEquals(listOf(original, original), calls)
    }
}
