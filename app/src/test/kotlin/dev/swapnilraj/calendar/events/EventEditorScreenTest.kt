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
class EventEditorScreenTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val calendar = CalendarInfo(CalendarId(2), "Personal", 1, CalendarAccount("fixture@example.test", "Fixture"),
        "fixture@example.test", ZoneOffset.UTC, 700, true, true, CalendarCapabilities(createEvents = AccessDecision.ALLOWED))
    private val draft = EventDraft(calendar.id, EventTime.Timed(Instant.parse("2028-06-15T09:00:00Z"),
        Instant.parse("2028-06-15T10:00:00Z")), ZoneOffset.UTC, "Future appointment", alertMinutes = listOf(10), targetIdentity = calendar.identity)
    private val ref = EventRef(calendar.id, 42, (draft.time as EventTime.Timed).start, (draft.time as EventTime.Timed).end)
    private var writes = 0
    private var writeResult: MutationResult? = null
    private var proveSafe = false
    private val writer = object : CalendarMutations {
        override suspend fun loadForEdit(ref: EventRef): EditReadResult = EditReadResult.Unavailable
        override suspend fun create(operationId: String, draft: EventDraft): MutationResult { writes++; return writeResult ?: MutationResult.LocalSaved(ref) }
        override suspend fun update(operationId: String, ref: EventRef, token: EditToken, patch: EventPatch): MutationResult { writes++; return writeResult ?: MutationResult.LocalSaved(ref) }
        override suspend fun delete(operationId: String, ref: EventRef, token: EditToken): MutationResult { writes++; return writeResult ?: MutationResult.LocalDeleted(ref) }
        override suspend fun recover(operationId: String): MutationResult = if (proveSafe) MutationResult.SafeToRetry(operationId) else MutationResult.Uncertain(operationId, null)
    }
    private fun controller() = preparedEditor(draft, null, "fixture-draft", writer, EventEditorControllerTest.Store(), scope)
    private fun show(controller: EventEditorController, close: () -> Unit = {}) {
        compose.setContent { DaylightTheme { Surface(Modifier.fillMaxSize()) {
            EventEditorContent(controller, listOf(calendar), close, {}, {})
        } } }
        compose.waitForIdle()
    }
    @After fun stop() { scope.cancel() }
    @Test fun failedFlushKeepsModalOpenUntilStorageRecovers() {
        val store = EventEditorControllerTest.Store().apply { failWrites = true }
        val controller = preparedEditor(draft, null, "fixture-draft", writer, store, scope)
        var closed = false
        compose.setContent { DaylightTheme {
            EventEditorSheet(controller, listOf(calendar), { closed = true }, {}, {}, {})
        } }
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        compose.waitForIdle()
        assertFalse(closed)
        assertEquals(0, writes)
        compose.onNodeWithText("The draft could not be read or stored. Retry after restoring local storage access.")
            .performScrollTo().assertIsDisplayed()
        store.failWrites = false
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(closed)
    }
    @Test fun restoredStaleDraftShowsLatestAndDisablesSave() = runBlocking {
        val store = EventEditorControllerTest.Store()
        val oldDraft = draft.copy(location = "Original location")
        val oldSource = EditableEvent(EventDetails(EventOccurrence(ref, draft.time, draft.title, 1), calendar,
            location = "Original location"), EditToken("old-source"), EditableField.entries.toSet())
        val old = preparedEditor(oldDraft, oldSource, "fixture-draft", writer, store, scope)
        old.change { it.copy(title = "My draft title") }; old.flushDraft()
        val fresh = oldSource.copy(details = oldSource.details.copy(location = "Remote location"), token = EditToken("fresh-source"))
        val restored = preparedEditor(draft.copy(location = "Remote location"), fresh, "fixture-draft", writer, store, scope)
        show(restored)
        compose.onNodeWithText("Location: Remote location").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("My draft title").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Save").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Delete event").assertIsNotEnabled()
        assertEquals(0, writes)
    }

    @Test fun browsedDateIsPrefilledAndCancelDoesNotWrite() {
        var closed = false
        val controller = controller(); show(controller) { closed = true }
        compose.onAllNodesWithText("2028-06-15").assertCountEquals(2)
        compose.onNodeWithText("09:00").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        assertTrue(closed); assertEquals(0, writes)
    }
    @Test fun invalidInputDisplaysRecoverableErrorWithoutWrite() {
        val controller = controller(); show(controller)
        compose.onNodeWithText("09:00").performTextReplacement("not a time")
        compose.onNodeWithText("Save").performScrollTo().performClick()
        compose.onNodeWithText("Check the calendar, dates, time zone, duration, and notification values.").performScrollTo().assertIsDisplayed()
        assertEquals(0, writes)
    }
    @Test fun allDayUsesLastDateLabelAndHidesClockFields() {
        val controller = controller(); show(controller)
        compose.onNodeWithText("All day").performClick()
        compose.onNodeWithText("Last all-day date (YYYY-MM-DD)").assertIsDisplayed()
        compose.onNodeWithText("Start time (HH:MM)").assertDoesNotExist()
    }
    @Test fun nativeDatePickerSelectionRetainsTheBrowsedDate() {
        show(controller())
        compose.onAllNodesWithText("Choose")[0].performClick()
        compose.onNodeWithText("Use date").performClick()
        compose.onAllNodesWithText("2028-06-15").assertCountEquals(2)
        assertEquals(0, writes)
    }
    @Test fun retryActionRequiresExplicitClickAndLeavesFieldsLocked() {
        writeResult = MutationResult.Uncertain("fixture", null)
        val editor = controller(); editor.save(); proveSafe = true; editor.recover(); show(editor)
        assertEquals(1, writes)
        compose.onNodeWithText("Future appointment").assertIsNotEnabled()
        compose.onNodeWithText("Save").performScrollTo().assertIsNotEnabled()
        writeResult = MutationResult.LocalSaved(ref)
        compose.onNodeWithText("Retry original save").performScrollTo().performClick()
        assertEquals(2, writes)
    }
    @Test fun originalDeletionRetryRequiresASecondExplicitConfirmation() {
        val source = EditableEvent(EventDetails(EventOccurrence(ref, draft.time, draft.title, 1), calendar),
            EditToken("original-token"), EditableField.entries.toSet())
        val editor = preparedEditor(draft, source, "fixture-delete", writer, EventEditorControllerTest.Store(), scope)
        writeResult = MutationResult.Uncertain("fixture", null)
        editor.requestDelete(); editor.confirmDelete(); proveSafe = true; editor.recover(); show(editor)
        assertEquals(1, writes)
        compose.onNodeWithText("Retry original deletion").performScrollTo().performClick()
        compose.onNodeWithText("Retry deletion of the original event?").assertIsDisplayed()
        assertEquals(1, writes)
        writeResult = MutationResult.LocalDeleted(ref)
        compose.onAllNodesWithText("Retry original deletion").onLast().performClick()
        assertEquals(2, writes)
    }
    @Test fun askEachTimeDoesNotSelectFirstCalendarImplicitly() {
        val editor = preparedEditor(draft.copy(calendarId = CalendarId(0)), null, "fixture-no-calendar", writer,
            EventEditorControllerTest.Store(), scope, initialCalendarSelected = false)
        show(editor)
        compose.onNodeWithText("Save").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Personal · fixture@example.test").performScrollTo().performClick()
        compose.onNodeWithText("Save").performScrollTo().assertIsEnabled()
        assertEquals(0, writes)
    }

    @Test @Config(qualifiers = "+notnight") fun retryReadyLight() {
        writeResult = MutationResult.Uncertain("fixture", null)
        val editor = controller(); editor.save(); proveSafe = true; editor.recover(); show(editor)
        compose.onRoot().captureRoboImage("src/test/screenshots/events_editor_retry_light.png")
    }

    @Test @Config(qualifiers = "+notnight") fun createEditorLight() {
        show(controller()); compose.onRoot().captureRoboImage("src/test/screenshots/events_editor_light.png")
    }
    @Test @Config(qualifiers = "+night") fun createEditorDark() {
        show(controller()); compose.onRoot().captureRoboImage("src/test/screenshots/events_editor_dark.png")
    }
}
