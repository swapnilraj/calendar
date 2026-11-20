package dev.swapnilraj.calendar.events

import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.*
import java.time.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DuplicateVisibilityBoundaryTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val time = EventTime.Timed(Instant.parse("2028-06-15T09:00:00Z"), Instant.parse("2028-06-15T10:00:00Z"))
    private val ref = EventRef(CalendarId(2), 42, time.start, time.end)
    private val details = EventDetails(EventOccurrence(ref, time, "Inherited visibility", 1), eventTestCalendar(), eventZone = ZoneOffset.UTC, availability = 0, privacy = 0)
    private val store = EventEditorControllerTest.Store()
    private val fixture = EventEditorControllerTest(); private val writer = fixture.Writer()
    private suspend fun prepare(target: CalendarId? = CalendarId(2), privacy: Int? = 0): EditorDraftLaunch =
        (prepareDuplicateDraft(details.copy(privacy = privacy), target, "copy", store, targetIdentity = target?.let { eventTestIdentity(it.value) }) as DraftPreparationResult.Ready).launch
    private fun editor(launch: EditorDraftLaunch, recovery: Boolean = false) = preparedEditor(launch.seed(LocalDate.parse("2040-01-01"), ZoneOffset.UTC),
        null, "copy", writer, store, scope, initialCalendarSelected = launch.calendarSelected, recoveryOnly = recovery, requireStoredDraft = true)
    private fun launch() = DraftBrowserCodec.read(store.records.getValue("copy")).launch!!
    @After fun stop() { scope.cancel() }
    @Test fun inheritedDefaultCannotCrossOrUnknownCalendarWithoutExplicitReview() = runBlocking {
        for (target in listOf(CalendarId(8), null)) {
            store.records.clear(); val launch = prepare(target)
            assertTrue(EditorNotice.CALENDAR_VISIBILITY_REVIEW_REQUIRED in launch.notices)
            val editor = editor(launch); assertEquals("", editor.state.fields.privacy)
            assertTrue(editor.visibilityChoiceRequired); editor.save(); assertEquals(0, writer.creates)
            assertEquals("", editor.state.fields.defaultVisibilityCalendar)
        }
    }
    @Test fun sameCalendarInheritedDefaultHasDurableApprovalAndSavesWithoutNewPolicyChoice() = runBlocking {
        val launch = prepare(); val editor = editor(launch)
        assertEquals("0", editor.state.fields.privacy); assertEquals("2", editor.state.fields.defaultVisibilityCalendar)
        assertFalse(editor.visibilityChoiceRequired); assertTrue(editor.flushDraft())
        val restored = editor(launch()); assertFalse(restored.visibilityChoiceRequired)
        assertEquals("2", restored.state.fields.defaultVisibilityCalendar)
        restored.save(); assertEquals(0, writer.createdDrafts.single().privacy)
    }
    @Test fun everyCalendarChangeInvalidatesDefaultIncludingPreviouslyExplicitDefaultChoice() = runBlocking {
        val editor = editor(prepare()); editor.selectCalendar(eventTestCalendar(8))
        assertEquals("", editor.state.fields.privacy); assertTrue(editor.visibilityChoiceRequired)
        editor.change { it.copy(privacy = "0") }; assertEquals("8", editor.state.fields.defaultVisibilityCalendar)
        assertTrue(editor.flushDraft()); val restored = editor(launch())
        assertEquals("0", restored.state.fields.privacy); assertFalse(restored.visibilityChoiceRequired)
        restored.selectCalendar(eventTestCalendar(9))
        assertTrue(restored.visibilityChoiceRequired); assertEquals("", restored.state.fields.defaultVisibilityCalendar)
        restored.save(); assertEquals(0, writer.creates)
        assertTrue(restored.flushDraft()); val afterReset = editor(launch())
        assertEquals("", afterReset.state.fields.privacy); assertTrue(afterReset.visibilityChoiceRequired)
        assertTrue(EditorNotice.CALENDAR_VISIBILITY_REVIEW_REQUIRED in afterReset.state.notices)
    }
    @Test fun explicitPortablePoliciesSurviveTargetChangesAndRestore() = runBlocking {
        for (privacy in 1..3) {
            store.records.clear(); val editor = editor(prepare(CalendarId(8), privacy))
            editor.selectCalendar(eventTestCalendar(9)); editor.flushDraft()
            val restored = editor(launch()); assertEquals(privacy.toString(), restored.state.fields.privacy)
            assertFalse(restored.visibilityChoiceRequired)
        }
        val editor = editor(launch()); editor.change { it.copy(privacy = "0") }
        assertEquals("9", editor.state.fields.defaultVisibilityCalendar)
        editor.change { it.copy(calendarId = "10", privacy = "0") }
        assertTrue(editor.visibilityChoiceRequired)
        editor.change { it.copy(privacy = "2") }; editor.selectCalendar(eventTestCalendar(11))
        assertFalse(editor.visibilityChoiceRequired); assertEquals("2", editor.state.fields.privacy)
    }
    @Test fun choosingDefaultWithoutCalendarCannotEstablishApprovalForLaterCalendar() = runBlocking {
        val editor = editor(prepare(null)); editor.change { it.copy(privacy = "0") }
        assertEquals("", editor.state.fields.defaultVisibilityCalendar)
        editor.selectCalendar(eventTestCalendar(8))
        assertTrue(editor.visibilityChoiceRequired); assertEquals("", editor.state.fields.privacy)
    }
    @Test fun ordinaryCreateAndExistingEditAreUnaffectedByCopyPolicy() = runBlocking {
        val initial = EventDraft(CalendarId(2), time, ZoneOffset.UTC, "Ordinary", privacy = 0, targetIdentity = eventTestIdentity())
        prepareNewDraft(initial, "copy", true, store)
        val ordinary = editor(launch()); ordinary.change { it.copy(calendarId = "8") }
        assertEquals("0", ordinary.state.fields.privacy); assertFalse(ordinary.visibilityChoiceRequired)
        store.records.clear()
        val source = EditableEvent(details, EditToken("source"), EditableField.entries.toSet())
        prepareEditDraft(initial, source, "copy", store)
        val edit = preparedEditor(initial, source, "copy", writer, store, scope, requireStoredDraft = true)
        edit.change { it.copy(title = "Changed") }; assertFalse(edit.visibilityChoiceRequired); edit.save()
        assertEquals(FieldChange.Keep, writer.patch!!.privacy)
    }
    @Test fun legacyMissingAndMismatchedMarkerRequireReviewWithoutAutoWriting() = runBlocking {
        for (marker in listOf<String?>(null, "99", "invalid")) {
            store.records.clear(); prepare(); val original = store.records.getValue("copy")
            val raw = JSONObject(original.payload).apply { if (marker == null) remove("defaultVisibilityIdentity") else put("defaultVisibilityIdentity", dev.swapnilraj.calendar.ui.events.CalendarIdentityJson.encode(eventTestIdentity(if (marker == "99") 99 else 100))) }
            val saved = original.copy(payload = raw.toString()); store.records["copy"] = saved
            val restored = editor(launch()); assertTrue(restored.visibilityChoiceRequired)
            assertEquals("", restored.state.fields.privacy); assertEquals(saved, store.records.getValue("copy"))
            restored.save(); assertEquals(0, writer.creates); restored.flushDraft()
            assertTrue(EditorNotice.CALENDAR_VISIBILITY_REVIEW_REQUIRED in launch().notices)
        }
    }
    @Test fun corruptMarkerAndUnknownNoticeRemainRetainedNonlaunchableAndCannotWrite() = runBlocking {
        prepare(); val original = store.records.getValue("copy")
        for (raw in listOf(JSONObject(original.payload).put("defaultVisibilityCalendar", JSONObject()),
            JSONObject(original.payload).put("notices", JSONArray(listOf("FUTURE_POLICY"))))) {
            val bad = original.copy(payload = raw.toString()); store.records["copy"] = bad
            assertNull(DraftBrowserCodec.read(bad).launch)
            val seed = EventDraft(CalendarId(2), time, ZoneOffset.UTC, "Seed", privacy = 0)
            val controller = preparedEditor(seed, null, "copy", writer, store, scope, requireStoredDraft = true)
            controller.save(); assertTrue(controller.state.draftReadFailed)
            assertEquals(bad, store.records.getValue("copy")); assertEquals(0, writer.creates)
        }
    }
    @Test fun submittedLegacyDefaultStaysExactAndAllowsReadOnlyRecoveryButNeverRetry() = runBlocking {
        val first = editor(prepare()); writer.next = MutationResult.Uncertain("ignored", null); first.save()
        val record = store.records.getValue("copy")
        val raw = JSONObject(record.payload).apply { remove("defaultVisibilityIdentity") }
        val legacy = record.copy(payload = raw.toString()); store.records["copy"] = legacy
        val restored = editor(launch(), recovery = true)
        assertEquals("0", restored.state.fields.privacy)
        assertTrue(EditorNotice.VISIBILITY_RECOVERY_ONLY in restored.state.notices)
        writer.safeRecovery = true; restored.recover(); restored.retryOriginal()
        assertFalse(restored.state.retryReady); assertEquals(1, writer.creates)
        assertEquals(legacy, store.records.getValue("copy"))
        writer.safeRecovery = false; writer.recovered = MutationResult.LocalSaved(ref); restored.recover()
        assertEquals(MutationResult.LocalSaved(ref), restored.state.result); assertTrue(store.records.isEmpty())
    }
    @Test fun matchingApprovedSubmittedDefaultRetainsExactProofGatedRetry() = runBlocking {
        val first = editor(prepare()); writer.next = MutationResult.Uncertain("ignored", null); first.save()
        val original = store.records.getValue("copy")
        val restored = editor(launch(), recovery = true); writer.safeRecovery = true; restored.recover()
        assertTrue(restored.state.retryReady); writer.next = MutationResult.PermissionRequired; restored.retryOriginal()
        assertEquals(2, writer.creates); assertEquals(1, writer.ids.distinct().size)
        assertEquals(listOf(0, 0), writer.createdDrafts.map { it.privacy })
        assertEquals(original, store.records.getValue("copy"))
    }
}
