package dev.swapnilraj.calendar

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.swapnilraj.calendar.core.CalendarSelection
import dev.swapnilraj.calendar.data.CalendarPreferences
import dev.swapnilraj.calendar.data.CalendarPreferenceState
import dev.swapnilraj.calendar.data.settings.CalendarSettingSave
import dev.swapnilraj.calendar.ui.PhoneSettingsWorkflow
import dev.swapnilraj.calendar.ui.calendars.CalendarViewOption
import dev.swapnilraj.calendar.ui.settings.*
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneSettingsWorkflowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun preferenceLoadingAndFailureMaskButRetainUnfinishedInputWithoutSaving() {
        val original = CalendarPreferenceState.Ready(CalendarPreferences(CalendarSelection.Explicit(emptySet()), null, "WEEK", 88f,
            CalendarSettingsOptions(defaultView = CalendarViewOption.WEEK)))
        var preferences by mutableStateOf<CalendarPreferenceState>(original)
        var saves = 0
        var retries = 0
        var closes = 0
        var dispatcher: OnBackPressedDispatcher? = null
        compose.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            DaylightTheme {
            PhoneSettingsWorkflow(preferences, CalendarViewOption.entries,
                save = { saves++; CalendarSettingSave.Saved }, onRetryPreferences = { retries++ },
                onClose = { closes++ }, onChooseDefaultCalendar = {}, onOpenReminders = null)
        } }
        compose.onNodeWithTag("phone_settings_open_general").performClick()
        compose.onNodeWithTag("phone_settings_general_list").performScrollToNode(hasTestTag("phone_settings_row_duration"))
        compose.onNodeWithTag("phone_settings_row_duration").performClick()
        compose.onNodeWithTag("phone_settings_editor_input").performTextReplacement("unfinished")
        compose.runOnIdle { preferences = CalendarPreferenceState.Loading }
        compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.navigation_preferences_loading)).assertIsDisplayed()
        compose.onNodeWithTag("phone_settings_editor_input").assertDoesNotExist()
        compose.runOnIdle { dispatcher!!.onBackPressed(); assertEquals(0, closes) }
        compose.runOnIdle { preferences = CalendarPreferenceState.Unavailable }
        compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.events_retry)).performClick()
        compose.runOnIdle { assertEquals(1, retries); assertEquals(0, saves) }
        compose.runOnIdle { preferences = original.copy(value = original.value.copy(options = original.value.options.copy(theme = SettingsTheme.DARK))) }
        compose.onNodeWithTag("phone_settings_editor_input").assertTextContains("unfinished")
        compose.onNodeWithTag("phone_settings_editor_cancel").performClick()
        compose.runOnIdle { assertEquals(0, saves) }
    }
}
