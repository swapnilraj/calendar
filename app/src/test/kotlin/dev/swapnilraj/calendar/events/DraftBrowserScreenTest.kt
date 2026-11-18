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
class DraftBrowserScreenTest {
    @get:Rule val compose = createComposeRule()
    private val initial = EventDraft(CalendarId(2), EventTime.Timed(Instant.parse("2028-06-15T09:00:00Z"), Instant.parse("2028-06-15T10:00:00Z")), ZoneOffset.UTC, "Plan a quiet weekend")
    private suspend fun items(): List<DraftBrowserItem> {
        val store = EventEditorControllerTest.Store()
        prepareNewDraft(initial, "fixture-draft", true, store, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC))
        val record = store.records.getValue("fixture-draft")
        return listOf(DraftBrowserCodec.read(record), DraftBrowserCodec.read(record.copy(id = "future", version = 99)))
    }
    @Test fun browserShowsRetainedUnknownRecordAndOpensExactDescriptor() = runBlocking {
        val items = items(); var opened: EditorDraftLaunch? = null
        compose.setContent { DaylightTheme { Surface(Modifier.fillMaxSize()) {
            DraftBrowserContent(DraftBrowserState.Ready(items), {}, { opened = it }, {}, ZoneOffset.UTC)
        } } }
        compose.onNodeWithText(initial.title).assertIsDisplayed()
        compose.onNodeWithText("Unrecognized draft").assertIsDisplayed()
        compose.onNodeWithText("Open draft").performClick()
        assertEquals(items.first().launch, opened)
        compose.onRoot().captureRoboImage("src/test/screenshots/events_drafts_light.png")
    }
    @Test fun recoveryOnlyHeaderAndActionsCannotMasqueradeAsNewEvent() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val store = EventEditorControllerTest.Store()
            val fixture = EventEditorControllerTest(); val writer = fixture.Writer()
            val time = initial.time as EventTime.Timed
            val ref = EventRef(initial.calendarId, 42, time.start, time.end)
            val source = EditableEvent(EventDetails(EventOccurrence(ref, initial.time, initial.title, 1), eventTestCalendar(initial.calendarId.value)), EditToken("original"), EditableField.entries.toSet())
            prepareEditDraft(initial, source, "recover", store)
            writer.deleteResult = MutationResult.Uncertain("ignored", ref.eventId)
            EventEditorController(initial, source, "recover", writer, store, scope, requireStoredDraft = true)
                .apply { requestDelete(); confirmDelete() }
            val controller = EventEditorController(initial, null, "recover", writer, store, scope,
                recoveryOnly = true, recoveryRef = ref, requireStoredDraft = true)
            compose.setContent { DaylightTheme { Surface(Modifier.fillMaxSize()) {
                EventEditorContent(controller, emptyList(), {}, {}, {})
            } } }
            compose.onNodeWithText("Recover operation").assertIsDisplayed()
            compose.onNodeWithText("Recovery only").assertIsDisplayed()
            compose.onNodeWithText("New event").assertDoesNotExist()
            compose.onNodeWithText("Delete event").assertDoesNotExist()
            compose.onNodeWithText("Save").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("Review operation").performScrollTo().performClick()
            assertEquals(1, writer.deletes); assertEquals(1, writer.recoveryIds.size)
        } finally { scope.cancel() }
    }
    @Test fun emptyAndFailedStatesHaveHonestActions() {
        var refreshes = 0
        compose.setContent { DaylightTheme { DraftBrowserContent(DraftBrowserState.Unavailable, {}, {}, { refreshes++ }, ZoneOffset.UTC) } }
        compose.onNodeWithText("Retry").performClick()
        assertEquals(1, refreshes)
    }
}
