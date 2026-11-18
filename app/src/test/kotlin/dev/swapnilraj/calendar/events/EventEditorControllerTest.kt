package dev.swapnilraj.calendar.events

import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.*
import java.time.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EventEditorControllerTest {
    private val scopes = mutableListOf<CoroutineScope>()
    private val initial = EventDraft(CalendarId(2), EventTime.Timed(Instant.parse("2028-06-15T09:00:00Z"),
        Instant.parse("2028-06-15T10:00:00Z")), ZoneOffset.UTC, "Future appointment", alertMinutes = listOf(10))
    private val ref = EventRef(CalendarId(2), 42, (initial.time as EventTime.Timed).start, (initial.time as EventTime.Timed).end)
    private fun controller(store: Store = Store(), writer: Writer = Writer(), existing: EditableEvent? = null,
        draft: EventDraft = initial): EventEditorController {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also(scopes::add)
        return EventEditorController(draft, existing, "draft-fixture", writer, store, scope,
            Clock.fixed(Instant.parse("2026-11-18T00:00:00Z"), ZoneOffset.UTC))
    }
    @After fun cleanUp() { scopes.forEach { it.cancel() } }
    private fun editable() = EditableEvent(EventDetails(EventOccurrence(ref, initial.time, initial.title, 1), null),
        EditToken("source-version"), EditableField.entries.toSet())

    @Test fun openingFutureDatePreservesDateAndSixtyMinuteDuration() {
        val controller = controller()
        assertEquals("2028-06-15", controller.state.fields.startDate)
        assertEquals("09:00", controller.state.fields.startTime)
        assertEquals("10:00", controller.state.fields.endTime)
        assertEquals(initial, EventEditorController.normalizedDraft(controller.state.fields))
    }
    @Test fun allDayLastDateIsInclusiveInFormAndExclusiveOnSave() {
        val draft = initial.copy(time = EventTime.AllDay(CalendarDateRange(LocalDate.parse("2028-06-15"), LocalDate.parse("2028-06-18"))))
        val fields = controller(draft = draft).state.fields
        assertEquals("2028-06-17", fields.endDate)
        val normalized = EventEditorController.normalizedDraft(fields)
        assertEquals(draft.time, normalized.time)
        assertEquals(ZoneOffset.UTC, normalized.eventZone)
    }
    @Test fun invalidPartialRawInputRestoresWithoutSubmittingEvent() = runBlocking {
        val store = Store(); val writer = Writer(); val first = controller(store, writer)
        first.change { it.copy(startDate = "2028-", alerts = "10,not-a-number", description = "Unfinished\nnotes") }
        assertTrue(first.flushDraft())
        val restored = controller(store, writer)
        assertEquals("2028-", restored.state.fields.startDate)
        assertEquals("10,not-a-number", restored.state.fields.alerts)
        assertEquals("Unfinished\nnotes", restored.state.fields.description)
        assertEquals(0, writer.creates)
        restored.save()
        assertEquals(EditorProblem.INVALID_INPUT, restored.state.problem)
        assertEquals(0, writer.creates)
    }
    @Test fun missingClockTimeIsRejectedAndRepeatedTimeRequiresChoice() {
        val controller = controller()
        controller.change { it.copy(zone = "Europe/London", startDate = "2026-03-29", endDate = "2026-03-29",
            startTime = "01:30", endTime = "03:00") }
        controller.save()
        assertEquals(EditorProblem.NONEXISTENT_TIME, controller.state.problem)
        controller.change { it.copy(startDate = "2026-10-25", endDate = "2026-10-25", startTime = "01:45", endTime = "01:15") }
        controller.save()
        assertEquals(EditorProblem.AMBIGUOUS_TIME, controller.state.problem)
        controller.change { it.copy(startOffset = "+01:00", endOffset = "Z") }
        val draft = EventEditorController.normalizedDraft(controller.state.fields)
        assertEquals(Duration.ofMinutes(30), (draft.time as EventTime.Timed).duration)
    }
    @Test fun changingDatesClearsPreviouslySelectedAmbiguousOffsets() {
        val controller = controller()
        controller.change { it.copy(startDate = "2026-10-25", startTime = "01:30", zone = "Europe/London") }
        assertEquals("", controller.state.fields.startOffset)
    }
    @Test fun doubleSaveCreatesOnlyOnceAndSuccessfulControllerIsTerminal() = runBlocking {
        val gate = CompletableDeferred<Unit>(); val writer = Writer().apply { hold = gate }
        val controller = controller(writer = writer)
        controller.save(); controller.save()
        assertEquals(1, writer.creates)
        assertTrue(controller.state.saving)
        gate.complete(Unit); yield()
        controller.save()
        assertEquals(1, writer.creates)
        assertTrue(controller.state.result is MutationResult.LocalSaved)
    }
    @Test fun changedFieldsBecomePatchesAndUntouchedDataIsKept() {
        val writer = Writer(); val controller = controller(writer = writer, existing = editable())
        controller.change { it.copy(title = "Changed title") }
        controller.save()
        assertEquals(FieldChange.Set("Changed title"), writer.patch?.title)
        assertEquals(FieldChange.Keep, writer.patch?.description)
        assertEquals(FieldChange.Keep, writer.patch?.location)
        assertEquals(FieldChange.Keep, writer.patch?.time)
        assertEquals(FieldChange.Keep, writer.patch?.alertMinutes)
    }
    @Test fun conflictRetainsDraftAndSamePayloadCanRetrySameOperation() {
        val store = Store(); val writer = Writer().apply { next = MutationResult.Conflict(null) }
        val controller = controller(store, writer)
        controller.save(); val id = writer.ids.single()
        assertEquals(EditorProblem.CONFLICT, controller.state.problem)
        assertEquals(initial.title, controller.state.fields.title)
        assertTrue(store.records.isNotEmpty())
        controller.save()
        assertEquals(listOf(id, id), writer.ids)
        controller.change { it.copy(title = "A deliberate revision") }
        controller.save()
        assertNotEquals(id, writer.ids.last())
    }
    @Test fun uncertainCreateRestoresLockedAndRecoveryNeverResubmits() {
        val store = Store(); val writer = Writer().apply { next = MutationResult.Uncertain("fixture-op", null) }
        val first = controller(store, writer)
        first.save(); val operation = writer.ids.single()
        val restored = controller(store, writer)
        assertTrue(restored.state.recoveryRequired)
        assertEquals(operation, restored.pendingOperationId)
        restored.save(); assertEquals(1, writer.creates)
        writer.recovered = MutationResult.LocalSaved(ref)
        restored.recover()
        assertEquals(listOf(operation), writer.recoveryIds)
        assertTrue(restored.state.result is MutationResult.LocalSaved)
        assertEquals(1, writer.creates)
        assertTrue(store.records.isEmpty())
    }
    @Test fun failedRecoveryKeepsSubmissionLocked() {
        val writer = Writer().apply { next = MutationResult.Uncertain("fixture-op", null); recovered = MutationResult.PermissionRequired }
        val controller = controller(writer = writer)
        controller.save(); controller.recover(); controller.save()
        assertEquals(1, writer.creates)
        assertTrue(controller.state.recoveryRequired)
        assertEquals(EditorProblem.PERMISSION, controller.state.problem)
    }
    @Test fun permissionDenialRetainsDraftAndUnexpectedWriteExceptionIsUncertain() {
        val store = Store(); val writer = Writer().apply { next = MutationResult.PermissionRequired }
        val controller = controller(store, writer)
        controller.save()
        assertEquals(EditorProblem.PERMISSION, controller.state.problem)
        assertTrue(store.records.isNotEmpty())
        writer.throwOnCreate = true
        controller.save()
        assertEquals(EditorProblem.UNCERTAIN, controller.state.problem)
        assertTrue(controller.state.recoveryRequired)
    }
    @Test fun dismissFlushWritesOnlyDraftAndDeleteRequiresConfirmation() = runBlocking {
        val writer = Writer(); val controller = controller(writer = writer, existing = editable())
        controller.change { it.copy(title = "Draft only") }
        controller.flushDraft()
        assertEquals(0, writer.updates); assertEquals(0, writer.deletes)
        controller.confirmDelete(); assertEquals(0, writer.deletes)
        controller.requestDelete(); controller.cancelDelete(); controller.confirmDelete()
        assertEquals(0, writer.deletes)
        controller.requestDelete(); controller.confirmDelete()
        assertEquals(1, writer.deletes)
    }
    @Test fun unchangedPointTimeAndDefaultReminderSurviveTitleOnlyEdit() {
        val point = initial.copy(time = EventTime.Timed(ref.instanceStart, ref.instanceStart), alertMinutes = listOf(-1))
        val source = editable().copy(details = editable().details.copy(occurrence = editable().details.occurrence.copy(time = point.time)))
        val writer = Writer(); val controller = controller(writer = writer, existing = source, draft = point)
        controller.change { it.copy(title = "Only the title changes") }; controller.save()
        assertEquals(1, writer.updates)
        assertEquals(FieldChange.Keep, writer.patch?.time)
        assertEquals(FieldChange.Keep, writer.patch?.alertMinutes)
    }
    @Test fun zeroDurationCreateIsRejected() {
        val writer = Writer(); val controller = controller(writer = writer)
        controller.change { it.copy(endTime = it.startTime) }; controller.save()
        assertEquals(0, writer.creates)
        assertEquals(EditorProblem.INVALID_INPUT, controller.state.problem)
    }
    @Test fun recurringSourceHasNoEnabledWriteWorkflow() {
        val source = editable().copy(details = editable().details.copy(recurrenceRule = "FREQ=WEEKLY"))
        val writer = Writer(); val controller = controller(writer = writer, existing = source)
        controller.save(); controller.requestDelete(); controller.confirmDelete()
        assertEquals(EditorProblem.UNSUPPORTED, controller.state.problem)
        assertEquals(0, writer.updates); assertEquals(0, writer.deletes)
    }
    @Test fun changedSourceTokenLocksStaleDraftUntilExplicitDiscardAndReload() = runBlocking {
        val store = Store(); val writer = Writer()
        val oldDraft = initial.copy(location = "Original location")
        val oldSource = editable().copy(details = editable().details.copy(location = "Original location"))
        val first = controller(store, writer, oldSource, oldDraft)
        first.change { it.copy(title = "My retained title") }; first.flushDraft()
        val retainedPayload = store.records.getValue("draft-fixture").payload
        val fresh = oldSource.copy(details = oldSource.details.copy(location = "Remote location"), token = EditToken("fresh"))
        val restored = controller(store, writer, fresh, initial.copy(location = "Remote location"))
        assertTrue(restored.state.reviewRequired)
        assertEquals("Remote location", restored.state.reviewLatest?.location)
        assertEquals("My retained title", restored.state.fields.title)
        assertEquals("Original location", restored.state.fields.location)
        restored.change { it.copy(title = "Cannot modify locked review") }; restored.save()
        assertEquals(0, writer.updates)
        assertTrue(restored.prepareDismiss())
        assertEquals(retainedPayload, store.records.getValue("draft-fixture").payload)
        writer.editRead = EditReadResult.Ready(fresh)
        assertEquals(EditReadResult.Ready(fresh), restored.discardAndReload())
        assertTrue(store.records.isEmpty())
        val freshEditor = controller(store, writer, fresh, initial.copy(location = "Remote location"))
        freshEditor.change { it.copy(title = "A newly reviewed edit") }; freshEditor.save()
        assertEquals(1, writer.updates)
        assertEquals(FieldChange.Keep, writer.patch?.location)
    }
    @Test fun unchangedCanonicalBaselineAndRightsAllowFreshTokenWithoutLosingDraft() = runBlocking {
        val store = Store(); val writer = Writer()
        val first = controller(store, writer, editable())
        first.change { it.copy(title = "Recovered title") }; first.flushDraft()
        val oldOperation = first.pendingOperationId
        val freshSource = editable().copy(token = EditToken("new-token-for-identical-source"))
        val restored = controller(store, writer, freshSource)
        assertFalse(restored.state.reviewRequired)
        assertEquals("Recovered title", restored.state.fields.title)
        assertNotEquals(oldOperation, restored.pendingOperationId)
        restored.save()
        assertEquals(1, writer.updates)
        assertEquals(FieldChange.Set("Recovered title"), writer.patch?.title)
        assertEquals(FieldChange.Keep, writer.patch?.location)
        assertEquals(FieldChange.Keep, writer.patch?.time)
    }
    @Test fun changedAllowedFieldsOrOccurrenceIdentityCannotAdoptFreshToken() = runBlocking {
        val store = Store(); val writer = Writer()
        val first = controller(store, writer, editable())
        first.change { it.copy(title = "Retained title") }; first.flushDraft()
        val payload = store.records.getValue("draft-fixture").payload
        val restricted = editable().copy(token = EditToken("fresh"), allowedFields = setOf(EditableField.TITLE))
        val rightsChanged = controller(store, writer, restricted)
        assertTrue(rightsChanged.state.reviewRequired); rightsChanged.save()
        val movedRef = ref.copy(instanceStart = ref.instanceStart.plusSeconds(60), instanceEnd = ref.instanceEnd.plusSeconds(60))
        val changedIdentity = editable().copy(token = EditToken("fresh"), details = editable().details.copy(
            occurrence = editable().details.occurrence.copy(ref = movedRef)))
        val moved = controller(store, writer, changedIdentity)
        assertTrue(moved.state.reviewRequired); moved.save()
        assertEquals(0, writer.updates)
        assertEquals(payload, store.records.getValue("draft-fixture").payload)
    }
    @Test fun sourceDetailChangesLockEvenIfCallerSuppliesAnOldNormalizedSeed() = runBlocking {
        val store = Store(); val writer = Writer()
        val first = controller(store, writer, editable())
        first.change { it.copy(title = "Retained title") }; first.flushDraft()
        val remote = editable().copy(token = EditToken("fresh"), details = editable().details.copy(location = "Remote change"))
        val restored = controller(store, writer, remote, initial)
        assertTrue(restored.state.reviewRequired); restored.save()
        assertEquals(0, writer.updates)
    }

    @Test fun failedDraftFlushPreventsDismissalAndPreservesInMemoryInput() = runBlocking {
        val store = Store(); val controller = controller(store)
        controller.change { it.copy(description = "Unsaved notes") }
        store.failWrites = true
        assertFalse(controller.prepareDismiss())
        assertEquals(EditorProblem.DRAFT_UNAVAILABLE, controller.state.problem)
        assertEquals("Unsaved notes", controller.state.fields.description)
        store.failWrites = false
        assertTrue(controller.prepareDismiss())
    }

    @Test fun unknownDraftVersionIsNotOverwrittenOrSubmitted() = runBlocking {
        val store = Store().apply { records["draft-fixture"] = DraftRecord("draft-fixture", 99, "future", Instant.EPOCH) }
        val writer = Writer(); val controller = controller(store, writer)
        controller.change { it.copy(title = "No") }; controller.save(); controller.flushDraft()
        assertEquals(EditorProblem.DRAFT_VERSION, controller.state.problem)
        assertEquals("future", store.records["draft-fixture"]?.payload)
        assertEquals(0, writer.creates)
    }
    @Test fun storageFailurePreventsProviderSubmission() {
        val writer = Writer(); val store = Store().apply { failWrites = true }
        val controller = controller(store, writer)
        controller.save()
        assertEquals(0, writer.creates)
        assertEquals(EditorProblem.DRAFT_UNAVAILABLE, controller.state.problem)
    }
    class Store : DraftStore {
        val records = mutableMapOf<String, DraftRecord>(); var failWrites = false
        override suspend fun load(id: String) = records[id]
        override suspend fun list() = records.values.toList()
        override suspend fun upsert(record: DraftRecord) { if (failWrites) error("fixture storage failure"); records[record.id] = record }
        override suspend fun delete(id: String) { records.remove(id) }
    }
    inner class Writer : CalendarMutations {
        var creates = 0; var updates = 0; var deletes = 0; var throwOnCreate = false
        val ids = mutableListOf<String>(); val recoveryIds = mutableListOf<String>()
        var next: MutationResult = MutationResult.LocalSaved(ref)
        var recovered: MutationResult = MutationResult.Uncertain("fixture-op", null)
        var editRead: EditReadResult? = null
        var patch: EventPatch? = null; var hold: CompletableDeferred<Unit>? = null
        override suspend fun loadForEdit(ref: EventRef) = editRead ?: EditReadResult.Ready(editable())
        override suspend fun create(operationId: String, draft: EventDraft): MutationResult {
            creates++; ids += operationId; hold?.await(); if (throwOnCreate) error("fixture interruption"); return next
        }
        override suspend fun update(operationId: String, ref: EventRef, token: EditToken, patch: EventPatch): MutationResult {
            updates++; ids += operationId; this.patch = patch; return next
        }
        override suspend fun delete(operationId: String, ref: EventRef, token: EditToken): MutationResult {
            deletes++; ids += operationId; return MutationResult.LocalDeleted(ref)
        }
        override suspend fun recover(operationId: String): MutationResult { recoveryIds += operationId; return recovered }
    }
}
