package dev.swapnilraj.calendar

import androidx.compose.runtime.SideEffect
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.swapnilraj.calendar.core.CalendarSelection
import dev.swapnilraj.calendar.data.*
import dev.swapnilraj.calendar.ui.*
import dev.swapnilraj.calendar.ui.settings.*
import dev.swapnilraj.calendar.ui.theme.LocalDaylightDarkTheme
import dev.swapnilraj.calendar.weather.*
import java.time.*
import kotlinx.coroutines.flow.flowOf
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DisplayThemeTest {
    @get:Rule val compose = createComposeRule()
    private fun show(theme: SettingsTheme, expectedDark: Boolean, filename: String) {
        val now = ZonedDateTime.parse("2026-11-17T10:00:00Z[Europe/London]")
        val hours = (0..24).map { ForecastHour(now.toLocalDate().atStartOfDay(now.zone).toInstant().plusSeconds(it * 3600L), 63, 10.0, 80.0, 2.0, it in 8..16) }
        val preferences = CalendarPreferenceState.Ready(CalendarPreferences(CalendarSelection.ProviderVisible, null, "DAY", 64f,
            CalendarSettingsOptions(theme = theme)))
        var dark: Boolean? = null
        var surface: Float? = null
        compose.setContent {
            CalendarPreferenceTheme(preferences) {
                val appliedDark = LocalDaylightDarkTheme.current
                val appliedSurface = MaterialTheme.colorScheme.surface.luminance()
                SideEffect { dark = appliedDark; surface = appliedSurface }
                DayScreen(now, true, 64f, { _, _ -> flowOf(CalendarState.Ready(emptyList())) }, {}, {}, initialScrollHour = 9.0,
                    summary = { WeatherSummary(daySummary(hours, now.toLocalDate(), now.zone)!!) },
                    background = { WeatherBackground(hoursForDay(hours, now.toLocalDate(), now.zone)!!) })
            }
        }
        compose.waitForIdle()
        assertEquals(expectedDark, dark)
        assertEquals(expectedDark, surface!! < 0.5f)
        compose.onRoot().captureRoboImage("src/test/screenshots/$filename.png")
    }
    @Test @Config(qualifiers = "+notnight") fun savedDarkThemeOverridesLightSystemForGridAndWeather() =
        show(SettingsTheme.DARK, true, "integration_forced_dark_weather")
    @Test @Config(qualifiers = "+night") fun savedLightThemeOverridesDarkSystemForGridAndWeather() =
        show(SettingsTheme.LIGHT, false, "integration_forced_light_weather")
}
