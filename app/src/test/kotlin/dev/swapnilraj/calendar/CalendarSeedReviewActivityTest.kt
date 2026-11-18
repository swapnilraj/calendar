package dev.swapnilraj.calendar

import android.Manifest
import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import dev.swapnilraj.calendar.android.navigation.*
import dev.swapnilraj.calendar.core.CalendarReadState
import dev.swapnilraj.calendar.ui.events.OrdinaryDraftDocument
import java.time.ZoneOffset
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
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

class CalendarSeedReviewTestApplication : Application(), CalendarSeedReviewOwner {
    override lateinit var calendarSeedReviewServices: CalendarSeedReviewServices
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = CalendarSeedReviewTestApplication::class, qualifiers = "en-rUS-w393dp-h851dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CalendarSeedReviewActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = RuntimeEnvironment.getApplication() as CalendarSeedReviewTestApplication
    private val store = SeedReviewStore()
    private val mutations = SeedReviewMutations()
    private fun setup(read: Boolean = true, write: Boolean = true) {
        if (read) shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR) else shadowOf(app).denyPermissions(Manifest.permission.READ_CALENDAR)
        if (write) shadowOf(app).grantPermissions(Manifest.permission.WRITE_CALENDAR) else shadowOf(app).denyPermissions(Manifest.permission.WRITE_CALENDAR)
        app.calendarSeedReviewServices = CalendarSeedReviewServices(store, mutations, SeedReviewRetirement,
            { CalendarReadState.Ready(seedReviewEnvironment) }, MutableSharedFlow(extraBufferCapacity = 8))
    }
    private fun launch(request: CalendarSeedReviewRequest) = Robolectric.buildActivity(CalendarSeedReviewActivity::class.java,
        CalendarSeedReviewActivity.intent(app, request.sessionId, request.seed, ZoneOffset.UTC)).setup().visible()
    private fun awaitText(text: String) = compose.waitUntil(8_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test fun completeSeedOpensNativeEditorAndCancelNeverWrites() {
        setup()
        val host = launch(seedReviewRequest(true))
        try {
            awaitText(app.getString(R.string.events_editor_create))
            compose.onNodeWithTag("calendar_seed_review").assertDoesNotExist()
            assertEquals(0, mutations.writes)
            host.get().finish()
            assertEquals(0, mutations.writes)
            assertNotNull(runBlocking { store.load(seedReviewRequest(true).draftId) })
        } finally { host.pause().stop().destroy() }
    }

    @Test fun incompleteSeedShowsBlankInputsAndKeepsDurableRawReview() {
        setup()
        val host = launch(seedReviewRequest())
        try {
            awaitText(app.getString(R.string.seed_review_complete))
            compose.onNodeWithTag("seed_start_date").assertTextEquals("", app.getString(R.string.seed_review_start_date))
            compose.onNodeWithTag("seed_start_time").assertTextEquals("", app.getString(R.string.seed_review_start_time))
            compose.onNodeWithTag("seed_start_date").performTextInput("2026-12-01")
            compose.waitUntil(8_000) {
                compose.onAllNodesWithTag("seed_start_date").fetchSemanticsNodes()
                runBlocking { store.load("incoming-review:${seedReviewRequest().sessionId}") }?.payload?.contains("2026-12-01") == true
            }
            assertEquals(0, mutations.writes)
        } finally { host.pause().stop().destroy() }
    }

    @Test fun explicitNativeSaveIsOnlyProviderWriteAndPreservesIncomingFields() {
        setup()
        val host = launch(seedReviewRequest(true))
        try {
            awaitText(app.getString(R.string.events_editor_create))
            assertEquals(0, mutations.writes)
            compose.onNodeWithText(app.getString(R.string.events_editor_save)).performScrollTo().performClick()
            compose.waitUntil(8_000) {
                compose.onAllNodesWithText(app.getString(R.string.events_editor_save)).fetchSemanticsNodes()
                host.get().isFinishing
            }
            assertEquals(1, mutations.writes)
            assertEquals("Synthetic incoming", mutations.saved?.title)
            assertEquals("Room", mutations.saved?.location); assertEquals("Notes", mutations.saved?.description)
            assertEquals(listOf(10), mutations.saved?.alertMinutes)
        } finally { host.pause().stop().destroy() }
    }

    @Test fun deniedWritePreflightDoesNotFreezeOrDispatchAndGrantNeverAutoSubmits() {
        setup(write = false)
        val request = seedReviewRequest(true)
        val host = launch(request)
        try {
            awaitText(app.getString(R.string.events_editor_create))
            compose.onNodeWithText(app.getString(R.string.events_editor_save)).performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(0, mutations.writes)
            val doc = runBlocking { OrdinaryDraftDocument.decode(requireNotNull(store.load(request.draftId))) }
            assertFalse(doc.submitted); assertNull(doc.frozen)
            shadowOf(app).grantPermissions(Manifest.permission.WRITE_CALENDAR)
            compose.waitForIdle()
            assertEquals(0, mutations.writes)
        } finally { host.pause().stop().destroy() }
    }

    @Test fun missingReadRequiresExplicitRecoveryWithoutCreatingDraft() {
        setup(read = false)
        val host = launch(seedReviewRequest(true))
        try {
            awaitText(app.getString(R.string.seed_review_allow_read))
            assertTrue(store.records.isEmpty()); assertEquals(0, mutations.writes)
        } finally { host.pause().stop().destroy() }
    }

    @Test fun guestSeedShowsMeetingEditorRequirementAndNeverDropsGuests() {
        setup()
        val request = seedReviewRequest(true).let { it.copy(seed = it.seed.copy(guests = listOf("guest@example.test"))) }
        val host = launch(request)
        try {
            awaitText(app.getString(R.string.seed_review_guests))
            compose.onNodeWithText("guest@example.test").assertExists()
            assertNull(runBlocking { store.load(request.draftId) }); assertEquals(0, mutations.writes)
        } finally { host.pause().stop().destroy() }
    }

    @Test fun incompleteRawInputSurvivesActualActivityRecreation() {
        setup()
        val host = launch(seedReviewRequest())
        try {
            awaitText(app.getString(R.string.seed_review_complete))
            compose.onNodeWithTag("seed_start_date").performTextInput("2026-12-02")
            compose.waitUntil(8_000) {
                compose.onAllNodesWithTag("seed_start_date").fetchSemanticsNodes()
                store.records.values.any { it.payload.contains("2026-12-02") }
            }
            host.recreate().visible()
            awaitText(app.getString(R.string.seed_review_complete))
            compose.onNodeWithTag("seed_start_date").assertTextContains("2026-12-02")
            compose.onNodeWithTag("seed_start_time").assertTextEquals("", app.getString(R.string.seed_review_start_time))
            assertEquals(0, mutations.writes)
        } finally { host.pause().stop().destroy() }
    }

    @Test fun unsupportedRuleIsVisibleAndNeverBecomesNativeOneoffOnArrival() {
        setup()
        val request = seedReviewRequest(true).let { it.copy(seed = it.seed.copy(recurrenceRule = "FREQ=HOURLY;COUNT=3")) }
        val host = launch(request)
        try {
            awaitText(app.getString(R.string.seed_review_complete))
            compose.onNodeWithTag("seed_rule").performScrollTo().assertTextContains("FREQ=HOURLY;COUNT=3")
            compose.onNodeWithTag("seed_continue").performScrollTo().performClick()
            awaitText(app.getString(R.string.seed_review_invalid))
            assertNull(store.records[request.draftId]); assertEquals(0, mutations.writes)
        } finally { host.pause().stop().destroy() }
    }

    @Test fun revokedReadScrubsNativeEditorBeforeResumeQueries() {
        setup()
        val host = launch(seedReviewRequest(true))
        try {
            awaitText(app.getString(R.string.events_editor_create))
            host.pause().stop()
            shadowOf(app).denyPermissions(Manifest.permission.READ_CALENDAR)
            host.restart().start().resume().visible()
            awaitText(app.getString(R.string.seed_review_allow_read))
            compose.onNodeWithText(app.getString(R.string.events_editor_create)).assertDoesNotExist()
            assertEquals(0, mutations.writes)
        } finally { host.pause().stop().destroy() }
    }

    @Test fun sourceFreeFrozenRecoveryMountsWithoutReadAndNeverShowsCalendarMetadata() {
        setup()
        val request = seedReviewRequest(true)
        runBlocking { app.calendarSeedReviewServices.open(request) }
        freezeSeedReviewNative(store, request)
        shadowOf(app).denyPermissions(Manifest.permission.READ_CALENDAR)
        val host = launch(request)
        try {
            awaitText(app.getString(R.string.events_drafts_recovery_only))
            compose.onNodeWithText(seedReviewCalendar.displayName).assertDoesNotExist()
            assertEquals(0, mutations.writes)
        } finally { host.pause().stop().destroy() }
    }

    @Test fun conflictingAutosaveKeepsPendingInputVisibleAndBlocksContinue() {
        setup()
        val request = seedReviewRequest()
        val host = launch(request)
        try {
            awaitText(app.getString(R.string.seed_review_complete))
            val old = requireNotNull(store.records["incoming-review:${request.sessionId}"])
            val document = CalendarSeedCompletionDocument.decode(old)
            val other = document.copy(fields = document.fields.copy(endDate = "2026-12-20"), revision = java.util.UUID.randomUUID().toString()).record(java.time.Instant.now())
            store.records[old.id] = other
            compose.onNodeWithTag("seed_start_date").performTextInput("2026-12-09")
            awaitText(app.getString(R.string.seed_review_unsaved))
            compose.onNodeWithTag("seed_start_date").assertTextContains("2026-12-09")
            compose.onNodeWithTag("seed_continue").assertIsNotEnabled()
            assertEquals(other, store.records[old.id]); assertEquals(0, mutations.writes)
        } finally { host.pause().stop().destroy() }
    }
}
