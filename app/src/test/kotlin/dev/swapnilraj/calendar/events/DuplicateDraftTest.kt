package dev.swapnilraj.calendar.events

import android.provider.CalendarContract
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.*
import java.time.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DuplicateDraftTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val time = EventTime.Timed(Instant.parse("2028-06-15T09:00:00Z"), Instant.parse("2028-06-15T10:00:00Z"))
    private val ref = EventRef(CalendarId(2), 42, time.start, time.end, RecurrenceRef(41, time.start))
    private val details = EventDetails(EventOccurrence(ref, time, "Team planning", 1), eventTestCalendar(),
        location = "Room 4", description = "Bring notes\nhttps://example.test/meeting", organizer = "organizer@example.test",
        eventZone = ZoneId.of("Europe/London"), attendees = listOf(EventAttendee("guest@example.test", "Guest", 1, 1, 1)),
        reminders = listOf(EventReminder(10, CalendarContract.Reminders.METHOD_ALERT)), recurrenceRule = "FREQ=WEEKLY",
        availability = 0, privacy = 2)
    private val store = EventEditorControllerTest.Store()
    private val fixture = EventEditorControllerTest(); private val writer = fixture.Writer()
    private suspend fun prepare(source: EventDetails = details, target: CalendarId? = CalendarId(8), timedDefault: Int? = null, allDayDefault: Int? = null): EditorDraftLaunch =
        (prepareDuplicateDraft(source, target, "copy", store, timedDefault, allDayDefault, targetIdentity = target?.let { eventTestIdentity(it.value) }) as DraftPreparationResult.Ready).launch
    private fun editor(launch: EditorDraftLaunch) = EventEditorController(launch.seed(LocalDate.parse("2040-01-01"), ZoneOffset.UTC), null,
        launch.draftId, writer, store, scope, initialCalendarSelected = launch.calendarSelected, requireStoredDraft = true)
    @After fun stop() { scope.cancel() }
    @Test fun duplicatesOneOccurrenceAndCopiesKnownPrivateMetadataWithoutSourceIdentity() = runBlocking {
        val launch = prepare(); assertEquals(EditorDraftKind.CREATE, launch.kind); assertNull(launch.ref)
        val draft = launch.seed(LocalDate.parse("2040-01-01"), ZoneOffset.UTC)
        assertEquals(details.occurrence.title, draft.title); assertEquals(time, draft.time)
        assertEquals(details.eventZone, draft.eventZone); assertEquals(details.location, draft.location); assertEquals(details.description, draft.description)
        assertEquals(CalendarId(8), draft.calendarId); assertEquals(2, draft.privacy); assertEquals(0, draft.availability)
        assertEquals(listOf(10), draft.alertMinutes)
        assertEquals(listOf(EditorNotice.DUPLICATE_AS_ONE_OFF, EditorNotice.GUESTS_NOT_COPIED, EditorNotice.RECURRENCE_NOT_COPIED), launch.notices)
        val raw = JSONObject(store.records.getValue("copy").payload)
        assertEquals("create", raw.getString("target")); assertEquals("", raw.getString("sourceToken"))
        assertFalse(raw.toString().contains("guest@example.test")); assertFalse(raw.toString().contains("organizer@example.test"))
        assertFalse(raw.toString().contains("FREQ=WEEKLY")); assertFalse(raw.getString("sourceBaseline").contains("refEvent"))
        assertEquals(0, writer.creates); assertEquals(0, writer.updates); assertEquals(0, writer.deletes)
        editor(launch).save(); assertEquals(draft, writer.createdDrafts.single()); assertEquals(0, writer.updates)
    }
    @Test fun nullTargetRequiresExplicitCalendarAndConfidentialStaysConfidential() = runBlocking {
        val launch = prepare(details.copy(privacy = 1), target = null)
        assertFalse(launch.calendarSelected); val editor = editor(launch)
        assertEquals("", editor.state.fields.calendarId); assertEquals("1", editor.state.fields.privacy)
        editor.save(); assertEquals(0, writer.creates)
        editor.selectCalendar(eventTestCalendar(8)); editor.save()
        assertEquals(1, writer.createdDrafts.single().privacy)
    }
    @Test fun unknownVisibilityRequiresExplicitKnownChoiceAcrossRestore() = runBlocking {
        val launch = prepare(details.copy(privacy = 99, availability = null))
        assertTrue(EditorNotice.VISIBILITY_REVIEW_REQUIRED in launch.notices); assertTrue(EditorNotice.AVAILABILITY_REVIEW in launch.notices)
        val first = editor(launch); first.save(); assertEquals(EditorProblem.VISIBILITY_REQUIRED, first.state.problem)
        first.change { it.copy(title = "Retained copy", privacy = "") }; first.flushDraft()
        val restoredLaunch = DraftBrowserCodec.read(store.records.getValue("copy")).launch!!
        val restored = editor(restoredLaunch); assertTrue(restored.visibilityChoiceRequired); assertEquals(launch.notices, restored.state.notices)
        restored.change { it.copy(privacy = "0") }; restored.save()
        assertEquals(1, writer.creates); assertEquals(0, writer.createdDrafts.single().privacy); assertNull(writer.createdDrafts.single().availability)
    }
    @Test fun signedAlertRowsCopyButUnsupportedMethodsAndUnresolvedDefaultsExplainOmission() = runBlocking {
        val reminders = listOf(EventReminder(-540, 1), EventReminder(15, 1), EventReminder(15, 1), EventReminder(-1, 1), EventReminder(20, 2), EventReminder(-1, 0))
        val launch = prepare(details.copy(reminders = reminders))
        assertEquals(listOf(-540, 15), launch.seed(LocalDate.MIN, ZoneOffset.UTC).alertMinutes)
        assertTrue(EditorNotice.DEFAULT_REMINDERS_UNRESOLVED in launch.notices); assertTrue(EditorNotice.REMINDERS_NOT_COPIED in launch.notices)
        val first = editor(launch); first.save(); assertEquals(listOf(-540, 15), writer.createdDrafts.single().alertMinutes)
    }
    @Test fun explicitDefaultUsesEventKindAndNeverStoresSentinel() = runBlocking {
        val allDay = EventTime.AllDay(CalendarDateRange(LocalDate.parse("2028-06-15"), LocalDate.parse("2028-06-18")))
        val source = details.copy(occurrence = details.occurrence.copy(time = allDay), reminders = listOf(EventReminder(-1, 1)))
        val launch = prepare(source, timedDefault = 5, allDayDefault = -540)
        assertEquals(allDay, launch.seed(LocalDate.MIN, ZoneOffset.UTC).time)
        assertEquals(listOf(-540), launch.seed(LocalDate.MIN, ZoneOffset.UTC).alertMinutes)
        assertFalse(EditorNotice.DEFAULT_REMINDERS_UNRESOLVED in launch.notices)
        val editor = editor(launch); assertEquals("2028-06-17", editor.state.fields.endDate)
        editor.save(); assertEquals(allDay, writer.createdDrafts.single().time)
    }
    @Test fun invalidExplicitDefaultStaysUnresolvedAndEmptyRowsNeverInventDefaults() = runBlocking {
        val launch = prepare(details.copy(reminders = listOf(EventReminder(-1, 1))), timedDefault = -1)
        assertTrue(launch.seed(LocalDate.MIN, ZoneOffset.UTC).alertMinutes.isEmpty())
        assertTrue(EditorNotice.DEFAULT_REMINDERS_UNRESOLVED in launch.notices)
        store.records.clear()
        val empty = prepare(details.copy(reminders = emptyList()), timedDefault = 10)
        assertTrue(empty.seed(LocalDate.MIN, ZoneOffset.UTC).alertMinutes.isEmpty())
    }
    @Test fun repeatedPreparationRetainsPartialFieldsStableIdentityAndCopyNotices() = runBlocking {
        val launch = prepare(); val first = editor(launch)
        first.change { it.copy(startDate = "2028-", description = "Unfinished copy") }; first.flushDraft()
        val before = store.records.getValue("copy")
        val repeated = prepare(details.copy(location = "Source changed"))
        assertEquals(before, store.records.getValue("copy")); assertEquals("2028-", repeated.rawDate)
        val restored = editor(repeated); assertEquals(first.pendingOperationId, restored.pendingOperationId)
        assertEquals(launch.notices, restored.state.notices); assertEquals("Unfinished copy", restored.state.fields.description)
        assertEquals(0, writer.creates)
    }
    @Test fun oldDraftsHaveNoNoticesAndUnknownNoticePayloadFailsClosedWithoutDeletion() = runBlocking {
        prepareNewDraft(EventDraft(CalendarId(8), time, ZoneOffset.UTC, "Ordinary draft", targetIdentity = eventTestIdentity(8)), "copy", true, store)
        val ordinary = DraftBrowserCodec.read(store.records.getValue("copy")).launch!!
        assertTrue(ordinary.notices.isEmpty()); assertFalse(editor(ordinary).visibilityChoiceRequired)
        val record = store.records.getValue("copy")
        val bad = record.copy(payload = JSONObject(record.payload).put("notices", JSONArray(listOf("FUTURE_COPY_POLICY"))).toString())
        store.records["copy"] = bad
        assertNull(DraftBrowserCodec.read(bad).launch)
        val restored = editor(ordinary); restored.save(); assertTrue(restored.state.draftReadFailed)
        assertEquals(bad, store.records.getValue("copy")); assertEquals(0, writer.creates)
    }
    @Test fun preparationFailureAndDismissNeverMutateOriginalOrCreateEvent() = runBlocking {
        store.failWrites = true
        assertEquals(DraftPreparationResult.Unavailable, prepareDuplicateDraft(details, CalendarId(8), "copy", store))
        store.failWrites = false; val launch = prepare(); val editor = editor(launch)
        assertTrue(editor.prepareDismiss()); assertEquals(0, writer.creates); assertEquals(0, writer.updates); assertEquals(0, writer.deletes)
        assertEquals(2, details.privacy); assertEquals("FREQ=WEEKLY", details.recurrenceRule)
    }
}
