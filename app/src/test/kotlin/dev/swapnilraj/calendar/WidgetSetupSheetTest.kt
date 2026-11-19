package dev.swapnilraj.calendar

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.swapnilraj.calendar.android.widgets.*
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.widgets.*
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
class WidgetSetupSheetTest {
    @get:Rule val compose = createComposeRule()
    private fun calendar(id: Long, access: Int? = 700, account: String = "owner@example.test", visible: Boolean = true) = CalendarInfo(CalendarId(id),
        if (id == 2L) "Personal" else "Planning", 0xff526e9e.toInt(), CalendarAccount(account, "com.google"), null, null, access, visible, true)
    private fun calendars() = listOf(calendar(1), calendar(2, 200, "personal@example.test"), calendar(3, 100), calendar(4, null), calendar(5, 0))
    private val appSelection = CalendarSelection.Explicit(setOf(CalendarId(1)))
    private fun show(choice: WidgetSetupChoice = WidgetSetupChoice(), data: CalendarReadState<List<CalendarInfo>> = CalendarReadState.Ready(calendars()),
        onSave: (WidgetSetupChoice) -> Unit = {}, onDismiss: () -> Unit = {}, onRetry: () -> Unit = {}, onPermission: (() -> Unit)? = null,
        font: Float = 1f, rtl: Boolean = false, error: MutableState<String?>? = null, saving: MutableState<Boolean>? = null,
        widgetId: MutableState<Int>? = null, metadata: MutableState<CalendarReadState<List<CalendarInfo>>>? = null) {
        compose.setContent {
            DaylightTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, font), LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    WidgetSetupSheet(widgetId?.value ?: 41, choice, metadata?.value ?: data, appSelection,
                        onSave, onDismiss, onRetry, saving = saving?.value ?: false, saveError = error?.value, onRequestPermission = onPermission)
                }
            }
        }
        compose.waitForIdle()
    }
    private fun node(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("widget_setup_fields").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }
    private fun text(label: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("widget_setup_fields").performScrollToNode(hasText(label))
        return compose.onNodeWithText(label)
    }
    @Test fun defaultChoiceIsPrivateAndFollowingAppWithoutAutomaticSave() {
        var saved: WidgetSetupChoice? = null
        show(onSave = { saved = it })
        assertNull(saved)
        node("widget_setup_follow").assertIsSelected()
        compose.onNodeWithTag("widget_setup_save").performClick()
        assertEquals(WidgetSetupChoice(WidgetCalendarSelection.FollowApp, WidgetPrivacy.BUSY_ONLY), saved)
    }
    @Test fun independentStartsEmptyAndDoesNotInheritOrMutateAppSelection() {
        var saved: WidgetSetupChoice? = null
        show(onSave = { saved = it })
        node("widget_setup_independent").performClick()
        node("widget_calendar_1").assertIsOff()
        node("widget_calendar_2").performClick().assertIsOn()
        compose.onNodeWithTag("widget_setup_save").performClick()
        assertEquals(WidgetCalendarSelection.Explicit(setOf(CalendarId(2))), saved?.selection)
        assertEquals(setOf(CalendarId(1)), appSelection.ids)
    }
    @Test fun explicitEmptyIsSavedAsNoneAndNotFollowApp() {
        var saved: WidgetSetupChoice? = null
        show(choice = WidgetSetupChoice(WidgetCalendarSelection.Explicit(emptySet())), onSave = { saved = it })
        text("No calendars selected. This widget will show no events.").assertIsDisplayed()
        compose.onNodeWithTag("widget_setup_save").performClick()
        assertEquals(WidgetCalendarSelection.Explicit(emptySet()), saved?.selection)
    }
    @Test fun modeSwitchRetainsIndependentDraftWithoutAdoptingAppFilters() {
        var saved: WidgetSetupChoice? = null
        show(onSave = { saved = it })
        node("widget_setup_independent").performClick()
        node("widget_calendar_2").performClick()
        node("widget_setup_follow").performClick()
        node("widget_app_calendar_1").assertHasNoClickAction()
        compose.onNodeWithTag("widget_app_calendar_2").assertDoesNotExist()
        node("widget_setup_independent").performClick()
        node("widget_calendar_2").assertIsOn()
        node("widget_calendar_1").assertIsOff()
        compose.onNodeWithTag("widget_setup_save").performClick()
        assertEquals(WidgetCalendarSelection.Explicit(setOf(CalendarId(2))), saved?.selection)
    }
    @Test fun knownAvailabilityOnlyIsSelectableButUnknownAndNoneCannotBeAdded() {
        var saved: WidgetSetupChoice? = null
        show(choice = WidgetSetupChoice(WidgetCalendarSelection.Explicit(emptySet())), onSave = { saved = it })
        node("widget_calendar_3").assertIsEnabled().performClick().assertIsOn()
        node("widget_calendar_4").assertIsNotEnabled().assertIsOff()
        node("widget_calendar_5").assertIsNotEnabled().assertIsOff()
        compose.onNodeWithTag("widget_setup_save").performClick()
        assertEquals(WidgetCalendarSelection.Explicit(setOf(CalendarId(3))), saved?.selection)
        assertTrue(widgetCalendarSelectable(calendar(6, 100)))
        assertFalse(widgetCalendarSelectable(calendar(7, null)))
    }
    @Test fun selectAllAvailableRetainsMissingIdsAndNeverAddsUnknownOrDeniedCalendars() {
        var saved: WidgetSetupChoice? = null
        show(choice = WidgetSetupChoice(WidgetCalendarSelection.Explicit(setOf(CalendarId(99)))), onSave = { saved = it })
        text("Select all available").performClick()
        compose.onNodeWithTag("widget_setup_save").performClick()
        assertEquals(WidgetCalendarSelection.Explicit(setOf(CalendarId(1), CalendarId(2), CalendarId(3), CalendarId(99))), saved?.selection)
    }
    @Test fun alreadyCheckedUnknownAndMissingChoicesArePreservedUntilExplicitRemoval() {
        var saved: WidgetSetupChoice? = null
        val initial = WidgetSetupChoice(WidgetCalendarSelection.Explicit(setOf(CalendarId(4), CalendarId(99))))
        show(choice = initial, onSave = { saved = it })
        node("widget_calendar_4").assertIsOn().assertIsEnabled()
        node("widget_missing_99").assertIsOn()
        compose.onNodeWithTag("widget_setup_save").performClick()
        assertEquals(initial, saved)
        node("widget_calendar_4").performClick().assertIsOff().assertIsNotEnabled()
        node("widget_missing_99").performClick()
        compose.onNodeWithTag("widget_setup_save").performClick()
        assertEquals(WidgetCalendarSelection.Explicit(emptySet()), saved?.selection)
    }
    @Test fun metadataFailureAndRefreshDoNotDropSavedIdsOrAdoptNewOnes() {
        var saved: WidgetSetupChoice? = null
        val metadata = mutableStateOf<CalendarReadState<List<CalendarInfo>>>(CalendarReadState.Unavailable)
        show(choice = WidgetSetupChoice(WidgetCalendarSelection.Explicit(setOf(CalendarId(99)))), metadata = metadata, onSave = { saved = it })
        node("widget_missing_99").assertIsOn()
        metadata.value = CalendarReadState.Ready(calendars())
        compose.waitForIdle()
        node("widget_missing_99").assertIsOn()
        node("widget_calendar_1").assertIsOff()
        compose.onNodeWithTag("widget_setup_save").performClick()
        assertEquals(WidgetCalendarSelection.Explicit(setOf(CalendarId(99))), saved?.selection)
    }
    @Test fun titlePrivacyIsExplicitAndFailedSaveKeepsSelectionAndPrivacy() {
        var saved: WidgetSetupChoice? = null
        var saves = 0
        val error = mutableStateOf<String?>(null)
        show(onSave = { saved = it; saves++; error.value = "Could not save. Try again." }, error = error)
        node("widget_setup_independent").performClick()
        node("widget_calendar_2").performClick()
        node("widget_privacy_titles").performClick().assertIsSelected()
        text("Event titles and calendar colors are visible on your home screen. Anyone who can see the widget can read them.").assertIsDisplayed()
        compose.onNodeWithTag("widget_setup_save").performClick()
        compose.onNodeWithText("Could not save. Try again.").assertIsDisplayed()
        node("widget_calendar_2").assertIsOn()
        node("widget_privacy_titles").assertIsSelected()
        compose.onNodeWithTag("widget_setup_save").performClick()
        assertEquals(2, saves)
        assertEquals(WidgetPrivacy.EVENT_TITLES, saved?.privacy)
        assertEquals(WidgetCalendarSelection.Explicit(setOf(CalendarId(2))), saved?.selection)
    }
    @Test fun savingDisablesChangesAndCancelWhileCancelOtherwiseNeverSaves() {
        var saves = 0
        var cancels = 0
        val saving = mutableStateOf(true)
        show(onSave = { saves++ }, onDismiss = { cancels++ }, saving = saving)
        compose.onNodeWithTag("widget_setup_save").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").assertIsNotEnabled()
        node("widget_setup_independent").assertIsNotEnabled()
        saving.value = false
        compose.waitForIdle()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, saves)
        assertEquals(1, cancels)
    }
    @Test fun metadataPermissionRecoveryOnlyUsesExplicitCallbacks() {
        var grants = 0
        var retries = 0
        show(data = CalendarReadState.PermissionRequired, onPermission = { grants++ }, onRetry = { retries++ })
        text("Allow calendar access").performClick()
        text("Retry").performClick()
        assertEquals(1, grants)
        assertEquals(1, retries)
    }
    @Test fun widgetIdentityChangeResetsFormWithoutCrossWidgetChoiceReuse() {
        val id = mutableStateOf(41)
        var saved: WidgetSetupChoice? = null
        show(widgetId = id, onSave = { saved = it })
        node("widget_setup_independent").performClick()
        node("widget_calendar_2").performClick()
        id.value = 42
        compose.waitForIdle()
        node("widget_setup_follow").assertIsSelected()
        compose.onNodeWithTag("widget_setup_save").performClick()
        assertEquals(WidgetSetupChoice(), saved)
    }
    @Test fun choiceDraftSurvivesRestorationWithoutCreatingConfiguration() {
        val restoration = StateRestorationTester(compose)
        var saved: WidgetSetupChoice? = null
        restoration.setContent { DaylightTheme {
            WidgetSetupSheet(41, WidgetSetupChoice(), CalendarReadState.Ready(calendars()), appSelection, { saved = it }, {}, {})
        } }
        node("widget_setup_independent").performClick()
        node("widget_calendar_2").performClick()
        node("widget_privacy_titles").performClick()
        restoration.emulateSavedInstanceStateRestore()
        node("widget_calendar_2").assertIsOn()
        node("widget_privacy_titles").assertIsSelected()
        assertNull(saved)
    }
    @Test @Config(qualifiers = "+notnight") fun widgetSetupFollowLight() {
        show()
        compose.onNodeWithTag("widget_setup_sheet").captureRoboImage("src/test/screenshots/widget_setup_follow_light.png")
    }
    @Test @Config(qualifiers = "+night") fun widgetSetupIndependentDark() {
        show(choice = WidgetSetupChoice(WidgetCalendarSelection.Explicit(setOf(CalendarId(1), CalendarId(99))), WidgetPrivacy.EVENT_TITLES))
        node("widget_missing_99").assertIsDisplayed()
        compose.onNodeWithTag("widget_setup_sheet").captureRoboImage("src/test/screenshots/widget_setup_independent_dark.png")
    }
    @Test @Config(qualifiers = "+notnight") fun widgetSetupLargeRtlKeepsPrivateChoiceReachable() {
        show(font = 2f, rtl = true)
        node("widget_privacy_busy").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithTag("widget_setup_sheet").captureRoboImage("src/test/screenshots/widget_setup_large_rtl.png")
        node("widget_setup_independent").assertIsDisplayed()
        compose.onNodeWithTag("widget_setup_save").assertIsDisplayed()
    }
}
