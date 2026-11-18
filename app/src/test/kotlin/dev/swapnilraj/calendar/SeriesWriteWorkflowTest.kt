package dev.swapnilraj.calendar

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import org.json.JSONObject
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
            val controller = SeriesEventEditorController(launch, f.source, f.port, f.store, f.scope, requestWritePermission = { true })
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
            compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.series_discard_review)).assertDoesNotExist()
            compose.onNodeWithText("Save").assertIsNotEnabled()
            assertEquals(before.payload, f.store.records.getValue(launch.draftId).payload)
            assertTrue(f.calls.isEmpty())
        }
    }

    @Test fun dispatchedUnsupportedRequestStaysFrozenAndCannotBeDiscardedOrIssuedAgain() {
        SeriesEditorFixture().use { f ->
            val launch = runBlocking { f.prepare(id = "frozen-unsupported") }
            f.next = SeriesMutationResult.Unsupported("Internal diagnostic")
            val restoration = StateRestorationTester(compose)
            restoration.setContent { DaylightTheme {
                SeriesWriteWorkflow(SeriesWriteRequest.from(launch), f.port, f.store, {}, {}, {},
                    requestWritePermission = { true }, onReplacedLaunch = { error("Frozen requests cannot be replaced") })
            } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Master title").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Master title").performTextReplacement("First changed title")
            compose.onNodeWithText("Save").performScrollTo().performClick()
            compose.waitUntil(10_000) { f.calls.size == 1 }
            val frozen = f.store.records.getValue(launch.draftId)
            val document = SeriesDraftCodec.decode(frozen)
            assertTrue(document.submitted)
            assertNotNull(document.frozen)
            assertEquals(FieldChange.Set("First changed title"), (f.calls.single().command as SeriesCommand.WholeEdit).intent.fields.title)
            val discard = RuntimeEnvironment.getApplication().getString(R.string.series_discard_review)
            compose.onNodeWithText(discard).assertDoesNotExist()
            compose.onNodeWithText("First changed title").assertIsNotEnabled()
            compose.onNodeWithText("Save").assertIsNotEnabled()
            restoration.emulateSavedInstanceStateRestore()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("First changed title").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("First changed title").assertIsNotEnabled()
            compose.onNodeWithText("Save").assertIsNotEnabled()
            compose.onNodeWithText(discard).assertDoesNotExist()
            assertEquals(frozen, f.store.records.getValue(launch.draftId))
            assertEquals(1, f.calls.size)
            assertTrue(f.recoveryIds.isEmpty())
            assertEquals(0, f.store.legacyCalls)
        }
    }

    @Test fun unsubmittedSourceReviewPublishesNewGenerationThatRestoresAndRejectsOldRoute() {
        SeriesEditorFixture().use { f ->
            val original = runBlocking { f.prepare(id = "unsubmitted-review") }
            val pending = f.controller(original)
            pending.change { it.copy(title = "Retained unsent title") }
            runBlocking { assertTrue(pending.flushDraft()) }
            val originalRecord = f.store.records.getValue(original.draftId)
            val originalDocument = SeriesDraftCodec.decode(originalRecord)
            assertFalse(originalDocument.submitted)
            assertNull(originalDocument.frozen)
            val latest = f.source.copy(token = EditToken("reviewed-source-token"), masterFields = f.source.masterFields.copy(title = "Remote template"))
            f.readResult = SeriesReadResult.Ready(latest)
            val originalRoute = SeriesWriteRequest.from(original).encode()
            var currentRoute = ""
            var replaceRoute: (String) -> Unit = {}
            var replacement: SeriesEditorLaunch? = null
            val restoration = StateRestorationTester(compose)
            restoration.setContent {
                var route by rememberSaveable { mutableStateOf(originalRoute) }
                currentRoute = route
                replaceRoute = { route = it }
                DaylightTheme {
                    SeriesWriteWorkflow(SeriesWriteRequest.decode(route), f.port, f.store, {}, {}, {},
                        requestWritePermission = { true }, onReplacedLaunch = { next ->
                            replacement = next
                            route = SeriesWriteRequest.from(next).encode()
                        })
                }
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Retained unsent title").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Retained unsent title").assertIsNotEnabled()
            assertEquals(originalRecord, f.store.records.getValue(original.draftId))
            assertTrue(f.calls.isEmpty())
            val review = RuntimeEnvironment.getApplication().getString(R.string.series_discard_review)
            compose.onNodeWithText(review).performScrollTo().performClick()
            compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.series_discard_button)).performClick()
            compose.waitUntil(10_000) { replacement != null && compose.onAllNodesWithText("Remote template").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Remote template").assertIsEnabled()
            val next = replacement!!
            assertNotEquals(original.generation, next.generation)
            assertEquals(original.draftId, next.draftId)
            assertEquals(original.selected, next.selected)
            assertEquals(next, SeriesDraftCodec.describe(f.store.records.getValue(next.draftId)))
            assertTrue(SeriesWriteRequest.decode(currentRoute)!!.matches(next))
            assertFalse(SeriesWriteRequest.from(original).matches(next))
            assertNotEquals(originalDocument.operationId, SeriesDraftCodec.decode(f.store.records.getValue(next.draftId)).operationId)
            compose.onNodeWithText("Remote template").performTextReplacement("Retained replacement title")
            compose.mainClock.advanceTimeBy(400)
            compose.waitUntil(10_000) { SeriesDraftCodec.decode(f.store.records.getValue(next.draftId)).fields.title == "Retained replacement title" }
            restoration.emulateSavedInstanceStateRestore()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Retained replacement title").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Retained replacement title").assertIsEnabled()
            assertEquals(next.generation, SeriesWriteRequest.decode(currentRoute)!!.generation)
            assertTrue(f.calls.isEmpty())
            val retained = f.store.records.getValue(next.draftId)
            val reads = f.reads
            compose.runOnIdle { replaceRoute(originalRoute) }
            val unreadable = RuntimeEnvironment.getApplication().getString(R.string.events_drafts_unreadable)
            compose.waitUntil(10_000) { compose.onAllNodesWithText(unreadable).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Save").assertDoesNotExist()
            assertEquals(reads, f.reads)
            assertEquals(retained, f.store.records.getValue(next.draftId))
            assertTrue(f.calls.isEmpty())
            assertEquals(0, f.store.legacyCalls)
        }
    }

    @Test fun deniedSeriesPreflightRetainsUnsubmittedFieldsAndGrantNeedsAnotherSave() {
        SeriesEditorFixture().use { f ->
            val launch = runBlocking { f.prepare(id = "workflow-permission") }
            var granted by mutableStateOf(false)
            var prompts = 0
            var saved = 0
            compose.setContent { DaylightTheme {
                SeriesWriteWorkflow(SeriesWriteRequest.from(launch), f.port, f.store, {}, { saved++ }, {},
                    requestWritePermission = { prompts++; granted })
            } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Master title").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Master title").performTextReplacement("Permission pending title")
            compose.onNodeWithText("Save").performScrollTo().performClick()
            compose.waitUntil(10_000) { prompts == 1 }
            val denied = SeriesDraftCodec.decode(f.store.records.getValue(launch.draftId))
            assertFalse(denied.submitted); assertNull(denied.frozen)
            assertEquals("Permission pending title", denied.fields.title)
            assertTrue(f.calls.isEmpty())
            compose.runOnIdle { granted = true }
            compose.waitForIdle()
            assertEquals(1, prompts); assertEquals(0, saved); assertTrue(f.calls.isEmpty())
            compose.onNodeWithText("Save").performScrollTo().performClick()
            compose.waitUntil(10_000) { saved == 1 }
            assertEquals(2, prompts)
            assertEquals(1, f.calls.size)
            assertEquals(denied.operationId, f.calls.single().id)
            assertEquals(FieldChange.Set("Permission pending title"), (f.calls.single().command as SeriesCommand.WholeEdit).intent.fields.title)
            assertTrue(f.store.records.isEmpty())
        }
    }

    @Test fun absentPermissionPreflightCannotFreezeOrDispatchAnOtherwiseEditableDraft() {
        SeriesEditorFixture().use { f ->
            val launch = runBlocking { f.prepare(id = "workflow-no-preflight") }
            compose.setContent { DaylightTheme { SeriesWriteWorkflow(SeriesWriteRequest.from(launch), f.port, f.store, {}, {}, {}) } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Master title").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Master title").performTextReplacement("No permission callback")
            compose.onNodeWithText("Save").performScrollTo().performClick()
            val message = RuntimeEnvironment.getApplication().getString(R.string.events_editor_permission)
            compose.waitUntil(10_000) { compose.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty() }
            val retained = SeriesDraftCodec.decode(f.store.records.getValue(launch.draftId))
            assertEquals("No permission callback", retained.fields.title)
            assertFalse(retained.submitted); assertNull(retained.frozen)
            assertTrue(f.calls.isEmpty())
        }
    }

    @Test fun malformedGenerationAndGenerationlessRouteCannotOpenTheCurrentDraft() {
        SeriesEditorFixture().use { f ->
            val launch = runBlocking { f.prepare(id = "route-generation") }
            val route = SeriesWriteRequest.from(launch)
            assertEquals(route, SeriesWriteRequest.decode(route.encode()))
            for (bad in listOf<Any>(42, JSONObject.NULL, "not-a-generation", "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA")) {
                val raw = JSONObject(route.encode()).put("generation", bad).toString()
                assertNull(SeriesWriteRequest.decode(raw))
            }
            assertNull(SeriesWriteRequest.decode(JSONObject(route.encode()).put("unknown", true).toString()))
            val legacy = SeriesWriteRequest.decode(JSONObject(route.encode()).apply { remove("generation") }.toString())!!
            assertFalse(legacy.matches(launch))
            val before = f.store.records.getValue(launch.draftId)
            compose.setContent { DaylightTheme { SeriesWriteWorkflow(legacy, f.port, f.store, {}, {}, {}) } }
            val unreadable = RuntimeEnvironment.getApplication().getString(R.string.events_drafts_unreadable)
            compose.waitUntil(10_000) { compose.onAllNodesWithText(unreadable).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Save").assertDoesNotExist()
            assertEquals(0, f.reads); assertTrue(f.calls.isEmpty())
            assertEquals(before, f.store.records.getValue(launch.draftId))
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
