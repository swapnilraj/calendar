package dev.swapnilraj.calendar.events

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.*
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Controlled public presentation only: no incoming-intent, meeting, or provider feature dependency. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w393dp-h851dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneOrdinaryOwnerLifecycleTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val store = Store()
    private val entered = CompletableDeferred<Unit>()
    private val release = CompletableDeferred<Unit>()
    private val initial = EventDraft(CalendarId(2), EventTime.Timed(Instant.parse("2028-06-15T09:00:00Z"), Instant.parse("2028-06-15T10:00:00Z")),
        ZoneOffset.UTC, "Synthetic retained phone draft", targetIdentity = eventTestIdentity())
    private val ref = EventRef(initial.calendarId, 42, (initial.time as EventTime.Timed).start, (initial.time as EventTime.Timed).end)
    private var visible by mutableStateOf(true)
    private var writes = 0
    private var holdWrite = false
    private var writeResult: MutationResult = MutationResult.LocalSaved(ref)
    private var inspectionCancelled = false
    private val saved = mutableListOf<EventRef>()
    private val writer = object : CalendarMutations {
        override suspend fun loadForEdit(ref: EventRef): EditReadResult = error("No source read")
        override suspend fun create(operationId: String, draft: EventDraft): MutationResult {
            writes++
            if (holdWrite) { entered.complete(Unit); release.await() }
            return writeResult
        }
        override suspend fun update(operationId: String, ref: EventRef, token: EditToken, patch: EventPatch): MutationResult = error("No update")
        override suspend fun delete(operationId: String, ref: EventRef, token: EditToken): MutationResult = error("No delete")
        override suspend fun recover(operationId: String): MutationResult = MutationResult.Unavailable
    }
    private fun controller(retirement: OperationRetirement? = null): EventEditorController = runBlocking {
        val launch = (prepareNewDraft(initial, "ordinary-phone", true, store) as DraftPreparationResult.Ready).launch
        EventEditorController(initial, null, launch.draftId, writer, store, scope, expectedGeneration = launch.generation,
            retirement = retirement, requestWritePermission = { true })
    }
    private fun show(controller: EventEditorController) {
        compose.waitUntil(8_000) { !controller.state.loading }
        compose.setContent { DaylightTheme {
            PhoneEventEditor(controller, listOf(eventTestCalendar()), {}, { saved += it }, {}, contentVisible = visible)
        } }
    }
    private fun mask() {
        compose.runOnIdle { visible = false }
        compose.onNodeWithContentDescription("Title").assertDoesNotExist()
        compose.onNodeWithText(initial.title).assertDoesNotExist()
        compose.onNodeWithText("Save").assertDoesNotExist()
    }
    @After fun stop() { release.complete(Unit); store.release.complete(Unit); scope.cancel() }

    @Test fun maskingPendingSaveKeepsOwnerUntilExactOutcomeAndCleanupArrive() {
        holdWrite = true; val controller = controller(); show(controller)
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(8_000) { entered.isCompleted }
        val frozen = store.rows.getValue("ordinary-phone")
        assertTrue(OrdinaryDraftDocument.decode(frozen).submitted)
        mask(); assertTrue(saved.isEmpty()); assertEquals(frozen, store.rows["ordinary-phone"])
        release.complete(Unit)
        try { compose.waitUntil(8_000) {
            // Drain Robolectric's Compose/UI work after the synthetic IO continuation completes.
            compose.onAllNodesWithText("Save").fetchSemanticsNodes()
            saved.size == 1
        } } catch (error: Throwable) {
            throw AssertionError("savedCount=${saved.size}; resultType=${controller.state.result?.javaClass?.simpleName}; localOutcomeType=${controller.state.localOutcome?.javaClass?.simpleName}; problem=${controller.state.problem}; rows=${store.rows.size}; scopeActive=${scope.isActive}; writes=$writes", error)
        }
        assertEquals(listOf(ref), saved); assertEquals(1, writes); assertTrue(store.rows.isEmpty())
        compose.runOnIdle { visible = true }; compose.waitForIdle()
        assertEquals(listOf(ref), saved); assertEquals(1, writes)
    }

    @Test fun finishCleanupSurvivesMaskAfterDeletionBeforeAcknowledgment() {
        store.failFirstCleanup = true; val controller = controller(); show(controller)
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(8_000) { controller.canAcknowledge }
        assertNotNull(controller.state.localOutcome); assertTrue(saved.isEmpty())
        store.holdCleanup = true
        compose.onNodeWithText("Finish closing saved draft").performScrollTo().performClick()
        compose.waitUntil(8_000) { store.committed.isCompleted }
        assertTrue(store.rows.isEmpty()); mask(); assertTrue(saved.isEmpty())
        store.release.complete(Unit)
        compose.waitUntil(8_000) {
            // Drain Robolectric's Compose/UI work after the synthetic IO continuation completes.
            compose.onAllNodesWithText("Save").fetchSemanticsNodes()
            saved.size == 1
        }
        assertEquals(listOf(ref), saved); assertFalse(store.cleanupCancelled)
        assertEquals(2, store.cleanupAttempts); assertEquals(1, writes)
    }

    @Test fun retirementInspectionSurvivesMaskWithoutDeletingOrResubmittingFrozenDraft() {
        writeResult = MutationResult.PermissionRequired
        val retirement = object : OperationRetirement {
            override suspend fun retire(request: RetirableOperation): OperationRetirementResult = error("No retirement request")
            override suspend fun inspect(request: RetirableOperation): OperationRetirementResult {
                entered.complete(Unit)
                try { release.await() } finally { inspectionCancelled = !currentCoroutineContext().isActive }
                return OperationRetirementResult.Retired(request.operationId)
            }
        }
        val controller = controller(retirement); show(controller)
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(8_000) { controller.canInspectRetirement }
        val frozen = store.rows.getValue("ordinary-phone")
        compose.onNodeWithText("Check discard result").performScrollTo().performClick()
        compose.waitUntil(8_000) { entered.isCompleted }; mask()
        release.complete(Unit)
        compose.waitUntil(8_000) { controller.state.retirementConfirmed }
        assertFalse(inspectionCancelled); assertEquals(frozen, store.rows["ordinary-phone"])
        assertEquals(1, writes); assertTrue(saved.isEmpty())
        controller.save(); controller.retryOriginal(); assertEquals(1, writes)
    }

    private class Store : AtomicDraftStore {
        val rows = ConcurrentHashMap<String, DraftRecord>()
        var failFirstCleanup = false; var holdCleanup = false
        var cleanupAttempts = 0; var cleanupCancelled = false
        val committed = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        override suspend fun load(id: String) = rows[id]
        override suspend fun list() = rows.values.toList()
        override suspend fun upsert(record: DraftRecord) = error("No blind upsert")
        override suspend fun delete(id: String) = error("No blind delete")
        override suspend fun compareAndSet(id: String, expected: DraftRecord?, replacement: DraftRecord?): DraftChangeResult = withContext(Dispatchers.IO) {
            if (replacement == null && ++cleanupAttempts == 1 && failFirstCleanup) return@withContext DraftChangeResult.Unavailable
            val result = synchronized(rows) {
                if (rows[id] != expected) DraftChangeResult.Changed else {
                    if (replacement == null) rows.remove(id) else rows[id] = replacement
                    DraftChangeResult.Applied
                }
            }
            if (result == DraftChangeResult.Applied && replacement == null && holdCleanup) {
                committed.complete(Unit)
                try { release.await() } finally { cleanupCancelled = !currentCoroutineContext().isActive }
            }
            result
        }
    }
}
