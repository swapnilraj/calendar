package dev.swapnilraj.calendar.data.calendar

import dev.swapnilraj.calendar.core.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import org.json.JSONArray
import org.json.JSONObject

/** Literal, bounded issued exclusion authority. No recurrence compiler or zone resolution here. */
internal object FollowingExdateAuthority {
    private val date=DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT)
    private val utc=DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withResolverStyle(ResolverStyle.STRICT)
    fun eligible(i:RecurrenceEditIntent.FollowingSeriesEdit)=i.newTailStart==FieldChange.Keep&&i.duration==RecurrenceDurationChange.KeepRaw&&i.rule==RecurrenceRuleChanges()&&i.fields.time==FieldChange.Keep&&i.fields.eventZone==FieldChange.Keep&&i.selectedExceptionPatch==EventPatch()
    fun key(k:RecurrenceKey):JSONObject=when(k){is RecurrenceKey.Date->JSONObject().put("date",k.date.toString());is RecurrenceKey.Timed->JSONObject().put("instant",k.instant.toString())}
    private fun parseKey(j:JSONObject,allDay:Boolean):RecurrenceKey=if(allDay){CreateSixJson.keys(j,"date");RecurrenceKey.Date(LocalDate.parse(CreateSixJson.text(j,"date")))}else{CreateSixJson.keys(j,"instant");RecurrenceKey.Timed(CreateSixJson.instant(j,"instant"))}
    private fun before(a:RecurrenceKey,b:RecurrenceKey)=when(a){is RecurrenceKey.Date->a.date<(b as RecurrenceKey.Date).date;is RecurrenceKey.Timed->a.instant<(b as RecurrenceKey.Timed).instant}
    private fun token(raw:String,allDay:Boolean):RecurrenceKey {
        if(allDay&&Regex("[0-9]{8}").matches(raw)){val d=LocalDate.parse(raw,date);require(d.year in 1..9999);return RecurrenceKey.Date(d)}
        require(Regex("[0-9]{8}T[0-9]{6}Z").matches(raw))
        val local=LocalDateTime.parse(raw,utc);require(local.year in 1..9999);val instant=local.toInstant(ZoneOffset.UTC)
        return if(allDay){require(instant.epochSecond%86400L==0L);RecurrenceKey.Date(instant.atOffset(ZoneOffset.UTC).toLocalDate())}else RecurrenceKey.Timed(instant)
    }
    private fun groups(raw:String?,allDay:Boolean):JSONArray {
        val out=JSONArray();if(raw.isNullOrEmpty())return out
        require(raw.length<=65536&&'\r' !in raw)
        val lines=raw.removeSuffix("\n").split('\n');var count=0
        for(line in lines){val prefix=if(line.startsWith("UTC;"))"UTC;"else"";val values=line.removePrefix(prefix).split(',');val tokens=JSONArray()
            for(value in values){require(!(allDay&&prefix=="UTC;"&&Regex("[0-9]{8}").matches(value)));require(++count<=10000);tokens.put(JSONObject().put("raw",value).put("key",key(token(value,allDay))))}
            out.put(JSONObject().put("prefix",prefix).put("tokens",tokens))
        };return out
    }
    private fun partition(groups:JSONArray,boundary:RecurrenceKey,allDay:Boolean,prefix:Boolean,trailing:Boolean):String? {
        val lines=buildList{for(i in 0 until groups.length()){val g=groups.getJSONObject(i);val a=g.getJSONArray("tokens");val values=buildList{for(n in 0 until a.length()){val t=a.getJSONObject(n);if(before(parseKey(t.getJSONObject("key"),allDay),boundary)==prefix)add(CreateSixJson.text(t,"raw"))}};if(values.isNotEmpty())add(CreateSixJson.text(g,"prefix")+values.joinToString(","))}}
        return if(lines.isEmpty())null else lines.joinToString("\n")+if(trailing)"\n"else""
    }
    fun create(raw:String?,boundary:RecurrenceKey,previous:RecurrenceKey?,prefix:String?,tail:String?):String {
        val allDay=boundary is RecurrenceKey.Date
        return JSONObject().put("version",1).put("sourceRaw",raw?:JSONObject.NULL).put("prefixRaw",prefix?:JSONObject.NULL).put("tailRaw",tail?:JSONObject.NULL)
            .put("boundary",key(boundary)).put("previousNominal",previous?.let(::key)?:JSONObject.NULL).put("groups",groups(raw,allDay)).put("trailingLf",raw?.endsWith('\n')==true).toString()
    }
    fun validate(r:FollowingExecutionReceipt) {
        val j=JSONObject(requireNotNull(r.exclusionsRaw));CreateSixJson.keys(j,"version","sourceRaw","prefixRaw","tailRaw","boundary","previousNominal","groups","trailingLf")
        require(CreateSixJson.number(j,"version")==1L)
        require(r.firstStart==r.selected.instanceStart&&r.firstEnd==r.selected.instanceEnd)
        val target=if(r.whole)r.prefix else requireNotNull(r.tail)
        for(field in listOf("duration","allDay","eventTimezone","eventEndTimezone"))require(target[field]==r.master[field])
        if(r.whole)FollowingExecutionReceipt.Context.forEach{require(r.prefix[it]==r.master[it])}
        val allDay=r.master["allDay"]=="1";val boundary=parseKey(j.getJSONObject("boundary"),allDay)
        val expected=if(allDay)RecurrenceKey.Date(r.selected.recurrence!!.originalStart.atOffset(ZoneOffset.UTC).toLocalDate())else RecurrenceKey.Timed(r.selected.recurrence!!.originalStart)
        require(boundary==expected)
        val raw=CreateSixJson.nullableText(j,"sourceRaw");val prefix=CreateSixJson.nullableText(j,"prefixRaw");val tail=CreateSixJson.nullableText(j,"tailRaw")
        require(raw==r.master["exdate"]&&r.prefixWrite.containsKey("exdate")&&r.prefixWrite["exdate"]==prefix&&r.prefix["exdate"]==prefix)
        val gs=groups(raw,allDay);require(CreateSixJson.canonical(gs)==CreateSixJson.canonical(j.getJSONArray("groups")))
        val trailing=CreateSixJson.bool(j,"trailingLf");require(trailing==(raw?.endsWith('\n')==true))
        for(i in 0 until gs.length()){val a=gs.getJSONObject(i).getJSONArray("tokens");for(n in 0 until a.length())require(parseKey(a.getJSONObject(n).getJSONObject("key"),allDay)!=boundary)}
        val previous=if(j.get("previousNominal")===JSONObject.NULL)null else parseKey(j.getJSONObject("previousNominal"),allDay)
        val original=requireNotNull(r.master["rrule"])
        fun replace(name:String,value:String)=original.split(';').let{parts->if(parts.any{it.startsWith("$name=")})parts.joinToString(";"){if(it.startsWith("$name="))"$name=$value"else it}else"$original;$name=$value"}
        if(r.whole){require(previous==null&&prefix==raw&&tail==null&&r.prefix["rrule"]==original)}else{
            require(r.tail!!.containsKey("exdate")&&r.tail["exdate"]==tail)
            require(prefix==if(raw.isNullOrEmpty())raw else partition(gs,boundary,allDay,true,trailing))
            require(tail==if(raw.isNullOrEmpty())raw else partition(gs,boundary,allDay,false,trailing))
            val counts=original.split(';').filter{it.startsWith("COUNT=")};require(counts.size<=1)
            if(counts.isNotEmpty()){val count=counts.single().substringAfter('=').toInt();require(count>r.ordinal&&previous==null&&r.prefix["rrule"]==replace("COUNT",r.ordinal.toString())&&r.tail["rrule"]==replace("COUNT",(count-r.ordinal).toString()))}
            else{require(previous!=null&&before(previous,boundary));val literal=when(previous){is RecurrenceKey.Date->previous.date.format(date);is RecurrenceKey.Timed->previous.instant.atOffset(ZoneOffset.UTC).format(utc)};require(r.prefix["rrule"]==replace("UNTIL",literal)&&r.tail["rrule"]==original)}
        }
        val command=r.command;require(!command.has("start")&&command.get("duration")=="keep"&&command.getJSONObject("rule").length()==0&&command.getJSONObject("selectedPatch").length()==0)
    }
    fun previous(prefixRule:String,allDay:Boolean):RecurrenceKey?=prefixRule.split(';').singleOrNull{it.startsWith("UNTIL=")}?.substringAfter('=')?.let{token(it,allDay)}
}
