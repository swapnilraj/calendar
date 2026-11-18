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
import java.time.ZoneOffset
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
class SeriesEditorScreenTest {
    @get:Rule val compose = createComposeRule()
    private val f = SeriesEditorFixture()
    @After fun stop() { f.scope.cancel() }
    private fun show(controller: SeriesEventEditorController) {
        compose.setContent { DaylightTheme { Surface(Modifier.fillMaxSize()) { SeriesEventEditorContent(controller, {}, {}) } } }
    }
    @Test fun scopeChooserRequiresExplicitSupportedSelection() {
        var selected: RecurrenceScope? = null
        compose.setContent { DaylightTheme { Surface(Modifier.fillMaxSize()) {
            SeriesScopeContent(f.source, SeriesEditorAction.EDIT, {}, { selected = it })
        } } }
        compose.onNodeWithText("This and following occurrences").assertIsNotEnabled()
        compose.onNodeWithText("This occurrence").assertIsEnabled()
        compose.onNodeWithText("Entire series").assertIsEnabled()
        assertNull(selected); compose.onRoot().captureRoboImage("src/test/screenshots/events_series_scope_light.png")
        compose.onNodeWithText("Entire series").performClick(); assertEquals(RecurrenceScope.ALL, selected); assertTrue(f.calls.isEmpty())
    }
    @Test fun unknownScopeRightsHaveNoEnabledMutationChoice() {
        compose.setContent { DaylightTheme { SeriesScopeContent(f.source.copy(scopes = emptyMap()), SeriesEditorAction.DELETE, {}, { error("Unknown choice") }) } }
        compose.onNodeWithText("This occurrence").assertIsNotEnabled()
        compose.onNodeWithText("Entire series").assertIsNotEnabled()
        compose.onNodeWithText("This and following occurrences").assertIsNotEnabled()
    }
    @Test fun masterEditorShowsMasterProvenanceAndNoGuessedTimeControls() = runBlocking<Unit> {
        val controller = f.controller(f.prepare()); show(controller)
        compose.onNodeWithText("These fields come from the series template.").assertIsDisplayed()
        compose.onNodeWithText("Master title").assertIsDisplayed(); compose.onNodeWithText("Override title").assertDoesNotExist()
        compose.onNodeWithText("Time, time zone, and recurrence changes are not available here yet. They remain unchanged.").assertIsDisplayed()
        compose.onRoot().captureRoboImage("src/test/screenshots/events_series_editor_light.png")
        compose.onNodeWithText("Save").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Changes update the series template. Occurrences with their own overrides retain those overrides.").assertIsDisplayed()
    }
    @Test fun thisEditorUsesSelectedOverrideAndCanOnlySaveExplicitChanges() = runBlocking<Unit> {
        val controller = f.controller(f.prepare(RecurrenceScope.THIS)); show(controller)
        compose.onNodeWithText("Override title").assertIsDisplayed(); compose.onNodeWithText("Master title").assertDoesNotExist()
        compose.onNodeWithText("These fields come from the selected occurrence.").assertIsDisplayed()
        compose.onNodeWithText("Override title").performTextReplacement("Changed exception")
        compose.onNodeWithText("Save").performScrollTo().assertIsEnabled().performClick()
        assertEquals(FieldChange.Set("Changed exception"), (f.calls.single().command as SeriesCommand.OccurrenceEdit).patch.title)
    }
    @Test fun technicalBackendReviewReasonsAreNeverRendered() = runBlocking<Unit> {
        f.next = SeriesMutationResult.Unsupported("SECRET provider event_id=41")
        val controller = f.controller(f.prepare()); controller.change { it.copy(title = "Retained") }; controller.save(); show(controller)
        compose.onNodeWithText("This scope is not supported for this selection yet.").assertIsDisplayed()
        compose.onAllNodesWithText("SECRET", substring = true).assertCountEquals(0)
        compose.onNodeWithText("Save").performScrollTo().assertIsNotEnabled()
    }
    @Test fun failedDismissFlushKeepsDraftAndModalVisible() = runBlocking<Unit> {
        val controller = f.controller(f.prepare()); controller.change { it.copy(title = "Unpersisted") }; f.store.failWrites = true
        var closed = false
        compose.setContent { DaylightTheme { SeriesEventEditorSheet(controller, { closed = true }, {}, {}, {}) } }
        compose.onNodeWithText("Cancel").performScrollTo().performClick(); compose.waitForIdle()
        assertFalse(closed); assertTrue(f.calls.isEmpty())
        compose.onNodeWithText("The draft could not be read or stored. Retry after restoring local storage access.").performScrollTo().assertIsDisplayed()
        f.store.failWrites = false; compose.onNodeWithText("Cancel").performScrollTo().performClick(); compose.waitForIdle(); assertTrue(closed)
    }
    @Test fun deleteDialogNamesEntireSeriesAndDoesNotWriteOnOpen() = runBlocking<Unit> {
        val controller = f.controller(f.prepare(action = SeriesEditorAction.DELETE)); show(controller)
        compose.onAllNodesWithText("Delete recurring event").filter(hasClickAction()).onFirst().performScrollTo().performClick()
        compose.onNodeWithText("Delete the entire series, including its existing exception occurrences? This cannot be undone here.").assertIsDisplayed()
        assertTrue(f.calls.isEmpty()); f.next = SeriesMutationResult.LocalDeleted(f.identity, RecurrenceScope.ALL, f.selected)
        compose.onNodeWithText("Delete").performClick(); assertEquals(SeriesCommand.Delete(RecurrenceScope.ALL), f.calls.single().command)
    }
    @Test fun sourceMissingRecoveryCallsSavedCallbackWithNullableSelectionWithoutNewMutation() {
        f.pending(); val controller = f.controller(f.launch(), null)
        var saved: SeriesMutationResult.LocalSaved? = null
        f.recovered = SeriesMutationResult.LocalSaved(f.identity, null)
        compose.setContent { DaylightTheme { SeriesEventEditorSheet(controller, {}, { saved = it }, {}, {}) } }
        compose.onNodeWithText("Recover operation").assertIsDisplayed()
        compose.onNodeWithText("Review operation").performClick(); compose.waitForIdle()
        assertEquals(SeriesMutationResult.LocalSaved(f.identity, null), saved); assertEquals(1, f.calls.size); assertEquals(0, f.reads)
    }
    @Test fun browserUsesDedicatedSeriesCallbackAndDoesNotOfferNoOpOpen() = runBlocking<Unit> {
        val launch = f.prepare(); val item = DraftBrowserCodec.read(f.store.records.getValue("series"))
        var opened: SeriesEditorLaunch? = null
        compose.setContent { DaylightTheme { Surface(Modifier.fillMaxSize()) {
            DraftBrowserContent(DraftBrowserState.Ready(listOf(item)), {}, { error("One-off route") }, {}, ZoneOffset.UTC, { opened = it })
        } } }
        compose.onNodeWithText("Recurring event draft").assertIsDisplayed()
        compose.onNodeWithText("Open draft").performClick(); assertEquals(launch, opened); assertTrue(f.calls.isEmpty())
    }
    @Test fun hostWithoutSeriesHandlerRetainsDraftWithoutEnabledOpenButton() = runBlocking<Unit> {
        f.prepare(); val item = DraftBrowserCodec.read(f.store.records.getValue("series"))
        compose.setContent { DaylightTheme { DraftBrowserContent(DraftBrowserState.Ready(listOf(item)), {}, {}, {}, ZoneOffset.UTC) } }
        compose.onNodeWithText("Open draft").assertDoesNotExist()
        compose.onNodeWithText("This recurring draft is retained. Opening it is not available in this host yet.").assertIsDisplayed()
    }
}
