package dev.swapnilraj.calendar

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import dev.swapnilraj.calendar.data.CalEvent
import dev.swapnilraj.calendar.data.CalendarState
import dev.swapnilraj.calendar.ui.DayScreen
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
import java.time.*
import kotlinx.coroutines.flow.MutableStateFlow
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
class ReadLimitUiTest {
    @get:Rule val compose = createComposeRule()
    private val zone = ZoneId.of("Europe/London")
    private val date = LocalDate.of(2026, 11, 17)
    @Test @Config(qualifiers = "+notnight") fun dayLimitReplacesOldEventsAndEmptyGridWithAnExplicitPanel() {
        var calendars = 0
        var retries = 0
        val start = date.atTime(9, 0).atZone(zone).toInstant()
        val state = MutableStateFlow<CalendarState>(CalendarState.Ready(listOf(CalEvent(1, start, start.plusSeconds(3600), "Private event payload", 0xff526e9e.toInt()))))
        compose.setContent { DaylightTheme {
            DayScreen(date.atTime(10, 0).atZone(zone), true, 64f, { _, _ -> state }, {}, {}, initialScrollHour = 8.0,
                onRetry = { retries++ }, onChooseCalendars = { calendars++ })
        } }
        compose.onNodeWithText("Private event payload").assertIsDisplayed()
        state.value = CalendarState.LimitExceeded
        compose.waitForIdle()
        compose.onNodeWithText("Private event payload").assertDoesNotExist()
        compose.onNodeWithText("09:00").assertDoesNotExist()
        val day = hasAnyAncestor(hasContentDescription("Day $date"))
        compose.onNode(hasText("Choose calendars") and day).performClick()
        compose.onNode(hasText("Retry") and day).performClick()
        assertEquals(1, calendars)
        assertEquals(1, retries)
        compose.onRoot().captureRoboImage("src/test/screenshots/read_limit_day_light.png")
    }
}
