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
class EventEditorMetadataScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun metadataChoicesUseFriendlyLabelsAndExplicitTypedValues() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val fixture = EventEditorControllerTest(); val writer = fixture.Writer()
            val calendar = CalendarInfo(CalendarId(2), "Personal", 1, CalendarAccount("fixture@example.test", "Fixture"),
                "fixture@example.test", ZoneOffset.UTC, 700, true, true, CalendarCapabilities(createEvents = AccessDecision.ALLOWED))
            val initial = EventDraft(calendar.id, EventTime.Timed(Instant.parse("2028-06-15T09:00:00Z"), Instant.parse("2028-06-15T10:00:00Z")),
                ZoneOffset.UTC, "Private appointment", availability = 0, privacy = 2, targetIdentity = calendar.identity)
            val controller = preparedEditor(initial, null, "metadata-ui", writer, EventEditorControllerTest.Store(), scope)
            compose.setContent { DaylightTheme { Surface(Modifier.fillMaxSize()) {
                EventEditorContent(controller, listOf(calendar), {}, {}, {})
            } } }
            compose.onNodeWithText("Visibility: Private").performScrollTo().performClick()
            compose.onNodeWithText("Calendar default").performClick()
            assertEquals("0", controller.state.fields.privacy)
            compose.onNodeWithText("Visibility: Calendar default").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Availability: Busy").assertIsDisplayed()
            compose.onRoot().captureRoboImage("src/test/screenshots/events_editor_metadata_light.png")
            compose.onNodeWithText("Save").performScrollTo().performClick()
            assertEquals(0, writer.createdDrafts.single().privacy)
        } finally { scope.cancel() }
    }
}
