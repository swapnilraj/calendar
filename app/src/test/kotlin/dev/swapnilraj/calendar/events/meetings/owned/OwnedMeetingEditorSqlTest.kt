package dev.swapnilraj.calendar.events.meetings.owned

import android.Manifest
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.calendar.CalendarOwnedMeetingWriterTest
import dev.swapnilraj.calendar.data.calendar.CalendarOperationRetirement
import dev.swapnilraj.calendar.ui.events.meetings.create.MeetingCreationGuestFields
import dev.swapnilraj.calendar.ui.events.meetings.owned.*
import java.time.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class OwnedMeetingEditorSqlTest {
    private val harness=CalendarOwnedMeetingWriterTest()
    private suspend fun open(f:CalendarOwnedMeetingWriterTest.Fixture,id:String):OwnedMeetingEditorController {
        val source=with(harness) {f.edit()}
        val launch=(prepareOwnedMeetingDraft(source,id,ZoneId.of("Asia/Tokyo"),f.store) as OwnedMeetingPreparation.Ready).launch
        return OwnedMeetingEditorController(launch,f.writer,f.store,{Manifest.permission.WRITE_CALENDAR in f.grants},CalendarOperationRetirement(f.store)).also {it.load()}
    }
    private fun rows(f:CalendarOwnedMeetingWriterTest.Fixture,table:String)=f.p.db.rawQuery("SELECT * FROM $table ORDER BY _id",null).use {cursor->buildList {while(cursor.moveToNext()) add(cursor.columnNames.associateWith {name->cursor.getString(cursor.getColumnIndexOrThrow(name))})}}

    @Test fun titleControllerToNativeWriterKeepsNullZoneDefaultAlertsOpaqueChildrenAndUnknownMetadata()=runBlocking {
        harness.fixture {f->
            f.p.db.execSQL("UPDATE events SET eventTimezone=NULL,eventEndTimezone=NULL,accessLevel=99,availability=99")
            f.p.db.execSQL("UPDATE reminders SET minutes=-1")
            val guests=rows(f,"attendees");val alerts=rows(f,"reminders");val c=open(f,"owned-title")
            assertEquals("Asia/Tokyo",c.state.fields!!.event.zone)
            c.changeFields(c.state.fields!!.let {it.copy(event=it.event.copy(title="Synthetic changed title"))});c.save()
            assertTrue(c.state.localOutcome.toString(),c.state.localOutcome is MeetingMutationResult.LocalSaved)
            val row=rows(f,"events").single()
            assertEquals("Synthetic changed title",row["title"]);assertNull(row["eventTimezone"]);assertNull(row["eventEndTimezone"])
            assertEquals("99",row["accessLevel"]);assertEquals("99",row["availability"]);assertEquals("Synthetic opaque event",row["opaque_event"])
            assertEquals(guests,rows(f,"attendees"));assertEquals(alerts,rows(f,"reminders"));assertEquals(1,f.p.batchCount)
            assertEquals(setOf("title"),f.p.writes.single {it.path=="events"}.values)
            c.acknowledgeLocalOutcome();assertTrue(c.state.acknowledged);assertNull(f.store.load("owned-title"))
        }
    }

    @Test fun guestControllerDeltaPreservesOtherResponsesAndCancellationUpdatesStatusWithoutDeleting()=runBlocking {
        harness.fixture {f->
            val before=rows(f,"attendees");val c=open(f,"owned-guests");val fields=c.state.fields!!
            val first=c.guestRows.first {it.attendee.type==1};val second=c.guestRows.first {it.attendee.type==2}
            c.changeFields(fields.copy(guests=fields.guests.map {when(it.ref) {first.ref->it.copy(type="OPTIONAL");second.ref->it.copy(removed=true);else->it}},
                added=listOf(MeetingCreationGuestFields(email="added@example.invalid",name="Synthetic added",type="REQUIRED"))))
            c.save();assertTrue(c.state.localOutcome.toString(),c.state.localOutcome is MeetingMutationResult.LocalSaved)
            val after=rows(f,"attendees");val organizer=before.single {it["attendeeRelationship"]=="2"}
            assertEquals(organizer,after.single {it["attendeeRelationship"]=="2"})
            val retainedBefore=before.single {it["attendeeEmail"]==first.attendee.email};val retainedAfter=after.single {it["attendeeEmail"]==first.attendee.email}
            assertEquals(retainedBefore["attendeeStatus"],retainedAfter["attendeeStatus"]);assertEquals(retainedBefore["opaque_guest"],retainedAfter["opaque_guest"])
            assertEquals("2",retainedAfter["attendeeType"]);assertTrue(after.none {it["attendeeEmail"]==second.attendee.email})
            assertEquals("3",after.single {it["attendeeEmail"]=="added@example.invalid"}["attendeeStatus"])
            c.acknowledgeLocalOutcome();val cancellation=open(f,"owned-cancel");val eventBefore=rows(f,"events").single();val people=rows(f,"attendees")
            cancellation.requestCancellation();cancellation.confirmCancellation()
            assertTrue(cancellation.state.localOutcome.toString(),cancellation.state.localOutcome is MeetingMutationResult.LocalCancelled)
            val eventAfter=rows(f,"events").single();assertEquals(eventBefore["_id"],eventAfter["_id"]);assertEquals("2",eventAfter["eventStatus"])
            assertEquals(eventBefore["uid2445"],eventAfter["uid2445"]);assertEquals(people,rows(f,"attendees"));assertEquals(1,f.p.count("events"))
            assertEquals(setOf("eventStatus"),f.p.writes.last {it.path=="events"}.values)
        }
    }
}
