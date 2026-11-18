package dev.swapnilraj.calendar

import android.Manifest
import android.content.Intent
import android.provider.CalendarContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.calendar.CalendarRecurringCreateTest
import dev.swapnilraj.calendar.data.reminders.SavedReminderSettings
import dev.swapnilraj.calendar.ui.EventWriteRequest
import dev.swapnilraj.calendar.ui.EventWriteWorkflow
import dev.swapnilraj.calendar.ui.MeetingCreationWorkflow
import dev.swapnilraj.calendar.ui.events.*
import dev.swapnilraj.calendar.ui.events.meetings.create.*
import dev.swapnilraj.calendar.ui.settings.*
import java.time.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver

/** Actual MainActivity/Add people/draft-browser composition with a native-shaped SQLite provider.
 * Synthetic local evidence only; guest invitation delivery and OS permission dialogs are external. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w393dp-h851dp-notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeMeetingCreateJourneyTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val title = "Synthetic work planning meeting"
    private val guest = "guest@example.invalid"
    private val none = CalendarSelection.Explicit(emptySet())

    @Test fun addPeoplePromotesOneExactDraftWithFreshGenerationAndRequiresFreshCalendarAuthority() = fixture { f ->
        main(f); create(f)
        val old = records(f).single(); val ordinary = OrdinaryDraftDocument.decode(old)
        assertNotNull(ordinary.fields.targetIdentity) // A saved default is a suggestion, not meeting authority.
        addPeople(f)
        val current = records(f).single(); val meeting = MeetingCreationDraftCodec.decode(current)
        assertEquals(old.id, current.id); assertNotEquals(ordinary.generation, meeting.generation)
        assertEquals(ordinary.fields, meeting.fields.event)
        assertNull(meeting.authority); assertNull(meeting.frozen); assertFalse(meeting.policyAccepted)
        assertEquals(emptyList<MeetingCreationGuestFields>(), meeting.fields.guests)
        shown(f.app.getString(R.string.phone_meeting_create_calendar_required))
        compose.onNodeWithText("Save").assertIsNotEnabled()
        assertNoWrite(f); assertFilters(f)
    }

    @Test fun explicitCalendarPeoplePolicyAndSaveWriteOneMeetingWithKnownReceiptThenExactCleanup() = fixture { f ->
        main(f); create(f); addPeople(f); complete(f)
        val before = document(f)
        assertTrue(before.policyAccepted); assertNotNull(before.authority); assertNull(before.frozen)
        assertNoWrite(f)
        save(); shown(f.app.getString(R.string.phone_meeting_create_saved))
        assertEquals(1, f.provider.batchCount); assertEquals(1, f.provider.count("events"))
        val frozen = document(f); val command = requireNotNull(frozen.frozen)
        assertEquals(before.generation, frozen.generation); assertEquals(before.operationId, command.operationId)
        assertEquals(before.fields, frozen.fields); assertEquals(title, f.provider.value("title"))
        assertEquals(command.draft.location, f.provider.value("eventLocation")); assertEquals(command.draft.description, f.provider.value("description"))
        val time = command.draft.time as EventTime.Timed
        assertEquals(time.start.toEpochMilli().toString(), f.provider.value("dtstart")); assertEquals(time.end.toEpochMilli().toString(), f.provider.value("dtend"))
        assertNull(f.provider.value("rrule")); assertNull(f.provider.value("duration"))
        assertEquals(2, f.provider.count("attendees")); assertEquals(command.draft.alertMinutes.size, f.provider.count("reminders"))
        f.provider.db.rawQuery("SELECT attendeeEmail,attendeeRelationship,attendeeType,attendeeStatus FROM attendees ORDER BY _id", null).use {
            assertTrue(it.moveToNext()); assertEquals("owner@example.invalid", it.getString(0)); assertEquals(2, it.getInt(1)); assertEquals(1, it.getInt(3))
            assertTrue(it.moveToNext()); assertEquals(guest, it.getString(0)); assertEquals(1, it.getInt(1)); assertEquals(1, it.getInt(2)); assertEquals(3, it.getInt(3)); assertFalse(it.moveToNext())
        }
        assertEquals("0", f.provider.value("guestsCanModify")); assertEquals("0", f.provider.value("guestsCanInviteOthers")); assertEquals("1", f.provider.value("guestsCanSeeGuests"))
        assertEquals("VERIFIED", phase(f, command.operationId))
        shown(f.app.getString(R.string.phone_meeting_create_saved_help))
        // A local provider commit does not remove the draft or claim delivered invitations.
        assertEquals(1, records(f).size)
        compose.onNodeWithText(f.app.getString(R.string.phone_event_done)).performClick()
        awaitNoEditor(f); assertTrue(records(f).isEmpty()); assertEquals(1, f.provider.batchCount); assertFilters(f)
    }

    @Test fun closingRecreatingAndBrowserOpeningKeepTheSameMeetingGenerationWithoutAutoSave() = fixture { f ->
        var host = main(f); create(f); addPeople(f); chooseCalendar(f); person(f)
        val before = document(f)
        compose.onNodeWithContentDescription(f.app.getString(R.string.phone_meeting_create_close)).performClick()
        awaitNoEditor(f)
        stop(host); host = main(f)
        openDrafts(); shown(title)
        compose.onNodeWithText(f.app.getString(R.string.events_drafts_open)).performClick()
        shown(f.app.getString(R.string.phone_meeting_create_title)); shown(guest)
        assertEquals(before.generation, document(f).generation); assertEquals(before.fields, document(f).fields)
        assertNoWrite(f)
        compose.runOnUiThread { host.recreate() }
        shown(f.app.getString(R.string.phone_meeting_create_title))
        assertEquals(before.generation, document(f).generation); assertEquals(before.operationId, document(f).operationId)
        assertNoWrite(f); assertFilters(f)
    }

    @Test fun writePermissionGrantAndRecreationRequireAnotherExplicitSaveWithoutFreezingEarly() = fixture(write = false) { f ->
        val host = main(f); create(f); addPeople(f); complete(f)
        save(); shown(f.app.getString(R.string.phone_meeting_create_permission))
        assertArrayEquals(arrayOf(Manifest.permission.WRITE_CALENDAR), shadowOf(host.get()).lastRequestedPermission.requestedPermissions)
        val before = document(f); assertNull(before.frozen); assertNoWrite(f)
        shadowOf(f.app).grantPermissions(Manifest.permission.WRITE_CALENDAR)
        compose.runOnUiThread { host.pause().stop().restart().start().resume() }
        compose.runOnUiThread { host.recreate() }
        shown(f.app.getString(R.string.phone_meeting_create_title))
        assertNull(document(f).frozen); assertEquals(before.generation, document(f).generation); assertNoWrite(f)
        save(); shown(f.app.getString(R.string.phone_meeting_create_saved))
        assertEquals(1, f.provider.batchCount)
    }

    @Test fun committedMeetingSurvivesReadLossAndBrowserRecoveryWithoutSnapshotsOrASecondCreate() = fixture { f ->
        var host = main(f); create(f); addPeople(f); complete(f)
        f.provider.failReadsAfterCommit = true
        save(); shown(f.app.getString(R.string.phone_meeting_create_uncertain))
        val frozen = records(f).single(); val doc = MeetingCreationDraftCodec.decode(frozen)
        assertNotNull(doc.frozen); assertEquals("COMMITTED", phase(f, doc.operationId)); assertEquals(1, f.provider.batchCount)
        f.app.calendarLocalStore.writableDatabase.delete("edit_snapshots", null, null)
        shadowOf(f.app).denyPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        compose.runOnUiThread { host.recreate() }
        shown("Allow calendar access")
        assertEquals(frozen, records(f).single()); assertEquals(1, f.provider.batchCount)
        f.provider.failReadsAfterCommit = false
        shadowOf(f.app).grantPermissions(Manifest.permission.READ_CALENDAR)
        // Reopen the durable browser route explicitly: permission recovery itself must not create.
        stop(host); host = main(f)
        openDrafts(); shown(f.app.getString(R.string.events_drafts_recover))
        compose.onNodeWithText(f.app.getString(R.string.events_drafts_recover)).performClick()
        shown(f.app.getString(R.string.phone_meeting_create_recovery_help))
        assertEquals(frozen, records(f).single()); assertEquals(1, f.provider.batchCount)
        compose.onNode(hasText(f.app.getString(R.string.phone_meeting_create_recovery)) and hasClickAction()).performScrollTo().performClick()
        shown(f.app.getString(R.string.phone_meeting_create_saved))
        assertEquals("VERIFIED", phase(f, doc.operationId)); assertEquals(1, f.provider.batchCount)
        assertEquals(doc.generation, document(f).generation); assertEquals(doc.operationId, document(f).operationId)
        compose.onNodeWithText(f.app.getString(R.string.phone_event_done)).performClick(); awaitNoEditor(f)
        assertTrue(records(f).isEmpty()); assertEquals(1, f.provider.count("events")); assertEquals(2, f.provider.count("attendees"))
        compose.runOnUiThread { host.recreate() }; shownTag("calendar_phone_month")
        assertEquals(1, f.provider.batchCount); assertTrue(records(f).isEmpty()); assertFilters(f)
    }

    @Test fun unknownPromotionAcknowledgmentRetainsExactlyOneSqlDraftAndNeverCallsTheRouteCallback() = fixture { f ->
        val date = LocalDate.of(2028, 1, 3)
        val initial = EventDraft(CalendarId(2), EventTime.Timed(date.atTime(9,0).toInstant(ZoneOffset.UTC), date.atTime(10,0).toInstant(ZoneOffset.UTC)), ZoneId.of("UTC"), title)
        val prepared = runBlocking { prepareNewDraft(initial, "synthetic-promotion-unknown", true, f.app.calendarLocalStore) } as DraftPreparationResult.Ready
        var hidden = false; var promoted = 0
        val port = object : AtomicDraftStore by f.app.calendarLocalStore {
            override suspend fun compareAndSet(id: String, expected: DraftRecord?, replacement: DraftRecord?): DraftChangeResult {
                val result = f.app.calendarLocalStore.compareAndSet(id, expected, replacement)
                if (replacement?.version == MeetingCreationDraftCodec.VERSION && result == DraftChangeResult.Applied) { hidden = true; return DraftChangeResult.Unavailable }
                return result
            }
            override suspend fun load(id: String): DraftRecord? {
                if (hidden) error("Synthetic unknown local acknowledgment")
                return f.app.calendarLocalStore.load(id)
            }
        }
        val host = Robolectric.buildActivity(ComponentActivity::class.java); f.activities += host
        compose.runOnUiThread {
            host.setup().visible(); host.get().setContent {
                EventWriteWorkflow(EventWriteRequest.fromLaunch(prepared.launch,date,ZoneId.of("UTC")), f.app.calendarWriter, port, emptyList(), true,
                    {}, {}, {}, retirement=f.app.operationRetirement, onPromotedMeeting={ promoted++ })
            }
        }
        shown(f.app.getString(R.string.phone_event_add_people))
        compose.onNodeWithText(f.app.getString(R.string.phone_event_add_people)).performScrollTo().performClick()
        shown(f.app.getString(R.string.phone_event_storage))
        assertEquals(0,promoted); assertEquals(1,records(f).size)
        assertEquals(prepared.launch.draftId,records(f).single().id)
        val promotedDoc=MeetingCreationDraftCodec.decode(records(f).single())
        assertNotEquals(prepared.launch.generation,promotedDoc.generation); assertNull(promotedDoc.authority); assertNull(promotedDoc.frozen)
        compose.onNodeWithText("Save").assertIsNotEnabled(); assertNoWrite(f)
        stop(host); main(f); openDrafts(); shown(title)
        compose.onNodeWithText(f.app.getString(R.string.events_drafts_open)).performClick()
        shown(f.app.getString(R.string.phone_meeting_create_title))
        assertEquals(promotedDoc.generation, document(f).generation); assertNoWrite(f)
    }

    @Test fun staleMeetingRouteGenerationCannotMountCurrentDraftOrExposeSave() = fixture { f ->
        val main = main(f); create(f); addPeople(f)
        val current = records(f).single(); val doc = MeetingCreationDraftCodec.decode(current)
        compose.onNodeWithContentDescription(f.app.getString(R.string.phone_meeting_create_close)).performClick()
        awaitNoEditor(f); stop(main)
        val host=Robolectric.buildActivity(ComponentActivity::class.java); f.activities+=host
        val oldRoute=doc.launch().copy(generation=java.util.UUID.randomUUID().toString())
        var saves=0
        compose.runOnUiThread {
            host.setup().visible(); host.get().setContent {
                MeetingCreationWorkflow(oldRoute,f.app.calendarMeetings,f.app.calendarLocalStore,emptyList(),f.app.operationRetirement,
                    {true},true,{}, {saves++})
            }
        }
        shown(f.app.getString(R.string.events_drafts_unreadable))
        compose.onNodeWithContentDescription(f.app.getString(R.string.phone_event_title)).assertDoesNotExist()
        compose.onAllNodesWithText("Save").assertCountEquals(0)
        assertEquals(current,records(f).single()); assertEquals(0,saves); assertNoWrite(f)
    }

    @Test fun actualAddPeopleHostRendersMeetingPeopleAndExplicitPolicy() = fixture { f ->
        main(f); create(f); addPeople(f); chooseCalendar(f); person(f)
        compose.onNodeWithContentDescription(f.app.getString(R.string.phone_meeting_create_close)).performClick()
        awaitNoEditor(f)
        // The app clock is intentionally live. Stabilize only this synthetic stored form before
        // reopening it through the real browser; capture must not depend on today's hour/date.
        val record=records(f).single(); val doc=MeetingCreationDraftCodec.decode(record)
        val stable=doc.copy(fields=doc.fields.copy(event=doc.fields.event.copy(startDate="2028-01-03",endDate="2028-01-03",
            startTime="09:00",endTime="10:00",zone="UTC",startOffset="Z",endOffset="Z")))
        assertEquals(DraftChangeResult.Applied,runBlocking {f.app.calendarLocalStore.compareAndSet(record.id,record,
            MeetingCreationDraftCodec.record(stable,Instant.parse("2026-11-18T09:00:00Z")))})
        openDrafts();compose.onNodeWithText(f.app.getString(R.string.events_drafts_open)).performClick()
        shown(f.app.getString(R.string.phone_meeting_create_title))
        compose.onNodeWithText(f.app.getString(R.string.phone_meeting_create_policy_ack)).performScrollTo().assertIsDisplayed()
        compose.waitForIdle()
        compose.onNode(isDialog()).captureRoboImage("src/test/screenshots/integration_live_meeting_create.png")
        assertNoWrite(f)
    }

    private fun create(f:Fixture) {
        shown(f.app.getString(R.string.navigation_create_event))
        compose.onNodeWithText(f.app.getString(R.string.navigation_create_event)).performClick()
        shown(f.app.getString(R.string.events_editor_create))
        compose.onNodeWithContentDescription(f.app.getString(R.string.phone_event_title)).performTextReplacement(title)
        compose.waitUntil(15_000) { records(f).singleOrNull()?.payload?.contains(title)==true }
    }
    private fun addPeople(f:Fixture) {
        compose.onNodeWithText(f.app.getString(R.string.phone_event_add_people)).performScrollTo().performClick()
        shown(f.app.getString(R.string.phone_meeting_create_title))
    }
    private fun chooseCalendar(f:Fixture) {
        val calendar=hasText("Synthetic calendar") and hasClickAction()
        compose.onNode(calendar).performScrollTo().performClick()
        val radio=hasText("Synthetic calendar") and SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.RadioButton) and hasAnyAncestor(isDialog())
        compose.onNode(radio).performClick()
        compose.waitUntil(15_000) { document(f).authority!=null }
    }
    private fun person(f:Fixture) {
        compose.onNodeWithText(f.app.getString(R.string.phone_meeting_create_add_person)).performScrollTo().performClick()
        val label="Person 1: ${f.app.getString(R.string.phone_meeting_create_email)}"
        compose.onNodeWithContentDescription(label).performScrollTo().performTextReplacement(guest)
        compose.waitUntil(15_000) { document(f).fields.guests.singleOrNull()?.email==guest }
        compose.onNodeWithContentDescription("Person 1: ${f.app.getString(R.string.phone_meeting_create_name)}").performScrollTo().performTextReplacement("Synthetic guest")
        compose.onNodeWithText(f.app.getString(R.string.phone_meeting_create_required)).performScrollTo().performClick()
        compose.waitUntil(15_000) { document(f).fields.guests.singleOrNull()?.type=="REQUIRED" }
    }
    private fun complete(f:Fixture) {
        chooseCalendar(f); person(f)
        compose.onNodeWithText(f.app.getString(R.string.phone_meeting_create_policy_ack)).performScrollTo().performClick()
        compose.waitUntil(15_000) { document(f).policyAccepted }
    }
    private fun save() { compose.onNode(hasText("Save") and hasClickAction() and isEnabled()).performClick() }
    private fun records(f:Fixture)=runBlocking {f.app.calendarLocalStore.list()}
    private fun document(f:Fixture)=MeetingCreationDraftCodec.decode(records(f).single())
    private fun phase(f:Fixture,id:String):String?=f.app.calendarLocalStore.readableDatabase.rawQuery("SELECT phase FROM mutation_journal WHERE id=?",arrayOf(id)).use {if(it.moveToFirst())it.getString(0) else null}
    private fun shown(value:String)=compose.waitUntil(15_000) {compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty()}
    private fun shownTag(tag:String)=compose.waitUntil(15_000) {compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}
    private fun awaitNoEditor(f:Fixture)=compose.waitUntil(15_000) {compose.onAllNodesWithContentDescription(f.app.getString(R.string.phone_meeting_create_close)).fetchSemanticsNodes().isEmpty()}
    private fun openDrafts() { shownTag("calendar_phone_month"); compose.onNodeWithContentDescription("Open navigation menu").performClick();compose.onNodeWithText("Drafts").performClick();shown("Drafts") }
    private fun main(f:Fixture)=Robolectric.buildActivity(MainActivity::class.java,Intent(f.app,MainActivity::class.java)).also {compose.runOnUiThread {it.setup().visible()};f.activities+=it}
    private fun stop(host:ActivityController<out ComponentActivity>) {compose.runOnUiThread {host.pause().stop().destroy()} }
    private fun assertNoWrite(f:Fixture) { assertEquals(0,f.provider.batchCount);assertEquals(0,f.provider.count("events"));assertEquals(0,f.provider.count("attendees"));f.app.calendarLocalStore.readableDatabase.rawQuery("SELECT COUNT(*) FROM mutation_journal",null).use {it.moveToFirst();assertEquals(0,it.getInt(0))} }
    private fun assertFilters(f:Fixture)=assertEquals(none,runBlocking {f.app.settings.calendarSelection.first()})
    private class Fixture(val app:CalendarApp,val provider:CalendarRecurringCreateTest.Provider) {val activities=mutableListOf<ActivityController<out ComponentActivity>>()}
    private fun fixture(write:Boolean=true,block:(Fixture)->Unit) {
        val app=RuntimeEnvironment.getApplication() as CalendarApp
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)
        if(write)shadowOf(app).grantPermissions(Manifest.permission.WRITE_CALENDAR) else shadowOf(app).denyPermissions(Manifest.permission.WRITE_CALENDAR)
        val provider=CalendarRecurringCreateTest.Provider().apply {nativeMeetingMode=true;db.execSQL("ALTER TABLE calendars ADD COLUMN allowedAttendeeTypes TEXT DEFAULT '0,1,2'")}
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY,provider)
        runBlocking {
            app.settings.saveReminderSettings(app.settings.readReminderSettings(),SavedReminderSettings());app.reminderRuntime.onResume().join()
            app.settings.setDisplaySettings(CalendarSettingsOptions(weatherVisible=false,theme=SettingsTheme.LIGHT,displayZone=DisplayZoneOption.Fixed(ZoneId.of("UTC"))))
            val calendars=(app.calendarReader.readCalendars() as CalendarReadState.Ready).value
            assertTrue(app.settings.setDefaultCalendar(CalendarId(2),calendars));assertTrue(app.settings.setCalendarSelection(none,calendars))
        }
        val f=Fixture(app,provider)
        try {block(f)}finally {f.activities.asReversed().forEach {if(!it.get().isDestroyed)stop(it)};provider.db.close()}
    }
}
