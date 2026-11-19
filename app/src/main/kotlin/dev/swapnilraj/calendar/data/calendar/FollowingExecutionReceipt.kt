package dev.swapnilraj.calendar.data.calendar

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.CalendarContract
import dev.swapnilraj.calendar.core.*
import java.time.Instant
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Private family3 material. Decoding never reconstructs a planning snapshot or a timezone. */
internal data class FollowingExecutionReceipt(
    val operationId: String, val commandRaw: String, val token: String, val sourceRaw: String,
    val whole: Boolean, val ordinal: Int, val prefixWrite: Map<String,String?>,
    val prefix: Map<String,String?>, val prefixReminders: List<Map<String,String?>>,
    val tail: Map<String,String?>?, val tailReminders: List<Map<String,String?>>,
    val tailAttendees: List<Map<String,String?>>, val firstStart: Instant, val firstEnd: Instant,
) {
    val source: JSONObject get() = JSONObject(sourceRaw)
    val calendar get() = CreateSixJson.row(source.getJSONObject("calendar"))
    val master get() = CreateSixJson.row(source.getJSONObject("master"))
    val selected get() = CreateSixJson.ref(source.getJSONObject("selection"))
    val masterId get() = requireNotNull(master["_id"]).toLong()
    val calendarId get() = requireNotNull(master["calendar_id"]).toLong()
    val command get() = JSONObject(commandRaw)
    val uid get() = tail?.get("uid2445")
    val signature: String get() = CreateSixJson.hash(JSONObject().put("version",1).put("selected",CreateSixJson.ref(selected))
        .put("token",token).put("command",command).toString())
    fun children(table: String): List<Map<String,String?>> {
        val key=when(table){"reminders"->"reminders";"attendees"->"attendees";"extendedproperties"->"extendedProperties";else->error("Unknown children")}
        val groups=source.getJSONArray(key)
        return if(groups.length()==0)emptyList() else FollowingJson.rows(groups.getJSONObject(0).getJSONArray("rows"))
    }
    fun plan()=JSONObject().put("version",1).put("whole",whole).put("ordinal",ordinal).put("prefixWrite",CreateSixJson.row(prefixWrite))
        .put("prefix",CreateSixJson.row(prefix)).put("prefixReminders",FollowingJson.rows(prefixReminders))
        .put("tail",tail?.let(CreateSixJson::row)?:JSONObject.NULL).put("tailReminders",FollowingJson.rows(tailReminders))
        .put("tailAttendees",FollowingJson.rows(tailAttendees)).put("firstStart",firstStart.toString()).put("firstEnd",firstEnd.toString())
    private fun digest()=CreateSixJson.hash(Feature+"\n1\n"+operationId+"\n"+signature+"\n"+commandRaw+"\n"+token+"\n"+sourceRaw+"\n"+
        CreateSixJson.canonical(plan())+"\n"+CreateSixJson.canonical(FollowingAcknowledgments.layout(steps())))
    fun prepared()=JSONObject().put("version",3).put("feature",Feature).put("kind",Kind).put("stage","prepared")
        .put("signature",signature).put("command",command).put("token",token).put("sourceRaw",sourceRaw)
        .put("plan",plan()).put("layout",FollowingAcknowledgments.layout(steps())).put("planDigestVersion",1).put("planDigest",digest())
    fun ref(result:Long)=EventRef(CalendarId(calendarId),result,firstStart,firstEnd,RecurrenceRef(result,firstStart))

    fun validate() {
        require(operationId.isNotBlank()&&operationId.length<=65536&&command.toString()==commandRaw&&source.toString()==sourceRaw)
        val s=source
        CreateSixJson.keys(s,"version","calendar","master","exceptions","reminders","attendees","extendedProperties","selection")
        require(CreateSixJson.number(s,"version")==1L&&s.getJSONArray("exceptions").length()==0)
        require(token==CreateSixJson.hash("SERIES_EDIT_V1"+sourceRaw))
        val m=master;val c=calendar;val selection=selected
        require(m.size<=128&&c.size<=128&&prefix.size<=128&&prefixWrite.size<=128&&(tail?.size?:0)<=128)
        require(masterId>0&&m["_id"]==masterId.toString()&&m["calendar_id"]==calendarId.toString()&&calendarId>=0&&c["_id"]==calendarId.toString()&&selection.calendarId.value==calendarId&&selection.eventId==masterId&&selection.recurrence?.masterEventId==masterId)
        require(m["deleted"]=="0"&&m["dtend"]==null&&m["allDay"] in setOf("0","1"))
        require(listOf("rdate","exdate","exrule").all{m[it].isNullOrEmpty()}&&listOf("original_id","original_sync_id","originalInstanceTime","originalAllDay").all{m[it]==null})
        for(table in listOf("reminders","attendees","extendedproperties")) {
            val key=if(table=="extendedproperties")"extendedProperties"else table
            val groups=s.getJSONArray(key);require(groups.length()<=1)
            if(groups.length()==1){val group=groups.getJSONObject(0);CreateSixJson.keys(group,"parent","rows");require(CreateSixJson.number(group,"parent")==masterId)}
            val rows=children(table);require(rows.all{it.size<=128&&it["event_id"]==masterId.toString()&&it["_id"]?.toLongOrNull()?.let{n->n>0&&n.toString()==it["_id"]}==true})
            require(rows.map{it["_id"]}.distinct().size==rows.size)
        }
        require(whole==(ordinal==0)&&ordinal in 0..10000)
        require(prefixWrite.keys.all{it in PrefixFields}&&prefixWrite["uid2445"]==null&&prefixWrite["_sync_id"]==null)
        val expected=m.filterKeys{it !in Bookkeeping}+prefixWrite
        require(prefix==expected)
        require(prefix["_id"]==masterId.toString()&&prefix["calendar_id"]==calendarId.toString()&&prefix["uid2445"]==m["uid2445"]&&prefix["_sync_id"]==m["_sync_id"])
        require(firstEnd>firstStart&&firstStart.nano==0&&firstEnd.nano==0)
        val output=if(whole){require(tail==null&&tailReminders.isEmpty()&&tailAttendees.isEmpty());prefix}else{
            require(!m["uid2445"].isNullOrBlank()||!m["_sync_id"].isNullOrBlank())
            require(prefixReminders==children("reminders")&&children("extendedproperties").isEmpty())
            val context=Context.associateWith{m[it]};require(prefixWrite==context+mapOf("rrule" to prefix["rrule"]))
            val t=requireNotNull(tail);require(t.keys.all{it in TailFields}&&t["calendar_id"]==calendarId.toString()&&t["dtend"]==null)
            val identity=requireNotNull(t["uid2445"]);require(UUID.fromString(identity).toString()==identity&&!identity.equals(m["uid2445"],true))
            t
        }
        require(output["allDay"]==m["allDay"]&&output["dtstart"]?.toLongOrNull()==firstStart.toEpochMilli())
        val duration=FollowingJson.durationSeconds(requireNotNull(output["duration"]))
        require(firstEnd==firstStart.plusSeconds(duration)&&!output["rrule"].isNullOrBlank())
        require(output["eventTimezone"]!=null)
        if(output["allDay"]=="1")require(firstStart.epochSecond%86400L==0L&&duration%86400L==0L&&output["eventTimezone"]=="UTC")
        tailReminders.forEach{require(it.keys==setOf("minutes","method")&&it.values.all{v->v!=null})}
        tailAttendees.forEach{require(it.keys.all{k->k in AttendeeFields}&&it["attendeeEmail"]==c["ownerAccount"]&&it["attendeeRelationship"]=="2")}
        require(tailAttendees.size<=1)
        validateCommand()
        require(steps().size<=4002)
    }
    private fun validateCommand() {
        val j=command;val required=setOf("type","expected","patch","duration","rule","selectedPatch")
        require(j.keys().asSequence().toSet().containsAll(required)&&j.keys().asSequence().all{it in required+"start"})
        require(CreateSixJson.text(j,"type")=="following"&&j.getJSONObject("selectedPatch").length()==0)
        val expected=j.getJSONObject("expected")
        if(expected.has("date")){CreateSixJson.keys(expected,"date");require(master["allDay"]=="1"&&java.time.LocalDate.parse(CreateSixJson.text(expected,"date"))==requireNotNull(selected.recurrence).originalStart.atOffset(java.time.ZoneOffset.UTC).toLocalDate())}
        else{CreateSixJson.keys(expected,"instant");require(master["allDay"]=="0"&&CreateSixJson.instant(expected,"instant")==requireNotNull(selected.recurrence).originalStart)}
        val patch=j.getJSONObject("patch");require(patch.keys().asSequence().all{it in setOf("title","location","description","alerts","availability","privacy")})
        val target=if(whole)prefix else requireNotNull(tail)
        for((input,native)in listOf("title" to "title","location" to "eventLocation","description" to "description"))if(patch.has(input))require(CreateSixJson.text(patch,input)==target[native])
        for((input,native)in listOf("availability" to "availability","privacy" to "accessLevel"))if(patch.has(input))require((CreateSixJson.nullableInt(patch,input)?:0).toString()==target[native])
        val originalReminders=children("reminders")
        val patchedReminders=if(patch.has("alerts"))originalReminders.filter{it["method"]!="1"}+
            CreateSixJson.alerts(patch.getJSONArray("alerts")).map{mapOf("minutes" to it.toString(),"method" to "1")} else originalReminders
        require(prefixReminders==if(whole)patchedReminders else originalReminders)
        if(!whole)require(tailReminders==patchedReminders.map{it.filterKeys{k->k in setOf("minutes","method")}})
        if(patch.has("alerts")) {
            val alerts=CreateSixJson.alerts(patch.getJSONArray("alerts"))
            val rows=if(whole)prefixReminders else tailReminders
            require(rows.filter{it["method"]=="1"}.map{it["minutes"]?.toInt()}==alerts)
        }
        val metadata=mapOf("title" to "title","location" to "eventLocation","description" to "description","availability" to "availability","privacy" to "accessLevel")
        for((input,native)in metadata)if(!patch.has(input))require(target[native]==master[native])
        if(whole)require(prefixWrite.keys.all{it in Context+setOf("eventEndTimezone","calendar_id","rrule","hasAlarm")||metadata.any{(input,native)->patch.has(input)&&it==native}})
        if(whole) {
            val alarm=if(patch.has("alerts"))if(patchedReminders.isEmpty())"0"else"1" else master["hasAlarm"]
            require(prefix["hasAlarm"]==alarm)
            if(!patch.has("alerts"))require("hasAlarm" !in prefixWrite)
        } else {
            val t=requireNotNull(tail)
            val inputFields=metadata.filterKeys{patch.has(it)}.values.toSet()
            val copied=PublicFields.filterTo(linkedSetOf()){master.containsKey(it)}
            val fields=copied+inputFields+TailTimeFields+"uid2445"+if(patch.has("alerts"))setOf("hasAlarm")else emptySet()
            require(t.keys==fields)
            for(key in copied-inputFields)require(t[key]==master[key])
            if(patch.has("alerts"))require(t["hasAlarm"]==if(patchedReminders.isEmpty())"0"else"1")
            val unchangedClock=!j.has("start")&&j.get("duration")=="keep"
            require(t["eventEndTimezone"]==if(unchangedClock)master["eventEndTimezone"]else t["eventTimezone"])
            require(tailAttendees==children("attendees").map{row->row.filterKeys{it in AttendeeFields}})
        }
        val d=j.get("duration")
        if(d is String)require(d=="keep"&&target["duration"]==master["duration"])else{val o=d as? JSONObject?:error("Invalid duration");require(o.length()==1);val k=if(o.has("seconds"))"seconds"else"days";CreateSixJson.keys(o,k);val n=CreateSixJson.number(o,k);require(n>0&&target["duration"]==if(k=="seconds")"PT${n}S"else"P${n}D")}
        // Rules and explicit starts are frozen protocol atoms, never passed to today's compiler.
        FollowingJson.rule(j.getJSONObject("rule"))
        if(j.has("start"))FollowingJson.start(j.getJSONObject("start"))
    }
    fun steps():List<FollowingNativeStep> = FollowingNativePlan.steps(this)
    companion object {
        const val Kind="SERIES_V1_FOLLOWING_EDIT"
        const val Feature="SERIES_FOLLOWING_EDIT_V3"
        val Context=listOf("dtstart","dtend","duration","eventTimezone","allDay","originalInstanceTime","originalAllDay")
        val Bookkeeping=setOf("dirty","mutators","lastDate","lastSynced")
        val PrefixFields=Context.toSet()+setOf("eventEndTimezone","calendar_id","rrule","hasAlarm","title","eventLocation","description","availability","accessLevel")
        val AttendeeFields=setOf("attendeeName","attendeeEmail","attendeeRelationship","attendeeType","attendeeStatus")
        val TailTimeFields=setOf("calendar_id","dtstart","dtend","duration","allDay","eventTimezone","eventEndTimezone","rrule")
        val PublicFields=setOf("title","description","eventLocation","eventStatus","eventColor","availability","accessLevel","organizer","hasAttendeeData","guestsCanModify","guestsCanInviteOthers","guestsCanSeeGuests")
        val TailFields=PrefixFields+setOf("uid2445","eventStatus","eventColor","organizer","hasAttendeeData","guestsCanModify","guestsCanInviteOthers","guestsCanSeeGuests")
        fun handles(op:CalendarLocalStore.Operation)=op.kind==Kind&&runCatching{JSONObject(op.resultRef?:"{}").optInt("version")==3}.getOrDefault(false)
        fun parse(op:CalendarLocalStore.Operation,maxBytes:Int):Pair<JSONObject,FollowingExecutionReceipt> {
            val raw=requireNotNull(op.resultRef);checkStoredJson(raw,maxBytes);val j=JSONObject(raw)
            require(op.kind==Kind&&CreateSixJson.number(j,"version")==3L&&CreateSixJson.text(j,"feature")==Feature&&CreateSixJson.text(j,"kind")==Kind)
            val prepared=op.phase in setOf("PREPARED","FAILED","ATTEMPTED");require(prepared||op.phase in setOf("COMMITTED","VERIFIED"))
            val base=setOf("version","feature","kind","stage","signature","command","token","sourceRaw","plan","layout","planDigestVersion","planDigest")
            val needed=base+if(prepared)emptySet()else setOf("result","ackComplete")+if(op.phase=="VERIFIED")setOf("insertIds","ref")else emptySet()
            val keys=j.keys().asSequence().toSet();require(keys.containsAll(needed)&&keys.all{it in needed+if(prepared)emptySet()else setOf("ackFacts","tailSync")})
            require(CreateSixJson.text(j,"stage")==if(prepared)"prepared"else"committed")
            val p=j.getJSONObject("plan");CreateSixJson.keys(p,"version","whole","ordinal","prefixWrite","prefix","prefixReminders","tail","tailReminders","tailAttendees","firstStart","firstEnd")
            require(CreateSixJson.number(p,"version")==1L&&CreateSixJson.number(j,"planDigestVersion")==1L)
            val ordinal=CreateSixJson.number(p,"ordinal");require(ordinal in 0..10000)
            val r=FollowingExecutionReceipt(op.id,j.getJSONObject("command").toString(),CreateSixJson.text(j,"token"),CreateSixJson.text(j,"sourceRaw"),CreateSixJson.bool(p,"whole"),ordinal.toInt(),
                CreateSixJson.row(p.getJSONObject("prefixWrite")),CreateSixJson.row(p.getJSONObject("prefix")),FollowingJson.rows(p.getJSONArray("prefixReminders")),
                if(p.get("tail")===JSONObject.NULL)null else CreateSixJson.row(p.getJSONObject("tail")),FollowingJson.rows(p.getJSONArray("tailReminders")),FollowingJson.rows(p.getJSONArray("tailAttendees")),
                CreateSixJson.instant(p,"firstStart"),CreateSixJson.instant(p,"firstEnd"))
            r.validate();require(r.signature==op.signature&&CreateSixJson.text(j,"signature")==op.signature&&CreateSixJson.text(j,"planDigest")==r.digest())
            require(CreateSixJson.canonical(j.getJSONObject("layout"))==CreateSixJson.canonical(FollowingAcknowledgments.layout(r.steps())))
            if(!prepared){val result=CreateSixJson.number(j,"result");require(result>0&&if(r.whole)result==r.masterId else result!=r.masterId)
                if(j.has("ackFacts"))CreateSixJson.facts(j.getJSONObject("ackFacts"))
                require(CreateSixJson.bool(j,"ackComplete")== (op.phase=="VERIFIED"))
                if(j.has("tailSync")){require(!r.whole);CreateSixJson.nullableText(j,"tailSync")}
                if(op.phase=="VERIFIED"){val ids=requireNotNull(FollowingAcknowledgments.insertIds(j.optJSONObject("ackFacts"),r.steps(),result,r.masterId))
                    require(CreateSixJson.canonical(j.getJSONObject("insertIds"))==CreateSixJson.canonical(FollowingAcknowledgments.ids(ids)))
                    require(CreateSixJson.ref(j.getJSONObject("ref"))==r.ref(result))}
            }
            return j to r
        }
    }
}

internal object FollowingJson {
    fun rows(rows:List<Map<String,String?>>)=JSONArray(rows.map(CreateSixJson::row))
    fun rows(a:JSONArray):List<Map<String,String?>>{require(a.length()<=4000);return List(a.length()){CreateSixJson.row(a.getJSONObject(it))}}
    fun durationSeconds(raw:String):Long {
        val match=Regex("^P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?$").matchEntire(raw)?:error("Unsupported native duration")
        require(match.groupValues.drop(1).any{it.isNotEmpty()})
        var total=0L
        match.groupValues.drop(1).zip(listOf(604800L,86400L,3600L,60L,1L)).forEach{(n,m)->if(n.isNotEmpty())total=Math.addExact(total,Math.multiplyExact(n.toLong(),m))}
        require(total in 1..Int.MAX_VALUE.toLong());return total
    }
    fun start(j:JSONObject){if(j.has("date")){CreateSixJson.keys(j,"date");java.time.LocalDate.parse(CreateSixJson.text(j,"date"))}else{
        CreateSixJson.keys(j,"local","zone","offset");java.time.LocalDateTime.parse(CreateSixJson.text(j,"local"));CreateSixJson.text(j,"zone");CreateSixJson.nullableText(j,"offset")?.let{java.time.ZoneOffset.of(it)}}}
    fun rule(j:JSONObject){require(j.keys().asSequence().all{it in setOf("pattern","interval","weekStart","end")})
        if(j.has("interval"))require(CreateSixJson.number(j,"interval") in 1..1000)
        if(j.has("weekStart"))java.time.DayOfWeek.valueOf(CreateSixJson.text(j,"weekStart"))
        if(j.has("pattern")){val p=j.getJSONObject("pattern");when(CreateSixJson.text(p,"type")){
            "daily"->CreateSixJson.keys(p,"type")
            "weekly"->{CreateSixJson.keys(p,"type","days");val a=p.getJSONArray("days");require(a.length() in 1..7);val days=List(a.length()){java.time.DayOfWeek.valueOf(a.get(it) as? String?:error("Invalid day"))};require(days.distinct().size==days.size)}
            "monthlyDate"->{CreateSixJson.keys(p,"type","day");require(CreateSixJson.number(p,"day") in 1..31)}
            "monthlyWeekday"->{CreateSixJson.keys(p,"type","ordinal","day");require(CreateSixJson.number(p,"ordinal") in setOf(-1L,1L,2L,3L,4L,5L));java.time.DayOfWeek.valueOf(CreateSixJson.text(p,"day"))}
            "yearly"->{CreateSixJson.keys(p,"type","date");java.time.MonthDay.parse(CreateSixJson.text(p,"date"))}
            else->error("Invalid pattern")}}
        if(j.has("end")){val e=j.getJSONObject("end");require(e.length()==1);when{
            e.has("type")->require(CreateSixJson.text(e,"type")=="never")
            e.has("count")->require(CreateSixJson.number(e,"count") in 1..10000)
            e.has("date")->java.time.LocalDate.parse(CreateSixJson.text(e,"date"))
            else->CreateSixJson.instant(e,"instant")}}
    }
}
