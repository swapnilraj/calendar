package dev.swapnilraj.calendar.events.meetings

import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.*
import dev.swapnilraj.calendar.ui.events.meetings.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MeetingResponseWorkflowTest {
    @Test fun routeContainsOnlyIdentityAndRejectsCoercion() = runBlocking {
        val f = MeetingResponseFixture(); val launch = f.prepare()
        val encoded = MeetingResponseRouteCodec.encode(launch)
        assertEquals(launch, MeetingResponseRouteCodec.decode(encoded))
        assertEquals(setOf("draftId", "generation", "selected", "needsRecovery"), JSONObject(encoded).keys().asSequence().toSet())
        for (key in listOf("title", "token", "response", "time"))
            assertNull(MeetingResponseRouteCodec.decode(JSONObject(encoded).put(key, "private").toString()))
        assertNull(MeetingResponseRouteCodec.decode(JSONObject(encoded).put("generation", "missing").toString()))
        assertNull(MeetingResponseRouteCodec.decode(JSONObject(encoded).put("needsRecovery", "true").toString()))
        for (number in listOf<Any>("2", 2.5)) {
            val raw = JSONObject(encoded); raw.getJSONObject("selected").put("calendar", number)
            assertNull(MeetingResponseRouteCodec.decode(raw.toString()))
        }
        assertEquals(launch, MeetingResponseRouteCodec.decode(JSONObject(encoded).apply { remove("needsRecovery") }.toString()))
    }

    @Test fun durableFrozenRecordOverridesFalseRouteHintAndMissingSource() = runBlocking {
        val f = MeetingResponseFixture(); f.pending(); f.readResult = MeetingReadResult.Unavailable
        val opening = openMeetingResponseDraft(f.launch().copy(needsRecovery = false), f.meetings, f.store)
        assertEquals(MeetingResponseOpening.Ready(null, true), opening)
        assertEquals(0, f.reads); assertEquals(1, f.calls.size)
    }

    @Test fun unsubmittedIgnoresRecoveryHintAndReadsFreshSource() = runBlocking {
        val f = MeetingResponseFixture(); val launch = f.prepare().copy(needsRecovery = true)
        val opening = openMeetingResponseDraft(launch, f.meetings, f.store)
        assertEquals(MeetingResponseOpening.Ready(f.source, false), opening)
        assertEquals(1, f.reads); assertEquals(0, f.permissionCalls); assertTrue(f.calls.isEmpty())
    }

    @Test fun missingWrongGenerationAndCorruptRecordsNeverReseed() = runBlocking {
        val f = MeetingResponseFixture(); val launch = f.prepare(); val original = f.record()
        assertEquals(MeetingResponseOpening.DraftReview, openMeetingResponseDraft(launch.copy(generation = java.util.UUID.randomUUID().toString()), f.meetings, f.store))
        f.store.rows.clear()
        assertEquals(MeetingResponseOpening.DraftReview, openMeetingResponseDraft(launch, f.meetings, f.store))
        f.store.rows[launch.draftId] = original.copy(payload = "{}")
        assertEquals(MeetingResponseOpening.DraftReview, openMeetingResponseDraft(launch, f.meetings, f.store))
        assertEquals(0, f.reads); assertEquals(1, f.store.compares); assertTrue(f.calls.isEmpty())
    }

    @Test fun freshTokenDriftRetainsSavedChoicesAndRequiresReview() = runBlocking {
        val f = MeetingResponseFixture(); val launch = f.prepare(); val old = f.controller(launch); f.choose(old); old.prepareDismiss()
        f.readResult = MeetingReadResult.Ready(f.source.copy(token = EditToken("new-token")))
        val opening = openMeetingResponseDraft(launch, f.meetings, f.store) as MeetingResponseOpening.Ready
        val controller = f.controller(launch, opening.source)
        assertTrue(controller.state.reviewRequired); assertFalse(controller.canSave)
        assertEquals("YES", controller.state.fields.response); assertTrue(f.calls.isEmpty())
    }

    @Test fun freezeDuringLiveReadWinsEvenWhenLiveReadFails() = runBlocking {
        val f = MeetingResponseFixture(); val launch = f.prepare(); val controller = f.controller(launch); f.choose(controller)
        f.next = MeetingMutationResult.Common(MutationResult.Uncertain("unknown", 42))
        f.onRead = { controller.save() }; f.readResult = MeetingReadResult.Unavailable
        assertEquals(MeetingResponseOpening.Ready(null, true), openMeetingResponseDraft(launch, f.meetings, f.store))
    }

    @Test fun explicitReviewPreparesNewIdentityOnlyAfterRetiringOldController() = runBlocking {
        val f = MeetingResponseFixture(); val launch = f.prepare(); val controller = f.controller(launch)
        val result = controller.discardAndReload()!!
        val prepared = prepareReviewedMeetingResponse(launch, result, f.store, controller.originalCalendarIdentity) as MeetingResponsePreparation.Ready
        assertNotEquals(launch.draftId, prepared.launch.draftId); assertNotEquals(launch.generation, prepared.launch.generation)
        val replacement = f.store.rows.getValue(prepared.launch.draftId)
        assertNull(controller.discardAndReload()); controller.save()
        assertEquals(replacement, f.store.rows.getValue(prepared.launch.draftId)); assertTrue(f.calls.isEmpty())
    }
    @Test fun browserRecognizesResponsesBeforeLegacyVersionsAndRetainsCorruption() = runBlocking {
        val f = MeetingResponseFixture(); f.prepare(); val record = f.record()
        assertEquals(f.launch(), DraftBrowserCodec.read(record).meetingPreview?.launch)
        for (version in listOf(1, 2, 99)) {
            val row = DraftBrowserCodec.read(record.copy(version = version))
            assertNull(row.launch); assertNull(row.meetingPreview); assertNotNull(row.issue)
        }
    }


}
