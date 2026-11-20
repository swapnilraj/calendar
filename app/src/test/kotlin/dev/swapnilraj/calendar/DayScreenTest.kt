package dev.swapnilraj.calendar

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import dev.swapnilraj.calendar.data.*
import dev.swapnilraj.calendar.ui.*
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
import java.time.*
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsActions
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DayScreenTest {
    @get:Rule val compose = createComposeRule()
    private val now=ZonedDateTime.of(2026,11,17,10,20,0,0,ZoneId.of("Europe/London"))
    private fun event(id: Long, start: Int,end: Int,title: String,color: Long,declined: Boolean=false) =
        CalEvent(id,now.toLocalDate().atStartOfDay(now.zone).toInstant().plusSeconds(start*60L),
            now.toLocalDate().atStartOfDay(now.zone).toInstant().plusSeconds(end*60L),title,color.toInt(),declined=declined)
    private fun fixtures(allDay: Boolean): List<CalEvent> {
        val events=listOf(event(1,9*60,10*60,"Design review",0xff397d70),event(2,9*60+30,11*60,"Planning",0xff526e9e),
            event(3,12*60,13*60,"Lunch with Maya",0xffb06a47),event(4,14*60,14*60+30,"Project catch-up",0xff8a638a,true),
            event(5,15*60,15*60,"Quick reminder",0xff746944))
        return if (!allDay) events else events + (1..5).map { i ->
            CalEvent(20L+i,now.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant(),now.toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                listOf("Studio day","Maya’s birthday","Release window","Team away","Reading day")[i-1],0xff727b59.toInt(),allDay=true)
        }
    }
    private fun show(allDay: Boolean=false, fontScale: Float=1f, direction: LayoutDirection=LayoutDirection.Ltr, keyboardMode: Boolean=false, onClick: (CalEvent)->Unit={}, onSave: (Float)->Unit={}) {
        val events=fixtures(allDay)
        compose.setContent {
            DaylightTheme {
              CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale), LocalLayoutDirection provides direction,
                  LocalInputModeManager provides if (keyboardMode) object : InputModeManager {
                      override val inputMode = InputMode.Keyboard
                      override fun requestInputMode(inputMode: InputMode) = true
                  } else LocalInputModeManager.current) {
                DayScreen(now,true,64f,{ date,zone -> flowOf(CalendarState.Ready(events.filter { it.occursOn(date,zone) })) },onSave,onClick,
                    initialScrollHour=8.0)
              }
            }
        }
        compose.waitForIdle()
    }
    @Test @Config(qualifiers="+notnight") fun overlapsLight() { show();compose.onRoot().captureRoboImage("src/test/screenshots/overlaps_light.png") }
    @Test fun densityChangePreservesLogicalWallMinuteRatherThanPixelOffset() {
        val density = androidx.compose.runtime.mutableFloatStateOf(1f)
        var minute = -1.0
        compose.setContent { DaylightTheme {
            CompositionLocalProvider(LocalDensity provides Density(density.floatValue, 1f)) {
                DayScreen(now, true, 64f, { _, _ -> flowOf(CalendarState.Ready(emptyList())) }, {}, {},
                    initialScrollHour = 9.5, onScrollMinuteChanged = { minute = it })
            }
        } }
        compose.waitUntil(10_000) { kotlin.math.abs(minute - 570.0) < 0.1 }
        compose.runOnIdle { density.floatValue = 2f }
        compose.waitForIdle()
        assertEquals(570.0, minute, 0.1)
    }
    @Test @Config(qualifiers="+night") fun overlapsDark() { show();compose.onRoot().captureRoboImage("src/test/screenshots/overlaps_dark.png") }
    @Test @Config(qualifiers="+notnight") fun allDayTodayLight() { show(true);compose.onRoot().captureRoboImage("src/test/screenshots/all_day_today_light.png") }
    @Test @Config(qualifiers="+night") fun allDayTodayDark() { show(true);compose.onRoot().captureRoboImage("src/test/screenshots/all_day_today_dark.png") }
    @Test @Config(qualifiers="+notnight") fun permissionLight() {
        compose.setContent { DaylightTheme { androidx.compose.material3.Surface { PermissionGate {} } } }
        compose.onRoot().captureRoboImage("src/test/screenshots/permission_light.png")
    }
    @Test @Config(qualifiers="+night") fun permissionDark() {
        compose.setContent { DaylightTheme { androidx.compose.material3.Surface { PermissionGate {} } } }
        compose.onRoot().captureRoboImage("src/test/screenshots/permission_dark.png")
    }
    @Test fun overflowExposesAndOpensEveryAllDayEvent() {
        var selected: CalEvent? = null
        show(allDay = true, onClick = { selected = it })
        compose.onNodeWithText("+2 more events").performClick()
        compose.onNode(isRoot() and hasAnyDescendant(hasTestTag("calendar_day_events"))).captureRoboImage("src/test/screenshots/calendar_day_events_sheet.png")
        compose.onNodeWithText("Reading day").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(25L, selected?.id)
    }
    @Test fun dateActionExposesTimedEventsAsReadableRows() {
        show(keyboardMode = true)
        compose.onNodeWithContentDescription("View events for Tuesday, November 17, 2026")
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithContentDescription("View events for Tuesday, November 17, 2026").performClick()
        compose.onNode(hasText("Quick reminder") and hasAnyAncestor(hasTestTag("calendar_day_events"))).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Close").assertDoesNotExist()
        compose.onNodeWithContentDescription("View events for Tuesday, November 17, 2026").assertIsFocused()
    }
    @Test @Config(qualifiers="+notnight") fun largeFontRemainsReadable() {
        show(allDay = true, fontScale = 2f)
        compose.onRoot().captureRoboImage("src/test/screenshots/calendar_large_font.png")
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Quick reminder").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        assertTrue("Short-event text must fit: ${layouts.map { it.size to it.didOverflowHeight }}", layouts.none { it.didOverflowHeight })
        val hours = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("10:00").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(hours) }
        assertTrue(hours.isNotEmpty() && hours.none { it.didOverflowHeight || it.didOverflowWidth })
        val dates = mutableListOf<TextLayoutResult>()
        compose.onNodeWithContentDescription("View events for Tuesday, November 17, 2026")
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(dates) }
        assertTrue("Weekday must fit", dates.isNotEmpty() && dates.none { it.didOverflowHeight || it.didOverflowWidth })
        val numbers = mutableListOf<TextLayoutResult>()
        compose.onNode(hasText("17") and hasAnyAncestor(hasContentDescription("View events for Tuesday, November 17, 2026")), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(numbers) }
        assertTrue("Date number must fit", numbers.isNotEmpty() && numbers.none { it.didOverflowHeight || it.didOverflowWidth })
    }
    @Test @Config(qualifiers="+notnight") fun rtlGridRemainsAligned() {
        show(direction = LayoutDirection.Rtl)
        compose.onRoot().captureRoboImage("src/test/screenshots/calendar_rtl.png")
        val event = compose.onNodeWithText("Design review").fetchSemanticsNode().boundsInRoot
        val nowLine = compose.onNodeWithContentDescription("Current time 10:20").fetchSemanticsNode().boundsInRoot
        assertTrue(event.left >= nowLine.left)
        assertTrue(event.right < nowLine.right)
        val hourLabel = compose.onNodeWithText("10:00").fetchSemanticsNode().boundsInRoot
        assertTrue("RTL event must stay outside the right gutter", event.right <= hourLabel.left)
    }
    @Test fun pagesOneDayAndTodayReturnsAndEventClickKeepsIdentity() {
        var selected: CalEvent?=null
        show(onClick={selected=it})
        compose.onNodeWithText("Design review").performClick()
        assertEquals(1L,selected?.id)
        compose.onNodeWithContentDescription("Next day",useUnmergedTree=true).performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Day 2026-11-18").assertIsDisplayed()
        compose.onNodeWithContentDescription("Go to today").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Design review").assertIsDisplayed()
        compose.onNodeWithContentDescription("Day 2026-11-17").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Day 2026-11-18").assertIsDisplayed()
    }
    @Test fun pinchPersistsFinalHeightWithinBoundsAndPagingKeepsScroll() {
        var saved=64f
        show(onSave={saved=it})
        compose.onNodeWithContentDescription("Day 2026-11-17").performTouchInput {
            down(0,Offset(centerX,300f));down(1,Offset(centerX,500f))
            moveTo(0,Offset(centerX,200f));moveTo(1,Offset(centerX,600f))
            up(0);up(1)
        }
        compose.waitForIdle()
        assertTrue("Pinch must enlarge and persist hours: $saved",saved>64f && saved<=160f)
        // The current time line makes a stable reference for the shared scroll position.
        val before=compose.onNodeWithContentDescription("Current time 10:20").fetchSemanticsNode().positionInRoot.y
        compose.onNodeWithContentDescription("Next day",useUnmergedTree=true).performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Go to today").performClick()
        compose.waitForIdle()
        val after=compose.onNodeWithContentDescription("Current time 10:20").fetchSemanticsNode().positionInRoot.y
        assertEquals(before,after,2f)
    }
}
