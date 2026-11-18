package dev.swapnilraj.calendar.events.meetings

import dev.swapnilraj.calendar.core.*
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
class MeetingResponseDraftDiscoveryTest {
    @Test fun firstExplicitOpenPreparesStableDurableDraftAndNextOpenReusesIt() = runBlocking {
        val f = MeetingResponseFixture()
        val first = prepareOrReopenMeetingResponseDraft(f.source, f.store, f.clock) as MeetingResponsePreparation.Ready
        val row = f.store.rows.getValue(first.launch.draftId)
        assertEquals(first, prepareOrReopenMeetingResponseDraft(f.source, f.store, f.clock))
        assertEquals(row, f.store.rows.getValue(first.launch.draftId)); assertEquals(1, f.store.compares)
        assertTrue(f.calls.isEmpty()); assertEquals(0, f.permissionCalls)
    }

    @Test fun reviewedRandomReplacementFrozenUncertainIsReopenedWithoutNewOperation() = runBlocking {
        val f = MeetingResponseFixture()
        val original = prepareOrReopenMeetingResponseDraft(f.source, f.store, f.clock) as MeetingResponsePreparation.Ready
        val controller = f.controller(original.launch)
        val fresh = controller.discardAndReload()!!
        val replacement = prepareReviewedMeetingResponse(original.launch, fresh, f.store) as MeetingResponsePreparation.Ready
        val second = f.controller(replacement.launch); f.choose(second)
        f.next = MeetingMutationResult.Common(MutationResult.Uncertain("uncertain", f.selected.eventId)); second.save()
        val records = f.store.rows.toMap(); val compares = f.store.compares
        val reopened = prepareOrReopenMeetingResponseDraft(f.source, f.store, f.clock) as MeetingResponsePreparation.Ready
        assertEquals(replacement.launch.copy(needsRecovery = true), reopened.launch)
        assertEquals(records, f.store.rows); assertEquals(compares, f.store.compares)
        assertFalse(f.store.rows.containsKey(original.launch.draftId)); assertEquals(1, f.calls.size)
    }

    @Test fun multipleMatchingRecordsNeverChooseOrSeedAnotherOperation() = runBlocking {
        val f = MeetingResponseFixture(); f.prepare(id = "first"); f.prepare(id = "second")
        val rows = f.store.rows.toMap()
        assertEquals(MeetingResponsePreparation.RetainedForReview, prepareOrReopenMeetingResponseDraft(f.source, f.store, f.clock))
        assertEquals(rows, f.store.rows); assertEquals(2, f.store.compares); assertTrue(f.calls.isEmpty())
    }

    @Test fun corruptAndUnknownVersionResponseRecordsCannotBeBypassed() = runBlocking {
        val f = MeetingResponseFixture(); f.prepare(); val row = f.record()
        for (bad in listOf(row.copy(payload = "{}"), row.copy(version = 99),
            row.copy(payload = JSONObject(row.payload).put("selected", JSONObject()).toString()),
            row.copy(version = 1, payload = JSONObject(row.payload).put("schema", 200).toString()))) {
            f.store.rows.clear(); f.store.rows[bad.id] = bad
            assertEquals(MeetingResponsePreparation.RetainedForReview, prepareOrReopenMeetingResponseDraft(f.source, f.store, f.clock))
            assertEquals(mapOf(bad.id to bad), f.store.rows)
        }
        assertEquals(1, f.store.compares); assertTrue(f.calls.isEmpty())
    }

    @Test fun validOtherOccurrenceAndOrdinaryDraftsDoNotBlockNewResponse() = runBlocking {
        val f = MeetingResponseFixture()
        val other = f.source.copy(details = f.source.details.copy(occurrence = f.source.details.occurrence.copy(ref = f.selected.copy(eventId = 99))))
        f.prepare(other, "other")
        f.store.rows["ordinary"] = DraftRecord("ordinary", 1, "{}", f.clock.instant())
        val prepared = prepareOrReopenMeetingResponseDraft(f.source, f.store, f.clock)
        assertTrue(prepared is MeetingResponsePreparation.Ready); assertEquals(3, f.store.rows.size)
    }

    @Test fun unreadableListingNeverFallsBackToPreparingAnotherDraft() = runBlocking {
        val f = MeetingResponseFixture()
        val store = object : AtomicDraftStore by f.store {
            override suspend fun list(): List<DraftRecord> = error("unavailable")
        }
        assertEquals(MeetingResponsePreparation.Unavailable, prepareOrReopenMeetingResponseDraft(f.source, store, f.clock))
        assertEquals(0, f.store.compares); assertTrue(f.store.rows.isEmpty())
    }
}
