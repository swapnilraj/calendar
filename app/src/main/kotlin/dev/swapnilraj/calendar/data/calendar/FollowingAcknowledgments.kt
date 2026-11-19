package dev.swapnilraj.calendar.data.calendar

import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.CalendarContract
import org.json.JSONArray
import org.json.JSONObject

/** Every mutation/assertion result has one frozen meaning; inserted children retain slot identity. */
internal data class FollowingNativeStep(
    val kind:String, val table:String, val id:Long?=null, val values:Map<String,String?> = emptyMap(),
    val selection:String?=null, val args:List<String?> = emptyList(), val expected:Int?=null,
    val parentBackref:Int?=null, val selectionBackref:Int?=null, val slot:String?=null,
) {
    fun build():ContentProviderOperation {
        val collection=Uri.parse("content://${CalendarContract.AUTHORITY}/$table")
        val uri=id?.let{ContentUris.withAppendedId(collection,it)}?:collection
        val builder=when(kind){"ASSERT"->ContentProviderOperation.newAssertQuery(uri);"UPDATE"->ContentProviderOperation.newUpdate(uri);"DELETE"->ContentProviderOperation.newDelete(uri);"INSERT"->ContentProviderOperation.newInsert(uri);else->error("Invalid native step")}
        if(values.isNotEmpty())builder.withValues(ContentValues().apply{values.forEach{(k,v)->if(v==null)putNull(k)else put(k,v)}})
        if(selection!=null)builder.withSelection(selection,args.toTypedArray())
        parentBackref?.let{builder.withValueBackReference("event_id",it)}
        selectionBackref?.let{builder.withSelectionBackReference(0,it)}
        expected?.let(builder::withExpectedCount)
        return builder.withYieldAllowed(false).build()
    }
}

internal object FollowingNativePlan {
    fun steps(r:FollowingExecutionReceipt):List<FollowingNativeStep> = buildList {
        val id=r.masterId;val calendar=r.calendarId;val sync=r.master["_sync_id"]?.takeIf(String::isNotBlank)
        fun row(table:String,id:Long,values:Map<String,String?>){add(FollowingNativeStep("ASSERT",table,id,values,expected=1))}
        row("calendars",calendar,r.calendar)
        val graph="deleted = 0 AND (_id = ? OR original_id = ?"+if(sync==null)")"else" OR (calendar_id = ? AND original_sync_id = ?))"
        add(FollowingNativeStep("ASSERT","events",selection=graph,args=listOf(id.toString(),id.toString())+if(sync==null)emptyList()else listOf(calendar.toString(),sync),expected=1))
        if(sync==null)add(FollowingNativeStep("ASSERT","events",selection="calendar_id = ? AND original_id IS NULL AND original_sync_id IS NOT NULL AND deleted = 0",args=listOf(calendar.toString()),expected=0))
        row("events",id,r.master)
        for(table in listOf("reminders","attendees","extendedproperties")) {
            val children=r.children(table)
            add(FollowingNativeStep("ASSERT",table,values=mapOf("event_id" to id.toString()),selection="event_id = ?",args=listOf(id.toString()),expected=children.size))
            children.forEach{row(table,requireNotNull(it["_id"]).toLong(),it)}
        }
        add(FollowingNativeStep("UPDATE","events",id,r.prefixWrite,expected=1))
        var tailIndex:Int?=null
        if(r.whole){
            if(r.prefixReminders!=r.children("reminders")){
                val deleted=r.children("reminders").count{it["method"]=="1"}
                add(FollowingNativeStep("DELETE","reminders",selection="event_id = ? AND method = ?",args=listOf(id.toString(),"1"),expected=deleted))
                r.prefixReminders.forEachIndexed{index,child->if(!child.containsKey("_id"))add(FollowingNativeStep("INSERT","reminders",values=child+mapOf("event_id" to id.toString()),slot="PREFIX_REMINDER:$index"))}
            }
        } else {
            tailIndex=size
            add(FollowingNativeStep("INSERT","events",values=requireNotNull(r.tail),slot="TAIL"))
            for((table,children,label)in listOf(Triple("reminders",r.tailReminders,"TAIL_REMINDER"),Triple("attendees",r.tailAttendees,"TAIL_ATTENDEE")))
                children.forEachIndexed{index,child->add(FollowingNativeStep("INSERT",table,values=child,parentBackref=tailIndex,slot="$label:$index"))}
        }
        row("events",id,r.prefix)
        fun assertChildren(parent:Long?,backref:Int?,table:String,children:List<Map<String,String?>>){
            add(FollowingNativeStep("ASSERT",table,values=if(parent!=null)mapOf("event_id" to parent.toString())else emptyMap(),
                selection="event_id = ?",args=listOf(parent?.toString()?:"0"),expected=children.size,parentBackref=backref,selectionBackref=backref))
            children.forEach{child->if(child.containsKey("_id"))row(table,requireNotNull(child["_id"]).toLong(),child)else{
                val fields=child.keys.toList()
                add(FollowingNativeStep("ASSERT",table,values=child,selection="event_id = ?"+fields.joinToString(""){" AND $it IS ?"},
                    args=listOf(parent?.toString()?:"0")+fields.map{child[it]},expected=children.count{it==child},selectionBackref=backref))
            }}
        }
        for(table in listOf("reminders","attendees","extendedproperties"))assertChildren(id,null,table,if(table=="reminders")r.prefixReminders else r.children(table))
        if(!r.whole){
            val tail=requireNotNull(r.tail)
            val expected=tail+mapOf("deleted" to "0","isOrganizer" to "1","hasAlarm" to if(r.tailReminders.isEmpty())"0"else"1","hasExtendedProperties" to "0",
                "selfAttendeeStatus" to (r.tailAttendees.singleOrNull()?.get("attendeeStatus")?:"0"),"_sync_id" to null,"rdate" to null,"exrule" to null,"exdate" to if(r.version==4)tail["exdate"]else null,
                "original_id" to null,"original_sync_id" to null,"originalInstanceTime" to null,"originalAllDay" to null)
            add(FollowingNativeStep("ASSERT","events",values=expected,selection="_id = ?",args=listOf("0"),expected=1,selectionBackref=tailIndex))
            for(table in listOf("reminders","attendees","extendedproperties"))assertChildren(null,tailIndex,table,when(table){"reminders"->r.tailReminders;"attendees"->r.tailAttendees;else->emptyList()})
        }
    }
}

internal object FollowingAcknowledgments {
    fun layout(steps:List<FollowingNativeStep>)=JSONObject().put("resultCount",steps.size).put("results",JSONArray(steps.mapIndexed{index,step->
        JSONObject().put("index",index).put("kind",step.kind).put("table",step.table).put("count",step.expected?:JSONObject.NULL).put("slot",step.slot?:JSONObject.NULL)
    }))
    fun capture(results:Array<ContentProviderResult>,expected:Int)=CalendarCreateAcknowledgments.capture(results,expected)
    fun ids(ids:Map<String,Long>)=JSONObject().apply{ids.toSortedMap().forEach{(slot,id)->put(slot,id)}}
    fun insertIds(facts:JSONObject?,steps:List<FollowingNativeStep>,result:Long,prefix:Long):Map<String,Long>? = try {
        val raw=requireNotNull(facts);CreateSixJson.facts(raw)
        require(CreateSixJson.number(raw,"resultCount")==steps.size.toLong())
        val rows=raw.getJSONArray("results");require(rows.length()==steps.size)
        val inserted=linkedMapOf<String,Long>();val used=mutableMapOf<String,MutableSet<Long>>()
        steps.forEachIndexed{index,step->
            val row=rows.getJSONObject(index);require(CreateSixJson.number(row,"index")==index.toLong())
            if(step.kind=="INSERT"){
                require(row.get("count")===JSONObject.NULL)
                val id=CalendarCreateAcknowledgments.insertId(Uri.parse(CreateSixJson.text(row,"uri")),step.table)
                require(used.getOrPut(step.table){mutableSetOf()}.add(id));inserted[requireNotNull(step.slot)]=id
            }else{require(row.get("uri")===JSONObject.NULL&&CreateSixJson.number(row,"count")==requireNotNull(step.expected).toLong())}
        }
        val tail=inserted["TAIL"]
        require(if(tail==null)result==prefix else result==tail&&tail!=prefix)
        inserted
    }catch(_:Exception){null}
}
