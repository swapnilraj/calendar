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
    private fun show(allDay: Boolean=false, onClick: (CalEvent)->Unit={}, onSave: (Float)->Unit={}) {
        val events=fixtures(allDay)
        compose.setContent {
            DaylightTheme {
                DayScreen(now,true,64f,{ date,zone -> flowOf(CalendarState.Ready(events.filter { it.occursOn(date,zone) })) },onSave,onClick,
                    initialScrollHour=8.0)
            }
        }
        compose.waitForIdle()
    }
    @Test @Config(qualifiers="+notnight") fun overlapsLight() { show();compose.onRoot().captureRoboImage("src/test/screenshots/overlaps_light.png") }
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
