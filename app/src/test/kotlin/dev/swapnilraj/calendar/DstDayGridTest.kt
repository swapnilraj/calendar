package dev.swapnilraj.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.swapnilraj.calendar.data.CalEvent
import dev.swapnilraj.calendar.ui.DayGrid
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h400dp-notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DstDayGridTest {
    @get:Rule val compose = createComposeRule()
    private val zone = ZoneId.of("Europe/London")
    private fun event(start: String, end: String) =
        CalEvent(7, Instant.parse(start), Instant.parse(end), "Night meeting", 0xff397d70.toInt())

    private fun show(date: String, events: List<CalEvent>, rtl: Boolean = false, fontScale: Float = 1f, onClick: (CalEvent) -> Unit = {}) {
        val day = LocalDate.parse(date)
        compose.setContent {
            DaylightTheme {
                CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                    LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    Surface(Modifier.height(400.dp)) {
                        DayGrid(day, events, 96f, day.atTime(1, 50).atZone(zone), zone, true, onClick,
                            background = { Box(Modifier.fillMaxSize().background(Color.Magenta)) })
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun fallBackPartsAnnounceActualDurationOffsetsAndOriginalEventClick() {
        val event = event("2026-10-25T00:45:00Z", "2026-10-25T01:15:00Z")
        var clicked: CalEvent? = null
        show("2026-10-25", listOf(event), onClick = { clicked = it })
        compose.onAllNodesWithContentDescription("Duration: 30 minutes", substring = true).assertCountEquals(2)
        compose.onNodeWithContentDescription("Part 1 of 2", substring = true).assertIsDisplayed().performClick()
        assertSame(event, clicked)
        compose.onNodeWithContentDescription("Part 2 of 2", substring = true).assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Duration: 0", substring = true).assertCountEquals(0)
        compose.onAllNodesWithContentDescription("UTC+01:00", substring = true).assertCountEquals(3)
    }

    @Test fun fallBackSnapshot() {
        val event = event("2026-10-25T00:15:00Z", "2026-10-25T01:45:00Z")
        var clicked: CalEvent? = null
        show("2026-10-25", listOf(event), onClick = { clicked = it })
        compose.onAllNodesWithContentDescription("Duration: 1 hour, 30 minutes", substring = true).assertCountEquals(2)
        compose.onNodeWithContentDescription("Part 1 of 2", substring = true).assertIsDisplayed().performClick()
        assertSame(event, clicked)
        compose.onNodeWithContentDescription("Part 2 of 2", substring = true).assertIsDisplayed()
        compose.onRoot().captureRoboImage("src/test/screenshots/dst_fallback.png")
    }

    @Test fun springForwardSnapshotAndSemantics() {
        show("2026-03-29", listOf(event("2026-03-29T00:30:00Z", "2026-03-29T01:30:00Z")))
        compose.onAllNodesWithContentDescription("Duration: 1 hour", substring = true).assertCountEquals(2)
        compose.onNodeWithContentDescription("Clock moves forward", substring = true).assertIsDisplayed()
        compose.onRoot().captureRoboImage("src/test/screenshots/dst_spring.png")
    }

    @Test fun rtlClockBandAndOffsetSemantics() {
        show("2026-10-25", listOf(event("2026-10-25T00:45:00Z", "2026-10-25T01:15:00Z")), rtl = true)
        compose.onAllNodesWithContentDescription("Duration: 30 minutes", substring = true).assertCountEquals(2)
        compose.onNodeWithContentDescription("Clock moves back", substring = true).assertIsDisplayed()
        compose.onRoot().captureRoboImage("src/test/screenshots/dst_fallback_rtl.png")
    }

    @Test fun ambiguousWeatherIsMaskedOnlyInsideTheEventAreaBand() {
        show("2026-10-25", emptyList())
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        // Background fixture is vivid magenta. The overlap band is 96..192; avoid text/grid edges.
        assertNotEquals(android.graphics.Color.MAGENTA, bitmap.getPixel(380, 180))
        assertEquals(android.graphics.Color.MAGENTA, bitmap.getPixel(380, 220))
        assertEquals(android.graphics.Color.MAGENTA, bitmap.getPixel(20, 180))
    }

    @Test fun currentTimeLineCannotPaintAcrossOpaqueEventContent() {
        val event = event("2026-11-17T01:00:00Z", "2026-11-17T02:00:00Z")
        show("2026-11-17", listOf(event))
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        assertEquals(event.color, bitmap.getPixel(100, 176))
        compose.onRoot().captureRoboImage("src/test/screenshots/dst_now_below_event.png")
    }

    @Test fun largeFontOffsetsAndTitleHaveNoVerticalOverflow() {
        val event = event("2026-10-25T00:15:00Z", "2026-10-25T01:45:00Z")
            .copy(title = "A very long calendar meeting title needing two lines")
        show("2026-10-25", listOf(event), fontScale = 2f)
        val nodes = compose.onAllNodesWithContentDescription("Duration: 1 hour, 30 minutes", substring = true)
        nodes.assertCountEquals(2)
        compose.onRoot().captureRoboImage("src/test/screenshots/dst_fallback_large_font.png")
        for (index in 0..1) {
            val layouts = mutableListOf<TextLayoutResult>()
            nodes[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.isNotEmpty())
            // A long title intentionally ellipsizes horizontally. didOverflowHeight also flags
            // maxLines truncation, so compare actual line bounds to the allocated height instead.
            assertTrue("Scaled fragment lines must not be vertically clipped", layouts.all {
                it.lineCount > 0 && it.getLineBottom(it.lineCount - 1) <= it.size.height + 0.5f
            })
        }
        val offsetRows = compose.onAllNodesWithText("UTC+", substring = true, useUnmergedTree = true)
        offsetRows.assertCountEquals(2)
        for (index in 0..1) {
            val layouts = mutableListOf<TextLayoutResult>()
            offsetRows[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.isNotEmpty())
            assertTrue("Offset line must not be vertically clipped", layouts.all {
                it.lineCount > 0 && it.getLineBottom(it.lineCount - 1) <= it.size.height + 0.5f
            })
        }
    }
}
