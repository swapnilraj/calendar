package dev.swapnilraj.calendar

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.events.EventEditorControllerTest
import dev.swapnilraj.calendar.ui.events.prepareNewDraft
import dev.swapnilraj.calendar.ui.events.DraftPreparationResult
import dev.swapnilraj.calendar.ui.*
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
import java.time.*
import kotlinx.coroutines.runBlocking
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
class EventWriteWorkflowTest {
    @get:Rule val compose = createComposeRule()
    private val date = LocalDate.parse("2028-06-15")
    private val calendar = CalendarInfo(CalendarId(2), "Fixture calendar", 0xff397d70.toInt(),
        CalendarAccount("owner@example.invalid", "com.google"), "owner@example.invalid", ZoneOffset.UTC,
        700, true, true, CalendarCapabilities(createEvents = AccessDecision.ALLOWED))
    private val ref = EventRef(calendar.id, 42, date.atStartOfDay(ZoneOffset.UTC).toInstant(), date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant())
    private class Writer(private val ref: EventRef) : CalendarMutations {
        var creates = 0
        var recovered = 0
        var saved: EventDraft? = null
        override suspend fun loadForEdit(ref: EventRef): EditReadResult = EditReadResult.Unavailable
        override suspend fun create(operationId: String, draft: EventDraft): MutationResult {
            creates++; saved = draft; return MutationResult.LocalSaved(ref)
        }
        override suspend fun update(operationId: String, ref: EventRef, token: EditToken, patch: EventPatch): MutationResult = error("Unexpected update")
        override suspend fun delete(operationId: String, ref: EventRef, token: EditToken): MutationResult = error("Unexpected delete")
        override suspend fun recover(operationId: String): MutationResult { recovered++; return MutationResult.Uncertain(operationId, null) }
    }

    @Test fun routeRoundTripRetainsExactMovedOccurrenceAndStableDraftIdentity() {
        val moved = ref.copy(eventId = 93, recurrence = RecurrenceRef(11, Instant.parse("2028-05-01T09:00:00Z")))
        val request = EventWriteRequest.edit(moved, ZoneId.of("Pacific/Auckland"), true)
        assertEquals(request, EventWriteRequest.decode(request.encode()))
        assertEquals(request.draftId, EventWriteRequest.edit(moved, ZoneId.of("America/Los_Angeles"), false).draftId)
        val create = EventWriteRequest.create(date, ZoneOffset.UTC, null)
        assertEquals(create, EventWriteRequest.decode(create.encode()))
    }

    @Test fun writePermissionGrantNeverAutomaticallySubmitsAndRecoveryDoesNotRequestWrite() = runBlocking {
        val writer = Writer(ref)
        var permission = false
        var requests = 0
        val guarded = PermissionAwareMutations(writer, { permission }, { requests++ })
        val draft = EventDraft(calendar.id, EventTime.AllDay(CalendarDateRange.day(date)), ZoneOffset.UTC, "Fixture", targetIdentity = calendar.identity)
        assertEquals(MutationResult.PermissionRequired, guarded.create("stable", draft))
        assertEquals(1, requests); assertEquals(0, writer.creates)
        permission = true
        assertEquals(0, writer.creates)
        guarded.recover("stable")
        assertEquals(1, requests); assertEquals(1, writer.recovered); assertEquals(0, writer.creates)
        assertEquals(MutationResult.LocalSaved(ref), guarded.create("stable", draft))
        assertEquals(1, writer.creates)
    }

    @Test fun createFromBrowsedDateRequiresCalendarChoiceThenSavesOnlyAfterExplicitRetry() {
        val writer = Writer(ref)
        var permission = false
        var requests = 0
        var saved: EventRef? = null
        val guarded = PermissionAwareMutations(writer, { permission }, { requests++ })
        val requested = EventWriteRequest.create(date, ZoneOffset.UTC, null)
        val store = EventEditorControllerTest.Store()
        val launch = runBlocking { prepareNewDraft(EventDraft(CalendarId(0), EventTime.AllDay(CalendarDateRange.day(date)), ZoneOffset.UTC, ""),
            requested.draftId, false, store) } as DraftPreparationResult.Ready
        val request = EventWriteRequest.fromLaunch(launch.launch, date, ZoneOffset.UTC)
        compose.setContent { DaylightTheme {
            EventWriteWorkflow(request, guarded, store, listOf(calendar), true,
                onDismiss = {}, onSaved = { saved = it }, onDeleted = {}, requestWritePermission = {
                    if (!permission) requests++
                    permission
                })
        } }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Starts: Thursday, June 15, 2028").assertExists()
        compose.onNodeWithContentDescription("Ends: Thursday, June 15, 2028").assertExists()
        assertEquals("2028-06-15", org.json.JSONObject(store.records.getValue(request.draftId).payload).getString("startDate"))
        compose.onNodeWithText("Save").assertIsDisplayed().performClick()
        assertEquals(0, requests); assertEquals(0, writer.creates)
        compose.onNodeWithText("Choose a calendar").performScrollTo().performClick()
        compose.onNode(hasText("Fixture calendar") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton) and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText("Save").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { requests == 1 }
        assertEquals(0, writer.creates)
        compose.runOnIdle { permission = true }
        compose.waitForIdle()
        assertEquals(0, writer.creates)
        compose.onNodeWithText("New event").assertIsDisplayed()
        compose.onNode(isDialog() and hasAnyDescendant(hasText("New event")))
            .captureRoboImage("src/test/screenshots/integration_create_event.png")
        compose.onNodeWithText("Save").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { saved != null }
        assertEquals(1, writer.creates)
        assertEquals(calendar.id, writer.saved?.calendarId)
        assertEquals(EventTime.AllDay(CalendarDateRange.day(date)), writer.saved?.time)
    }

    @Test fun staleGenerationRouteCannotMountOrModifyReplacementDraft() {
        val writer = Writer(ref); val store = EventEditorControllerTest.Store()
        val initial = EventDraft(calendar.id, EventTime.AllDay(CalendarDateRange.day(date)), ZoneOffset.UTC, "Retained successor", targetIdentity = calendar.identity)
        val prepared = runBlocking { prepareNewDraft(initial, "generation-route", true, store) } as DraftPreparationResult.Ready
        val request = EventWriteRequest.fromLaunch(prepared.launch, date, ZoneOffset.UTC)
        val before = store.records.getValue(request.draftId)
        val successor = before.copy(payload = org.json.JSONObject(before.payload)
            .put("generation", java.util.UUID.randomUUID().toString()).put("revision", java.util.UUID.randomUUID().toString()).toString())
        runBlocking { assertEquals(DraftChangeResult.Applied, store.compareAndSet(request.draftId, before, successor)) }
        compose.setContent { DaylightTheme {
            EventWriteWorkflow(request, writer, store, listOf(calendar), true, {}, {}, {}, requestWritePermission = { error("No permission prompt") })
        } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("This draft could not be opened.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Save").assertDoesNotExist()
        compose.onNodeWithText("Retained successor").assertDoesNotExist()
        compose.onNodeWithText("Retry").performClick(); compose.waitForIdle()
        assertEquals(successor, store.records[request.draftId]); assertEquals(0, writer.creates)
    }

    @Test fun restoredCompletedCreateWithMissingDraftNeverSeedsAnotherOperation() {
        val writer = Writer(ref)
        val requested = EventWriteRequest.create(date, ZoneOffset.UTC, calendar.id)
        val store = EventEditorControllerTest.Store()
        val launch = runBlocking { prepareNewDraft(EventDraft(calendar.id, EventTime.AllDay(CalendarDateRange.day(date)), ZoneOffset.UTC, "Fixture", targetIdentity = calendar.identity),
            requested.draftId, true, store) } as DraftPreparationResult.Ready
        val request = EventWriteRequest.fromLaunch(launch.launch, date, ZoneOffset.UTC)
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        restoration.setContent { DaylightTheme {
            EventWriteWorkflow(request, writer, store, listOf(calendar), true, {}, {}, {}, requestWritePermission = { true })
        } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Save").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Save").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { writer.creates == 1 && store.records.isEmpty() }
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("This draft could not be opened.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Save").assertDoesNotExist()
        compose.onNodeWithText("Retry").performClick()
        compose.waitForIdle()
        assertEquals(1, writer.creates)
    }
}
