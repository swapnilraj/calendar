package dev.swapnilraj.calendar

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.swapnilraj.calendar.ui.calendars.CalendarMonthNavigator
import dev.swapnilraj.calendar.ui.calendars.CalendarPhoneToolbar
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
@Config(sdk = [35], qualifiers = "en-rUS-w393dp-h851dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CalendarPhoneNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val date = LocalDate.of(2028, 2, 29)
    private val month = YearMonth.from(date)

    private fun show(initialMonth: YearMonth = month, initialDate: LocalDate = date,
        weekday: DayOfWeek = DayOfWeek.MONDAY, fontScale: Float = 1f,
        direction: LayoutDirection = LayoutDirection.Ltr, search: (() -> Unit)? = null,
        menu: () -> Unit = {}, today: () -> Unit = {}, picker: () -> Unit = {},
        onMonth: (YearMonth) -> Unit = {}, onDate: (LocalDate) -> Unit = {}) {
        compose.setContent {
            var shownMonth by remember { mutableStateOf(initialMonth) }
            var selected by remember { mutableStateOf(initialDate) }
            var expanded by remember { mutableStateOf(true) }
            DaylightTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale),
                    LocalLayoutDirection provides direction) {
                    Surface(Modifier.fillMaxSize()) {
                        Column {
                            CalendarPhoneToolbar(selected, initialDate, expanded, menu, { expanded = !expanded }, today, search)
                            if (expanded) CalendarMonthNavigator(shownMonth, selected, initialDate, weekday,
                                onNavigateMonth = { shownMonth = it; onMonth(it) },
                                onSelectDate = { selected = it; onDate(it) }, onChooseDate = picker)
                        }
                    }
                }
            }
        }
    }

    private fun day(month: YearMonth, date: LocalDate) = compose.onNodeWithTag("calendar_month_date_${month}_$date")

    @Test fun toolbarCallbacksAndExpansionHaveRealActions() {
        var menus = 0; var searches = 0; var todays = 0
        show(menu = { menus++ }, search = { searches++ }, today = { todays++ })
        compose.onNodeWithContentDescription("Open navigation menu").performClick()
        compose.onNodeWithContentDescription("Search events").performClick()
        compose.onNodeWithTag("calendar_phone_today").performClick()
        assertEquals(1, menus); assertEquals(1, searches); assertEquals(1, todays)
        compose.onNodeWithTag("calendar_phone_month").performClick()
        compose.onNodeWithTag("calendar_month_navigator").assertDoesNotExist()
        compose.onNodeWithTag("calendar_phone_month").performClick()
        compose.onNodeWithTag("calendar_month_navigator").assertExists()
    }

    @Test fun unavailableSearchIsNotAnEnabledPlaceholder() {
        show()
        compose.onNodeWithContentDescription("Search events").assertDoesNotExist()
    }

    @Test fun leapFebruaryUsesSixCompleteWeeksAndSelectionCallbacks() {
        var chosen: LocalDate? = null
        show(onDate = { chosen = it })
        day(month, LocalDate.of(2028, 1, 31)).assertExists()
        day(month, LocalDate.of(2028, 3, 12)).assertExists()
        day(month, date).assertIsSelected()
        day(month, LocalDate.of(2028, 3, 1)).performClick()
        assertEquals(LocalDate.of(2028, 3, 1), chosen)
        day(month, date).assertIsNotSelected()
        day(month, LocalDate.of(2028, 3, 1)).assertIsSelected()
    }

    @Test fun monthPagingAndArrowNavigationNeverSelectDate() {
        val months = mutableListOf<YearMonth>()
        var selections = 0
        show(onMonth = { months += it }, onDate = { selections++ })
        compose.onNodeWithTag("calendar_month_pager").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(month.plusMonths(1), months.last())
        compose.onNodeWithContentDescription("Previous month").performClick()
        compose.waitForIdle()
        assertEquals(month, months.last())
        assertEquals(0, selections)
        day(month, date).assertIsSelected()
    }

    @Test fun calendarWeekStartAndYearBoundaryAreRealDates() {
        val january = YearMonth.of(2027, 1)
        show(initialMonth = january, initialDate = january.atDay(1), weekday = DayOfWeek.SUNDAY)
        val first = day(january, LocalDate.of(2026, 12, 27)).fetchSemanticsNode().boundsInRoot
        val following = day(january, LocalDate.of(2026, 12, 28)).fetchSemanticsNode().boundsInRoot
        assertTrue(first.left < following.left)
        assertEquals(first.top, following.top)
        day(january, LocalDate.of(2027, 2, 6)).assertExists()
    }

    @Test fun minimumSupportedYearDisablesPreviousMonthAndOutOfBoundsDates() {
        val january = YearMonth.of(1, 1)
        show(initialMonth = january, initialDate = january.atDay(1), weekday = DayOfWeek.SUNDAY)
        compose.onNodeWithContentDescription("Previous month").assertIsNotEnabled()
        day(january, LocalDate.of(0, 12, 31)).assertIsNotEnabled()
        day(january, january.atDay(1)).assertIsEnabled()
    }

    @Test fun maximumSupportedYearDisablesNextMonth() {
        val december = YearMonth.of(9999, 12)
        show(initialMonth = december, initialDate = december.atDay(31))
        compose.onNodeWithContentDescription("Next month").assertIsNotEnabled()
    }

    @Test fun accessibleDateYearAlternativeCallsTheHost() {
        var opened = 0
        show(picker = { opened++ })
        compose.onNodeWithText("Choose another date or year").performClick()
        assertEquals(1, opened)
    }

    @Test fun largeRtlMaintainsPhysicalDateOrderAndReachableActions() {
        var opened = 0
        show(fontScale = 2f, direction = LayoutDirection.Rtl, picker = { opened++ })
        val monday = day(month, LocalDate.of(2028, 1, 31)).fetchSemanticsNode().boundsInRoot
        val tuesday = day(month, LocalDate.of(2028, 2, 1)).fetchSemanticsNode().boundsInRoot
        assertTrue(monday.left > tuesday.left)
        assertTrue(monday.height >= 48f)
        compose.onNodeWithText("Choose another date or year").performClick()
        assertEquals(1, opened)
        compose.onNodeWithTag("calendar_phone_today").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "+notnight") fun phoneNavigationLight() {
        show(search = {})
        compose.onRoot().captureRoboImage("src/test/screenshots/reference_phone_navigation_light.png")
    }

    @Test @Config(qualifiers = "+night") fun phoneNavigationDark() {
        show(search = {})
        compose.onRoot().captureRoboImage("src/test/screenshots/reference_phone_navigation_dark.png")
    }

    @Test @Config(qualifiers = "+notnight") fun phoneNavigationLargeRtl() {
        show(search = {}, fontScale = 2f, direction = LayoutDirection.Rtl)
        compose.onRoot().captureRoboImage("src/test/screenshots/reference_phone_navigation_large_rtl.png")
    }
}
