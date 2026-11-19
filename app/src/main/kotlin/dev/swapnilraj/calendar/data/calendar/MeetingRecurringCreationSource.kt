package dev.swapnilraj.calendar.data.calendar

import dev.swapnilraj.calendar.core.*
import java.time.*
import java.time.format.DateTimeFormatter
import org.json.JSONArray
import org.json.JSONObject

/** A new source version is required for recurrence. The base bridge preserves V1 source bytes. */
internal data class MeetingRecurringCreationSource(val baseRaw:String,val calendar:Map<String,String?>) {
    fun encode(maxBytes:Int)=wrap(baseRaw,maxBytes)
    companion object {
        const val Feature="MEETING_CREATE_SOURCE_V2"
        const val TokenPrefix="meeting-create-v2:"
        fun token(raw:String)=TokenPrefix+meetingHash(Feature+raw)
        fun canIssue(calendar:Map<String,String?>):Boolean = when(calendar["canModifyTimeZone"]){
            "1"->true
            "0"->try{ProviderTimeZoneCompatibility.isCompatible(ZoneId.of(calendar["calendar_timezone"]?:""))}catch(_:Exception){false}
            else->false
        }
        fun wrap(base:String,maxBytes:Int):String {
            val calendar=CalendarMeetingCreateWriter.legacySourceCalendar(base,maxBytes)
            require(calendar["canModifyTimeZone"] in setOf("0","1"))
            return JSONObject().put("feature",Feature).put("version",2).put("recurringCreation","ALLOWED").put("base",JSONObject(base)).toString().also{checkStoredJson(it,maxBytes)}
        }
        fun decode(raw:String,token:EditToken,maxBytes:Int):MeetingRecurringCreationSource {
            checkStoredJson(raw,maxBytes);require(token.value==token(raw));val j=JSONObject(raw)
            require(j.getString("feature")==Feature&&j.getInt("version")==2&&j.getString("recurringCreation")=="ALLOWED")
            val base=j.getJSONObject("base").toString();val calendar=CalendarMeetingCreateWriter.legacySourceCalendar(base,maxBytes)
            // Stored issuance proof is structural. Do not reinterpret a previously issued source
            // through today's timezone rules; exact plan validation handles persisted operations.
            require(calendar["canModifyTimeZone"] in setOf("0","1"));require(wrap(base,maxBytes)==raw)
            return MeetingRecurringCreationSource(base,calendar)
        }
    }
}

/** Private protocol V1. After prepare, neither retry nor recovery calls the recurrence compiler.
 * Local wall/offset facts freeze the first slot and inclusive UNTIL boundary arithmetically,
 * without looking up changed tzdb rules. The original RRULE/DURATION strings remain authoritative. */
internal data class FrozenMeetingCreationPlan(val dtstart:Instant,val firstEnd:Instant,val allDay:Boolean,val zone:String,
    val rule:String,val duration:String,val startLocal:String?,val startOffset:Int?,val untilBoundaryLocal:String?,val untilBoundaryOffset:Int?) {
    fun json()=JSONObject().put("version",1).put("start",dtstart.toString()).put("end",firstEnd.toString()).put("allDay",allDay).put("zone",zone)
        .put("rrule",rule).put("duration",duration).put("startLocal",startLocal?:JSONObject.NULL).put("startOffset",startOffset?:JSONObject.NULL)
        .put("untilBoundaryLocal",untilBoundaryLocal?:JSONObject.NULL).put("untilBoundaryOffset",untilBoundaryOffset?:JSONObject.NULL)
    fun ref(calendar:CalendarId,id:Long)=EventRef(calendar,id,dtstart,firstEnd,RecurrenceRef(id,dtstart))
    fun validate(draft:EventDraft) {
        val spec=requireNotNull(draft.recurrence)
        require(dtstart.nano==0&&firstEnd.nano==0&&dtstart.atOffset(ZoneOffset.UTC).year in 1..9999&&firstEnd.atOffset(ZoneOffset.UTC).year in 1..9999)
        val seconds=Math.subtractExact(firstEnd.epochSecond,dtstart.epochSecond);require(seconds in 1..Int.MAX_VALUE.toLong())
        val expected=when(val time=draft.time){is EventTime.Timed->time.start to time.end;is EventTime.AllDay->time.dates.start.atStartOfDay(ZoneOffset.UTC).toInstant() to time.dates.endExclusive.atStartOfDay(ZoneOffset.UTC).toInstant()}
        require(expected==(dtstart to firstEnd))
        if(allDay){require(draft.time is EventTime.AllDay&&zone=="UTC"&&startLocal==null&&startOffset==null&&seconds%86400==0L);require(duration=="P${seconds/86400}D")}
        else {require(draft.time is EventTime.Timed&&zone==draft.eventZone?.id&&startLocal!=null&&startOffset!=null)
            require(LocalDateTime.parse(startLocal).toInstant(ZoneOffset.ofTotalSeconds(startOffset))==dtstart);require(duration=="PT${seconds}S")}
        require(spec.interval in 1..RecurrenceCreation.MAX_INTERVAL)
        val parts=when(val p=spec.pattern){RecurrencePattern.Daily->mutableListOf("FREQ=DAILY")
            is RecurrencePattern.Weekly->mutableListOf("FREQ=WEEKLY","BYDAY="+p.days.sortedBy{it.value}.joinToString(","){day(it)})
            is RecurrencePattern.MonthlyDate->mutableListOf("FREQ=MONTHLY","BYMONTHDAY=${p.day}")
            is RecurrencePattern.MonthlyWeekday->mutableListOf("FREQ=MONTHLY","BYDAY=${p.ordinal}${day(p.day)}")
            is RecurrencePattern.Yearly->mutableListOf("FREQ=YEARLY","BYMONTH=${p.monthDay.monthValue}","BYMONTHDAY=${p.monthDay.dayOfMonth}")}
        parts+="INTERVAL=${spec.interval}";parts+="WKST=${day(spec.weekStart)}"
        when(val end=spec.end){RecurrenceEnd.Never->require(untilBoundaryLocal==null&&untilBoundaryOffset==null)
            is RecurrenceEnd.Count->{require(end.count in 1..RecurrenceCreation.MAX_COUNT&&untilBoundaryLocal==null&&untilBoundaryOffset==null);parts+="COUNT=${end.count}"}
            is RecurrenceEnd.ThroughDate->{if(allDay){require(untilBoundaryLocal==null&&untilBoundaryOffset==null);parts+="UNTIL="+end.date.format(DateTimeFormatter.BASIC_ISO_DATE)}
                else {val boundary=LocalDateTime.parse(requireNotNull(untilBoundaryLocal));requireNotNull(untilBoundaryOffset)
                    require(!boundary.isBefore(end.date.plusDays(1).atStartOfDay())&&boundary.isBefore(end.date.plusDays(3).atStartOfDay()))
                    val cutoff=boundary.toInstant(ZoneOffset.ofTotalSeconds(untilBoundaryOffset)).minusSeconds(1);require(cutoff>=dtstart)
                    parts+="UNTIL="+Utc.format(cutoff)}}
            is RecurrenceEnd.ExactUntil->error("Imported UNTIL is not an authored request")}
        require(rule==parts.joinToString(";"))
    }
    companion object {
        private val Utc=DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
        private fun day(d:DayOfWeek)=d.name.take(2)
        fun freeze(plan:RecurrenceCreationPlan,draft:EventDraft):FrozenMeetingCreationPlan {
            val timed=plan.start as? RecurrenceStart.Timed
            val boundary=(draft.recurrence?.end as? RecurrenceEnd.ThroughDate)?.takeIf{!plan.allDay}?.date?.plusDays(1)?.atStartOfDay(plan.eventZone)
            return FrozenMeetingCreationPlan(plan.dtstart,when(val t=plan.firstTime){is EventTime.Timed->t.end;is EventTime.AllDay->t.dates.endExclusive.atStartOfDay(ZoneOffset.UTC).toInstant()},plan.allDay,
                if(plan.allDay)"UTC"else plan.eventZone.id,plan.ruleRaw,plan.durationRaw,timed?.localDateTime?.toString(),timed?.preferredOffset?.totalSeconds,
                boundary?.toLocalDateTime()?.toString(),boundary?.offset?.totalSeconds).also{it.validate(draft)}
        }
        fun parse(j:JSONObject,draft:EventDraft):FrozenMeetingCreationPlan {
            require(j.getInt("version")==1)
            fun text(k:String)=if(j.isNull(k))null else j.getString(k)
            fun number(k:String)=if(j.isNull(k))null else j.getInt(k)
            return FrozenMeetingCreationPlan(Instant.parse(j.getString("start")),Instant.parse(j.getString("end")),j.getBoolean("allDay"),j.getString("zone"),j.getString("rrule"),j.getString("duration"),text("startLocal"),number("startOffset"),text("untilBoundaryLocal"),number("untilBoundaryOffset")).also{it.validate(draft);require(it.json().toString()==j.toString())}
        }
    }
}

internal object RecurringMeetingCreationCodec {
    const val Kind="MEETING_V2_CREATE_RECURRING"
    const val Feature="MEETING_CREATE_COMMAND_V2"
    fun command(token:EditToken,draft:EventDraft,guests:List<MeetingGuestInput>,maxBytes:Int):JSONObject {
        require(draft.recurrence!=null);if(guests.size>200||token.value.length>64*1024)throw ProviderReadLimitExceeded()
        checkCanonical(guests.map{listOf(it.email,it.name,it.type.name)},maxBytes)
        return JSONObject().put("version",2).put("token",token.value).put("draft",CalendarWriter.frozenDraftJson(draft,maxBytes))
            .put("guests",JSONArray(guests.map{JSONObject().put("email",it.email).put("type",it.type.name).put("name",it.name?:JSONObject.NULL)})).also{checkStoredJson(it.toString(),maxBytes)}
    }
    fun parse(j:JSONObject):Triple<EditToken,EventDraft,List<MeetingGuestInput>> {
        require(j.getInt("version")==2);val d=j.getJSONObject("draft");require(d.has("recurrence"))
        val start=Instant.parse(d.getString("start"));val end=Instant.parse(d.getString("end"));val time=if(d.getBoolean("allDay"))EventTime.AllDay.fromProvider(start,end)else EventTime.Timed(start,end)
        val alerts=d.getJSONArray("alerts");val guests=j.getJSONArray("guests");require(guests.length()<=200)
        val draft=EventDraft(CalendarId(d.getLong("calendar")),time,if(d.isNull("zone"))null else ZoneId.of(d.getString("zone")),d.getString("title"),d.getString("location"),d.getString("description"),List(alerts.length()){alerts.getInt(it)},
            if(d.has("availability"))d.getInt("availability")else null,if(d.has("privacy"))d.getInt("privacy")else null,CalendarWriter.frozenDraftIdentity(d.getJSONObject("targetIdentity")),CalendarWriter.frozenDraftRecurrence(d.getJSONObject("recurrence")))
        val inputs=List(guests.length()){i->guests.getJSONObject(i).let{MeetingGuestInput(it.getString("email"),MeetingGuestType.valueOf(it.getString("type")),if(it.isNull("name"))null else it.getString("name"))}}
        return Triple(EditToken(j.getString("token")),draft,inputs)
    }
}
