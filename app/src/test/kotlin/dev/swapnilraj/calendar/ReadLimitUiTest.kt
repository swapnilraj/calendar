package dev.swapnilraj.calendar

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.search.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import dev.swapnilraj.calendar.data.CalEvent
import dev.swapnilraj.calendar.data.CalendarState
import dev.swapnilraj.calendar.ui.DayScreen
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadLimitUiTest {
    @get:Rule val compose = createComposeRule()
    private val zone = ZoneId.of("Europe/London")
    private val date = LocalDate.of(2026, 11, 17)
    private val request = CalendarRequest(defaultSearchRange(date), zone)
    private val metadata = CalendarInfo(CalendarId(1), "Private calendar label", 0, CalendarAccount("owner@example.test", "com.google"), null, null, 700, true, true)
    private fun snapshot(search: CalendarSearchRequest): CalendarSearchSnapshot {
        val start = date.atTime(9, 0).atZone(zone).toInstant()
        val ref = EventRef(CalendarId(1), 1, start, start.plusSeconds(3600))
        return CalendarSearchSnapshot(search, listOf(metadata), listOf(CalendarSearchHit(EventOccurrence(ref, EventTime.Timed(ref.instanceStart, ref.instanceEnd), "Private event payload", 0))), false,
            listOf(CalendarCoverage(CalendarId(1), CoverageReason.SYNC_DISABLED)))
    }
    @Test fun controllerLimitClearsPriorAuthorityAndRetainsCurrentQueryAndRange() = runBlocking {
        val limited = CompletableDeferred<Unit>()
        val port = object : CalendarSearch {
            override fun observe(request: CalendarSearchRequest) = flow<CalendarReadState<CalendarSearchSnapshot>> {
                emit(CalendarReadState.Ready(snapshot(request)))
                limited.await()
                emit(CalendarReadState.LimitExceeded)
                awaitCancellation()
            }
        }
        val controller = SearchController(this, port, request)
        try {
            controller.setQuery("private")
            withTimeout(5000) { controller.state.first { it.status == SearchStatus.RESULTS } }
            limited.complete(Unit)
            val state = withTimeout(5000) { controller.state.first { it.status == SearchStatus.LIMIT_EXCEEDED } }
            assertTrue(state.hits.isEmpty())
            assertNull(state.snapshot)
            assertEquals("private", state.query)
            assertEquals(request, state.calendar)
        } finally { controller.close() }
    }
    @Test @Config(qualifiers = "+notnight") fun searchLimitCannotDisplayStalePayloadAndIsActionableAtLargeRtl() {
        var retries = 0
        val stale = snapshot(CalendarSearchRequest("private", request))
        compose.setContent { DaylightTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f), LocalLayoutDirection provides LayoutDirection.Rtl) {
                SearchSheet(SearchUiState("private", request, status = SearchStatus.LIMIT_EXCEEDED, snapshot = stale), {}, {}, {}, { retries++ }, {}, {}, {})
            }
        } }
        compose.onNodeWithTag("search_results").performScrollToNode(hasText("This search contains too much calendar data. Narrow the phrase or date range, or choose fewer calendars, then try again."))
        compose.onNodeWithText("Private event payload").assertDoesNotExist()
        compose.onNodeWithText("Private calendar label", substring = true).assertDoesNotExist()
        compose.onNodeWithText("No matching events in this date range.").assertDoesNotExist()
        compose.onNodeWithTag("search_query").assertTextContains("private")
        compose.onNodeWithTag("search_sheet").captureRoboImage("src/test/screenshots/read_limit_search_large_rtl.png")
        compose.onNodeWithTag("search_results").performScrollToNode(hasText("Retry"))
        compose.onNodeWithText("Retry").performClick()
        assertEquals(1, retries)
    }
    @Test fun truncatedEmptySnapshotStillExplainsIncompleteCoverage() {
        val search = CalendarSearchRequest("private", request)
        compose.setContent { DaylightTheme {
            SearchSheet(SearchUiState("private", request, status = SearchStatus.NO_MATCHES,
                snapshot = snapshot(search).copy(hits = emptyList(), truncated = true)), {}, {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithTag("search_results").performScrollToNode(hasText("No matches were found in the checked portion. The search ended before all events were checked."))
        compose.onNodeWithText("No matching events in this date range.").assertDoesNotExist()
    }
    @Test @Config(qualifiers = "+notnight") fun dayLimitReplacesOldEventsAndEmptyGridWithAnExplicitPanel() {
        var calendars = 0
        var retries = 0
        val start = date.atTime(9, 0).atZone(zone).toInstant()
        val state = MutableStateFlow<CalendarState>(CalendarState.Ready(listOf(CalEvent(1, start, start.plusSeconds(3600), "Private event payload", 0xff526e9e.toInt()))))
        compose.setContent { DaylightTheme {
            DayScreen(date.atTime(10, 0).atZone(zone), true, 64f, { _, _ -> state }, {}, {}, initialScrollHour = 8.0,
                onRetry = { retries++ }, onChooseCalendars = { calendars++ })
        } }
        compose.onNodeWithText("Private event payload").assertIsDisplayed()
        state.value = CalendarState.LimitExceeded
        compose.waitForIdle()
        compose.onNodeWithText("Private event payload").assertDoesNotExist()
        compose.onNodeWithText("09:00").assertDoesNotExist()
        val day = hasAnyAncestor(hasContentDescription("Day $date"))
        compose.onNode(hasText("Choose calendars") and day).performClick()
        compose.onNode(hasText("Retry") and day).performClick()
        assertEquals(1, calendars)
        assertEquals(1, retries)
        compose.onRoot().captureRoboImage("src/test/screenshots/read_limit_day_light.png")
    }
}
