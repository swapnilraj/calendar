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
class DraftBrowserTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val initial = EventDraft(CalendarId(2), EventTime.Timed(Instant.parse("2028-06-15T09:00:00Z"), Instant.parse("2028-06-15T10:00:00Z")), ZoneOffset.UTC, "Future appointment", targetIdentity = eventTestIdentity())
    private val ref = EventRef(CalendarId(2), 42, (initial.time as EventTime.Timed).start, (initial.time as EventTime.Timed).end)
    private val existing = EditableEvent(EventDetails(EventOccurrence(ref, initial.time, initial.title, 1), eventTestCalendar()), EditToken("original"), EditableField.entries.toSet())
    private val store = EventEditorControllerTest.Store()
    private val fixture = EventEditorControllerTest()
    private val writer = fixture.Writer()
    private fun editor(source: EditableEvent? = null, recovery: Boolean = false, target: EventRef? = ref, required: Boolean = false, seed: EventDraft = initial) =
        preparedEditor(seed, source, "draft", writer, store, scope, recoveryOnly = recovery, recoveryRef = target, requireStoredDraft = required)
    private suspend fun prepare(edit: Boolean = false, selected: Boolean = true): EditorDraftLaunch =
        ((if (edit) prepareEditDraft(initial, existing, "draft", store) else prepareNewDraft(initial, "draft", selected, store)) as DraftPreparationResult.Ready).launch
    @After fun stop() { scope.cancel() }

    @Test fun preparePersistsIdentityBeforeOpeningAndDoesNotChooseCalendar() = runBlocking {
        val launch = prepare(selected = false)
        assertFalse(launch.calendarSelected)
        val firstId = JSONObject(store.records.getValue("draft").payload).getString("operationId")
        val opened = editor(required = true)
        assertEquals(firstId, opened.pendingOperationId)
        assertEquals("", opened.state.fields.calendarId)
        opened.save(); assertEquals(EditorProblem.CALENDAR_REQUIRED, opened.state.problem)
        assertEquals(0, writer.creates)
    }
    @Test fun preparationReusesIncompleteRawDraftAndNeverReplacesUnknownRecord() = runBlocking {
        prepare(); val first = editor(required = true)
        first.change { it.copy(startDate = "2028-", alerts = "10,", title = "Unfinished") }; assertTrue(first.flushDraft())
        val before = store.records.getValue("draft")
        val launch = prepare()
        assertEquals("2028-", launch.rawDate); assertEquals(before, store.records.getValue("draft"))
        val restored = editor(required = true, seed = launch.seed(LocalDate.parse("2040-01-01"), ZoneId.of("Asia/Tokyo")))
        assertEquals("2028-", restored.state.fields.startDate)
        store.records["draft"] = before.copy(version = 999)
        assertEquals(DraftPreparationResult.Unavailable, prepareNewDraft(initial, "draft", true, store))
        assertEquals(999, store.records.getValue("draft").version)
    }
    @Test fun writeOrIgnoredWriteNeverExposesLaunch() = runBlocking {
        store.failWrites = true
        assertEquals(DraftPreparationResult.Unavailable, prepareNewDraft(initial, "draft", true, store))
        val ignored = object : DraftStore {
            override suspend fun load(id: String): DraftRecord? = null
            override suspend fun list() = emptyList<DraftRecord>()
            override suspend fun upsert(record: DraftRecord) {}
            override suspend fun delete(id: String) {}
        }
        assertEquals(DraftPreparationResult.Unavailable, prepareNewDraft(initial, "draft", true, ignored))
        assertEquals(0, writer.creates)
    }
    @Test fun missingCompletedCreateRecordCannotSeedSecondSubmission() = runBlocking {
        prepare(); val first = editor(required = true); first.save()
        assertEquals(1, writer.creates); assertTrue(store.records.isEmpty())
        val restored = editor(required = true); restored.change { it.copy(title = "Duplicate") }; restored.save()
        assertTrue(restored.state.draftReadFailed); assertEquals(1, writer.creates)
    }
    @Test fun missingEditRecordBlocksDirectDeletionAndSave() {
        val restored = editor(existing, required = true)
        restored.requestDelete(); restored.confirmDelete(); restored.save()
        assertFalse(restored.state.confirmDelete); assertEquals(0, writer.deletes); assertEquals(0, writer.updates)
    }
    @Test fun browserPreservesExactReferenceAndPartialDateWithoutWriting() = runBlocking {
        prepare(edit = true); val first = editor(existing, required = true)
        first.change { it.copy(startDate = "unfinished") }; first.flushDraft()
        val before = store.records.getValue("draft")
        val launch = DraftBrowserCodec.read(before).launch!!
        assertEquals(ref, launch.ref); assertEquals("draft", launch.draftId)
        assertEquals("unfinished", launch.rawDate); assertFalse(launch.needsRecovery)
        assertEquals(before, store.records.getValue("draft")); assertEquals(0, writer.updates)
    }
    private suspend fun pendingDelete(): EditorDraftLaunch {
        prepare(edit = true); writer.deleteResult = MutationResult.Uncertain("ignored", ref.eventId)
        editor(existing, required = true).apply { requestDelete(); confirmDelete() }
        return DraftBrowserCodec.read(store.records.getValue("draft")).launch!!
    }
    @Test fun missingLiveSourceCanResolveCommittedDeleteReadOnly() = runBlocking {
        val launch = pendingDelete(); assertTrue(launch.needsRecovery); assertEquals(ref, launch.ref)
        writer.editRead = EditReadResult.Unavailable; writer.recovered = MutationResult.LocalDeleted(ref)
        val recovered = editor(recovery = true, required = true)
        recovered.recover()
        assertTrue(recovered.state.result is MutationResult.LocalDeleted)
        assertEquals(1, writer.deletes); assertTrue(store.records.isEmpty())
    }
    @Test fun recoveryOnlyRetainsRawRecordAndAllNormalActionsStayLocked() = runBlocking {
        pendingDelete(); val original = store.records.getValue("draft")
        val recovered = editor(recovery = true, required = true)
        writer.recovered = MutationResult.Unavailable; recovered.recover()
        recovered.change { it.copy(title = "Unsafe replacement") }; recovered.save(); recovered.requestDelete(); recovered.confirmDelete()
        assertNull(recovered.discardAndReload()); assertTrue(recovered.prepareDismiss())
        assertEquals(initial.title, recovered.state.fields.title); assertEquals(1, writer.deletes)
        assertEquals(original, store.records.getValue("draft"))
    }
    @Test fun explicitProofRetriesOnlyOriginalCommandEvenAfterKnownFailure() = runBlocking {
        pendingDelete(); val original = store.records.getValue("draft")
        val recovered = editor(recovery = true, required = true)
        writer.safeRecovery = true; recovered.recover(); assertTrue(recovered.state.retryReady)
        writer.deleteResult = MutationResult.PermissionRequired; recovered.retryOriginal()
        recovered.change { it.copy(title = "Unsafe") }; recovered.save(); recovered.requestDelete(); recovered.confirmDelete()
        assertEquals(2, writer.deletes); assertFalse(recovered.state.retryReady)
        recovered.recover(); assertTrue(recovered.state.retryReady)
        assertEquals(1, writer.ids.distinct().size); assertEquals(ref to existing.token, writer.deletedCommands.last())
        assertEquals(original, store.records.getValue("draft"))
    }
    @Test fun changedRecordOrMismatchedRecoveryTargetFailsClosed() = runBlocking {
        pendingDelete(); val wrong = editor(recovery = true, target = ref.copy(eventId = 99), required = true)
        wrong.recover(); wrong.retryOriginal(); assertTrue(wrong.state.draftReadFailed)
        val right = editor(recovery = true, required = true); writer.safeRecovery = true; right.recover()
        val record = store.records.getValue("draft")
        store.records["draft"] = record.copy(payload = JSONObject(record.payload).put("title", "Changed externally").toString())
        right.retryOriginal(); assertEquals(1, writer.deletes)
        assertEquals("Changed externally", JSONObject(store.records.getValue("draft").payload).getString("title"))
    }
    @Test fun legacyPendingDeleteCanRecoverWithoutLosingCanonicalReference() = runBlocking {
        pendingDelete(); val record = store.records.getValue("draft")
        val raw = JSONObject(record.payload).apply { remove("submission") }
        store.records["draft"] = record.copy(version = 4, payload = raw.toString())
        val launch = DraftBrowserCodec.read(store.records.getValue("draft")).launch!!
        assertEquals(ref, launch.ref); assertTrue(launch.needsRecovery)
        val recovered = editor(recovery = true, required = true); writer.safeRecovery = true; recovered.recover(); recovered.retryOriginal()
        assertFalse(recovered.state.retryReady); assertEquals(1, writer.deletes)
        assertEquals(raw.toString(), store.records.getValue("draft").payload)
        writer.safeRecovery = false; writer.recovered = MutationResult.LocalDeleted(ref); recovered.recover()
        assertTrue(recovered.state.result is MutationResult.LocalDeleted)
    }
    @Test fun movedEventCanResolveOriginalUpdateWithoutLiveSource() = runBlocking {
        prepare(edit = true); writer.next = MutationResult.Uncertain("ignored", ref.eventId)
        val first = editor(existing, required = true)
        first.change { it.copy(startTime = "11:00", endTime = "12:00") }; first.save()
        val launch = DraftBrowserCodec.read(store.records.getValue("draft")).launch!!
        assertEquals(ref, launch.ref); assertTrue(launch.needsRecovery)
        val moved = ref.copy(instanceStart = ref.instanceStart.plusSeconds(7200), instanceEnd = ref.instanceEnd.plusSeconds(7200))
        writer.recovered = MutationResult.LocalSaved(moved)
        val recovered = editor(recovery = true, required = true); assertEquals("11:00", recovered.state.fields.startTime)
        recovered.recover(); assertEquals(MutationResult.LocalSaved(moved), recovered.state.result)
        assertEquals(1, writer.updates); assertEquals(0, writer.creates)
    }
    @Test fun pendingCreateUsesFrozenSeedAndReadOnlyRecovery() = runBlocking {
        prepare(); writer.next = MutationResult.Uncertain("ignored", null); editor(required = true).save()
        val launch = DraftBrowserCodec.read(store.records.getValue("draft")).launch!!
        assertNull(launch.ref); assertTrue(launch.needsRecovery)
        assertEquals(initial, launch.seed(LocalDate.parse("2040-01-01"), ZoneId.of("Asia/Tokyo")))
        val recovered = editor(recovery = true, target = null, required = true)
        writer.recovered = MutationResult.LocalSaved(ref); recovered.recover()
        assertEquals(1, writer.creates); assertEquals(MutationResult.LocalSaved(ref), recovered.state.result)
    }
    @Test fun frozenCommandContradictingRawSubmittedFieldsCannotLaunch() = runBlocking {
        prepare(); writer.next = MutationResult.Uncertain("ignored", null); editor(required = true).save()
        val record = store.records.getValue("draft")
        val bad = record.copy(payload = JSONObject(record.payload).put("title", "Different request").toString())
        assertNull(DraftBrowserCodec.read(bad).launch)
    }
    @Test fun editPreparationRetainsStaleDraftForExplicitReview() = runBlocking {
        prepare(edit = true); val first = editor(existing, required = true)
        first.change { it.copy(title = "My retained draft") }; first.flushDraft()
        val freshDraft = initial.copy(location = "Remote office")
        val fresh = existing.copy(details = existing.details.copy(location = "Remote office"), token = EditToken("fresh"))
        assertTrue(prepareEditDraft(freshDraft, fresh, "draft", store) is DraftPreparationResult.Ready)
        val restored = editor(fresh, required = true, seed = freshDraft)
        assertTrue(restored.state.reviewRequired); assertEquals("My retained draft", restored.state.fields.title)
        restored.save(); assertEquals(0, writer.updates)
    }
    @Test fun corruptAndFutureRecordsAreRetainedAndCannotLaunch() = runBlocking {
        prepare(); val record = store.records.getValue("draft")
        val bad = listOf(record.copy(version = 99), record.copy(payload = "not json"),
            record.copy(payload = JSONObject(record.payload).put("target", "unexpected").toString()),
            record.copy(payload = JSONObject(record.payload).put("allDay", "maybe").toString()))
        bad.forEach { assertNull(DraftBrowserCodec.read(it).launch) }
        assertEquals(record, store.records.getValue("draft"))
    }
}
