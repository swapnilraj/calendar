package dev.swapnilraj.calendar.events.meetings

import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.meetings.*
import java.time.Instant
import java.util.UUID
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
class MeetingResponseControllerTest {
    private val f = MeetingResponseFixture()
    @After fun stop() { f.scope.cancel() }
    @Test fun durableOpenHasNoImplicitScopeResponsePermissionOrProviderOperation() = runBlocking {
        val launch = f.prepare(); val record = f.record(); val controller = f.controller(launch)
        assertEquals(MeetingResponseFields(), controller.state.fields); assertFalse(controller.canSave)
        controller.save(); assertTrue(f.calls.isEmpty()); assertEquals(0, f.permissionCalls)
        assertEquals(record, f.record()); assertEquals(0, f.store.legacyCalls); assertEquals(0, f.reads)
        assertEquals(launch, MeetingResponseDraftCodec.describe(record))
    }
    @Test fun rightsComeOnlyFromIssuedResponseScopeAndUnsupportedScopeNeverFallsBack() = runBlocking {
        val unknown = f.source.copy(details = f.source.details.copy(canRespond = AccessDecision.ALLOWED, isOrganizer = true), scopes = emptyMap())
        assertEquals(MeetingResponsePreparation.Unavailable, prepareMeetingResponseDraft(unknown, "response", f.store, f.clock))
        val controller = f.controller(f.prepare()); controller.selectScope(RecurrenceScope.ALL); controller.selectResponse(MeetingResponse.NO)
        assertEquals("", controller.state.fields.scope); assertFalse(controller.canSave); controller.save(); assertTrue(f.calls.isEmpty())
        assertEquals(0, f.permissionCalls)
    }
    @Test fun allScopeNeedsExplicitChoiceAndExactCommandUsesBackendAuthority() = runBlocking {
        val source = f.source.copy(scopes = mapOf(RecurrenceScope.ALL to MeetingScopeAccess(respond = AccessDecision.ALLOWED)))
        val controller = f.controller(f.prepare(source), source); assertEquals("", controller.state.fields.scope)
        f.choose(controller, RecurrenceScope.ALL, MeetingResponse.MAYBE)
        f.next = MeetingMutationResult.LocalSaved(f.selected, RecurrenceScope.ALL); controller.save()
        assertEquals(MeetingCommand.Respond(RecurrenceScope.ALL, MeetingResponse.MAYBE), f.calls.single().command)
        assertEquals(f.source.token, f.calls.single().token); assertEquals(0, f.createCalls)
    }
    @Test fun permissionDeniedRetainsDurableRawChoicesWithoutFreezingOrDispatching() = runBlocking {
        val controller = f.controller(f.prepare()); f.choose(controller); f.granted = false; controller.save()
        assertEquals(1, f.permissionCalls); assertTrue(f.calls.isEmpty()); assertFalse(controller.frozen)
        assertEquals(MeetingResponseFields("THIS", "YES"), MeetingResponseDraftCodec.decode(f.record()).fields)
        controller.selectResponse(MeetingResponse.NO); f.granted = true; controller.save()
        assertEquals(MeetingCommand.Respond(RecurrenceScope.THIS, MeetingResponse.NO), f.calls.single().command)
        assertTrue(f.calls.none { it.command is MeetingCommand.CancelOwned }); assertEquals(0, f.createCalls)
    }
    @Test fun fieldsAndFrozenCommandAreDurableBeforeAnyMutation() = runBlocking {
        val controller = f.controller(f.prepare()); f.choose(controller)
        f.mutationGate = CompletableDeferred(); f.next = MeetingMutationResult.Common(MutationResult.Uncertain("ignored", 42)); controller.save()
        val call = f.calls.single(); val document = MeetingResponseDraftCodec.decode(f.record())
        assertEquals(call.id, document.frozen!!.operationId); assertEquals(call.selected, document.frozen!!.selected)
        assertEquals(call.token, document.frozen!!.token); assertEquals(call.command, document.frozen!!.command)
        assertEquals(controller.launch.generation, document.generation); assertTrue(f.launch().needsRecovery)
        f.mutationGate!!.complete(Unit); yield(); assertTrue(controller.state.recoveryRequired)
    }
    @Test fun doubleSaveIsGuardedDuringPermissionAndProviderSuspensions() = runBlocking {
        val controller = f.controller(f.prepare()); f.choose(controller); val permission = CompletableDeferred<Unit>(); f.permissionGate = permission
        val mutate = CompletableDeferred<Unit>(); f.mutationGate = mutate
        controller.save(); controller.save(); assertEquals(1, f.permissionCalls); assertTrue(f.calls.isEmpty())
        permission.complete(Unit); yield(); controller.save(); assertEquals(1, f.calls.size)
        mutate.complete(Unit); yield(); controller.save(); assertEquals(1, f.calls.size); assertTrue(f.store.rows.isEmpty())
    }
    @Test fun unknownRawSelectionsRemainIntactUntilUserExplicitlyCorrectsThem() = runBlocking {
        f.prepare(); val old = f.record(); val raw = JSONObject(old.payload).put("scope", "FOLLOWING").put("response", "partial-")
        f.store.rows["response"] = old.copy(payload = raw.toString())
        val controller = f.controller(f.launch()); assertEquals(MeetingResponseFields("FOLLOWING", "partial-"), controller.state.fields)
        controller.save(); assertTrue(f.calls.isEmpty()); assertEquals(0, f.permissionCalls)
        assertTrue(controller.prepareDismiss()); val restored = f.controller(f.launch())
        assertEquals(MeetingResponseFields("FOLLOWING", "partial-"), restored.state.fields)
        f.choose(restored, response = MeetingResponse.MAYBE); restored.save()
        assertEquals(MeetingCommand.Respond(RecurrenceScope.THIS, MeetingResponse.MAYBE), f.calls.single().command)
    }
    @Test fun collisionReusesRecognizedExistingGenerationWithoutOverwritingRawChoices() = runBlocking {
        val launch = f.prepare(); val controller = f.controller(launch); f.choose(controller, response = MeetingResponse.NO); assertTrue(controller.prepareDismiss())
        val exact = f.record(); val other = f.source.copy(token = EditToken("new-source-token"))
        val result = prepareMeetingResponseDraft(other, "response", f.store, f.clock) as MeetingResponsePreparation.Ready
        assertEquals(launch.generation, result.launch.generation); assertEquals(exact, f.record())
        val reopened = f.controller(result.launch, other); assertTrue(reopened.state.reviewRequired)
        assertEquals("NO", reopened.state.fields.response); reopened.save(); assertTrue(f.calls.isEmpty())
    }
    @Test fun atomicCreateCollisionNeverOverwritesAnExistingOrUnknownRecord() = runBlocking {
        val collision = MeetingResponseDraftCodec.record(MeetingResponseDocument.initial(f.source, "response").copy(fields = MeetingResponseFields("THIS", "MAYBE")), f.clock.instant())
        f.store.beforeCompare = { expected, _ -> if (expected == null) f.store.rows["response"] = collision }
        val result = prepareMeetingResponseDraft(f.source, "response", f.store, f.clock) as MeetingResponsePreparation.Ready
        assertEquals(MeetingResponseDraftCodec.describe(collision), result.launch); assertEquals(collision, f.record())
        val opaque = collision.copy(version = 99, payload = "opaque future content"); f.store.rows["response"] = opaque
        assertEquals(MeetingResponsePreparation.RetainedForReview, prepareMeetingResponseDraft(f.source, "response", f.store, f.clock))
        assertEquals(opaque, f.record()); assertEquals(0, f.store.legacyCalls)
    }
    @Test fun unavailableFactoryAcknowledgementRequiresExactOwnRevisionReadback() = runBlocking {
        f.store.unavailableBefore = true
        assertEquals(MeetingResponsePreparation.Unavailable, prepareMeetingResponseDraft(f.source, "response", f.store, f.clock)); assertTrue(f.store.rows.isEmpty())
        f.store.unavailableBefore = false; f.store.unavailableAfter = true
        assertTrue(prepareMeetingResponseDraft(f.source, "response", f.store, f.clock) is MeetingResponsePreparation.Ready)
        assertTrue(f.calls.isEmpty()); assertEquals(0, f.permissionCalls)
    }
    @Test fun unknownSchemaCorruptFrozenAndWrongGenerationStayRetainedForReview() = runBlocking {
        val launch = f.prepare(); val old = f.record()
        for (raw in listOf(JSONObject(old.payload).put("schema", 2), JSONObject(old.payload).put("guestDeltas", JSONObject()), JSONObject(old.payload).put("generation", "not-a-generation"))) {
            val opaque = old.copy(payload = raw.toString()); f.store.rows["response"] = opaque
            assertNull(MeetingResponseDraftCodec.describe(opaque)); assertEquals(MeetingResponsePreparation.RetainedForReview, prepareMeetingResponseDraft(f.source, "response", f.store, f.clock))
            val controller = f.controller(launch); controller.save(); controller.recover(); assertTrue(controller.state.draftUnreadable)
            assertEquals(opaque, f.record())
        }
        f.store.rows["response"] = old
        val wrong = f.controller(launch.copy(generation = UUID.randomUUID().toString())); assertTrue(wrong.state.draftUnreadable)
        wrong.save(); assertTrue(f.calls.isEmpty()); assertEquals(0, f.permissionCalls)
    }
    @Test fun changedTokenOrResponseRightsNeverRebaseRetainedChoices() = runBlocking {
        val controller = f.controller(f.prepare()); f.choose(controller); controller.prepareDismiss(); val old = f.record()
        for (source in listOf(f.source.copy(token = EditToken("changed")), f.source.copy(scopes = emptyMap()))) {
            val restored = f.controller(f.launch(), source); assertTrue(restored.state.reviewRequired)
            restored.selectResponse(MeetingResponse.NO); restored.save(); assertEquals("YES", restored.state.fields.response)
            assertEquals(old, f.record()); assertTrue(f.calls.isEmpty())
        }
    }
    @Test fun frozenConflictedDeniedOrUnsupportedRequestsCannotBeDiscardedOrReissued() = runBlocking {
        for (outcome in listOf(MeetingMutationResult.Common(MutationResult.Conflict(null)), MeetingMutationResult.Common(MutationResult.PermissionRequired),
            MeetingMutationResult.NeedsReview("internal provider-id"), MeetingMutationResult.Unsupported("internal field code"))) {
            f.store.rows.clear(); val controller = f.controller(f.prepare()); f.choose(controller); f.next = outcome; controller.save()
            val old = f.record(); val id = controller.operationId; val count = f.calls.size
            controller.selectResponse(MeetingResponse.NO); controller.selectScope(RecurrenceScope.ALL); controller.save()
            assertNull(controller.discardAndReload()); assertEquals(count, f.calls.size); assertEquals(id, controller.operationId)
            assertEquals(old, f.record()); assertEquals("YES", controller.state.fields.response); assertTrue(controller.state.recoveryRequired)
        }
        assertEquals(0, f.reads)
    }
    @Test fun restoreAndReadOnlyRecoveryNeverAskWritePermissionOrResubmit() = runBlocking {
        val first = f.pending(); val saved = f.record(); val count = f.permissionCalls
        val restored = f.controller(f.launch(), null); assertEquals(first.operationId, restored.operationId)
        assertEquals(1, f.calls.size); assertTrue(f.recoveries.isEmpty()); assertEquals(count, f.permissionCalls)
        restored.recover(); restored.retryOriginal(); restored.save(); restored.selectResponse(MeetingResponse.NO)
        assertEquals(count, f.permissionCalls); assertEquals(1, f.calls.size); assertEquals(saved, f.record())
        assertTrue(restored.state.recoveryRequired); assertFalse(restored.state.retryReady)
    }
    @Test fun onlyMatchingRecoveryProofPermitsExplicitExactOriginalRetry() = runBlocking {
        f.pending(); val original = f.calls.single(); val restored = f.controller(f.launch(), null)
        f.recoverResult = MeetingMutationResult.Common(MutationResult.SafeToRetry("wrong")); restored.recover(); restored.retryOriginal(); assertEquals(1, f.calls.size)
        f.proof = true; restored.recover(); assertTrue(restored.state.retryReady); assertEquals(1, f.calls.size)
        restored.selectResponse(MeetingResponse.NO); restored.selectScope(RecurrenceScope.ALL)
        f.next = MeetingMutationResult.Common(MutationResult.PermissionRequired); restored.retryOriginal()
        assertEquals(listOf(original, original), f.calls); assertFalse(restored.state.retryReady); assertTrue(restored.frozen)
        assertNull(restored.discardAndReload()); assertEquals(0, f.reads)
    }
    @Test fun proofFromMutateDoesNotAuthorizeRetryAndNoNeverBecomesCancellation() = runBlocking {
        val controller = f.controller(f.prepare()); f.choose(controller, response = MeetingResponse.NO)
        f.next = MeetingMutationResult.Common(MutationResult.SafeToRetry(controller.operationId!!)); controller.save(); controller.retryOriginal()
        assertFalse(controller.state.retryReady); assertEquals(1, f.calls.size)
        assertEquals(MeetingCommand.Respond(RecurrenceScope.THIS, MeetingResponse.NO), f.calls.single().command)
        assertEquals(0, f.createCalls)
    }
    @Test fun cancelledOrMismatchedSuccessNeverClaimsLocalResponseSaved() = runBlocking {
        for (result in listOf(MeetingMutationResult.LocalCancelled(f.selected, RecurrenceScope.THIS),
            MeetingMutationResult.LocalSaved(f.selected, RecurrenceScope.ALL), MeetingMutationResult.LocalSaved(f.selected.copy(eventId = 99), RecurrenceScope.THIS))) {
            f.store.rows.clear(); val controller = f.controller(f.prepare()); f.choose(controller); f.next = result; controller.save()
            assertNull(controller.state.result); assertNull(controller.state.localOutcome); assertTrue(controller.state.recoveryRequired)
            assertTrue(f.store.rows.isNotEmpty())
        }
    }
    @Test fun sourceMissingKnownOutcomeClosesLocallyWithoutCreationOrFreshRead() = runBlocking {
        f.pending(); val controller = f.controller(f.launch(), null); val permissions = f.permissionCalls
        f.recoverResult = MeetingMutationResult.LocalSaved(f.selected, RecurrenceScope.THIS); controller.recover()
        assertEquals(f.recoverResult, controller.state.result); assertEquals(f.recoverResult, controller.state.localOutcome)
        assertTrue(f.store.rows.isEmpty()); assertEquals(1, f.calls.size); assertEquals(0, f.reads); assertEquals(permissions, f.permissionCalls)
    }
    @Test fun unavailableBeforeUpdateNeverDispatchesButExplicitSaveCanRetryAfterStorageRepair() = runBlocking {
        val controller = f.controller(f.prepare()); f.choose(controller); f.store.unavailableBefore = true; controller.save()
        assertTrue(f.calls.isEmpty()); assertEquals(0, f.permissionCalls); assertEquals(MeetingResponseProblem.DRAFT_UNAVAILABLE, controller.state.problem)
        f.store.unavailableBefore = false; controller.save(); assertEquals(1, f.calls.size)
    }
    @Test fun committedUnavailableUpdatesReadBackOwnRevisionBeforeDispatchAndNeverUseLegacyWrites() = runBlocking {
        val controller = f.controller(f.prepare()); f.choose(controller); val revisions = mutableSetOf<String>()
        f.store.unavailableAfter = true; f.store.afterCompare = { _, next, result -> if (next != null && result == DraftChangeResult.Applied) revisions += JSONObject(next.payload).getString("revision") }
        controller.save(); assertEquals(1, f.calls.size); assertEquals(1, f.permissionCalls); assertEquals(2, revisions.size)
        assertEquals(0, f.store.legacyCalls); assertNotNull(controller.state.result)
    }
    @Test fun anotherIdenticalClaimWithDifferentRevisionCannotBeMistakenForOurUnknownCommit() = runBlocking {
        val controller = f.controller(f.prepare()); f.choose(controller)
        var collision: DraftRecord? = null
        f.store.beforeCompare = { old, next -> if (old != null && next != null) {
            collision = next.copy(payload = JSONObject(next.payload).put("revision", UUID.randomUUID().toString()).toString())
            f.store.rows["response"] = collision!!; f.store.unavailableBefore = true
        } }
        controller.save(); assertTrue(f.calls.isEmpty()); assertEquals(0, f.permissionCalls)
        assertEquals(collision, f.record()); assertEquals(MeetingResponseProblem.DRAFT_CHANGED, controller.state.problem)
    }
    @Test fun staleControllersCannotOverwriteOrReplayAnotherRawClaim() = runBlocking {
        val launch = f.prepare(); val a = f.controller(launch); val b = f.controller(launch)
        f.choose(a); f.choose(b, response = MeetingResponse.NO)
        val gate = CompletableDeferred<Unit>(); f.permissionGate = gate; f.next = MeetingMutationResult.Common(MutationResult.Uncertain("ignored", 42))
        a.save(); b.save(); assertEquals(1, f.permissionCalls); assertEquals(MeetingResponseProblem.DRAFT_CHANGED, b.state.problem)
        gate.complete(Unit); yield(); assertEquals(1, f.calls.size)
        assertEquals(MeetingCommand.Respond(RecurrenceScope.THIS, MeetingResponse.YES), f.calls.single().command)
        assertEquals("YES", MeetingResponseDraftCodec.decode(f.record()).fields.response); assertNull(b.discardAndReload())
    }
    @Test fun freshControllerMaySupersedeOnlyUnsubmittedPermissionStageAndOldWorkerCannotFreeze() = runBlocking {
        val a = f.controller(f.prepare()); f.choose(a); val gate = CompletableDeferred<Unit>(); f.permissionGate = gate
        f.next = MeetingMutationResult.Common(MutationResult.Uncertain("ignored", 42)); a.save()
        val b = f.controller(f.launch()); f.choose(b, response = MeetingResponse.NO); b.save()
        gate.complete(Unit); yield()
        assertEquals(1, f.calls.size); assertEquals(MeetingCommand.Respond(RecurrenceScope.THIS, MeetingResponse.NO), f.calls.single().command)
        assertEquals(MeetingResponseProblem.DRAFT_CHANGED, a.state.problem)
    }
    @Test fun leaseChangeAfterFreezeBlocksDispatchAndPreservesRecoverableOriginal() = runBlocking {
        val controller = f.controller(f.prepare()); f.choose(controller)
        var changed: DraftRecord? = null
        f.store.afterCompare = { _, next, result -> if (next != null && result == DraftChangeResult.Applied && JSONObject(next.payload).has("frozen")) {
            changed = next.copy(payload = JSONObject(next.payload).put("revision", UUID.randomUUID().toString()).toString()); f.store.rows["response"] = changed!!
        } }
        controller.save(); assertTrue(f.calls.isEmpty()); assertEquals(changed, f.record()); assertTrue(f.launch().needsRecovery)
        assertEquals(MeetingResponseProblem.DRAFT_CHANGED, controller.state.problem)
    }
    @Test fun successCleanupCannotDeleteReplacementGeneration() = runBlocking {
        val controller = f.controller(f.prepare()); f.choose(controller); val gate = CompletableDeferred<Unit>(); f.mutationGate = gate; controller.save()
        val old = f.record(); val newer = MeetingResponseDraftCodec.record(MeetingResponseDocument.initial(f.source, "response"), f.clock.instant())
        assertEquals(DraftChangeResult.Applied, f.store.compareAndSet("response", old, newer))
        gate.complete(Unit); yield(); assertEquals(newer, f.record()); assertNull(controller.state.result)
        assertNotNull(controller.state.localOutcome); assertEquals(MeetingResponseProblem.DRAFT_CHANGED, controller.state.problem)
    }
    @Test fun compareIncludesTimestampAndSuccessfulDismissRetiresOldController() = runBlocking {
        val launch = f.prepare(); val stale = f.controller(launch); f.choose(stale)
        val old = f.record(); f.store.rows["response"] = old.copy(updatedAt = old.updatedAt.plusNanos(1))
        stale.save(); assertTrue(f.calls.isEmpty()); assertEquals(MeetingResponseProblem.DRAFT_CHANGED, stale.state.problem)
        val current = f.controller(f.launch()); f.choose(current); assertTrue(current.prepareDismiss())
        val saved = f.record(); current.selectResponse(MeetingResponse.NO); current.save(); assertEquals(saved, f.record()); assertTrue(f.calls.isEmpty())
    }
    @Test fun explicitUnsubmittedDiscardRetiresBeforeFreshLoadAndCannotDeleteReplacement() = runBlocking {
        val launch = f.prepare(); val controller = f.controller(launch, f.source.copy(token = EditToken("fresh")))
        val gate = CompletableDeferred<Unit>(); f.onRead = { gate.await() }
        val first = f.scope.async { controller.discardAndReload() }
        assertTrue(f.store.rows.isEmpty()); val replacement = f.controller(f.prepare()); f.choose(replacement, response = MeetingResponse.NO)
        assertTrue(replacement.prepareDismiss()); val saved = f.record()
        assertNull(controller.discardAndReload()); controller.save(); gate.complete(Unit); assertTrue(first.await() is MeetingReadResult.Ready)
        assertEquals(saved, f.record()); assertEquals(1, f.reads); assertTrue(f.calls.isEmpty())
    }
    @Test fun frozenProofDoesNotPermitDiscardAndPermissionDeniedRetryStillKeepsOriginal() = runBlocking {
        f.pending(); val controller = f.controller(f.launch(), null); f.proof = true; controller.recover()
        val compares = f.store.compares; assertNull(controller.discardAndReload()); assertEquals(compares, f.store.compares); assertEquals(0, f.reads)
        f.granted = false; controller.retryOriginal(); assertTrue(controller.state.retryReady); assertEquals(1, f.calls.size)
        val original = f.calls.single(); f.granted = true; f.next = MeetingMutationResult.Common(MutationResult.Unavailable); controller.retryOriginal()
        assertEquals(listOf(original, original), f.calls); assertFalse(controller.state.retryReady)
    }
    @Test fun unverifiedUnavailableAcknowledgementNeverDispatchesOrOverwritesCommittedFreeze() = runBlocking {
        val controller = f.controller(f.prepare()); f.choose(controller); f.store.unavailableAfter = true
        f.store.afterCompare = { _, next, _ -> if (next != null && JSONObject(next.payload).has("frozen")) f.store.failRead = true }
        controller.save(); assertTrue(f.calls.isEmpty()); assertEquals(1, f.permissionCalls)
        val pending = f.record(); assertTrue(MeetingResponseDraftCodec.describe(pending)!!.needsRecovery)
        f.store.failRead = false; f.store.unavailableAfter = false
        controller.selectResponse(MeetingResponse.NO); controller.save()
        assertTrue(f.calls.isEmpty()); assertEquals(pending, f.record()); assertEquals(MeetingResponseProblem.DRAFT_CHANGED, controller.state.problem)
        val restored = f.controller(f.launch(), null); assertTrue(restored.state.recoveryRequired); assertFalse(restored.canSave)
    }
    @Test fun competingReplacementDuringPermissionWaitCannotBeErasedByDiscard() = runBlocking {
        val first = f.controller(f.prepare()); f.choose(first); val gate = CompletableDeferred<Unit>(); f.permissionGate = gate; first.save()
        val reviewing = f.controller(f.launch())
        assertTrue(reviewing.discardAndReload() is MeetingReadResult.Ready<*>)
        val replacement = f.controller(f.prepare()); f.choose(replacement, response = MeetingResponse.NO); replacement.prepareDismiss()
        val exact = f.record(); gate.complete(Unit); yield()
        assertTrue(f.calls.isEmpty()); assertEquals(exact, f.record()); assertEquals(MeetingResponseProblem.DRAFT_CHANGED, first.state.problem)
    }
    @Test fun missingRestoredRecordNeverSeedsAnotherOperation() = runBlocking {
        val launch = f.prepare(); f.store.rows.clear(); val controller = f.controller(launch)
        f.choose(controller); controller.save(); controller.recover(); controller.retryOriginal(); assertTrue(controller.state.draftUnreadable)
        assertTrue(f.calls.isEmpty()); assertEquals(0, f.permissionCalls); assertTrue(f.store.rows.isEmpty())
    }
}
