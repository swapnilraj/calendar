package dev.swapnilraj.calendar

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.agenda.*
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
import java.time.*
import java.time.temporal.ChronoUnit
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
class AgendaViewTest {
    @get:Rule val compose = createComposeRule()
    private val zone = ZoneId.of("America/Los_Angeles")
    private val date = LocalDate.of(2026, 11, 17)
    private val range = CalendarDateRange(date.minusDays(2), date.plusDays(5))
    private val recurring = EventRef(CalendarId(2), 7, Instant.parse("2026-11-17T19:00:00Z"), Instant.parse("2026-11-17T20:00:00Z"),
        RecurrenceRef(6, Instant.parse("2026-11-17T17:00:00Z")))
    private fun timed(id: Long, title: String, start: Instant, end: Instant, declined: Boolean = false): EventOccurrence =
        EventOccurrence(EventRef(CalendarId(1), id, start, end), EventTime.Timed(start, end), title, 0xff526e9e.toInt(), declined)
    private fun fixtures(): List<EventOccurrence> {
        val start = Instant.parse("2026-11-16T00:00:00Z")
        val end = Instant.parse("2026-11-19T00:00:00Z")
        val trip = EventOccurrence(EventRef(CalendarId(1), 1, start, end), EventTime.AllDay.fromProvider(start, end), "Work trip", 0xff397d70.toInt())
        val appointment = timed(2, "Planning session", date.atTime(9, 0).atZone(zone).toInstant(), date.atTime(10, 0).atZone(zone).toInstant())
        val late = timed(3, "Overnight maintenance", date.atTime(23, 0).atZone(zone).toInstant(), date.plusDays(1).atTime(1, 0).atZone(zone).toInstant())
        val declined = timed(4, "Declined review", date.plusDays(1).atTime(14, 0).atZone(zone).toInstant(), date.plusDays(1).atTime(15, 0).atZone(zone).toInstant(), true)
        val repeat = EventOccurrence(recurring, EventTime.Timed(recurring.instanceStart, recurring.instanceEnd), "Recurring standup", 0xff8a638a.toInt())
        val midnight = date.atStartOfDay(zone).toInstant()
        return listOf(declined, repeat, late, appointment, trip, appointment, timed(9, "Midnight point", midnight, midnight))
    }
    private fun show(initialRange: CalendarDateRange = range, initialSelection: LocalDate = date,
        events: List<EventOccurrence> = fixtures(), displayZone: ZoneId = zone, fontScale: Float = 1f,
        direction: LayoutDirection = LayoutDirection.Ltr, onSelect: (LocalDate) -> Unit = {},
        onOpen: (EventRef) -> Unit = {}, onRange: (CalendarDateRange) -> Unit = {}, showNavigationChrome: Boolean = true) {
        compose.setContent {
            var requestedRange by remember { mutableStateOf(initialRange) }
            var selection by remember { mutableStateOf(initialSelection) }
            DaylightTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale), LocalLayoutDirection provides direction) {
                    AgendaView(requestedRange, selection, events, displayZone,
                        onSelectDate = { selection = it; onSelect(it) }, onOpenEvent = onOpen,
                        onRequestRange = { requestedRange = it; onRange(it) }, today = date.plusDays(1), showNavigationChrome = showNavigationChrome)
                }
            }
        }
        compose.waitForIdle()
    }
    private fun agendaNode(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("agenda_list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }
    @Test fun sparseGroupsAreChronologicalAndAllDayComesBeforeTimedWithoutDuplicates() {
        val groups = agendaDays(range, date.plusDays(2), fixtures(), zone)
        assertEquals(listOf(date.minusDays(1), date, date.plusDays(1), date.plusDays(2)), groups.map { it.date })
        assertTrue(groups.last().events.isEmpty())
        val selected = groups.single { it.date == date }.events
        assertEquals(listOf("Work trip", "Midnight point", "Planning session", "Recurring standup", "Overnight maintenance"), selected.map { it.title })
    }
    @Test fun allDayUtcDatesDoNotShiftWithTheDisplayZone() {
        val trip = fixtures().single { it.title == "Work trip" }
        for (displayZone in listOf(zone, ZoneId.of("Pacific/Kiritimati"))) {
            val groups = agendaDays(range, date, listOf(trip), displayZone)
            assertEquals(listOf(date.minusDays(1), date, date.plusDays(1)), groups.map { it.date })
            val label = agendaTimeLabel(trip, displayZone, true, Locale.US)
            assertTrue(label.contains("Nov 16, 2026"))
            assertTrue(label.contains("Nov 18, 2026"))
            assertFalse(label.contains("Nov 19"))
            assertFalse(label.contains("16:00"))
        }
    }
    @Test fun multiDayTimedEventsRepeatOnlyOnIntersectingDatesAndMidnightEndIsExclusive() {
        val late = fixtures().single { it.title == "Overnight maintenance" }
        assertEquals(listOf(date, date.plusDays(1)), agendaDays(range, date, listOf(late), zone).map { it.date })
        val end = date.plusDays(1).atStartOfDay(zone).toInstant()
        val midnightEnd = timed(42, "Until midnight", date.atTime(23, 0).atZone(zone).toInstant(), end)
        assertEquals(listOf(date), agendaDays(range, date, listOf(midnightEnd), zone).map { it.date })
        val point = timed(43, "Next midnight", end, end)
        assertEquals(listOf(date.plusDays(1)), agendaDays(range, date.plusDays(1), listOf(point), zone).map { it.date })
    }
    @Test fun dateHeadersChangeTheSharedSelection() {
        var selected: LocalDate? = null
        show(onSelect = { selected = it })
        agendaNode("agenda_date_${date.plusDays(1)}").performClick().assertIsSelected()
        assertEquals(date.plusDays(1), selected)
    }
    @Test fun eventClickPreservesMovedRecurrenceIdentityAndSelectsItsDisplayedDate() {
        var selected: LocalDate? = null
        var opened: EventRef? = null
        show(onSelect = { selected = it }, onOpen = { opened = it })
        agendaNode("agenda_event_${date}_2_7").performClick()
        assertEquals(date, selected)
        assertEquals(recurring, opened)
        assertEquals(Instant.parse("2026-11-17T17:00:00Z"), opened?.recurrence?.originalStart)
    }
    @Test fun earlierAndLaterRequestAdjacentWindowsWithoutImplicitDataReads() {
        var requested: CalendarDateRange? = null
        var selected: LocalDate? = null
        show(onRange = { requested = it }, onSelect = { selected = it })
        compose.onNodeWithText("Earlier").performClick()
        val previous = CalendarDateRange(range.start.minusDays(7), range.start)
        assertEquals(previous, requested)
        assertEquals(previous.start, selected)
        compose.onNodeWithText("Later").performClick()
        assertEquals(range, requested)
        compose.onNodeWithText("Later").performClick()
        assertEquals(CalendarDateRange(range.endExclusive, range.endExclusive.plusDays(7)), requested)
    }
    @Test @Config(qualifiers = "w360dp-h640dp-mdpi") fun phoneChromeSuppressionRetainsRangeAndExplicitEndpointsWithoutScrollReads() {
        val requested = mutableListOf<CalendarDateRange>()
        val selections = mutableListOf<LocalDate>()
        show(onRange = { requested += it }, onSelect = { selections += it }, showNavigationChrome = false)
        compose.onNodeWithTag("agenda_loaded_range").assertIsDisplayed()
        compose.onNodeWithTag("agenda_earlier").assertIsDisplayed()
        compose.onNodeWithTag("agenda_later").assertIsDisplayed()
        agendaNode("agenda_event_${date.plusDays(1)}_1_4").assertIsDisplayed()
        assertTrue(requested.isEmpty())
        compose.onNodeWithTag("agenda_later").performClick()
        val next = CalendarDateRange(range.endExclusive, range.endExclusive.plusDays(7))
        assertEquals(listOf(next), requested)
        assertEquals(listOf(next.start), selections)
        compose.onNodeWithTag("agenda_earlier").performClick()
        assertEquals(listOf(next, range), requested)
        assertEquals(listOf(next.start, range.start), selections)
        compose.onRoot().captureRoboImage("src/test/screenshots/agenda_phone_compact.png")
    }
    @Test fun firstSupportedYearDisablesEarlierAndExplainsTheBoundary() {
        var requests = 0
        show(initialRange = CalendarDateRange(AgendaMinDate, AgendaMinDate.plusDays(31)), initialSelection = AgendaMinDate,
            events = emptyList(), onRange = { requests++ }, showNavigationChrome = false)
        compose.onNodeWithTag("agenda_earlier").assertIsNotEnabled()
        compose.onNodeWithTag("agenda_later").assertIsEnabled()
        compose.onNodeWithText("Start of supported dates (year 1).").assertIsDisplayed()
        assertEquals(0, requests)
        compose.onRoot().captureRoboImage("src/test/screenshots/agenda_phone_boundary.png")
    }
    @Test fun compactDateAndCardRemainDistinctFullSizeActionsWithExactOccurrence() {
        var opened: EventRef? = null
        var rangeChanges = 0
        show(onOpen = { opened = it }, onRange = { rangeChanges++ }, showNavigationChrome = false)
        val dateBounds = compose.onNodeWithTag("agenda_date_$date").fetchSemanticsNode().boundsInRoot
        val card = agendaNode("agenda_event_${date}_2_7")
        val cardBounds = card.fetchSemanticsNode().boundsInRoot
        assertTrue(dateBounds.width >= 48f && dateBounds.height >= 48f)
        assertTrue(cardBounds.width >= 48f && cardBounds.height >= 48f)
        card.performClick()
        assertEquals(recurring, opened)
        assertEquals(0, rangeChanges)
    }
    @Test @Config(qualifiers = "w320dp-h640dp-mdpi") fun narrowLargeRtlStacksWrappingCardsAndRetainsEndpointAccess() {
        val title = "A long personal and work appointment title that must remain available in full"
        val event = fixtures().first { it.ref == recurring }.copy(title = title)
        var opened: EventRef? = null
        show(events = listOf(event), fontScale = 2f, direction = LayoutDirection.Rtl, onOpen = { opened = it }, showNavigationChrome = false)
        compose.onNodeWithTag("agenda_loaded_range").assertIsDisplayed()
        compose.onNodeWithTag("agenda_earlier").assertIsDisplayed()
        compose.onNodeWithTag("agenda_later").assertIsDisplayed()
        agendaNode("agenda_event_${date}_2_7").performClick()
        compose.onNodeWithText(title).assertExists()
        assertEquals(recurring, opened)
    }
    @Test fun datePickerSelectsAnArbitraryLeapDayAndRequestsItsBoundedWindow() {
        var selected: LocalDate? = null
        var requested: CalendarDateRange? = null
        val leap = LocalDate.of(2028, 2, 29)
        show(initialSelection = leap, onSelect = { selected = it }, onRange = { requested = it })
        compose.onNodeWithContentDescription("Choose agenda date. Selected: Tuesday, February 29, 2028").performClick()
        compose.onNodeWithText("Go to date").performClick()
        assertEquals(leap, selected)
        val window = requireNotNull(requested)
        assertTrue(leap in window)
        assertEquals(7L, ChronoUnit.DAYS.between(window.start, window.endExclusive))
    }
    @Test fun todayUpdatesSelectionAndRequestsARangeOnlyWhenOutside() {
        var selected: LocalDate? = null
        var requested: CalendarDateRange? = null
        show(initialRange = CalendarDateRange.day(date.minusDays(30)), onSelect = { selected = it }, onRange = { requested = it })
        compose.onNodeWithContentDescription("Go to today").performClick()
        assertEquals(date.plusDays(1), selected)
        assertEquals(CalendarDateRange.day(date.plusDays(1)), requested)
    }
    @Test fun outsideSelectionOffersARealRangeRequestWithoutRewritingSelection() {
        val leap = LocalDate.of(2028, 2, 29)
        var requested: CalendarDateRange? = null
        var selections = 0
        show(initialSelection = leap, onSelect = { selections++ }, onRange = { requested = it })
        compose.onNodeWithText("Show selected date").performClick()
        assertTrue(leap in requireNotNull(requested))
        assertEquals(0, selections)
    }
    @Test fun emptyReadyRangeHasUsefulDateSpecificAndRangeMessages() {
        show(events = emptyList())
        compose.onNodeWithText("No events in the displayed range.").assertIsDisplayed()
        compose.onNodeWithText("No events for this date.").assertIsDisplayed()
        compose.onNodeWithTag("agenda_date_$date").assertIsSelected()
    }
    @Test fun rangeBoundariesNeverRequestUnsupportedYears() {
        val early = CalendarDateRange(AgendaMinDate, AgendaMinDate.plusDays(7))
        assertNull(adjacentAgendaRange(early, false))
        val late = CalendarDateRange(AgendaMaxDate.minusDays(6), AgendaMaxDate.plusDays(1))
        assertNull(adjacentAgendaRange(late, true))
        val containing = agendaWindow(AgendaMaxDate, 31)
        assertTrue(AgendaMaxDate in containing)
        assertEquals(31L, ChronoUnit.DAYS.between(containing.start, containing.endExclusive))
        show(initialRange = late, initialSelection = AgendaMaxDate, events = emptyList())
        compose.onNodeWithText("Later").assertIsNotEnabled()
        compose.onNodeWithText("Earlier").assertIsEnabled()
        compose.onNodeWithText("End of supported dates (year 9999).").assertIsDisplayed()
    }
    @Test(expected = IllegalArgumentException::class) fun unboundedRangesAreRejectedBeforeIteration() {
        agendaDays(CalendarDateRange(date, date.plusDays(367)), date, emptyList(), zone)
    }
    @Test fun repeatedHourLabelsDistinguishOffsetsAndActualDuration() {
        val london = ZoneId.of("Europe/London")
        val start = Instant.parse("2026-10-25T00:45:00Z")
        val end = Instant.parse("2026-10-25T01:15:00Z")
        val event = timed(99, "Clock change", start, end)
        val label = agendaTimeLabel(event, london, true, Locale.UK)
        assertTrue(label.contains("01:45 UTC+01:00"))
        assertTrue(label.contains("01:15 UTC+00:00"))
        show(initialRange = CalendarDateRange.day(LocalDate.of(2026, 10, 25)), initialSelection = LocalDate.of(2026, 10, 25),
            events = listOf(event), displayZone = london)
        compose.onNodeWithText("30 minutes", substring = true).assertIsDisplayed()
    }
    @Test @Config(qualifiers = "+notnight") fun agendaLight() {
        show()
        compose.onRoot().captureRoboImage("src/test/screenshots/agenda_light.png")
    }
    @Test @Config(qualifiers = "+night") fun agendaDark() {
        show()
        compose.onRoot().captureRoboImage("src/test/screenshots/agenda_dark.png")
    }
    @Test @Config(qualifiers = "+notnight") fun largeFontRtlKeepsEveryGroupReachable() {
        show(fontScale = 2f, direction = LayoutDirection.Rtl)
        compose.onRoot().captureRoboImage("src/test/screenshots/agenda_large_rtl.png")
        agendaNode("agenda_event_${date.plusDays(1)}_1_4").assertIsDisplayed().performClick()
    }
}
