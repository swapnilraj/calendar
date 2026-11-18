package dev.swapnilraj.calendar

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.search.*
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
import java.time.*
import java.util.Locale
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
class SearchSheetTest {
    @get:Rule val compose = createComposeRule()
    private val date = LocalDate.of(2026, 11, 17)
    private val calendar = CalendarRequest(defaultSearchRange(date), ZoneId.of("America/Los_Angeles"), CalendarSelection.Explicit(setOf(CalendarId(1))))
    private val account = CalendarInfo(CalendarId(1), "Planning", 0xff526e9e.toInt(), CalendarAccount("owner@example.test", "com.google"), null, null, 700, true, false)
    private val recurring = EventRef(CalendarId(1), 7, date.atTime(9, 0).atZone(calendar.displayZone).toInstant(), date.atTime(10, 0).atZone(calendar.displayZone).toInstant(),
        RecurrenceRef(6, date.minusDays(1).atTime(9, 0).atZone(calendar.displayZone).toInstant()))
    private fun ready(limit: Int = 200): SearchUiState {
        val request = CalendarSearchRequest("review", calendar, limit)
        val allDayStart = date.atStartOfDay(ZoneOffset.UTC).toInstant()
        val allDayEnd = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
        val hits = listOf(CalendarSearchHit(EventOccurrence(recurring, EventTime.Timed(recurring.instanceStart, recurring.instanceEnd), "Quarterly review", 0xff526e9e.toInt()),
            "Meeting room", "Review the milestones and upcoming work."),
            CalendarSearchHit(EventOccurrence(EventRef(CalendarId(1), 8, allDayStart, allDayEnd), EventTime.AllDay.fromProvider(allDayStart, allDayEnd), "Review day", 0xff397d70.toInt()), snippet = "Plain review text"))
        return SearchUiState("review", calendar, limit, status = SearchStatus.RESULTS,
            snapshot = CalendarSearchSnapshot(request, listOf(account), hits, true, listOf(CalendarCoverage(account.id, CoverageReason.SYNC_DISABLED))))
    }
    private fun show(state: SearchUiState = ready(), font: Float = 1f, rtl: Boolean = false,
        onOpen: (EventRef) -> Unit = {}, onQuery: (String) -> Unit = {}, onRange: (CalendarDateRange) -> Unit = {},
        onMore: () -> Unit = {}, onRetry: () -> Unit = {}, onCalendars: () -> Unit = {}, onDismiss: () -> Unit = {}, onPermission: (() -> Unit)? = null) {
        compose.setContent {
            DaylightTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, font), LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    SearchSheet(state, onQuery, onRange, onMore, onRetry, onCalendars, onOpen, onDismiss, onRequestPermission = onPermission)
                }
            }
        }
        compose.waitForIdle()
    }
    private fun resultNode(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("search_results").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }
    private fun resultText(label: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("search_results").performScrollToNode(hasText(label))
        return compose.onNodeWithText(label)
    }
    @Test fun recurringResultOpensExactIdentityAndPlainMetadataIsVisible() {
        var opened: EventRef? = null
        show(onOpen = { opened = it })
        resultNode("search_hit_1_7").performClick()
        assertEquals(recurring, opened)
        compose.onNodeWithText("Meeting room").assertExists()
        compose.onNodeWithText("Review the milestones and upcoming work.").assertExists()
    }
    @Test fun sameDayTimedHitsAlwaysIdentifyTheirDateAndYear() {
        val day = LocalDate.of(2027, 1, 1)
        val start = day.atTime(9, 0).atZone(calendar.displayZone).toInstant()
        val event = EventOccurrence(recurring.copy(instanceStart = start, instanceEnd = start.plusSeconds(3600)),
            EventTime.Timed(start, start.plusSeconds(3600)), "New year review", 0xff526e9e.toInt())
        val label = searchTimeLabel(event, calendar.displayZone, true, Locale.US)
        assertEquals("\u202aJan 1, 2027 · 09:00 – 10:00\u202c", label)
        val state = ready()
        show(state = state.copy(snapshot = state.snapshot!!.copy(hits = listOf(CalendarSearchHit(event)))))
        resultNode("search_hit_1_7").assertIsDisplayed()
        compose.onNodeWithText("Jan 1, 2027", substring = true).assertIsDisplayed()
    }
    @Test fun crossDayTimedHitsKeepBothDatesWithoutDuplicatePrefix() {
        val first = LocalDate.of(2026, 12, 31).atTime(23, 0).atZone(calendar.displayZone).toInstant()
        val last = LocalDate.of(2027, 1, 1).atTime(1, 0).atZone(calendar.displayZone).toInstant()
        val event = EventOccurrence(recurring.copy(instanceStart = first, instanceEnd = last), EventTime.Timed(first, last), "Overnight", 0)
        val label = searchTimeLabel(event, calendar.displayZone, true, Locale.US)
        assertEquals("\u202aDec 31, 2026 23:00 – Jan 1, 2027 01:00\u202c", label)
    }
    @Test fun repeatedHourKeepsDateYearOffsetsAndBidiOrdering() {
        val zone = ZoneId.of("Europe/London")
        val start = Instant.parse("2026-10-25T00:45:00Z")
        val end = Instant.parse("2026-10-25T01:15:00Z")
        val event = EventOccurrence(recurring.copy(instanceStart = start, instanceEnd = end), EventTime.Timed(start, end), "Clock review", 0)
        assertEquals("\u202aOct 25, 2026 · 01:45 UTC+01:00 – 01:15 UTC+00:00\u202c", searchTimeLabel(event, zone, true, Locale.US))
    }
    @Test fun allDaySearchResultUsesUtcDatesAndDescriptionIsNonexecutingPlainText() {
        val state = ready()
        val hits = state.hits.map { if (it.occurrence.ref.eventId == 8L) it.copy(snippet = "<b>Plain review text</b>") else it }
        show(state = state.copy(snapshot = state.snapshot!!.copy(hits = hits)))
        resultNode("search_hit_1_8").assertIsDisplayed()
        compose.onNodeWithText("All day · Nov 17, 2026", substring = true).assertIsDisplayed()
        compose.onNodeWithText("<b>Plain review text</b>").assertIsDisplayed()
    }
    @Test fun boundedPaginationAndCoverageAreExplained() {
        var more = 0
        show(onMore = { more++ })
        resultText("More results").performClick()
        assertEquals(1, more)
        resultText("Only the first 2 matching events are shown. More matches may exist.").assertIsDisplayed()
        resultText("Planning · owner@example.test: calendar sync is off; recent changes may be missing.").assertIsDisplayed()
    }
    @Test fun truncatedEmptySearchDoesNotClaimCompleteNoMatches() {
        val state = ready()
        show(state = state.copy(status = SearchStatus.NO_MATCHES, snapshot = state.snapshot!!.copy(hits = emptyList())))
        resultText("No matches were found in the checked portion. The search ended before all events were checked.").assertIsDisplayed()
        compose.onNodeWithText("No matching events in this date range.").assertDoesNotExist()
        resultText("More results").assertIsDisplayed()
    }
    @Test fun resultCapOffersNarrowingInsteadOfDeadMoreButton() {
        show(state = ready(1000))
        resultText("Narrow the phrase or date range to find other events.").assertIsDisplayed()
        compose.onNodeWithText("More results").assertDoesNotExist()
    }
    @Test fun queryCalendarAndCloseActionsOnlyDelegate() {
        var query = ""
        var calendars = 0
        var dismissed = 0
        show(onQuery = { query = it }, onCalendars = { calendars++ }, onDismiss = { dismissed++ })
        compose.onNodeWithTag("search_query").performTextReplacement("literal_%")
        assertEquals("literal_%", query)
        resultText("Choose calendars").performClick()
        compose.onNodeWithText("Close").performClick()
        assertEquals(1, calendars)
        assertEquals(1, dismissed)
    }
    @Test fun customRangePickerConvertsInclusiveEndExactlyOnce() {
        var requested: CalendarDateRange? = null
        show(onRange = { requested = it })
        compose.onNodeWithTag("search_date_range").performClick()
        compose.onNodeWithText("Search these dates").performClick()
        assertEquals(calendar.range, requested)
    }
    @Test fun rangeExpansionRetainsQueryAndShowsTheBound() {
        var requested: CalendarDateRange? = null
        show(onRange = { requested = it })
        resultText("Include 12 months later").performClick()
        assertEquals(expandedSearchRange(calendar.range, false), requested)
        compose.onNodeWithTag("search_query").assertTextContains("review")
    }
    @Test fun permissionActionOnlyAppearsWhenRootSuppliesRecovery() {
        var grants = 0
        show(state = SearchUiState("review", calendar, status = SearchStatus.PERMISSION_REQUIRED), onPermission = { grants++ })
        resultText("Allow calendar access").performClick()
        assertEquals(1, grants)
    }
    @Test fun unavailableKeepsQueryAndOffersRetry() {
        var retried = 0
        show(state = SearchUiState("review", calendar, status = SearchStatus.UNAVAILABLE), onRetry = { retried++ })
        resultText("Retry").performClick()
        assertEquals(1, retried)
        compose.onNodeWithTag("search_query").assertTextContains("review")
    }
    @Test @Config(qualifiers = "+notnight") fun searchResultsLight() {
        show()
        resultNode("search_hit_1_7").assertIsDisplayed()
        compose.onNodeWithTag("search_sheet").captureRoboImage("src/test/screenshots/search_results_light.png")
    }
    @Test @Config(qualifiers = "+night") fun searchPermissionDark() {
        show(state = SearchUiState("review", calendar, status = SearchStatus.PERMISSION_REQUIRED), onPermission = {})
        resultText("Calendar access is needed to search events.").assertIsDisplayed()
        compose.onNodeWithTag("search_sheet").captureRoboImage("src/test/screenshots/search_permission_dark.png")
    }
    @Test @Config(qualifiers = "+notnight") fun searchLargeRtlKeepsFullOccurrenceReachable() {
        show(font = 2f, rtl = true)
        resultNode("search_hit_1_7").assertIsDisplayed()
        compose.onNodeWithTag("search_sheet").captureRoboImage("src/test/screenshots/search_large_rtl.png")
        resultNode("search_hit_1_8").assertIsDisplayed()
    }
}
