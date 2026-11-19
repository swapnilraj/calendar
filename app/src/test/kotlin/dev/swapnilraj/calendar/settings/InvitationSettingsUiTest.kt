package dev.swapnilraj.calendar.settings

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import dev.swapnilraj.calendar.core.InvitationFilter
import dev.swapnilraj.calendar.core.CalendarSelection
import dev.swapnilraj.calendar.data.CalendarPreferences
import dev.swapnilraj.calendar.data.CalendarPreferenceState
import dev.swapnilraj.calendar.data.settings.CalendarSettingSave
import dev.swapnilraj.calendar.ui.PhoneSettingsWorkflow
import java.time.ZoneId
import dev.swapnilraj.calendar.data.settings.CalendarSettingChange
import dev.swapnilraj.calendar.ui.calendars.CalendarViewOption
import dev.swapnilraj.calendar.ui.settings.*
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
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
class InvitationSettingsUiTest {
    @get:Rule val compose = createComposeRule()
    private fun open() {
        compose.onNodeWithTag("phone_settings_open_general").performClick()
        compose.onNodeWithTag("phone_settings_general_list").performScrollToNode(hasTestTag("phone_settings_row_invitations"))
        compose.onNodeWithTag("phone_settings_row_invitations").performClick()
    }
    @Test fun openingAndCancellingDoesNotSaveAndChoiceIsTyped() {
        val changes = mutableListOf<CalendarSettingChange>()
        compose.setContent { DaylightTheme { PhoneSettingsScreen(CalendarSettingsOptions(), CalendarViewOption.entries, changes::add, {}, {}, null) } }
        open()
        compose.onNodeWithTag("phone_settings_choice_ALL").assertIsSelected()
        compose.onRoot().captureRoboImage("src/test/screenshots/invitation_filter_choice_light.png")
        compose.onNodeWithTag("phone_settings_editor_cancel").performClick()
        assertTrue(changes.isEmpty())
        compose.onNodeWithTag("phone_settings_row_invitations").performClick()
        compose.onNodeWithTag("phone_settings_choice_PENDING_ONLY").performClick()
        assertEquals(listOf(CalendarSettingChange.InvitationFilter(InvitationFilter.PENDING_ONLY)), changes)
        compose.onNodeWithTag("phone_settings_choice_ALL").assertIsNotEnabled()
    }
    @Test fun failedSaveRetriesExactTypedChangeAndAcknowledgementClosesEditor() {
        val changes = mutableListOf<CalendarSettingChange>()
        var revision by mutableLongStateOf(0)
        var error by mutableStateOf<String?>(null)
        compose.setContent { DaylightTheme { PhoneSettingsScreen(CalendarSettingsOptions(), CalendarViewOption.entries,
            { changes += it; error = "Save failed" }, {}, {}, null, saveError = error, savedChangeRevision = revision) } }
        open()
        compose.onNodeWithTag("phone_settings_choice_PENDING_ONLY").performClick()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(listOf(CalendarSettingChange.InvitationFilter(InvitationFilter.PENDING_ONLY), CalendarSettingChange.InvitationFilter(InvitationFilter.PENDING_ONLY)), changes)
        compose.runOnIdle { error = null; revision++ }
        compose.onNodeWithTag("phone_settings_editor").assertDoesNotExist()
        compose.onNodeWithTag("phone_settings_general_list").assertExists()
    }
    @Test fun pendingChoiceOpensFromCurrentOptionsWithoutSubmitting() {
        var calls = 0
        compose.setContent { DaylightTheme { PhoneSettingsScreen(CalendarSettingsOptions(invitationFilter = InvitationFilter.PENDING_ONLY),
            CalendarViewOption.entries, { calls++ }, {}, {}, null) } }
        open()
        compose.onNodeWithTag("phone_settings_choice_PENDING_ONLY").assertIsSelected()
        compose.onNodeWithTag("phone_settings_editor_cancel").performClick()
        assertEquals(0, calls)
    }
    @Test fun restoringPendingEditorNeverReplaysSubmittedChange() {
        val state = PhoneSettingsState()
        state.open(PhoneSettingEditor.INVITATION_FILTER, CalendarSettingsOptions(), ZoneId.of("UTC"))
        state.choice = InvitationFilter.PENDING_ONLY.name
        state.submit(CalendarSettingChange.InvitationFilter(InvitationFilter.PENDING_ONLY), 7)
        val scope = object : SaverScope { override fun canBeSaved(value: Any) = true }
        val saved = with(PhoneSettingsState.Saver) { scope.save(state) }!!
        val restored = PhoneSettingsState.Saver.restore(saved)!!
        assertEquals(PhoneSettingEditor.INVITATION_FILTER, restored.editor)
        assertEquals(InvitationFilter.PENDING_ONLY.name, restored.choice)
        assertFalse(restored.awaitingSave)
        assertNull(restored.lastChange)
    }
    @Test fun loadingAndUnavailablePreferencesMaskInvitationChoiceWithoutSaving() {
        val original = CalendarPreferenceState.Ready(CalendarPreferences(CalendarSelection.Explicit(emptySet()), null,
            "WEEK", 88f, CalendarSettingsOptions(invitationFilter = InvitationFilter.PENDING_ONLY)))
        var preferences by mutableStateOf<CalendarPreferenceState>(original)
        var saves = 0
        compose.setContent { DaylightTheme { PhoneSettingsWorkflow(preferences, CalendarViewOption.entries,
            save = { saves++; CalendarSettingSave.Saved }, onRetryPreferences = {}, onClose = {},
            onChooseDefaultCalendar = {}, onOpenReminders = null) } }
        open()
        compose.onNodeWithTag("phone_settings_choice_PENDING_ONLY").assertIsSelected()
        compose.runOnIdle { preferences = CalendarPreferenceState.Loading }
        compose.onNodeWithTag("phone_settings_choice_PENDING_ONLY").assertDoesNotExist()
        compose.runOnIdle { preferences = CalendarPreferenceState.Unavailable }
        compose.onNodeWithTag("phone_settings_choice_PENDING_ONLY").assertDoesNotExist()
        compose.runOnIdle { preferences = original }
        compose.onNodeWithTag("phone_settings_choice_PENDING_ONLY").assertIsSelected()
        compose.onNodeWithTag("phone_settings_editor_cancel").performClick()
        assertEquals(0, saves)
    }

}
