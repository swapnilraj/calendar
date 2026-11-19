package dev.swapnilraj.calendar

import android.Manifest
import android.content.Intent
import android.provider.CalendarContract
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.text.AnnotatedString
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.calendar.CalendarRecurringCreateTest
import dev.swapnilraj.calendar.data.calendar.meetingParseRef
import dev.swapnilraj.calendar.data.reminders.SavedReminderSettings
import dev.swapnilraj.calendar.ui.events.meetings.create.*
import dev.swapnilraj.calendar.ui.settings.*
import java.time.*
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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

/** Actual application hosts and real private SQL; the fixture expands only the first native
 * occurrence. Pure previews and local writes do not certify Google sync or later provider expansion. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="en-rUS-w393dp-h851dp-notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeRecurringMeetingCreateJourneyTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val title="Synthetic recurring planning meeting"
    private val guest="synthetic-recurring-guest@example.invalid"
    private val timedStart=Instant.parse("2028-03-10T14:00:00Z")
    private val none=CalendarSelection.Explicit(emptySet())

    @Test fun dailyCountRequiresTypedReviewAndCreatesOneNativeMasterWithOrganizerGuestsAndAllFirstRef()=fixture {f->
        enter(f); count(f)
        val original=document(f); assertEquals(2,original.schema)
        assertEquals(AccessDecision.ALLOWED,original.authority!!.recurringCreation)
        saveReview(f); compose.onNodeWithText("After 3 occurrences").assertExists()
        assertNoWrite(f); assertNull(document(f).frozen)
        confirm(f); shown(f.app.getString(R.string.phone_meeting_create_saved))
        val frozen=document(f); val command=requireNotNull(frozen.frozen)
        assertEquals(original.generation,frozen.generation); assertEquals(original.operationId,frozen.operationId)
        assertEquals("FREQ=DAILY;INTERVAL=1;WKST=MO;COUNT=3",f.provider.value("rrule"))
        assertEquals("PT5400S",f.provider.value("duration")); assertNull(f.provider.value("dtend"))
        assertEquals("America/New_York",f.provider.value("eventTimezone")); assertEquals("0",f.provider.value("allDay"))
        assertEquals(timedStart.toEpochMilli().toString(),f.provider.value("dtstart"))
        val uid=requireNotNull(f.provider.value("uid2445"))
        assertEquals(uid,UUID.fromString(uid.removeSuffix("@daylight.invalid")).toString()+"@daylight.invalid")
        val saved=runBlocking {f.app.calendarMeetings.recover(frozen.operationId)} as MeetingMutationResult.LocalSaved
        assertEquals(RecurrenceScope.ALL,saved.scope)
        assertEquals(RecurrenceRef(saved.selected.eventId,timedStart),saved.selected.recurrence)
        assertEquals(timedStart.plusSeconds(5400),saved.selected.instanceEnd)
        assertEquals(saved.selected,meetingParseRef(receipt(f).getJSONObject("firstRef")))
        assertEquals(uid,receipt(f).getString("uid")); assertEquals(2,receipt(f).getInt("version")); assertTrue(receipt(f).getBoolean("ackComplete"))
        assertEquals(command.draft.title,f.provider.value("title")); assertEquals(2,f.provider.count("attendees"))
        f.provider.db.rawQuery("SELECT attendeeEmail,attendeeRelationship,attendeeType,attendeeStatus FROM attendees ORDER BY _id",null).use {
            assertTrue(it.moveToNext());assertEquals("owner@example.invalid",it.getString(0));assertEquals(2,it.getInt(1));assertEquals(1,it.getInt(3))
            assertTrue(it.moveToNext());assertEquals(guest,it.getString(0));assertEquals(1,it.getInt(1));assertEquals(1,it.getInt(2));assertEquals(3,it.getInt(3));assertFalse(it.moveToNext())
        }
        assertEquals(1,f.provider.batchCount);assertEquals(1,f.provider.count("reminders"))
        done(f);assertTrue(records(f).isEmpty());assertEquals("VERIFIED",phase(f,frozen.operationId));assertEquals(1,f.provider.batchCount);assertFilters(f)
    }

    @Test fun twoDayAllDayMeetingUsesUtcDatesAndInclusiveThroughDateWithoutInventingDtend()=fixture {f->
        enter(f,allDay=true); through(f,"2028-01-05")
        saveReview(f);compose.onNodeWithText("Through Jan 5, 2028, inclusive").assertExists();assertNoWrite(f)
        confirm(f);shown(f.app.getString(R.string.phone_meeting_create_saved))
        val doc=document(f)
        assertEquals("FREQ=DAILY;INTERVAL=1;WKST=MO;UNTIL=20280105",f.provider.value("rrule"))
        assertEquals("P2D",f.provider.value("duration"));assertNull(f.provider.value("dtend"))
        assertEquals("UTC",f.provider.value("eventTimezone"));assertEquals("1",f.provider.value("allDay"))
        assertEquals(Instant.parse("2028-01-03T00:00:00Z").toEpochMilli().toString(),f.provider.value("dtstart"))
        val first=meetingParseRef(receipt(f).getJSONObject("firstRef"))
        assertEquals(Instant.parse("2028-01-03T00:00:00Z"),first.instanceStart);assertEquals(Instant.parse("2028-01-05T00:00:00Z"),first.instanceEnd)
        assertEquals(RecurrenceRef(first.eventId,first.instanceStart),first.recurrence)
        assertEquals(RecurrenceEnd.ThroughDate(LocalDate.of(2028,1,5)),doc.frozen!!.draft.recurrence!!.end)
        assertEquals(1,f.provider.batchCount);assertEquals(2,f.provider.count("attendees"));done(f);assertTrue(records(f).isEmpty());assertFilters(f)
    }

    @Test fun writeGrantNeverSubmitsAndChangedCalendarAuthorityAtConfirmationRequiresFreshSelectionAndReview()=fixture(write=false) {f->
        val host=enter(f);count(f);saveReview(f);confirm(f)
        assertArrayEquals(arrayOf(Manifest.permission.WRITE_CALENDAR),shadowOf(host.get()).lastRequestedPermission.requestedPermissions)
        val before=document(f);assertNull(before.frozen);assertNoWrite(f)
        shadowOf(f.app).grantPermissions(Manifest.permission.WRITE_CALENDAR);compose.waitForIdle();assertNoWrite(f);assertNull(document(f).frozen)
        f.provider.setCalendar("_sync_id","replacement-calendar-B")
        // The still-visible explicit review cannot authorize its old calendar/token after the grant.
        confirm(f);shown(f.app.getString(R.string.phone_meeting_create_source_changed))
        assertNull(document(f).frozen);assertEquals(before.generation,document(f).generation);assertEquals(before.operationId,document(f).operationId);assertNoWrite(f)
        f.app.calendarReader.refresh()
        compose.waitUntil(15_000) {runBlocking {f.app.calendarReader.readCalendars()}.let {it is CalendarReadState.Ready && it.value.single().identity.providerId=="replacement-calendar-B"}}
        chooseCalendar(f)
        compose.waitUntil(15_000) {document(f).authority?.identity?.providerId=="replacement-calendar-B"}
        saveReview(f);assertNoWrite(f);confirm(f);shown(f.app.getString(R.string.phone_meeting_create_saved))
        assertEquals(1,f.provider.batchCount);assertEquals(before.operationId,document(f).operationId);done(f);assertTrue(records(f).isEmpty())
    }

    @Test fun committedRecurringMeetingRestoresAfterReadLossAndRecoversFrozenPlanWithoutSnapshotsOrAnotherBatch()=fixture {f->
        var host=enter(f);count(f);saveReview(f)
        f.provider.failReadsAfterCommit=true;confirm(f);shown(f.app.getString(R.string.phone_meeting_create_uncertain))
        val frozen=records(f).single();val doc=MeetingCreationDraftCodec.decode(frozen)
        val proof=receipt(f);val plan=proof.getJSONObject("plan").toString();val uid=proof.getString("uid")
        assertEquals("COMMITTED",phase(f,doc.operationId));assertNotNull(doc.frozen!!.draft.recurrence);assertEquals(1,f.provider.batchCount)
        f.app.calendarLocalStore.writableDatabase.delete("edit_snapshots",null,null)
        shadowOf(f.app).denyPermissions(Manifest.permission.READ_CALENDAR,Manifest.permission.WRITE_CALENDAR)
        compose.runOnUiThread {host.recreate()};shown("Allow calendar access")
        assertEquals(frozen,records(f).single());assertEquals(1,f.provider.batchCount)
        f.provider.failReadsAfterCommit=false;shadowOf(f.app).grantPermissions(Manifest.permission.READ_CALENDAR)
        stop(host);host=main(f)
        openDrafts();shown(f.app.getString(R.string.events_drafts_recover));compose.onNodeWithText(f.app.getString(R.string.events_drafts_recover)).performClick()
        shown(f.app.getString(R.string.phone_meeting_create_recovery_help));assertEquals(frozen,records(f).single())
        val priorZone=TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Australia/Lord_Howe"))
            compose.onNode(hasText(f.app.getString(R.string.phone_meeting_create_recovery)) and hasClickAction()).performScrollTo().performClick()
            shown(f.app.getString(R.string.phone_meeting_create_saved))
        }finally {TimeZone.setDefault(priorZone)}
        assertEquals(plan,receipt(f).getJSONObject("plan").toString());assertEquals(uid,receipt(f).getString("uid"))
        assertEquals(frozen,records(f).single());assertEquals(1,f.provider.batchCount);assertEquals("VERIFIED",phase(f,doc.operationId))
        val first=meetingParseRef(receipt(f).getJSONObject("firstRef"));assertEquals(timedStart,first.instanceStart);assertEquals(timedStart.plusSeconds(5400),first.instanceEnd)
        assertEquals(RecurrenceRef(first.eventId,timedStart),first.recurrence)
        done(f);assertTrue(records(f).isEmpty());compose.runOnUiThread {host.recreate()};shownTag("calendar_phone_month")
        assertEquals(1,f.provider.batchCount);assertEquals(1,f.provider.count("events"));assertEquals(2,f.provider.count("attendees"));assertFilters(f)
    }

    /** Start through the actual Add people entry, then stabilize only the retained synthetic raw
     * date/time before reopening. No controller command or provider mutation is manufactured. */
    private fun enter(f:Fixture,allDay:Boolean=false):ActivityController<MainActivity> {
        val host=main(f);shown(f.app.getString(R.string.navigation_create_event));compose.onNodeWithText(f.app.getString(R.string.navigation_create_event)).performClick()
        shown(f.app.getString(R.string.events_editor_create));compose.onNodeWithContentDescription(f.app.getString(R.string.phone_event_title)).performTextReplacement(title)
        compose.waitUntil(15_000) {records(f).singleOrNull()?.payload?.contains(title)==true}
        compose.onNodeWithText(f.app.getString(R.string.phone_event_add_people)).performScrollTo().performClick();shown(f.app.getString(R.string.phone_meeting_create_title))
        compose.onNodeWithContentDescription(f.app.getString(R.string.phone_meeting_create_close)).performClick();awaitNoEditor(f)
        val record=records(f).single();val doc=MeetingCreationDraftCodec.decode(record)
        val event=doc.fields.event.copy(startDate=if(allDay)"2028-01-03" else "2028-03-10",endDate=if(allDay)"2028-01-04" else "2028-03-10",
            startTime=if(allDay)"" else "09:00",endTime=if(allDay)"" else "10:30",zone=if(allDay)"UTC" else "America/New_York",
            allDay=allDay,startOffset=if(allDay)"" else "-05:00",endOffset=if(allDay)"" else "-05:00",alerts="10")
        val stable=MeetingCreationDraftCodec.record(doc.copy(fields=doc.fields.copy(event=event)),Instant.parse("2026-11-18T09:00:00Z"))
        assertEquals(DraftChangeResult.Applied,runBlocking {f.app.calendarLocalStore.compareAndSet(record.id,record,stable)})
        openDrafts();compose.onNodeWithText(f.app.getString(R.string.events_drafts_open)).performClick();shown(f.app.getString(R.string.phone_meeting_create_title))
        chooseCalendar(f);compose.onNodeWithText(f.app.getString(R.string.phone_meeting_create_add_person)).performScrollTo().performClick()
        compose.onNodeWithContentDescription("Person 1: ${f.app.getString(R.string.phone_meeting_create_email)}").performScrollTo().performTextReplacement(guest)
        compose.waitUntil(15_000) {document(f).fields.guests.singleOrNull()?.email==guest}
        compose.onNodeWithText(f.app.getString(R.string.phone_meeting_create_required)).performScrollTo().performClick()
        compose.waitUntil(15_000) {document(f).fields.guests.singleOrNull()?.type=="REQUIRED"}
        compose.onNodeWithText(f.app.getString(R.string.phone_meeting_create_policy_ack)).performScrollTo().performClick()
        compose.waitUntil(15_000) {document(f).policyAccepted};assertNoWrite(f);return host
    }
    private fun chooseCalendar(f:Fixture) {
        val calendar=(hasText("Synthetic calendar") or hasText(f.app.getString(R.string.phone_event_choose_calendar))) and hasClickAction() and hasAnyAncestor(isDialog())
        compose.onNode(calendar).performScrollTo().performClick()
        compose.onNode(hasText("Synthetic calendar") and SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.RadioButton) and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(15_000) {document(f).authority!=null && document(f).authority!!.recurringCreation==AccessDecision.ALLOWED}
    }
    private fun count(f:Fixture) {
        repeatPanel(f);compose.onNodeWithText("After a number of occurrences").performScrollTo().performClick()
        raw(hasSetTextAction() and hasText("Occurrences (1–10000)"),"3");compose.onNodeWithText("Done").performClick()
        compose.waitUntil(15_000) {
            compose.waitForIdle()
            document(f).fields.event.recurrence.let {it.frequency=="DAILY" && it.endMode=="COUNT" && it.count=="3"}
        }
    }
    private fun through(f:Fixture,date:String) {
        repeatPanel(f);compose.onNodeWithText("Through a date").performScrollTo().performClick()
        raw(hasSetTextAction() and hasText("Last eligible date (YYYY-MM-DD)"),date);compose.onNodeWithText("Done").performClick()
        compose.waitUntil(15_000) {
            compose.waitForIdle()
            document(f).fields.event.recurrence.let {it.frequency=="DAILY" && it.endMode=="DATE" && it.throughDate==date}
        }
    }
    private fun repeatPanel(f:Fixture) {compose.onNodeWithText("Does not repeat").performScrollTo().performClick();compose.onNodeWithText("Daily").performScrollTo().performClick();compose.onNode(hasText("Daily") and isSelectable()).assertIsSelected()}
    private fun raw(matcher:SemanticsMatcher,value:String) {compose.onNode(matcher).performScrollTo().performClick();compose.waitUntil(15_000) {compose.onAllNodes(matcher and isFocused()).fetchSemanticsNodes().size==1};compose.onNode(matcher).performTextReplacement(value);compose.waitUntil(15_000) {compose.onAllNodes(matcher and SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString(value))).fetchSemanticsNodes().size==1}}
    private fun saveReview(f:Fixture) {
        val save=hasText("Save") and hasClickAction() and isEnabled()
        // Closing the repeat panel does not await the durable draft update or its ready frame.
        compose.waitUntil(15_000) {compose.waitForIdle();compose.onAllNodes(save).fetchSemanticsNodes().size==1}
        compose.onNode(save).performClick();shown(f.app.getString(R.string.recurrence_editor_review))
    }
    private fun confirm(f:Fixture) {compose.onNodeWithText(f.app.getString(R.string.recurrence_editor_confirm)).performClick()}
    private fun done(f:Fixture) {compose.onNodeWithText(f.app.getString(R.string.phone_event_done)).performClick();awaitNoEditor(f)}
    private fun records(f:Fixture)=runBlocking {f.app.calendarLocalStore.list()}
    private fun document(f:Fixture)=MeetingCreationDraftCodec.decode(records(f).single())
    private fun receipt(f:Fixture)=f.app.calendarLocalStore.readableDatabase.rawQuery("SELECT result_ref FROM mutation_journal WHERE id=?",arrayOf(document(f).operationId)).use {assertTrue(it.moveToFirst());JSONObject(it.getString(0))}
    private fun phase(f:Fixture,id:String)=f.app.calendarLocalStore.readableDatabase.rawQuery("SELECT phase FROM mutation_journal WHERE id=?",arrayOf(id)).use {assertTrue(it.moveToFirst());it.getString(0)}
    private fun shown(text:String)=compose.waitUntil(15_000) {compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()}
    private fun shownTag(tag:String)=compose.waitUntil(15_000) {compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}
    private fun awaitNoEditor(f:Fixture)=compose.waitUntil(15_000) {compose.onAllNodesWithContentDescription(f.app.getString(R.string.phone_meeting_create_close)).fetchSemanticsNodes().isEmpty()}
    private fun openDrafts() {shownTag("calendar_phone_month");compose.onNodeWithContentDescription("Open navigation menu").performClick();compose.onNodeWithText("Drafts").performClick();shown("Drafts")}
    private fun main(f:Fixture)=Robolectric.buildActivity(MainActivity::class.java,Intent(f.app,MainActivity::class.java)).also {compose.runOnUiThread {it.setup().visible()};f.activities+=it}
    private fun stop(host:ActivityController<out ComponentActivity>) {compose.runOnUiThread {host.pause().stop().destroy()}}
    private fun assertNoWrite(f:Fixture) {assertEquals(0,f.provider.batchCount);assertEquals(0,f.provider.count("events"));f.app.calendarLocalStore.readableDatabase.rawQuery("SELECT COUNT(*) FROM mutation_journal",null).use {it.moveToFirst();assertEquals(0,it.getInt(0))}}
    private fun assertFilters(f:Fixture)=assertEquals(none,runBlocking {f.app.settings.calendarSelection.first()})
    private class Fixture(val app:CalendarApp,val provider:CalendarRecurringCreateTest.Provider) {val activities=mutableListOf<ActivityController<out ComponentActivity>>()}
    private fun fixture(write:Boolean=true,block:(Fixture)->Unit) {
        val app=RuntimeEnvironment.getApplication() as CalendarApp;shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)
        if(write)shadowOf(app).grantPermissions(Manifest.permission.WRITE_CALENDAR) else shadowOf(app).denyPermissions(Manifest.permission.WRITE_CALENDAR)
        val provider=CalendarRecurringCreateTest.Provider().apply {nativeMeetingMode=true;db.execSQL("ALTER TABLE calendars ADD COLUMN allowedAttendeeTypes TEXT DEFAULT '0,1,2'")}
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY,provider)
        runBlocking {app.settings.saveReminderSettings(app.settings.readReminderSettings(),SavedReminderSettings());app.reminderRuntime.onResume().join();app.settings.setDisplaySettings(CalendarSettingsOptions(weatherVisible=false,theme=SettingsTheme.LIGHT,displayZone=DisplayZoneOption.Fixed(ZoneId.of("UTC"))));val calendars=(app.calendarReader.readCalendars() as CalendarReadState.Ready).value;assertTrue(app.settings.setDefaultCalendar(CalendarId(2),calendars));assertTrue(app.settings.setCalendarSelection(none,calendars))}
        val f=Fixture(app,provider);try {block(f)}finally {f.activities.asReversed().forEach {if(!it.get().isDestroyed)stop(it)};provider.db.close()}
    }
}
