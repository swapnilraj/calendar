package dev.swapnilraj.calendar.data.calendar

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.provider.CalendarContract
import dev.swapnilraj.calendar.core.*
import java.time.*
import java.util.Locale
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

internal class OwnedMeetingConflict : Exception()
internal class OwnedMeetingReview(val reason: String) : Exception()
internal class OwnedMeetingInvalid(val reason: String) : Exception()
internal class OwnedMeetingDenied(val reason: MutationDenial) : Exception()

/** Exposed source fields are conflict evidence; sparse updates do not enumerate or replay hidden
 * physical OEM columns. SDK9 attendees deliberately exclude derived joined Event fields. */
internal data class OwnedMeetingSource(val selected: EventRef, val calendar: Map<String,String?>,
    val event: Map<String,String?>, val attendees: List<Map<String,String?>>, val reminders: List<Map<String,String?>>) {
    val identity get() = meetingIdentity(calendar)
    val owner get() = requireNotNull(identity.owner)
    val self get() = attendees.single { it["attendeeEmail"] == owner }
    val time: EventTime get() = if(event["allDay"]=="1")EventTime.AllDay.fromProvider(selected.instanceStart,selected.instanceEnd)else EventTime.Timed(selected.instanceStart,selected.instanceEnd)
    val zone: ZoneId? get() = event["eventTimezone"]?.let { try { ZoneId.of(it) } catch(_:Exception){null} }
    fun canonical(maxBytes:Int):String {
        checkCanonical(listOf(calendar,event,attendees,reminders),maxBytes,4096)
        return JSONObject().put("feature",Feature).put("version",1).put("selected",meetingRefJson(selected)).put("calendar",meetingRowJson(calendar))
            .put("event",meetingRowJson(event)).put("attendees",JSONArray(attendees.map(::meetingRowJson))).put("reminders",JSONArray(reminders.map(::meetingRowJson)))
            .toString().also { checkStoredJson(it,maxBytes) }
    }
    fun validate() {
        val ns=identity
        if(selected.recurrence!=null||event.meetingLong("_id")!=selected.eventId||event.meetingLong("calendar_id")!=selected.calendarId.value||ns.calendarId!=selected.calendarId)throw OwnedMeetingConflict()
        if(ns.account.name.isBlank()||ns.account.type.isBlank()||ns.owner!=ns.account.name||!ownedMeetingEmail(ns.owner.orEmpty()))throw OwnedMeetingDenied(MutationDenial.OWNERSHIP_UNKNOWN)
        if(calendar["calendar_access_level"]?.toIntOrNull() !in setOf(700,800))throw OwnedMeetingDenied(MutationDenial.CALENDAR_READ_ONLY)
        if(event["isOrganizer"]!="1"||event["organizer"]!=ns.owner)throw OwnedMeetingDenied(MutationDenial.OWNERSHIP_UNKNOWN)
        if(event["deleted"]!="0"||event["eventStatus"] !in setOf(null,"0","1")||event["hasAttendeeData"] !in setOf("0","1")||
            !event["customAppPackage"].isNullOrBlank()||!event["customAppUri"].isNullOrBlank())throw OwnedMeetingDenied(MutationDenial.UNSUPPORTED_EVENT)
        if(RecurrenceFields.any{!event[it].isNullOrBlank()})throw OwnedMeetingDenied(MutationDenial.RECURRENCE)
        if(event.meetingLong("dtstart")!=selected.instanceStart.toEpochMilli()||event.meetingLong("dtend")!=selected.instanceEnd.toEpochMilli()||event["allDay"] !in setOf("0","1"))throw OwnedMeetingConflict()
        time // Validate DATE/instant shape without inventing a zone.
        if(attendees.size !in 1..201||attendees.any{it.keys!=CalendarMeetingWriter.attendeeFields.toSet()||it.meetingLong("_id")?.let{v->v>0}!=true||it.meetingLong("event_id")!=selected.eventId}||attendees.map{it["_id"]}.toSet().size!=attendees.size)throw OwnedMeetingReview("The complete guest identities are ambiguous")
        val candidates=attendees.filter{it["attendeeEmail"]?.trim()?.equals(owner.trim(),true)==true}
        val self=candidates.singleOrNull()?:throw OwnedMeetingReview("A unique exact organizer attendee is required")
        if(self["attendeeEmail"]!=owner||self["attendeeRelationship"]!="2"||self["attendeeType"] !in setOf("0","1","2")||self["attendeeStatus"] !in setOf("0","1","2","3","4")||self["attendeeStatus"]!=event["selfAttendeeStatus"]||self["attendeeIdentity"].isNullOrBlank()!=self["attendeeIdNamespace"].isNullOrBlank())throw OwnedMeetingReview("The organizer attendee identity is not proven")
        if(attendees.any{it["_id"]!=self["_id"]&&it["attendeeRelationship"]=="2"})throw OwnedMeetingReview("An additional organizer identity requires a separate ownership policy")
        if(reminders.any{it.meetingLong("_id")?.let{v->v>0}!=true||it.meetingLong("event_id")!=selected.eventId}||reminders.map{it["_id"]}.toSet().size!=reminders.size)throw OwnedMeetingReview("The reminder identities are ambiguous")
    }
    fun guestTypes():Set<MeetingGuestType> {
        if(event["hasAttendeeData"]!="1")return emptySet()
        val raw=calendar["allowedAttendeeTypes"]?.takeIf(String::isNotBlank)?:return emptySet()
        val types=raw.split(',').map{it.trim().toIntOrNull()?:return emptySet()}.toSet()
        if(types.any{it !in 0..3})return emptySet()
        return MeetingGuestType.entries.filter{ownedMeetingType(it) in types}.toSet()
    }
    fun unambiguousGuests():Boolean {
        val guests=attendees.filter{it["_id"]!=self["_id"]}
        return guests.all{ownedMeetingEmail(it["attendeeEmail"].orEmpty())}&&guests.map{it["attendeeEmail"]!!.trim().lowercase(Locale.ROOT)}.toSet().size==guests.size
    }
    fun guestRef(token:EditToken,row:Map<String,String?>)=MeetingGuestRef("owned-guest-v1:"+meetingHash(token.value+meetingRowJson(row).toString()))
    fun guestActions(row:Map<String,String?>):Set<MeetingGuestAction> {
        if(row["_id"]==self["_id"]||guestTypes().isEmpty()||!unambiguousGuests()||row["attendeeRelationship"]!="1"||row["attendeeType"] !in setOf("0","1","2")||row["attendeeStatus"] !in setOf("0","1","2","3","4")||row["attendeeIdentity"].isNullOrBlank()!=row["attendeeIdNamespace"].isNullOrBlank())return emptySet()
        return MeetingGuestAction.entries.toSet()
    }
    companion object {
        const val Feature="MEETING_OWNED_SOURCE_V1"
        val RecurrenceFields=listOf("rrule","rdate","exrule","exdate","original_id","original_sync_id","originalInstanceTime","originalAllDay")
        fun token(raw:String)="meeting-owned-edit-v1:"+meetingHash(Feature+raw)
        fun decode(raw:String,maxBytes:Int):OwnedMeetingSource {
            checkStoredJson(raw,maxBytes);val j=JSONObject(raw);require(j.getString("feature")==Feature&&j.getInt("version")==1)
            fun rows(key:String)=j.getJSONArray(key).let{a->List(a.length()){meetingRow(a.getJSONObject(it))}}
            val result=OwnedMeetingSource(meetingParseRef(j.getJSONObject("selected")),meetingRow(j.getJSONObject("calendar")),meetingRow(j.getJSONObject("event")),rows("attendees"),rows("reminders"))
            result.validate();require(result.canonical(maxBytes)==raw);return result
        }
    }
}

internal class OwnedMeetingProvider(private val resolver:ContentResolver,private val hasPermission:(String)->Boolean,private val readBudgets:ProviderReadBudgets) {
    fun ensureRead(){if(!hasPermission(Manifest.permission.READ_CALENDAR))throw SecurityException()}
    suspend fun rows(uri:Uri,fields:Array<String>?=null,selection:String?=null,args:Array<out String?>?=null,maxRows:Int):List<Map<String,String?>> {
        ensureRead()
        val cursor=suspendCancellableCoroutine<Cursor>{c->val signal=CancellationSignal();c.invokeOnCancellation{signal.cancel()}
            try{val found=resolver.query(uri,fields,selection,args,"_id ASC",signal)?:error("Provider unavailable");try{ensureRead()}catch(f:Exception){found.close();throw f};c.resume(found,onCancellation={_,resource,_->resource.close()})}
            catch(f:Exception){c.resumeWithException(f)}}
        return cursor.bounded(maxRows).use{c->buildList{while(c.moveToNext()){currentCoroutineContext().ensureActive();add(c.columnNames.associateWith{key->val i=c.getColumnIndexOrThrow(key);if(c.getType(i)==Cursor.FIELD_TYPE_BLOB)throw OwnedMeetingReview("Unsupported exposed provider field");if(c.isNull(i))null else c.getString(i)})}}}
    }
    suspend fun calendar(id:CalendarId)=rows(ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI,id.value),maxRows=1).singleOrNull()?.also{
        if(it.meetingLong("_id")!=id.value)throw OwnedMeetingConflict();if((it["calendar_access_level"]?.toIntOrNull()?:-1)<200)throw OwnedMeetingDenied(MutationDenial.CALENDAR_READ_ONLY)
    }?:throw OwnedMeetingConflict()
    suspend fun capture(selected:EventRef,expected:CalendarIdentity?=null):OwnedMeetingSource {
        val cal=calendar(selected.calendarId);if(expected!=null&&meetingIdentity(cal)!=expected)throw OwnedMeetingConflict()
        if(cal["calendar_access_level"]?.toIntOrNull() !in setOf(700,800))throw OwnedMeetingDenied(MutationDenial.CALENDAR_READ_ONLY)
        val event=rows(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI,selected.eventId),maxRows=1).singleOrNull()?:throw OwnedMeetingConflict()
        val ns=meetingIdentity(cal)
        // Prove ownership before reading any private child rows; IS_ORGANIZER alone is insufficient.
        if(event.meetingLong("calendar_id")!=selected.calendarId.value||event["isOrganizer"]!="1"||ns.owner.isNullOrBlank()||ns.owner!=ns.account.name||event["organizer"]!=ns.owner||ns.account.type.isBlank())throw OwnedMeetingDenied(MutationDenial.OWNERSHIP_UNKNOWN)
        if(event["deleted"]!="0"||OwnedMeetingSource.RecurrenceFields.any{!event[it].isNullOrBlank()}||event["eventStatus"] !in setOf(null,"0","1"))throw OwnedMeetingDenied(MutationDenial.UNSUPPORTED_EVENT)
        if(event["hasAttendeeData"] !in setOf("0","1")||!event["customAppPackage"].isNullOrBlank()||!event["customAppUri"].isNullOrBlank())throw OwnedMeetingDenied(MutationDenial.UNSUPPORTED_EVENT)
        val attendees=rows(CalendarContract.Attendees.CONTENT_URI,CalendarMeetingWriter.attendeeFields,"event_id = ? AND calendar_id = ? AND organizer = ?",arrayOf(selected.eventId.toString(),selected.calendarId.value.toString(),ns.owner),minOf(201,readBudgets.maxDetailChildren))
        val reminders=rows(CalendarContract.Reminders.CONTENT_URI,arrayOf("_id","event_id","minutes","method"),"event_id = ?",arrayOf(selected.eventId.toString()),readBudgets.maxDetailChildren)
        return OwnedMeetingSource(selected,cal,event,attendees,reminders).also{it.validate()}
    }
}
internal fun ownedMeetingType(type:MeetingGuestType)=when(type){MeetingGuestType.UNSPECIFIED->0;MeetingGuestType.REQUIRED->1;MeetingGuestType.OPTIONAL->2}
internal fun ownedMeetingEmail(email:String)=email.length in 3..254&&email.all{it.code in 33..126}&&Regex("[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+").matches(email)
