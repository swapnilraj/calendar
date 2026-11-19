package dev.swapnilraj.calendar.data.calendar

import android.content.*
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract

/** Only native-derived fields and controlled faults needed by the Following backend tests.
 * Expansion/transactions remain the bounded scoped-series regression model, not device proof. */
internal class FollowingSqlProvider : ScopedSeriesSqlProvider() {
    var failReminder=false
    var malformedLastEventAck=false
    var beforeQuery:((String)->Unit)?=null
    var afterQuery:((String)->Unit)?=null
    val insertedEventValues=mutableListOf<ContentValues>()
    override fun query(uri:Uri,fields:Array<out String>?,selection:String?,args:Array<out String>?,sort:String?):Cursor {
        val path=uri.pathSegments.first();beforeQuery?.invoke(path)
        if(path !in setOf("events","attendees"))return super.query(uri,fields,selection,args,sort).also{afterQuery?.invoke(path)}
        if(failReadsAfterCommit&&committed)error("Synthetic post-commit read failure")
        queries+=Query(path,fields?.toList(),selection)
        val where=mutableListOf<String>();val bind=mutableListOf<String>()
        uri.lastPathSegment?.toLongOrNull()?.let{where+="_id = ?";bind+=it.toString()}
        if(selection!=null){where+="($selection)";bind+=args.orEmpty()}
        val projected=fields?:if(path=="events")db.rawQuery("PRAGMA table_info(events)",null).use{c->buildList{while(c.moveToNext())c.getString(1).takeIf{it!="native_duration_ms"}?.let(::add)}.toTypedArray()}else null
        val effective=if(path=="events")projected?.map{field->if(field=="isOrganizer")"COALESCE(isOrganizer, organizer = (SELECT ownerAccount FROM calendars WHERE calendars._id = events.calendar_id)) AS isOrganizer"else field}?.toTypedArray()else projected
        val table=if(path=="attendees")"(SELECT attendees.*, events.calendar_id, events.organizer, events.uid2445 FROM attendees JOIN events ON attendees.event_id = events._id)"else path
        return db.query(table,effective,where.joinToString(" AND ").takeIf{it.isNotEmpty()},bind.toTypedArray(),null,null,sort).also{afterQuery?.invoke(path)}
    }
    override fun insert(uri:Uri,values:ContentValues?):Uri {
        val path=uri.pathSegments.first()
        if(path=="reminders"&&failReminder)throw OperationApplicationException("Synthetic reminder rollback")
        val inserted=ContentValues(values)
        if(path=="events"){
            insertedEventValues+=ContentValues(values)
            if(!inserted.containsKey("organizer"))inserted.put("organizer","owner@example.invalid")
            if(!inserted.containsKey("eventStatus"))inserted.putNull("eventStatus")
            if(!inserted.containsKey("isOrganizer"))inserted.putNull("isOrganizer")
            inserted.put("hasAlarm",0)
        }
        val result=if(path=="attendees")ContentUris.withAppendedId(uri,db.insertOrThrow(path,null,inserted))else super.insert(uri,inserted)
        val id=ContentUris.parseId(result)
        if(path=="events"&&values!!.containsKey("selfAttendeeStatus"))db.insertOrThrow("attendees",null,ContentValues().apply{put("event_id",id);put("attendeeEmail","owner@example.invalid");put("attendeeStatus",values.getAsInteger("selfAttendeeStatus"));put("attendeeRelationship",1);put("attendeeType",0)})
        if(path=="attendees"&&inserted.getAsString("attendeeEmail")=="owner@example.invalid")db.execSQL("UPDATE events SET selfAttendeeStatus = ? WHERE _id = ?",arrayOf(inserted.getAsInteger("attendeeStatus")?:if(inserted.getAsInteger("attendeeRelationship")==2)1 else 0,inserted.getAsLong("event_id")))
        if(path=="reminders")db.execSQL("UPDATE events SET hasAlarm=1 WHERE _id = ?",arrayOf(inserted.getAsLong("event_id")))
        return result
    }
    override fun applyBatch(operations:ArrayList<ContentProviderOperation>):Array<ContentProviderResult> {
        val results=super.applyBatch(operations)
        if(malformedLastEventAck)results.indexOfLast{it.uri?.pathSegments?.firstOrNull()=="events"}.takeIf{it>=0}?.let{results[it]=ContentProviderResult(Uri.parse("content://${CalendarContract.AUTHORITY}/events/not-an-id"))}
        return results
    }
}
