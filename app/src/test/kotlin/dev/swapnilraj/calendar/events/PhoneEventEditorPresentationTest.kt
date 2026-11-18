package dev.swapnilraj.calendar.events

import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.*
import java.time.*
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneEventEditorPresentationTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val store = EventEditorControllerTest.Store()
    private val writer = EventEditorControllerTest().Writer()
    private val draft = EventDraft(CalendarId(2), EventTime.Timed(Instant.parse("2028-06-15T09:00:00Z"), Instant.parse("2028-06-15T10:00:00Z")), ZoneOffset.UTC, "Synthetic event", targetIdentity = eventTestIdentity())
    private fun editor(existing: EditableEvent? = null) = preparedEditor(draft, existing, "phone", writer, store, scope)
    @After fun stop() { scope.cancel() }
    @Test fun localizedSummariesNeverReplaceInvalidRawValuesWithTodayOrAnotherTime() {
        assertTrue(PhoneEventEditorText.date("2028-06-15", Locale.US)!!.contains("2028"))
        assertNull(PhoneEventEditorText.date("2028-", Locale.US)); assertNull(PhoneEventEditorText.time("1:", true, Locale.US))
        assertNull(PhoneEventEditorText.zone("Not/AZone")); assertNull(PhoneEventEditorText.alerts("10,partial"))
        assertEquals(listOf(-540, 0, 15), PhoneEventEditorText.alerts("-540,0,15"))
    }
    @Test fun civilOverlapAndGapStayExplicitInsteadOfInventingOffsets() {
        assertEquals(listOf(ZoneOffset.ofHours(1), ZoneOffset.UTC), PhoneEventEditorText.offsets("2026-10-25", "01:30", "Europe/London"))
        assertTrue(PhoneEventEditorText.offsets("2026-03-29", "01:30", "Europe/London").isEmpty())
        assertTrue(PhoneEventEditorText.offsets("2026-", "partial", "Europe/London").isEmpty())
    }
    @Test fun topSaveHonorsChosenNamespaceAndFrozenRetryDoesNotEnableOrdinarySaving() {
        val editor = editor(); val view = PhoneEventEditorPresentation(editor, listOf(eventTestCalendar()))
        assertTrue(view.canSave); assertTrue(view.canEdit(EditableField.TIME))
        assertFalse(PhoneEventEditorPresentation(editor, listOf(eventTestCalendar().copy(providerId = "different-calendar"))).canSave)
        writer.next = MutationResult.PermissionRequired; editor.save()
        writer.safeRecovery = true; editor.recover()
        assertTrue(editor.state.retryReady); assertFalse(view.canSave); assertFalse(view.canEdit(EditableField.TITLE))
        assertEquals(1, writer.creates)
    }
    @Test fun importedOffsetZoneDoesNotBlockUnrelatedTitleAndAlertKeepPatches() {
        val imported = draft.copy(eventZone = ZoneOffset.ofHoursMinutes(5,30))
        val time = draft.time as EventTime.Timed
        val ref = EventRef(draft.calendarId, 42, time.start, time.end)
        val source = EditableEvent(EventDetails(EventOccurrence(ref, draft.time, draft.title, 1), eventTestCalendar(), eventZone = imported.eventZone), EditToken("imported"), EditableField.entries.toSet())
        val editor = preparedEditor(imported, source, "imported", writer, store, scope)
        editor.change { it.copy(title = "Title only, with a new alert", alerts = "10") }
        val view = PhoneEventEditorPresentation(editor, listOf(eventTestCalendar()))
        assertFalse(view.requiresProviderZoneChoice); assertTrue(view.canSave)
        editor.save(); assertEquals(1, writer.updates)
        assertEquals(FieldChange.Keep, writer.patch!!.time); assertEquals(FieldChange.Keep, writer.patch!!.eventZone)
        assertEquals(FieldChange.Set(listOf(10)), writer.patch!!.alertMinutes)
    }
    @Test fun authoredOffsetOnlyZoneNeedsRegionChoiceForCreateAndTemporalEdit() {
        val imported = draft.copy(eventZone = ZoneOffset.ofHoursMinutes(5,30))
        val created = preparedEditor(imported, null, "offset-create", writer, store, scope)
        assertTrue(PhoneEventEditorPresentation(created, listOf(eventTestCalendar())).requiresProviderZoneChoice)
        assertFalse(PhoneEventEditorPresentation(created, listOf(eventTestCalendar())).canSave)
        val time = draft.time as EventTime.Timed
        val ref = EventRef(draft.calendarId, 42, time.start, time.end)
        val source = EditableEvent(EventDetails(EventOccurrence(ref, draft.time, draft.title, 1), eventTestCalendar(), eventZone = imported.eventZone), EditToken("imported"), EditableField.entries.toSet())
        val edited = preparedEditor(imported, source, "offset-edit", writer, store, scope)
        edited.change { it.copy(startTime = "15:00") }
        val view = PhoneEventEditorPresentation(edited, listOf(eventTestCalendar()))
        assertTrue(view.requiresProviderZoneChoice); assertFalse(view.canSave)
        edited.change { it.copy(zone = "Asia/Kolkata") }
        assertFalse(view.requiresProviderZoneChoice); assertTrue(view.canSave)
        assertEquals(0, writer.creates); assertEquals(0, writer.updates)
    }
    @Test fun importedNullZoneTitleKeepAfterTravelUsesControllerProofInsteadOfFreshSystemZone() = runBlocking {
        val oldZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("GMT+05:30"))
            val imported = draft.copy(eventZone = null)
            val time = draft.time as EventTime.Timed
            val ref = EventRef(draft.calendarId, 42, time.start, time.end)
            val source = EditableEvent(EventDetails(EventOccurrence(ref, draft.time, draft.title, 1), eventTestCalendar()), EditToken("null-zone"), EditableField.entries.toSet())
            val first = preparedEditor(imported, source, "null-zone", writer, store, scope)
            first.change { it.copy(title = "Retained title only") }; assertTrue(first.flushDraft())
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"))
            val restored = preparedEditor(imported, source, "null-zone", writer, store, scope)
            val view = PhoneEventEditorPresentation(restored, listOf(eventTestCalendar()))
            assertFalse(restored.state.reviewRequired); assertFalse(restored.hasAuthoredTemporalChanges)
            assertFalse(view.requiresProviderZoneChoice); assertTrue(view.canSave)
            restored.save(); assertEquals(1, writer.updates)
            assertEquals(FieldChange.Keep, writer.patch!!.time); assertEquals(FieldChange.Keep, writer.patch!!.eventZone)
        } finally { TimeZone.setDefault(oldZone) }
    }
    @Test fun unknownRightsAndForeignConstructorCalendarCannotBecomeEditorAuthority() = runBlocking {
        val ref = EventRef(draft.calendarId, 42, (draft.time as EventTime.Timed).start, (draft.time as EventTime.Timed).end)
        val source = EditableEvent(EventDetails(EventOccurrence(ref, draft.time, draft.title, 1), eventTestCalendar()), EditToken("A"), setOf(EditableField.TITLE))
        val launch = (prepareEditDraft(draft, source, "phone", store) as DraftPreparationResult.Ready).launch
        val first = editor(source); val view = PhoneEventEditorPresentation(first, listOf(eventTestCalendar()))
        assertTrue(view.canEdit(EditableField.TITLE)); assertFalse(view.canEdit(EditableField.TIME)); assertFalse(view.canChooseCalendar)
        val foreign = source.copy(token = EditToken("B"), details = source.details.copy(calendar = eventTestCalendar().copy(providerId = "B")))
        val restored = EventEditorController(draft, foreign, launch.draftId, writer, store, scope, expectedGeneration = launch.generation)
        val retained = PhoneEventEditorPresentation(restored, listOf(eventTestCalendar()))
        assertTrue(restored.isEditing); assertTrue(restored.state.reviewRequired); assertNull(retained.calendar)
        assertFalse(retained.canSave); assertFalse(retained.canChooseCalendar)
    }
}
