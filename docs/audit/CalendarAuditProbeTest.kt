package dev.swapnilraj.calendar

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.swapnilraj.calendar.data.*
import dev.swapnilraj.calendar.ui.*
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
import dev.swapnilraj.calendar.weather.*
import java.time.*
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Documentary probes: a passing defect probe confirms the current defect, not correct behavior.
 * Run only through scripts/run-feature-audit-probes.sh. All events are synthetic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], qualifiers="en-rUS-w393dp-h851dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CalendarAuditProbeTest {
    @get:Rule val compose=createComposeRule()
    private val date=LocalDate.of(2026,11,17)
    private val zone=ZoneId.of("Europe/London")
    private fun timed(id: Long,start: String,end: String,title: String)=CalEvent(id,
        date.atTime(LocalTime.parse(start)).atZone(zone).toInstant(),
        date.atTime(LocalTime.parse(end)).atZone(zone).toInstant(),title,0xff397d70.toInt())
    private fun allDay(i: Long)=CalEvent(i,date.atStartOfDay(ZoneOffset.UTC).toInstant(),
        date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(),"Holiday $i",0xff526e9e.toInt(),allDay=true)

    private fun show(events: List<CalEvent>, direction: LayoutDirection=LayoutDirection.Ltr,
        fontScale: Float=1f, displayZone: ZoneId=zone) {
        val now=date.atTime(10,20).atZone(displayZone)
        compose.setContent {
            val density=LocalDensity.current.density
            CompositionLocalProvider(LocalLayoutDirection provides direction,
                LocalDensity provides Density(density,fontScale)) {
                DaylightTheme {
                    DayScreen(now,true,64f,{ d,z->flowOf(CalendarState.Ready(events.filter { it.occursOn(d,z) })) },{},{},
                        initialScrollHour=8.0,
                        background={ WeatherBackground(List(25) { DayHour(Condition.CLEAR,tempC=10.0) }) })
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun confirmsAllDayOverflowHasNoActionAndHidesEvents() {
        show((1L..5L).map(::allDay))
        val node=compose.onNodeWithText("+2 more").fetchSemanticsNode()
        assertNull(node.config.getOrNull(SemanticsActions.OnClick))
        compose.onNodeWithText("Holiday 4").assertDoesNotExist()
        compose.onNodeWithText("Holiday 5").assertDoesNotExist()
        println("AUDIT all_day_overflow: total=5 visible=3 overflowClickAction=false")
        compose.onRoot().captureRoboImage("build/audit/all_day_overflow.png")
    }

    @Test fun confirmsAllDayAnnouncementUsesConvertedClockTimes() {
        show(listOf(allDay(1)),displayZone=ZoneId.of("America/Los_Angeles"))
        compose.onNodeWithContentDescription("Holiday 1, 16:00 – 16:00").assertExists()
        println("AUDIT all_day_accessibility: UTC all-day date announced as Holiday 1, 16:00 – 16:00 in Los Angeles")
    }

    @Test fun confirmsFallBackDurationCollapses() {
        val event=CalEvent(1,Instant.parse("2026-10-25T00:45:00Z"),Instant.parse("2026-10-25T01:15:00Z"),
            "Synthetic clock-change meeting",0xff397d70.toInt())
        val segment=timedEvents(listOf(event),LocalDate.of(2026,10,25),zone).single()
        assertEquals(30L,Duration.between(event.start,event.end).toMinutes())
        assertEquals(105.0,segment.startMinute,0.0)
        assertEquals(0.0,segment.endMinute-segment.startMinute,0.0)
        println("AUDIT dst_fall_back: actualDurationMinutes=30 startMinute=105 renderedDurationBeforeMinimum=0")
    }

    @Test fun confirmsLargeFontOverflowsShortEventText() {
        show(listOf(timed(1,"09:00","09:00","Reminder"),timed(2,"10:00","11:00","Design review")),fontScale=2f)
        val layouts=mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Reminder").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout=layouts.single()
        println("AUDIT large_font: fontScale=2 textLayoutHeight=${layout.size.height} overflowHeight=${layout.didOverflowHeight}")
        assertTrue(layout.didOverflowHeight)
        compose.onRoot().captureRoboImage("build/audit/large_font.png")
    }

    @Test fun rendersRtlForGeometryInspection() {
        show(listOf(timed(1,"09:00","10:00","Design review")),direction=LayoutDirection.Rtl)
        val bounds=compose.onNodeWithText("Design review").fetchSemanticsNode().boundsInRoot
        println("AUDIT rtl: eventBounds=$bounds; grid vertical gutter is drawn at physical x=56")
        assertTrue(bounds.left < 56f)
        compose.onRoot().captureRoboImage("build/audit/rtl.png")
    }
}
