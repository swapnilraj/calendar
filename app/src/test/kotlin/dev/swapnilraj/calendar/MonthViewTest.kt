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
import dev.swapnilraj.calendar.ui.month.*
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
class MonthViewTest {
    @get:Rule val compose = createComposeRule()
    private val zone = ZoneId.of("America/Los_Angeles")
    private val date = LocalDate.of(2026, 11, 17)
    private val month = YearMonth.from(date)
    private val recurring = EventRef(CalendarId(2), 7, Instant.parse("2026-11-17T19:00:00Z"), Instant.parse("2026-11-17T20:00:00Z"),
        RecurrenceRef(6, Instant.parse("2026-11-17T17:00:00Z")))
    private fun fixtures(): List<EventOccurrence> {
        val tripStart = Instant.parse("2026-11-16T00:00:00Z")
        val tripEnd = Instant.parse("2026-11-19T00:00:00Z")
        val trip = EventOccurrence(EventRef(CalendarId(1), 1, tripStart, tripEnd),
            EventTime.AllDay(CalendarDateRange(date.minusDays(1), date.plusDays(2))), "Work trip", 0xff397d70.toInt())
        val appointments = (2..5).map { id ->
            val start = date.atTime(8 + id, 0).atZone(zone).toInstant()
            EventOccurrence(EventRef(CalendarId(1), id.toLong(), start, start.plusSeconds(3600)),
                EventTime.Timed(start, start.plusSeconds(3600)), "Appointment $id", 0xff526e9e.toInt(), declined = id == 5)
        }
        return listOf(trip) + appointments + EventOccurrence(recurring,
            EventTime.Timed(recurring.instanceStart, recurring.instanceEnd), "Recurring standup", 0xff8a638a.toInt())
    }
    private fun show(displayMonth: YearMonth = month, selection: LocalDate = date, first: DayOfWeek = DayOfWeek.MONDAY,
        fontScale: Float = 1f, direction: LayoutDirection = LayoutDirection.Ltr,
        onSelect: (LocalDate) -> Unit = {}, onOpen: (EventRef) -> Unit = {},
        onCreate: (LocalDate) -> Unit = {}, onNavigate: (YearMonth) -> Unit = {}) {
        compose.setContent {
            DaylightTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale), LocalLayoutDirection provides direction) {
                    MonthView(displayMonth, selection, first, fixtures(), zone, onSelect, onOpen, onCreate, onNavigate,
                        today = date.plusDays(1))
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun leapFebruaryContainsTheLeapDateAndExactlySixCompleteWeeks() {
        val dates = monthDates(YearMonth.of(2028, 2), DayOfWeek.MONDAY)
        assertEquals(42, dates.size)
        assertEquals(LocalDate.of(2028, 1, 31), dates.first())
        assertEquals(LocalDate.of(2028, 3, 12), dates.last())
        assertTrue(dates.contains(LocalDate.of(2028, 2, 29)))
        assertTrue(dates.zipWithNext().all { (a, b) -> a.plusDays(1) == b })
    }
    @Test fun sixRowMonthAndYearBoundaryKeepAdjacentMonthDates() {
        val may = monthDates(YearMonth.of(2021, 5), DayOfWeek.MONDAY)
        assertEquals(LocalDate.of(2021, 4, 26), may.first())
        assertEquals(LocalDate.of(2021, 6, 6), may.last())
        val december = monthDates(YearMonth.of(2026, 12), DayOfWeek.MONDAY)
        assertEquals(LocalDate.of(2026, 11, 30), december.first())
        assertEquals(LocalDate.of(2027, 1, 10), december.last())
    }
    @Test fun firstWeekdayChangesTheMatrixRatherThanOnlyTheHeading() {
        assertEquals(LocalDate.of(2026, 11, 1), monthDates(month, DayOfWeek.SUNDAY).first())
        assertEquals(LocalDate.of(2026, 10, 26), monthDates(month, DayOfWeek.MONDAY).first())
        show(first = DayOfWeek.SUNDAY)
        compose.onNodeWithContentDescription("Sunday").assertExists()
        compose.onNodeWithTag("month_date_2026-11-01").assertExists()
        compose.onNodeWithTag("month_date_2026-12-12").assertExists()
    }
    @Test fun allDayDatesAreZoneIndependentAndEndExclusive() {
        val dates = monthDates(month, DayOfWeek.MONDAY)
        for (displayZone in listOf(zone, ZoneId.of("Pacific/Kiritimati"))) {
            val grouped = monthEventsByDate(dates, fixtures(), displayZone)
            assertTrue(grouped[date.minusDays(2)].orEmpty().none { it.title == "Work trip" })
            for (day in listOf(date.minusDays(1), date, date.plusDays(1))) assertTrue(grouped[day].orEmpty().any { it.title == "Work trip" })
            assertTrue(grouped[date.plusDays(2)].orEmpty().none { it.title == "Work trip" })
        }
    }
    @Test fun multiDayContinuationChangesAcrossTheStartMiddleAndEnd() {
        val trip = fixtures().first()
        assertEquals(MonthContinuation(false, true), continuation(trip, date.minusDays(1), zone))
        assertEquals(MonthContinuation(true, true), continuation(trip, date, zone))
        assertEquals(MonthContinuation(true, false), continuation(trip, date.plusDays(1), zone))
    }
    @Test fun timedMidnightEndDoesNotLeakIntoTheNextDate() {
        val start = date.atTime(23, 0).atZone(zone).toInstant()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant()
        val event = EventOccurrence(EventRef(CalendarId(1), 42, start, end), EventTime.Timed(start, end), "Late appointment", 0)
        val grouped = monthEventsByDate(monthDates(month, DayOfWeek.MONDAY), listOf(event), zone)
        assertEquals(listOf(event), grouped[date])
        assertTrue(grouped[date.plusDays(1)].orEmpty().isEmpty())
        assertEquals(MonthContinuation(false, false), continuation(event, date, zone))
    }
    @Test fun crowdedDateOverflowOpensEveryEventAndPreservesRecurringIdentity() {
        var selected: LocalDate? = null
        var opened: EventRef? = null
        show(onSelect = { selected = it }, onOpen = { opened = it })
        compose.onNode(hasContentDescription("Show 4 more events") and hasAnyAncestor(hasTestTag("month_date_$date")))
            .performScrollTo().performClick()
        assertEquals(date, selected)
        compose.onNode(hasText("Recurring standup") and hasAnyAncestor(hasTestTag("month_day_events")))
            .performScrollTo().assertIsDisplayed().performClick()
        assertEquals(recurring, opened)
        assertEquals(Instant.parse("2026-11-17T17:00:00Z"), opened?.recurrence?.originalStart)
    }
    @Test fun dateSheetOffersExplicitCreationForTheSelectedDate() {
        var selected: LocalDate? = null
        var created: LocalDate? = null
        val empty = date.plusDays(3)
        show(onSelect = { selected = it }, onCreate = { created = it })
        compose.onNodeWithTag("month_date_$empty").performScrollTo().performClick()
        assertEquals(empty, selected)
        compose.onNodeWithText("No events").assertIsDisplayed()
        compose.onNodeWithText("Create all-day event on Friday, November 20, 2026").performClick()
        assertEquals(empty, created)
    }
    @Test fun nextMonthCrossesTheYearBoundaryAndTodayKeepsItsDate() {
        var navigated: YearMonth? = null
        var selected: LocalDate? = null
        show(displayMonth = YearMonth.of(2026, 12), onNavigate = { navigated = it }, onSelect = { selected = it })
        compose.onNodeWithContentDescription("Next month").performClick()
        assertEquals(YearMonth.of(2027, 1), navigated)
        compose.onNodeWithText("Today").performClick()
        assertEquals(date.plusDays(1), selected)
        assertEquals(month, navigated)
    }
    @Test fun datePickerCanJumpToAnArbitraryLeapDayWithoutTimezoneConversion() {
        var navigated: YearMonth? = null
        var selected: LocalDate? = null
        show(selection = LocalDate.of(2028, 2, 29), onNavigate = { navigated = it }, onSelect = { selected = it })
        compose.onNodeWithContentDescription("November 2026, Choose date").performClick()
        compose.onNodeWithText("Go to date").performClick()
        assertEquals(LocalDate.of(2028, 2, 29), selected)
        assertEquals(YearMonth.of(2028, 2), navigated)
    }
    @Test @Config(qualifiers = "w840dp-h900dp-mdpi") fun wideMonthShowsTheCompleteGrid() {
        show()
        compose.onNodeWithTag("month_date_2026-10-26").assertIsDisplayed()
        compose.onNodeWithTag("month_date_2026-12-06").assertIsDisplayed()
    }
    @Test @Config(qualifiers = "w320dp-h640dp-mdpi") fun narrowMonthKeepsItsLastDateReachable() {
        show()
        compose.onNodeWithTag("month_horizontal_scroll").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(10000f, 0f) }
        compose.onNodeWithTag("month_date_2026-12-06").performScrollTo().assertIsDisplayed()
    }
    @Test @Config(qualifiers = "+notnight") fun monthLight() {
        show()
        compose.onNodeWithTag("month_date_$date").assertIsSelected()
        compose.onNodeWithTag("month_date_${date.plusDays(1)}").assert(hasContentDescription("Today", substring = true))
        compose.onRoot().captureRoboImage("src/test/screenshots/month_light.png")
    }
    @Test @Config(qualifiers = "+night") fun monthDark() {
        show()
        compose.onRoot().captureRoboImage("src/test/screenshots/month_dark.png")
    }
    @Test @Config(qualifiers = "+notnight") fun largeFontRtlCanScrollToEveryDate() {
        show(fontScale = 2f, direction = LayoutDirection.Rtl)
        compose.onNodeWithTag("month_date_$date").performScrollTo().assertIsDisplayed()
        compose.onRoot().captureRoboImage("src/test/screenshots/month_large_rtl.png")
        compose.onNodeWithTag("month_horizontal_scroll").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(10000f, 0f) }
        compose.onNodeWithTag("month_date_2026-12-06").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("month_horizontal_scroll").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(-10000f, 0f) }
        compose.onNodeWithTag("month_date_2026-10-26").performScrollTo().assertIsDisplayed()
    }
}
