package dev.swapnilraj.calendar

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.events.SeriesEditorFixture
import dev.swapnilraj.calendar.ui.*
import dev.swapnilraj.calendar.ui.events.*
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancel
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
class SeriesWriteWorkflowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun choosingScopePersistsDraftBeforePublishingEditorWithoutMutatingProvider() {
        SeriesEditorFixture().use { f ->
            var prepared: SeriesEditorLaunch? = null
            compose.setContent { DaylightTheme {
                SeriesScopeWorkflow(SeriesScopeRequest(f.selected, SeriesEditorAction.EDIT), f.port, f.store, {}, { prepared = it })
            } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Entire series").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("This and following occurrences").assertIsNotEnabled()
            compose.onNodeWithText("Entire series").performClick()
            compose.waitUntil(10_000) { prepared != null }
            val saved = runBlocking { f.store.load(prepared!!.draftId) }
            assertNotNull(saved)
            assertEquals(prepared, SeriesDraftCodec.describe(saved!!))
            assertEquals(RecurrenceScope.ALL, prepared!!.scope)
            assertEquals(f.selected, prepared!!.selected)
            assertTrue(f.calls.isEmpty())
        }
    }

    @Test fun missingCompletedDraftCannotReseedAnEditorAfterRestoration() {
        SeriesEditorFixture().use { f ->
            val launch = runBlocking { (prepareSeriesDraft(f.source, RecurrenceScope.ALL, SeriesEditorAction.EDIT, "completed-series", f.store) as SeriesDraftPreparationResult.Ready).launch }
            runBlocking { f.store.delete(launch.draftId) }
            val restoration = StateRestorationTester(compose)
            restoration.setContent { DaylightTheme {
                SeriesWriteWorkflow(SeriesWriteRequest.from(launch), f.port, f.store, {}, {}, {})
            } }
            val message = RuntimeEnvironment.getApplication().getString(R.string.events_drafts_unreadable)
            compose.waitUntil(10_000) { compose.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty() }
            restoration.emulateSavedInstanceStateRestore()
            compose.waitUntil(10_000) { compose.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Save").assertDoesNotExist()
            assertTrue(f.calls.isEmpty())
            assertEquals(0, f.reads)
            assertTrue(f.store.records.isEmpty())
        }
    }

    @Test fun submittedSeriesDeletionRecoversWithoutSourceReadOrRepeatedDelete() {
        SeriesEditorFixture().use { f ->
            val launch = runBlocking { (prepareSeriesDraft(f.source, RecurrenceScope.ALL, SeriesEditorAction.DELETE, "recover-series", f.store) as SeriesDraftPreparationResult.Ready).launch }
            val controller = SeriesEventEditorController(launch, f.source, f.port, f.store, f.scope)
            f.next = SeriesMutationResult.Common(MutationResult.Uncertain(controller.operationId!!, f.selected.eventId))
            controller.requestDelete(); controller.confirmDelete()
            assertEquals(1, f.calls.size)
            val submitted = runBlocking { SeriesDraftCodec.describe(f.store.load(launch.draftId)!!)!! }
            assertTrue(submitted.needsRecovery)
            f.readResult = SeriesReadResult.Unavailable
            f.recovered = SeriesMutationResult.LocalDeleted(f.identity, RecurrenceScope.ALL, f.selected)
            var deleted: SeriesMutationResult.LocalDeleted? = null
            compose.setContent { DaylightTheme {
                SeriesWriteWorkflow(SeriesWriteRequest.from(submitted), f.port, f.store, {}, {}, { deleted = it })
            } }
            val recover = RuntimeEnvironment.getApplication().getString(R.string.events_editor_recover)
            compose.waitUntil(10_000) { compose.onAllNodesWithText(recover).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(recover).performScrollTo().performClick()
            compose.waitUntil(10_000) { deleted != null }
            assertEquals(0, f.reads)
            assertEquals(1, f.calls.size)
            assertEquals(1, f.recoveryIds.size)
            assertTrue(f.store.records.isEmpty())
            assertEquals(f.selected, deleted!!.selected)
        }
    }

    @Test fun changedSeriesSourceKeepsOriginalRawFieldsAndTokenLockedForReview() {
        SeriesEditorFixture().use { f ->
            val launch = runBlocking { (prepareSeriesDraft(f.source, RecurrenceScope.ALL, SeriesEditorAction.EDIT, "changed-series", f.store) as SeriesDraftPreparationResult.Ready).launch }
            val before = f.store.records.getValue(launch.draftId)
            f.readResult = SeriesReadResult.Ready(f.source.copy(token = EditToken("fresh-token"), masterFields = f.source.masterFields.copy(title = "Remote template")))
            compose.setContent { DaylightTheme {
                SeriesWriteWorkflow(SeriesWriteRequest.from(launch), f.port, f.store, {}, {}, {})
            } }
            val review = RuntimeEnvironment.getApplication().getString(R.string.series_source_changed)
            compose.waitUntil(10_000) { compose.onAllNodesWithText(review).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Master title").assertExists()
            compose.onNodeWithText("Remote template").assertDoesNotExist()
            assertEquals(before.payload, f.store.records.getValue(launch.draftId).payload)
            assertTrue(f.calls.isEmpty())
        }
    }

    @Test fun explicitDiscardReplacesRetiredControllerEvenWhenSourceTokenIsUnchanged() {
        SeriesEditorFixture().use { f ->
            val launch = runBlocking { f.prepare(id = "review-same-source") }
            f.next = SeriesMutationResult.Unsupported("Internal diagnostic")
            compose.setContent { DaylightTheme {
                SeriesWriteWorkflow(SeriesWriteRequest.from(launch), f.port, f.store, {}, {}, {})
            } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Master title").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Master title").performTextReplacement("First changed title")
            compose.onNodeWithText("Save").performScrollTo().performClick()
            val review = RuntimeEnvironment.getApplication().getString(R.string.series_discard_review)
            compose.waitUntil(10_000) { compose.onAllNodesWithText(review).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(review).performScrollTo().performClick()
            compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.series_discard_button)).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Master title").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Master title").assertIsEnabled().performTextReplacement("Second changed title")
            f.next = SeriesMutationResult.LocalSaved(f.identity, f.selected)
            compose.onNodeWithText("Save").performScrollTo().performClick()
            compose.waitUntil(10_000) { f.calls.size == 2 && f.store.records.isEmpty() }
            assertNotEquals(f.calls[0].id, f.calls[1].id)
            assertEquals(FieldChange.Set("Second changed title"), (f.calls[1].command as SeriesCommand.WholeEdit).intent.fields.title)
        }
    }

    @Test fun writePermissionGrantNeverRetriesSeriesMutationAutomatically() = runBlocking {
        SeriesEditorFixture().use { f ->
            var granted = false
            var prompts = 0
            val wrapper = PermissionAwareSeriesMutations(f.port, { granted }, { prompts++ })
            val command = SeriesCommand.Delete(RecurrenceScope.ALL)
            assertEquals(SeriesMutationResult.Common(MutationResult.PermissionRequired), wrapper.mutate("permission-series", f.selected, f.source.token, command))
            assertEquals(1, prompts)
            assertTrue(f.calls.isEmpty())
            granted = true
            assertTrue(f.calls.isEmpty())
            wrapper.mutate("permission-series", f.selected, f.source.token, command)
            assertEquals(1, f.calls.size)
            assertEquals(command, f.calls.single().command)
        }
    }
}

private inline fun <T> SeriesEditorFixture.use(block: (SeriesEditorFixture) -> T): T =
    try { block(this) } finally { scope.cancel() }
