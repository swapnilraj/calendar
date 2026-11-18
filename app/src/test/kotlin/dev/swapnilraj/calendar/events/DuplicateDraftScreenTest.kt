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
class DuplicateDraftScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun copyNoticesRemainVisibleAndUnknownVisibilityRequiresChoiceBeforeSave() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val store = EventEditorControllerTest.Store(); val fixture = EventEditorControllerTest(); val writer = fixture.Writer()
            val calendar = CalendarInfo(CalendarId(8), "Personal", 1, CalendarAccount("fixture@example.test", "Fixture"),
                "fixture@example.test", ZoneOffset.UTC, 700, true, true, CalendarCapabilities(createEvents = AccessDecision.ALLOWED))
            val time = EventTime.Timed(Instant.parse("2028-06-15T09:00:00Z"), Instant.parse("2028-06-15T10:00:00Z"))
            val ref = EventRef(CalendarId(2), 42, time.start, time.end, RecurrenceRef(41, time.start))
            val details = EventDetails(EventOccurrence(ref, time, "Team planning", 1), null, eventZone = ZoneOffset.UTC,
                reminders = listOf(EventReminder(-1, 1), EventReminder(15, 2)), recurrenceRule = "FREQ=WEEKLY")
            val launch = (prepareDuplicateDraft(details, calendar.id, "copy-ui", store) as DraftPreparationResult.Ready).launch
            val controller = EventEditorController(launch.seed(LocalDate.parse("2040-01-01"), ZoneOffset.UTC), null,
                launch.draftId, writer, store, scope, requireStoredDraft = true)
            compose.setContent { DaylightTheme { Surface(Modifier.fillMaxSize()) {
                EventEditorContent(controller, listOf(calendar), {}, {}, {})
            } } }
            compose.onNodeWithText("Guests are not copied.").assertIsDisplayed()
            compose.onNodeWithText("Recurrence is not copied. This draft contains only the displayed occurrence.").assertIsDisplayed()
            compose.onRoot().captureRoboImage("src/test/screenshots/events_duplicate_notice_light.png")
            compose.onNodeWithText("Save").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("Visibility: Not provided").performScrollTo().performClick()
            compose.onNodeWithText("Private").performClick()
            compose.onNodeWithText("Save").performScrollTo().assertIsEnabled().performClick()
            assertEquals(1, writer.creates); assertEquals(2, writer.createdDrafts.single().privacy)
            assertEquals(0, writer.updates); assertEquals(0, writer.deletes)
        } finally { scope.cancel() }
    }
}
