package dev.swapnilraj.calendar

import androidx.compose.ui.test.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.events.EventEditorControllerTest
import dev.swapnilraj.calendar.ui.events.*
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
        700, true, true, CalendarCapabilities(createEvents = AccessDecision.ALLOWED), "calendar-A")
    private val ref = EventRef(calendar.id, 42, date.atStartOfDay(ZoneOffset.UTC).toInstant(), date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant())
    private class Writer(private val ref: EventRef) : CalendarMutations {
        var loads = 0
        var uncertain = false
        var creates = 0
        var recovered = 0
        var saved: EventDraft? = null
        override suspend fun loadForEdit(ref: EventRef): EditReadResult { loads++; return EditReadResult.Unavailable }
        override suspend fun create(operationId: String, draft: EventDraft): MutationResult {
            creates++; saved = draft; return if (uncertain) MutationResult.Uncertain(operationId, null) else MutationResult.LocalSaved(ref)
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

    private fun prepare(store: EventEditorControllerTest.Store, selected: Boolean = true): EventWriteRequest {
        val seed = EventDraft(if (selected) calendar.id else CalendarId(0), EventTime.AllDay(CalendarDateRange.day(date)),
            ZoneId.of("UTC"), if (selected) "Fixture" else "", targetIdentity = calendar.identity.takeIf { selected })
        val id = EventWriteRequest.create(date, ZoneId.of("UTC"), calendar.id.takeIf { selected }).draftId
        val ready = runBlocking { prepareNewDraft(seed, id, selected, store) } as DraftPreparationResult.Ready
        return EventWriteRequest.fromLaunch(ready.launch, date, ZoneId.of("UTC"))
    }
    private fun shown(text: String) = compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun readyTitle() = compose.waitUntil(15_000) {
        compose.onAllNodes(hasSetTextAction() and hasContentDescription("Title") and isEnabled()).fetchSemanticsNodes().size == 1
    }
    private fun confirmDraftAction() { compose.onNodeWithText("Confirm").performClick() }

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
                    requests++
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
        val denied = org.json.JSONObject(store.records.getValue(request.draftId).payload)
        assertFalse(denied.getBoolean("submitted")); assertFalse(denied.has("submission")); assertEquals("", denied.getString("operationKind"))
        compose.runOnIdle { permission = true }
        compose.waitForIdle()
        assertEquals(0, writer.creates)
        compose.onNodeWithText("New event").assertIsDisplayed()
        compose.onNode(isDialog() and hasAnyDescendant(hasText("New event")))
            .captureRoboImage("src/test/screenshots/integration_create_event.png")
        compose.onNodeWithText("Save").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { saved != null }
        assertEquals(2, requests); assertEquals(1, writer.creates)
        assertEquals(calendar.id, writer.saved?.calendarId)
        assertEquals(calendar.identity, writer.saved?.targetIdentity)
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

    @Test fun replacedStoredGenerationRejectsOldRestoredRouteBeforeAnySourceRead() {
        val writer = Writer(ref); val store = EventEditorControllerTest.Store()
        val source = EditableEvent(EventDetails(EventOccurrence(ref, EventTime.AllDay(CalendarDateRange.day(date)), "Private original", 1), calendar),
            EditToken("source-A"), EditableField.entries.toSet())
        val initial = source.details.editorSeed()
        val first = runBlocking { prepareEditDraft(initial, source, "stable-edit", store) } as DraftPreparationResult.Ready
        val stale = EventWriteRequest.fromLaunch(first.launch, date, ZoneId.of("UTC"))
        val old = store.records.getValue(stale.draftId)
        runBlocking { assertEquals(DraftChangeResult.Applied, store.compareAndSet(old.id, old, null)) }
        val next = runBlocking { prepareEditDraft(initial, source, old.id, store) } as DraftPreparationResult.Ready
        assertNotEquals(first.launch.generation, next.launch.generation)
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        restoration.setContent { DaylightTheme { EventWriteWorkflow(stale, writer, store, listOf(calendar), true, {}, {}, {}, requestWritePermission = { true }) } }
        shown("This draft could not be opened."); assertEquals(0, writer.loads)
        compose.onNodeWithText("Private original").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore(); shown("This draft could not be opened.")
        assertEquals(0, writer.loads); assertEquals(next.launch.generation, DraftBrowserCodec.read(store.records.getValue(old.id)).launch!!.generation)
    }

    @Test fun explicitReplacementRepublishesNewGenerationAndSurvivesStateRestorationWithoutAnAutomaticWrite() {
        val writer = Writer(ref); val store = EventEditorControllerTest.Store(); val initial = prepare(store)
        var route: EventWriteRequest? = null; var replacements = 0
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        restoration.setContent { DaylightTheme {
            var raw by rememberSaveable { mutableStateOf(initial.encode()) }
            val request = EventWriteRequest.decode(raw); SideEffect { route = request }
            EventWriteWorkflow(request, writer, store, listOf(calendar), true, {}, {}, {}, requestWritePermission = { true },
                onReplacedLaunch = { replacements++; raw = EventWriteRequest.fromLaunch(it, date, ZoneId.of("UTC")).encode() })
        } }
        shown("Save"); readyTitle()
        compose.onNode(hasSetTextAction() and hasContentDescription("Title")).performScrollTo().performTextReplacement("Retained copied fields")
        compose.onNodeWithText("Start a new draft").performScrollTo().performClick(); confirmDraftAction()
        compose.waitUntil(15_000) { compose.waitForIdle(); replacements == 1 && route?.draftId != initial.draftId }
        val replaced = requireNotNull(route); assertNotEquals(initial.generation, replaced.generation)
        assertFalse(store.records.containsKey(initial.draftId)); assertEquals(1, store.records.size)
        restoration.emulateSavedInstanceStateRestore(); shown("Retained copied fields")
        assertEquals(replaced, route); assertEquals(1, replacements); assertEquals(0, writer.loads); assertEquals(0, writer.creates)
        assertEquals("", org.json.JSONObject(store.records.getValue(replaced.draftId).payload).getString("calendarId"))
        compose.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test fun sourceUnavailableDraftCanBeDiscardedWithoutAProviderWriteOrAutomaticReplacement() = discardUnavailable(unbound = false)
    @Test fun unboundLegacyDraftDiscardsWithoutReadingCurrentSourceOrAdoptingItsNamespace() = discardUnavailable(unbound = true)
    private fun discardUnavailable(unbound: Boolean) {
        val store = EventEditorControllerTest.Store(); val writer = Writer(ref); var dismissed = false; var replacements = 0
        val source = EditableEvent(EventDetails(EventOccurrence(ref, EventTime.AllDay(CalendarDateRange.day(date)), "Retained old source", 1), calendar), EditToken("source-A"), EditableField.entries.toSet())
        val ready = runBlocking { prepareEditDraft(source.details.editorSeed(), source, "retained", store) } as DraftPreparationResult.Ready
        if (unbound) {
            val record = store.records.getValue(ready.launch.draftId); val raw = org.json.JSONObject(record.payload)
            listOf("feature", "schema", "generation", "revision").forEach(raw::remove)
            raw.put("sourceBaseline", org.json.JSONObject(raw.getString("sourceBaseline")).apply { remove("sourceCalendarIdentity") }.toString())
            store.records[record.id] = record.copy(version = 4, payload = raw.toString())
        }
        val launch = DraftBrowserCodec.read(store.records.getValue("retained")).launch!!
        val request = EventWriteRequest.fromLaunch(launch, date, ZoneId.of("UTC"))
        compose.setContent { DaylightTheme { EventWriteWorkflow(request, writer, store, listOf(calendar), true, { dismissed = true }, {}, {},
            requestWritePermission = { error("Discard must not prompt") }, onReplacedLaunch = { replacements++ }) } }
        shown("Discard draft")
        compose.onNodeWithText("Edit event").assertExists(); compose.onNodeWithText("New event").assertDoesNotExist()
        if (unbound) { assertEquals(0, writer.loads); compose.onNodeWithText("Start a new draft").assertDoesNotExist() }
        compose.onNodeWithText("Discard draft").performScrollTo().performClick(); confirmDraftAction()
        compose.waitUntil(15_000) { compose.waitForIdle(); dismissed }
        assertTrue(store.records.isEmpty()); assertEquals(0, replacements); assertEquals(0, writer.creates); assertEquals(0, writer.recovered)
    }

    @Test fun frozenHostReplacementWaitsForMatchingTypedRetirementProofAndNeverRewritesOriginal() {
        val store = EventEditorControllerTest.Store(); val writer = Writer(ref).apply { uncertain = true }; val initial = prepare(store)
        val retired = mutableListOf<RetirableOperation>(); var matching = false; var replacements = 0
        val port = object : OperationRetirement {
            override suspend fun retire(request: RetirableOperation): OperationRetirementResult {
                retired += request; return OperationRetirementResult.Retired(if (matching) request.operationId else "wrong-operation")
            }
            override suspend fun inspect(request: RetirableOperation): OperationRetirementResult = error("No invented unavailable acknowledgement")
        }
        compose.setContent { DaylightTheme { EventWriteWorkflow(initial, writer, store, listOf(calendar), true, {}, {}, {}, retirement = port,
            requestWritePermission = { true }, onReplacedLaunch = { replacements++ }) } }
        shown("Save"); readyTitle(); compose.onNodeWithText("Save").performClick()
        shown("We could not confirm the result of this change. Check its result before trying again.")
        val frozen = store.records.getValue(initial.draftId)
        compose.onNodeWithText("Start a new draft").performScrollTo().performClick(); confirmDraftAction()
        shown("The original change could not be identified safely. Your draft is kept for review.")
        assertEquals(frozen, store.records.getValue(initial.draftId)); assertEquals(0, replacements); assertEquals(1, writer.creates)
        assertTrue(retired.single() is RetirableOperation.Create)
        assertEquals(writer.saved, (retired.single() as RetirableOperation.Create).draft)
        matching = true
        compose.onNodeWithText("Start a new draft").performScrollTo().performClick(); confirmDraftAction()
        compose.waitUntil(15_000) { compose.waitForIdle(); replacements == 1 }
        assertEquals(retired[0], retired[1]); assertFalse(store.records.containsKey(initial.draftId)); assertEquals(1, store.records.size)
        assertEquals(1, writer.creates); assertEquals(0, writer.recovered)
    }
}
