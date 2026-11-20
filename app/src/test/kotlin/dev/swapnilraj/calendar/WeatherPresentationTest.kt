package dev.swapnilraj.calendar

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.swapnilraj.calendar.ui.*
import dev.swapnilraj.calendar.ui.settings.WeatherUnits
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
import dev.swapnilraj.calendar.weather.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h400dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WeatherPresentationTest {
    @get:Rule val compose = createComposeRule()
    private val sample = DayHour(Condition.RAIN, 80.0, 1.9, false, 11.0)
    private fun show(hour: DayHour = sample, units: WeatherUnits = WeatherUnits.CELSIUS, summary: DaySummary? = null,
        stale: Boolean = false, font: Float = 1f, rtl: Boolean = false, precipitationHour: DayHour = hour) {
        compose.setContent {
            DaylightTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, font),
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    Surface(Modifier.fillMaxSize()) {
                        Column {
                            if (summary != null) Row { WeatherSummary(summary, stale, units) }
                            WeatherReadout(hour, units, precipitationHour = precipitationHour)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }
    @Test fun fahrenheitConversionUsesOriginalCelsiusOnceAndRoundsOnlyAtDisplay() {
        assertEquals(32, displayedWeatherTemperature(0.0, WeatherUnits.FAHRENHEIT))
        assertEquals(212, displayedWeatherTemperature(100.0, WeatherUnits.FAHRENHEIT))
        assertEquals(-40, displayedWeatherTemperature(-40.0, WeatherUnits.FAHRENHEIT))
        assertEquals(52, displayedWeatherTemperature(11.0, WeatherUnits.FAHRENHEIT))
        assertEquals(53, displayedWeatherTemperature(11.9, WeatherUnits.FAHRENHEIT))
        assertEquals(27, displayedWeatherTemperature(-2.9, WeatherUnits.FAHRENHEIT))
        assertEquals(-4, displayedWeatherTemperature(-20.0, WeatherUnits.FAHRENHEIT))
    }
    @Test fun celsiusKeepsLegacyWholeNumberPresentation() {
        assertEquals(11, displayedWeatherTemperature(11.9, WeatherUnits.CELSIUS))
        assertEquals(-2, displayedWeatherTemperature(-2.9, WeatherUnits.CELSIUS))
    }
    @Test fun celsiusDefaultHeaderVisualAndAccessibleUnits() {
        show(summary = DaySummary(Condition.RAIN, 11.9, -2.9, 80.0))
        compose.onNodeWithText("11° / -2°").assertIsDisplayed()
        compose.onNodeWithContentDescription("High 11 degrees Celsius, low -2 degrees Celsius").assertIsDisplayed()
        compose.onNodeWithContentDescription("Cool · 11 degrees Celsius").assertIsDisplayed()
    }
    @Test fun fahrenheitHeaderAndReadoutExposeUnitsWithoutChangingBandsOrPrecipitation() {
        show(units = WeatherUnits.FAHRENHEIT, summary = DaySummary(Condition.RAIN, 11.0, 6.0, 80.0), stale = true)
        compose.onNodeWithText("52°F / 43°F").assertIsDisplayed()
        compose.onNodeWithContentDescription("High 52 degrees Fahrenheit, low 43 degrees Fahrenheit, cached forecast").assertIsDisplayed()
        compose.onNodeWithContentDescription("Cool · 52 degrees Fahrenheit").assertIsDisplayed()
        compose.onNodeWithContentDescription("Light rain, 1.9 millimeters").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Precipitation chance 80 percent").assertCountEquals(2)
        assertEquals(11.0, sample.tempC, 0.0)
        assertEquals(TempBand.COOL, tempBand(sample.tempC))
    }
    @Test fun dryReadoutDoesNotInventProbabilityOrPrecipitation() {
        show(hour = DayHour(Condition.CLEAR, 80.0, 0.0, true, 20.0))
        compose.onAllNodesWithContentDescription("Precipitation chance", substring = true).assertCountEquals(0)
        compose.onAllNodesWithContentDescription("millimeters", substring = true).assertCountEquals(0)
        compose.onNodeWithText("Bright · Clear").assertIsDisplayed()
    }
    @Test fun missingPrecipitationAmountStaysUnknownRatherThanZero() {
        show(hour = sample.copy(precipMm = null))
        compose.onNodeWithContentDescription("Rain", useUnmergedTree = true).assertExists()
        compose.onAllNodesWithContentDescription("millimeters", substring = true).assertCountEquals(0)
    }
    @Test fun snowRetainsMillimetersAndCelsiusBandThresholds() {
        show(hour = DayHour(Condition.SNOW, null, 2.5, false, -3.0), units = WeatherUnits.FAHRENHEIT)
        compose.onNodeWithContentDescription("Freezing · 27 degrees Fahrenheit").assertIsDisplayed()
        compose.onNodeWithContentDescription("Heavy snow, 2.5 millimeters").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Precipitation chance", substring = true).assertCountEquals(0)
    }
    @Test fun precipitationUsesTheEndStampWhileTemperatureAndSkyUseTheSelectedHour() {
        val instant = DayHour(Condition.CLEAR, 0.0, 0.0, true, 20.0)
        val nextStamp = DayHour(Condition.RAIN, 90.0, 3.0, false, 7.0)
        show(hour = instant, precipitationHour = nextStamp, units = WeatherUnits.FAHRENHEIT)
        compose.onNodeWithContentDescription("Mild · 68 degrees Fahrenheit").assertIsDisplayed()
        compose.onNodeWithText("Bright · Clear").assertIsDisplayed()
        compose.onNodeWithContentDescription("Rain, 3.0 millimeters").assertIsDisplayed()
        compose.onNodeWithContentDescription("Precipitation chance 90 percent").assertIsDisplayed()
    }
    @Test @Config(qualifiers = "+notnight") fun fahrenheitReadoutLight() {
        show(units = WeatherUnits.FAHRENHEIT, summary = DaySummary(Condition.RAIN, 11.0, 6.0, 80.0), stale = true)
        compose.onRoot().captureRoboImage("src/test/screenshots/weather_units_fahrenheit_light.png")
    }
    @Test @Config(qualifiers = "+night") fun fahrenheitReadoutLargeRtl() {
        show(hour = DayHour(Condition.SNOW, 70.0, 0.8, false, -2.0), units = WeatherUnits.FAHRENHEIT,
            summary = DaySummary(Condition.SNOW, -2.0, -7.0, 70.0), font = 2f, rtl = true)
        compose.onRoot().captureRoboImage("src/test/screenshots/weather_units_fahrenheit_large_rtl.png")
    }
}
