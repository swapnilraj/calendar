package dev.swapnilraj.calendar.settings

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.swapnilraj.calendar.data.settings.CalendarSettingChange
import dev.swapnilraj.calendar.ui.calendars.CalendarViewOption
import dev.swapnilraj.calendar.ui.settings.*
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
import java.time.ZoneId
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
class PhoneSettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    private class Fixture {
        var options by mutableStateOf(CalendarSettingsOptions())
        var saving by mutableStateOf(false)
        var error by mutableStateOf<String?>(null)
        var revision by mutableLongStateOf(0)
        var views = CalendarViewOption.entries.toList()
        val calls = mutableListOf<CalendarSettingChange>()
        var close = 0
        var calendars = 0
        var reminders = 0
        var back: OnBackPressedDispatcher? = null
        var immediateAck = false
        fun submit(change: CalendarSettingChange) {
            calls += change
            error = null
            saving = true
            if (immediateAck) { revision++; saving = false }
        }
        fun failed() { saving = false; error = "Could not save this choice." }
        fun saved(options: CalendarSettingsOptions = this.options) { this.options = options; revision++; saving = false }
    }

    @Composable
    private fun content(f: Fixture, reminders: Boolean = true, font: Float = 1f, rtl: Boolean = false) {
        f.back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
        DaylightTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, font),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                PhoneSettingsScreen(f.options, f.views, f::submit, { f.close++ }, { f.calendars++ },
                    if (reminders) ({ f.reminders++ }) else null,
                    saving = f.saving, saveError = f.error, savedChangeRevision = f.revision, deviceZone = ZoneId.of("UTC"))
            }
        }
    }
    private fun show(f: Fixture, reminders: Boolean = true, font: Float = 1f, rtl: Boolean = false) {
        compose.setContent { content(f, reminders, font, rtl) }
    }
    private fun general() { compose.onNodeWithTag("phone_settings_open_general").performClick() }
    private fun row(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("phone_settings_general_list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }
    private fun input() = compose.onNodeWithTag("phone_settings_editor_input")
    private fun apply() = compose.onNodeWithTag("phone_settings_editor_apply").performClick()
    private fun back(f: Fixture) { compose.runOnUiThread { f.back!!.onBackPressed() }; compose.waitForIdle() }

    @Test fun homeActionsAreRealAndUnsupportedGoogleControlsStayAbsent() {
        val f = Fixture(); show(f)
        compose.onNodeWithTag("phone_settings_default_calendar").performClick()
        compose.onNodeWithTag("phone_settings_reminders").performClick()
        assertEquals(1, f.calendars); assertEquals(1, f.reminders); assertTrue(f.calls.isEmpty())
        general()
        for (label in listOf("Manage accounts", "Alternate calendar", "Show week number", "Show event illustrations",
            "Adding invitations", "Tasks notifications", "Quick responses", "Share Google Calendar data with other apps")) {
            compose.onNodeWithText(label).assertDoesNotExist()
        }
        back(f); compose.onNodeWithTag("phone_settings_open_general").assertExists(); assertEquals(0, f.close)
        back(f); assertEquals(1, f.close); assertTrue(f.calls.isEmpty())
    }

    @Test fun missingReminderCallbackHasNoDeadHomeRow() {
        val f = Fixture(); show(f, reminders = false)
        compose.onNodeWithTag("phone_settings_reminders").assertDoesNotExist()
        assertTrue(f.calls.isEmpty())
    }

    @Test fun switchSubmissionLocksImmediatelyAndExplicitRetryKeepsTheSameDesiredChoice() {
        val f = Fixture(); show(f); general()
        row("phone_settings_row_declined").assertIsOn().performClick()
        assertEquals(listOf(CalendarSettingChange.ShowDeclined(false)), f.calls)
        compose.onNodeWithTag("phone_settings_row_declined").assertIsNotEnabled()
        compose.runOnIdle { f.failed() }; compose.waitForIdle()
        compose.onNodeWithText("Could not save this choice.").assertExists()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(listOf(CalendarSettingChange.ShowDeclined(false), CalendarSettingChange.ShowDeclined(false)), f.calls)
        assertEquals(0, f.close)
    }

    @Test fun invalidRawImeAndCancelNeverSubmit() {
        val f = Fixture(); show(f); general(); row("phone_settings_row_duration").performClick()
        input().performTextReplacement(" 2147483648 "); input().performImeAction()
        assertTrue(f.calls.isEmpty()); input().assertTextContains(" 2147483648 ")
        apply()
        compose.onNodeWithText("Enter a whole number from 1 to 1440 minutes.").assertExists()
        assertTrue(f.calls.isEmpty())
        compose.onNodeWithTag("phone_settings_editor_cancel").performClick()
        compose.onNodeWithTag("phone_settings_general_list").assertExists()
        assertTrue(f.calls.isEmpty()); assertEquals(0, f.close)
        row("phone_settings_row_duration").performClick()
        input().performTextReplacement(" 90 "); apply()
        compose.runOnIdle { f.failed() }; compose.waitForIdle()
        compose.onNodeWithTag("phone_settings_editor_cancel").performClick()
        compose.onNodeWithText("Try again").assertDoesNotExist()
        assertEquals(listOf(CalendarSettingChange.Duration(90)), f.calls)
    }

    @Test fun rawInputAndErrorSurviveReadUpdatesUntilTheExplicitSavedAcknowledgment() {
        val f = Fixture(); show(f); general(); row("phone_settings_row_duration").performClick()
        input().performTextReplacement(" 90 "); apply()
        assertEquals(listOf(CalendarSettingChange.Duration(90)), f.calls)
        input().assertIsNotEnabled()
        compose.onNodeWithTag("phone_settings_editor_cancel").assertIsNotEnabled()
        back(f); compose.onNodeWithTag("phone_settings_editor").assertExists()
        compose.runOnIdle { f.failed(); f.options = f.options.copy(theme = SettingsTheme.DARK, defaultView = CalendarViewOption.MONTH) }
        compose.waitForIdle()
        input().assertTextContains(" 90 "); compose.onNodeWithText("Could not save this choice.").assertExists()
        apply()
        assertEquals(2, f.calls.size)
        compose.runOnIdle { f.saved(f.options.copy(defaultDurationMinutes = 90)) }; compose.waitForIdle()
        compose.onNodeWithTag("phone_settings_editor").assertDoesNotExist()
        row("phone_settings_row_duration").assertTextContains("90 minutes")
        assertEquals(CalendarViewOption.MONTH, f.options.defaultView); assertEquals(0, f.close)
    }

    @Test fun deviceZoneRemainsAuthoritativeUntilAValidExplicitFixedZoneIsSaved() {
        val f = Fixture(); show(f); general()
        row("phone_settings_row_zone").assertIsNotEnabled()
        row("phone_settings_row_device_zone").assertIsOn().performClick()
        input().assertTextContains("UTC"); assertTrue(f.calls.isEmpty())
        input().performTextReplacement("+05:30"); apply()
        assertTrue(f.calls.isEmpty()); compose.onNodeWithText("Choose an available IANA time zone. Numeric offsets are not supported.").assertExists()
        input().performTextReplacement(" Europe/London "); apply()
        val fixed = DisplayZoneOption.Fixed(ZoneId.of("Europe/London"))
        assertEquals(listOf(CalendarSettingChange.DisplayZone(fixed)), f.calls)
        compose.runOnIdle { f.saved(f.options.copy(displayZone = fixed)) }; compose.waitForIdle()
        row("phone_settings_row_zone").assertIsEnabled().assertTextContains("Europe/London")
        row("phone_settings_row_device_zone").assertIsOff().performClick()
        assertEquals(CalendarSettingChange.DisplayZone(DisplayZoneOption.Device), f.calls.last())
    }

    @Test fun synchronousSavedRevisionAcknowledgesTheSubmittedChoiceWithoutClosingSettings() {
        val f = Fixture().apply { immediateAck = true }; show(f); general()
        row("phone_settings_row_weekday").performClick()
        assertTrue(f.calls.isEmpty())
        compose.onNodeWithTag("phone_settings_choice_MONDAY").performClick()
        compose.waitForIdle()
        assertEquals(listOf(CalendarSettingChange.FirstWeekday(FirstWeekdayOption.MONDAY)), f.calls)
        compose.onNodeWithTag("phone_settings_editor").assertDoesNotExist()
        compose.onNodeWithTag("phone_settings_general_list").assertExists(); assertEquals(0, f.close)
    }

    @Test fun onlyAvailableViewsAreOfferedAndRawRestorationNeverReplaysAChange() {
        val f = Fixture().apply { views = listOf(CalendarViewOption.DAY, CalendarViewOption.MONTH) }
        val restoration = StateRestorationTester(compose)
        restoration.setContent { content(f) }
        general(); row("phone_settings_row_view").performClick()
        compose.onNodeWithTag("phone_settings_choice_WEEK").assertDoesNotExist()
        compose.onNodeWithTag("phone_settings_choice_MONTH").assertExists()
        compose.onNodeWithTag("phone_settings_editor_cancel").performClick()
        row("phone_settings_row_duration").performClick()
        input().performTextReplacement(" invalid raw "); apply()
        restoration.emulateSavedInstanceStateRestore()
        input().assertTextContains(" invalid raw ")
        compose.onNodeWithText("Enter a whole number from 1 to 1440 minutes.").assertExists()
        assertTrue(f.calls.isEmpty())
        back(f); assertTrue(f.calls.isEmpty()); compose.onNodeWithTag("phone_settings_general_list").assertExists()
    }

    @Test fun largeRtlKeepsWeatherAndReminderDefaultsReachableAndPreservesExplicitNullableReminder() {
        val f = Fixture(); show(f, font = 2f, rtl = true); general()
        row("phone_settings_row_weather_visible").assertIsDisplayed()
        row("phone_settings_row_weather_units").performClick()
        compose.onNodeWithTag("phone_settings_choice_FAHRENHEIT").assertIsDisplayed()
        compose.onNodeWithTag("phone_settings_editor_cancel").performClick()
        row("phone_settings_row_all_day_reminder").assertIsDisplayed()
        row("phone_settings_row_timed_reminder").performClick()
        compose.onNodeWithTag("phone_settings_editor_reminder_enabled").performClick()
        assertTrue(f.calls.isEmpty()); apply()
        assertEquals(listOf(CalendarSettingChange.TimedReminder(null)), f.calls)
    }
}
