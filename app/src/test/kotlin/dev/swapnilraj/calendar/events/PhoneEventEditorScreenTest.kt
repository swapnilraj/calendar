package dev.swapnilraj.calendar.events

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.*
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
import java.time.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w393dp-h851dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneEventEditorScreenTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val store = EventEditorControllerTest.Store()
    private val writer = EventEditorControllerTest().Writer()
    private val draft = EventDraft(CalendarId(2), EventTime.Timed(Instant.parse("2028-06-15T09:00:00Z"), Instant.parse("2028-06-15T10:00:00Z")), ZoneOffset.UTC,
        "Planning for next week", location = "Garden room", alertMinutes = listOf(10), targetIdentity = eventTestIdentity())
    private fun editor() = preparedEditor(draft, null, "phone", writer, store, scope, recurringCreationSupported = true)
    private fun show(controller: EventEditorController, dark: Boolean = false, largeRtl: Boolean = false, onDismiss: () -> Unit = {}, onSaved: (EventRef) -> Unit = {}, onReplacedLaunch: ((EditorDraftLaunch) -> Unit)? = null) {
        RuntimeEnvironment.setFontScale(if (largeRtl) 2f else 1f)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (largeRtl) 2f else density.fontScale), LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                DaylightTheme(dark = dark) { PhoneEventEditor(controller, listOf(eventTestCalendar()), onDismiss, onSaved, {}, onReplacedLaunch = onReplacedLaunch) }
            }
        }
        compose.waitForIdle()
    }
    @After fun stop() { scope.cancel(); RuntimeEnvironment.setFontScale(1f) }
    @Test fun normalDraftReplacementIsVisibleAndRequiresConfirmationBeforePublishingFreshLaunch() {
        var replacement: EditorDraftLaunch? = null
        val controller = editor(); val oldGeneration = controller.generation
        show(controller, onReplacedLaunch = { replacement = it })
        compose.onNodeWithText("Start a new draft").performScrollTo().assertIsDisplayed().performClick()
        compose.waitForIdle(); assertNull(replacement); assertEquals(oldGeneration, controller.generation); assertEquals(0,writer.creates)
        compose.onNodeWithText("Stop any pending original change, then keep these fields in a new draft. Choose a calendar again and review its visibility before tapping Save.").assertIsDisplayed()
        compose.onNodeWithText("Confirm").performClick()
        compose.waitUntil(5_000) { replacement != null }
        assertNotEquals(oldGeneration,replacement!!.generation); assertNotEquals("phone",replacement!!.draftId)
        assertEquals(0,writer.creates)
    }
    @Test fun toolbarSaveRemainsVisibleAfterScrollingAndOnlyExplicitSaveWrites() {
        var saved: EventRef? = null; val controller = editor(); show(controller, onSaved = { saved = it })
        compose.onNodeWithText("Saving updates this device. Your calendar account handles sync.").performScrollTo()
        compose.onNodeWithText("Save").assertIsDisplayed().performClick(); compose.waitForIdle()
        assertEquals(1, writer.creates); assertNotNull(saved); assertTrue(store.records.isEmpty())
    }
    @Test fun textDateFallbackRetainsPartialEntryAcrossCloseAndReopenWithoutSavingEvent() {
        val controller = editor(); var closed = false; show(controller, onDismiss = { closed = true })
        compose.onNodeWithContentDescription("Starts: Thursday, June 15, 2028").performClick()
        compose.onNodeWithText("Enter as text").performClick()
        compose.onNodeWithText("Date (YYYY-MM-DD)").performTextReplacement("2028-")
        compose.onNodeWithText("Done").performClick(); compose.waitForIdle()
        assertEquals("2028-", controller.state.fields.startDate)
        compose.onNodeWithContentDescription("Close event editor").performClick(); compose.waitForIdle()
        assertTrue(closed); assertEquals(0, writer.creates)
        val restored = editor(); assertEquals("2028-", restored.state.fields.startDate)
        restored.save(); assertEquals(0, writer.creates)
    }
    @Test fun allDayRowsShowInclusiveDatesAndSaveExclusiveUtcRange() {
        val range = CalendarDateRange(LocalDate.parse("2028-06-15"), LocalDate.parse("2028-06-18"))
        val controller = preparedEditor(draft.copy(time = EventTime.AllDay(range)), null, "all-day", writer, store, scope)
        show(controller)
        compose.onNodeWithContentDescription("Ends: Saturday, June 17, 2028").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Starts: 09:00").assertDoesNotExist()
        compose.onNodeWithText("Time zone").assertDoesNotExist()
        compose.onNodeWithText("Save").assertIsDisplayed().performClick()
        assertEquals(EventTime.AllDay(range), writer.createdDrafts.single().time)
        assertEquals(ZoneOffset.UTC, writer.createdDrafts.single().eventZone)
    }
    @Test fun failedDraftFlushKeepsFullScreenOpenAndRawInputReachable() {
        val controller = editor(); controller.change { it.copy(title = "Keep unfinished input") }; store.failWrites = true
        var closed = false; show(controller, onDismiss = { closed = true })
        compose.onNodeWithContentDescription("Close event editor").performClick(); compose.waitForIdle()
        assertFalse(closed)
        compose.onNodeWithText("Your draft could not be saved on this device. Keep this screen open and try again.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Title").assertExists()
        store.failWrites = false
        compose.onNodeWithContentDescription("Close event editor").performClick(); compose.waitForIdle()
        assertTrue(closed); assertEquals(0, writer.creates)
        assertEquals("Keep unfinished input", editor().state.fields.title)
    }
    @Test fun openingAndCancellingTimePickerPreservesExplicitOverlapOffset() {
        val controller = editor()
        controller.change { it.copy(zone = "Europe/London", startDate = "2026-10-25", endDate = "2026-10-25", startTime = "01:30", endTime = "02:30") }
        controller.change { it.copy(startOffset = "+01:00") }; show(controller)
        compose.onNodeWithContentDescription("Starts: 01:30").performScrollTo().performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals("+01:00", controller.state.fields.startOffset); assertEquals("01:30", controller.state.fields.startTime)
        assertEquals(0, writer.creates)
    }
    @Test fun unchangedMinutePickerPreservesVisibleSourceSecondsAndMilliseconds() {
        val precise = draft.copy(time = EventTime.Timed(Instant.parse("2028-06-15T09:00:30.123Z"), Instant.parse("2028-06-15T10:00:30.456Z")))
        val controller = preparedEditor(precise, null, "precise", writer, store, scope)
        show(controller)
        compose.onNodeWithContentDescription("Starts: 09:00:30.123").performScrollTo().performClick()
        compose.onNodeWithText("Done").performClick(); compose.waitForIdle()
        assertEquals("09:00:30.123", controller.state.fields.startTime)
        assertEquals(precise.time, EventEditorController.normalizedDraft(controller.state.fields).time)
        assertEquals(0, writer.creates)
    }
    @Test fun largeTextTimePickerKeepsTextFallbackAndDoneReachable() {
        val controller = editor(); show(controller, largeRtl = true)
        compose.onNodeWithContentDescription("Starts: 09:00").performScrollTo().performClick()
        compose.onNodeWithText("Enter as text").assertIsDisplayed().performClick()
        compose.onNodeWithText("Time (HH:MM)").assertIsDisplayed()
        compose.onNodeWithText("Done").assertIsDisplayed().performClick()
        assertEquals("09:00", controller.state.fields.startTime); assertEquals(0, writer.creates)
    }
    @Test fun notificationRowsUseHumanLabelsAndAddingOneDoesNotSaveAnEvent() {
        val controller = editor(); show(controller)
        compose.onNodeWithText("10 minutes before").performScrollTo().performClick()
        compose.onNodeWithText("1 day before").performScrollTo().performClick()
        assertEquals(listOf(10,1440), PhoneEventEditorText.alerts(controller.state.fields.alerts)); assertEquals(0, writer.creates)
        compose.onNodeWithContentDescription("Remove notification: 10 minutes before").performScrollTo().performClick()
        assertEquals("1440", controller.state.fields.alerts)
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("1 day before").assertExists(); compose.onNodeWithText("Save").assertIsDisplayed()
    }
    @Test fun repeatSummaryOpensFocusedTypedSettingsAndRetainsUnfinishedValues() {
        val controller = editor(); show(controller)
        compose.onNodeWithText("Does not repeat").performScrollTo().performClick()
        compose.onNodeWithText("Weekly").performClick()
        compose.onNodeWithText("Repeat every (1–1000)").performScrollTo().performTextReplacement("")
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Finish repeat settings").performScrollTo().assertIsDisplayed()
        assertEquals("", controller.state.fields.recurrence.interval); assertEquals(0, writer.creates)
        controller.save(); assertNull(controller.state.recurrenceReview); assertEquals(0, writer.creates)
    }
    @Test fun frozenRecoveryUsesUserFacingActionsAndNeverEnablesTopSave() {
        writer.next = MutationResult.PermissionRequired; val controller = editor(); controller.save(); show(controller)
        compose.onNodeWithText("Check save result").assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Allow calendar access, then check the original result. Your pending change is kept unchanged.").assertIsDisplayed()
        compose.onNodeWithText("Allow calendar access, then tap Save again.").assertDoesNotExist()
        compose.onAllNodesWithText("journal", substring = true, ignoreCase = true).assertCountEquals(0)
        compose.onAllNodesWithText("Retire", substring = true, ignoreCase = true).assertCountEquals(0)
        compose.onAllNodesWithText("Add people").assertCountEquals(0)
        assertEquals(1, writer.creates)
    }
    @Test fun phoneEditorLight() { show(editor()); compose.onNode(isDialog()).captureRoboImage("src/test/screenshots/phone_event_editor_light.png") }
    @Test fun phoneEditorDark() { show(editor(), dark = true); compose.onNode(isDialog()).captureRoboImage("src/test/screenshots/phone_event_editor_dark.png") }
    @Test fun largeRtlToolbarStaysReachable() {
        show(editor(), largeRtl = true)
        compose.onNodeWithText("Save").assertIsDisplayed(); compose.onNodeWithContentDescription("Close event editor").assertIsDisplayed()
        compose.onNode(isDialog()).captureRoboImage("src/test/screenshots/phone_event_editor_rtl_large.png")
        compose.onNodeWithText("Garden room").performScrollTo(); compose.onNodeWithText("Save").assertIsDisplayed()
    }
}
