package dev.swapnilraj.calendar.events

import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.*
import java.time.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CreateCalendarIdentityTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val a = eventTestCalendar().copy(displayName = "Original", account = CalendarAccount("a@example.test", "Work"), owner = "owner-a", providerId = "provider-a")
    private val b = a.copy(displayName = "Replacement", account = CalendarAccount("b@example.test", "Personal"), owner = "owner-b", providerId = "provider-b")
    private val initial = EventDraft(a.id, EventTime.Timed(Instant.parse("2028-06-15T09:00:00Z"), Instant.parse("2028-06-15T10:00:00Z")),
        ZoneOffset.UTC, "Retained appointment", privacy = 2, targetIdentity = a.identity)
    private val ref = EventRef(a.id, 42, (initial.time as EventTime.Timed).start, (initial.time as EventTime.Timed).end)
    private val fixture = EventEditorControllerTest(); private val writer = fixture.Writer(); private val store = EventEditorControllerTest.Store()
    private fun editor(seed: EventDraft = initial, existing: EditableEvent? = null, recovery: Boolean = false) =
        EventEditorController(seed, existing, "namespace", writer, store, scope, recoveryOnly = recovery, requireStoredDraft = true)
    private suspend fun prepare(seed: EventDraft = initial) = (prepareNewDraft(seed, "namespace", true, store) as DraftPreparationResult.Ready).launch
    private fun saved() = store.records.getValue("namespace")
    private fun legacyPending(rawBinding: Boolean = false) {
        val current = saved(); val raw = JSONObject(current.payload)
        raw.put("operationKind", "create").put("submitted", true)
        raw.put("submission", FrozenEditorSubmission.Create(raw.getString("operationId"), initial.copy(targetIdentity = null)).encode())
        if (!rawBinding) raw.remove("targetIdentity")
        store.records["namespace"] = current.copy(payload = raw.toString())
    }
    @After fun stop() { scope.cancel() }
    @Test fun boundDestinationPersistsThroughRawDraftSeedAndExactFrozenCommand() = runBlocking {
        val launch = prepare(); assertEquals(a.identity, launch.targetIdentity)
        assertEquals(a.identity, launch.seed(LocalDate.parse("2040-01-01"), ZoneId.of("Asia/Tokyo")).targetIdentity)
        val controller = editor(); writer.next = MutationResult.Uncertain("ignored", null); controller.save()
        assertEquals(a.identity, writer.createdDrafts.single().targetIdentity)
        val frozen = FrozenEditorSubmission.decode(JSONObject(saved().payload).getJSONObject("submission")) as FrozenEditorSubmission.Create
        assertEquals(a.identity, frozen.draft.targetIdentity)
        assertEquals(frozen, FrozenEditorSubmission.decode(frozen.encode()))
        assertEquals(a.identity, editor(initial.copy(targetIdentity = b.identity), recovery = true).state.fields.targetIdentity)
    }
    @Test fun legacyUnsubmittedTargetNeverAdoptsFreshSeedAndRequiresExplicitSameIdReselection() = runBlocking {
        prepare(); val raw = JSONObject(saved().payload).apply { remove("targetIdentity"); put("title", "Unfinished legacy title") }
        store.records["namespace"] = saved().copy(payload = raw.toString())
        val controller = editor(initial.copy(targetIdentity = b.identity))
        assertEquals("Unfinished legacy title", controller.state.fields.title); assertEquals("2", controller.state.fields.calendarId)
        assertTrue(controller.calendarSelectionRequired); controller.save(); assertTrue(writer.createdDrafts.isEmpty())
        assertEquals(EditorProblem.CALENDAR_REQUIRED, controller.state.problem)
        controller.selectCalendar(b); assertEquals(b.identity, controller.state.fields.targetIdentity)
        controller.save(); assertEquals(b.identity, writer.createdDrafts.single().targetIdentity)
    }
    @Test fun everyNamespaceDimensionDriftClearsAuthorityWhileRetainingRawFields() = runBlocking {
        for (replacement in listOf(a.copy(account = a.account.copy(name = "other@example.test")), a.copy(account = a.account.copy(type = "Other")),
            a.copy(owner = "other-owner"), a.copy(owner = null), a.copy(providerId = "other-provider"), a.copy(providerId = null))) {
            store.records.clear(); prepare(); val controller = editor()
            controller.change { it.copy(title = "Unfinished title", startDate = "2028-") }
            controller.observeCalendars(listOf(replacement))
            assertTrue(controller.calendarSelectionRequired); assertNull(controller.state.fields.targetIdentity)
            assertEquals("Unfinished title", controller.state.fields.title); assertEquals("2028-", controller.state.fields.startDate)
            controller.save(); assertTrue(writer.createdDrafts.isEmpty())
            controller.selectCalendar(replacement); assertEquals(replacement.identity, controller.state.fields.targetIdentity)
        }
    }
    @Test fun genericRawChangesCannotIssueOrReplaceCalendarIdentityProof() = runBlocking {
        prepare(); val controller = editor()
        controller.change { it.copy(targetIdentity = b.identity) }; assertEquals(a.identity, controller.state.fields.targetIdentity)
        controller.change { it.copy(calendarId = "8", targetIdentity = eventTestIdentity(8)) }
        assertNull(controller.state.fields.targetIdentity); controller.save(); assertTrue(writer.createdDrafts.isEmpty())
        controller.selectCalendar(eventTestCalendar(8)); assertEquals(eventTestIdentity(8), controller.state.fields.targetIdentity)
    }
    @Test fun returningCalendarMetadataNeverReissuesPreviouslyInvalidatedProof() = runBlocking {
        prepare(); val controller = editor(); controller.observeCalendars(listOf(b)); controller.observeCalendars(listOf(a))
        assertTrue(controller.calendarSelectionRequired); controller.save(); assertTrue(writer.createdDrafts.isEmpty())
        controller.selectCalendar(a); controller.save(); assertEquals(a.identity, writer.createdDrafts.single().targetIdentity)
    }
    @Test fun duplicateInheritedDefaultApprovalBindsFullIdentityAndNamespaceChangeResetsIt() = runBlocking {
        val details = EventDetails(EventOccurrence(ref, initial.time, initial.title, 1), a, eventZone = ZoneOffset.UTC, privacy = 0)
        val launch = (prepareDuplicateDraft(details, a.id, "namespace", store, targetIdentity = a.identity) as DraftPreparationResult.Ready).launch
        val controller = editor(launch.seed(LocalDate.MIN, ZoneOffset.UTC)); assertEquals("0", controller.state.fields.privacy)
        assertEquals(a.identity, controller.state.fields.defaultVisibilityIdentity)
        controller.observeCalendars(listOf(b)); assertNull(controller.state.fields.targetIdentity); assertEquals("", controller.state.fields.privacy)
        assertNull(controller.state.fields.defaultVisibilityIdentity); controller.selectCalendar(b); controller.save()
        assertTrue(writer.createdDrafts.isEmpty()); assertTrue(controller.visibilityChoiceRequired)
        controller.change { it.copy(privacy = "0") }; assertEquals(b.identity, controller.state.fields.defaultVisibilityIdentity)
        controller.flushDraft(); val restored = editor(initial.copy(targetIdentity = a.identity)); restored.observeCalendars(listOf(b))
        assertEquals(b.identity, restored.state.fields.defaultVisibilityIdentity); assertFalse(restored.calendarSelectionRequired)
        restored.save(); assertEquals(b.identity, writer.createdDrafts.single().targetIdentity); assertEquals(0, writer.createdDrafts.single().privacy)
    }
    @Test fun portableExplicitPrivacySurvivesDriftButTargetStillRequiresReselection() = runBlocking {
        for (privacy in 1..3) {
            store.records.clear(); val details = EventDetails(EventOccurrence(ref, initial.time, initial.title, 1), a, eventZone = ZoneOffset.UTC, privacy = privacy)
            val launch = (prepareDuplicateDraft(details, a.id, "namespace", store, targetIdentity = a.identity) as DraftPreparationResult.Ready).launch
            val controller = editor(launch.seed(LocalDate.MIN, ZoneOffset.UTC)); controller.observeCalendars(listOf(b))
            assertEquals(privacy.toString(), controller.state.fields.privacy); assertTrue(controller.calendarSelectionRequired)
            controller.save(); assertTrue(writer.createdDrafts.isEmpty()); controller.selectCalendar(b)
            assertFalse(controller.calendarSelectionRequired); assertFalse(controller.visibilityChoiceRequired)
        }
    }
    @Test fun numericOnlyDuplicateDestinationAndPrivacyApprovalDoNotBecomeIdentityProof() = runBlocking {
        val details = EventDetails(EventOccurrence(ref, initial.time, initial.title, 1), a, eventZone = ZoneOffset.UTC, privacy = 0)
        val launch = (prepareDuplicateDraft(details, a.id, "namespace", store) as DraftPreparationResult.Ready).launch
        assertFalse(launch.calendarSelected); assertNull(launch.targetIdentity)
        val controller = editor(launch.seed(LocalDate.MIN, ZoneOffset.UTC)); assertTrue(controller.calendarSelectionRequired)
        assertEquals("", controller.state.fields.privacy); controller.selectCalendar(a)
        assertTrue(controller.visibilityChoiceRequired); controller.save(); assertTrue(writer.createdDrafts.isEmpty())
    }
    @Test fun submittedLegacyUnboundCreateAllowsOnlyJournalRecoveryEvenWithRetryProof() = runBlocking {
        prepare(); legacyPending(); val exact = saved()
        val launch = DraftBrowserCodec.read(exact).launch!!; assertTrue(launch.needsRecovery)
        val controller = editor(initial.copy(targetIdentity = b.identity), recovery = true)
        controller.selectCalendar(b); controller.observeCalendars(listOf(b)); controller.save()
        writer.safeRecovery = true; controller.recover(); controller.retryOriginal()
        assertTrue(writer.createdDrafts.isEmpty()); assertFalse(controller.state.retryReady)
        assertTrue(EditorNotice.DESTINATION_RECOVERY_ONLY in controller.state.notices); assertEquals(exact, saved())
        writer.safeRecovery = false; writer.recovered = MutationResult.LocalSaved(ref); controller.recover()
        assertEquals(MutationResult.LocalSaved(ref), controller.state.result); assertTrue(store.records.isEmpty())
    }
    @Test fun submittedUnboundFrozenCreateCannotBorrowRawOrFreshNamespaceBinding() = runBlocking {
        prepare(); legacyPending(rawBinding = true); val exact = saved()
        val controller = editor(initial.copy(targetIdentity = b.identity), recovery = true)
        writer.safeRecovery = true; controller.recover(); controller.retryOriginal()
        assertFalse(controller.state.retryReady); assertTrue(writer.createdDrafts.isEmpty()); assertEquals(exact, saved())
    }
    @Test fun boundPendingRetryKeepsOriginalNamespaceAndNeverSubstitutesCurrentCalendar() = runBlocking {
        prepare(); writer.next = MutationResult.Uncertain("ignored", null); editor().save()
        val original = writer.createdDrafts.single(); val exact = saved()
        val controller = editor(initial.copy(targetIdentity = b.identity), recovery = true); controller.observeCalendars(listOf(b)); controller.selectCalendar(b)
        writer.safeRecovery = true; controller.recover(); writer.next = MutationResult.ValidationFailed(setOf(InvalidEventField.CALENDAR)); controller.retryOriginal()
        assertEquals(listOf(original, original), writer.createdDrafts); assertEquals(a.identity, writer.createdDrafts.last().targetIdentity)
        assertEquals(exact, saved()); assertEquals(1, writer.ids.distinct().size)
    }
    @Test fun unknownIdentitySchemaIsRetainedAndCalendarIdMismatchRequiresReselection() = runBlocking {
        prepare(); val original = saved()
        val bad = original.copy(payload = JSONObject(original.payload).apply { getJSONObject("targetIdentity").put("futureAuthority", "unknown") }.toString())
        store.records["namespace"] = bad; assertNull(DraftBrowserCodec.read(bad).launch)
        val unreadable = editor(); unreadable.save(); assertTrue(unreadable.state.draftReadFailed); assertEquals(bad, saved())
        store.records["namespace"] = original.copy(payload = JSONObject(original.payload).put("targetIdentity", CalendarIdentityJson.encode(eventTestIdentity(8))).toString())
        val mismatch = editor(); assertEquals("2", mismatch.state.fields.calendarId); assertTrue(mismatch.calendarSelectionRequired)
        mismatch.save(); assertTrue(writer.createdDrafts.isEmpty())
    }
    @Test fun frozenLegacyJsonOmitsMissingTargetWhileNewBindingChangesExactCommandSignature() {
        val legacy = FrozenEditorSubmission.Create("operation", initial.copy(targetIdentity = null))
        assertFalse(legacy.encode().getJSONObject("draft").has("targetIdentity")); assertEquals(legacy, FrozenEditorSubmission.decode(legacy.encode()))
        val boundA = FrozenEditorSubmission.Create("operation", initial); val boundB = boundA.copy(draft = initial.copy(targetIdentity = b.identity))
        assertNotEquals(boundA.encode().toString(), boundB.encode().toString()); assertEquals(boundA, FrozenEditorSubmission.decode(boundA.encode()))
    }
    @Test fun explicitNamespaceChangeAfterDefiniteFailureRotatesOperationId() = runBlocking {
        prepare(); writer.next = MutationResult.PermissionRequired; val controller = editor(); controller.save()
        val old = writer.ids.single(); controller.observeCalendars(listOf(b)); controller.selectCalendar(b); controller.save()
        assertNotEquals(old, writer.ids.last()); assertEquals(listOf(a.identity, b.identity), writer.createdDrafts.map { it.targetIdentity })
    }
    @Test fun blankAccountNamespaceIsUnprovenAndCannotBeIssuedBySelection() = runBlocking {
        val blank = a.copy(account = a.account.copy(name = ""))
        val launch = prepare(initial.copy(targetIdentity = blank.identity)); assertFalse(launch.calendarSelected)
        val controller = editor(initial.copy(targetIdentity = blank.identity)); assertTrue(controller.calendarSelectionRequired)
        controller.selectCalendar(blank); controller.save(); assertTrue(writer.createdDrafts.isEmpty())
        controller.selectCalendar(a); controller.save(); assertEquals(a.identity, writer.createdDrafts.single().targetIdentity)
    }
    @Test fun existingEditAndDeleteRemainTokenBoundWithoutCreateDestinationProof() = runBlocking {
        val draft = initial.copy(targetIdentity = null); val details = EventDetails(EventOccurrence(ref, initial.time, initial.title, 1), a)
        val source = EditableEvent(details, EditToken("event-token"), EditableField.entries.toSet())
        prepareEditDraft(draft, source, "namespace", store); val controller = editor(draft, source)
        controller.observeCalendars(listOf(b)); controller.change { it.copy(title = "Edited") }; controller.save()
        assertEquals(1, writer.updates); assertTrue(writer.createdDrafts.isEmpty())
        store.records.clear(); prepareEditDraft(draft, source, "namespace", store)
        val deleting = editor(draft, source); deleting.requestDelete(); deleting.confirmDelete(); assertEquals(1, writer.deletes)
    }
}
