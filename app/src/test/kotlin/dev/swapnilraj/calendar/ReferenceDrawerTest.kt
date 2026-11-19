package dev.swapnilraj.calendar

import android.provider.CalendarContract
import androidx.compose.foundation.layout.fillMaxSize
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
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.calendars.*
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
import java.time.ZoneOffset
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
class ReferenceDrawerTest {
    @get:Rule val compose = createComposeRule()
    private val account = CalendarAccount("owner@example.test", "com.google")
    private val calendar = CalendarInfo(CalendarId(8), "Personal", 0xff397d70.toInt(), account,
        account.name, ZoneOffset.UTC, CalendarContract.Calendars.CAL_ACCESS_OWNER, true, true,
        CalendarCapabilities(AccessDecision.ALLOWED, AccessDecision.ALLOWED, AccessDecision.ALLOWED, AccessDecision.ALLOWED))
    private val order = listOf(CalendarViewOption.AGENDA, CalendarViewOption.DAY, CalendarViewOption.THREE_DAY,
        CalendarViewOption.WEEK, CalendarViewOption.MONTH)

    private fun show(available: List<CalendarViewOption> = CalendarViewOption.entries.reversed(),
        wired: Boolean = true, selected: CalendarViewOption = CalendarViewOption.WEEK,
        fontScale: Float = 1f, direction: LayoutDirection = LayoutDirection.Ltr,
        onView: (CalendarViewOption) -> Unit = {}, onFilter: (CalendarSelection) -> Unit = {},
        onDefault: (CalendarId?) -> Unit = {}) {
        compose.setContent {
            var view by remember { mutableStateOf(selected) }
            DaylightTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale),
                    LocalLayoutDirection provides direction) {
                    Surface(Modifier.fillMaxSize()) {
                        CalendarDrawerSheet(listOf(calendar), CalendarSelection.Explicit(emptySet()), calendar.id,
                            onFilter, onDefault, {}, currentView = view,
                            onViewSelected = if (wired) ({ value -> view = value; onView(value) }) else null,
                            availableViews = available)
                    }
                }
            }
        }
    }

    private fun row(view: CalendarViewOption) = compose.onNodeWithTag("calendar_drawer_view_${view.name}")
    private fun reveal(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("calendar_drawer_list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }

    @Test fun viewsAreOrderedAboveAccountsRegardlessOfInputOrderAndDuplicates() {
        show(available = CalendarViewOption.entries.reversed() + CalendarViewOption.DAY)
        val tops = order.map { row(it).fetchSemanticsNode().boundsInRoot.top }
        assertTrue(tops.zipWithNext().all { (first, next) -> first < next })
        compose.onNodeWithText("Schedule").assertExists()
        compose.onNodeWithText("Agenda").assertDoesNotExist()
        compose.onAllNodesWithTag("calendar_drawer_view_DAY").assertCountEquals(1)
        val accountTop = compose.onNodeWithTag("calendar_account_18:owner@example.test:com.google")
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue(accountTop > row(CalendarViewOption.MONTH).fetchSemanticsNode().boundsInRoot.bottom)
    }

    @Test fun viewChoiceUsesPersistedEnumAndDoesNotChangeCalendarChoices() {
        var chosen: CalendarViewOption? = null
        var filters = 0
        var defaults = 0
        show(onView = { chosen = it }, onFilter = { filters++ }, onDefault = { defaults++ })
        row(CalendarViewOption.WEEK).assertIsSelected()
        row(CalendarViewOption.AGENDA).performClick()
        assertEquals(CalendarViewOption.AGENDA, chosen)
        row(CalendarViewOption.AGENDA).assertIsSelected()
        row(CalendarViewOption.WEEK).assertIsNotSelected()
        assertEquals(0, filters)
        assertEquals(0, defaults)
        reveal("calendar_toggle_8").assertIsOff()
        reveal("calendar_default_8").assertIsSelected()
    }

    @Test fun unavailableViewsAndUnwiredChoicesAreAbsent() {
        show(available = listOf(CalendarViewOption.DAY, CalendarViewOption.MONTH))
        row(CalendarViewOption.DAY).assertExists()
        row(CalendarViewOption.MONTH).assertExists()
        row(CalendarViewOption.WEEK).assertDoesNotExist()
        row(CalendarViewOption.THREE_DAY).assertDoesNotExist()
        row(CalendarViewOption.AGENDA).assertDoesNotExist()
    }

    @Test fun absentCallbackKeepsOriginalDrawerWithoutDeadNavigation() {
        show(wired = false)
        compose.onNodeWithTag("calendar_drawer_views").assertDoesNotExist()
        compose.onNodeWithText("Select none").assertExists()
    }

    @Test fun emptyAvailableViewsKeepOriginalDrawer() {
        show(available = emptyList())
        compose.onNodeWithTag("calendar_drawer_views").assertDoesNotExist()
    }

    @Test fun largeRtlViewActionsAndCalendarDefaultsRemainReachable() {
        var chosen: CalendarViewOption? = null
        show(fontScale = 2f, direction = LayoutDirection.Rtl, onView = { chosen = it })
        order.forEach { view ->
            reveal("calendar_drawer_view_${view.name}").assertIsDisplayed()
            assertTrue(row(view).fetchSemanticsNode().boundsInRoot.height >= 48f)
        }
        reveal("calendar_drawer_view_MONTH").performClick()
        assertEquals(CalendarViewOption.MONTH, chosen)
        reveal("calendar_default_8").assertIsSelected().assertIsDisplayed()
    }

    @Test @Config(qualifiers = "+notnight") fun referenceDrawerLight() {
        show()
        compose.onRoot().captureRoboImage("src/test/screenshots/reference_drawer_light.png")
    }

    @Test @Config(qualifiers = "+night") fun referenceDrawerDark() {
        show(selected = CalendarViewOption.AGENDA)
        compose.onRoot().captureRoboImage("src/test/screenshots/reference_drawer_dark.png")
    }

    @Test @Config(qualifiers = "+notnight") fun referenceDrawerLargeRtl() {
        show(fontScale = 2f, direction = LayoutDirection.Rtl)
        compose.onRoot().captureRoboImage("src/test/screenshots/reference_drawer_large_rtl.png")
    }
}
