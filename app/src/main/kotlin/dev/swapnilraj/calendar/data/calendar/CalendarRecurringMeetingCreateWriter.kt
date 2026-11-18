package dev.swapnilraj.calendar.data.calendar

import android.Manifest
import android.content.*
import android.net.Uri
import android.os.Parcel
import android.provider.CalendarContract
import dev.swapnilraj.calendar.core.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Authored series only. Private V2 plans are frozen at PREPARED, never recompiled on retry or
 * recovery. A provisional UID is identity evidence, never a way to search for a lost insert. */
internal class CalendarRecurringMeetingCreateWriter(private val resolver:ContentResolver,private val reader:CalendarReader,
    private val store:CalendarLocalStore,private val hasPermission:(String)->Boolean,private val owner:CalendarMeetingCreateWriter,
    private val io:CoroutineDispatcher,private val readBudgets:ProviderReadBudgets) {
    private val mutex=Mutex()
    private val author=CalendarWriter(resolver,reader,store,hasPermission,io,readBudgets)
    private val provider=OwnedMeetingProvider(resolver,hasPermission,readBudgets)
    private data class Prepared(val command:JSONObject,val source:MeetingRecurringCreationSource,val draft:EventDraft,val inputs:List<MeetingGuestInput>,
        val plan:FrozenMeetingCreationPlan,val uid:String,val event:Map<String,String?>,val attendees:List<Map<String,String?>>)
    private data class Batch(val ops:ArrayList<ContentProviderOperation>,val event:Int,val attendees:List<Int>,val reminders:List<Int>)
    private class Conflict:Exception()
    private class Review(val reason:String):Exception()

    /** Calendar-only feasibility, without inventing an authored event or querying a private row. */
    fun checkSourceParcel(calendar:Map<String,String?>) {
        val ops=listOf(assertRow(ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI,meetingIdentity(calendar).calendarId.value),calendar))
        if(parcelSize(ops)>256*1024-16*1024)throw ProviderReadLimitExceeded()
    }
    suspend fun create(id:String,token:EditToken,draft:EventDraft,inputs:List<MeetingGuestInput>):MeetingMutationResult=withContext(io+readBudgetContext(readBudgets.mutation)){mutex.withLock {
        var attempted:CalendarLocalStore.Operation?=null;var known:Long?=null
        try {
            store.abandoned(id){fingerprint(token,draft,inputs,readBudgets.maxCanonicalBytes)}?.let{return@withLock common(it)}
            if(!permissions())return@withLock common(MutationResult.PermissionRequired)
            if(id.isBlank())return@withLock MeetingMutationResult.Invalid("Operation ID is required")
            if(draft.recurrence==null)return@withLock MeetingMutationResult.Unsupported("This executor requires an authored series")
            if(draft.targetIdentity==null||draft.targetIdentity?.calendarId!=draft.calendarId)return@withLock MeetingMutationResult.Invalid("Choose the original destination calendar again")
            val command=RecurringMeetingCreationCodec.command(token,draft,inputs,readBudgets.maxCanonicalBytes)
            val signature=meetingHash(RecurringMeetingCreationCodec.Feature+command.toString())
            val existing=store.operation(id)
            if(existing!=null){if(existing.kind!=Kind||existing.signature!=signature)return@withLock common(MutationResult.Conflict(null))
                if(existing.phase !in setOf("PREPARED","FAILED"))return@withLock recover(existing,false)}
            val prepared=if(existing!=null){proof(existing)?.second?:return@withLock common(MutationResult.Uncertain(id,null))}else{
                if(!token.value.startsWith(MeetingRecurringCreationSource.TokenPrefix))return@withLock MeetingMutationResult.NeedsReview("Reopen creation to obtain a source with recurring eligibility")
                val raw=store.edit(token.value)?:throw Conflict();val source=MeetingRecurringCreationSource.decode(raw,token,readBudgets.maxCanonicalBytes)
                val(_,frozenDraft,frozenInputs)=RecurringMeetingCreationCodec.parse(command)
                if(frozenDraft.targetIdentity!=meetingIdentity(source.calendar))throw Conflict()
                owner.verifyCalendarPolicy(source.calendar)
                val plan=FrozenMeetingCreationPlan.freeze(author.meetingRecurrencePlan(frozenDraft),frozenDraft)
                val payload=owner.initialRecurringPayload(source.calendar,frozenDraft.copy(recurrence=null),frozenInputs)
                val uid=UUID.randomUUID().toString()+"@daylight.invalid"
                val values=ContentValues(payload.first).apply{put("dtstart",plan.dtstart.toEpochMilli());putNull("dtend");put("allDay",if(plan.allDay)1 else 0);put("eventTimezone",plan.zone);put("eventEndTimezone",plan.zone);put("rrule",plan.rule);put("duration",plan.duration);put("uid2445",uid)}
                val p=Prepared(command,source,frozenDraft,frozenInputs,plan,uid,row(values),payload.second.map(::row))
                require(p.event==structuralEvent(p));p
            }
            if(prepared.command.toString()!=command.toString())return@withLock common(MutationResult.Conflict(null))
            ensureCurrentSource(prepared)
            val metadata=receipt(prepared,signature)
            preflightReceipts(prepared,metadata)
            var batch=batch(prepared);checkParcel(batch.ops)
            if(!permissions())return@withLock common(MutationResult.PermissionRequired)
            currentCoroutineContext().ensureActive();store.prepare(id,signature,Kind,metadata.toString())
            val persisted=store.operation(id)?:error("Journal unavailable")
            if(persisted.kind!=Kind||persisted.signature!=signature)return@withLock common(MutationResult.Conflict(null))
            if(persisted.phase=="ABANDONED")return@withLock common(MutationResult.Retired(id))
            if(persisted.phase !in setOf("PREPARED","FAILED"))return@withLock recover(persisted,false)
            val winner=proof(persisted)?.second?:return@withLock common(MutationResult.Uncertain(id,null))
            if(winner.command.toString()!=command.toString()||winner.source.encode(readBudgets.maxCanonicalBytes)!=prepared.source.encode(readBudgets.maxCanonicalBytes))return@withLock common(MutationResult.Conflict(null))
            // The winning stored plan/UID is authoritative, including any original UTC UNTIL.
            batch=batch(winner);checkParcel(batch.ops);val stored=receipt(winner,signature);preflightReceipts(winner,stored)
            ensureCurrentSource(winner)
            if(!permissions())return@withLock common(MutationResult.PermissionRequired)
            currentCoroutineContext().ensureActive();if(!store.attempt(persisted))return@withLock recover(store.operation(id)!!,false)
            attempted=persisted.copy(phase="ATTEMPTED")
            if(!permissions()){check(store.mark(attempted,"FAILED"));return@withLock common(MutationResult.PermissionRequired)}
            currentCoroutineContext().ensureActive();val results=resolver.applyBatch(CalendarContract.AUTHORITY,batch.ops)
            known=insertId(results.getOrNull(batch.event)?.uri,"events")
            val committed=JSONObject(stored.toString()).put("stage","committed").put("event",known)
            val ids=try{
                require(results.size==batch.ops.size)
                val a=batch.attendees.map{insertId(results.getOrNull(it)?.uri,"attendees")};val r=batch.reminders.map{insertId(results.getOrNull(it)?.uri,"reminders")}
                require(a.distinct().size==a.size&&r.distinct().size==r.size);a to r
            }catch(c:CancellationException){throw c}catch(_:Exception){null}
            if(ids==null){committed.put("ackComplete",false);check(store.mark(attempted,"COMMITTED",committed.toString()));return@withLock common(MutationResult.Uncertain(id,known))}
            committed.put("ackComplete",true).put("attendees",JSONArray(ids.first)).put("reminders",JSONArray(ids.second))
            check(store.mark(attempted,"COMMITTED",committed.toString()))
            recover(store.operation(id)!!,false)
        }catch(_:SecurityException){common(if(attempted==null)MutationResult.PermissionRequired else MutationResult.Uncertain(id,known))}
        catch(_:ProviderReadLimitExceeded){if(attempted==null)MeetingMutationResult.NeedsReview("The complete recurring meeting exceeds the read or storage budget")else common(MutationResult.Uncertain(id,known))}
        catch(_:Conflict){common(if(attempted==null)MutationResult.Conflict(null)else MutationResult.Uncertain(id,known))}
        catch(v:CalendarWriter.Invalid){if(attempted==null)common(MutationResult.ValidationFailed(v.fields))else common(MutationResult.Uncertain(id,known))}
        catch(v:CalendarMeetingCreateWriter.Invalid){if(attempted==null)MeetingMutationResult.Invalid(v.reason)else common(MutationResult.Uncertain(id,known))}
        catch(v:CalendarMeetingCreateWriter.Review){if(attempted==null)MeetingMutationResult.NeedsReview(v.reason)else common(MutationResult.Uncertain(id,known))}
        catch(v:CalendarMeetingCreateWriter.Denied){common(if(attempted==null)MutationResult.Denied(v.reason)else MutationResult.Uncertain(id,known))}
        catch(r:Review){if(attempted==null)MeetingMutationResult.NeedsReview(r.reason)else common(MutationResult.Uncertain(id,known))}
        catch(_:OperationApplicationException){if(attempted==null)common(MutationResult.Unavailable)else try{check(store.mark(attempted,"FAILED"));common(MutationResult.Unavailable)}catch(c:CancellationException){throw c}catch(_:Exception){common(MutationResult.Uncertain(id,known))}}
        catch(c:CancellationException){throw c}
        catch(_:Exception){common(if(attempted==null)MutationResult.Unavailable else MutationResult.Uncertain(id,known))}
    }}
    suspend fun recover(id:String):MeetingMutationResult=withContext(io+readBudgetContext(readBudgets.mutation)){mutex.withLock {
        try{if(store.operationHeader(id)?.phase=="ABANDONED")return@withLock common(MutationResult.Retired(id));provider.ensureRead();store.operation(id)?.let{recover(it,true)}?:common(MutationResult.Unavailable)}
        catch(_:SecurityException){common(MutationResult.PermissionRequired)}catch(c:CancellationException){throw c}catch(_:Exception){common(MutationResult.Uncertain(id,null))}
    }}
    private suspend fun ensureCurrentSource(p:Prepared) {
        provider.ensureRead();owner.verifyCalendarPolicy(p.source.calendar)
        if(p.draft.targetIdentity!=meetingIdentity(p.source.calendar)||p.draft.calendarId!=meetingIdentity(p.source.calendar).calendarId)throw Conflict()
        if(CalendarMeetingCreateWriter.legacySourceRaw(owner.currentCreationCalendar(p.draft.calendarId))!=p.source.baseRaw)throw Conflict()
    }
    private fun receipt(p:Prepared,signature:String)=JSONObject().put("feature",ReceiptFeature).put("version",2).put("kind",Kind).put("signature",signature)
        .put("command",p.command).put("source",JSONObject(p.source.encode(readBudgets.maxCanonicalBytes))).put("plan",p.plan.json()).put("uid",p.uid)
        .put("integrity",integrity(p,signature)).put("stage","prepared")
    private fun integrity(p:Prepared,signature:String)=meetingHash(ReceiptFeature+signature+p.source.encode(readBudgets.maxCanonicalBytes)+p.plan.json().toString()+p.uid+meetingRowJson(p.event).toString()+JSONArray(p.attendees.map(::meetingRowJson)).toString())
    private fun preflightReceipts(p:Prepared,metadata:JSONObject) {
        checkStoredJson(metadata.toString(),readBudgets.maxCanonicalBytes)
        val complete=JSONObject(metadata.toString()).put("stage","committed").put("event",Long.MAX_VALUE).put("ackComplete",true)
            .put("attendees",JSONArray(List(p.attendees.size){Long.MAX_VALUE})).put("reminders",JSONArray(List(p.draft.alertMinutes.size){Long.MAX_VALUE}))
            .put("firstRef",meetingRefJson(p.plan.ref(p.draft.calendarId,Long.MAX_VALUE)))
            .put("_sync_id","\u0000".repeat(readBudgets.mutation.maxCellUnits))
        checkStoredJson(complete.toString(),readBudgets.maxCanonicalBytes)
        checkStoredJson(JSONObject(metadata.toString()).put("stage","committed").put("event",Long.MAX_VALUE).put("ackComplete",false).toString(),readBudgets.maxCanonicalBytes)
    }
    private fun batch(p:Prepared):Batch {
        val cal=p.draft.calendarId.value
        val ops=arrayListOf(assertRow(ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI,cal),p.source.calendar));val event=ops.size
        ops+=ContentProviderOperation.newInsert(CalendarContract.Events.CONTENT_URI).withValues(values(p.event)).withYieldAllowed(false).build()
        val attendees=mutableListOf<Int>();val reminders=mutableListOf<Int>()
        for(row in p.attendees){attendees+=ops.size;ops+=ContentProviderOperation.newInsert(CalendarContract.Attendees.CONTENT_URI).withValues(values(row)).withValueBackReference("event_id",event).withYieldAllowed(false).build()}
        for(minutes in p.draft.alertMinutes){reminders+=ops.size;ops+=ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI).withValueBackReference("event_id",event).withValue("minutes",minutes).withValue("method",1).withYieldAllowed(false).build()}
        val expected=p.event+mapOf("deleted" to "0","isOrganizer" to "1","selfAttendeeStatus" to "1","hasAlarm" to if(p.draft.alertMinutes.isEmpty())"0"else "1",
            "rdate" to null,"exdate" to null,"exrule" to null,"original_id" to null,"original_sync_id" to null,"originalInstanceTime" to null,"originalAllDay" to null)
        ops+=ContentProviderOperation.newAssertQuery(CalendarContract.Events.CONTENT_URI).withSelection("_id = ?",arrayOf("0")).withSelectionBackReference(0,event).withValues(values(expected)).withValueBackReference("_id",event).withExpectedCount(1).withYieldAllowed(false).build()
        for((uri,count)in listOf(CalendarContract.Attendees.CONTENT_URI to p.attendees.size,CalendarContract.Reminders.CONTENT_URI to p.draft.alertMinutes.size))ops+=ContentProviderOperation.newAssertQuery(uri).withSelection("event_id = ?",arrayOf("0")).withSelectionBackReference(0,event).withValueBackReference("event_id",event).withExpectedCount(count).withYieldAllowed(false).build()
        p.attendees.forEachIndexed{i,row->ops+=ContentProviderOperation.newAssertQuery(CalendarContract.Attendees.CONTENT_URI).withSelection("_id = ? AND event_id = ?",arrayOf("0","0")).withSelectionBackReference(0,attendees[i]).withSelectionBackReference(1,event).withValues(values(row)).withValueBackReference("event_id",event).withExpectedCount(1).withYieldAllowed(false).build()}
        p.draft.alertMinutes.forEachIndexed{i,minutes->ops+=ContentProviderOperation.newAssertQuery(CalendarContract.Reminders.CONTENT_URI).withSelection("_id = ? AND event_id = ?",arrayOf("0","0")).withSelectionBackReference(0,reminders[i]).withSelectionBackReference(1,event).withValue("minutes",minutes).withValue("method",1).withValueBackReference("event_id",event).withExpectedCount(1).withYieldAllowed(false).build()}
        return Batch(ops,event,attendees,reminders)
    }
    private fun proof(operation:CalendarLocalStore.Operation):Pair<JSONObject,Prepared>?=try {
        require(operation.kind==Kind);val j=JSONObject(operation.resultRef?:error("Missing preparation"));require(j.getString("feature")==ReceiptFeature&&j.getInt("version")==2&&j.getString("kind")==Kind&&j.getString("signature")==operation.signature)
        val command=j.getJSONObject("command");val(token,draft,inputs)=RecurringMeetingCreationCodec.parse(command)
        require(RecurringMeetingCreationCodec.command(token,draft,inputs,readBudgets.maxCanonicalBytes).toString()==command.toString()&&fingerprint(token,draft,inputs,readBudgets.maxCanonicalBytes)==(Kind to operation.signature))
        val source=MeetingRecurringCreationSource.decode(j.getJSONObject("source").toString(),token,readBudgets.maxCanonicalBytes);owner.verifyCalendarPolicy(source.calendar)
        require(draft.targetIdentity==meetingIdentity(source.calendar)&&draft.calendarId==meetingIdentity(source.calendar).calendarId)
        val plan=FrozenMeetingCreationPlan.parse(j.getJSONObject("plan"),draft);val uid=j.getString("uid");require(validUid(uid))
        val attendees=owner.authoredAttendees(source.calendar,inputs).map(::row)
        val preliminary=Prepared(command,source,draft,inputs,plan,uid,emptyMap(),attendees);val prepared=preliminary.copy(event=structuralEvent(preliminary))
        require(integrity(prepared,operation.signature)==j.getString("integrity"))
        if(operation.phase in setOf("PREPARED","FAILED"))require(j.getString("stage")=="prepared"&&!j.has("event")&&!j.has("ackComplete"))
        else{require(operation.phase in setOf("COMMITTED","VERIFIED")&&j.getString("stage")=="committed"&&j.getLong("event")>0)
            if(j.getBoolean("ackComplete")){val a=j.getJSONArray("attendees").longs();val r=j.getJSONArray("reminders").longs();require(a.size==attendees.size&&r.size==draft.alertMinutes.size&&a.all{it>0}&&r.all{it>0}&&a.distinct().size==a.size&&r.distinct().size==r.size)}
            else require(!j.has("attendees")&&!j.has("reminders")&&!j.has("firstRef"))
            if(j.has("firstRef"))require(meetingParseRef(j.getJSONObject("firstRef"))==plan.ref(draft.calendarId,j.getLong("event")))}
        j to prepared
    }catch(c:CancellationException){throw c}catch(_:Exception){null}
    private suspend fun recover(operation:CalendarLocalStore.Operation,safeRetry:Boolean):MeetingMutationResult {
        if(operation.phase=="ABANDONED")return common(MutationResult.Retired(operation.id))
        val parsed=proof(operation)?:return common(MutationResult.Uncertain(operation.id,null));val j=parsed.first;val p=parsed.second
        if(operation.phase in setOf("PREPARED","FAILED"))return common(if(safeRetry)MutationResult.SafeToRetry(operation.id)else MutationResult.Unavailable)
        val id=j.getLong("event");fun uncertain()=common(MutationResult.Uncertain(operation.id,id))
        if(!j.getBoolean("ackComplete"))return uncertain()
        return try {
            if(!sameCalendar(p))return uncertain()
            val expected=p.event+mapOf("_id" to id.toString(),"deleted" to "0","isOrganizer" to "1","selfAttendeeStatus" to "1","hasAlarm" to if(p.draft.alertMinutes.isEmpty())"0"else "1")
            val fields=(expected.keys+listOf("_sync_id","rdate","exdate","exrule","original_id","original_sync_id","originalInstanceTime","originalAllDay")).toTypedArray()
            val actual=provider.rows(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI,id),fields,maxRows=1).singleOrNull()?:return uncertain()
            if(expected.any{(key,value)->actual[key]!=value}||listOf("rdate","exdate","exrule","original_id","original_sync_id","originalInstanceTime","originalAllDay").any{actual[it]!=null})return uncertain()
            val knownSync=j.nullable("_sync_id")?.takeIf(String::isNotBlank);val actualSync=actual["_sync_id"]?.takeIf(String::isNotBlank)
            if(knownSync!=null&&knownSync!=actualSync)return uncertain()
            val guestIds=j.getJSONArray("attendees").longs();val reminderIds=j.getJSONArray("reminders").longs()
            // Joined event predicates fence ID reuse after the event read, before either private
            // child cursor is materialized. UID is an exact known-parent guard, never a row search.
            val children="event_id = ? AND calendar_id = ? AND organizer = ? AND uid2445 = ?"
            val childArgs=arrayOf(id.toString(),p.draft.calendarId.value.toString(),meetingIdentity(p.source.calendar).owner,p.uid)
            val guests=provider.rows(CalendarContract.Attendees.CONTENT_URI,AttendeeFields,children,childArgs,201)
            if(guests.size!=p.attendees.size)return uncertain()
            p.attendees.forEachIndexed{i,values->val row=guests.singleOrNull{it["_id"]==guestIds[i].toString()}?:return uncertain();if(row["event_id"]!=id.toString()||values.any{(key,value)->row[key]!=value}||!row["attendeeIdentity"].isNullOrBlank()||!row["attendeeIdNamespace"].isNullOrBlank())return uncertain()}
            val reminders=provider.rows(CalendarContract.Reminders.CONTENT_URI,ReminderFields,children,childArgs,readBudgets.maxSeriesChildren)
            if(reminders.size!=p.draft.alertMinutes.size)return uncertain()
            p.draft.alertMinutes.forEachIndexed{i,minutes->val row=reminders.singleOrNull{it["_id"]==reminderIds[i].toString()}?:return uncertain();if(row["event_id"]!=id.toString()||row["minutes"]!=minutes.toString()||row["method"]!="1")return uncertain()}
            val ref=p.plan.ref(p.draft.calendarId,id)
            val uri=CalendarContract.Instances.CONTENT_URI.buildUpon().apply{ContentUris.appendId(this,ref.instanceStart.toEpochMilli());ContentUris.appendId(this,Math.addExact(ref.instanceStart.toEpochMilli(),1))}.build()
            val native=provider.rows(uri,InstanceFields,"event_id = ? AND calendar_id = ? AND begin = ?",arrayOf(id.toString(),ref.calendarId.value.toString(),ref.instanceStart.toEpochMilli().toString()),1).singleOrNull()?:return uncertain()
            if(native["event_id"]!=id.toString()||native["calendar_id"]!=ref.calendarId.value.toString()||native["begin"]!=ref.instanceStart.toEpochMilli().toString()||native["end"]!=ref.instanceEnd.toEpochMilli().toString()||native["allDay"]!=(if(p.plan.allDay)"1"else "0")||listOf("original_id","original_sync_id","originalInstanceTime").any{!native[it].isNullOrBlank()})return uncertain()
            if(!sameCalendar(p))return uncertain();provider.ensureRead()
            val verified=JSONObject(j.toString()).put("_sync_id",knownSync?:actualSync?:JSONObject.NULL).put("firstRef",meetingRefJson(ref))
            check(store.mark(operation,"VERIFIED",verified.toString()));reader.refresh();MeetingMutationResult.LocalSaved(ref,RecurrenceScope.ALL)
        }catch(s:SecurityException){throw s}catch(c:CancellationException){throw c}catch(_:Exception){uncertain()}
    }
    private suspend fun sameCalendar(p:Prepared):Boolean{val current=owner.currentCreationCalendar(p.draft.calendarId);return meetingIdentity(current)==meetingIdentity(p.source.calendar)&&(current["calendar_access_level"]?.toIntOrNull()?:-1)>=200}
    private fun permissions()=hasPermission(Manifest.permission.READ_CALENDAR)&&hasPermission(Manifest.permission.WRITE_CALENDAR)
    companion object {
        private const val Kind=RecurringMeetingCreationCodec.Kind
        private const val ReceiptFeature="MEETING_RECURRING_CREATE_RECEIPT_V2"
        private val AttendeeFields=CalendarMeetingWriter.attendeeFields
        private val ReminderFields=arrayOf("_id","event_id","minutes","method")
        private val InstanceFields=arrayOf("event_id","calendar_id","begin","end","allDay","original_id","original_sync_id","originalInstanceTime")
        fun handles(kind:String)=kind==Kind
        fun fingerprint(token:EditToken,draft:EventDraft,guests:List<MeetingGuestInput>,maxBytes:Int)=Kind to meetingHash(RecurringMeetingCreationCodec.Feature+RecurringMeetingCreationCodec.command(token,draft,guests,maxBytes).toString())
        private fun structuralEvent(p:Prepared)=linkedMapOf<String,String?>("dtstart" to p.plan.dtstart.toEpochMilli().toString(),"dtend" to null,"allDay" to if(p.plan.allDay)"1"else "0","eventTimezone" to p.plan.zone,"eventEndTimezone" to p.plan.zone,"duration" to p.plan.duration,"calendar_id" to p.draft.calendarId.value.toString(),"title" to p.draft.title,"eventLocation" to p.draft.location,"description" to p.draft.description,
            "organizer" to meetingIdentity(p.source.calendar).owner,"eventStatus" to "1","hasAttendeeData" to "1","guestsCanModify" to "0","guestsCanInviteOthers" to "0","guestsCanSeeGuests" to "1","rrule" to p.plan.rule,"uid2445" to p.uid).apply{p.draft.availability?.let{put("availability",it.toString())};p.draft.privacy?.let{put("accessLevel",it.toString())}}
        private fun row(v:ContentValues)=v.keySet().associateWith{v.get(it)?.toString()}
        private fun values(row:Map<String,String?>)=ContentValues().apply{row.forEach{(k,v)->if(v==null)putNull(k)else put(k,v)}}
        private fun assertRow(uri:Uri,row:Map<String,String?>)=ContentProviderOperation.newAssertQuery(uri).withValues(values(row)).withExpectedCount(1).withYieldAllowed(false).build()
        private fun parcelSize(ops:List<ContentProviderOperation>):Int{val p=Parcel.obtain();try{p.writeTypedList(ops);return p.dataSize()}finally{p.recycle()}}
        private fun checkParcel(ops:List<ContentProviderOperation>){if(parcelSize(ops)>256*1024)throw Review("The complete recurring meeting transaction exceeds the safe local size budget")}
        private fun validUid(uid:String)=uid.endsWith("@daylight.invalid")&&try{UUID.fromString(uid.removeSuffix("@daylight.invalid")).toString()+"@daylight.invalid"==uid}catch(_:Exception){false}
        private fun insertId(uri:Uri?,path:String):Long{require(uri!=null&&uri.scheme=="content"&&uri.authority==CalendarContract.AUTHORITY&&uri.encodedQuery==null&&uri.encodedFragment==null);val segments=uri.pathSegments;require(segments.size==2&&segments[0]==path);val id=segments[1].toLong();require(id>0&&uri.encodedPath=="/$path/$id");return id}
        private fun common(result:MutationResult)=MeetingMutationResult.Common(result)
        private fun JSONArray.longs()=List(length()){getLong(it)}
        private fun JSONObject.nullable(key:String)=if(isNull(key))null else getString(key)
    }
}
