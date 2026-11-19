package dev.swapnilraj.calendar.data.calendar

import dev.swapnilraj.calendar.core.*
import java.time.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class FollowingExdateAuthorityTest {
    internal fun receipt(raw:String?=null,prefix:String?=null,tail:String?=null,whole:Boolean=false,allDay:Boolean=false):FollowingExecutionReceipt {
        val old=FollowingExecutionReceiptTest().receipt(whole,allDay)
        val source=JSONObject(old.sourceRaw);source.getJSONObject("master").put("exdate",raw?:JSONObject.NULL)
        val encoded=source.toString();val key=if(allDay)RecurrenceKey.Date(old.firstStart.atOffset(ZoneOffset.UTC).toLocalDate())else RecurrenceKey.Timed(old.firstStart)
        val p=if(whole)raw else prefix
        return old.copy(sourceRaw=encoded,token=CreateSixJson.hash("SERIES_EDIT_V1"+encoded),prefixWrite=old.prefixWrite+mapOf("exdate" to p),prefix=old.prefix+mapOf("exdate" to p),tail=old.tail?.plus(mapOf("exdate" to tail)),exclusionsRaw=FollowingExdateAuthority.create(raw,key,null,p,if(whole)null else tail))
    }
    private fun parse(r:FollowingExecutionReceipt,phase:String="PREPARED",j:JSONObject=r.prepared())=FollowingExecutionReceipt.parse(CalendarLocalStore.Operation(r.operationId,r.signature,FollowingExecutionReceipt.Kind,phase,j.toString()),1024*1024).second
    private fun invalid(r:FollowingExecutionReceipt)=assertThrows(Exception::class.java){parse(r)}
    private fun witness(r:FollowingExecutionReceipt,edit:(JSONObject)->Unit):FollowingExecutionReceipt {val j=JSONObject(r.exclusionsRaw!!);edit(j);return r.copy(exclusionsRaw=j.toString())}
    @Test fun nullAndEmptyStayDistinctForBothWholeAndSplit(){for(whole in listOf(false,true))for(raw in listOf(null,"")){val r=receipt(raw,raw,raw,whole);assertEquals(raw,parse(r).prefix["exdate"]);assertEquals(if(whole)null else raw,parse(r).tail?.get("exdate"))}}
    @Test fun literalDuplicatesOrderGroupsAndTrailingLfSurvive(){val r=receipt("UTC;20280104T090000Z,20280104T090000Z\n20280108T090000Z\n","UTC;20280104T090000Z,20280104T090000Z\n","20280108T090000Z\n");assertEquals(r.plan().toString(),parse(r).plan().toString())}
    @Test fun allDayDateAndUtcMidnightKeysPartitionTogether(){assertThrows(Exception::class.java){receipt("UTC;20280104",null,null,allDay=true).validate()};val r=receipt("20280104,20280108T000000Z","20280104","20280108T000000Z",allDay=true);parse(r)}
    @Test fun emptyPartitionOfNonemptySourceIsNull(){parse(receipt("20280108T090000Z",null,"20280108T090000Z"));invalid(receipt("20280108T090000Z","","20280108T090000Z"))}
    @Test fun malformedOrAmbiguousNativeLiteralRefuses(){for(raw in listOf(" ","20280104T090000Z,","20280104T090000Z\n\n","20280104T090000Z\r\n","TZID=UTC:20280104T090000Z","20280104T090000","20280104T090000+0000","20280230T090000Z")){assertThrows(Exception::class.java){receipt(raw,raw,null).validate()}}}
    @Test fun excludedSelectedBoundaryRefuses(){invalid(receipt("20280105T090000Z",null,"20280105T090000Z"))}
    @Test fun tokenKeyTamperIsRejectedWithSelfConsistentDigest(){val r=receipt("20280104T090000Z","20280104T090000Z",null);invalid(witness(r){it.getJSONArray("groups").getJSONObject(0).getJSONArray("tokens").getJSONObject(0).getJSONObject("key").put("instant","2028-01-04T10:00:00Z")})}
    @Test fun groupAndTrailingFlagTamperIsRejectedWithSelfConsistentDigest(){val r=receipt("UTC;20280104T090000Z\n","UTC;20280104T090000Z\n",null);invalid(witness(r){it.put("trailingLf",false)});invalid(witness(r){it.getJSONArray("groups").getJSONObject(0).put("prefix","")})}
    @Test fun schemaScalarAndUnknownFieldTamperRefuses(){val r=receipt();invalid(witness(r){it.put("surprise",true)});invalid(witness(r){it.put("sourceRaw",false)});invalid(witness(r){it.put("trailingLf","false")})}
    @Test fun nativePartitionAndRuleTamperRefuses(){val r=receipt();invalid(r.copy(prefix=r.prefix+mapOf("exdate" to "")));invalid(r.copy(tail=r.tail!!+mapOf("rrule" to "FREQ=DAILY;COUNT=8")))}
    @Test fun countUsesNominalOrdinalIncludingExcludedEarlierDates(){val r=receipt("20280103T090000Z,20280104T090000Z","20280103T090000Z,20280104T090000Z",null);parse(r);assertEquals("FREQ=DAILY;COUNT=2",r.prefix["rrule"]);assertEquals("FREQ=DAILY;COUNT=6",r.tail!!["rrule"])}
    @Test fun untilAndUnboundedRetainLiteralTailWithFrozenPreviousNominal(){for(rule in listOf("FREQ=DAILY","FREQ=DAILY;UNTIL=20280120T090000Z")){val old=receipt();val source=JSONObject(old.sourceRaw);source.getJSONObject("master").put("rrule",rule);val raw=source.toString();val prefix="FREQ=DAILY;UNTIL=20280104T090000Z";val r=old.copy(sourceRaw=raw,token=CreateSixJson.hash("SERIES_EDIT_V1"+raw),prefixWrite=old.prefixWrite+mapOf("rrule" to prefix),prefix=old.prefix+mapOf("rrule" to prefix),tail=old.tail!!+mapOf("rrule" to rule),exclusionsRaw=FollowingExdateAuthority.create(null,RecurrenceKey.Timed(old.firstStart),RecurrenceKey.Timed(Instant.parse("2028-01-04T09:00:00Z")),null,null));parse(r);invalid(witness(r){it.put("previousNominal",JSONObject().put("instant",r.firstStart.toString()))})}}
    @Test fun phaseAndVersionCannotAliasLegacyOrInventCommit(){val r=receipt();assertThrows(Exception::class.java){parse(r,"COMMITTED")};assertThrows(Exception::class.java){parse(r,j=r.prepared().put("version",3))};val old=FollowingExecutionReceiptTest().receipt();assertEquals(3,parse(old).version)}
    @Test fun ackLayoutAndFrozenFingerprintRemainBound(){val r=receipt();val decoded=parse(r);assertEquals(r.signature,decoded.signature);assertEquals(4,r.prepared().getInt("version"));assertThrows(Exception::class.java){parse(r,j=r.prepared().put("layout",JSONObject().put("resultCount",0)))};assertEquals("exdate",r.steps().single{it.kind=="UPDATE"}.values.keys.single{it=="exdate"})}
    @Test fun coherentShiftedTailClockCannotAcquireMetadataAuthority(){
        val r=receipt();val shifted=r.firstStart.plusSeconds(3600)
        invalid(r.copy(firstStart=shifted,firstEnd=r.firstEnd.plusSeconds(3600),tail=r.tail!!+mapOf("dtstart" to shifted.toEpochMilli().toString())))
        // This temporal shape remains legal on the separately issued legacy3 protocol.
        val old=FollowingExecutionReceiptTest().receipt()
        parse(old.copy(firstStart=shifted,firstEnd=old.firstEnd.plusSeconds(3600),tail=old.tail!!+mapOf("dtstart" to shifted.toEpochMilli().toString())))
    }
    @Test fun coherentlyRehashedTailZoneCannotAcquireMetadataAuthority(){
        val r=receipt();invalid(r.copy(tail=r.tail!!+mapOf("eventTimezone" to "America/New_York")))
        val old=FollowingExecutionReceiptTest().receipt();parse(old.copy(tail=old.tail!!+mapOf("eventTimezone" to "America/New_York")))
    }
    @Test fun wholeContextAndEndZoneCannotChangeUnderMetadataKeep(){
        val r=receipt(whole=true)
        for((field,value)in listOf("eventTimezone" to "UTC","eventEndTimezone" to "UTC","originalInstanceTime" to "123","originalAllDay" to "1")){
            invalid(r.copy(prefixWrite=r.prefixWrite+mapOf(field to value),prefix=r.prefix+mapOf(field to value)))
        }
        val old=FollowingExecutionReceiptTest().receipt(whole=true)
        parse(old.copy(prefixWrite=old.prefixWrite+mapOf("eventEndTimezone" to "UTC"),prefix=old.prefix+mapOf("eventEndTimezone" to "UTC")))
    }

}
