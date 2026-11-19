package dev.swapnilraj.calendar.data.calendar

import android.app.Application
import android.content.ContentProviderResult
import android.content.ContentUris
import android.net.Uri
import dev.swapnilraj.calendar.core.*
import java.time.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class FollowingExecutionReceiptTest {
    private val begin=Instant.parse("2028-01-03T09:00:00Z")
    private val issuedUid="issued-prefix-identity"
    internal fun receipt(whole:Boolean=false,allDay:Boolean=false,anonymous:Boolean=false):FollowingExecutionReceipt {
        val start=if(allDay)Instant.parse("2028-01-03T00:00:00Z")else begin
        val duration=if(allDay)86400L*3 else 3600L
        val selectedStart=start.plusSeconds(if(whole)0 else 86400L*2)
        val selected=EventRef(CalendarId(2),1,selectedStart,selectedStart.plusSeconds(duration),RecurrenceRef(1,selectedStart))
        val calendar=mapOf("_id" to "2","account_name" to "owner@example.invalid","account_type" to "com.google","ownerAccount" to "owner@example.invalid","_sync_id" to "calendar-A","calendar_access_level" to "700")
        val master=mapOf("_id" to "1","calendar_id" to "2","_sync_id" to if(anonymous)null else "prefix-sync","uid2445" to if(anonymous)null else issuedUid,
            "dtstart" to start.toEpochMilli().toString(),"dtend" to null,"duration" to if(allDay)"P3D"else"PT1H","allDay" to if(allDay)"1"else"0","eventTimezone" to if(allDay)"UTC"else"Europe/London",
            "eventEndTimezone" to null,"rrule" to "FREQ=DAILY;COUNT=8","rdate" to null,"exdate" to null,"exrule" to null,"original_id" to null,"original_sync_id" to null,"originalInstanceTime" to null,"originalAllDay" to null,
            "deleted" to "0","title" to "Synthetic source","description" to "Synthetic notes","eventLocation" to "Synthetic room","organizer" to "owner@example.invalid","isOrganizer" to "1","hasAlarm" to "1","hasExtendedProperties" to "0","selfAttendeeStatus" to "0")
        val reminders=listOf(mapOf("_id" to "10","event_id" to "1","minutes" to "10","method" to "1"),mapOf("_id" to "11","event_id" to "1","minutes" to "30","method" to "1"))
        fun children(rows:List<Map<String,String?>>)=JSONArray(listOf(JSONObject().put("parent",1).put("rows",FollowingJson.rows(rows))))
        val source=JSONObject().put("version",1).put("calendar",CreateSixJson.row(calendar)).put("master",CreateSixJson.row(master)).put("exceptions",JSONArray())
            .put("reminders",children(reminders)).put("attendees",children(emptyList())).put("extendedProperties",children(emptyList())).put("selection",CreateSixJson.ref(selected)).toString()
        val key=if(allDay)RecurrenceKey.Date(selectedStart.atOffset(ZoneOffset.UTC).toLocalDate())else RecurrenceKey.Timed(selectedStart)
        val command=CalendarSeriesWriter.frozenCommandJson(SeriesCommand.FollowingEdit(RecurrenceEditIntent.FollowingSeriesEdit(key,EventPatch(title=FieldChange.Set("Synthetic changed"))))).toString()
        val prefixWrite=FollowingExecutionReceipt.Context.associateWith{master[it]}+if(whole)mapOf("eventEndTimezone" to null,"rrule" to master["rrule"],"title" to "Synthetic changed")else mapOf("rrule" to "FREQ=DAILY;COUNT=2")
        val tail=if(whole)null else mapOf("calendar_id" to "2","dtstart" to selectedStart.toEpochMilli().toString(),"dtend" to null,"duration" to master["duration"],"allDay" to master["allDay"],"eventTimezone" to master["eventTimezone"],"eventEndTimezone" to master["eventEndTimezone"],
            "rrule" to "FREQ=DAILY;COUNT=6","uid2445" to "33bf8ffd-3d12-4943-a755-9b337538e1c2","title" to "Synthetic changed","description" to master["description"],"eventLocation" to master["eventLocation"],"organizer" to master["organizer"])
        return FollowingExecutionReceipt("following",command,CreateSixJson.hash("SERIES_EDIT_V1"+source),source,whole,if(whole)0 else 2,prefixWrite,master+prefixWrite,reminders,tail,
            if(whole)emptyList()else reminders.map{it.filterKeys{k->k in setOf("minutes","method")}},emptyList(),selectedStart,selectedStart.plusSeconds(duration))
    }
    private fun op(r:FollowingExecutionReceipt,j:JSONObject=r.prepared(),phase:String="PREPARED")=CalendarLocalStore.Operation(r.operationId,r.signature,FollowingExecutionReceipt.Kind,phase,j.toString())
    private fun rejects(block:()->Unit){assertThrows(Exception::class.java,block)}
    private fun results(r:FollowingExecutionReceipt):Array<ContentProviderResult> = r.steps().mapIndexed{index,s->
        if(s.kind=="INSERT")ContentProviderResult(Uri.parse("content://com.android.calendar/${s.table}/${if(s.slot=="TAIL")2 else 100+index}"))else ContentProviderResult(requireNotNull(s.expected))
    }.toTypedArray()

    @Test fun wholeAndSplitRoundTripExactSourcePlanCommandAndFingerprint() {
        for(whole in listOf(false,true))for(allDay in listOf(false,true)){
            val r=receipt(whole,allDay);r.validate()
            val decoded=FollowingExecutionReceipt.parse(op(r),1024*1024).second
            assertEquals(r.sourceRaw,decoded.sourceRaw);assertEquals(r.commandRaw,decoded.commandRaw);assertEquals(r.plan().toString(),decoded.plan().toString())
            val key=if(allDay)RecurrenceKey.Date(r.selected.recurrence!!.originalStart.atOffset(ZoneOffset.UTC).toLocalDate())else RecurrenceKey.Timed(r.selected.recurrence!!.originalStart)
            assertEquals(FollowingExecutionReceipt.Kind to r.signature,CalendarSeriesWriter.retirementFingerprint(r.selected,EditToken(r.token),SeriesCommand.FollowingEdit(RecurrenceEditIntent.FollowingSeriesEdit(key,EventPatch(title=FieldChange.Set("Synthetic changed"))))))
        }
    }
    @Test fun prefixContextRetainsExactRawDurationNullsAndNoUnrelatedEndZone() {
        val r=receipt();val update=r.steps().single{it.kind=="UPDATE"}
        assertEquals(FollowingExecutionReceipt.Context.toSet()+"rrule",update.values.keys)
        assertEquals("PT1H",update.values["duration"]);assertTrue(update.values.containsKey("dtend"));assertNull(update.values["dtend"])
        assertFalse(update.values.containsKey("eventEndTimezone"));assertEquals("Europe/London",update.values["eventTimezone"])
        assertEquals(r.plan().toString(),FollowingExecutionReceipt.parse(op(r),1024*1024).second.plan().toString())
    }
    @Test fun historicalZoneStringsDecodeWithoutCurrentZoneLookup() {
        val r=receipt();val source=JSONObject(r.sourceRaw);val master=source.getJSONObject("master").put("eventTimezone","Historical/Removed_Zone")
        val raw=source.toString();val historical=r.copy(sourceRaw=raw,token=CreateSixJson.hash("SERIES_EDIT_V1"+raw),prefixWrite=r.prefixWrite+mapOf("eventTimezone" to "Historical/Removed_Zone"),
            prefix=r.prefix+mapOf("eventTimezone" to "Historical/Removed_Zone"),tail=r.tail!!+mapOf("eventTimezone" to "Historical/Removed_Zone"))
        assertEquals("Historical/Removed_Zone",FollowingExecutionReceipt.parse(op(historical),1024*1024).second.tail!!["eventTimezone"])
    }
    @Test fun anonymousWholeRemainsEligibleButUnbindableSplitCannotPrepare() {
        val whole=receipt(whole=true,anonymous=true);whole.validate();assertNull(FollowingExecutionReceipt.parse(op(whole),1024*1024).second.master["uid2445"])
        val split=receipt(anonymous=true);rejects{FollowingExecutionReceipt.parse(op(split),1024*1024)}
    }
    @Test fun strictScalarsUnknownKeysDigestSourceTokenAndOperationBindingsReject() {
        val r=receipt()
        for(mutate in listOf<(JSONObject)->Unit>({it.put("version","3")},{it.put("unknown",true)},{it.put("token","0".repeat(64))},
            {it.getJSONObject("plan").put("whole","false")},{it.getJSONObject("plan").getJSONObject("prefixWrite").put("dtstart",7)},
            {it.getJSONObject("layout").put("resultCount",1)},{it.put("planDigest","0".repeat(64))})){
            val j=r.prepared();mutate(j);rejects{FollowingExecutionReceipt.parse(op(r,j),1024*1024)}
        }
        rejects{FollowingExecutionReceipt.parse(op(r).copy(id="other-operation"),1024*1024)}
        val raw=op(r).copy(resultRef=r.prepared().toString().replace("\"planDigestVersion\":1","\"planDigestVersion\":1.0"))
        rejects{FollowingExecutionReceipt.parse(raw,1024*1024)}
        rejects{FollowingExecutionReceipt.parse(op(r),128)}
    }
    @Test fun recomputedDigestCannotChangePrefixUidContextOrUnchangedMetadata() {
        val r=receipt()
        for(altered in listOf(r.copy(prefixWrite=r.prefixWrite+mapOf("eventTimezone" to "UTC")),r.copy(prefix=r.prefix+mapOf("uid2445" to "replacement")),
            r.copy(tail=r.tail!!+mapOf("description" to "Changed without command")),r.copy(tail=r.tail!!+mapOf("eventEndTimezone" to "Europe/London")),r.copy(prefixReminders=emptyList()),r.copy(firstEnd=r.firstEnd.plusSeconds(1))))
            rejects{FollowingExecutionReceipt.parse(op(altered),1024*1024)}
    }
    @Test fun exceptionAndExdateMaterialCannotEnterTheExceptionFreeProtocolWithANewDigest() {
        val original=receipt()
        for(exclusion in listOf(false,true)) {
            val source=JSONObject(original.sourceRaw)
            if(exclusion)source.getJSONObject("master").put("exdate","20280105T090000Z")
            else source.getJSONArray("exceptions").put(JSONObject().put("_id","7"))
            val raw=source.toString();val changed=original.copy(sourceRaw=raw,token=CreateSixJson.hash("SERIES_EDIT_V1"+raw))
            rejects{FollowingExecutionReceipt.parse(op(changed),1024*1024)}
        }
        val allDay=receipt(allDay=true);val command=allDay.command.getJSONObject("expected").put("date","2028-01-06")
        val j=allDay.command.put("expected",command)
        val changed=allDay.copy(commandRaw=j.toString())
        rejects{FollowingExecutionReceipt.parse(op(changed),1024*1024)}
    }

    @Test fun everyCountAndInsertAckIsRequiredAtItsFrozenIndex() {
        for(whole in listOf(false,true)){
            val r=receipt(whole);val canonical=results(r);val result=if(whole)1L else 2L
            assertNotNull(FollowingAcknowledgments.insertIds(FollowingAcknowledgments.capture(canonical,canonical.size),r.steps(),result,1))
            for(index in canonical.indices){val bad=canonical.copyOf();bad[index]=if(r.steps()[index].kind=="INSERT")ContentProviderResult(Uri.parse("https://com.android.calendar/${r.steps()[index].table}/999"))else ContentProviderResult(requireNotNull(r.steps()[index].expected)+1)
                assertNull(FollowingAcknowledgments.insertIds(FollowingAcknowledgments.capture(bad,bad.size),r.steps(),result,1))}
            assertNull(FollowingAcknowledgments.insertIds(FollowingAcknowledgments.capture(canonical.dropLast(1).toTypedArray(),canonical.size),r.steps(),result,1))
        }
    }
    @Test fun swappedChildIdsRetainSlotBindingForPayloadVerificationAndDuplicatesFail() {
        val r=receipt();val canonical=results(r);val inserts=r.steps().withIndex().filter{it.value.table=="reminders"&&it.value.kind=="INSERT"}.map{it.index}
        val swapped=canonical.copyOf();swapped[inserts[0]]=canonical[inserts[1]];swapped[inserts[1]]=canonical[inserts[0]]
        val proof=FollowingAcknowledgments.insertIds(FollowingAcknowledgments.capture(swapped,swapped.size),r.steps(),2,1)!!
        assertEquals(ContentUris.parseId(canonical[inserts[1]].uri!!),proof["TAIL_REMINDER:0"])
        swapped[inserts[1]]=swapped[inserts[0]]
        assertNull(FollowingAcknowledgments.insertIds(FollowingAcknowledgments.capture(swapped,swapped.size),r.steps(),2,1))
    }
    @Test fun committedIncompleteEvidenceNeverPassesVerifiedOrPreparedPhasePairing() {
        val r=receipt();val committed=r.prepared().put("stage","committed").put("result",2).put("ackComplete",false)
        assertEquals(r.uid,FollowingExecutionReceipt.parse(op(r,committed,"COMMITTED"),1024*1024).second.uid)
        for(phase in listOf("PREPARED","FAILED","ATTEMPTED","VERIFIED"))rejects{FollowingExecutionReceipt.parse(op(r,committed,phase),1024*1024)}
        rejects{FollowingExecutionReceipt.parse(op(r,phase="COMMITTED"),1024*1024)}
    }
    @Test fun completeVerifiedReceiptBindsAllIdsAndFrozenFirstRef() {
        val r=receipt();val facts=FollowingAcknowledgments.capture(results(r),r.steps().size)!!;val ids=FollowingAcknowledgments.insertIds(facts,r.steps(),2,1)!!
        val j=r.prepared().put("stage","committed").put("result",2).put("ackComplete",true).put("ackFacts",facts).put("insertIds",FollowingAcknowledgments.ids(ids)).put("ref",CreateSixJson.ref(r.ref(2)))
        assertEquals(r.ref(2),FollowingExecutionReceipt.parse(op(r,j,"VERIFIED"),1024*1024).second.ref(2))
        j.getJSONObject("insertIds").put("TAIL_REMINDER:0",999)
        rejects{FollowingExecutionReceipt.parse(op(r,j,"VERIFIED"),1024*1024)}
    }
}
