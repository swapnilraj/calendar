package dev.swapnilraj.calendar

import android.Manifest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import dev.swapnilraj.calendar.android.navigation.*
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.OrdinaryDraftDocument
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = CalendarEditReviewTestApplication::class, qualifiers = "en-rUS-w393dp-h851dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CalendarEditReviewActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = RuntimeEnvironment.getApplication() as CalendarEditReviewTestApplication
    private val f = EditReviewFixture()
    private fun setup(read: Boolean = true, write: Boolean = true) {
        if (read) shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR) else shadowOf(app).denyPermissions(Manifest.permission.READ_CALENDAR)
        if (write) shadowOf(app).grantPermissions(Manifest.permission.WRITE_CALENDAR) else shadowOf(app).denyPermissions(Manifest.permission.WRITE_CALENDAR)
        app.calendarEditReviewServices = f.services()
    }
    private fun launch(request: CalendarEditReviewRequest = f.request()) = Robolectric.buildActivity(CalendarEditReviewActivity::class.java,
        CalendarEditReviewActivity.intent(app, request)).setup().visible()
    private fun shown(value: String) = compose.waitUntil(8_000) { compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }
    private fun apply() {
        compose.onNodeWithTag("incoming_edit_apply").performScrollTo().performClick()
        compose.waitUntil(8_000) { compose.onAllNodes(hasContentDescription(app.getString(R.string.phone_event_title)) and hasSetTextAction() and isEnabled()).fetchSemanticsNodes().size == 1 }
    }
    private fun save() = compose.onNodeWithText(app.getString(R.string.events_editor_save)).assertIsDisplayed().performClick()

    @Test fun arrivalAndCloseOnlyRetainReviewNoNativeDraftOrProviderWrite() {
        setup(); val host = launch()
        try {
            shown(app.getString(R.string.incoming_edit_review_apply))
            compose.onNodeWithText("Original synthetic event").assertExists()
            compose.onNodeWithText("Proposed synthetic title").assertExists()
            assertNull(f.store.rows[f.request().nativeId]); assertEquals(0, f.writes)
            compose.onNodeWithText(app.getString(R.string.incoming_edit_review_close)).performScrollTo().performClick()
            assertTrue(host.get().isFinishing); assertNotNull(f.store.rows[f.request().reviewId]); assertEquals(0, f.writes)
        } finally { host.pause().stop().destroy() }
    }

    @Test fun explicitApplyThenNativeSaveUpdatesOnlySuggestedFieldsAndOldSessionCannotReplay() {
        setup(); val host = launch()
        try {
            shown(app.getString(R.string.incoming_edit_review_apply)); apply()
            assertEquals(0, f.writes)
            val native = OrdinaryDraftDocument.decode(requireNotNull(f.store.rows[f.request().nativeId]))
            assertTrue(native.sourceBaseline.contains("Original synthetic event")); assertFalse(native.sourceBaseline.contains("Proposed synthetic title"))
            save()
            compose.waitUntil(8_000) { compose.onAllNodesWithText(app.getString(R.string.events_editor_save)).fetchSemanticsNodes(); host.get().isFinishing }
            assertEquals(1, f.writes); assertEquals(FieldChange.Set("Proposed synthetic title"), f.patches.single().title)
            assertEquals(FieldChange.Keep, f.patches.single().time); assertEquals(FieldChange.Keep, f.patches.single().eventZone)
            assertEquals(FieldChange.Keep, f.patches.single().alertMinutes)
            assertNull(f.store.rows[f.request().nativeId]); assertTrue(isCalendarEditHandoffReceipt(requireNotNull(f.store.rows[f.request().reviewId])))
        } finally { host.pause().stop().destroy() }
        val replay = launch()
        try {
            shown(app.getString(R.string.incoming_edit_review_consumed))
            assertEquals(1, f.writes); assertNull(f.store.rows[f.request().nativeId])
        } finally { replay.pause().stop().destroy() }
    }

    @Test fun deniedWritePreflightNeverFreezesAndPermissionGrantNeverAutoSubmits() {
        setup(write = false); val host = launch()
        try {
            shown(app.getString(R.string.incoming_edit_review_apply)); apply(); save(); compose.waitForIdle()
            assertEquals(0, f.writes); assertFalse(OrdinaryDraftDocument.decode(requireNotNull(f.store.rows[f.request().nativeId])).hasRequest)
            shadowOf(app).grantPermissions(Manifest.permission.WRITE_CALENDAR); compose.waitForIdle()
            assertEquals(0, f.writes)
        } finally { host.pause().stop().destroy() }
    }

    @Test fun missingReadRequiresExplicitGrantAndAnotherApplyGesture() {
        setup(read = false); val host = launch()
        try {
            shown(app.getString(R.string.incoming_edit_review_allow))
            assertNull(shadowOf(host.get()).lastRequestedPermission); assertEquals(0, f.infoReads)
            compose.onNodeWithText(app.getString(R.string.incoming_edit_review_allow)).performClick()
            assertArrayEquals(arrayOf(Manifest.permission.READ_CALENDAR), shadowOf(host.get()).lastRequestedPermission.requestedPermissions)
            shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)
            host.pause().stop().restart().start().resume().visible()
            shown(app.getString(R.string.incoming_edit_review_apply))
            assertNull(f.store.rows[f.request().nativeId]); assertEquals(0, f.writes)
        } finally { host.pause().stop().destroy() }
    }

    @Test fun malformedGuestsAndRuleStayVisibleWithNoOrdinaryApplyFallback() {
        setup()
        val request = f.request(f.seed().copy(guests = listOf("invalid guest input"), recurrenceRule = "FREQ=INVALID"))
        val host = launch(request)
        try {
            shown(app.getString(R.string.incoming_edit_review_guests))
            compose.onNodeWithText("invalid guest input").assertExists(); compose.onNodeWithText("FREQ=INVALID").assertExists()
            compose.onNodeWithTag("incoming_edit_apply").assertDoesNotExist()
            assertNull(f.store.rows[request.nativeId]); assertEquals(0, f.writes)
        } finally { host.pause().stop().destroy() }
    }

    @Test fun mountedNativeUidReplacementScrubsWithoutReadingReplacementDetailsOrRebasing() {
        setup(); val host = launch()
        try {
            shown(app.getString(R.string.incoming_edit_review_apply)); apply()
            val before = f.store.rows[f.request().nativeId]; val reads = f.editReads
            f.info = f.info.copy(providerUid = "replacement-event")
            f.changes.tryEmit(Unit)
            shown(app.getString(R.string.incoming_edit_review_stale))
            compose.onNodeWithText("Proposed synthetic title").assertDoesNotExist()
            assertEquals(reads, f.editReads); assertEquals(before, f.store.rows[f.request().nativeId]); assertEquals(0, f.writes)
        } finally { host.pause().stop().destroy() }
    }

    @Test fun ownTimeUpdateReaderHintCannotCancelCleanupOrRevalidateChangedBoundsAfterCommit() {
        setup()
        val request = f.request(f.seed().copy(occurrence = CalendarOccurrenceHints(f.start.plusSeconds(3600), f.start.plusSeconds(7200), null)))
        val observed = CompletableDeferred<Unit>(); var updating = false; var readsAtUpdate = 0
        f.onEnvironment = { if (updating) observed.complete(Unit) }
        f.onUpdated = { updating = true; readsAtUpdate = f.infoReads; f.info = f.info.copy(providerUid = "learned-after-commit"); f.changes.emit(Unit); withTimeout(5_000) { observed.await() } }
        val host = launch(request)
        try {
            shown(app.getString(R.string.incoming_edit_review_apply)); apply(); save()
            compose.waitUntil(8_000) { compose.onAllNodesWithText(app.getString(R.string.events_editor_save)).fetchSemanticsNodes(); host.get().isFinishing }
            assertEquals(1, f.writes); assertEquals(readsAtUpdate, f.infoReads)
            assertNull(f.store.rows[request.nativeId]); assertTrue(isCalendarEditHandoffReceipt(requireNotNull(f.store.rows[request.reviewId])))
        } finally { host.pause().stop().destroy() }
    }

    @Test fun readLossMasksPendingFrozenWriteWithoutCancellingItsControllerOrExactCleanup() {
        setup()
        val request = f.request(f.seed().copy(occurrence = CalendarOccurrenceHints(f.start.plusSeconds(3600), f.start.plusSeconds(7200), null)))
        val proofStarted = CompletableDeferred<Unit>(); val releaseProof = CompletableDeferred<Unit>()
        val releaseUpdate = CompletableDeferred<Unit>()
        var updateCancelled = false
        var frozen: DraftRecord? = null
        var editsAtUpdate = 0; var infoAtUpdate = 0
        f.onUpdated = {
            editsAtUpdate = f.editReads; infoAtUpdate = f.infoReads
            frozen = requireNotNull(f.store.rows[request.nativeId])
            assertTrue(OrdinaryDraftDocument.decode(requireNotNull(frozen)).hasRequest)
            shadowOf(app).denyPermissions(Manifest.permission.READ_CALENDAR)
            f.environment = CalendarReadState.Ready((f.environment as CalendarReadState.Ready).value.copy(calendars = emptyList(), calendarReadGranted = false))
            f.info = f.info.copy(providerUid = "learned-after-own-commit")
            f.store.beforeLoad = { id ->
                if (id == request.nativeId && !proofStarted.isCompleted) {
                    proofStarted.complete(Unit); releaseProof.await()
                }
            }
            f.changes.emit(Unit)
            try { releaseUpdate.await() } finally { updateCancelled = !currentCoroutineContext().isActive }
        }
        val host = launch(request)
        try {
            shown(app.getString(R.string.incoming_edit_review_apply)); apply(); save()
            compose.waitUntil(8_000) { proofStarted.isCompleted }
            // The permission mask is visible before local frozen-proof I/O can finish. The same
            // controller's provider call remains alive, with no source fields/actions or pickers.
            compose.onNodeWithText("Proposed synthetic title").assertDoesNotExist()
            compose.onNodeWithText("Original synthetic event").assertDoesNotExist()
            compose.onNodeWithText(app.getString(R.string.events_editor_save)).assertDoesNotExist()
            compose.onNodeWithContentDescription(app.getString(R.string.phone_event_title)).assertDoesNotExist()
            assertFalse(updateCancelled)
            assertEquals(frozen, f.store.rows[request.nativeId])
            val before = OrdinaryDraftDocument.decode(requireNotNull(frozen))
            releaseProof.complete(Unit); compose.waitForIdle()
            val after = OrdinaryDraftDocument.decode(requireNotNull(f.store.rows[request.nativeId]))
            assertEquals(before.generation, after.generation); assertEquals(before.operationId, after.operationId)
            assertEquals(editsAtUpdate, f.editReads); assertEquals(infoAtUpdate, f.infoReads)
            releaseUpdate.complete(Unit)
            compose.waitUntil(8_000) { compose.onAllNodesWithText(app.getString(R.string.events_editor_save)).fetchSemanticsNodes(); host.get().isFinishing }
            assertFalse(updateCancelled); assertEquals(1, f.writes)
            assertNull(f.store.rows[request.nativeId]); assertTrue(isCalendarEditHandoffReceipt(requireNotNull(f.store.rows[request.reviewId])))
        } finally { releaseProof.complete(Unit); releaseUpdate.complete(Unit); host.pause().stop().destroy() }
        val replay = launch(request)
        try {
            shown(app.getString(R.string.incoming_edit_review_consumed))
            assertEquals(1, f.writes); assertNull(f.store.rows[request.nativeId])
        } finally { replay.pause().stop().destroy() }
    }

    @Test fun readLossMaskPreservesFinishCloseCleanupAfterExactDeleteButBeforeAcknowledgment() {
        setup()
        val request = f.request()
        val proofStarted = CompletableDeferred<Unit>(); val releaseProof = CompletableDeferred<Unit>()
        val cleanupCommitted = CompletableDeferred<Unit>(); val releaseAck = CompletableDeferred<Unit>()
        var cleanupAttempts = 0; var cleanupCancelled = false
        f.store.beforeChange = { id, _, next ->
            if (id == request.nativeId && next == null && ++cleanupAttempts == 1) error("Synthetic first cleanup unavailable")
        }
        val host = launch(request)
        try {
            shown(app.getString(R.string.incoming_edit_review_apply)); apply(); save()
            shown(app.getString(R.string.phone_event_finish_close))
            assertEquals(1, f.writes); assertEquals(1, cleanupAttempts)
            val native = requireNotNull(f.store.rows[request.nativeId])
            val receipt = requireNotNull(f.store.rows[request.reviewId])
            assertTrue(OrdinaryDraftDocument.decode(native).hasRequest)
            f.store.afterChange = { id, expected, next ->
                if (id == request.nativeId && next == null) {
                    assertEquals(native, expected)
                    shadowOf(app).denyPermissions(Manifest.permission.READ_CALENDAR)
                    f.environment = CalendarReadState.Ready((f.environment as CalendarReadState.Ready).value.copy(calendars = emptyList(), calendarReadGranted = false))
                    f.store.beforeLoad = { loaded ->
                        if (loaded == request.nativeId && !proofStarted.isCompleted) {
                            proofStarted.complete(Unit); releaseProof.await()
                        }
                    }
                    cleanupCommitted.complete(Unit); f.changes.emit(Unit)
                    try { releaseAck.await() } finally { cleanupCancelled = !currentCoroutineContext().isActive }
                }
            }
            compose.onNodeWithText(app.getString(R.string.phone_event_finish_close)).performScrollTo().performClick()
            compose.waitUntil(8_000) { cleanupCommitted.isCompleted && proofStarted.isCompleted }
            compose.onNodeWithText("Proposed synthetic title").assertDoesNotExist()
            compose.onNodeWithText(app.getString(R.string.phone_event_finish_close)).assertDoesNotExist()
            compose.onNodeWithText(app.getString(R.string.events_editor_save)).assertDoesNotExist()
            assertFalse(cleanupCancelled); assertNull(f.store.rows[request.nativeId])
            assertEquals(receipt, f.store.rows[request.reviewId])
            releaseProof.complete(Unit); compose.waitForIdle()
            releaseAck.complete(Unit)
            compose.waitUntil(8_000) { compose.onAllNodesWithText(app.getString(R.string.events_editor_save)).fetchSemanticsNodes(); host.get().isFinishing }
            assertFalse(cleanupCancelled); assertEquals(2, cleanupAttempts); assertEquals(1, f.writes)
            assertNull(f.store.rows[request.nativeId]); assertEquals(receipt, f.store.rows[request.reviewId])
        } finally { releaseProof.complete(Unit); releaseAck.complete(Unit); host.pause().stop().destroy() }
        val replay = launch(request)
        try {
            shown(app.getString(R.string.incoming_edit_review_consumed))
            assertEquals(1, f.writes); assertEquals(2, cleanupAttempts)
        } finally { replay.pause().stop().destroy() }
    }

    @Test fun frozenResumeAndRecoverUseReceiptBackendProofWithoutMutableUidSourceRead() {
        setup(write = false)
        val request = f.request()
        runBlocking {
            val review = app.calendarEditReviewServices.open(request) as CalendarEditReviewResult.Review
            app.calendarEditReviewServices.applySuggestions(review.snapshot)
        }
        f.freeze(request); val oldReads = f.infoReads; val oldEdits = f.editReads
        f.infoFailure = CalendarReadState.Unavailable; f.info = f.info.copy(providerUid = "learned-uid-after-known-commit")
        f.onRecover = { MutationResult.LocalSaved(request.selected) }
        val host = launch(request)
        try {
            val recover = hasText(app.getString(R.string.phone_event_check_save)) and hasClickAction()
            compose.waitUntil(8_000) { compose.onAllNodes(recover).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(recover).performScrollTo().performClick()
            compose.waitUntil(8_000) { compose.onAllNodes(recover).fetchSemanticsNodes(); host.get().isFinishing }
            assertEquals(oldReads, f.infoReads); assertEquals(oldEdits, f.editReads); assertEquals(1, f.recoveries); assertEquals(0, f.writes)
            assertNull(f.store.rows[request.nativeId])
        } finally { host.pause().stop().destroy() }
    }
}
