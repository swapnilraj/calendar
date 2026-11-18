package dev.swapnilraj.calendar.events

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
class OrdinaryRetirementScreenTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val store = EventEditorControllerTest.Store()
    private val draft = EventDraft(CalendarId(2), EventTime.Timed(Instant.parse("2030-06-15T09:00:00Z"), Instant.parse("2030-06-15T10:00:00Z")), ZoneOffset.UTC,
        "Synthetic retained event", targetIdentity = eventTestIdentity())
    private var calls = 0; private var fences = 0; private var blocked = false
    private val writer = object : CalendarMutations {
        override suspend fun loadForEdit(ref: EventRef): EditReadResult = error("Source-free creation recovery")
        override suspend fun create(operationId: String, draft: EventDraft): MutationResult { calls++; return MutationResult.PermissionRequired }
        override suspend fun update(operationId: String, ref: EventRef, token: EditToken, patch: EventPatch): MutationResult = error("No edit")
        override suspend fun delete(operationId: String, ref: EventRef, token: EditToken): MutationResult = error("No delete")
        override suspend fun recover(operationId: String): MutationResult = MutationResult.Unavailable
    }
    private val retirement = object : OperationRetirement {
        override suspend fun retire(request: RetirableOperation): OperationRetirementResult {
            fences++; return if (blocked) OperationRetirementResult.Blocked(request.operationId, RetirementBlockReason.RECORDED_COMMIT) else OperationRetirementResult.Retired(request.operationId)
        }
        override suspend fun inspect(request: RetirableOperation): OperationRetirementResult = OperationRetirementResult.NotRetired(request.operationId)
    }
    private fun frozen(): EventEditorController = runBlocking {
        val launch = (prepareNewDraft(draft, "retained", true, store) as DraftPreparationResult.Ready).launch
        val first = EventEditorController(draft, null, launch.draftId, writer, store, scope,
            expectedGeneration = launch.generation, requestWritePermission = { true })
        first.save()
        val stored = DraftBrowserCodec.read(store.records.getValue("retained")).launch!!
        EventEditorController(draft, null, stored.draftId, writer, store, scope, recoveryOnly = stored.needsRecovery,
            expectedGeneration = stored.generation, retirement = retirement, requestWritePermission = { error("No permission needed for local retirement") })
    }
    @After fun close() { scope.cancel() }
    @Test fun noReplacementCallbackNeverOffersAnEphemeralNewLaunch() {
        val editor = frozen()
        compose.setContent { DaylightTheme { EventEditorSheet(editor, listOf(eventTestCalendar()), {}, {}, {}) } }
        compose.onNodeWithText("Replace with a new draft").assertDoesNotExist()
        assertEquals(1, calls); assertEquals(0, fences)
    }
    @Test fun explicitConfirmationRetiresThenPublishesDurableReplacementWithoutSavingIt() {
        val editor = frozen(); var replacement: EditorDraftLaunch? = null
        compose.setContent { DaylightTheme { EventEditorSheet(editor, listOf(eventTestCalendar()), {}, {}, {}, onReplacedLaunch = { replacement = it }) } }
        compose.onNodeWithText("Replace with a new draft").performScrollTo().performClick()
        assertEquals(0, fences); assertNull(replacement)
        compose.onNodeWithText("Confirm").performClick(); compose.waitForIdle()
        assertEquals(1, fences); assertNotNull(replacement); assertFalse(store.records.containsKey("retained"))
        assertNotNull(store.records[replacement!!.draftId]); assertNotNull(replacement!!.generation)
        assertFalse(replacement!!.calendarSelected); assertEquals(1, calls)
    }
    @Test fun recordedCommitBlocksConfirmedDiscardAndLeavesRecoveryVisible() {
        blocked = true; val editor = frozen(); val exact = store.records.getValue("retained"); var closed = false
        compose.setContent { DaylightTheme { EventEditorSheet(editor, listOf(eventTestCalendar()), { closed = true }, {}, {}, onDiscarded = { closed = true }) } }
        compose.onNodeWithText("Retire and discard original request").performScrollTo().performClick()
        compose.onNodeWithText("Confirm").performClick(); compose.waitForIdle()
        assertFalse(closed); assertEquals(exact, store.records["retained"])
        compose.onNodeWithText("A local commit is recorded. Retirement cannot undo that save or authorize another creation. Review the original outcome.").assertIsDisplayed()
        assertEquals(1, calls)
    }
}
