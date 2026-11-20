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
class OrdinaryAtomicRetirementTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val clock = Clock.fixed(Instant.parse("2030-06-15T12:00:00.123456789Z"), ZoneOffset.UTC)
    private val initial = EventDraft(CalendarId(2), EventTime.Timed(Instant.parse("2030-06-15T09:00:00Z"), Instant.parse("2030-06-15T10:00:00Z")),
        ZoneOffset.UTC, "Synthetic event", targetIdentity = eventTestIdentity())
    private val ref = EventRef(CalendarId(2), 42, (initial.time as EventTime.Timed).start, (initial.time as EventTime.Timed).end)
    private val source = EditableEvent(EventDetails(EventOccurrence(ref, initial.time, initial.title, 1), eventTestCalendar()), EditToken("A"), EditableField.entries.toSet())
    private val store = EventEditorControllerTest.Store()
    private val writer = Port()
    private val retirement = Fence()
    private suspend fun prepare(edit: Boolean = false, id: String = "draft") =
        ((if (edit) prepareEditDraft(initial, source, id, store, clock) else prepareNewDraft(initial, id, true, store, clock)) as DraftPreparationResult.Ready).launch
    private fun controller(launch: EditorDraftLaunch, existing: EditableEvent? = if (launch.ref != null) source else null,
        permission: suspend () -> Boolean = { true }, port: OperationRetirement? = retirement) =
        EventEditorController(initial, existing, launch.draftId, writer, store, scope, clock,
            recoveryOnly = launch.needsRecovery, recoveryRef = launch.ref, requireStoredDraft = true,
            expectedGeneration = launch.generation, retirement = port, requestWritePermission = permission)
    @After fun close() { scope.cancel() }

    @Test fun factoryRequiresAtomicStoreAndNeverBlindlyOverwritesCollision() = runBlocking {
        val plain = object : DraftStore by store {}
        assertEquals(DraftPreparationResult.Unavailable, prepareNewDraft(initial, "plain", true, plain, clock))
        assertTrue(store.records.isEmpty())
        val first = prepare(); val record = store.records.getValue("draft")
        val again = prepareNewDraft(initial.copy(title = "Different seed"), "draft", true, store, clock) as DraftPreparationResult.Ready
        assertEquals(first, again.launch); assertEquals(record, store.records["draft"])
        assertNotNull(first.generation)
    }
    @Test fun gridPreparationNeverReusesAnotherExistingOrRacingRawDraft() = runBlocking {
        prepare(edit = true); val retained = store.records.getValue("draft")
        val fields = EventEditorController.fieldsFor(initial).copy(startTime = "11:00", endTime = "12:00")
        assertEquals(DraftPreparationResult.Unavailable, prepareEditorFieldsDraft(initial, source, "draft", true, fields, store, clock))
        assertEquals(retained, store.records["draft"])
        store.records.clear()
        store.beforeCas = { id, _, candidate -> if (candidate != null) store.records[id] = candidate.copy(payload = JSONObject(candidate.payload).put("title", "Racing draft").toString()) }
        assertEquals(DraftPreparationResult.Unavailable, prepareEditorFieldsDraft(initial, source, "draft", true, fields, store, clock))
        assertEquals("Racing draft", JSONObject(store.records.getValue("draft").payload).getString("title"))
    }
    @Test fun newOnlyPreparationReturnsExactOwnCandidateForConfirmedAndUnknownAcknowledgments() = runBlocking {
        for (unknown in listOf(false, true)) {
            store.records.clear(); store.unknownAcknowledgment = unknown
            val fields = EventEditorController.fieldsFor(initial).copy(startTime = "11:00", endTime = "12:00")
            val ready = prepareEditorFieldsDraft(initial, source, "grid", true, fields, store, clock) as DraftPreparationResult.Ready
            assertEquals(store.records.getValue("grid"), ready.exactRecord)
            assertEquals(ready.launch, DraftBrowserCodec.read(requireNotNull(ready.exactRecord)).launch)
            assertEquals(if (unknown) DraftChangeResult.Unavailable else DraftChangeResult.Applied, store.compareAndSet("grid", ready.exactRecord, null))
            assertNull(store.records["grid"])
        }
    }
    @Test fun cancelledPreparationProofCannotDeleteRevisedOrFrozenSuccessor() = runBlocking {
        for (freeze in listOf(false, true)) {
            store.records.clear()
            val ready = prepareEditorFieldsDraft(initial, source, "grid", true, EventEditorController.fieldsFor(initial), store, clock) as DraftPreparationResult.Ready
            val editor = controller(ready.launch)
            if (freeze) editor.save() else { editor.change { it.copy(title = "New retained raw input") }; assertTrue(editor.flushDraft()) }
            val successor = store.records.getValue("grid")
            assertNotEquals(ready.exactRecord, successor)
            assertEquals(DraftChangeResult.Changed, store.compareAndSet("grid", ready.exactRecord, null))
            assertEquals(successor, store.records["grid"])
        }
    }
    @Test fun legacyFrozenPreparedFlagStillLaunchesSourceFreeAndCannotNormalSave() = runBlocking {
        val original = controller(prepare(edit = true)); original.requestDelete(); original.confirmDelete()
        val record = store.records.getValue("draft")
        val raw = JSONObject(record.payload).put("submitted", false)
        listOf("feature", "schema", "generation", "revision").forEach(raw::remove)
        store.records["draft"] = record.copy(version = 4, payload = raw.toString())
        val launch = DraftBrowserCodec.read(store.records.getValue("draft")).launch!!
        assertTrue(launch.needsRecovery)
        val restored = controller(launch, existing = null); restored.save(); restored.requestDelete(); restored.confirmDelete()
        assertEquals(1, writer.writes); assertEquals(0, writer.loads); assertTrue(restored.state.recoveryRequired)
    }
    @Test fun missingRecordAndWrongGenerationNeverSeedOrWrite() = runBlocking {
        val launch = prepare(); store.records.clear()
        val missing = controller(launch); missing.save(); missing.requestDelete(); missing.confirmDelete()
        assertTrue(missing.state.draftReadFailed); assertTrue(store.records.isEmpty()); assertEquals(0, writer.writes)
        val fresh = prepare(); val wrong = controller(fresh.copy(generation = java.util.UUID.randomUUID().toString()))
        assertTrue(wrong.state.draftReadFailed); assertEquals("", wrong.state.fields.title); wrong.save(); assertEquals(0, writer.writes)
    }
    @Test fun staleRawControllerCannotOverwriteNewRevisionEvenWhenTimestampMatches() = runBlocking {
        val launch = prepare(); val old = controller(launch); val fresh = controller(launch)
        fresh.change { it.copy(title = "Replacement revision") }; assertTrue(fresh.flushDraft())
        val retained = store.records.getValue("draft")
        old.change { it.copy(title = "Stale replay") }; assertFalse(old.flushDraft()); old.save()
        assertEquals(retained, store.records["draft"]); assertEquals(0, writer.writes)
        assertEquals(clock.instant(), retained.updatedAt)
    }
    @Test fun unknownCasAcknowledgmentNeedsExactOwnRevisionReadbackBeforeDispatch() = runBlocking {
        val launch = prepare(); store.unknownAcknowledgment = true
        val editor = controller(launch); editor.save()
        assertEquals(1, writer.writes); assertTrue(editor.state.recoveryRequired)
        val frozen = store.records.getValue("draft")
        assertTrue(DraftBrowserCodec.read(frozen).launch!!.needsRecovery)
        assertNotNull(JSONObject(frozen.payload).getString("revision"))
    }
    @Test fun changedFreezeLeaseNeverDispatchesOrDeletesAnotherDraft() = runBlocking {
        val launch = prepare(); val editor = controller(launch)
        store.beforeCas = { id, expected, replacement ->
            if (replacement?.payload?.contains("\"submitted\":true") == true) {
                store.records[id] = expected!!.copy(payload = JSONObject(expected.payload).put("title", "Other controller").put("revision", java.util.UUID.randomUUID().toString()).toString())
            }
        }
        editor.save(); assertEquals(0, writer.writes)
        assertEquals("Other controller", JSONObject(store.records.getValue("draft").payload).getString("title"))
    }
    @Test fun deniedPreflightLeavesRawUnsubmittedAndGrantNeverAutoSaves() = runBlocking {
        val launch = prepare(); var granted = false; val editor = controller(launch, permission = { granted })
        editor.change { it.copy(description = "Retain raw") }; editor.save()
        assertEquals(EditorProblem.PERMISSION, editor.state.problem); assertEquals(0, writer.writes)
        assertFalse(JSONObject(store.records.getValue("draft").payload).has("submission"))
        granted = true; assertEquals(0, writer.writes); editor.save(); assertEquals(1, writer.writes)
    }
    @Test fun inFlightFlushCannotReplaceTheFrozenRecordOrAuthorizeAnotherWrite() = runBlocking {
        val gate = CompletableDeferred<Unit>(); writer.hold = gate
        val editor = controller(prepare()); editor.save(); assertTrue(editor.state.saving)
        val frozen = store.records.getValue("draft")
        assertFalse(editor.flushDraft()); assertFalse(editor.prepareDismiss())
        editor.change { it.copy(title = "Late input") }; editor.save()
        assertEquals(frozen, store.records["draft"]); assertEquals(1, writer.writes)
        gate.complete(Unit); yield(); assertEquals(frozen, store.records["draft"])
    }
    @Test fun frozenErrorNeverUnfreezesOrMintsAnotherOperation() = runBlocking {
        writer.next = MutationResult.PermissionRequired
        val launch = prepare(); val editor = controller(launch); editor.save()
        val record = store.records.getValue("draft"); val id = editor.pendingOperationId
        editor.change { it.copy(title = "Unsafe revision") }; editor.save()
        assertEquals(record, store.records["draft"]); assertEquals(id, editor.pendingOperationId); assertEquals(1, writer.writes)
        val stored = DraftBrowserCodec.read(record).launch!!
        assertTrue(stored.needsRecovery)
        val restored = controller(stored, existing = null); restored.recover()
        assertEquals(1, writer.writes); assertEquals(0, writer.loads)
    }
    @Test fun retireUnavailableInspectsExactCommandBeforeCleanupAndNewGeneration() = runBlocking {
        val editor = controller(prepare()); editor.change { it.copy(title = "Partial", endDate = "2030-") }; assertTrue(editor.flushDraft())
        // Freeze a valid raw request first; then its exact payload is immutable.
        editor.change { it.copy(endDate = "2030-06-15") }; editor.save()
        val original = retirementRequest(); retirement.result = OperationRetirementResult.Unavailable
        retirement.inspection = OperationRetirementResult.Retired(original.operationId)
        val replacement = editor.replaceDraft()!!
        assertEquals(listOf(original), retirement.retired); assertEquals(listOf(original), retirement.inspected)
        assertNotEquals("draft", replacement.draftId); assertNotEquals(editor.generation, replacement.generation)
        assertFalse(store.records.containsKey("draft")); assertFalse(replacement.calendarSelected)
        val next = controller(replacement); assertEquals("Partial", next.state.fields.title)
        assertEquals("", next.state.fields.calendarId); assertNotEquals(original.operationId, next.pendingOperationId)
        editor.retryOriginal(); editor.save(); assertEquals(1, writer.writes)
    }
    @Test fun retirementProofForAnotherOperationCannotDeleteOrReissue() = runBlocking {
        val editor = controller(prepare()); editor.save(); val exact = store.records.getValue("draft")
        retirement.result = OperationRetirementResult.Retired("other")
        assertNull(editor.replaceDraft()); assertEquals(exact, store.records["draft"])
        assertEquals(EditorProblem.RETIRE_CONFLICT, editor.state.problem)
    }
    @Test fun blockedDispatchAndRecordedCommitStayDistinctAndRetainFrozenRecord() = runBlocking {
        for (reason in RetirementBlockReason.entries) {
            store.records.clear(); val editor = controller(prepare()); editor.save()
            val exact = store.records.getValue("draft")
            retirement.result = OperationRetirementResult.Blocked(editor.pendingOperationId, reason)
            assertFalse(editor.discardDraft()); assertEquals(exact, store.records["draft"])
            assertEquals(if (reason == RetirementBlockReason.RECORDED_COMMIT) EditorProblem.RETIRE_BLOCKED_COMMIT else EditorProblem.RETIRE_BLOCKED_DISPATCH, editor.state.problem)
        }
    }
    @Test fun tombstoneProofSurvivesCleanupFailureAndOldRetryStaysStopped() = runBlocking {
        val editor = controller(prepare()); editor.save(); val operation = editor.pendingOperationId
        retirement.result = OperationRetirementResult.Retired(operation); store.failWrites = true
        assertFalse(editor.discardDraft()); assertTrue(editor.state.retirementConfirmed)
        writer.safe = true; editor.recover(); editor.retryOriginal(); editor.save(); assertEquals(1, writer.writes)
        val restored = controller(DraftBrowserCodec.read(store.records.getValue("draft")).launch!!)
        retirement.inspection = OperationRetirementResult.Retired(operation)
        restored.inspectRetirement(); store.failWrites = false
        assertTrue(restored.discardDraft()); assertTrue(store.records.isEmpty()); assertEquals(1, retirement.retired.size)
    }
    @Test fun changedCleanupRecordIsRetainedAndNoReplacementIsPrepared() = runBlocking {
        val editor = controller(prepare()); editor.save(); retirement.result = OperationRetirementResult.Retired(editor.pendingOperationId)
        var replacement: DraftRecord? = null
        store.beforeCas = { id, expected, next -> if (next == null) {
            replacement = expected!!.copy(payload = JSONObject(expected.payload).put("revision", java.util.UUID.randomUUID().toString()).toString())
            store.records[id] = replacement!!
        } }
        assertNull(editor.replaceDraft()); assertEquals(replacement, store.records["draft"]); assertEquals(1, store.records.size)
    }
    @Test fun editReplacementChecksOriginalNamespaceBeforeRetiringOrDeleting() = runBlocking {
        val launch = prepare(edit = true); val old = controller(launch); old.change { it.copy(title = "Original raw") }; old.flushDraft()
        val record = store.records.getValue("draft")
        val foreign = source.copy(token = EditToken("B"), details = source.details.copy(calendar = eventTestCalendar().copy(providerId = "B")))
        val restored = controller(launch, existing = foreign); writer.fresh = EditReadResult.Ready(foreign)
        assertNull(restored.replaceDraft()); assertEquals(record, store.records["draft"]); assertTrue(retirement.retired.isEmpty())
        assertEquals("Original raw", restored.state.fields.title)
    }
    @Test fun legacyUnboundEditCanOnlyExplicitlyDiscardWithoutReadingOrRebinding() = runBlocking {
        val launch = prepare(edit = true); val record = store.records.getValue("draft"); val raw = JSONObject(record.payload)
        raw.remove("feature"); raw.remove("schema"); raw.remove("generation"); raw.remove("revision")
        raw.put("sourceBaseline", JSONObject(raw.getString("sourceBaseline")).apply { remove("sourceCalendarIdentity") }.toString())
        store.records["draft"] = record.copy(version = 4, payload = raw.toString())
        val legacy = DraftBrowserCodec.read(store.records.getValue("draft")).launch!!
        val editor = controller(legacy); assertFalse(editor.canUpgrade); assertFalse(editor.canReplace)
        assertTrue(editor.discardDraft()); assertEquals(0, writer.loads); assertTrue(store.records.isEmpty())
    }
    @Test fun explicitLegacyUpgradePreservesPinnedRawAndRequiresRepublishedGeneration() = runBlocking {
        prepare(); val record = store.records.getValue("draft"); val raw = JSONObject(record.payload)
        listOf("feature", "schema", "generation", "revision").forEach(raw::remove); raw.put("startDate", "2030-")
        store.records["draft"] = record.copy(version = 4, payload = raw.toString())
        val legacy = DraftBrowserCodec.read(store.records.getValue("draft")).launch!!
        val editor = controller(legacy); assertTrue(editor.canUpgrade); editor.save(); assertEquals(0, writer.writes)
        val upgraded = editor.upgradeLegacyDraft()!!; assertNotNull(upgraded.generation)
        assertEquals("2030-", controller(upgraded).state.fields.startDate)
        assertTrue(controller(legacy).state.draftReadFailed)
    }
    @Test fun knownLocalSaveAcknowledgesCleanupWithoutRetiringCommittedOperation() = runBlocking {
        writer.next = MutationResult.LocalSaved(ref)
        val editor = controller(prepare())
        store.beforeCas = { _, _, next -> if (next == null) store.failWrites = true }
        editor.save(); assertNotNull(editor.state.localOutcome); assertNull(editor.state.result); assertFalse(editor.canDiscard)
        store.beforeCas = null; store.failWrites = false
        assertTrue(editor.acknowledgeLocalOutcome()); assertEquals(MutationResult.LocalSaved(ref), editor.state.result)
        assertTrue(retirement.retired.isEmpty()); assertEquals(1, writer.writes)
    }
    @Test fun lostCleanupAcknowledgmentUsesAbsenceProofWithoutRetirementOrReplay() = runBlocking {
        writer.next = MutationResult.LocalSaved(ref)
        val editor = controller(prepare())
        store.beforeCas = { _, _, next -> if (next == null) store.unknownAcknowledgment = true }
        editor.save()
        assertEquals(MutationResult.LocalSaved(ref), editor.state.result)
        assertTrue(store.records.isEmpty()); assertEquals(1, writer.writes)
        editor.save(); editor.retryOriginal(); editor.recover()
        assertEquals(1, writer.writes); assertTrue(retirement.retired.isEmpty())
    }
    @Test fun knownOutcomeNeverCleansOrReplacesForeignSuccessor() = runBlocking {
        writer.next = MutationResult.LocalSaved(ref)
        val editor = controller(prepare()); var foreign: DraftRecord? = null
        store.beforeCas = { id, expected, next -> if (next == null) {
            foreign = expected!!.copy(payload = JSONObject(expected.payload)
                .put("generation", java.util.UUID.randomUUID().toString())
                .put("revision", java.util.UUID.randomUUID().toString()).toString())
            store.records[id] = foreign!!
        } }
        editor.save()
        assertEquals(MutationResult.LocalSaved(ref), editor.state.localOutcome)
        assertNull(editor.state.result); assertEquals(foreign, store.records["draft"])
        assertFalse(editor.acknowledgeLocalOutcome()); assertNull(editor.replaceDraft())
        editor.save(); editor.retryOriginal(); editor.recover()
        assertEquals(1, writer.writes); assertEquals(foreign, store.records["draft"])
        assertTrue(retirement.retired.isEmpty()); assertTrue(editor.prepareDismiss())
    }
    @Test fun shippedVersionOneRawDraftRequiresExplicitUpgradeAndRetainsIncompleteInput() = runBlocking {
        prepare(); val modern = store.records.getValue("draft"); val raw = JSONObject(modern.payload)
        listOf("feature", "schema", "generation", "revision", "recurrence").forEach(raw::remove)
        raw.put("startDate", "2030-")
        val legacy = modern.copy(version = 1, payload = raw.toString()); store.records["draft"] = legacy
        val launch = requireNotNull(DraftBrowserCodec.read(legacy).launch)
        val editor = controller(launch)
        assertTrue(editor.canUpgrade); assertTrue(editor.state.legacy); editor.save()
        assertEquals(legacy, store.records["draft"]); assertEquals(0, writer.writes)
        val upgraded = requireNotNull(editor.upgradeLegacyDraft())
        assertNotNull(upgraded.generation); assertEquals("2030-", controller(upgraded).state.fields.startDate)
        assertEquals(0, writer.writes); assertTrue(controller(launch).state.draftReadFailed)
    }
    @Test fun everyFrozenCommandMapsToItsExactTypedRetirementRequest() {
        val patch = EventPatch(title = FieldChange.Set("new")); val token = EditToken("old")
        assertEquals(RetirableOperation.Create("c", initial), FrozenEditorSubmission.Create("c", initial).retirementRequest())
        assertEquals(RetirableOperation.Update("u", ref, token, patch), FrozenEditorSubmission.Update("u", ref, token, patch).retirementRequest())
        assertEquals(RetirableOperation.Delete("d", ref, token), FrozenEditorSubmission.Delete("d", ref, token).retirementRequest())
    }
    private fun retirementRequest(): RetirableOperation = FrozenEditorSubmission.decode(JSONObject(store.records.getValue("draft").payload).getJSONObject("submission")).retirementRequest()
    private inner class Port : CalendarMutations {
        var writes = 0; var loads = 0; var safe = false; var hold: CompletableDeferred<Unit>? = null
        var next: MutationResult = MutationResult.Uncertain("synthetic", null)
        var fresh: EditReadResult = EditReadResult.Ready(source)
        override suspend fun loadForEdit(ref: EventRef): EditReadResult { loads++; return fresh }
        override suspend fun create(operationId: String, draft: EventDraft): MutationResult { writes++; hold?.await(); return next }
        override suspend fun update(operationId: String, ref: EventRef, token: EditToken, patch: EventPatch): MutationResult { writes++; hold?.await(); return next }
        override suspend fun delete(operationId: String, ref: EventRef, token: EditToken): MutationResult { writes++; hold?.await(); return next }
        override suspend fun recover(operationId: String): MutationResult = if (safe) MutationResult.SafeToRetry(operationId) else MutationResult.Uncertain(operationId, null)
    }
    private class Fence : OperationRetirement {
        val retired = mutableListOf<RetirableOperation>(); val inspected = mutableListOf<RetirableOperation>()
        var result: OperationRetirementResult = OperationRetirementResult.Unavailable
        var inspection: OperationRetirementResult = OperationRetirementResult.Unavailable
        override suspend fun retire(request: RetirableOperation): OperationRetirementResult { retired += request; return result }
        override suspend fun inspect(request: RetirableOperation): OperationRetirementResult { inspected += request; return inspection }
    }
}
