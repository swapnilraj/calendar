package dev.swapnilraj.calendar

import android.Manifest
import android.content.Intent
import android.provider.CalendarContract
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.calendar.CalendarOwnedMeetingWriterTest
import dev.swapnilraj.calendar.data.reminders.SavedReminderSettings
import dev.swapnilraj.calendar.ui.events.DraftBrowserCodec
import dev.swapnilraj.calendar.ui.events.meetings.owned.*
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

/** Actual app composition, SQL drafts/journal and native-shaped synthetic provider. These local
 * organizer journeys do not certify account sync, invitation delivery or physical device behavior. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="en-rUS-w393dp-h851dp-notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeOwnedMeetingJourneyTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val title="Synthetic organizer planning"
    private val identity=CalendarIdentity(CalendarId(2),CalendarAccount("owner@example.invalid","com.google"),"owner@example.invalid","calendar-A")
    private val selection=CalendarSelection.Explicit(setOf(CalendarId(2)))

    @Test fun detailsEditAddsAndRemovesGuestsWithSparseTitleChangeAndPreservesOtherResponses()=fixture {f->
        enter(f);title(f,"Synthetic edited organizer planning")
        // Remove one issued guest by its button position; the surviving row remains untouched.
        compose.onAllNodesWithText(f.app.getString(R.string.phone_event_remove)).onFirst().performScrollTo().performClick()
        compose.onNodeWithText(f.app.getString(R.string.phone_meeting_create_add_person)).performScrollTo().performClick()
        compose.onNodeWithContentDescription("New person 1: ${f.app.getString(R.string.phone_meeting_create_email)}").performScrollTo().performTextReplacement("new-guest@example.invalid")
        compose.onAllNodesWithText("Required").onLast().performScrollTo().performClick()
        compose.waitUntil(15_000) {document(f).fields.added.singleOrNull()?.type=="REQUIRED"}
        val before=document(f);assertNoWrite(f)
        save();shown(f.app.getString(R.string.owned_meeting_saved))
        val frozen=document(f);val command=frozen.frozen!!.command as MeetingCommand.EditOwned
        assertEquals(before.generation,frozen.generation);assertEquals(FieldChange.Set("Synthetic edited organizer planning"),command.patch.title)
        assertEquals(FieldChange.Keep,command.patch.time);assertEquals(FieldChange.Keep,command.patch.eventZone);assertEquals(FieldChange.Keep,command.patch.alertMinutes)
        assertEquals(1,f.provider.batchCount);assertEquals("Synthetic opaque event",f.value("events","opaque_event",f.ref.eventId))
        assertEquals("Synthetic edited organizer planning",f.value("events","title",f.ref.eventId))
        assertEquals(listOf("owner@example.invalid","optional@example.invalid","new-guest@example.invalid"),emails(f))
        assertEquals(f.originalOptional,row(f,"optional@example.invalid"));assertEquals(f.originalSelf,row(f,"owner@example.invalid"))
        assertEquals("3",row(f,"new-guest@example.invalid")[2]);assertEquals("1",row(f,"new-guest@example.invalid")[3])
        assertTrue(f.provider.writes.filter {it.path=="events"}.all {it.values==setOf("title")})
        assertEquals("VERIFIED",phase(f,frozen.operationId));done(f);assertTrue(records(f).isEmpty());assertEquals(1,f.provider.batchCount);assertFilters(f)
    }

    @Test fun issuedGuestRoleChangePreservesGuestIdentityResponseAndOpaqueFields()=fixture {f->
        enter(f)
        compose.onAllNodesWithText(f.app.getString(R.string.phone_meeting_create_required)).onLast().performScrollTo().performClick()
        compose.waitUntil(15_000) {document(f).fields.guests.any {it.type=="REQUIRED"}}
        save();shown(f.app.getString(R.string.owned_meeting_saved))
        val expected=f.originalOptional.toMutableList().also {it[3]="1"}
        assertEquals(expected,row(f,"optional@example.invalid"));assertEquals(f.originalSelf,row(f,"owner@example.invalid"))
        assertEquals(title,f.value("events","title",f.ref.eventId));assertEquals(3,f.provider.count("attendees"))
        assertEquals(1,f.provider.batchCount);assertTrue(f.provider.writes.all {it.path=="attendees"&&it.values==setOf("attendeeType")})
        done(f);assertTrue(records(f).isEmpty());assertFilters(f)
    }

    @Test fun cancellationRequiresConfirmationAndDoesNotApplyUnsavedFieldsOrDeleteGuests()=fixture {f->
        enter(f);title(f,"Synthetic unsaved cancellation title");assertNoWrite(f)
        // A durable row can be observed before its save coroutine publishes idle UI readiness.
        val more=hasContentDescription(f.app.getString(R.string.owned_meeting_more)) and hasClickAction() and isEnabled()
        compose.waitUntil(15_000) {compose.onAllNodes(more).fetchSemanticsNodes().size==1}
        compose.onNode(more).performClick()
        compose.onNodeWithText(f.app.getString(R.string.owned_meeting_cancel)).performClick()
        shown(f.app.getString(R.string.owned_meeting_cancel_warning));assertNoWrite(f)
        compose.onNode(hasText(f.app.getString(R.string.owned_meeting_cancel)) and hasClickAction()).performClick()
        shown(f.app.getString(R.string.owned_meeting_cancelled))
        val doc=document(f);assertTrue(doc.frozen!!.command is MeetingCommand.CancelOwned)
        assertEquals(title,f.value("events","title",f.ref.eventId));assertEquals("2",f.value("events","eventStatus",f.ref.eventId))
        assertEquals(3,f.provider.count("attendees"));assertEquals(f.originalOptional,row(f,"optional@example.invalid"));assertEquals(f.originalSelf,row(f,"owner@example.invalid"))
        assertEquals(1,f.provider.batchCount);assertTrue(f.provider.writes.all {it.path=="events"&&it.values==setOf("eventStatus")})
        done(f);assertTrue(records(f).isEmpty());assertEquals(1,f.provider.batchCount);assertFilters(f)
    }

    @Test fun closeRecreateAndRealDraftBrowserRestoreExactOwnedGenerationWithoutWrites()=fixture {f->
        var host=enter(f);title(f,"Synthetic retained organizer title")
        val before=document(f);close(f);stop(host);host=main(f);openDrafts()
        val browser=DraftBrowserCodec.read(records(f).single());assertNull(browser.launch);assertNotNull(browser.ownedMeeting)
        assertEquals(before.generation,browser.ownedMeeting!!.launch.generation)
        compose.onNodeWithText(f.app.getString(R.string.events_drafts_open)).performClick();shown(f.app.getString(R.string.owned_meeting_edit))
        compose.onNodeWithContentDescription(f.app.getString(R.string.phone_event_title)).assertTextEquals("Synthetic retained organizer title")
        compose.runOnUiThread {host.recreate()};shown(f.app.getString(R.string.owned_meeting_edit))
        assertEquals(before.generation,document(f).generation);assertEquals(before.operationId,document(f).operationId);assertEquals(before.fields,document(f).fields)
        assertNoWrite(f);assertFilters(f)
    }

    @Test fun grantingWritePermissionDoesNotFreezeOrSubmitUntilAnotherExplicitSave()=fixture(write=false) {f->
        val host=enter(f);title(f,"Synthetic permission checked title");save();shown(f.app.getString(R.string.owned_meeting_permission))
        assertArrayEquals(arrayOf(Manifest.permission.WRITE_CALENDAR),shadowOf(host.get()).lastRequestedPermission.requestedPermissions)
        val before=document(f);assertNull(before.frozen);assertNoWrite(f)
        shadowOf(f.app).grantPermissions(Manifest.permission.WRITE_CALENDAR)
        compose.runOnUiThread {host.pause().stop().restart().start().resume();host.recreate()}
        shown(f.app.getString(R.string.owned_meeting_edit));assertNull(document(f).frozen);assertEquals(before.generation,document(f).generation);assertNoWrite(f)
        save();shown(f.app.getString(R.string.owned_meeting_saved));assertEquals(1,f.provider.batchCount)
    }

    @Test fun changedCalendarNamespaceBlocksSaveAndRetainsRawOwnedDraft()=fixture {f->
        enter(f);title(f,"Synthetic namespace checked title");val before=document(f)
        f.provider.db.execSQL("UPDATE calendars SET _sync_id='foreign-calendar-B' WHERE _id=2")
        save();shown(f.app.getString(R.string.owned_meeting_source_changed))
        assertNoWrite(f);assertEquals(before.generation,document(f).generation);assertEquals(before.fields,document(f).fields);assertNull(document(f).frozen)
        assertEquals(title,f.value("events","title",f.ref.eventId))
    }

    @Test fun lifecycleReadLossKeepsMountedRawDraftAndFreshGrantRequiresExplicitSave()=fixture {f->
        val host=enter(f);title(f,"Synthetic mounted raw title");val before=document(f)
        shadowOf(f.app).denyPermissions(Manifest.permission.READ_CALENDAR)
        f.app.calendarReader.refresh();compose.runOnUiThread {host.pause().stop().restart().start().resume()}
        shown(f.app.getString(R.string.owned_meeting_read_permission))
        // The retained calendar behind the mask must also disappear from accessibility semantics.
        compose.onAllNodesWithText(title).assertCountEquals(0)
        compose.onNodeWithContentDescription(f.app.getString(R.string.phone_event_title)).assertDoesNotExist()
        assertEquals(before.generation,document(f).generation);assertEquals(before.fields,document(f).fields);assertNoWrite(f)
        shadowOf(f.app).grantPermissions(Manifest.permission.READ_CALENDAR)
        f.app.calendarReader.refresh();compose.runOnUiThread {host.pause().stop().restart().start().resume()}
        shown(f.app.getString(R.string.owned_meeting_edit))
        compose.onNodeWithContentDescription(f.app.getString(R.string.phone_event_title)).assertTextEquals("Synthetic mounted raw title")
        assertEquals(before.generation,document(f).generation);assertNull(document(f).frozen);assertNoWrite(f)
        save();shown(f.app.getString(R.string.owned_meeting_saved));assertEquals(1,f.provider.batchCount)
    }

    @Test fun committedReadLossMasksThenReopensAndRecoversWithoutSnapshotsWritePermissionOrReplay()=fixture {f->
        var host=enter(f);title(f,"Synthetic frozen organizer title");f.provider.failReadsAfterCommit=true
        save();shown(f.app.getString(R.string.owned_meeting_uncertain));val frozen=document(f);assertNotNull(frozen.frozen)
        assertEquals("COMMITTED",phase(f,frozen.operationId));assertEquals(1,f.provider.batchCount)
        f.provider.failReadsAfterCommit=false
        shadowOf(f.app).denyPermissions(Manifest.permission.READ_CALENDAR,Manifest.permission.WRITE_CALENDAR)
        f.app.calendarReader.refresh();compose.runOnUiThread {host.pause().stop().restart().start().resume()}
        shown(f.app.getString(R.string.owned_meeting_read_permission))
        // The retained calendar behind the mask must also disappear from accessibility semantics.
        compose.onAllNodesWithText(title).assertCountEquals(0)
        compose.onNodeWithContentDescription(f.app.getString(R.string.phone_event_title)).assertDoesNotExist()
        compose.onAllNodesWithText("required@example.invalid").assertCountEquals(0)
        compose.onNode(hasText(f.app.getString(R.string.owned_meeting_leave)) and hasAnyAncestor(isDialog())).performClick()
        shown("Allow calendar access")
        assertEquals(frozen.frozen,document(f).frozen);assertEquals(1,f.provider.batchCount)
        f.app.calendarLocalStore.writableDatabase.execSQL("DELETE FROM edit_snapshots")
        stop(host);shadowOf(f.app).grantPermissions(Manifest.permission.READ_CALENDAR)
        host=main(f);openDrafts();compose.onNodeWithText(f.app.getString(R.string.events_drafts_recover)).performClick()
        shown(f.app.getString(R.string.owned_meeting_recovery));assertEquals(frozen.generation,document(f).generation)
        compose.onNodeWithText(f.app.getString(R.string.owned_meeting_check)).performClick();shown(f.app.getString(R.string.owned_meeting_saved))
        assertEquals(1,f.provider.batchCount);assertEquals(frozen.frozen,document(f).frozen);done(f);assertTrue(records(f).isEmpty());assertFilters(f)
    }

    private fun enter(f:Fixture):ActivityController<MainActivity> {
        val host=main(f);shown(title);compose.onNodeWithText(title).performScrollTo().performClick()
        shown(f.app.getString(R.string.events_editor_edit),description=true);compose.onNodeWithContentDescription(f.app.getString(R.string.events_editor_edit)).performClick()
        shown(f.app.getString(R.string.owned_meeting_edit));compose.waitUntil(15_000) {records(f).singleOrNull()?.let {OwnedMeetingDraftCodec.describe(it)}!=null}
        assertNoWrite(f);return host
    }
    private fun title(f:Fixture,value:String) {compose.onNodeWithContentDescription(f.app.getString(R.string.phone_event_title)).performTextReplacement(value);compose.waitUntil(15_000) {document(f).fields.event.title==value}}
    private fun save() {
        val enabled=hasText("Save") and hasClickAction() and isEnabled()
        compose.waitUntil(15_000) {compose.onAllNodes(enabled).fetchSemanticsNodes().size==1}
        compose.onNode(enabled).performClick()
    }
    private fun close(f:Fixture) {compose.onNodeWithContentDescription(f.app.getString(R.string.owned_meeting_close)).performClick();compose.waitUntil(15_000) {compose.onAllNodesWithContentDescription(f.app.getString(R.string.owned_meeting_close)).fetchSemanticsNodes().isEmpty()}}
    private fun done(f:Fixture) {compose.onNodeWithText(f.app.getString(R.string.phone_event_done)).performClick();compose.waitUntil(15_000) {records(f).isEmpty()}}
    private fun openDrafts() {shown("Open navigation menu",description=true);compose.onNodeWithContentDescription("Open navigation menu").performClick();compose.onNodeWithText("Drafts").performClick();shown("Drafts")}
    private fun shown(value:String,description:Boolean=false)=compose.waitUntil(15_000) {if(description)compose.onAllNodesWithContentDescription(value).fetchSemanticsNodes().isNotEmpty() else compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty()}
    private fun main(f:Fixture)=Robolectric.buildActivity(MainActivity::class.java,Intent(f.app,MainActivity::class.java)).also {compose.runOnUiThread {it.setup().visible()};f.activities+=it}
    private fun stop(host:ActivityController<out ComponentActivity>) {compose.runOnUiThread {host.pause().stop().destroy()}}
    private fun records(f:Fixture)=runBlocking {f.app.calendarLocalStore.list()}
    private fun document(f:Fixture)=OwnedMeetingDraftCodec.decode(records(f).single())
    private fun phase(f:Fixture,id:String)=f.app.calendarLocalStore.readableDatabase.rawQuery("SELECT phase FROM mutation_journal WHERE id=?",arrayOf(id)).use {assertTrue(it.moveToFirst());it.getString(0)}
    private fun emails(f:Fixture)=f.provider.db.rawQuery("SELECT attendeeEmail FROM attendees ORDER BY _id",null).use {c->buildList {while(c.moveToNext())add(c.getString(0))}}
    private fun row(f:Fixture,email:String)=f.provider.db.rawQuery("SELECT _id,attendeeEmail,attendeeStatus,attendeeType,opaque_guest FROM attendees WHERE attendeeEmail=?",arrayOf(email)).use {c->assertTrue(c.moveToFirst());List(c.columnCount) {if(c.isNull(it))null else c.getString(it)}}
    private fun assertNoWrite(f:Fixture) {assertEquals(0,f.provider.batchCount);assertTrue(f.provider.writes.isEmpty());assertEquals(1,f.provider.count("events"));assertEquals(3,f.provider.count("attendees"))}
    private fun assertFilters(f:Fixture)=assertEquals(selection,runBlocking {f.app.settings.calendarSelection.first()})
    private inner class Fixture(val app:CalendarApp,val provider:CalendarOwnedMeetingWriterTest.Provider,val ref:EventRef) {
        val activities=mutableListOf<ActivityController<out ComponentActivity>>()
        val originalSelf=row(this,"owner@example.invalid");val originalOptional=row(this,"optional@example.invalid")
        fun value(table:String,key:String,id:Long)=provider.value(table,key,id)
    }
    private fun fixture(write:Boolean=true,block:(Fixture)->Unit) {
        val app=RuntimeEnvironment.getApplication() as CalendarApp
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR,Manifest.permission.WRITE_CALENDAR)
        val provider=CalendarOwnedMeetingWriterTest.Provider();ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY,provider)
        val ref=runBlocking {
            app.settings.saveReminderSettings(app.settings.readReminderSettings(),SavedReminderSettings());app.reminderRuntime.onResume().join()
            app.settings.setDisplaySettings(CalendarSettingsOptions(weatherVisible=false,theme=SettingsTheme.LIGHT,displayZone=DisplayZoneOption.Fixed(ZoneId.of("UTC"))))
            val calendars=(app.calendarReader.readCalendars() as CalendarReadState.Ready).value
            assertTrue(app.settings.setCalendarSelection(selection,calendars));assertTrue(app.settings.setCalendarView("AGENDA"))
            val start=LocalDate.now(ZoneOffset.UTC).atTime(9,0).toInstant(ZoneOffset.UTC)
            val source=(app.calendarMeetings.loadForCreate(identity) as MeetingReadResult.Ready).value
            val draft=EventDraft(CalendarId(2),EventTime.Timed(start,start.plusSeconds(3600)),ZoneId.of("UTC"),title,"Synthetic room","Synthetic notes",listOf(10),1,2,identity)
            val seeded=app.calendarMeetings.create("synthetic-owned-seed",source.token,draft,listOf(MeetingGuestInput("required@example.invalid",MeetingGuestType.REQUIRED,"Required"),MeetingGuestInput("optional@example.invalid",MeetingGuestType.OPTIONAL)))
            assertTrue(seeded.toString(),seeded is MeetingMutationResult.LocalSaved)
            (seeded as MeetingMutationResult.LocalSaved).selected
        }
        provider.db.execSQL("UPDATE events SET _sync_id='owned-event-A',uid2445='owned-public-A',opaque_event='Synthetic opaque event'")
        provider.db.execSQL("UPDATE attendees SET opaque_guest='Synthetic opaque guest'")
        provider.db.execSQL("UPDATE attendees SET attendeeStatus=4 WHERE attendeeEmail='optional@example.invalid'")
        provider.batchCount=0;provider.committed=false;provider.writes.clear();provider.guestQueries.clear();provider.base.queries.clear()
        if(!write)shadowOf(app).denyPermissions(Manifest.permission.WRITE_CALENDAR)
        val f=Fixture(app,provider,ref)
        try {block(f)}finally {f.activities.asReversed().forEach {if(!it.get().isDestroyed)stop(it)};provider.db.close()}
    }
}
