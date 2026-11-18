package dev.swapnilraj.calendar

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.provider.CalendarContract
import android.provider.ContactsContract
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.calendar.CalendarOwnedMeetingWriterTest
import dev.swapnilraj.calendar.data.reminders.SavedReminderSettings
import dev.swapnilraj.calendar.ui.events.meetings.create.MeetingCreationDraftCodec
import dev.swapnilraj.calendar.ui.events.meetings.owned.OwnedMeetingDraftCodec
import dev.swapnilraj.calendar.ui.settings.*
import java.time.*
import java.util.concurrent.CopyOnWriteArrayList
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

/** Actual MainActivity/permission registry, production contacts adapter, raw SQLite drafts and
 * dedicated fake providers. No physical contacts UI, real accounts or guest delivery is certified. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="en-rUS-w393dp-h851dp-notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeContactGuestPickerJourneyTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val email=" Synthetic.Guest@example.invalid "
    private val name=" Synthetic Guest "
    private val title="Synthetic contact picker appointment"

    @Test fun preGrantedContactsStillNeedSessionOptInAndSelectionOnlyAppendsRawDraftGuest()=fixture(contactsGranted=true) {f->
        val host=createPicker(f);query(f,"Synthetic")
        settleDebounce();assertTrue(f.contacts.requests.isEmpty());assertNull(shadowOf(host.get()).lastRequestedPermission);assertNoCalendarWrite(f)
        optIn(f);awaitQuery(f,1);assertLocalQuery(f)
        choose(f);done(f)
        val doc=MeetingCreationDraftCodec.decode(records(f).single())
        assertEquals(email,doc.fields.guests.single().email);assertEquals(name,doc.fields.guests.single().name);assertEquals("",doc.fields.guests.single().type)
        assertNull(doc.frozen);assertFalse(doc.policyAccepted);assertNull(shadowOf(host.get()).lastRequestedPermission)
        assertNoCalendarWrite(f)
    }

    @Test fun permissionGrantSearchesOnlyTheOptedInSessionAndNeverAppendsOrSavesAutomatically()=fixture {f->
        val host=createPicker(f);query(f,"Synthetic");optIn(f)
        val request=shadowOf(host.get()).lastRequestedPermission
        assertArrayEquals(arrayOf(Manifest.permission.READ_CONTACTS),request.requestedPermissions)
        assertTrue(f.contacts.requests.isEmpty());assertTrue(MeetingCreationDraftCodec.decode(records(f).single()).fields.guests.isEmpty())
        compose.runOnUiThread {
            shadowOf(f.app).grantPermissions(Manifest.permission.READ_CONTACTS)
            host.get().onRequestPermissionsResult(request.requestCode,request.requestedPermissions,intArrayOf(PackageManager.PERMISSION_GRANTED))
        }
        awaitQuery(f,1);assertLocalQuery(f)
        assertTrue(MeetingCreationDraftCodec.decode(records(f).single()).fields.guests.isEmpty());assertNoCalendarWrite(f)
        choose(f);done(f);assertEquals(email,MeetingCreationDraftCodec.decode(records(f).single()).fields.guests.single().email);assertNoCalendarWrite(f)
    }

    @Test fun latePermissionGrantAfterBackAndReopenCannotOptInTheNewSession()=fixture {f->
        val host=createPicker(f);query(f,"Synthetic");optIn(f)
        val request=shadowOf(host.get()).lastRequestedPermission
        assertArrayEquals(arrayOf(Manifest.permission.READ_CONTACTS),request.requestedPermissions)
        assertTrue(f.contacts.requests.isEmpty())
        back(f);openPicker(f);query(f,"Synthetic")
        compose.runOnUiThread {
            shadowOf(f.app).grantPermissions(Manifest.permission.READ_CONTACTS)
            host.get().onRequestPermissionsResult(request.requestCode,request.requestedPermissions,intArrayOf(PackageManager.PERMISSION_GRANTED))
            host.pause().stop().restart().start().resume()
        }
        shown(f.app.getString(R.string.guest_picker_enable_contacts));settleDebounce()
        assertTrue(f.contacts.requests.isEmpty());assertTrue(MeetingCreationDraftCodec.decode(records(f).single()).fields.guests.isEmpty());assertNoCalendarWrite(f)
        optIn(f);awaitQuery(f,1);choose(f);done(f);assertNoCalendarWrite(f)
    }

    @Test fun activityRecreationKeepsRawGuestDraftButDoesNotRestoreContactsConsentOrSearch()=fixture(contactsGranted=true) {f->
        val host=createPicker(f);query(f,"Synthetic");optIn(f);awaitQuery(f,1);choose(f);done(f)
        val before=MeetingCreationDraftCodec.decode(records(f).single())
        compose.runOnUiThread {host.recreate()}
        shown(f.app.getString(R.string.phone_meeting_create_title));openPicker(f);query(f,"Synthetic");settleDebounce()
        assertEquals(1,f.contacts.requests.size);shown(f.app.getString(R.string.guest_picker_enable_contacts))
        val after=MeetingCreationDraftCodec.decode(records(f).single());assertEquals(before.generation,after.generation);assertEquals(before.fields,after.fields)
        assertNull(after.frozen);assertNoCalendarWrite(f)
    }

    @Test fun ownedEditorUsesSameOptInPickerWithoutChangingIssuedOpaqueGuestsOrProviderRows()=fixture(contactsGranted=true,owned=true) {f->
        main(f);shown(title);compose.onNodeWithText(title).performScrollTo().performClick()
        shown(f.app.getString(R.string.events_editor_edit),description=true)
        compose.onNodeWithContentDescription(f.app.getString(R.string.events_editor_edit)).performClick();shown(f.app.getString(R.string.owned_meeting_edit))
        compose.waitUntil(15_000) {records(f).singleOrNull()?.let {OwnedMeetingDraftCodec.describe(it)}!=null}
        val before=OwnedMeetingDraftCodec.decode(records(f).single());val rows=f.calendar.db.rawQuery("SELECT _id,attendeeEmail,attendeeStatus,attendeeType,opaque_guest FROM attendees ORDER BY _id",null).use {c->buildList {while(c.moveToNext())add(List(c.columnCount) {if(c.isNull(it))null else c.getString(it)})}}
        openPicker(f);query(f,"Synthetic");settleDebounce();assertTrue(f.contacts.requests.isEmpty())
        optIn(f);awaitQuery(f,1);choose(f);done(f)
        val after=OwnedMeetingDraftCodec.decode(records(f).single());assertEquals(before.generation,after.generation);assertEquals(before.fields.guests,after.fields.guests)
        assertEquals(email,after.fields.added.single().email);assertEquals(name,after.fields.added.single().name);assertEquals("",after.fields.added.single().type);assertNull(after.frozen)
        val current=f.calendar.db.rawQuery("SELECT _id,attendeeEmail,attendeeStatus,attendeeType,opaque_guest FROM attendees ORDER BY _id",null).use {c->buildList {while(c.moveToNext())add(List(c.columnCount) {if(c.isNull(it))null else c.getString(it)})}}
        assertEquals(rows,current);assertNoCalendarWrite(f)
    }

    private fun createPicker(f:Fixture):ActivityController<MainActivity> {
        val host=main(f);shown(f.app.getString(R.string.navigation_create_event));compose.onNodeWithText(f.app.getString(R.string.navigation_create_event)).performClick()
        shown(f.app.getString(R.string.events_editor_create));compose.onNodeWithContentDescription(f.app.getString(R.string.phone_event_title)).performTextReplacement(title)
        compose.onNodeWithText(f.app.getString(R.string.phone_event_add_people)).performScrollTo().performClick();shown(f.app.getString(R.string.phone_meeting_create_title))
        openPicker(f);return host
    }
    private fun openPicker(f:Fixture) {shown(f.app.getString(R.string.guest_picker_open));compose.onNodeWithText(f.app.getString(R.string.guest_picker_open)).performScrollTo().performClick();shown(f.app.getString(R.string.guest_picker_search))}
    private fun query(f:Fixture,text:String)=compose.onNodeWithText(f.app.getString(R.string.guest_picker_search)).performTextReplacement(text)
    private fun optIn(f:Fixture)=compose.onNodeWithText(f.app.getString(R.string.guest_picker_enable_contacts)).performClick()
    private fun choose(f:Fixture) {shown(name);compose.onNodeWithText(name).performScrollTo().performClick()}
    private fun done(f:Fixture) {compose.onNodeWithText(f.app.getString(R.string.phone_event_done)).performClick();compose.waitUntil(15_000) {compose.onAllNodesWithText(f.app.getString(R.string.guest_picker_search)).fetchSemanticsNodes().isEmpty()}}
    private fun back(f:Fixture) {compose.onNodeWithText(f.app.getString(R.string.guest_picker_back)).performClick();compose.waitUntil(15_000) {compose.onAllNodesWithText(f.app.getString(R.string.guest_picker_search)).fetchSemanticsNodes().isEmpty()}}
    private fun awaitQuery(f:Fixture,count:Int) {compose.waitUntil(15_000) {f.contacts.requests.size>=count};shown(name);assertEquals(count,f.contacts.requests.size)}
    private fun settleDebounce() {val began=System.nanoTime();compose.waitUntil(2_000) {System.nanoTime()-began>=650_000_000};compose.waitForIdle()}
    private fun shown(text:String,description:Boolean=false)=compose.waitUntil(15_000) {if(description)compose.onAllNodesWithContentDescription(text).fetchSemanticsNodes().isNotEmpty() else compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()}
    private fun records(f:Fixture)=runBlocking {f.app.calendarLocalStore.list()}
    private fun main(f:Fixture)=Robolectric.buildActivity(MainActivity::class.java,Intent(f.app,MainActivity::class.java)).also {compose.runOnUiThread {it.setup().visible()};f.activities+=it}
    private fun stop(host:ActivityController<out ComponentActivity>) {compose.runOnUiThread {host.pause().stop().destroy()}}
    private fun assertLocalQuery(f:Fixture) {
        val request=f.contacts.requests.single();assertEquals(ContactsContract.CommonDataKinds.Email.CONTENT_FILTER_URI.pathSegments+"Synthetic",request.uri.pathSegments)
        assertEquals(setOf(ContactsContract.DIRECTORY_PARAM_KEY,ContactsContract.LIMIT_PARAM_KEY),request.uri.queryParameterNames)
        assertEquals("0",request.uri.getQueryParameter(ContactsContract.DIRECTORY_PARAM_KEY));assertEquals(listOf(ContactsContract.CommonDataKinds.Email.ADDRESS,ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),request.fields)
    }
    private fun assertNoCalendarWrite(f:Fixture) {assertEquals(0,f.calendar.batchCount);assertTrue(f.calendar.writes.isEmpty());assertEquals(f.initialEvents,f.calendar.count("events"));assertEquals(f.initialGuests,f.calendar.count("attendees"));assertEquals(f.initialJournal,journalCount(f.app))}
    private fun journalCount(app:CalendarApp)=app.calendarLocalStore.readableDatabase.rawQuery("SELECT COUNT(*) FROM mutation_journal",null).use {it.moveToFirst();it.getInt(0)}
    private data class Request(val uri:Uri,val fields:List<String>?)
    private inner class ContactsProvider:ContentProvider() {
        val requests=CopyOnWriteArrayList<Request>()
        override fun onCreate()=true
        override fun getType(uri:Uri):String?=null
        override fun query(uri:Uri,fields:Array<out String>?,where:String?,args:Array<out String>?,sort:String?):Cursor=error("Cancellation-aware contacts query required")
        override fun query(uri:Uri,fields:Array<out String>?,where:String?,args:Array<out String>?,sort:String?,signal:CancellationSignal?):Cursor {
            check(where==null&&args==null&&sort==null&&signal!=null);signal.throwIfCanceled();requests+=Request(uri,fields?.toList())
            return MatrixCursor(arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS,ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)).apply {addRow(arrayOf(email,name))}
        }
        override fun insert(uri:Uri,values:ContentValues?):Uri=throw AssertionError("No contact writes")
        override fun update(uri:Uri,values:ContentValues?,where:String?,args:Array<out String>?):Int=throw AssertionError("No contact writes")
        override fun delete(uri:Uri,where:String?,args:Array<out String>?):Int=throw AssertionError("No contact writes")
    }
    private class Fixture(val app:CalendarApp,val calendar:CalendarOwnedMeetingWriterTest.Provider,val contacts:ContactsProvider,val initialEvents:Int,val initialGuests:Int,val initialJournal:Int) {val activities=mutableListOf<ActivityController<out ComponentActivity>>()}
    private fun fixture(contactsGranted:Boolean=false,owned:Boolean=false,block:(Fixture)->Unit) {
        val app=RuntimeEnvironment.getApplication() as CalendarApp
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR,Manifest.permission.WRITE_CALENDAR)
        if(contactsGranted)shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS) else shadowOf(app).denyPermissions(Manifest.permission.READ_CONTACTS)
        val calendar=CalendarOwnedMeetingWriterTest.Provider();val contacts=ContactsProvider()
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY,calendar);ShadowContentResolver.registerProviderInternal(ContactsContract.AUTHORITY,contacts)
        runBlocking {
            app.settings.saveReminderSettings(app.settings.readReminderSettings(),SavedReminderSettings());app.reminderRuntime.onResume().join()
            app.settings.setDisplaySettings(CalendarSettingsOptions(weatherVisible=false,theme=SettingsTheme.LIGHT,displayZone=DisplayZoneOption.Fixed(ZoneId.of("UTC"))))
            val calendars=(app.calendarReader.readCalendars() as CalendarReadState.Ready).value
            assertTrue(app.settings.setDefaultCalendar(CalendarId(2),calendars));assertTrue(app.settings.setCalendarSelection(CalendarSelection.Explicit(if(owned)setOf(CalendarId(2)) else emptySet()),calendars))
            if(owned) {
                assertTrue(app.settings.setCalendarView("AGENDA"))
                val identity=calendars.single {it.id==CalendarId(2)}.identity
                val source=(app.calendarMeetings.loadForCreate(identity) as MeetingReadResult.Ready).value
                val start=LocalDate.now(ZoneOffset.UTC).atTime(9,0).toInstant(ZoneOffset.UTC)
                val outcome=app.calendarMeetings.create("synthetic-contact-owned-seed",source.token,EventDraft(CalendarId(2),EventTime.Timed(start,start.plusSeconds(3600)),ZoneId.of("UTC"),title,targetIdentity=identity),listOf(MeetingGuestInput("existing@example.invalid",MeetingGuestType.OPTIONAL)))
                assertTrue(outcome.toString(),outcome is MeetingMutationResult.LocalSaved)
                calendar.db.execSQL("UPDATE attendees SET opaque_guest='Synthetic contact fixture opaque',attendeeStatus=4 WHERE attendeeEmail='existing@example.invalid'")
            }
        }
        calendar.batchCount=0;calendar.committed=false;calendar.writes.clear()
        val f=Fixture(app,calendar,contacts,calendar.count("events"),calendar.count("attendees"),journalCount(app))
        try {block(f)}finally {f.activities.asReversed().forEach {if(!it.get().isDestroyed)stop(it)};calendar.db.close()}
    }
}
