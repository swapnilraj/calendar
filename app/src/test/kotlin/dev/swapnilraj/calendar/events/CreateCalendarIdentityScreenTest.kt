package dev.swapnilraj.calendar.events

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.*
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
import java.time.*
import kotlinx.coroutines.*
import org.json.JSONObject
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
class CreateCalendarIdentityScreenTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val a = eventTestCalendar().copy(displayName = "Work", account = CalendarAccount("original@example.test", "Work"), providerId = "original-calendar")
    private val b = a.copy(displayName = "Personal", account = CalendarAccount("replacement@example.test", "Personal"), owner = "replacement@example.test", providerId = "replacement-calendar")
    private val initial = EventDraft(a.id, EventTime.Timed(Instant.parse("2028-06-15T09:00:00Z"), Instant.parse("2028-06-15T10:00:00Z")),
        ZoneOffset.UTC, "Retained appointment", privacy = 2, targetIdentity = a.identity)
    private val fixture = EventEditorControllerTest(); private val writer = fixture.Writer(); private val store = EventEditorControllerTest.Store()
    @After fun stop() { scope.cancel() }
    @Test fun reusedIdRequiresExplicitNewAccountSelectionAndRetainsDraftFields() = runBlocking<Unit> {
        prepareNewDraft(initial, "namespace-ui", true, store)
        val controller = EventEditorController(initial, null, "namespace-ui", writer, store, scope, requireStoredDraft = true)
        var available by mutableStateOf(listOf(a))
        compose.setContent { DaylightTheme { Surface(Modifier.fillMaxSize()) { EventEditorContent(controller, available, {}, {}, {}) } } }
        compose.onNodeWithText("Work · original@example.test").assertIsSelected()
        compose.runOnIdle { available = listOf(b) }; compose.waitForIdle()
        compose.onNodeWithText("Reselect the destination calendar to verify its account before saving. The draft fields are retained.").assertIsDisplayed()
        compose.onNodeWithText(initial.title).assertIsDisplayed()
        compose.onNodeWithText("Personal · replacement@example.test").assertIsNotSelected()
        compose.onNodeWithText("Save").assertIsNotEnabled(); assertTrue(writer.createdDrafts.isEmpty())
        compose.onRoot().captureRoboImage("src/test/screenshots/events_calendar_namespace_review_light.png")
        compose.onNodeWithText("Personal · replacement@example.test").performClick().assertIsSelected()
        assertEquals(b.identity, controller.state.fields.targetIdentity)
        compose.onNodeWithText("Save").performScrollTo().assertIsEnabled().performClick()
        assertEquals(b.identity, writer.createdDrafts.single().targetIdentity)
    }
    @Test fun submittedLegacyRequestShowsReadOnlyDestinationWarningAndNeverCurrentReplacementAccount() = runBlocking<Unit> {
        prepareNewDraft(initial, "namespace-ui", true, store)
        val old = store.records.getValue("namespace-ui"); val raw = JSONObject(old.payload)
        raw.put("submitted", true).put("operationKind", "create").remove("targetIdentity")
        raw.put("submission", FrozenEditorSubmission.Create(raw.getString("operationId"), initial.copy(targetIdentity = null)).encode())
        store.records["namespace-ui"] = old.copy(payload = raw.toString())
        val controller = EventEditorController(initial.copy(targetIdentity = b.identity), null, "namespace-ui", writer, store, scope,
            recoveryOnly = true, requireStoredDraft = true)
        compose.setContent { DaylightTheme { Surface(Modifier.fillMaxSize()) { EventEditorContent(controller, listOf(b), {}, {}, {}) } } }
        compose.onNodeWithText("The original request has no verified destination calendar binding. Its outcome can be checked, but retrying it is disabled. No current calendar account will be substituted.").assertIsDisplayed()
        compose.onNodeWithText("Original calendar unavailable").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("replacement@example.test", substring = true).assertCountEquals(0)
        compose.onNodeWithText("Save").performScrollTo().assertIsNotEnabled()
        writer.safeRecovery = true; controller.recover(); compose.waitForIdle()
        assertFalse(controller.state.retryReady); assertTrue(writer.createdDrafts.isEmpty())
        assertEquals(old.copy(payload = raw.toString()), store.records.getValue("namespace-ui"))
    }
}
