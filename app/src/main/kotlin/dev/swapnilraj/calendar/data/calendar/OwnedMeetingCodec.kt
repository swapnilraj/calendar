package dev.swapnilraj.calendar.data.calendar

import dev.swapnilraj.calendar.core.*
import java.time.*
import org.json.JSONArray
import org.json.JSONObject

/** New family only; invited RSVP/series/creation frozen encoders remain byte-for-byte separate. */
internal object OwnedMeetingCodec {
    const val Feature="MEETING_OWNED_COMMAND_V1"
    const val EditKind="MEETING_OWNED_V1_EDIT"
    const val CancelKind="MEETING_OWNED_V1_CANCEL"
    fun handles(kind:String)=kind==EditKind||kind==CancelKind
    fun supported(selected:EventRef,command:MeetingCommand)=selected.recurrence==null&&when(command){is MeetingCommand.EditOwned->command.scope==RecurrenceScope.THIS&&command.expectedMasterStart==null;is MeetingCommand.CancelOwned->command.scope==RecurrenceScope.THIS;else->false}
    fun fingerprint(selected:EventRef,token:EditToken,command:MeetingCommand,maxBytes:Int):Pair<String,String>? {
        if(!supported(selected,command)||!token.value.startsWith("meeting-owned-edit-v1:"))return null
        return (if(command is MeetingCommand.EditOwned)EditKind else CancelKind) to meetingHash(Feature+encode(selected,token,command,maxBytes).toString())
    }
    fun encode(selected:EventRef,token:EditToken,command:MeetingCommand,maxBytes:Int):JSONObject {
        require(supported(selected,command))
        val j=JSONObject().put("selected",meetingRefJson(selected)).put("token",token.value).put("scope","THIS")
        when(command){is MeetingCommand.EditOwned->{
            j.put("type","edit").put("patch",patch(command.patch)).put("guests",JSONArray(command.guests.map{change->when(change){
                is MeetingGuestChange.Add->JSONObject().put("type","add").put("email",change.guest.email).put("name",change.guest.name?:JSONObject.NULL).put("guestType",change.guest.type.name)
                is MeetingGuestChange.ChangeType->JSONObject().put("type","change").put("ref",change.ref.value).put("guestType",change.type.name)
                is MeetingGuestChange.Remove->JSONObject().put("type","remove").put("ref",change.ref.value)
            }}))
        };is MeetingCommand.CancelOwned->j.put("type","cancel");else->error("Unsupported")}
        checkStoredJson(j.toString(),maxBytes);return j
    }
    fun decode(j:JSONObject):Triple<EventRef,EditToken,MeetingCommand> {
        require(j.getString("scope")=="THIS")
        val command=when(j.getString("type")){"cancel"->MeetingCommand.CancelOwned(RecurrenceScope.THIS)
            "edit"->{val a=j.getJSONArray("guests");require(a.length()<=400);MeetingCommand.EditOwned(RecurrenceScope.THIS,parsePatch(j.getJSONObject("patch")),List(a.length()){i->val c=a.getJSONObject(i);when(c.getString("type")){
                "add"->MeetingGuestChange.Add(MeetingGuestInput(c.getString("email"),MeetingGuestType.valueOf(c.getString("guestType")),if(c.isNull("name"))null else c.getString("name")))
                "change"->MeetingGuestChange.ChangeType(MeetingGuestRef(c.getString("ref")),MeetingGuestType.valueOf(c.getString("guestType")))
                "remove"->MeetingGuestChange.Remove(MeetingGuestRef(c.getString("ref")))
                else->error("Unknown guest command")
            }})};else->error("Unknown owned command")}
        return Triple(meetingParseRef(j.getJSONObject("selected")),EditToken(j.getString("token")),command)
    }
    private fun patch(p:EventPatch)=JSONObject().apply {
        fun <T>change(key:String,c:FieldChange<T>,encode:(T)->Any?={it}){if(c is FieldChange.Set)put(key,encode(c.value)?:JSONObject.NULL)}
        change("title",p.title);change("location",p.location);change("description",p.description)
        change("zone",p.eventZone){it?.id};change("alerts",p.alertMinutes){JSONArray(it)};change("availability",p.availability);change("privacy",p.privacy)
        change("time",p.time){time->when(time){is EventTime.Timed->JSONObject().put("type","timed").put("start",time.start.toString()).put("end",time.end.toString())
            is EventTime.AllDay->JSONObject().put("type","date").put("start",time.dates.start.toString()).put("end",time.dates.endExclusive.toString())}}
    }
    private fun parsePatch(j:JSONObject):EventPatch {
        fun <T>change(key:String,read:()->T):FieldChange<T> = if(j.has(key))FieldChange.Set(read())else FieldChange.Keep
        return EventPatch(title=change("title"){j.getString("title")},location=change("location"){j.getString("location")},description=change("description"){j.getString("description")},
            eventZone=change("zone"){if(j.isNull("zone"))null else ZoneId.of(j.getString("zone"))},
            alertMinutes=change("alerts"){j.getJSONArray("alerts").let{a->List(a.length()){a.getInt(it)}}},availability=change("availability"){if(j.isNull("availability"))null else j.getInt("availability")},
            privacy=change("privacy"){if(j.isNull("privacy"))null else j.getInt("privacy")},time=change("time"){j.getJSONObject("time").let{t->when(t.getString("type")){
                "timed"->EventTime.Timed(Instant.parse(t.getString("start")),Instant.parse(t.getString("end")))
                "date"->EventTime.AllDay(CalendarDateRange(LocalDate.parse(t.getString("start")),LocalDate.parse(t.getString("end"))))
                else->error("Unknown time")}}})
    }
}
