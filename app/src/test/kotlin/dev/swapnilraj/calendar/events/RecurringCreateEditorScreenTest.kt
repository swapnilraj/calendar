package dev.swapnilraj.calendar.events

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w393dp-h851dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecurringCreateEditorScreenTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val store = EventEditorControllerTest.Store()
    private val writer = EventEditorControllerTest().Writer()
    private val initial = EventDraft(CalendarId(2), EventTime.Timed(Instant.parse("2028-06-15T09:00:00Z"), Instant.parse("2028-06-15T10:00:00Z")),
        ZoneOffset.UTC, "Recurring fixture", targetIdentity = eventTestIdentity())
    private fun editor(enabled: Boolean) = preparedEditor(initial, null, "recurring", writer, store, scope,
        Clock.fixed(Instant.parse("2026-11-18T00:00:00Z"), ZoneOffset.UTC), recurringCreationSupported = enabled)
    private fun show(controller: EventEditorController) {
        compose.setContent { DaylightTheme { Surface(Modifier.fillMaxSize()) {
            EventEditorContent(controller, listOf(eventTestCalendar()), {}, {}, {})
        } } }
    }
    @After fun stop() { scope.cancel() }

    @Test fun repeatControlsAreAbsentUntilVerifiedWriterIsEnabled() {
        show(editor(false))
        compose.onNodeWithText("Repeat", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Does not repeat").assertDoesNotExist()
        assertEquals(0, writer.creates)
    }
    @Test fun nativeDailyChoiceRequiresReviewBeforeSingleTypedCreate() {
        val controller = editor(true); writer.next = MutationResult.Uncertain("fixture", null); show(controller)
        compose.onNodeWithText("Daily").performScrollTo().performClick()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        compose.onNodeWithText("Review recurring event").assertIsDisplayed()
        compose.onNodeWithText("Every 1 day").assertIsDisplayed()
        compose.onNodeWithText("First expected occurrences").performScrollTo().assertIsDisplayed()
        assertEquals(0, writer.creates)
        compose.onNodeWithText("Create recurring event").performClick()
        compose.waitForIdle()
        assertEquals(1, writer.creates); assertEquals(RecurrenceSpec(RecurrencePattern.Daily), writer.createdDrafts.single().recurrence)
        compose.onNodeWithText("Review recurring event").assertDoesNotExist()
    }
    @Test fun recurringReviewShowsAuthoredRuleAndExpectedPrefix() {
        val controller = editor(true)
        controller.change { it.copy(title = "Weekly planning", recurrence = RecurrenceEditorFields(frequency = "WEEKLY",
            weekdays = listOf("THURSDAY"), endMode = "COUNT", count = "3")) }
        controller.save(); show(controller)
        compose.onNodeWithText("Review recurring event").assertIsDisplayed()
        compose.onNodeWithText("Weekly on Thursday").assertIsDisplayed()
        compose.onNodeWithText("After 3 occurrences").assertIsDisplayed()
        compose.onNode(isDialog()).captureRoboImage("src/test/screenshots/events_create_recurrence_review_light.png")
        assertEquals(0, writer.creates)
    }

    @Test fun restoredPartialRepeatInputsRemainVisibleAndCannotSubmit() = runBlocking<Unit> {
        val first = editor(true); first.change { it.copy(recurrence = RecurrenceEditorFields(frequency = "WEEKLY", interval = "", weekdays = emptyList())) }; first.flushDraft()
        val restored = editor(true); show(restored)
        compose.onNodeWithText("Repeat every (1–1000)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        assertEquals(0, writer.creates); assertNull(restored.state.recurrenceReview)
        assertEquals("", restored.state.fields.recurrence.interval); assertTrue(restored.state.fields.recurrence.weekdays.isEmpty())
    }
}
