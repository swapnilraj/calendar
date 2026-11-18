package dev.swapnilraj.calendar

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.BoxScope
import dev.swapnilraj.calendar.ui.LocalCalendarGutter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.multiday.*
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
import java.time.*
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
class MultiDayViewTest {
    @get:Rule val compose = createComposeRule()
    private val zone = ZoneId.of("Europe/London")
    private val date = LocalDate.of(2026, 10, 24)
    private val range = CalendarDateRange(date, date.plusDays(3))
    private val recurring = EventRef(CalendarId(2), 7, Instant.parse("2026-10-24T08:00:00Z"), Instant.parse("2026-10-24T09:00:00Z"),
        RecurrenceRef(6, Instant.parse("2026-10-23T08:00:00Z")))
    private fun fixtures(): List<EventOccurrence> {
        val allDay = (1..3).map { id ->
            val first = date.minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
            val end = date.plusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant()
            EventOccurrence(EventRef(CalendarId(1), id.toLong(), first, end), EventTime.AllDay.fromProvider(first, end), "Trip $id", 0xff397d70.toInt())
        }
        val repeated = EventOccurrence(recurring, EventTime.Timed(recurring.instanceStart, recurring.instanceEnd), "Standup", 0xff526e9e.toInt())
        val start = Instant.parse("2026-10-25T00:45:00Z")
        val end = Instant.parse("2026-10-25T01:15:00Z")
        val dst = EventOccurrence(EventRef(CalendarId(1), 9, start, end), EventTime.Timed(start, end), "Clock review", 0xff8a638a.toInt())
        return allDay + repeated + dst
    }
    private fun show(window: CalendarDateRange = range, events: List<EventOccurrence> = fixtures(), fontScale: Float = 1f,
        direction: LayoutDirection = LayoutDirection.Ltr, minute: Double = 480.0,
        onSelect: (LocalDate) -> Unit = {}, onOpen: (EventRef) -> Unit = {}, onDay: (LocalDate) -> Unit = {},
        onRange: (CalendarDateRange) -> Unit = {}, onAnchor: (Double) -> Unit = {}, onZoom: (Float) -> Unit = {},
        background: @Composable BoxScope.(LocalDate) -> Unit = {}, showWeekends: Boolean = true) {
        compose.setContent {
            var current by remember { mutableStateOf(window) }
            var selection by remember { mutableStateOf(date) }
            DaylightTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale), LocalLayoutDirection provides direction) {
                    MultiDayView(current, selection, events, date.atTime(8, 30).atZone(zone), zone, true, 64f, onZoom,
                        onSelectDate = { selection = it; onSelect(it) }, onOpenEvent = onOpen, onShowDateEvents = onDay,
                        onNavigateRange = { current = it; onRange(it) }, initialScrollMinute = minute, onScrollMinuteChanged = onAnchor, background = background, showWeekends = showWeekends)
                }
            }
        }
        compose.waitForIdle()
    }
    @Test fun weekHidesOnlyWeekendColumnsAndKeepsSevenDateNavigationAndSelectionAccess() {
        var requested: CalendarDateRange? = null
        var shown: LocalDate? = null
        val week = CalendarDateRange(date, date.plusDays(7))
        show(window = week, showWeekends = false, onRange = { requested = it }, onDay = { shown = it })
        compose.onNodeWithTag("multiday_grid_$date").assertDoesNotExist()
        compose.onNodeWithTag("multiday_grid_${date.plusDays(1)}").assertDoesNotExist()
        (2L..6L).forEach { compose.onNodeWithTag("multiday_grid_${date.plusDays(it)}").assertExists() }
        compose.onNodeWithTag("multiday_hidden_selection").assertIsDisplayed().assertIsSelected().performClick()
        assertEquals(date, shown)
        assertNull(requested)
        compose.onNodeWithText("Later").performClick()
        assertEquals(CalendarDateRange(week.endExclusive, week.endExclusive.plusDays(7)), requested)
    }
    @Test fun threeDayViewKeepsWeekendsEvenWhenWeekendsAreHiddenInWeekAndMonth() {
        show(showWeekends = false)
        multiDayDates(range).forEach { compose.onNodeWithTag("multiday_grid_$it").assertExists() }
        compose.onNodeWithTag("multiday_hidden_selection").assertDoesNotExist()
    }
    @Test @Config(qualifiers = "+notnight") fun weekdaysOnlyWeekPreservesOriginalRangeLabel() {
        show(window = CalendarDateRange(date, date.plusDays(7)), showWeekends = false)
        compose.onRoot().captureRoboImage("src/test/screenshots/multiday_weekdays_light.png")
    }
    @Test fun threeAndSevenDayMatricesCrossYearBoundariesWithoutLocaleAssumptions() {
        assertEquals(listOf(LocalDate.of(2026, 12, 30), LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 1)),
            multiDayDates(CalendarDateRange(LocalDate.of(2026, 12, 30), LocalDate.of(2027, 1, 2))))
        assertEquals(7, multiDayDates(CalendarDateRange(date, date.plusDays(7))).size)
    }
    @Test(expected = IllegalArgumentException::class) fun unsupportedRangeIsRejected() { multiDayDates(CalendarDateRange.day(date)) }
    @Test fun boundaryNavigationKeepsFullWindowsInsideSupportedYears() {
        assertNull(adjacentMultiDayRange(multiDayWindow(MultiDayMinDate, 7), false))
        val last = multiDayWindow(MultiDayMaxDate, 7)
        assertEquals(MultiDayMaxDate.plusDays(1), last.endExclusive)
        assertEquals(7, multiDayDates(last).size)
        assertNull(adjacentMultiDayRange(last, true))
    }
    @Test fun allDayDateSegmentationIsIndependentOfDisplayZoneAndPreservesIdentity() {
        val trip = fixtures().first()
        for (displayZone in listOf(zone, ZoneId.of("Pacific/Honolulu"), ZoneId.of("Pacific/Kiritimati"))) {
            val days = multiDayEvents(range, listOf(trip, trip), displayZone)
            assertEquals(listOf(date, date.plusDays(1)), days.filterValues { it.isNotEmpty() }.keys.toList())
            assertEquals(trip.ref, days.getValue(date).single().ref)
            assertEquals(trip.ref.instanceStart, days.getValue(date).single().start)
        }
    }
    @Test fun timedMultiDayMidnightEndIsExclusiveAndMidnightPointBelongsToNewDate() {
        val start = date.atTime(23, 0).atZone(zone).toInstant()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant()
        val event = EventOccurrence(EventRef(CalendarId(1), 44, start, end), EventTime.Timed(start, end), "Late", 0)
        val point = EventOccurrence(EventRef(CalendarId(1), 45, end, end), EventTime.Timed(end, end), "Point", 0)
        val days = multiDayEvents(range, listOf(event, point), zone)
        assertEquals(listOf(44L), days.getValue(date).map { it.id })
        assertEquals(listOf(45L), days.getValue(date.plusDays(1)).map { it.id })
    }
    @Test fun eventClickKeepsOriginalRecurringRefAndSelectsItsColumn() {
        var opened: EventRef? = null
        var selected: LocalDate? = null
        show(onSelect = { selected = it }, onOpen = { opened = it })
        compose.onNodeWithText("Standup").performClick()
        assertEquals(recurring, opened)
        assertEquals(date, selected)
    }
    @Test fun allDayOverflowDelegatesFullDayWithoutCreatingLocalDialog() {
        var shown: LocalDate? = null
        var selected: LocalDate? = null
        show(onDay = { shown = it }, onSelect = { selected = it })
        compose.onAllNodesWithText("+2 more events").onFirst().performClick()
        assertEquals(date, shown)
        assertEquals(date, selected)
        compose.onNodeWithTag("calendar_day_events").assertDoesNotExist()
    }
    @Test fun dateHeaderSelectsAndOpensDayWithoutShiftingTheRange() {
        var shown: LocalDate? = null
        var requested: CalendarDateRange? = null
        show(onDay = { shown = it }, onRange = { requested = it })
        compose.onNodeWithContentDescription("View events for Sunday, October 25, 2026").performClick().assertIsSelected()
        assertEquals(date.plusDays(1), shown)
        assertNull(requested)
    }
    @Test fun oneGutterAndOneVerticalScrollServeAllColumns() {
        show()
        compose.onAllNodesWithText("09:00").assertCountEquals(1)
        compose.onNodeWithTag("multiday_time_gutter").assertExists()
        val top1 = compose.onNodeWithTag("multiday_grid_$date").fetchSemanticsNode().boundsInRoot.top
        val top2 = compose.onNodeWithTag("multiday_grid_${date.plusDays(1)}").fetchSemanticsNode().boundsInRoot.top
        assertEquals(top1, top2, 0.1f)
    }
    @Test fun zoomKeepsLogicalMinuteAndPublishesBoundedHeight() {
        var anchor = -1.0
        var zoom = -1f
        show(onAnchor = { anchor = it }, onZoom = { zoom = it })
        assertEquals(480.0, anchor, 1.0)
        compose.onNodeWithContentDescription("Zoom in").performClick()
        compose.waitForIdle()
        assertEquals(80f, zoom)
        assertEquals(480.0, anchor, 1.0)
        compose.onAllNodesWithText("09:00").assertCountEquals(1)
    }
    @Test fun nextRangeRetainsAnchorAndRequestsExactlyThreeDates() {
        var requested: CalendarDateRange? = null
        var anchor = -1.0
        show(onRange = { requested = it }, onAnchor = { anchor = it })
        compose.onNodeWithText("Later").performClick()
        assertEquals(CalendarDateRange(range.endExclusive, range.endExclusive.plusDays(3)), requested)
        assertEquals(480.0, anchor, 1.0)
    }
    @Test fun perColumnWeatherSlotReceivesNoExtraGutter() {
        val gutters = mutableMapOf<LocalDate, androidx.compose.ui.unit.Dp>()
        show(background = { day ->
            val gutter = LocalCalendarGutter.current
            SideEffect { gutters[day] = gutter }
        })
        assertEquals(multiDayDates(range).toSet(), gutters.keys)
        assertTrue(gutters.values.all { it == 0.dp })
    }
    @Test fun dstEventSegmentsKeepOneOccurrenceCallback() {
        var opened: EventRef? = null
        val expected = fixtures().last().ref
        show(minute = 30.0, onOpen = { opened = it })
        compose.onNodeWithContentDescription("Part 1 of 2", substring = true).performClick()
        assertEquals(expected, opened)
        compose.onNodeWithContentDescription("Part 2 of 2", substring = true).assertExists()
    }
    @Test fun arbitraryDatePickerIsAvailable() {
        show()
        compose.onNodeWithTag("multiday_date_picker").performClick()
        compose.onNodeWithText("Choose").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
    }
    @Test fun repeatedClockBandExplanationBelongsToItsDateHeader() {
        show()
        compose.onNode(hasContentDescription("Clock moves back", substring = true)).assertExists()
        compose.onAllNodes(hasContentDescription("Clock moves back", substring = true)).assertCountEquals(1)
    }
    @Test @Config(qualifiers = "+notnight") fun threeDayLight() {
        show()
        compose.onRoot().captureRoboImage("src/test/screenshots/multiday_three_light.png")
    }
    @Test @Config(qualifiers = "+night") fun weekDarkPansHeaderAndGridTogether() {
        show(window = CalendarDateRange(date, date.plusDays(7)))
        compose.onRoot().captureRoboImage("src/test/screenshots/multiday_week_dark.png")
        compose.onNodeWithTag("multiday_columns").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        val header = compose.onNodeWithTag("multiday_header_${date.plusDays(3)}").fetchSemanticsNode().boundsInRoot.left
        val grid = compose.onNodeWithTag("multiday_grid_${date.plusDays(3)}").fetchSemanticsNode().boundsInRoot.left
        assertEquals(header, grid, 0.1f)
    }
    @Test @Config(qualifiers = "+notnight") fun largeRtlHasReadableColumnsAndReachableOverflow() {
        show(fontScale = 2f, direction = LayoutDirection.Rtl)
        compose.onRoot().captureRoboImage("src/test/screenshots/multiday_large_rtl.png")
        compose.onAllNodesWithText("+2 more events").onFirst().assertIsDisplayed().performClick()
    }
}
