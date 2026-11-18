package dev.swapnilraj.calendar

import android.Manifest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import dev.swapnilraj.calendar.android.navigation.*
import dev.swapnilraj.calendar.core.*
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
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
@Config(sdk = [35], application = CalendarSeedReviewTestApplication::class, qualifiers = "en-rUS-w393dp-h851dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CalendarSeedRefreshActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = RuntimeEnvironment.getApplication() as CalendarSeedReviewTestApplication
    private val store = SeedReviewStore()
    private val writer = SeedReviewMutations()
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    private var environment: CalendarReadState<CalendarSeedReviewEnvironment> = CalendarReadState.Ready(seedReviewEnvironment)
    private var envRead: (() -> Unit)? = null
    private fun services(port: CalendarMutations = writer, storage: AtomicDraftStore = store) = CalendarSeedReviewServices(storage, port, SeedReviewRetirement,
        { envRead?.invoke(); environment }, changes)
    private fun launch() = Robolectric.buildActivity(CalendarSeedReviewActivity::class.java,
        CalendarSeedReviewActivity.intent(app, seedReviewRequest(true).sessionId, seedReviewRequest(true).seed, ZoneOffset.UTC)).setup().visible()
    private fun shown(text: String) = compose.waitUntil(8_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun recoveryAction() = hasText(app.getString(R.string.phone_event_check_save)) and hasClickAction()
    private fun grant() = shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

    @Test fun writerRefreshHintDuringKnownCommitRecoveryDoesNotCancelExactCleanup() {
        grant()
        val observed = CompletableDeferred<Unit>()
        var recoveryStarted = false; var recoveries = 0
        val port = object : CalendarMutations by writer {
            override suspend fun recover(operationId: String): MutationResult {
                recoveries++; recoveryStarted = true
                changes.emit(Unit)
                withTimeout(5_000) { observed.await() }
                val hints = seedReviewRequest(true).seed.occurrence
                return MutationResult.LocalSaved(EventRef(seedReviewCalendar.id, 99, requireNotNull(hints.begin), requireNotNull(hints.end)))
            }
        }
        app.calendarSeedReviewServices = services(port)
        runBlocking { app.calendarSeedReviewServices.open(seedReviewRequest(true)) }
        freezeSeedReviewNative(store, seedReviewRequest(true))
        val receipt = store.records["incoming-review:${seedReviewRequest(true).sessionId}"]
        envRead = { if (recoveryStarted) observed.complete(Unit) }
        val host = launch()
        try {
            compose.waitUntil(8_000) { compose.onAllNodes(recoveryAction()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(recoveryAction()).performScrollTo().performClick()
            compose.waitUntil(8_000) {
                compose.onAllNodes(recoveryAction()).fetchSemanticsNodes()
                host.get().isFinishing
            }
            assertEquals(1, recoveries); assertEquals(0, writer.writes)
            assertNull(store.records[seedReviewRequest(true).draftId])
            assertEquals(receipt, store.records["incoming-review:${seedReviewRequest(true).sessionId}"])
        } finally { host.pause().stop().destroy() }
    }

    @Test fun equalEnvironmentHintDoesNotReopenOrReplaceMountedNativeDraft() {
        grant()
        var rejectNativeReads = false
        val storage = object : AtomicDraftStore by store {
            override suspend fun load(id: String): DraftRecord? {
                check(!rejectNativeReads || !id.startsWith("incoming:"))
                return store.load(id)
            }
        }
        app.calendarSeedReviewServices = services(storage = storage)
        val host = launch()
        try {
            shown(app.getString(R.string.events_editor_create))
            val before = store.records[seedReviewRequest(true).draftId]
            val observed = CompletableDeferred<Unit>(); envRead = { observed.complete(Unit) }
            rejectNativeReads = true
            changes.tryEmit(Unit)
            compose.waitUntil(8_000) { compose.onAllNodesWithText(app.getString(R.string.events_editor_create)).fetchSemanticsNodes(); observed.isCompleted }
            compose.onNodeWithText(app.getString(R.string.events_editor_create)).assertExists()
            compose.onNode(hasSetTextAction() and hasText("Synthetic incoming")).assertExists()
            assertEquals(before, store.records[seedReviewRequest(true).draftId]); assertEquals(0, writer.writes)
        } finally { host.pause().stop().destroy() }
    }

    @Test fun freshEnvironmentFailureScrubsMountedEditorInsteadOfRetainingCachedMetadata() {
        grant(); app.calendarSeedReviewServices = services()
        val host = launch()
        try {
            shown(app.getString(R.string.events_editor_create))
            environment = CalendarReadState.Unavailable
            changes.tryEmit(Unit)
            shown(app.getString(R.string.seed_review_unavailable))
            compose.onNode(hasSetTextAction() and hasText("Synthetic incoming")).assertDoesNotExist()
            assertEquals(0, writer.writes)
        } finally { host.pause().stop().destroy() }
    }

    @Test fun freshNamespaceReplacementInvalidatesUnsubmittedCalendarWithoutAdoptingReplacement() {
        grant(); app.calendarSeedReviewServices = services()
        val host = launch()
        try {
            shown(app.getString(R.string.events_editor_create))
            environment = CalendarReadState.Ready(seedReviewEnvironment.copy(calendars = listOf(seedReviewCalendar.copy(providerId = "replacement-calendar"))))
            changes.tryEmit(Unit)
            shown(app.getString(R.string.phone_event_choose_calendar_notice))
            compose.onNodeWithText(app.getString(R.string.events_editor_save)).assertIsNotEnabled()
            assertEquals(0, writer.writes)
        } finally { host.pause().stop().destroy() }
    }
}
