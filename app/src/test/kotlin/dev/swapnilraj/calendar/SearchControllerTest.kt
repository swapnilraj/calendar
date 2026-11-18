package dev.swapnilraj.calendar

import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.search.*
import java.time.*
import java.util.Collections
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class SearchControllerTest {
    private val date = LocalDate.of(2026, 11, 17)
    private val calendar = CalendarRequest(defaultSearchRange(date), ZoneId.of("Europe/London"))
    private fun metadata(visible: Boolean = true) = CalendarInfo(CalendarId(1), "Work", 0xff526e9e.toInt(), CalendarAccount("owner@example.test", "com.google"), null, null, 700, visible, true)
    private fun hit(id: Long = 1, calendarId: Long = 1, declined: Boolean = false): CalendarSearchHit {
        val start = date.atTime(9, 0).atZone(calendar.displayZone).toInstant().plusSeconds(id)
        val ref = EventRef(CalendarId(calendarId), id, start, start.plusSeconds(3600), RecurrenceRef(9, start.minusSeconds(86400)))
        return CalendarSearchHit(EventOccurrence(ref, EventTime.Timed(ref.instanceStart, ref.instanceEnd), "Review $id", 0, declined), "Room", "Review notes")
    }
    private fun port(block: (CalendarSearchRequest) -> Flow<CalendarReadState<CalendarSearchSnapshot>>) = object : CalendarSearch {
        override fun observe(request: CalendarSearchRequest) = block(request)
    }
    private suspend fun await(controller: SearchController, status: SearchStatus = SearchStatus.RESULTS) = withTimeout(5000) { controller.state.first { it.status == status } }
    @Test fun defaultAndExpandedRangesUseInclusiveCalendarDatesAndSupportedBounds() {
        assertEquals(CalendarDateRange(LocalDate.of(2025, 11, 17), LocalDate.of(2028, 11, 18)), defaultSearchRange(date))
        val leap = CalendarDateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2028, 2, 29))
        assertEquals(LocalDate.of(2029, 3, 1), expandedSearchRange(leap, false)?.endExclusive)
        assertEquals(LocalDate.of(1, 1, 1), defaultSearchRange(LocalDate.of(1, 1, 1)).start)
        assertEquals(LocalDate.of(10000, 1, 1), defaultSearchRange(LocalDate.of(9999, 12, 31)).endExclusive)
        val ten = CalendarDateRange(LocalDate.of(2020, 1, 1), LocalDate.of(2030, 1, 1))
        assertTrue(validSearchRange(ten))
        assertNull(expandedSearchRange(ten, true))
        assertNull(expandedSearchRange(ten, false))
        assertFalse(validSearchRange(CalendarDateRange(ten.start, ten.endExclusive.plusDays(1))))
    }
    @Test fun rapidEditsDebounceToOneTrimmedLiteralRequest() = runBlocking {
        val seen = Collections.synchronizedList(mutableListOf<CalendarSearchRequest>())
        val controller = SearchController(this, port { request -> seen += request; flowOf(CalendarReadState.Ready(CalendarSearchSnapshot(request, listOf(metadata()), listOf(hit()), false))) }, calendar)
        try {
            controller.setQuery("r")
            controller.setQuery("rev")
            controller.setQuery("  review_%  ")
            assertEquals(SearchStatus.WAITING, controller.state.value.status)
            assertTrue(seen.isEmpty())
            val ready = await(controller)
            assertEquals(listOf("review_%"), seen.map { it.query })
            assertEquals("  review_%  ", ready.query)
            assertEquals(200, seen.single().limit)
        } finally { controller.close() }
    }
    @Test fun editingCancelsLiveReadImmediatelyAndRemovesOldRowsBeforeNextRead() = runBlocking {
        val cancelled = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val controller = SearchController(this, port { request -> flow {
            try { emit(CalendarReadState.Ready(CalendarSearchSnapshot(request, listOf(metadata()), listOf(hit()), false))); started.complete(Unit); awaitCancellation() }
            finally { cancelled.complete(Unit) }
        } }, calendar)
        try {
            controller.setQuery("first")
            started.await()
            assertEquals(1, controller.state.value.hits.size)
            controller.setQuery("second")
            assertEquals(SearchStatus.WAITING, controller.state.value.status)
            assertTrue(controller.state.value.hits.isEmpty())
            withTimeout(1000) { cancelled.await() }
        } finally { controller.close() }
    }
    @Test fun staleRequestSnapshotsAreIgnored() = runBlocking {
        val controller = SearchController(this, port { request -> flow {
            emit(CalendarReadState.Ready(CalendarSearchSnapshot(request.copy(query = "stale"), listOf(metadata()), listOf(hit(99)), false)))
            emit(CalendarReadState.Ready(CalendarSearchSnapshot(request, listOf(metadata()), listOf(hit(1)), false)))
        } }, calendar)
        try { controller.setQuery("current"); assertEquals(1L, await(controller).hits.single().occurrence.ref.eventId) }
        finally { controller.close() }
    }
    @Test fun emptyOverlongInvalidRangeAndExplicitNoneNeverQueryPort() = runBlocking {
        var calls = 0
        val controller = SearchController(this, port { calls++; emptyFlow() }, calendar)
        try {
            controller.setQuery("   ")
            assertEquals(SearchStatus.EMPTY_QUERY, controller.state.value.status)
            controller.setQuery("x".repeat(257))
            assertEquals(SearchStatus.INVALID_QUERY, controller.state.value.status)
            controller.setRange(CalendarDateRange(date, date.plusYears(10).plusDays(1)))
            controller.setQuery("review")
            assertEquals(SearchStatus.INVALID_RANGE, controller.state.value.status)
            controller.setRange(calendar.range)
            controller.updateContext(CalendarSelection.Explicit(emptySet()), calendar.displayZone)
            assertEquals(SearchStatus.NO_SELECTION, controller.state.value.status)
            delay(350)
            assertEquals(0, calls)
        } finally { controller.close() }
    }
    @Test fun noCalendarsNoSelectionNoMatchesAndPermissionRemainDistinct() = runBlocking {
        val states = listOf(SearchStatus.NO_CALENDARS, SearchStatus.NO_SELECTION, SearchStatus.NO_MATCHES, SearchStatus.PERMISSION_REQUIRED, SearchStatus.UNAVAILABLE)
        for (expected in states) {
            val controller = SearchController(this, port { request -> flowOf(when (expected) {
                SearchStatus.PERMISSION_REQUIRED -> CalendarReadState.PermissionRequired
                SearchStatus.UNAVAILABLE -> CalendarReadState.Unavailable
                else -> CalendarReadState.Ready(CalendarSearchSnapshot(request, when (expected) {
                    SearchStatus.NO_CALENDARS -> emptyList(); SearchStatus.NO_SELECTION -> listOf(metadata(false)); else -> listOf(metadata())
                }, emptyList(), false))
            }) }, calendar)
            try { controller.setQuery("review"); assertEquals(expected, await(controller, expected).status) }
            finally { controller.close() }
        }
    }
    @Test fun filteringDedupSortingAndIdentityUseFullOriginalOccurrence() = runBlocking {
        val expected = hit(2)
        val controller = SearchController(this, port { request -> flowOf(CalendarReadState.Ready(CalendarSearchSnapshot(request, listOf(metadata()),
            listOf(hit(3), expected, expected, hit(4, calendarId = 2), hit(5, declined = true)), false,
            listOf(CalendarCoverage(CalendarId(1), CoverageReason.SYNC_DISABLED))))) }, calendar.copy(selection = CalendarSelection.Explicit(setOf(CalendarId(1)))), false)
        try {
            controller.setQuery("review")
            val ready = await(controller)
            assertEquals(listOf(2L, 3L), ready.hits.map { it.occurrence.ref.eventId })
            assertEquals(expected.occurrence.ref, ready.hits.first().occurrence.ref)
            assertEquals(CoverageReason.SYNC_DISABLED, ready.snapshot?.coverage?.single()?.reason)
        } finally { controller.close() }
    }
    @Test fun moreResultsAreBoundedAndContextChangesResetLimitAndCancelRows() = runBlocking {
        val seen = mutableListOf<CalendarSearchRequest>()
        val controller = SearchController(this, port { request -> seen += request; flowOf(CalendarReadState.Ready(CalendarSearchSnapshot(request, listOf(metadata()), listOf(hit()), true))) }, calendar)
        try {
            controller.setQuery("review")
            await(controller)
            repeat(4) { controller.loadMore(); await(controller) }
            assertEquals(listOf(200, 400, 600, 800, 1000), seen.map { it.limit })
            controller.loadMore()
            assertEquals(1000, controller.state.value.limit)
            controller.updateContext(CalendarSelection.Explicit(setOf(CalendarId(1))), ZoneId.of("UTC"), false)
            assertEquals(200, controller.state.value.limit)
            assertTrue(controller.state.value.hits.isEmpty())
            val ready = await(controller)
            assertEquals(ZoneId.of("UTC"), ready.calendar.displayZone)
            assertFalse(ready.includeDeclined)
        } finally { controller.close() }
    }
    @Test fun oversizedSourceResultsAreCappedWithoutClaimingCompleteness() = runBlocking {
        val controller = SearchController(this, port { request -> flowOf(CalendarReadState.Ready(CalendarSearchSnapshot(request,
            listOf(metadata()), (1L..250L).map { hit(it) }, false))) }, calendar)
        try {
            controller.setQuery("review")
            val ready = await(controller)
            assertEquals(200, ready.hits.size)
            assertTrue(ready.snapshot!!.truncated)
        } finally { controller.close() }
    }
    @Test fun partialSearchWithoutHitsStillAllowsBoundedExpansion() = runBlocking {
        val seen = mutableListOf<Int>()
        val controller = SearchController(this, port { request -> seen += request.limit; flowOf(CalendarReadState.Ready(CalendarSearchSnapshot(request,
            listOf(metadata()), emptyList(), true))) }, calendar)
        try {
            controller.setQuery("review")
            await(controller, SearchStatus.NO_MATCHES)
            controller.loadMore()
            await(controller, SearchStatus.NO_MATCHES)
            assertEquals(listOf(200, 400), seen)
        } finally { controller.close() }
    }
    @Test fun retryRecoversFailureAndClosingDoesNotCancelCallerScope() = runBlocking {
        var calls = 0
        val controller = SearchController(this, port { request -> if (++calls == 1) throw IllegalStateException("Synthetic failure")
            else flowOf(CalendarReadState.Ready(CalendarSearchSnapshot(request, listOf(metadata()), listOf(hit()), false))) }, calendar)
        controller.setQuery("review")
        await(controller, SearchStatus.UNAVAILABLE)
        controller.retry()
        await(controller)
        controller.close()
        assertTrue(isActive)
        assertEquals(2, calls)
    }
}
