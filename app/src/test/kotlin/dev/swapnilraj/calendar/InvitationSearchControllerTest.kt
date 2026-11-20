package dev.swapnilraj.calendar

import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.search.*
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class InvitationSearchControllerTest {
    private val date = LocalDate.of(2026, 11, 17)
    private val calendar = CalendarRequest(defaultSearchRange(date), ZoneId.of("UTC"))
    private val info = CalendarInfo(CalendarId(1), "Work", 0, CalendarAccount("owner@example.test", "com.google"), null, null, 700, true, true)
    private fun hit(id: Long, invitation: InvitationState): CalendarSearchHit {
        val start = date.atTime(9, 0).toInstant(ZoneOffset.UTC)
        val ref = EventRef(info.id, id, start, start.plusSeconds(3600), RecurrenceRef(99, start.minusSeconds(86400)))
        return CalendarSearchHit(EventOccurrence(ref, EventTime.Timed(ref.instanceStart, ref.instanceEnd), "Private $id", 0, invitation = invitation))
    }
    private fun port(block: (CalendarSearchRequest) -> Flow<CalendarReadState<CalendarSearchSnapshot>>) = object : CalendarSearch {
        override fun observe(request: CalendarSearchRequest) = block(request)
    }
    private suspend fun ready(controller: SearchController, status: SearchStatus = SearchStatus.RESULTS) =
        withTimeout(5000) { controller.state.first { it.status == status } }

    @Test fun filterSwitchImmediatelyClearsRowsAndCancelsPriorSubscription() = runBlocking {
        val cancelled = CompletableDeferred<Unit>()
        val controller = SearchController(this, port { request -> flow {
            try {
                emit(CalendarReadState.Ready(CalendarSearchSnapshot(request, listOf(info), listOf(hit(1, InvitationState.PENDING)), false)))
                awaitCancellation()
            } finally { cancelled.complete(Unit) }
        } }, calendar)
        try {
            controller.setQuery("private"); ready(controller)
            controller.updateContext(calendar.selection, calendar.displayZone, invitationFilter = InvitationFilter.PENDING_ONLY)
            assertNull(controller.state.value.snapshot)
            assertTrue(controller.state.value.hits.isEmpty())
            assertEquals(InvitationFilter.PENDING_ONLY, controller.state.value.calendar.invitationFilter)
            withTimeout(1000) { cancelled.await() }
            assertEquals(InvitationFilter.PENDING_ONLY, ready(controller).snapshot!!.request.calendar.invitationFilter)
        } finally { controller.close() }
    }
    @Test fun omittedFilterRetainsPendingAcrossOtherContextChangesAndExplicitAllRestoresIt() = runBlocking {
        val controller = SearchController(this, port { emptyFlow() }, calendar.copy(invitationFilter = InvitationFilter.PENDING_ONLY))
        try {
            controller.updateContext(CalendarSelection.Explicit(setOf(info.id)), ZoneId.of("Asia/Kolkata"), false)
            assertEquals(InvitationFilter.PENDING_ONLY, controller.state.value.calendar.invitationFilter)
            assertFalse(controller.state.value.includeDeclined)
            controller.updateContext(calendar.selection, calendar.displayZone, invitationFilter = InvitationFilter.ALL)
            assertEquals(InvitationFilter.ALL, controller.state.value.calendar.invitationFilter)
        } finally { controller.close() }
    }
    @Test fun pendingNormalizationPreservesIdentityAndUnknownCoverageWithoutAcceptedRows() = runBlocking {
        val pending = hit(1, InvitationState.PENDING)
        val controller = SearchController(this, port { request -> flowOf(CalendarReadState.Ready(CalendarSearchSnapshot(request,
            listOf(info), listOf(pending, hit(2, InvitationState.UNKNOWN), hit(3, InvitationState.NOT_PENDING)), false,
            listOf(CalendarCoverage(info.id, CoverageReason.SYNC_DISABLED))))) }, calendar.copy(invitationFilter = InvitationFilter.PENDING_ONLY))
        try {
            controller.setQuery("private"); val state = ready(controller)
            assertEquals(listOf(pending), state.hits)
            assertEquals(2, state.snapshot!!.coverage.size)
            assertTrue(state.snapshot!!.coverage.contains(CalendarCoverage(info.id, CoverageReason.INVITATION_CLASSIFICATION_UNKNOWN)))
        } finally { controller.close() }
    }
    @Test fun unknownOnlyResultRetainsIncompleteCoverage() = runBlocking {
        val controller = SearchController(this, port { request -> flowOf(CalendarReadState.Ready(CalendarSearchSnapshot(request,
            listOf(info), listOf(hit(1, InvitationState.UNKNOWN)), false))) }, calendar.copy(invitationFilter = InvitationFilter.PENDING_ONLY))
        try {
            controller.setQuery("private"); val state = ready(controller, SearchStatus.NO_MATCHES)
            assertTrue(state.hits.isEmpty())
            assertEquals(CoverageReason.INVITATION_CLASSIFICATION_UNKNOWN, state.snapshot!!.coverage.single().reason)
        } finally { controller.close() }
    }
    @Test fun snapshotWithOnlyFilterMismatchCannotExposePrivateHits() {
        val pending = calendar.copy(invitationFilter = InvitationFilter.PENDING_ONLY)
        val state = SearchUiState("private", pending, status = SearchStatus.RESULTS,
            snapshot = CalendarSearchSnapshot(CalendarSearchRequest("private", calendar), listOf(info), listOf(hit(1, InvitationState.PENDING)), false))
        assertNull(state.matchingSnapshot)
        assertTrue(state.hits.isEmpty())
    }
}
