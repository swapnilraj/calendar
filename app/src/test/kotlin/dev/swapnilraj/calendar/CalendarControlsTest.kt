package dev.swapnilraj.calendar

import android.provider.CalendarContract
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.calendars.*
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
import java.time.ZoneOffset
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
class CalendarControlsTest {
    @get:Rule val compose = createComposeRule()
    private val google = CalendarAccount("alex@example.com", "com.google")
    private val sameNameOtherType = CalendarAccount("alex@example.com", "com.android.exchange")
    private val work = CalendarAccount("alex@work.example", "com.google")
    private val writable = CalendarCapabilities(AccessDecision.ALLOWED, AccessDecision.ALLOWED, AccessDecision.ALLOWED, AccessDecision.ALLOWED)
    private val readOnly = CalendarCapabilities(AccessDecision.ALLOWED, AccessDecision.DENIED, AccessDecision.DENIED, AccessDecision.DENIED)
    private fun fixtures(): List<CalendarInfo> = listOf(
        CalendarInfo(CalendarId(1), "Events", 0xff397d70.toInt(), google, google.name, ZoneOffset.UTC,
            CalendarContract.Calendars.CAL_ACCESS_OWNER, true, true, writable),
        CalendarInfo(CalendarId(2), "Holidays", 0xffb06a47.toInt(), google, google.name, ZoneOffset.UTC,
            CalendarContract.Calendars.CAL_ACCESS_READ, true, true, readOnly),
        CalendarInfo(CalendarId(3), "Events", 0xff8a638a.toInt(), work, work.name, ZoneOffset.UTC,
            CalendarContract.Calendars.CAL_ACCESS_FREEBUSY, false, false,
            readOnly.copy(readDetails = AccessDecision.DENIED)),
        CalendarInfo(CalendarId(4), "Events", 0xff526e9e.toInt(), sameNameOtherType, sameNameOtherType.name,
            ZoneOffset.UTC, null, true, true),
        CalendarInfo(CalendarId(5), "Revoked calendar", 0xff746944.toInt(), work, work.name, ZoneOffset.UTC,
            CalendarContract.Calendars.CAL_ACCESS_NONE, true, true,
            readOnly.copy(readDetails = AccessDecision.DENIED)),
    )
    private fun show(calendars: List<CalendarInfo> = fixtures(), initial: CalendarSelection = CalendarSelection.ProviderVisible,
        initialDefault: CalendarId? = CalendarId(1), fontScale: Float = 1f,
        direction: LayoutDirection = LayoutDirection.Ltr, onSelection: (CalendarSelection) -> Unit = {},
        onDefault: (CalendarId?) -> Unit = {}, onDismiss: () -> Unit = {}) {
        compose.setContent {
            var selection by remember { mutableStateOf(initial) }
            var defaultCalendar by remember { mutableStateOf(initialDefault) }
            DaylightTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale),
                    LocalLayoutDirection provides direction) {
                    Surface(Modifier.fillMaxSize()) {
                        CalendarDrawerSheet(calendars, selection, defaultCalendar,
                            onSelectionChanged = { selection = it; onSelection(it) },
                            onDefaultCalendarChanged = { defaultCalendar = it; onDefault(it) }, onDismiss)
                    }
                }
            }
        }
        compose.waitForIdle()
    }
    private fun drawerNode(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("calendar_drawer_list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }
    @Test fun providerVisibleFlagsAreInitialDefaultsAndExplicitEmptyMeansNone() {
        assertEquals(setOf(CalendarId(1), CalendarId(2), CalendarId(4)), selectedCalendarIds(fixtures(), CalendarSelection.ProviderVisible))
        assertTrue(selectedCalendarIds(fixtures(), CalendarSelection.Explicit(emptySet())).isEmpty())
        assertEquals(CalendarSelection.Explicit(setOf(CalendarId(99), CalendarId(1))),
            toggledCalendarSelection(fixtures(), CalendarSelection.Explicit(setOf(CalendarId(99))), CalendarId(1), true))
    }
    @Test fun duplicateCalendarNamesAndAccountNamesKeepIndependentIdsAndGroups() {
        var changed: CalendarSelection? = null
        show(onSelection = { changed = it })
        drawerNode("calendar_account_16:alex@example.com:com.android.exchange").performScrollTo().assertIsDisplayed()
        drawerNode("calendar_account_16:alex@example.com:com.google").performScrollTo().assertIsDisplayed()
        drawerNode("calendar_toggle_1").performScrollTo().assertIsOn().performClick()
        assertEquals(CalendarSelection.Explicit(setOf(CalendarId(2), CalendarId(4))), changed)
        compose.onNodeWithTag("calendar_toggle_2").assertIsOn()
        drawerNode("calendar_toggle_4").performScrollTo().assertIsOn()
    }
    @Test fun selectNoneAndAllEmitExplicitChoicesWithoutSelectingNoAccessCalendars() {
        var changed: CalendarSelection? = null
        show(onSelection = { changed = it })
        compose.onNodeWithText("Select none").performClick()
        assertEquals(CalendarSelection.Explicit(emptySet()), changed)
        compose.onNodeWithText("No calendars selected").assertIsDisplayed()
        compose.onNodeWithText("Select all").performClick()
        assertEquals(CalendarSelection.Explicit(setOf(CalendarId(1), CalendarId(2), CalendarId(3), CalendarId(4))), changed)
    }
    @Test fun readOnlyAndUnknownCalendarsCannotBecomeCreationDefaults() {
        var changes = 0
        show(onDefault = { changes++ })
        drawerNode("calendar_default_2").performScrollTo().assertIsNotEnabled()
        drawerNode("calendar_default_3").performScrollTo().assertIsNotEnabled()
        drawerNode("calendar_default_4").performScrollTo().assertIsNotEnabled()
        drawerNode("calendar_default_1").performScrollTo().assertIsEnabled().assertIsSelected()
        assertEquals(0, changes)
    }
    @Test fun displayFilteringDoesNotChangeTheCreationDefault() {
        var defaults = 0
        show(onDefault = { defaults++ })
        drawerNode("calendar_toggle_1").performScrollTo().performClick()
        drawerNode("calendar_default_1").performScrollTo().assertIsSelected()
        assertEquals(0, defaults)
        drawerNode("calendar_default_none").performScrollTo().performClick().assertIsSelected()
        assertEquals(1, defaults)
    }
    @Test fun creationDefaultCallbackUsesTheCalendarIdAndCanBeCleared() {
        var changed: CalendarId? = CalendarId(99)
        show(initialDefault = null, onDefault = { changed = it })
        drawerNode("calendar_default_1").performScrollTo().performClick()
        assertEquals(CalendarId(1), changed)
        drawerNode("calendar_default_none").performScrollTo().performClick()
        assertNull(changed)
    }
    @Test fun missingDefaultIsExplainedWithoutAutomaticallyChangingPreferences() {
        var changes = 0
        show(initialDefault = CalendarId(99), onDefault = { changes++ })
        compose.onNodeWithTag("calendar_drawer_list").performScrollToNode(hasText("Saved default calendar is unavailable."))
        compose.onNodeWithText("Saved default calendar is unavailable.").assertIsDisplayed()
        assertEquals(0, changes)
    }
    @Test fun noCalendarAndNoSelectionAreDifferentStates() {
        show(calendars = emptyList(), initial = CalendarSelection.Explicit(emptySet()), initialDefault = null)
        compose.onNodeWithText("No calendars available").assertIsDisplayed()
        compose.onNodeWithText("No calendars selected").assertDoesNotExist()
    }
    @Test fun hiddenSyncDisabledAndFreeBusyContextDoesNotPreventAppLocalSelection() {
        var changed: CalendarSelection? = null
        show(onSelection = { changed = it })
        drawerNode("calendar_toggle_3").assert(hasText("Availability only", substring = true))
            .performScrollTo().assertIsOff().performClick()
        assertTrue(CalendarId(3) in (changed as CalendarSelection.Explicit).ids)
        compose.onNode(hasTestTag("calendar_toggle_3") and hasText("Hidden in device provider", substring = true)).assertIsOn()
        compose.onNode(hasTestTag("calendar_toggle_3") and hasText("Sync is off", substring = true)).assertIsOn()
    }
    @Test fun revokedCalendarCanBeDeselectedButCannotBeNewlySelected() {
        show(initial = CalendarSelection.Explicit(setOf(CalendarId(5))))
        drawerNode("calendar_toggle_5").performScrollTo().assertIsEnabled().assertIsOn().performClick()
        compose.onNodeWithTag("calendar_toggle_5").assertIsOff().assertIsNotEnabled()
    }
    @Test fun closeUsesTheProvidedDismissAction() {
        var closed = false
        show(onDismiss = { closed = true })
        compose.onNodeWithText("Close").performClick()
        assertTrue(closed)
    }
    @Test fun chooserOnlyEnablesImplementedSuppliedViews() {
        var chosen: CalendarViewOption? = null
        compose.setContent {
            DaylightTheme {
                CalendarViewChooser(listOf(CalendarViewOption.DAY, CalendarViewOption.MONTH), CalendarViewOption.DAY, { chosen = it })
            }
        }
        compose.onNodeWithContentDescription("Change view. Current view: Day").performClick()
        compose.onNodeWithText("Week").assertDoesNotExist()
        compose.onNodeWithText("Agenda").assertDoesNotExist()
        compose.onNodeWithText("3 days").assertDoesNotExist()
        compose.onNodeWithText("Month").performClick()
        assertEquals(CalendarViewOption.MONTH, chosen)
    }
    @Test fun chooserWithoutImplementedViewsHasNoEnabledAction() {
        compose.setContent {
            DaylightTheme { CalendarViewChooser(emptyList(), CalendarViewOption.DAY, {}) }
        }
        compose.onNodeWithContentDescription("Change view. Current view: No views available").assertIsNotEnabled()
    }
    @Test @Config(qualifiers = "+notnight") fun drawerLight() {
        show()
        compose.onRoot().captureRoboImage("src/test/screenshots/calendars_drawer_light.png")
    }
    @Test @Config(qualifiers = "+night") fun drawerDark() {
        show()
        compose.onRoot().captureRoboImage("src/test/screenshots/calendars_drawer_dark.png")
    }
    @Test @Config(qualifiers = "+notnight") fun largeFontRtlKeepsDefaultChoicesReachable() {
        show(fontScale = 2f, direction = LayoutDirection.Rtl)
        compose.onRoot().captureRoboImage("src/test/screenshots/calendars_drawer_large_rtl.png")
        drawerNode("calendar_default_1").performScrollTo().assertIsDisplayed().assertIsSelected()
        drawerNode("calendar_toggle_3").performScrollTo().assertIsDisplayed()
    }
}
