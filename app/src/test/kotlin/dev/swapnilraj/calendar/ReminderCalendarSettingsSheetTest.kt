package dev.swapnilraj.calendar

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.calendar.CalendarReminderSource
import dev.swapnilraj.calendar.data.reminders.*
import dev.swapnilraj.calendar.ui.reminders.*
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
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
class ReminderCalendarSettingsSheetTest {
    @get:Rule val compose = createComposeRule()
    private fun calendar(id: Long = 1, account: String = "owner@example.test", access: Int? = 700) = CalendarInfo(CalendarId(id), "Planning $id", 0,
        CalendarAccount(account, "com.google"), account, null, access, true, true)
    private fun identity(info: CalendarInfo = calendar()) = requireNotNull(CalendarReminderSource.calendarIdentity(info))
    private fun saved() = SavedReminderSettings(false, mapOf(CalendarId(1) to identity(), CalendarId(99) to ReminderCalendarIdentity("retained-missing", "retained-proof")),
        mapOf(CalendarId(1) to ReminderDefaults(10, 900, ReminderDefaultMethod.LOCAL_ALERT), CalendarId(99) to ReminderDefaults(-30, -540, ReminderDefaultMethod.NON_LOCAL)))
    private fun show(base: SavedReminderSettings = saved(), metadata: MutableState<CalendarReadState<List<CalendarInfo>>>? = null,
        preferences: MutableState<ReminderPreferenceState>? = null, result: MutableState<ReminderPreferenceSave?>? = null, saving: Boolean = false,
        onSave: (SavedReminderSettings, SavedReminderSettings) -> Unit = { _, _ -> }, onReload: () -> Unit = {}, onPermissions: () -> Unit = {},
        font: Float = 1f, rtl: Boolean = false) {
        compose.setContent { DaylightTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, font), LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                ReminderCalendarSettingsSheet(preferences?.value ?: ReminderPreferenceState.Ready(base), metadata?.value ?: CalendarReadState.Ready(listOf(calendar(), calendar(2), calendar(3, access = 100), calendar(4, account = ""))),
                    onSave, {}, onPermissions, onReload, {}, saving, result?.value)
            }
        } }
        compose.waitForIdle()
    }
    private fun node(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("reminder_calendar_fields").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }
    private fun text(label: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("reminder_calendar_fields").performScrollToNode(hasText(label))
        return compose.onNodeWithText(label)
    }
    @Test fun untouchedLegacyAndUnrepresentedPreferencesRemainExact() {
        val expected = saved()
        val draft = ReminderSettingsDraft.from(expected).copy(enabled = true)
        assertEquals(expected.copy(enabled = true), draft.updated())
        assertEquals(-540, draft.updated()!!.explicitDefaults.getValue(CalendarId(99)).allDayMinutesBefore)
        assertEquals("retained-proof", draft.updated()!!.expectedCalendarIdentities.getValue(CalendarId(99)).providerUidFingerprint)
    }
    @Test fun nullIsUnresolvedAndNewOffsetsAcceptFullIntRangeWithoutMagicDefaults() {
        val empty = SavedReminderSettings(expectedCalendarIdentities = mapOf(CalendarId(1) to identity()))
        val draft = ReminderSettingsDraft.from(empty)
        assertTrue(draft.updated()!!.explicitDefaults.isEmpty())
        val raw = draft.edit(CalendarId(1), ReminderDefaultsInput("", "", ReminderDefaultMethod.UNRESOLVED)).updated()!!
        assertEquals(ReminderDefaults(), raw.explicitDefaults[CalendarId(1)])
        assertEquals(Int.MAX_VALUE, draft.edit(CalendarId(1), ReminderDefaultsInput(Int.MAX_VALUE.toString(), "0", ReminderDefaultMethod.LOCAL_ALERT)).updated()!!.explicitDefaults[CalendarId(1)]!!.timedMinutesBefore)
        for (bad in listOf("-1", "-5", "2147483648", "nope")) assertNull(draft.edit(CalendarId(1), ReminderDefaultsInput(bad, "", ReminderDefaultMethod.UNRESOLVED)).updated())
    }
    @Test fun identityVerificationNeverWeakensSavedProviderProof() {
        val strong = ReminderCalendarIdentity("same-account", "uid-a")
        assertFalse(reminderIdentityVerified(strong, ReminderCalendarIdentity("same-account")))
        assertFalse(reminderIdentityVerified(strong, ReminderCalendarIdentity("same-account", "uid-b")))
        assertTrue(reminderIdentityVerified(strong, ReminderCalendarIdentity("same-account", "uid-a")))
        assertFalse(reminderIdentityVerified(strong, ReminderCalendarIdentity("different-account", "uid-a")))
    }
    @Test fun explicitReconfirmationClearsOnlyThatCalendarsDefaults() {
        val base = saved()
        val next = identity(calendar(account = "replacement@example.test"))
        val draft = ReminderSettingsDraft.from(base).confirm(CalendarId(1), next)
        val updated = draft.updated()!!
        assertEquals(next, updated.expectedCalendarIdentities[CalendarId(1)])
        assertFalse(updated.explicitDefaults.containsKey(CalendarId(1)))
        assertEquals(base.explicitDefaults[CalendarId(99)], updated.explicitDefaults[CalendarId(99)])
        assertEquals(base.expectedCalendarIdentities[CalendarId(99)], updated.expectedCalendarIdentities[CalendarId(99)])
    }
    @Test fun enablingNeverSelectsProviderVisibleCalendarsAutomatically() {
        var updated: SavedReminderSettings? = null
        show(base = SavedReminderSettings(), onSave = { _, value -> updated = value })
        node("reminder_calendars_enabled").performClick()
        node("reminder_calendar_1").assertIsOff()
        node("reminder_calendar_3").assertIsNotEnabled()
        node("reminder_calendar_4").assertIsNotEnabled()
        compose.onNodeWithTag("reminder_calendars_save").performClick()
        assertEquals(SavedReminderSettings(enabled = true), updated)
    }
    @Test fun metadataChangeNeedsExplicitConfirmationBeforeReplacingSavedIdentity() {
        val base = saved()
        val metadata = mutableStateOf<CalendarReadState<List<CalendarInfo>>>(CalendarReadState.Ready(listOf(calendar(account = "replacement@example.test"))))
        var expected: SavedReminderSettings? = null
        var updated: SavedReminderSettings? = null
        show(base, metadata = metadata, onSave = { before, after -> expected = before; updated = after })
        compose.onNodeWithTag("reminder_calendars_save").performClick()
        assertEquals(base, updated)
        node("reminder_reconfirm_1").performClick()
        compose.onNodeWithTag("reminder_identity_confirm").performClick()
        compose.onNodeWithTag("reminder_calendars_save").performClick()
        assertEquals(base, expected)
        assertEquals(identity(calendar(account = "replacement@example.test")), updated!!.expectedCalendarIdentities[CalendarId(1)])
        assertFalse(updated!!.explicitDefaults.containsKey(CalendarId(1)))
        assertEquals(base.explicitDefaults[CalendarId(99)], updated!!.explicitDefaults[CalendarId(99)])
    }
    @Test fun confirmationIsFencedIfMetadataChangesWhileDialogIsOpen() {
        val metadata = mutableStateOf<CalendarReadState<List<CalendarInfo>>>(CalendarReadState.Ready(listOf(calendar(account = "first@example.test"))))
        show(metadata = metadata)
        node("reminder_reconfirm_1").performClick()
        metadata.value = CalendarReadState.Ready(listOf(calendar(account = "second@example.test")))
        compose.waitForIdle()
        compose.onNodeWithTag("reminder_identity_confirm").assertIsNotEnabled()
        compose.onNodeWithText("Calendar information changed again while this confirmation was open. Cancel and review the new information.").assertIsDisplayed()
        compose.onNodeWithTag("reminder_identity_cancel").performClick()
    }
    @Test fun missingCurrentUidKeepsStrongSavedEvidenceUntilExplicitConsent() {
        val source = identity()
        val base = SavedReminderSettings(expectedCalendarIdentities = mapOf(CalendarId(1) to source.copy(providerUidFingerprint = "strong-saved-proof")))
        var updated: SavedReminderSettings? = null
        show(base, onSave = { _, next -> updated = next })
        node("reminder_calendar_1").assertIsOn()
        compose.onNodeWithTag("reminder_defaults_1").assertDoesNotExist()
        compose.onNodeWithTag("reminder_calendars_save").performClick()
        assertEquals(base, updated)
    }
    @Test fun sharedIssuerProviderIdChangeIsNotSilentlyAdopted() {
        val original = calendar().copy(providerId = "provider-calendar-a")
        val replacement = calendar().copy(providerId = "provider-calendar-b")
        val originalIdentity = identity(original)
        assertTrue(originalIdentity.isKnownDifferentFrom(identity(replacement)))
        val base = SavedReminderSettings(expectedCalendarIdentities = mapOf(CalendarId(1) to originalIdentity))
        var updated: SavedReminderSettings? = null
        show(base, metadata = mutableStateOf(CalendarReadState.Ready(listOf(replacement))), onSave = { _, value -> updated = value })
        node("reminder_reconfirm_1").assertIsDisplayed()
        compose.onNodeWithTag("reminder_calendars_save").performClick()
        assertEquals(originalIdentity, updated!!.expectedCalendarIdentities[CalendarId(1)])
    }
    @Test fun invalidPendingOffsetIsRetainedAndFailedSaveDoesNotResetFields() {
        val result = mutableStateOf<ReminderPreferenceSave?>(null)
        val expected = saved()
        var calls = 0
        var last: SavedReminderSettings? = null
        show(result = result, onSave = { before, next -> assertEquals(expected, before); calls++; last = next; result.value = ReminderPreferenceSave.Unavailable })
        node("reminder_defaults_1").performClick()
        node("reminder_timed_1").performTextReplacement("-1")
        compose.onNodeWithTag("reminder_calendars_save").performClick()
        assertEquals(0, calls)
        node("reminder_timed_1").assertTextContains("-1").performTextReplacement("45")
        compose.onNodeWithTag("reminder_calendars_save").performClick()
        node("reminder_timed_1").assertTextContains("45")
        assertEquals(1, calls)
        assertEquals(45, last!!.explicitDefaults[CalendarId(1)]!!.timedMinutesBefore)
        assertEquals(expected.explicitDefaults[CalendarId(99)], last!!.explicitDefaults[CalendarId(99)])
    }
    @Test fun staleCasRequiresExplicitReloadAndNeverAutomaticallyRetries() {
        val result = mutableStateOf<ReminderPreferenceSave?>(null)
        var calls = 0
        var reloads = 0
        show(result = result, onSave = { _, _ -> calls++; result.value = ReminderPreferenceSave.Changed }, onReload = { reloads++ })
        node("reminder_defaults_1").performClick()
        node("reminder_timed_1").performTextReplacement("45")
        compose.onNodeWithTag("reminder_calendars_save").performClick().assertIsNotEnabled()
        node("reminder_timed_1").assertTextContains("45")
        compose.onNodeWithTag("reminder_settings_reload").performClick()
        assertEquals(1, calls)
        assertEquals(1, reloads)
    }
    @Test fun incomingSettingsNeverSilentlyRebaseAnOpenDraft() {
        val preferences = mutableStateOf<ReminderPreferenceState>(ReminderPreferenceState.Ready(saved()))
        show(preferences = preferences)
        node("reminder_defaults_1").performClick()
        node("reminder_timed_1").performTextReplacement("45")
        preferences.value = ReminderPreferenceState.Ready(saved().copy(enabled = true))
        compose.waitForIdle()
        compose.onNodeWithTag("reminder_calendars_save").assertIsNotEnabled()
        node("reminder_timed_1").assertTextContains("45")
    }
    @Test fun unavailablePreferencesAreNotReplacedWithDisabledDefaultsAndPermissionsOnlyDelegate() {
        var permissions = 0
        var saves = 0
        show(preferences = mutableStateOf(ReminderPreferenceState.Unavailable()), onSave = { _, _ -> saves++ }, onPermissions = { permissions++ })
        compose.onNodeWithTag("reminder_calendars_save").assertIsNotEnabled()
        compose.onNodeWithTag("reminder_calendars_enabled").assertDoesNotExist()
        text("Permissions and delivery setup").performClick()
        assertEquals(1, permissions)
        assertEquals(0, saves)
    }
    @Test fun savingDisablesDuplicateSaveCancelAndSelection() {
        var saves = 0
        show(saving = true, onSave = { _, _ -> saves++ })
        compose.onNodeWithTag("reminder_calendars_save").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").assertIsNotEnabled()
        node("reminder_calendars_enabled").assertIsNotEnabled()
        node("reminder_calendar_1").assertIsNotEnabled()
        assertEquals(0, saves)
    }
    @Test fun successfulLocalSaveDoesNotLookLikeAConflictOrClaimDelivery() {
        val preferences = mutableStateOf<ReminderPreferenceState>(ReminderPreferenceState.Ready(saved()))
        val result = mutableStateOf<ReminderPreferenceSave?>(null)
        show(preferences = preferences, result = result, onSave = { _, next ->
            preferences.value = ReminderPreferenceState.Ready(next)
            result.value = ReminderPreferenceSave.Saved(next)
        })
        node("reminder_calendars_enabled").performClick()
        compose.onNodeWithTag("reminder_calendars_save").performClick().assertIsNotEnabled()
        compose.onNodeWithText("Saved locally. Actual reminder delivery has not been verified.").assertIsDisplayed()
        compose.onNodeWithTag("reminder_settings_reload").assertDoesNotExist()
    }
    @Test fun restoredPendingRawFieldsKeepTheOriginalCasSnapshot() {
        val restoration = StateRestorationTester(compose)
        var before: SavedReminderSettings? = null
        restoration.setContent { DaylightTheme {
            ReminderCalendarSettingsSheet(ReminderPreferenceState.Ready(saved()), CalendarReadState.Ready(listOf(calendar())), { expected, _ -> before = expected }, {}, {}, {}, {})
        } }
        node("reminder_defaults_1").performClick()
        node("reminder_timed_1").performTextReplacement("45")
        restoration.emulateSavedInstanceStateRestore()
        node("reminder_timed_1").assertTextContains("45")
        compose.onNodeWithTag("reminder_calendars_save").performClick()
        assertEquals(saved(), before)
    }
    @Test @Config(qualifiers = "+notnight") fun reminderCalendarSettingsLight() {
        show()
        compose.onNodeWithTag("reminder_calendar_sheet").captureRoboImage("src/test/screenshots/reminder_calendars_light.png")
    }
    @Test @Config(qualifiers = "+night") fun reminderCalendarDefaultsDark() {
        show()
        node("reminder_defaults_1").performClick()
        node("reminder_default_method").assertIsDisplayed()
        compose.onNodeWithTag("reminder_calendar_sheet").captureRoboImage("src/test/screenshots/reminder_calendars_defaults_dark.png")
    }
    @Test @Config(qualifiers = "+notnight") fun reminderCalendarSettingsLargeRtl() {
        show(font = 2f, rtl = true)
        node("reminder_calendar_1").assertIsDisplayed()
        compose.onNodeWithTag("reminder_calendar_sheet").captureRoboImage("src/test/screenshots/reminder_calendars_large_rtl.png")
        compose.onNodeWithTag("reminder_calendars_save").assertIsDisplayed()
    }
}
