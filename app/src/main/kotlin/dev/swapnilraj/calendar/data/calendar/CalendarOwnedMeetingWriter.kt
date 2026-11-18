package dev.swapnilraj.calendar.data.calendar

import android.Manifest
import android.content.*
import android.net.Uri
import android.os.Parcel
import android.provider.CalendarContract
import dev.swapnilraj.calendar.core.*
import java.time.*
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Owned ordinary meetings only. Sparse deltas preserve unrepresented physical columns; a local
 * ACK proves neither invitation/update delivery nor attendee acceptance. No provider cloning. */
internal class CalendarOwnedMeetingWriter(private val resolver:ContentResolver,private val reader:CalendarReader,
    private val store:CalendarLocalStore,private val hasPermission:(String)->Boolean,private val io:CoroutineDispatcher,
    private val readBudgets:ProviderReadBudgets) {
    private val provider=OwnedMeetingProvider(resolver,hasPermission,readBudgets)
    private val author=CalendarWriter(resolver,reader,store,hasPermission,io,readBudgets)
    private val mutex=Mutex()
    private data class Model(val values:Map<String,String?>,val removed:Set<Long>,val changed:Map<Long,Int>,val added:List<Map<String,String?>>,val alerts:List<Int>?,val ref:EventRef,val cancelled:Boolean)
    private data class Batch(val ops:ArrayList<ContentProviderOperation>,val guestInserts:List<Int>,val alertInserts:List<Int>,val counts:Map<Int,Int>)

    /** Null means an explicitly invited row: leave its original RSVP discovery/token unchanged. */
    suspend fun discover(selected:EventRef):MeetingReadResult<EditableMeeting>?=withContext(io+readBudgetContext(readBudgets.mutation)) {
        try {
            provider.ensureRead();val calendar=provider.calendar(selected.calendarId)
            val identity=meetingIdentity(calendar)
            if((calendar["calendar_access_level"]?.toIntOrNull()?:-1)<300)throw OwnedMeetingDenied(MutationDenial.CALENDAR_READ_ONLY)
            if(identity.owner.isNullOrBlank()||identity.owner!=identity.account.name||identity.account.name.isBlank()||identity.account.type.isBlank())throw OwnedMeetingDenied(MutationDenial.OWNERSHIP_UNKNOWN)
            if(calendar["calendar_access_level"]?.toIntOrNull() !in setOf(700,800))return@withContext null
            val role=provider.rows(eventUri(selected.eventId),arrayOf("_id","calendar_id","isOrganizer"),maxRows=1).singleOrNull()?:throw OwnedMeetingConflict()
            if(role.meetingLong("_id")!=selected.eventId||role.meetingLong("calendar_id")!=selected.calendarId.value)throw OwnedMeetingConflict()
            if(role["isOrganizer"]=="0")return@withContext null
            val before=provider.capture(selected);val raw=before.canonical(readBudgets.maxCanonicalBytes)
            val details=when(val r=reader.readDetails(selected)){is CalendarReadState.Ready->r.value;CalendarReadState.PermissionRequired->throw SecurityException();CalendarReadState.LimitExceeded->throw ProviderReadLimitExceeded();else->throw OwnedMeetingConflict()}
            if(details.occurrence.ref!=selected||provider.capture(selected,before.identity).canonical(readBudgets.maxCanonicalBytes)!=raw)throw OwnedMeetingConflict()
            val token=EditToken(OwnedMeetingSource.token(raw));val types=before.guestTypes()
            val guestRows=before.attendees.filter{it["_id"]!=before.self["_id"]}.map { row->MeetingGuestRow(before.guestRef(token,row),EventAttendee(row["attendeeEmail"].orEmpty(),row["attendeeName"],row["attendeeStatus"]?.toIntOrNull()?:Int.MIN_VALUE,row["attendeeRelationship"]?.toIntOrNull()?:Int.MIN_VALUE,row["attendeeType"]?.toIntOrNull()?:Int.MIN_VALUE),before.guestActions(row)) }
            val fields=mutableSetOf(EditableField.TITLE,EditableField.LOCATION,EditableField.DESCRIPTION,EditableField.TIME,EditableField.ALERTS,EditableField.PRIVACY)
            if(before.calendar["canModifyTimeZone"]=="1"&&before.event["allDay"]=="0")fields+=EditableField.ZONE
            val availability=before.calendar["allowedAvailability"]?.split(',')?.map{it.trim().toIntOrNull()}
            if(!availability.isNullOrEmpty()&&availability.all{it in 0..2})fields+=EditableField.AVAILABILITY
            provider.ensureRead();store.saveEdit(token.value,raw)
            MeetingReadResult.Ready(EditableMeeting(details.copy(canEdit=AccessDecision.ALLOWED,canRespond=AccessDecision.DENIED),token,mapOf(RecurrenceScope.THIS to MeetingScopeAccess(
                event=SeriesScopeCapabilities(AccessDecision.ALLOWED,fields,AccessDecision.ALLOWED),respond=AccessDecision.DENIED,
                addGuests=if(types.isNotEmpty()&&before.unambiguousGuests()&&before.attendees.size<201)AccessDecision.ALLOWED else AccessDecision.DENIED,
                allowedGuestTypes=types,guests=guestRows))))
        }catch(_:SecurityException){MeetingReadResult.PermissionRequired}
        catch(_:ProviderReadLimitExceeded){MeetingReadResult.LimitExceeded}
        catch(d:OwnedMeetingDenied){MeetingReadResult.Denied(d.reason)}
        catch(r:OwnedMeetingReview){MeetingReadResult.NeedsReview(r.reason)}
        catch(_:OwnedMeetingConflict){MeetingReadResult.NeedsReview("The meeting changed; reopen its details")}
        catch(c:CancellationException){throw c}
        catch(_:Exception){MeetingReadResult.Unavailable}
    }
    suspend fun mutate(id:String,selected:EventRef,token:EditToken,command:MeetingCommand):MeetingMutationResult=withContext(io+readBudgetContext(readBudgets.mutation)){mutex.withLock {
        var attempted:CalendarLocalStore.Operation?=null
        try {
            store.abandoned(id){requireNotNull(fingerprint(selected,token,command,readBudgets.maxCanonicalBytes))}?.let{return@withLock common(it)}
            if(!OwnedMeetingCodec.supported(selected,command)||!token.value.startsWith("meeting-owned-edit-v1:"))return@withLock MeetingMutationResult.Unsupported("Only THIS editing and cancellation on an owned ordinary meeting are implemented")
            if(!permissions())return@withLock common(MutationResult.PermissionRequired)
            if(id.isBlank())return@withLock MeetingMutationResult.Invalid("Operation ID is required")
            val frozen=OwnedMeetingCodec.encode(selected,token,command,readBudgets.maxCanonicalBytes);val(kind,signature)=requireNotNull(fingerprint(selected,token,command,readBudgets.maxCanonicalBytes))
            val existing=store.operation(id)
            if(existing!=null){if(existing.kind!=kind||existing.signature!=signature)return@withLock common(MutationResult.Conflict(null));if(existing.phase !in setOf("PREPARED","FAILED"))return@withLock recover(existing,false);if(proof(existing)==null)return@withLock common(MutationResult.Uncertain(id,selected.eventId))}
            val raw=store.edit(token.value)?:throw OwnedMeetingConflict();if(OwnedMeetingSource.token(raw)!=token.value)throw OwnedMeetingConflict()
            val old=OwnedMeetingSource.decode(raw,readBudgets.maxCanonicalBytes);if(old.selected!=selected)throw OwnedMeetingConflict()
            val source=provider.capture(selected,old.identity);if(source.canonical(readBudgets.maxCanonicalBytes)!=raw)throw OwnedMeetingConflict()
            // Verify selected occurrence/bounds before authoring, without relying on caller ref alone.
            when(val result=reader.readDetails(selected)){is CalendarReadState.Ready->if(result.value.occurrence.ref!=selected)throw OwnedMeetingConflict();CalendarReadState.PermissionRequired->throw SecurityException();CalendarReadState.LimitExceeded->throw ProviderReadLimitExceeded();else->throw OwnedMeetingConflict()}
            val model=model(source,token,command);val batch=batch(source,model);checkParcel(batch.ops)
            val metadata=JSONObject().put("feature",ReceiptFeature).put("version",1).put("kind",kind).put("signature",signature).put("command",frozen).put("source",JSONObject(raw)).put("stage","prepared")
            checkStoredJson(metadata.toString(),readBudgets.maxCanonicalBytes)
            if(!permissions())return@withLock common(MutationResult.PermissionRequired)
            currentCoroutineContext().ensureActive();store.prepare(id,signature,kind,metadata.toString())
            val persisted=store.operation(id)?:error("Journal unavailable")
            if(persisted.kind!=kind||persisted.signature!=signature)return@withLock common(MutationResult.Conflict(null))
            if(persisted.phase=="ABANDONED")return@withLock common(MutationResult.Retired(id))
            if(persisted.phase !in setOf("PREPARED","FAILED"))return@withLock recover(persisted,false)
            if(proof(persisted)?.first?.toString()!=metadata.toString())return@withLock common(MutationResult.Uncertain(id,selected.eventId))
            if(!permissions())return@withLock common(MutationResult.PermissionRequired)
            if(!store.attempt(persisted))return@withLock recover(store.operation(id)!!,false)
            attempted=persisted.copy(phase="ATTEMPTED")
            if(!permissions()){check(store.mark(attempted,"FAILED"));return@withLock common(MutationResult.PermissionRequired)}
            currentCoroutineContext().ensureActive();val results=resolver.applyBatch(CalendarContract.AUTHORITY,batch.ops)
            require(results.size==batch.ops.size);batch.counts.forEach{(index,count)->require(results[index].count==count)}
            val guests=batch.guestInserts.map{insertId(results[it].uri,"attendees",source.attendees.mapNotNull{r->r.meetingLong("_id")}.toSet())}
            val alerts=batch.alertInserts.map{insertId(results[it].uri,"reminders",source.reminders.mapNotNull{r->r.meetingLong("_id")}.toSet())}
            require(guests.distinct().size==guests.size&&alerts.distinct().size==alerts.size)
            metadata.put("stage","committed").put("guests",JSONArray(guests)).put("alerts",JSONArray(alerts))
            check(store.mark(attempted,"COMMITTED",metadata.toString()))
            recover(store.operation(id)!!,false)
        }catch(_:ProviderReadLimitExceeded){if(attempted==null)MeetingMutationResult.NeedsReview("The complete meeting exceeds the supported read or storage budget")else common(MutationResult.Uncertain(id,selected.eventId))}
        catch(_:SecurityException){common(if(attempted==null)MutationResult.PermissionRequired else MutationResult.Uncertain(id,selected.eventId))}
        catch(d:OwnedMeetingDenied){if(attempted==null)common(MutationResult.Denied(d.reason))else common(MutationResult.Uncertain(id,selected.eventId))}
        catch(r:OwnedMeetingReview){if(attempted==null)MeetingMutationResult.NeedsReview(r.reason)else common(MutationResult.Uncertain(id,selected.eventId))}
        catch(r:OwnedMeetingInvalid){if(attempted==null)MeetingMutationResult.Invalid(r.reason)else common(MutationResult.Uncertain(id,selected.eventId))}
        catch(v:CalendarWriter.Invalid){if(attempted==null)common(MutationResult.ValidationFailed(v.fields))else common(MutationResult.Uncertain(id,selected.eventId))}
        catch(_:OwnedMeetingConflict){common(if(attempted==null)MutationResult.Conflict(null)else MutationResult.Uncertain(id,selected.eventId))}
        catch(_:OperationApplicationException){if(attempted==null)common(MutationResult.Unavailable)else try{check(store.mark(attempted,"FAILED"));common(MutationResult.Unavailable)}catch(c:CancellationException){throw c}catch(_:Exception){common(MutationResult.Uncertain(id,selected.eventId))}}
        catch(c:CancellationException){throw c}
        catch(_:Exception){common(if(attempted==null)MutationResult.Unavailable else MutationResult.Uncertain(id,selected.eventId))}
    }}
    suspend fun recover(id:String):MeetingMutationResult=withContext(io+readBudgetContext(readBudgets.mutation)){mutex.withLock {
        try{if(store.operationHeader(id)?.phase=="ABANDONED")return@withLock common(MutationResult.Retired(id));provider.ensureRead();store.operation(id)?.let{recover(it,true)}?:common(MutationResult.Unavailable)}
        catch(_:SecurityException){common(MutationResult.PermissionRequired)}catch(c:CancellationException){throw c}catch(_:Exception){common(MutationResult.Uncertain(id,null))}
    }}
    private fun model(source:OwnedMeetingSource,token:EditToken,command:MeetingCommand):Model {
        source.validate()
        if(command is MeetingCommand.CancelOwned)return Model(mapOf("eventStatus" to "2"),emptySet(),emptyMap(),emptyList(),null,source.selected,true)
        command as MeetingCommand.EditOwned
        if(command.guests.size>400)throw OwnedMeetingInvalid("Too many guest changes")
        val values=author.meetingOneOffPatchValues(source.selected.calendarId,source.time,source.zone,source.event,source.calendar,source.reminders,command.patch)
        val removed=mutableSetOf<Long>();val changed=mutableMapOf<Long,Int>();val added=mutableListOf<Map<String,String?>>()
        val refs=source.attendees.filter{it["_id"]!=source.self["_id"]}.associateBy{source.guestRef(token,it)}
        if(command.guests.isNotEmpty()&&(source.guestTypes().isEmpty()||!source.unambiguousGuests()))throw OwnedMeetingReview("Guest changes require complete unambiguous attendee capabilities")
        val emails=source.attendees.map{it["attendeeEmail"].orEmpty().trim().lowercase(Locale.ROOT)}.toMutableSet()
        val touched=mutableSetOf<Long>()
        for(change in command.guests)when(change){
            is MeetingGuestChange.Add->{val input=change.guest;val email=input.email.trim();val name=input.name?.trim()?.takeIf(String::isNotEmpty)
                if(!ownedMeetingEmail(email)||input.email.length>256||name?.let{it.length>256||it.any{c->c.isISOControl()}}==true)throw OwnedMeetingInvalid("Enter supported guest email addresses and plain names")
                if(!emails.add(email.lowercase(Locale.ROOT)))throw OwnedMeetingInvalid("A guest or organizer address is repeated")
                if(input.type !in source.guestTypes())throw OwnedMeetingInvalid("The calendar does not support this guest type")
                added+=mapOf("attendeeEmail" to email,"attendeeName" to name,"attendeeRelationship" to "1","attendeeType" to ownedMeetingType(input.type).toString(),"attendeeStatus" to "3","attendeeIdentity" to null,"attendeeIdNamespace" to null)
            }
            is MeetingGuestChange.ChangeType->{val row=refs[change.ref]?:throw OwnedMeetingInvalid("The guest selector does not belong to this source")
                val id=requireNotNull(row.meetingLong("_id"));if(!touched.add(id)||MeetingGuestAction.CHANGE_TYPE !in source.guestActions(row)||change.type !in source.guestTypes())throw OwnedMeetingInvalid("This guest type change is not supported")
                changed[id]=ownedMeetingType(change.type)
            }
            is MeetingGuestChange.Remove->{val row=refs[change.ref]?:throw OwnedMeetingInvalid("The guest selector does not belong to this source")
                val id=requireNotNull(row.meetingLong("_id"));if(!touched.add(id)||MeetingGuestAction.REMOVE !in source.guestActions(row))throw OwnedMeetingInvalid("This guest removal is not supported")
                removed+=id
            }
        }
        if(source.attendees.size-removed.size+added.size>201)throw OwnedMeetingReview("This meeting exceeds the supported guest count")
        if(values.size()==0&&command.guests.isEmpty()&&command.patch.alertMinutes==FieldChange.Keep)throw OwnedMeetingInvalid("There are no changes to save")
        val map=values.keySet().associateWith{values.get(it)?.toString()}
        val ref=when(val time=(command.patch.time as? FieldChange.Set)?.value?:source.time){is EventTime.Timed->source.selected.copy(instanceStart=time.start,instanceEnd=time.end)
            is EventTime.AllDay->source.selected.copy(instanceStart=time.dates.start.atStartOfDay(ZoneOffset.UTC).toInstant(),instanceEnd=time.dates.endExclusive.atStartOfDay(ZoneOffset.UTC).toInstant())}
        return Model(map,removed,changed,added,(command.patch.alertMinutes as? FieldChange.Set)?.value,ref,false)
    }
    private fun batch(source:OwnedMeetingSource,m:Model):Batch {
        val ops=arrayListOf(assertRow(ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI,source.selected.calendarId.value),source.calendar),assertRow(eventUri(source.selected.eventId),source.event))
        assertChildren(ops,CalendarContract.Attendees.CONTENT_URI,source.selected.eventId,source.attendees)
        assertChildren(ops,CalendarContract.Reminders.CONTENT_URI,source.selected.eventId,source.reminders)
        val counts=mutableMapOf<Int,Int>();val guests=mutableListOf<Int>();val alerts=mutableListOf<Int>()
        fun addCount(op:ContentProviderOperation){counts[ops.size]=1;ops+=op}
        val id=source.selected.eventId
        if(m.values.isNotEmpty())addCount(ContentProviderOperation.newUpdate(CalendarContract.Events.CONTENT_URI).withSelection("_id = ? AND calendar_id = ?",arrayOf(id.toString(),source.selected.calendarId.value.toString())).withValues(values(m.values)).withExpectedCount(1).withYieldAllowed(false).build())
        for(row in source.attendees){val rowId=requireNotNull(row.meetingLong("_id"));val where="_id = ? AND event_id = ? AND attendeeEmail = ? AND attendeeStatus = ? AND attendeeType = ?";val args=arrayOf(rowId.toString(),id.toString(),row["attendeeEmail"],row["attendeeStatus"],row["attendeeType"])
            if(rowId in m.removed)addCount(ContentProviderOperation.newDelete(CalendarContract.Attendees.CONTENT_URI).withSelection(where,args).withExpectedCount(1).withYieldAllowed(false).build())
            else m.changed[rowId]?.let{type->addCount(ContentProviderOperation.newUpdate(CalendarContract.Attendees.CONTENT_URI).withSelection(where,args).withValue("attendeeType",type).withExpectedCount(1).withYieldAllowed(false).build())}
        }
        for(row in m.added){guests+=ops.size;ops+=ContentProviderOperation.newInsert(CalendarContract.Attendees.CONTENT_URI).withValues(values(row)).withValue("event_id",id).withYieldAllowed(false).build()}
        if(m.alerts!=null){for(row in source.reminders.filter{it["method"]=="1"})addCount(ContentProviderOperation.newDelete(CalendarContract.Reminders.CONTENT_URI).withSelection("_id = ? AND event_id = ? AND method = ?",arrayOf(row["_id"],id.toString(),"1")).withExpectedCount(1).withYieldAllowed(false).build())
            for(minutes in m.alerts){alerts+=ops.size;ops+=ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI).withValue("event_id",id).withValue("minutes",minutes).withValue("method",1).withYieldAllowed(false).build()}}
        ops+=assertRow(eventUri(id),expectedEvent(source,m))
        val retained=retainedGuests(source,m);count(ops,CalendarContract.Attendees.CONTENT_URI,id,retained.size+m.added.size)
        retained.forEach{ops+=assertRow(ContentUris.withAppendedId(CalendarContract.Attendees.CONTENT_URI,requireNotNull(it.meetingLong("_id"))),it)}
        m.added.forEachIndexed{i,row->ops+=ContentProviderOperation.newAssertQuery(CalendarContract.Attendees.CONTENT_URI).withSelection("_id = ? AND event_id = ?",arrayOf("0",id.toString())).withSelectionBackReference(0,guests[i]).withValues(values(row)).withValue("event_id",id).withExpectedCount(1).withYieldAllowed(false).build()}
        val retainedAlerts=retainedReminders(source,m);count(ops,CalendarContract.Reminders.CONTENT_URI,id,retainedAlerts.size+(m.alerts?.size?:0))
        retainedAlerts.forEach{ops+=assertRow(ContentUris.withAppendedId(CalendarContract.Reminders.CONTENT_URI,requireNotNull(it.meetingLong("_id"))),it)}
        m.alerts?.forEachIndexed{i,minutes->ops+=ContentProviderOperation.newAssertQuery(CalendarContract.Reminders.CONTENT_URI).withSelection("_id = ? AND event_id = ?",arrayOf("0",id.toString())).withSelectionBackReference(0,alerts[i]).withValue("event_id",id).withValue("minutes",minutes).withValue("method",1).withExpectedCount(1).withYieldAllowed(false).build()}
        return Batch(ops,guests,alerts,counts)
    }
    private suspend fun recover(operation:CalendarLocalStore.Operation,safeRetry:Boolean):MeetingMutationResult {
        if(operation.phase=="ABANDONED")return common(MutationResult.Retired(operation.id))
        val parsed=proof(operation)?:return common(MutationResult.Uncertain(operation.id,null));val receipt=parsed.first;val source=parsed.second;val triple=OwnedMeetingCodec.decode(receipt.getJSONObject("command"));val m=model(source,triple.second,triple.third)
        fun uncertain()=common(MutationResult.Uncertain(operation.id,source.selected.eventId))
        if(operation.phase in setOf("PREPARED","FAILED"))return common(if(safeRetry)MutationResult.SafeToRetry(operation.id)else MutationResult.Unavailable)
        return try {
            if(!namespace(source))return uncertain()
            val current=provider.rows(eventUri(source.selected.eventId),maxRows=1).singleOrNull()?:return uncertain()
            val expected=expectedEvent(source,m).filterKeys{it !in setOf("_sync_id","uid2445")}
            if(expected.any{(k,v)->current[k]!=v})return uncertain()
            for(key in listOf("_sync_id","uid2445")){val known=receipt.nullable(key)?.takeIf(String::isNotBlank)?:source.event[key]?.takeIf(String::isNotBlank);if(known!=null&&current[key]?.takeIf(String::isNotBlank)!=known)return uncertain()}
            val guestIds=receipt.getJSONArray("guests").longs();val alertIds=receipt.getJSONArray("alerts").longs()
            val expectedGuests=retainedGuests(source,m)+m.added.mapIndexed{i,row->row+mapOf("_id" to guestIds[i].toString(),"event_id" to source.selected.eventId.toString())}
            val actualGuests=provider.rows(CalendarContract.Attendees.CONTENT_URI,CalendarMeetingWriter.attendeeFields,"event_id = ? AND calendar_id = ? AND organizer = ?",arrayOf(source.selected.eventId.toString(),source.selected.calendarId.value.toString(),source.owner),minOf(201,readBudgets.maxDetailChildren))
            if(actualGuests.sortedBy{it.meetingLong("_id")}!=expectedGuests.sortedBy{it.meetingLong("_id")})return uncertain()
            val expectedAlerts=retainedReminders(source,m)+(m.alerts?.mapIndexed{i,minutes->mapOf("_id" to alertIds[i].toString(),"event_id" to source.selected.eventId.toString(),"minutes" to minutes.toString(),"method" to "1")}?:emptyList())
            val actualAlerts=provider.rows(CalendarContract.Reminders.CONTENT_URI,ReminderFields,"event_id = ?",arrayOf(source.selected.eventId.toString()),readBudgets.maxDetailChildren)
            if(actualAlerts.sortedBy{it.meetingLong("_id")}!=expectedAlerts.sortedBy{it.meetingLong("_id")})return uncertain()
            if(!m.cancelled){val native=provider.rows(instanceUri(m.ref),InstanceFields,"event_id = ? AND calendar_id = ? AND begin = ?",arrayOf(m.ref.eventId.toString(),m.ref.calendarId.value.toString(),m.ref.instanceStart.toEpochMilli().toString()),1).singleOrNull()?:return uncertain()
                if(native["end"]!=m.ref.instanceEnd.toEpochMilli().toString()||native["allDay"]!=expectedEvent(source,m)["allDay"]||listOf("original_id","original_sync_id","originalInstanceTime").any{!native[it].isNullOrBlank()})return uncertain()}
            if(!namespace(source))return uncertain();provider.ensureRead()
            val verified=JSONObject(receipt.toString());for(key in listOf("_sync_id","uid2445"))verified.put(key,receipt.nullable(key)?.takeIf(String::isNotBlank)?:source.event[key]?.takeIf(String::isNotBlank)?:current[key]?.takeIf(String::isNotBlank)?:JSONObject.NULL)
            check(store.mark(operation,"VERIFIED",verified.toString()));reader.refresh()
            if(m.cancelled)MeetingMutationResult.LocalCancelled(m.ref,RecurrenceScope.THIS)else MeetingMutationResult.LocalSaved(m.ref,RecurrenceScope.THIS)
        }catch(s:SecurityException){throw s}catch(c:CancellationException){throw c}catch(_:Exception){uncertain()}
    }
    private fun proof(operation:CalendarLocalStore.Operation):Pair<JSONObject,OwnedMeetingSource>?=try {
        require(handles(operation.kind));val receipt=JSONObject(operation.resultRef?:error("Missing receipt"));require(receipt.getString("feature")==ReceiptFeature&&receipt.getInt("version")==1&&receipt.getString("kind")==operation.kind&&receipt.getString("signature")==operation.signature)
        val source=OwnedMeetingSource.decode(receipt.getJSONObject("source").toString(),readBudgets.maxCanonicalBytes)
        val(selected,token,command)=OwnedMeetingCodec.decode(receipt.getJSONObject("command"));require(selected==source.selected&&OwnedMeetingSource.token(source.canonical(readBudgets.maxCanonicalBytes))==token.value)
        require(fingerprint(selected,token,command,readBudgets.maxCanonicalBytes)==(operation.kind to operation.signature))
        require(OwnedMeetingCodec.encode(selected,token,command,readBudgets.maxCanonicalBytes).toString()==receipt.getJSONObject("command").toString());val m=model(source,token,command)
        if(operation.phase in setOf("PREPARED","FAILED"))require(receipt.getString("stage")=="prepared"&&!receipt.has("guests")&&!receipt.has("alerts"))
        else {require(operation.phase in setOf("COMMITTED","VERIFIED")&&receipt.getString("stage")=="committed");val guests=receipt.getJSONArray("guests").longs();val alerts=receipt.getJSONArray("alerts").longs()
            require(guests.size==m.added.size&&alerts.size==(m.alerts?.size?:0)&&guests.all{it>0}&&alerts.all{it>0}&&guests.distinct().size==guests.size&&alerts.distinct().size==alerts.size)
            require(guests.none{it in source.attendees.mapNotNull{r->r.meetingLong("_id")}}&&alerts.none{it in source.reminders.mapNotNull{r->r.meetingLong("_id")}})}
        receipt to source
    }catch(c:CancellationException){throw c}catch(_:Exception){null}
    private suspend fun namespace(source:OwnedMeetingSource):Boolean{val current=provider.calendar(source.selected.calendarId);return meetingIdentity(current)==source.identity&&(current["calendar_access_level"]?.toIntOrNull()?:-1)>=200}
    private fun permissions()=hasPermission(Manifest.permission.READ_CALENDAR)&&hasPermission(Manifest.permission.WRITE_CALENDAR)
    companion object {
        const val ReceiptFeature="MEETING_OWNED_RECEIPT_V1"
        private val Bookkeeping=setOf("dirty","mutators","lastDate","lastSynced")
        private val ReminderFields=arrayOf("_id","event_id","minutes","method")
        private val InstanceFields=arrayOf("event_id","calendar_id","begin","end","allDay","original_id","original_sync_id","originalInstanceTime")
        fun handles(kind:String)=OwnedMeetingCodec.handles(kind)
        fun fingerprint(selected:EventRef,token:EditToken,command:MeetingCommand,maxBytes:Int)=OwnedMeetingCodec.fingerprint(selected,token,command,maxBytes)
        private fun retainedGuests(source:OwnedMeetingSource,m:Model)=source.attendees.filter{it.meetingLong("_id") !in m.removed}.map{row->m.changed[row.meetingLong("_id")]?.let{row+("attendeeType" to it.toString())}?:row}
        private fun retainedReminders(source:OwnedMeetingSource,m:Model)=if(m.alerts==null)source.reminders else source.reminders.filter{it["method"]!="1"}
        private fun expectedEvent(source:OwnedMeetingSource,m:Model)=source.event.filterKeys{it !in Bookkeeping}+m.values+(if(m.alerts==null)emptyMap()else mapOf("hasAlarm" to if(retainedReminders(source,m).size+m.alerts.size==0)"0"else "1"))
        private fun common(result:MutationResult)=MeetingMutationResult.Common(result)
        private fun eventUri(id:Long)=ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI,id)
        private fun values(row:Map<String,String?>)=ContentValues().apply{row.forEach{(k,v)->if(v==null)putNull(k)else put(k,v)}}
        private fun assertRow(uri:Uri,row:Map<String,String?>)=ContentProviderOperation.newAssertQuery(uri).withValues(values(row)).withExpectedCount(1).withYieldAllowed(false).build()
        private fun count(ops:ArrayList<ContentProviderOperation>,uri:Uri,id:Long,size:Int){ops+=ContentProviderOperation.newAssertQuery(uri).withSelection("event_id = ?",arrayOf(id.toString())).withValue("event_id",id).withExpectedCount(size).withYieldAllowed(false).build()}
        private fun assertChildren(ops:ArrayList<ContentProviderOperation>,uri:Uri,id:Long,rows:List<Map<String,String?>>){count(ops,uri,id,rows.size);rows.forEach{ops+=assertRow(ContentUris.withAppendedId(uri,requireNotNull(it.meetingLong("_id"))),it)}}
        private fun instanceUri(ref:EventRef)=CalendarContract.Instances.CONTENT_URI.buildUpon().apply{ContentUris.appendId(this,ref.instanceStart.toEpochMilli());ContentUris.appendId(this,Math.addExact(ref.instanceStart.toEpochMilli(),1))}.build()
        private fun insertId(uri:Uri?,path:String,existing:Set<Long>):Long{require(uri!=null&&uri.scheme=="content"&&uri.authority==CalendarContract.AUTHORITY&&uri.encodedQuery==null&&uri.encodedFragment==null);val segments=uri.pathSegments;require(segments.size==2&&segments[0]==path);val id=segments[1].toLong();require(id>0&&id !in existing&&uri.encodedPath=="/$path/$id");return id}
        private fun JSONArray.longs()=List(length()){getLong(it)}
        private fun JSONObject.nullable(key:String)=if(isNull(key))null else getString(key)
        private fun checkParcel(ops:List<ContentProviderOperation>){val p=Parcel.obtain();try{p.writeTypedList(ops);if(p.dataSize()>256*1024)throw OwnedMeetingReview("The complete meeting transaction exceeds the safe local size budget")}finally{p.recycle()}}
    }
}
