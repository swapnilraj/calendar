package dev.swapnilraj.calendar.data.calendar

import android.Manifest
import android.content.*
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.Parcel
import android.provider.CalendarContract
import dev.swapnilraj.calendar.core.*
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** New owned one-off meetings only. A local commit never establishes invitation delivery. */
internal class CalendarMeetingCreateWriter(
    private val resolver: ContentResolver,
    private val reader: CalendarReader,
    private val store: CalendarLocalStore,
    private val hasPermission: (String) -> Boolean,
    private val io: CoroutineDispatcher,
    private val readBudgets: ProviderReadBudgets,
) {
    private val mutex = Mutex()
    private val authoredFields = CalendarWriter(resolver, reader, store, hasPermission, io, readBudgets)
    private class Review(val reason: String) : Exception()
    private class Invalid(val reason: String) : Exception()
    private class Denied(val reason: MutationDenial) : Exception()
    private class Conflict : Exception()
    private data class Guest(val email: String, val type: Int, val name: String?)
    private data class Source(val calendar: Map<String,String?>) {
        val identity get() = meetingIdentity(calendar)
        fun encode(): String = JSONObject().put("feature", SourceFeature).put("calendar", meetingRowJson(calendar)).put("identity", meetingIdentityJson(identity)).toString()
    }

    suspend fun loadForCreate(target: CalendarIdentity): MeetingReadResult<MeetingCreation> = withContext(io + readBudgetContext(readBudgets.mutation)) {
        try {
            ensureRead()
            val before = calendar(target.calendarId)
            if (before.identity != target) throw Conflict()
            val types = policy(before)
            val info = when(val result=reader.readCalendars()) {
                is CalendarReadState.Ready -> result.value.singleOrNull {it.identity==target} ?: throw Conflict()
                CalendarReadState.PermissionRequired -> throw SecurityException()
                CalendarReadState.LimitExceeded -> throw ProviderReadLimitExceeded()
                else -> return@withContext MeetingReadResult.Unavailable
            }
            val encoded=before.encode(); checkStoredJson(encoded,readBudgets.maxCanonicalBytes)
            if(calendar(target.calendarId).encode()!=encoded) throw Conflict()
            val token=EditToken(token(encoded));ensureRead();store.saveEdit(token.value,encoded)
            MeetingReadResult.Ready(MeetingCreation(info,token,types.mapNotNull(::guestType).toSet()))
        } catch(_:ProviderReadLimitExceeded){MeetingReadResult.LimitExceeded}
        catch(_:SecurityException){MeetingReadResult.PermissionRequired}
        catch(denied:Denied){MeetingReadResult.Denied(denied.reason)}
        catch(review:Review){MeetingReadResult.NeedsReview(review.reason)}
        catch(_:Conflict){MeetingReadResult.NeedsReview("The destination calendar changed; choose it again")}
        catch(cancelled:CancellationException){throw cancelled}
        catch(_:Exception){MeetingReadResult.Unavailable}
    }

    suspend fun create(id:String, editToken:EditToken, draft:EventDraft, inputs:List<MeetingGuestInput>):MeetingMutationResult = withContext(io+readBudgetContext(readBudgets.mutation)) {mutex.withLock {
        var dispatch:CalendarLocalStore.Operation?=null
        var knownId:Long?=null
        try {
            store.abandoned(id){fingerprint(editToken,draft,inputs,readBudgets.maxCanonicalBytes)}?.let{return@withLock common(it)}
            if(draft.recurrence!=null)return@withLock MeetingMutationResult.Unsupported("Recurring meeting creation requires a separately verified executor")
            if(draft.targetIdentity == null || draft.targetIdentity?.calendarId != draft.calendarId)
                return@withLock MeetingMutationResult.Invalid("Choose the original destination calendar again")
            if(!permissions())return@withLock common(MutationResult.PermissionRequired)
            if(id.isBlank())return@withLock MeetingMutationResult.Invalid("Operation ID is required")
            val command=commandJson(editToken,draft,inputs,readBudgets.maxCanonicalBytes)
            val frozenDraft=parseDraft(command.getJSONObject("draft"))
            val frozenInputs=parseGuests(command.getJSONArray("guests"))
            val signature=meetingHash(CommandFeature+command.toString())
            val existing=store.operation(id)
            if(existing!=null){
                if(existing.kind!=Kind||existing.signature!=signature)return@withLock common(MutationResult.Conflict(null))
                if(existing.phase !in setOf("PREPARED","FAILED"))return@withLock recover(existing,false)
                if(proof(existing)==null)return@withLock common(MutationResult.Uncertain(id,null))
            }
            val source=source(editToken)
            val target=frozenDraft.targetIdentity ?: throw Invalid("Choose a destination calendar again")
            if(target!=source.identity||frozenDraft.calendarId!=target.calendarId)throw Invalid("The draft destination does not match its original calendar")
            if(calendar(target.calendarId).encode()!=source.encode())throw Conflict()
            val types=policy(source)
            val guests=guests(frozenInputs,source.identity.owner!!,types)
            val event=eventValues(frozenDraft,source)
            val ops=arrayListOf(assertRow(ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI,target.calendarId.value),source.calendar))
            val eventIndex=ops.size
            ops+=ContentProviderOperation.newInsert(CalendarContract.Events.CONTENT_URI).withValues(event).withYieldAllowed(false).build()
            val attendeeIndices=mutableListOf<Int>()
            val authoredAttendees=listOf(self(source.identity.owner!!))+guests.map(::attendee)
            for(values in authoredAttendees){attendeeIndices+=ops.size;ops+=ContentProviderOperation.newInsert(CalendarContract.Attendees.CONTENT_URI)
                .withValues(values).withValueBackReference("event_id",eventIndex).withYieldAllowed(false).build()}
            val reminderIndices=mutableListOf<Int>()
            for(minutes in frozenDraft.alertMinutes){reminderIndices+=ops.size;ops+=ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI)
                .withValueBackReference("event_id",eventIndex).withValue("minutes",minutes).withValue("method",1).withYieldAllowed(false).build()}
            // AOSP16 77bed8f9 normal insert only creates implicit self when SELF_ATTENDEE_STATUS
            // is supplied. We omit it, insert one organizer, and prove the owner-exact status mirror.
            val expected=ContentValues(event).apply {put("selfAttendeeStatus",1);put("isOrganizer",1);put("deleted",0);put("hasAlarm",if(frozenDraft.alertMinutes.isEmpty())0 else 1)}
            ops+=ContentProviderOperation.newAssertQuery(CalendarContract.Events.CONTENT_URI).withSelection("_id = ?",arrayOf("0"))
                .withSelectionBackReference(0,eventIndex).withValues(expected).withExpectedCount(1).withYieldAllowed(false).build()
            for((uri,count) in listOf(CalendarContract.Attendees.CONTENT_URI to authoredAttendees.size,CalendarContract.Reminders.CONTENT_URI to frozenDraft.alertMinutes.size))
                ops+=ContentProviderOperation.newAssertQuery(uri).withSelection("event_id = ?",arrayOf("0")).withSelectionBackReference(0,eventIndex)
                    .withValueBackReference("event_id",eventIndex).withExpectedCount(count).withYieldAllowed(false).build()
            authoredAttendees.forEachIndexed {index,values-> ops+=ContentProviderOperation.newAssertQuery(CalendarContract.Attendees.CONTENT_URI)
                .withSelection("_id = ? AND event_id = ?",arrayOf("0","0")).withSelectionBackReference(0,attendeeIndices[index]).withSelectionBackReference(1,eventIndex)
                .withValues(values).withValueBackReference("event_id",eventIndex).withExpectedCount(1).withYieldAllowed(false).build()}
            frozenDraft.alertMinutes.forEachIndexed {index,minutes->ops+=ContentProviderOperation.newAssertQuery(CalendarContract.Reminders.CONTENT_URI)
                .withSelection("_id = ? AND event_id = ?",arrayOf("0","0")).withSelectionBackReference(0,reminderIndices[index]).withSelectionBackReference(1,eventIndex)
                .withValue("minutes",minutes).withValue("method",1).withExpectedCount(1).withYieldAllowed(false).build()}
            val parcel=Parcel.obtain();try{parcel.writeTypedList(ops);if(parcel.dataSize()>256*1024)throw Review("The complete meeting transaction exceeds the safe local size budget")}finally{parcel.recycle()}
            val metadata=JSONObject().put("feature",CommandFeature).put("kind",Kind).put("signature",signature).put("version",1)
                .put("stage","prepared").put("command",command).put("identity",meetingIdentityJson(target))
            checkStoredJson(metadata.toString(),readBudgets.maxCanonicalBytes)
            if(!permissions())return@withLock common(MutationResult.PermissionRequired)
            currentCoroutineContext().ensureActive();store.prepare(id,signature,Kind,metadata.toString())
            val persisted=store.operation(id)?:error("Journal unavailable")
            if(persisted.kind!=Kind||persisted.signature!=signature)return@withLock common(MutationResult.Conflict(null))
            if(persisted.phase=="ABANDONED")return@withLock common(MutationResult.Retired(id))
            if(proof(persisted)?.toString()!=metadata.toString())return@withLock common(MutationResult.Uncertain(id,null))
            if(!store.attempt(persisted))return@withLock recover(store.operation(id)!!,false)
            dispatch=persisted.copy(phase="ATTEMPTED")
            if(!permissions()){check(store.mark(dispatch,"FAILED"));return@withLock common(MutationResult.PermissionRequired)}
            currentCoroutineContext().ensureActive()
            val results=resolver.applyBatch(CalendarContract.AUTHORITY,ops)
            knownId=insertId(results,eventIndex,"events")
            val attendeeIds=attendeeIndices.map{insertId(results,it,"attendees")}
            val reminderIds=reminderIndices.map{insertId(results,it,"reminders")}
            require(attendeeIds.distinct().size==attendeeIds.size&&reminderIds.distinct().size==reminderIds.size)
            metadata.put("stage","committed").put("event",knownId).put("attendees",JSONArray(attendeeIds)).put("reminders",JSONArray(reminderIds))
            check(store.mark(dispatch,"COMMITTED",metadata.toString()))
            recover(store.operation(id)!!,false)
        }catch(_:ProviderReadLimitExceeded){if(dispatch==null)MeetingMutationResult.NeedsReview("The complete meeting exceeds the read or storage budget")else common(MutationResult.Uncertain(id,knownId))}
        catch(_:SecurityException){common(if(dispatch==null)MutationResult.PermissionRequired else MutationResult.Uncertain(id,knownId))}
        catch(_:Conflict){common(MutationResult.Conflict(null))}
        catch(denied:Denied){MeetingMutationResult.Common(MutationResult.Denied(denied.reason))}
        catch(invalid:Invalid){MeetingMutationResult.Invalid(invalid.reason)}
        catch(invalid:CalendarWriter.Invalid){common(MutationResult.ValidationFailed(invalid.fields))}
        catch(review:Review){MeetingMutationResult.NeedsReview(review.reason)}
        catch(_:OperationApplicationException){
            if(dispatch==null)common(MutationResult.Unavailable)else try{check(store.mark(dispatch,"FAILED"));common(MutationResult.Unavailable)}catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){common(MutationResult.Uncertain(id,knownId))}
        }catch(cancelled:CancellationException){throw cancelled}
        catch(_:Exception){common(if(dispatch==null)MutationResult.Unavailable else MutationResult.Uncertain(id,knownId))}
    }}

    suspend fun recover(id:String):MeetingMutationResult=withContext(io+readBudgetContext(readBudgets.mutation)){mutex.withLock{
        try{if(store.operationHeader(id)?.phase=="ABANDONED")return@withLock common(MutationResult.Retired(id))
            ensureRead();store.operation(id)?.let{recover(it,true)}?:common(MutationResult.Unavailable)
        }catch(_:SecurityException){common(MutationResult.PermissionRequired)}catch(cancelled:CancellationException){throw cancelled}
        catch(_:Exception){common(MutationResult.Uncertain(id,null))}
    }}
    private suspend fun recover(operation:CalendarLocalStore.Operation,retry:Boolean):MeetingMutationResult{
        if(operation.phase=="ABANDONED")return common(MutationResult.Retired(operation.id))
        val proof=proof(operation)?:return common(MutationResult.Uncertain(operation.id,null))
        if(operation.phase in setOf("PREPARED","FAILED"))return common(if(retry)MutationResult.SafeToRetry(operation.id)else MutationResult.Unavailable)
        val id=proof.getLong("event");fun uncertain()=common(MutationResult.Uncertain(operation.id,id))
        val command=proof.getJSONObject("command");val draft=parseDraft(command.getJSONObject("draft"));val original=source(EditToken(command.getString("token")))
        val target=meetingParseIdentity(proof.getJSONObject("identity"))
        if(target!=original.identity||draft.targetIdentity!=target)return uncertain()
        val current=calendar(target.calendarId)
        if(current.identity!=target||(current.calendar["calendar_access_level"]?.toIntOrNull()?:-1)<CalendarContract.Calendars.CAL_ACCESS_READ)return uncertain()
        val guests=guests(parseGuests(command.getJSONArray("guests")),target.owner!!,policy(original))
        val authoredValues=eventValues(draft,original)
        val expected=authoredValues.keySet().associateWith{authoredValues.get(it)?.toString()}+
            mapOf("_id" to id.toString(),"deleted" to "0","selfAttendeeStatus" to "1","isOrganizer" to "1","hasAlarm" to if(draft.alertMinutes.isEmpty())"0" else "1")
        val fields=(expected.keys+listOf("rrule","rdate","exrule","exdate","original_id","original_sync_id","originalInstanceTime","_sync_id","uid2445")).toTypedArray()
        val actual=try{rows(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI,id),fields,maxRows=1).singleOrNull()}
            catch(_:IllegalArgumentException){rows(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI,id),fields.filter{it!="uid2445"}.toTypedArray(),maxRows=1).singleOrNull()}?:return uncertain()
        if(expected.any{(key,value)->actual[key]!=value}||listOf("rrule","rdate","exrule","exdate","original_id","original_sync_id","originalInstanceTime").any{!actual[it].isNullOrBlank()})return uncertain()
        for(key in listOf("_sync_id","uid2445")) {
            val known=if(proof.has(key)&&!proof.isNull(key))proof.getString(key).takeIf(String::isNotBlank)else null
            if(known!=null&&known!=actual[key])return uncertain()
        }
        val authored=listOf(self(target.owner!!))+guests.map(::attendee)
        val attendeeIds=proof.getJSONArray("attendees").longs();val reminderIds=proof.getJSONArray("reminders").longs()
        if(attendeeIds.size!=authored.size||reminderIds.size!=draft.alertMinutes.size)return uncertain()
        val attendeeRows=rows(CalendarContract.Attendees.CONTENT_URI,AttendeeFields,"event_id = ?",arrayOf(id.toString()),MaxGuests+1)
        if(attendeeRows.size!=authored.size)return uncertain()
        authored.forEachIndexed{index,values-> val row=attendeeRows.singleOrNull{it["_id"]==attendeeIds[index].toString()}?:return uncertain()
            if(row["event_id"]!=id.toString()||values.keySet().any{row[it]!=values.get(it)?.toString()}||!row["attendeeIdentity"].isNullOrBlank()||!row["attendeeIdNamespace"].isNullOrBlank())return uncertain()}
        val reminders=rows(CalendarContract.Reminders.CONTENT_URI,arrayOf("_id","event_id","minutes","method"),"event_id = ?",arrayOf(id.toString()),readBudgets.maxSeriesChildren)
        if(reminders.size!=reminderIds.size)return uncertain()
        reminderIds.forEachIndexed{index,rowId->val row=reminders.singleOrNull{it["_id"]==rowId.toString()}?:return uncertain()
            if(row["event_id"]!=id.toString()||row["method"]!="1"||row["minutes"]!=draft.alertMinutes[index].toString())return uncertain()}
        val ref=reference(draft,id)
        val uri=CalendarContract.Instances.CONTENT_URI.buildUpon().apply{ContentUris.appendId(this,ref.instanceStart.toEpochMilli());ContentUris.appendId(this,Math.addExact(ref.instanceStart.toEpochMilli(),1))}.build()
        val instance=rows(uri,arrayOf("event_id","calendar_id","begin","end","allDay","original_id","original_sync_id","originalInstanceTime"),
            "event_id = ? AND calendar_id = ? AND begin = ?",arrayOf(id.toString(),target.calendarId.value.toString(),ref.instanceStart.toEpochMilli().toString()),1).singleOrNull()?:return uncertain()
        if(instance["event_id"]!=id.toString()||instance["calendar_id"]!=target.calendarId.value.toString()||instance["begin"]!=ref.instanceStart.toEpochMilli().toString()||
            instance["end"]!=ref.instanceEnd.toEpochMilli().toString()||instance["allDay"]!=if(draft.time is EventTime.AllDay)"1" else "0")return uncertain()
        if(listOf("original_id","original_sync_id","originalInstanceTime").any{!instance[it].isNullOrBlank()})return uncertain()
        val finalCalendar=calendar(target.calendarId)
        if(finalCalendar.identity!=target||(finalCalendar.calendar["calendar_access_level"]?.toIntOrNull()?:-1)<CalendarContract.Calendars.CAL_ACCESS_READ)return uncertain()
        ensureRead();val receipt=JSONObject(proof.toString())
        for(key in listOf("_sync_id","uid2445")) {
            val known=if(proof.has(key)&&!proof.isNull(key))proof.getString(key).takeIf(String::isNotBlank)else null
            receipt.put(key,known?:actual[key]?.takeIf(String::isNotBlank)?:JSONObject.NULL)
        }
        check(store.mark(operation,"VERIFIED",receipt.toString()));reader.refresh()
        return MeetingMutationResult.LocalSaved(ref,RecurrenceScope.THIS)
    }

    private fun proof(operation:CalendarLocalStore.Operation):JSONObject?=try{
        require(operation.kind==Kind);val proof=JSONObject(operation.resultRef?:error("Missing proof"))
        require(proof.getInt("version")==1&&proof.getString("feature")==CommandFeature&&proof.getString("kind")==Kind)
        val command=proof.getJSONObject("command");require(meetingHash(CommandFeature+command.toString())==operation.signature&&proof.getString("signature")==operation.signature)
        val draft=parseDraft(command.getJSONObject("draft"));require(draft.recurrence==null)
        val original=source(EditToken(command.getString("token")));require(original.identity==draft.targetIdentity&&original.identity==meetingParseIdentity(proof.getJSONObject("identity")))
        require(commandJson(EditToken(command.getString("token")),draft,parseGuests(command.getJSONArray("guests")),readBudgets.maxCanonicalBytes).toString()==command.toString())
        if(operation.phase in setOf("PREPARED","FAILED")){require(proof.getString("stage")=="prepared"&&!proof.has("event")&&!proof.has("attendees")&&!proof.has("reminders"))}
        else {require(operation.phase in setOf("COMMITTED","VERIFIED")&&proof.getString("stage")=="committed"&&proof.getLong("event")>=0)
            for(key in listOf("attendees","reminders")){val ids=proof.getJSONArray(key).longs();require(ids.all{it>=0}&&ids.distinct().size==ids.size)}}
        proof
    }catch(_:Exception){null}
    private fun source(token:EditToken):Source{
        val raw=store.edit(token.value)?:throw Conflict();if(token(raw)!=token.value)throw Conflict()
        val json=JSONObject(raw);require(json.getString("feature")==SourceFeature)
        val source=Source(meetingRow(json.getJSONObject("calendar")));require(source.identity==meetingParseIdentity(json.getJSONObject("identity")))
        require(source.encode()==raw);return source
    }
    private suspend fun calendar(id:CalendarId):Source{
        val uri=ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI,id.value)
        val row=try{rows(uri,CalendarFields,maxRows=1).singleOrNull()}catch(_:IllegalArgumentException){rows(uri,CalendarFields.filter{it!="_sync_id"}.toTypedArray(),maxRows=1).singleOrNull()}?:throw Conflict()
        if(row["_id"]!=id.value.toString())throw Conflict();return Source(row)
    }
    private fun policy(source:Source):Set<Int>{
        val identity=source.identity
        if(identity.account.name.isBlank()||identity.account.type.isBlank()||identity.owner!=identity.account.name||!validEmail(identity.owner.orEmpty()))throw Denied(MutationDenial.OWNERSHIP_UNKNOWN)
        if(source.calendar["calendar_access_level"]?.toIntOrNull() !in setOf(CalendarContract.Calendars.CAL_ACCESS_OWNER,CalendarContract.Calendars.CAL_ACCESS_ROOT))throw Denied(MutationDenial.CALENDAR_READ_ONLY)
        val raw=source.calendar["allowedAttendeeTypes"]?.takeIf{it.isNotBlank()}?:throw Review("The calendar's attendee types are unavailable")
        val types=raw.split(',').map{it.trim().toIntOrNull()?:throw Review("The calendar's attendee types are unavailable")}.toSet()
        if(types.any{it !in 0..3}||0 !in types)throw Review("The calendar does not prove support for a structural organizer attendee")
        return types
    }
    private fun guests(inputs:List<MeetingGuestInput>,owner:String,types:Set<Int>):List<Guest>{
        if(inputs.size>MaxGuests)throw Review("This meeting exceeds the supported guest count")
        val rows=linkedMapOf<String,Guest>()
        for(input in inputs){
            val email=input.email.trim();val name=input.name?.trim()?.takeIf{it.isNotEmpty()}
            if(!validEmail(email)||input.email.length>256)throw Invalid("Enter a supported email address for each guest")
            if(name!=null&&(name.length>256||name.any{it.isISOControl()}))throw Invalid("Guest names must be plain text of at most 256 characters")
            if(email.equals(owner,true))throw Invalid("The organizer is included automatically; remove the duplicate self guest")
            val type=when(input.type){MeetingGuestType.UNSPECIFIED->0;MeetingGuestType.REQUIRED->1;MeetingGuestType.OPTIONAL->2}
            if(type !in types)throw Invalid("The calendar does not support the requested guest type")
            val guest=Guest(email,type,name);val key=email.lowercase(Locale.ROOT);val old=rows[key]
            if(old!=null&&(old.type!=type||old.name!=name))throw Invalid("Duplicate guest entries have conflicting names or types")
            if(old==null)rows[key]=guest
        };return rows.values.toList()
    }
    private fun eventValues(draft:EventDraft,source:Source)=authoredFields.meetingOneOffValues(draft,source.calendar).apply{
        put("organizer",source.identity.owner);put("eventStatus",1);put("hasAttendeeData",1)
        put("guestsCanModify",0);put("guestsCanInviteOthers",0);put("guestsCanSeeGuests",1)
    }
    private fun permissions()=hasPermission(Manifest.permission.READ_CALENDAR)&&hasPermission(Manifest.permission.WRITE_CALENDAR)
    private fun ensureRead(){if(!hasPermission(Manifest.permission.READ_CALENDAR))throw SecurityException()}
    private suspend fun rows(uri:Uri,fields:Array<String>,selection:String?=null,args:Array<String>?=null,maxRows:Int):List<Map<String,String?>>{
        ensureRead();val cursor=suspendCancellableCoroutine<Cursor>{continuation->val signal=CancellationSignal();continuation.invokeOnCancellation{signal.cancel()}
            try{val result=resolver.query(uri,fields,selection,args,null,signal)?:error("Provider unavailable")
                try{ensureRead()}catch(failure:Exception){result.close();throw failure}
                continuation.resume(result,onCancellation={_,resource,_->resource.close()})
            }catch(failure:Exception){continuation.resumeWithException(failure)}}
        return cursor.bounded(maxRows).use{buildList{while(it.moveToNext()){currentCoroutineContext().ensureActive();add(fields.associateWith{field->it.getColumnIndexOrThrow(field).let{column->if(it.isNull(column))null else it.getString(column)}})}}}
    }
    companion object{
        private const val SourceFeature="MEETING_CREATE_SOURCE_V1"
        private const val CommandFeature="MEETING_CREATE_COMMAND_V1"
        private const val Kind="MEETING_V1_CREATE"
        private const val MaxGuests=200
        fun handles(kind:String)=kind==Kind
        fun fingerprint(token:EditToken,draft:EventDraft,guests:List<MeetingGuestInput>,maxBytes:Int)=Kind to meetingHash(CommandFeature+commandJson(token,draft,guests,maxBytes).toString())
        private fun commandJson(token:EditToken,draft:EventDraft,guests:List<MeetingGuestInput>,maxBytes:Int):JSONObject{
            if(guests.size>MaxGuests||token.value.length>64*1024)throw ProviderReadLimitExceeded()
            checkCanonical(guests.map{listOf(it.email,it.name,it.type.name)},maxBytes)
            return JSONObject().put("token",token.value).put("draft",CalendarWriter.frozenDraftJson(draft,maxBytes))
                .put("guests",JSONArray(guests.map{JSONObject().put("email",it.email).put("type",it.type.name).put("name",it.name?:JSONObject.NULL)}))
        }
        private fun parseGuests(array:JSONArray):List<MeetingGuestInput>{require(array.length()<=MaxGuests);return List(array.length()){array.getJSONObject(it).let{guest->MeetingGuestInput(guest.getString("email"),MeetingGuestType.valueOf(guest.getString("type")),if(guest.isNull("name"))null else guest.getString("name"))}}}
        private fun parseDraft(json:JSONObject):EventDraft{
            require(!json.has("recurrence"));val start=Instant.parse(json.getString("start"));val end=Instant.parse(json.getString("end"))
            val time=if(json.getBoolean("allDay"))EventTime.AllDay.fromProvider(start,end)else EventTime.Timed(start,end)
            val alerts=json.getJSONArray("alerts")
            return EventDraft(CalendarId(json.getLong("calendar")),time,if(json.isNull("zone"))null else java.time.ZoneId.of(json.getString("zone")),json.getString("title"),json.getString("location"),json.getString("description"),
                List(alerts.length()){alerts.getInt(it)},if(json.has("availability"))json.getInt("availability")else null,if(json.has("privacy"))json.getInt("privacy")else null,CalendarWriter.frozenDraftIdentity(json.getJSONObject("targetIdentity")))
        }
        private fun token(raw:String)="meeting-create-v1:"+meetingHash(SourceFeature+raw)
        private fun JSONArray.longs()=List(length()){getLong(it)}
        private fun common(result:MutationResult)=MeetingMutationResult.Common(result)
        private fun validEmail(email:String):Boolean=email.length in 3..254&&email.all{it.code in 33..126}&&
            Regex("[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+").matches(email)
        private fun guestType(value:Int)=when(value){0->MeetingGuestType.UNSPECIFIED;1->MeetingGuestType.REQUIRED;2->MeetingGuestType.OPTIONAL;else->null}
        private fun self(owner:String)=ContentValues().apply{put("attendeeEmail",owner);put("attendeeRelationship",2);put("attendeeType",0);put("attendeeStatus",1)}
        private fun attendee(guest:Guest)=ContentValues().apply{put("attendeeEmail",guest.email);guest.name?.let{put("attendeeName",it)};put("attendeeRelationship",1);put("attendeeType",guest.type);put("attendeeStatus",3)}
        private fun reference(draft:EventDraft,id:Long):EventRef=when(val time=draft.time){is EventTime.Timed->EventRef(draft.calendarId,id,time.start,time.end)
            is EventTime.AllDay->EventRef(draft.calendarId,id,time.dates.start.atStartOfDay(ZoneOffset.UTC).toInstant(),time.dates.endExclusive.atStartOfDay(ZoneOffset.UTC).toInstant())}
        private fun assertRow(uri:Uri,row:Map<String,String?>)=ContentProviderOperation.newAssertQuery(uri).withValues(ContentValues().apply{row.forEach{(key,value)->if(value==null)putNull(key)else put(key,value)}}).withExpectedCount(1).withYieldAllowed(false).build()
        private fun insertId(results:Array<ContentProviderResult>,index:Int,path:String):Long{val uri=results.getOrNull(index)?.uri?:error("Missing insert acknowledgement");require(uri.authority==CalendarContract.AUTHORITY&&uri.pathSegments.size==2&&uri.pathSegments[0]==path);return ContentUris.parseId(uri).also{require(it>=0)}}
        private val CalendarFields=arrayOf("_id","account_name","account_type","ownerAccount","_sync_id","calendar_access_level","allowedAttendeeTypes","allowedReminders","maxReminders","allowedAvailability","canModifyTimeZone","calendar_timezone")
        private val AttendeeFields=arrayOf("_id","event_id","attendeeName","attendeeEmail","attendeeStatus","attendeeRelationship","attendeeType","attendeeIdentity","attendeeIdNamespace")
    }
}
