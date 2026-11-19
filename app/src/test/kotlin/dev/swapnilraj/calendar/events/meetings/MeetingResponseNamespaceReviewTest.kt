package dev.swapnilraj.calendar.events.meetings

import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.meetings.*
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
class MeetingResponseNamespaceReviewTest {
    private val f = MeetingResponseFixture()
    @After fun stop() { f.scope.cancel() }
    private val original get() = f.source.copy(details = f.source.details.copy(calendar = f.source.details.calendar!!.copy(providerId = "calendar-A")))

    @Test fun restoredForeignSourceCannotDeleteOriginalOrSeedReviewedReplacementEvenWithIdenticalLabelsAndRefs() = runBlocking {
        val source = original
        val launch = f.prepare(source)
        val editor = f.controller(launch, source); f.choose(editor, response = MeetingResponse.NO); assertTrue(editor.prepareDismiss())
        val before = f.record()
        assertEquals(source.details.calendar!!.identity, MeetingResponseDraftCodec.decode(before).sourceCalendarIdentity)
        val foreign = source.copy(token = EditToken("foreign-token"), details = source.details.copy(calendar = source.details.calendar!!.copy(providerId = "calendar-B")))
        val restored = f.controller(launch, foreign)
        assertTrue(restored.state.reviewRequired); assertFalse(restored.canSave)
        f.readResult = MeetingReadResult.Ready(foreign)
        val compares = f.store.compares
        assertNull(restored.discardAndReload())
        assertEquals(compares, f.store.compares); assertEquals(before, f.record())
        assertEquals("NO", restored.state.fields.response)
        assertEquals(MeetingResponsePreparation.RetainedForReview,
            prepareReviewedMeetingResponse(launch, MeetingReadResult.Ready(foreign), f.store, source.details.calendar!!.identity))
        assertEquals(MeetingResponsePreparation.RetainedForReview,
            prepareReviewedMeetingResponse(launch, MeetingReadResult.Ready(foreign), f.store))
        assertEquals(setOf(launch.draftId), f.store.rows.keys)
        assertTrue(f.calls.isEmpty()); assertEquals(0, f.permissionCalls)
        assertTrue(prepareMeetingResponseDraft(foreign, "fresh-manual-selection", f.store, f.clock) is MeetingResponsePreparation.Ready)
    }

    @Test fun legacyUnsubmittedMissingIdentityRetainsRawAndNeverInfersProofFromLabelsOrToken() = runBlocking {
        val launch = f.prepare(); val controller = f.controller(launch); f.choose(controller); assertTrue(controller.prepareDismiss())
        val before = f.record(); val legacy = before.copy(payload = JSONObject(before.payload).apply {
            put("schema", 1); remove("sourceCalendarIdentity")
        }.toString()); f.store.rows[legacy.id] = legacy
        val restored = f.controller(f.launch())
        assertTrue(restored.state.reviewRequired); assertFalse(restored.canChange); assertFalse(restored.canSave)
        assertNull(restored.discardAndReload()); restored.save()
        assertEquals(legacy, f.record()); assertEquals(0, f.reads); assertEquals(0, f.permissionCalls); assertTrue(f.calls.isEmpty())
    }

    @Test fun legacyFrozenExactRecoveryDoesNotRequireCurrentIdentityOrRewriteOriginalCommand() = runBlocking {
        f.pending(); val before = f.record(); val legacy = before.copy(payload = JSONObject(before.payload).apply {
            put("schema", 1); remove("sourceCalendarIdentity")
        }.toString()); f.store.rows[legacy.id] = legacy
        val permissionCalls = f.permissionCalls
        val frozen = MeetingResponseDraftCodec.decode(legacy).frozen!!
        val restored = f.controller(f.launch(), null)
        f.recoverResult = MeetingMutationResult.Common(MutationResult.Uncertain(frozen.operationId, frozen.selected.eventId))
        restored.recover()
        assertEquals(listOf(frozen.operationId), f.recoveries)
        assertEquals(legacy, f.record()); assertEquals(frozen, MeetingResponseDraftCodec.decode(f.record()).frozen)
        assertEquals(1, f.calls.size); assertEquals(0, f.reads); assertEquals(permissionCalls, f.permissionCalls)
        assertNull(restored.discardAndReload())
    }

    @Test fun newUnsubmittedDraftCannotGuessAMissingOrBlankCalendarIdentity() = runBlocking {
        for (source in listOf(f.source.copy(details = f.source.details.copy(calendar = null)),
            f.source.copy(details = f.source.details.copy(calendar = f.source.details.calendar!!.copy(account = CalendarAccount("", "fixture")))))) {
            assertEquals(MeetingResponsePreparation.Unavailable, prepareMeetingResponseDraft(source, "unbound", f.store, f.clock))
        }
        assertTrue(f.store.rows.isEmpty()); assertTrue(f.calls.isEmpty())
    }
}
