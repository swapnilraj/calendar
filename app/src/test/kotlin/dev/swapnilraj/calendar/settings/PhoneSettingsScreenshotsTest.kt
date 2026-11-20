package dev.swapnilraj.calendar.settings

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.swapnilraj.calendar.ui.calendars.CalendarViewOption
import dev.swapnilraj.calendar.ui.settings.*
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
import java.time.ZoneId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w393dp-h851dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneSettingsScreenshotsTest {
    @get:Rule val compose = createComposeRule()

    private fun show(options: CalendarSettingsOptions = CalendarSettingsOptions(), dark: Boolean = false, font: Float = 1f, rtl: Boolean = false) {
        compose.setContent {
            var error by remember { mutableStateOf<String?>(null) }
            DaylightTheme(dark) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, font),
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    PhoneSettingsScreen(options, CalendarViewOption.entries, { error = "Preferences could not be saved. Try again." }, {}, {}, {},
                        saveError = error, deviceZone = ZoneId.of("UTC"), defaultCalendarSummary = "Ask each time")
                }
            }
        }
    }
    private fun general() { compose.onNodeWithTag("phone_settings_open_general").performClick() }
    private fun row(tag: String) {
        compose.onNodeWithTag("phone_settings_general_list").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick()
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/" + name + ".png")
    }

    @Test fun homeLight() { show(); capture("phone_settings_home_light") }
    @Test fun homeDark() { show(dark = true); capture("phone_settings_home_dark") }
    @Test fun homeLargeRtl() { show(font = 2f, rtl = true); capture("phone_settings_home_rtl_large") }
    @Test fun generalLight() { show(); general(); capture("phone_settings_general_light") }
    @Test fun generalDarkFixedZone() {
        show(CalendarSettingsOptions(displayZone = DisplayZoneOption.Fixed(ZoneId.of("Europe/London"))), dark = true)
        general(); capture("phone_settings_general_dark_fixed_zone")
    }
    @Test fun generalLargeRtl() { show(font = 2f, rtl = true); general(); capture("phone_settings_general_rtl_large") }
    @Test fun weatherAndReminderGroupsRemainVisible() {
        show(); general()
        compose.onNodeWithTag("phone_settings_general_list").performScrollToNode(hasTestTag("phone_settings_row_weather_units"))
        capture("phone_settings_weather")
    }
    @Test fun invalidDurationKeepsRawInputAndExplicitActions() {
        show(); general(); row("phone_settings_row_duration")
        compose.onNodeWithTag("phone_settings_editor_input").performTextReplacement(" 0 ")
        compose.onNodeWithTag("phone_settings_editor_input").performImeAction()
        compose.onNodeWithTag("phone_settings_editor_apply").performClick()
        capture("phone_settings_duration_invalid")
    }
    @Test fun unavailableSaveRetainsExactRawProposal() {
        show(); general(); row("phone_settings_row_duration")
        compose.onNodeWithTag("phone_settings_editor_input").performTextReplacement(" 90 ")
        compose.onNodeWithTag("phone_settings_editor_input").performImeAction()
        compose.onNodeWithTag("phone_settings_editor_apply").performClick()
        compose.onNodeWithText("Preferences could not be saved. Try again.").assertExists()
        capture("phone_settings_save_error")
    }
}
