package dev.swapnilraj.calendar.data.calendar

import android.Manifest
import android.content.*
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.Parcel
import android.provider.CalendarContract
import dev.swapnilraj.calendar.core.*
import java.time.*
import java.util.UUID
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Portable, owned, exception-free splits. The normal Events URI never clones server identity. */
internal class CalendarFollowingSeriesWriter(
    private val resolver: ContentResolver,
    private val sourceReader: CalendarSeriesReader,
    private val store: CalendarLocalStore,
    private val hasPermission: (String) -> Boolean,
    private val owner: CalendarSeriesWriter,
    private val io: CoroutineDispatcher,
    private val budget: RecurrencePlanningBudget,
    private val readBudgets: ProviderReadBudgets,
    private val providerClonePolicy: ProviderClonePolicy,
) {
    private val mutex = Mutex()
    private class Review(val reason: String) : Exception()
    private class Conflict : Exception()
    private data class Raw(val encoded: String, val calendar: Map<String,String?>, val master: Map<String,String?>,
        val reminders: Map<Long,List<Map<String,String?>>>, val attendees: Map<Long,List<Map<String,String?>>>,
        val properties: Map<Long,List<Map<String,String?>>>, val snapshot: RecurrenceSeriesSnapshot)
    private data class Material(val whole:Boolean,val ordinal:Int,val start:RecurrenceStart,val template:RecurrenceMasterTemplate,
        val prefix:Map<String,String?>,val tail:Map<String,String?>?,val prefixReminders:List<Map<String,String?>>,
        val tailReminders:List<Map<String,String?>>,val tailAttendees:List<Map<String,String?>>) {
        fun json()=JSONObject().put("whole",whole).put("ordinal",ordinal).put("prefix",meetingRowJson(prefix))
            .put("tail",tail?.let(::meetingRowJson)?:JSONObject.NULL).put("prefixReminders",JSONArray(prefixReminders.map(::meetingRowJson)))
            .put("tailReminders",JSONArray(tailReminders.map(::meetingRowJson))).put("tailAttendees",JSONArray(tailAttendees.map(::meetingRowJson)))
    }

    fun capabilities(source:CalendarSeriesSource,series:EditableSeries):SeriesScopeCapabilities?=try {
        val raw=raw(source);val selected=requireNotNull(raw.snapshot.validatedSelection)
        val original=requireNotNull(selected.recurrence).originalStart
        val key=if(raw.snapshot.start is RecurrenceStart.AllDay)RecurrenceKey.Date(original.atOffset(ZoneOffset.UTC).toLocalDate())else RecurrenceKey.Timed(original)
        val model=material(raw,SeriesCommand.FollowingEdit(RecurrenceEditIntent.FollowingSeriesEdit(key)),previewUid(raw))
        if(!model.whole)requireCloneProfile()
        SeriesScopeCapabilities(AccessDecision.ALLOWED,series.allowedFields,AccessDecision.UNKNOWN,recurrence=AccessDecision.ALLOWED)
    }catch(limit:ProviderReadLimitExceeded){throw limit}
    catch(cancelled:CancellationException){throw cancelled}
    catch(_:Exception){null}

    suspend fun mutate(id:String,selected:EventRef,token:EditToken,command:SeriesCommand.FollowingEdit):SeriesMutationResult=
        withContext(io+readBudgetContext(readBudgets.mutation)){mutex.withLock{
        var attempted:CalendarLocalStore.Operation?=null
        var knownTail:Long?=null
        try {
            store.abandoned(id){CalendarSeriesWriter.retirementFingerprint(selected,token,command)}?.let{return@withLock common(it)}
            val fingerprint=CalendarSeriesWriter.retirementFingerprint(selected,token,command)
            val candidate=store.operation(id)
            if(candidate==null||FollowingExecutionReceipt.handles(candidate))return@withLock mutateFrozen(id,selected,token,command,fingerprint.second,candidate)
            if(candidate.kind!=fingerprint.first||candidate.signature!=fingerprint.second)return@withLock common(MutationResult.Conflict(null))
            if(candidate.phase !in setOf("PREPARED","FAILED"))return@withLock recover(candidate,false)
            if(!permissions())return@withLock common(MutationResult.PermissionRequired)
            if(id.isBlank())return@withLock SeriesMutationResult.Invalid("Operation ID is required")
            val(kind,signature)=CalendarSeriesWriter.retirementFingerprint(selected,token,command)
            val existing=store.operation(id)
            if(existing!=null){
                if(existing.kind!=kind||existing.signature!=signature)return@withLock common(MutationResult.Conflict(null))
                if(existing.phase !in setOf("PREPARED","FAILED"))return@withLock recover(existing,false)
                if(existing.resultRef==null)return@withLock common(MutationResult.Uncertain(id,null))
            }
            val source=sourceReader.checkedSource(selected,token);val raw=raw(source)
            val frozenCommand=CalendarSeriesWriter.parseFollowingCommand(raw.snapshot,CalendarSeriesWriter.frozenCommandJson(command))
            val initial=material(raw,frozenCommand,previewUid(raw))
            if(!initial.whole)requireCloneProfile()
            val uid=if(initial.whole)null else if(existing==null)newUid(raw) else proof(existing)?.first?.nullable("uid")?:return@withLock common(MutationResult.Uncertain(id,null))
            var model=material(raw,frozenCommand,uid,existing?.let{proof(it)?.first?.let(::issuedTailEndZone)?:return@withLock common(MutationResult.Uncertain(id,null))}?:FieldChange.Keep)
            var ops=operations(source,raw,model)
            checkParcel(ops.first)
            // The source+materialized UUID are durable independently of the immutable command.
            checkCanonical(listOf(raw.calendar,raw.master,raw.reminders,raw.attendees,raw.properties,model.prefix,model.tail,
                model.prefixReminders,model.tailReminders,model.tailAttendees),readBudgets.maxCanonicalBytes,16384)
            val metadata=JSONObject().put("version",2).put("feature",Feature).put("kind",Kind).put("signature",signature)
                .put("token",token.value).put("command",CalendarSeriesWriter.frozenCommandJson(frozenCommand))
                .put("source",JSONObject(raw.encoded)).put("uid",uid?:JSONObject.NULL).put("material",model.json()).put("stage","prepared")
            checkStoredJson(metadata.toString(),readBudgets.maxCanonicalBytes)
            if(!permissions())return@withLock common(MutationResult.PermissionRequired)
            if(!model.whole)requireCloneProfile()
            currentCoroutineContext().ensureActive();store.prepare(id,signature,kind,metadata.toString())
            val persisted=store.operation(id)?:error("Journal unavailable")
            if(persisted.kind!=kind||persisted.signature!=signature)return@withLock common(MutationResult.Conflict(null))
            if(persisted.phase=="ABANDONED")return@withLock common(MutationResult.Retired(id))
            if(persisted.phase !in setOf("PREPARED","FAILED"))return@withLock recover(persisted,false)
            val winner=proof(persisted)?:return@withLock common(MutationResult.Uncertain(id,null))
            if(winner.second.encoded!=raw.encoded)return@withLock common(MutationResult.Conflict(null))
            // Another helper can win prepare. Its one persisted UID always wins, never our proposal.
            val stored=winner.first
            model=material(raw,frozenCommand,stored.nullable("uid"),issuedTailEndZone(stored));ops=operations(source,raw,model);checkParcel(ops.first)
            if(!permissions())return@withLock common(MutationResult.PermissionRequired)
            currentCoroutineContext().ensureActive()
            if(!model.whole)requireCloneProfile()
            if(!store.attempt(persisted))return@withLock recover(store.operation(id)!!,false)
            attempted=persisted.copy(phase="ATTEMPTED")
            if(!permissions()){check(store.mark(attempted,"FAILED"));return@withLock common(MutationResult.PermissionRequired)}
            if(!model.whole&&!providerClonePolicy.allows(ProviderCloneOperation.INDEPENDENT_TAIL))return@withLock common(MutationResult.Uncertain(id,null))
            currentCoroutineContext().ensureActive()
            val results=resolver.applyBatch(CalendarContract.AUTHORITY,ops.first)
            knownTail=if(model.whole)raw.snapshot.identity.masterEventId else normalInsertId(results,requireNotNull(ops.second)).also{require(it!=raw.snapshot.identity.masterEventId)}
            val receipt=JSONObject(stored.toString()).put("stage","committed").put("result",knownTail)
            check(store.mark(attempted,"COMMITTED",receipt.toString()))
            recover(store.operation(id)!!,false)
        }catch(_:SecurityException){common(if(attempted==null)MutationResult.PermissionRequired else MutationResult.Uncertain(id,knownTail))}
        catch(_:ProviderReadLimitExceeded){if(attempted==null)SeriesMutationResult.PlanningReview("The complete split exceeds the read or storage budget")else common(MutationResult.Uncertain(id,knownTail))}
        catch(_:SeriesSourceConflict){common(MutationResult.Conflict(null))}catch(_:Conflict){common(MutationResult.Conflict(null))}
        catch(denied:SeriesSourceDenied){common(MutationResult.Denied(denied.reason))}
        catch(invalid:CalendarSeriesWriter.Invalid){common(MutationResult.ValidationFailed(invalid.fields))}
        catch(review:Review){if(attempted==null)SeriesMutationResult.PlanningReview(review.reason)else common(MutationResult.Uncertain(id,knownTail))}
        catch(review:SeriesSourceReview){if(attempted==null)SeriesMutationResult.PlanningReview(review.reason)else common(MutationResult.Uncertain(id,knownTail))}
        catch(_:OperationApplicationException){if(attempted==null)common(MutationResult.Unavailable)else try{check(store.mark(attempted,"FAILED"));common(MutationResult.Unavailable)}catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){common(MutationResult.Uncertain(id,knownTail))}}
        catch(cancelled:CancellationException){throw cancelled}
        catch(_:Exception){common(if(attempted==null)MutationResult.Unavailable else MutationResult.Uncertain(id,knownTail))}
    }}

    suspend fun recover(id:String):SeriesMutationResult=withContext(io+readBudgetContext(readBudgets.mutation)){mutex.withLock{
        try {if(store.operationHeader(id)?.phase=="ABANDONED")return@withLock common(MutationResult.Retired(id))
            ensureRead();store.operation(id)?.let{recover(it,true)}?:common(MutationResult.Unavailable)
        }catch(_:SecurityException){common(MutationResult.PermissionRequired)}catch(cancelled:CancellationException){throw cancelled}
        catch(_:Exception){common(MutationResult.Uncertain(id,null))}
    }}
    private suspend fun recover(operation:CalendarLocalStore.Operation,retry:Boolean):SeriesMutationResult {
        if(operation.phase=="ABANDONED")return common(MutationResult.Retired(operation.id))
        if(FollowingExecutionReceipt.handles(operation)) {
            val parsed=try{FollowingExecutionReceipt.parse(operation,readBudgets.maxCanonicalBytes)}catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){return common(MutationResult.Uncertain(operation.id,null))}
            return recoverFrozen(operation,parsed)
        }
        // Historical2 never recorded complete native ACK facts. No current planner can invent
        // that evidence for an attempted/committed operation after the fact.
        if(operation.phase in setOf("PREPARED","FAILED")){if(proof(operation)==null)return common(MutationResult.Uncertain(operation.id,null));return common(if(retry)MutationResult.SafeToRetry(operation.id)else MutationResult.Unavailable)}
        if(operation.phase !in setOf("COMMITTED","VERIFIED"))return common(MutationResult.Uncertain(operation.id,null))
        return legacyKnown(operation)
    }

    private suspend fun legacyKnown(operation:CalendarLocalStore.Operation):SeriesMutationResult { return try {
        val j=JSONObject(requireNotNull(operation.resultRef))
        require(j.getInt("version")==2&&j.getString("feature")==Feature&&j.getString("kind")==Kind&&j.getString("signature")==operation.signature&&j.getString("stage")=="committed")
        val source=j.getJSONObject("source");val raw=source.toString();val token=j.getString("token")
        require(meetingHash("SERIES_EDIT_V1"+raw)==token)
        val selection=CreateSixJson.ref(source.getJSONObject("selection"));val envelope=JSONObject().put("version",1).put("selected",CreateSixJson.ref(selection)).put("token",token).put("command",j.getJSONObject("command"))
        require(CreateSixJson.hash(envelope.toString())==operation.signature)
        val calendar=CreateSixJson.row(source.getJSONObject("calendar"));val master=CreateSixJson.row(source.getJSONObject("master"));val result=CalendarCreateAcknowledgments.integer(j.get("result"));require(result>0)
        if(!sameCalendar(calendar,calendar(CalendarId(requireNotNull(master["calendar_id"]).toLong()))))return common(MutationResult.Uncertain(operation.id,null))
        val whole=j.getJSONObject("material").getBoolean("whole")
        val prefix=requireNotNull(master["_id"]).toLong();require(if(whole)result==prefix else result!=prefix)
        if(!whole){val uid=requireNotNull(j.nullable("uid"));require(UUID.fromString(uid).toString()==uid&&!uid.equals(master["uid2445"],true))}
        val expected=if(whole)master else mapOf("_id" to result.toString(),"calendar_id" to master["calendar_id"],"uid2445" to j.nullable("uid"))
        if(!identityRow(result,requireNotNull(master["calendar_id"]).toLong(),expected["uid2445"],if(whole)expected["_sync_id"]else null,whole).first)return common(MutationResult.Uncertain(operation.id,null))
        common(MutationResult.Uncertain(operation.id,result))
    }catch(cancelled:CancellationException){throw cancelled}catch(_:SecurityException){common(MutationResult.PermissionRequired)}catch(_:Exception){common(MutationResult.Uncertain(operation.id,null))}
    }

    // Called only while the family mutex is held. The owner never re-enters this mutex.
    internal var frozenPreparationProbe:()->Unit={}
    internal var frozenCandidateReady:()->Unit={}
    private suspend fun mutateFrozen(id:String,selected:EventRef,token:EditToken,command:SeriesCommand.FollowingEdit,signature:String,existing:CalendarLocalStore.Operation?):SeriesMutationResult {
        var attempted:CalendarLocalStore.Operation?=null;var known:Long?=null
        try {
            require(id.isNotBlank()&&id.length<=readBudgets.mutation.maxCellUnits)
            val initial=if(existing!=null){
                if(existing.kind!=Kind||existing.signature!=signature)return common(MutationResult.Conflict(null))
                val parsed=try{FollowingExecutionReceipt.parse(existing,readBudgets.maxCanonicalBytes)}catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){return common(MutationResult.Uncertain(id,null))}
                if(existing.phase !in setOf("PREPARED","FAILED"))return recoverFrozen(existing,parsed)
                parsed.second
            }else{
                if(!permissions())return common(MutationResult.PermissionRequired)
                val source=sourceReader.checkedSource(selected,token);val raw=raw(source)
                val frozen=CalendarSeriesWriter.parseFollowingCommand(raw.snapshot,CalendarSeriesWriter.frozenCommandJson(command))
                val preview=material(raw,frozen,previewUid(raw))
                if(!preview.whole){
                    requireCloneProfile()
                    if(raw.master["uid2445"].isNullOrBlank()&&raw.master["_sync_id"].isNullOrBlank())throw Review("The prefix has no issued identity for a safe independent split")
                }
                val model=material(raw,frozen,if(preview.whole)null else newUid(raw))
                FollowingExecutionReceipt(id,CalendarSeriesWriter.frozenCommandJson(frozen).toString(),token.value,raw.encoded,model.whole,model.ordinal,
                    prefixValues(raw,model),model.prefix,model.prefixReminders,model.tail,model.tailReminders,model.tailAttendees,templateStart(model.template.time),templateEnd(model.template.time)).also{it.validate();frozenCandidateReady()}
            }
            if(!permissions())return common(MutationResult.PermissionRequired)
            if(!initial.whole)requireCloneProfile()
            frozenPreflight(initial)
            currentCoroutineContext().ensureActive();store.prepare(id,signature,Kind,initial.prepared().toString())
            val winner=store.operation(id)?:return common(MutationResult.Uncertain(id,null))
            if(winner.kind!=Kind||winner.signature!=signature)return common(MutationResult.Conflict(null))
            if(winner.phase=="ABANDONED")return common(MutationResult.Retired(id))
            // A concurrent legacy preparation is never silently converted to3.
            if(!FollowingExecutionReceipt.handles(winner))return common(MutationResult.Uncertain(id,null))
            val parsed=try{FollowingExecutionReceipt.parse(winner,readBudgets.maxCanonicalBytes)}catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){return common(MutationResult.Uncertain(id,null))}
            if(winner.phase !in setOf("PREPARED","FAILED"))return recoverFrozen(winner,parsed)
            val receipt=parsed.second
            frozenPreflight(receipt)
            if(!freshOriginal(receipt))return common(MutationResult.Conflict(null))
            if(!permissions())return common(MutationResult.PermissionRequired)
            if(!receipt.whole)requireCloneProfile()
            currentCoroutineContext().ensureActive()
            if(!store.attempt(winner))return recover(store.operation(id)?:return common(MutationResult.Uncertain(id,null)),false)
            attempted=winner.copy(phase="ATTEMPTED")
            if(!permissions()){check(store.mark(attempted,"FAILED"));return common(MutationResult.PermissionRequired)}
            if(!receipt.whole&&!providerClonePolicy.allows(ProviderCloneOperation.INDEPENDENT_TAIL))return common(MutationResult.Uncertain(id,null))
            currentCoroutineContext().ensureActive()
            val steps=receipt.steps();val results=resolver.applyBatch(CalendarContract.AUTHORITY,ArrayList(steps.map{it.build()}))
            known=if(receipt.whole)receipt.masterId else CalendarCreateAcknowledgments.insertId(results.getOrNull(steps.indexOfFirst{it.slot=="TAIL"})?.uri,"events").also{require(it!=receipt.masterId)}
            val minimal=receipt.prepared().put("stage","committed").put("result",known).put("ackComplete",false).toString()
            val committed=try{FollowingAcknowledgments.capture(results,steps.size)?.let{JSONObject(minimal).put("ackFacts",it).toString().takeIf{raw->raw.toByteArray(Charsets.UTF_8).size<=readBudgets.maxCanonicalBytes}}?:minimal}catch(_:Exception){minimal}
            check(store.mark(attempted,"COMMITTED",committed))
            val latest=store.operation(id)?:return common(MutationResult.Uncertain(id,known))
            return recoverFrozen(latest,FollowingExecutionReceipt.parse(latest,readBudgets.maxCanonicalBytes),justAcknowledged=true)
        }catch(cancelled:CancellationException){throw cancelled}
        catch(_:SecurityException){return common(if(attempted==null)MutationResult.PermissionRequired else MutationResult.Uncertain(id,known))}
        catch(_:OperationApplicationException){return if(attempted==null)common(MutationResult.Unavailable)else try{check(store.mark(requireNotNull(attempted),"FAILED"));common(MutationResult.Unavailable)}catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){common(MutationResult.Uncertain(id,known))}}
        catch(conflict:SeriesSourceConflict){return common(MutationResult.Conflict(null))}
        catch(denied:SeriesSourceDenied){return common(MutationResult.Denied(denied.reason))}
        catch(invalid:CalendarSeriesWriter.Invalid){return common(MutationResult.ValidationFailed(invalid.fields))}
        catch(review:Review){return if(attempted==null)SeriesMutationResult.PlanningReview(review.reason)else common(MutationResult.Uncertain(id,known))}
        catch(review:SeriesSourceReview){return if(attempted==null)SeriesMutationResult.PlanningReview(review.reason)else common(MutationResult.Uncertain(id,known))}
        catch(_:ProviderReadLimitExceeded){return if(attempted==null)SeriesMutationResult.PlanningReview("The complete frozen split exceeds the read or storage budget")else common(MutationResult.Uncertain(id,known))}
        catch(_:Exception){return common(if(attempted==null)MutationResult.Unavailable else MutationResult.Uncertain(id,known))}
    }

    private fun frozenPreflight(r:FollowingExecutionReceipt) {
        r.validate();val steps=r.steps();checkParcel(steps.map{it.build()})
        Math.subtractExact(r.firstStart.toEpochMilli(),1);Math.addExact(r.firstStart.toEpochMilli(),1)
        val children=r.children("reminders").size+r.children("attendees").size+r.children("extendedproperties").size+r.prefixReminders.size+r.tailReminders.size+r.tailAttendees.size
        val width=r.master.size+r.calendar.size+(r.tail?.size?:0)
        val childColumns=(r.children("reminders")+r.children("attendees")+r.children("extendedproperties")+r.prefixReminders+r.tailReminders+r.tailAttendees)
            .sumOf{it.size.toLong()+2L}
        // Include repeated identity/membership guards and actual stored child widths, including
        // nullable opaque source columns, before allowing a predictably unverifiable dispatch.
        if(children>readBudgets.maxSeriesChildren||100L+8L*children>readBudgets.mutation.maxRows||10L*width+16L*childColumns+64L*children+256>readBudgets.mutation.maxCells)throw ProviderReadLimitExceeded()
        val result=if(r.whole)r.masterId else Long.MAX_VALUE
        val results=steps.mapIndexed{index,s->if(s.kind=="INSERT")ContentProviderResult(ContentUris.withAppendedId(Uri.parse("content://${CalendarContract.AUTHORITY}/${s.table}"),if(s.slot=="TAIL")result else Long.MAX_VALUE-index-1))else ContentProviderResult(requireNotNull(s.expected))}.toTypedArray()
        val facts=FollowingAcknowledgments.capture(results,steps.size)?:throw Review("Complete native acknowledgements cannot fit the private receipt")
        val ids=FollowingAcknowledgments.insertIds(facts,steps,result,r.masterId)?:throw Review("Complete native acknowledgement layout is unavailable")
        val prepared=r.prepared();checkStoredJson(prepared.toString(),readBudgets.maxCanonicalBytes)
        val minimal=JSONObject(prepared.toString()).put("stage","committed").put("result",result).put("ackComplete",false)
        checkStoredJson(minimal.toString(),readBudgets.maxCanonicalBytes)
        val verified=JSONObject(minimal.toString()).put("ackComplete",true).put("ackFacts",facts).put("insertIds",FollowingAcknowledgments.ids(ids)).put("ref",CreateSixJson.ref(r.ref(result))).toString()
        checkStoredJson(verified,readBudgets.maxCanonicalBytes)
        if((currentReadBudget()?.consumedTextUnits?:0L)+8L*verified.length+256L*children+10L*r.sourceRaw.length>readBudgets.mutation.maxTextUnits)throw ProviderReadLimitExceeded()
    }

    /** Recheck complete raw source without a snapshot, an edit-snapshot lookup or current planning. */
    private suspend fun freshOriginal(r:FollowingExecutionReceipt):Boolean {
        val calendar=frozenRows(ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI,r.calendarId),null,maxRows=1).singleOrNull()?:return false
        if(calendar!=r.calendar||!sameCalendar(r.calendar,calendar))return false
        if(calendar["calendar_access_level"]?.toIntOrNull() !in setOf(700,800))return false
        if(!identityRow(r.masterId,r.calendarId,r.master["uid2445"],r.master["_sync_id"],true).first)return false
        val guard=identityGuard(r.masterId,r.calendarId,r.master["uid2445"],r.master["_sync_id"],true)
        if(frozenRows(CalendarContract.Events.CONTENT_URI,null,guard.first,guard.second,1).singleOrNull()!=r.master)return false
        if(!noExceptions(r.masterId,r.master["_sync_id"],CalendarId(r.calendarId)))return false
        suspend fun bound()=identityRow(r.masterId,r.calendarId,r.master["uid2445"],r.master["_sync_id"],true).first
        for(table in listOf("reminders","attendees","extendedproperties")){
            if(!bound())return false
            val uri=Uri.parse("content://${CalendarContract.AUTHORITY}/$table")
            val expected=r.children(table)
            if(table=="extendedproperties") {
                // Preserve the existing WHOLE in-place property policy. SPLIT sources have none;
                // no new joined property view or clone capability is assumed here.
                if(expected.isEmpty()) {
                    if(frozenRows(uri,arrayOf("_id","event_id"),"event_id = ?",arrayOf(r.masterId.toString()),readBudgets.maxSeriesChildren).isNotEmpty()||!bound())return false
                } else {
                    val actual=frozenRows(uri,null,"event_id = ?",arrayOf(r.masterId.toString()),readBudgets.maxSeriesChildren)
                    if(actual.sortedBy{it["_id"]?.toLongOrNull()}!=expected.sortedBy{it["_id"]?.toLongOrNull()}||!bound())return false
                }
                continue
            }
            suspend fun membership():Boolean {
                val actual=frozenRows(uri,arrayOf("_id","event_id"),"event_id = ?",arrayOf(r.masterId.toString()),readBudgets.maxSeriesChildren)
                return actual.all{it["event_id"]==r.masterId.toString()}&&actual.size==expected.size&&
                    actual.map{it["_id"]}.distinct().size==actual.size&&actual.map{it["_id"]}.toSet()==expected.map{it["_id"]}.toSet()
            }
            if(!membership()||!bound())return false
            for(row in expected){
                if(!bound())return false
                var selection="event_id = ? AND calendar_id = ?"
                val args=mutableListOf<String?>(r.masterId.toString(),r.calendarId.toString())
                val uid=r.master["uid2445"]
                if(uid==null)selection+=" AND uid2445 IS NULL"else{selection+=" AND uid2445 = ?";args+=uid}
                val child=ContentUris.withAppendedId(uri,requireNotNull(row["_id"]).toLong())
                if(frozenRows(child,row.keys.toTypedArray(),selection,args.toTypedArray(),1).singleOrNull()!=row||!bound())return false
            }
            if(!membership()||!bound())return false
        }
        return bound()
    }

    private fun identityGuard(id:Long,calendar:Long,uid:String?,sync:String?,guardSync:Boolean):Pair<String,Array<String?>> {
        var sql="_id = ? AND calendar_id = ?";val args=mutableListOf<String?>(id.toString(),calendar.toString())
        if(uid==null)sql+=" AND uid2445 IS NULL"else{sql+=" AND uid2445 = ?";args+=uid}
        if(guardSync){if(sync==null)sql+=" AND _sync_id IS NULL"else{sql+=" AND _sync_id = ?";args+=sync}}
        return sql to args.toTypedArray()
    }
    private suspend fun identityRow(id:Long,calendar:Long,uid:String?,sync:String?,guardSync:Boolean):Pair<Boolean,Map<String,String?>?> {
        val guard=identityGuard(id,calendar,uid,sync,guardSync)
        val row=frozenRows(CalendarContract.Events.CONTENT_URI,arrayOf("_id","calendar_id","uid2445","_sync_id"),guard.first,guard.second,1).singleOrNull()
        return (row!=null&&row["_id"]==id.toString()&&row["calendar_id"]==calendar.toString()&&row["uid2445"]==uid&&(!guardSync||row["_sync_id"]==sync)) to row
    }
    private fun tailExpected(r:FollowingExecutionReceipt)=requireNotNull(r.tail)+mapOf("deleted" to "0","isOrganizer" to "1","hasAlarm" to if(r.tailReminders.isEmpty())"0"else"1","hasExtendedProperties" to "0",
        "selfAttendeeStatus" to (r.tailAttendees.singleOrNull()?.get("attendeeStatus")?:"0"),"rdate" to null,"exrule" to null,"exdate" to null,"original_id" to null,"original_sync_id" to null,"originalInstanceTime" to null,"originalAllDay" to null)

    private suspend fun recoverFrozen(operation:CalendarLocalStore.Operation,parsed:Pair<JSONObject,FollowingExecutionReceipt>,justAcknowledged:Boolean=false):SeriesMutationResult {
        ensureRead()
        if(operation.phase in setOf("PREPARED","FAILED"))return common(MutationResult.SafeToRetry(operation.id))
        if(operation.phase=="ATTEMPTED")return common(MutationResult.Uncertain(operation.id,null))
        val(proof,r)=parsed;val result=CreateSixJson.number(proof,"result");var bound=true
        var recheck:(suspend()->Boolean)?=null
        suspend fun uncertain():SeriesMutationResult {
            // A guarded empty row can be an observed identity replacement. Preserve a known
            // result on unavailable checks, but never after an exact binding is observed gone.
            if(bound)try{if(recheck?.invoke()==false)bound=false}catch(cancelled:CancellationException){throw cancelled}catch(denied:SecurityException){throw denied}catch(_:Exception){}
            return common(MutationResult.Uncertain(operation.id,result.takeIf{bound}))
        }
        return try {
            suspend fun namespace():Boolean=sameCalendar(r.calendar,calendar(CalendarId(r.calendarId)))
            if(!namespace()){bound=false;return uncertain()}
            if(!identityRow(r.masterId,r.calendarId,r.master["uid2445"],r.master["_sync_id"],true).first){bound=false;return uncertain()}
            val sticky=if(proof.has("tailSync"))CreateSixJson.nullableText(proof,"tailSync")?.takeIf(String::isNotBlank)else null
            var learned:String?=null;var observedSync:String?=null
            if(!r.whole){val binding=identityRow(result,r.calendarId,r.uid,null,false);if(!binding.first){bound=false;return uncertain()}
                observedSync=binding.second?.get("_sync_id");learned=observedSync?.takeIf(String::isNotBlank)}
            // The generated UID identifies the known tail. Sticky sync is completeness proof,
            // never a replacement for that UID or permission to read a disagreeing payload.
            recheck={namespace()&&identityRow(r.masterId,r.calendarId,r.master["uid2445"],r.master["_sync_id"],true).first&&
                (r.whole||identityRow(result,r.calendarId,r.uid,null,false).first)}
            bound=true
            if(!r.whole&&(sticky!=null&&sticky!=learned||learned!=null&&learned==r.master["_sync_id"]))return uncertain()
            val ids=FollowingAcknowledgments.insertIds(proof.optJSONObject("ackFacts"),r.steps(),result,r.masterId)?:return uncertain()
            suspend fun prefixBinding()=identityRow(r.masterId,r.calendarId,r.master["uid2445"],r.master["_sync_id"],true).first
            suspend fun tailBinding()=r.whole||identityRow(result,r.calendarId,r.uid,observedSync,true).first
            suspend fun parent(id:Long,expected:Map<String,String?>,uid:String?,sync:String?,guardSync:Boolean):Boolean {
                val guard=identityGuard(id,r.calendarId,uid,sync,guardSync)
                return frozenRows(CalendarContract.Events.CONTENT_URI,expected.keys.toTypedArray(),guard.first,guard.second,1).singleOrNull()==expected
            }
            suspend fun parents()=parent(r.masterId,r.prefix,r.master["uid2445"],r.master["_sync_id"],true)&&
                (r.whole||parent(result,tailExpected(r),r.uid,observedSync,true))
            if(!parents())return uncertain()
            fun expectedChildren(tail:Boolean,table:String):List<Map<String,String?>> {
                val parent=if(tail)result else r.masterId
                val rows=if(tail)when(table){"reminders"->r.tailReminders;"attendees"->r.tailAttendees;else->emptyList()}else if(table=="reminders")r.prefixReminders else r.children(table)
                return rows.mapIndexed{index,row->val label=if(tail){if(table=="reminders")"TAIL_REMINDER:$index"else"TAIL_ATTENDEE:$index"}else"PREFIX_REMINDER:$index"
                    val id=row["_id"]?.toLong()?:requireNotNull(ids[label]);row+mapOf("_id" to id.toString(),"event_id" to parent.toString())}
            }
            suspend fun membership(parent:Long,table:String,expected:List<Map<String,String?>>):Boolean {
                val actual=frozenRows(Uri.parse("content://${CalendarContract.AUTHORITY}/$table"),arrayOf("_id","event_id"),"event_id = ?",arrayOf(parent.toString()),readBudgets.maxSeriesChildren)
                return actual.all{it["event_id"]==parent.toString()}&&actual.map{it["_id"]}.distinct().size==actual.size&&actual.map{it["_id"]}.toSet()==expected.map{it["_id"]}.toSet()&&actual.size==expected.size
            }
            for(tail in if(r.whole)listOf(false)else listOf(false,true))for(table in listOf("reminders","attendees","extendedproperties")) {
                val parent=if(tail)result else r.masterId;val expected=expectedChildren(tail,table)
                if(!membership(parent,table,expected))return uncertain()
                for(row in expected){
                    if(!prefixBinding()||!tailBinding())return uncertain()
                    val uid=if(tail)r.uid else r.master["uid2445"]
                    val childArgs=mutableListOf<String?>(parent.toString())
                    var selection="event_id = ?"
                    // Native Reminder/Attendee item views expose the parent UID/calendar. Property
                    // payloads retain the existing in-place policy: issued binding before/after.
                    if(table!="extendedproperties"){
                        selection+=" AND calendar_id = ?";childArgs+=r.calendarId.toString()
                        if(uid==null)selection+=" AND uid2445 IS NULL"else{selection+=" AND uid2445 = ?";childArgs+=uid}
                    }
                    val uri=ContentUris.withAppendedId(Uri.parse("content://${CalendarContract.AUTHORITY}/$table"),requireNotNull(row["_id"]).toLong())
                    if(frozenRows(uri,row.keys.toTypedArray(),selection,childArgs.toTypedArray(),1).singleOrNull()!=row)return uncertain()
                    if(!prefixBinding()||!tailBinding())return uncertain()
                }
            }
            if(!noExceptions(r.masterId,r.master["_sync_id"],CalendarId(r.calendarId))||!r.whole&&!noExceptions(result,learned,CalendarId(r.calendarId)))return uncertain()
            val ref=r.ref(result);val uri=CalendarContract.Instances.CONTENT_URI.buildUpon().apply{ContentUris.appendId(this,Math.subtractExact(r.firstStart.toEpochMilli(),1));ContentUris.appendId(this,Math.addExact(r.firstStart.toEpochMilli(),1))}.build()
            val instance=frozenRows(uri,InstanceFields,"event_id = ? AND calendar_id = ? AND begin = ?",arrayOf(result.toString(),r.calendarId.toString(),r.firstStart.toEpochMilli().toString()),1).singleOrNull()?:return uncertain()
            if(instance["event_id"]!=result.toString()||instance["calendar_id"]!=r.calendarId.toString()||instance["begin"]!=r.firstStart.toEpochMilli().toString()||instance["end"]!=r.firstEnd.toEpochMilli().toString()||instance["allDay"]!=r.prefix["allDay"]||listOf("original_id","original_sync_id","originalInstanceTime").any{instance[it]!=null})return uncertain()
            if(!prefixBinding()||!tailBinding())return uncertain()
            if(!parents())return uncertain()
            for(tail in if(r.whole)listOf(false)else listOf(false,true))for(table in listOf("reminders","attendees","extendedproperties"))if(!membership(if(tail)result else r.masterId,table,expectedChildren(tail,table)))return uncertain()
            if(!namespace()){bound=false;return uncertain()};ensureRead()
            val verified=JSONObject(proof.toString()).put("ackComplete",true).put("insertIds",FollowingAcknowledgments.ids(ids)).put("ref",CreateSixJson.ref(ref))
            if(!r.whole)verified.put("tailSync",learned?:JSONObject.NULL)
            check(store.mark(operation,"VERIFIED",verified.toString()));sourceReader.refresh()
            SeriesMutationResult.LocalSaved(SeriesIdentity(CalendarId(r.calendarId),result),ref)
        }catch(cancelled:CancellationException){throw cancelled}catch(_:SecurityException){common(MutationResult.PermissionRequired)}catch(_:Exception){if(justAcknowledged)common(MutationResult.Uncertain(operation.id,result))else uncertain()}
    }

    private suspend fun frozenRows(uri:Uri,fields:Array<String>?,selection:String?=null,args:Array<String?>?=null,maxRows:Int):List<Map<String,String?>> {
        ensureRead()
        val cursor=suspendCancellableCoroutine<Cursor>{continuation->val signal=CancellationSignal();continuation.invokeOnCancellation{signal.cancel()}
            try{val result=(resolver.query(uri,fields,selection,args,null,signal)?:error("Provider unavailable")).bounded(maxRows)
                try{ensureRead()}catch(failure:Exception){result.close();throw failure}
                continuation.resume(result,onCancellation={_,resource,_->resource.close()})
            }catch(failure:Exception){continuation.resumeWithException(failure)}}
        return cursor.use{c->val names=fields?.toList()?:c.columnNames.filterNot{uri.pathSegments.firstOrNull()=="attendees"&&SeriesAttendeeColumns.isJoinedAlias(it)}
            buildList{while(c.moveToNext()){currentCoroutineContext().ensureActive();ensureRead();add(names.associateWith{field->ensureRead();val column=c.getColumnIndexOrThrow(field);require(c.getType(column)!=Cursor.FIELD_TYPE_BLOB);if(c.isNull(column))null else c.getString(column)})};ensureRead()}}
    }

    private fun material(raw:Raw,command:SeriesCommand.FollowingEdit,uid:String?,issuedEndZone:FieldChange<String?> = FieldChange.Keep):Material {
        // Tests can make actual current planning unavailable after preparation/commit.
        frozenPreparationProbe()
        owned(raw)
        if(raw.master["dtend"]!=null||RecurrenceMasterTemplates.resolve(raw.snapshot) !is RecurrenceMasterTemplateResult.Ready)throw Review("The original recurring interval requires review")
        val selected=requireNotNull(raw.snapshot.validatedSelection)
        if(selected.instanceStart.nano!=0||selected.instanceEnd.nano!=0)throw Review("The selected recurrence requires exact whole-second bounds")
        val actualStart=if(raw.snapshot.start is RecurrenceStart.AllDay)RecurrenceStart.AllDay(selected.instanceStart.atOffset(ZoneOffset.UTC).toLocalDate())else {
            val zone=(raw.snapshot.start as RecurrenceStart.Timed).zone
            val local=selected.instanceStart.atZone(zone);RecurrenceStart.Timed(local.toLocalDateTime(),zone,local.offset)
        }
        val observed=(RecurrenceMasterTemplates.resolve(actualStart,raw.snapshot.durationRaw) as? RecurrenceMasterTemplateResult.Ready)?.template
            ?:throw Review("The selected native interval cannot be preserved by this portable profile")
        if(templateStart(observed.time)!=selected.instanceStart||templateEnd(observed.time)!=selected.instanceEnd)
            throw Review("The native occurrence and stored duration disagree; review is required")
        val plan=RecurrenceEditing.plan(raw.snapshot,selected,command.intent,budget) as? RecurrencePlanningResult.Ready?:throw Review("The original boundary and repeat transformation cannot be proven")
        val rules=RecurrenceFollowingRules.resolve(raw.snapshot,selected,command.intent,plan,budget)
        val id=raw.snapshot.identity.masterEventId
        val prefix=raw.master.filterKeys{it !in Bookkeeping}.toMutableMap()
        var prefixReminders=raw.reminders[id].orEmpty()
        when(rules){
            is RecurrenceFollowingRuleResult.Whole->{
                require(uid==null||uid==previewUid(raw))
                val action=plan.actions.singleOrNull() as? RecurrencePlanAction.UpdateMaster?:throw Review("An ordinal-zero edit requires one master action")
                val start=(action.anchor as? FieldChange.Set)?.value?:raw.snapshot.start
                val template=interval(start,action.duration,raw.snapshot.durationRaw)
                val values=owner.followingChanges(raw.calendar,raw.reminders,raw.master,action.patch)
                if(command.intent.newTailStart!=FieldChange.Keep||command.intent.duration!=RecurrenceDurationChange.KeepRaw||command.intent.rule!=RecurrenceRuleChanges()){
                    val times=nativeTime(raw,start,template,rules.ruleRaw)
                    if(raw.master["eventTimezone"]==times.getAsString("eventTimezone"))times.remove("eventEndTimezone")
                    values.putAll(times)
                }
                values.keySet().forEach{prefix[it]=values.get(it)?.toString()}
                prefixReminders=patchedReminders(raw,action.patch)
                return Material(true,0,start,template,prefix,null,prefixReminders,emptyList(),emptyList())
            }
            is RecurrenceFollowingRuleResult.Tail->{
                if(uid==null||!validUid(uid,raw.master["uid2445"]))throw Review("The independent tail identity is unavailable")
                val action=plan.actions.getOrNull(1) as? RecurrencePlanAction.InsertTail?:throw Review("A split requires a prefix and independent tail")
                if(plan.actions.size!=2||action.exclusions.isNotEmpty())throw Review("Exception transfer is not implemented")
                val prefixAction=plan.actions[0] as? RecurrencePlanAction.UpdateMaster?:throw Review("The prefix action is unavailable")
                if(prefixAction.patch!=EventPatch()||prefixAction.anchor!=FieldChange.Keep||prefixAction.duration!=RecurrenceDurationChange.KeepRaw)throw Review("The prefix may only change its repeat limit")
                prefix["rrule"]=rules.prefixRuleRaw
                val template=interval(rules.tailStart,action.duration,raw.snapshot.durationRaw)
                val tail=copyPublic(raw).apply{putAll(owner.followingChanges(raw.calendar,raw.reminders,raw.master,action.patch));putAll(nativeTime(raw,rules.tailStart,template,rules.tailRuleRaw));put("uid2445",uid)}
                val metadataOnly=command.intent.newTailStart==FieldChange.Keep&&command.intent.duration==RecurrenceDurationChange.KeepRaw&&
                    command.intent.fields.time==FieldChange.Keep&&command.intent.fields.eventZone==FieldChange.Keep
                val canonicalEndZone=tail.getAsString("eventEndTimezone")
                val preserved=if(issuedEndZone is FieldChange.Set)issuedEndZone.value else if(metadataOnly)raw.master["eventEndTimezone"] else canonicalEndZone
                // Legacy prepared material is already issued native authority: accept its exact
                // original/canonical spelling, never silently rebase it onto the new preservation.
                require(if(metadataOnly)preserved==raw.master["eventEndTimezone"]||preserved==canonicalEndZone else preserved==canonicalEndZone)
                if(preserved==null)tail.putNull("eventEndTimezone")else tail.put("eventEndTimezone",preserved)
                validatePublic(tail)
                val reminders=patchedReminders(raw,action.patch).map{it.filterKeys{key->key in ReminderCopy}}
                val attendees=copyChildren(raw,reminders)
                return Material(false,rules.boundaryOrdinal,rules.tailStart,template,prefix,tail.keySet().associateWith{tail.get(it)?.toString()},prefixReminders,reminders,attendees)
            }
            is RecurrenceFollowingRuleResult.NeedsReview->throw Review("The original boundary and raw repeat limits require review")
        }
    }
    private fun issuedTailEndZone(receipt:JSONObject):FieldChange<String?> {
        val material=receipt.getJSONObject("material")
        if(material.getBoolean("whole"))return FieldChange.Keep
        val tail=material.getJSONObject("tail")
        require(tail.has("eventEndTimezone"))
        return FieldChange.Set(if(tail.isNull("eventEndTimezone"))null else tail.get("eventEndTimezone") as? String?:error("Invalid frozen end zone"))
    }

    private fun interval(start:RecurrenceStart,change:RecurrenceDurationChange,raw:String?):RecurrenceMasterTemplate {
        val duration=when(change){RecurrenceDurationChange.KeepRaw->raw;is RecurrenceDurationChange.TimedElapsed->"PT${change.duration.seconds}S";is RecurrenceDurationChange.AllDayDays->"P${change.days}D"}
        return (RecurrenceMasterTemplates.resolve(start,duration) as? RecurrenceMasterTemplateResult.Ready)?.template?:throw Review("The new interval is not compatible with native recurrence storage")
    }
    private fun nativeTime(raw:Raw,start:RecurrenceStart,template:RecurrenceMasterTemplate,rule:String)=ContentValues().apply{
        if((start is RecurrenceStart.AllDay)!=(raw.snapshot.start is RecurrenceStart.AllDay))throw Review("Timed and all-day conversion requires review")
        val zone=if(start is RecurrenceStart.AllDay)"UTC"else template.eventZone.id
        if(raw.master["eventTimezone"]!=zone&&raw.calendar["canModifyTimeZone"]!="1")throw Review("This calendar does not prove permission to change the time zone")
        put("calendar_id",raw.snapshot.identity.calendarId.value);put("dtstart",templateStart(template.time).toEpochMilli());putNull("dtend");put("duration",template.durationRaw)
        put("allDay",if(start is RecurrenceStart.AllDay)1 else 0);put("eventTimezone",zone);put("eventEndTimezone",zone);put("rrule",rule)
    }
    private fun patchedReminders(raw:Raw,patch:EventPatch):List<Map<String,String?>>{
        val original=raw.reminders[raw.snapshot.identity.masterEventId].orEmpty()
        val alerts=(patch.alertMinutes as? FieldChange.Set)?.value?:return original
        return original.filter{it["method"]!="1"}+alerts.map{mapOf("minutes" to it.toString(),"method" to "1")}
    }
    private fun owned(raw:Raw) {
        val c=raw.calendar;val m=raw.master
        if(c["ownerAccount"].isNullOrBlank()||c["ownerAccount"]!=c["account_name"]||m["organizer"]!=c["ownerAccount"]||m["isOrganizer"]!="1"||c["calendar_access_level"]?.toIntOrNull() !in setOf(700,800))
            throw Review("Shared or delegated series require a separately verified copy profile")
    }
    private fun copyPublic(raw:Raw):ContentValues {
        val m=raw.master;val c=raw.calendar
        if(raw.properties.values.any{it.isNotEmpty()}||m["hasExtendedProperties"]!="0")throw Review("Extended properties cannot be preserved by a public tail insert")
        if(!m["customAppPackage"].isNullOrBlank()||!m["customAppUri"].isNullOrBlank()||m["eventColor_index"]!=null)throw Review("Specialized event metadata has no verified public copy policy")
        if(listOf("original_id","original_sync_id","originalInstanceTime","originalAllDay").any{m[it]!=null})throw Review("A detached source cannot become an independent master")
        for((key,value) in m)if(value!=null&&key !in PublicCopy&&key !in KnownNonCopy)throw Review("Provider-specific event data has no verified copy policy")
        return ContentValues().apply{for(key in PublicCopy)if(m.containsKey(key)){val value=m[key];if(value==null)putNull(key)else put(key,value)}}
    }
    private fun validatePublic(values:ContentValues){
        fun number(key:String,range:IntRange){val raw=values.getAsString(key);if(raw!=null&&raw.toIntOrNull() !in range)throw Review("The source contains unsupported public metadata")}
        number("eventStatus",0..1);number("availability",0..2);number("accessLevel",0..3)
        listOf("hasAttendeeData","guestsCanModify","guestsCanInviteOthers","guestsCanSeeGuests").forEach{number(it,0..1)}
        values.getAsString("eventColor")?.let{if(it.toIntOrNull()==null)throw Review("The source event color cannot be copied safely")}
    }
    private fun copyChildren(raw:Raw,reminders:List<Map<String,String?>>):List<Map<String,String?>> {
        val id=raw.snapshot.identity.masterEventId
        val original=raw.reminders[id].orEmpty()
        if(original.any{row->row.any{(key,value)->key !in ReminderCopy&&key !in ChildIdentity&&value!=null}})throw Review("Provider-specific reminder data cannot be cloned")
        if(reminders.isNotEmpty()){
            val allowed=raw.calendar["allowedReminders"]?.split(',')?.map{it.trim().toIntOrNull()?:throw Review("Reminder capabilities are unavailable")}?.toSet()?:throw Review("Reminder capabilities are unavailable")
            val maximum=raw.calendar["maxReminders"]?.toIntOrNull()?:throw Review("Reminder limits are unavailable")
            if(reminders.size>maximum||reminders.any{it["minutes"]?.toIntOrNull()?.let{value->value < -1}!=false||it["method"]?.toIntOrNull() !in setOf(0,1,2,3,4)||it["method"]?.toIntOrNull() !in allowed})throw Review("Stored reminders cannot be copied within the calendar's supported limits")
        }
        val rows=raw.attendees[id].orEmpty()
        if(rows.isEmpty()){if(raw.master["selfAttendeeStatus"]!="0")throw Review("The organizer response has no complete attendee representation");return emptyList()}
        val row=rows.singleOrNull()?:throw Review("A tail with external or duplicate attendees requires review")
        if(row["attendeeEmail"]!=raw.calendar["ownerAccount"]||row["attendeeRelationship"]!="2"||row["attendeeStatus"]?.toIntOrNull() !in 0..4||row["attendeeStatus"]!=raw.master["selfAttendeeStatus"]||
            row.any{(key,value)->key !in AttendeeCopy&&key !in ChildIdentity&&value!=null}||row["attendeeIdentity"]!=null||row["attendeeIdNamespace"]!=null)throw Review("The source self attendee cannot be copied unambiguously")
        val allowed=raw.calendar["allowedAttendeeTypes"]?.split(',')?.map{it.trim().toIntOrNull()?:throw Review("Attendee capabilities are unavailable")}?.toSet()?:throw Review("Attendee capabilities are unavailable")
        if(row["attendeeType"]?.toIntOrNull() !in 0..2||row["attendeeType"]?.toIntOrNull() !in allowed)throw Review("The self attendee type is unsupported")
        return listOf(row.filterKeys{it in AttendeeCopy})
    }

    private fun prefixValues(raw:Raw,model:Material):Map<String,String?> {
        val prefixValues=if(model.whole)model.prefix.filter{(key,value)->raw.master[key]!=value}.toMutableMap()else mutableMapOf("rrule" to model.prefix["rrule"])
        if(!model.whole){
            // Native updateInstancesLocked returns early without DTSTART. Raw-times replacement
            // also consumes only this sparse patch, so retain exact source timezone/all-day facts.
            val context=try{owner.followingContext(raw.master)}catch(review:SeriesSourceReview){throw Review(review.reason)}
            context.keySet().forEach{key->prefixValues[key]=context.get(key)?.toString()}
        }
        // DTEND null is explicit even when the source's native row already had null.
        if(model.whole)for(key in listOf("dtstart","dtend","duration","eventTimezone","eventEndTimezone","allDay","rrule"))prefixValues[key]=model.prefix[key]
        return prefixValues
    }

    private fun operations(source:CalendarSeriesSource,raw:Raw,model:Material):Pair<ArrayList<ContentProviderOperation>,Int?> {
        val id=raw.snapshot.identity.masterEventId;val ops=owner.followingAssertions(source)
        val prefixValues=prefixValues(raw,model)
        ops+=ContentProviderOperation.newUpdate(eventUri(id)).withValues(values(prefixValues)).withExpectedCount(1).withYieldAllowed(false).build()
        if(model.whole){
            val before=raw.reminders[id].orEmpty();if(model.prefixReminders!=before){
                ops+=ContentProviderOperation.newDelete(CalendarContract.Reminders.CONTENT_URI).withSelection("event_id = ? AND method = ?",arrayOf(id.toString(),"1")).withYieldAllowed(false).build()
                model.prefixReminders.filter{!it.containsKey("_id")}.forEach{ops+=ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI).withValues(values(it)).withValue("event_id",id).withYieldAllowed(false).build()}
            }
            ops+=assertRow(eventUri(id),model.prefix)
            assertChildren(ops,id,null,model.prefixReminders,raw.attendees[id].orEmpty(),raw.properties[id].orEmpty())
            return ops to null
        }
        val tailIndex=ops.size
        ops+=ContentProviderOperation.newInsert(CalendarContract.Events.CONTENT_URI).withValues(values(requireNotNull(model.tail))).withYieldAllowed(false).build()
        for((uri,children) in listOf(CalendarContract.Reminders.CONTENT_URI to model.tailReminders,CalendarContract.Attendees.CONTENT_URI to model.tailAttendees))
            children.forEach{ops+=ContentProviderOperation.newInsert(uri).withValues(values(it)).withValueBackReference("event_id",tailIndex).withYieldAllowed(false).build()}
        ops+=assertRow(eventUri(id),model.prefix)
        assertChildren(ops,id,null,model.prefixReminders,raw.attendees[id].orEmpty(),raw.properties[id].orEmpty())
        val tailExpected=requireNotNull(model.tail)+mapOf("deleted" to "0","isOrganizer" to "1","hasAlarm" to if(model.tailReminders.isEmpty())"0" else "1","hasExtendedProperties" to "0",
            "selfAttendeeStatus" to (model.tailAttendees.singleOrNull()?.get("attendeeStatus")?:"0"),"_sync_id" to null,"rdate" to null,"exrule" to null,"exdate" to null,"original_id" to null,"original_sync_id" to null,"originalInstanceTime" to null,"originalAllDay" to null)
        ops+=ContentProviderOperation.newAssertQuery(CalendarContract.Events.CONTENT_URI).withSelection("_id = ?",arrayOf("0")).withSelectionBackReference(0,tailIndex).withValues(values(tailExpected)).withExpectedCount(1).withYieldAllowed(false).build()
        assertChildren(ops,null,tailIndex,model.tailReminders,model.tailAttendees,emptyList())
        return ops to tailIndex
    }
    private fun assertChildren(ops:ArrayList<ContentProviderOperation>,id:Long?,backref:Int?,reminders:List<Map<String,String?>>,attendees:List<Map<String,String?>>,properties:List<Map<String,String?>>){
        for((uri,children) in listOf(CalendarContract.Reminders.CONTENT_URI to reminders,CalendarContract.Attendees.CONTENT_URI to attendees,CalendarContract.ExtendedProperties.CONTENT_URI to properties)){
            fun builder()=ContentProviderOperation.newAssertQuery(uri).withSelection("event_id = ?",arrayOf(id?.toString()?:"0")).apply{if(backref!=null)withSelectionBackReference(0,backref)}
            ops+=builder().apply{if(backref==null)withValue("event_id",id)else withValueBackReference("event_id",backref)}.withExpectedCount(children.size).withYieldAllowed(false).build()
            children.forEach{row->
                if(row.containsKey("_id")){ops+=assertRow(ContentUris.withAppendedId(uri,row.getValue("_id")!!.toLong()),row)}
                else{
                    val fields=row.keys.toList();val selection="event_id = ?"+fields.joinToString(""){" AND $it IS ?"}
                    ops+=ContentProviderOperation.newAssertQuery(uri).withSelection(selection,arrayOf(id?.toString()?:"0",*fields.map{row[it]}.toTypedArray()))
                        .apply{if(backref!=null)withSelectionBackReference(0,backref)}.withValues(values(row)).withExpectedCount(children.count{it==row}).withYieldAllowed(false).build()
                }
            }
        }
    }
    private suspend fun verifyChildren(id:Long,reminders:List<Map<String,String?>>,attendees:List<Map<String,String?>>,properties:List<Map<String,String?>>):Boolean{
        for((uri,expected) in listOf(CalendarContract.Reminders.CONTENT_URI to reminders,CalendarContract.Attendees.CONTENT_URI to attendees,CalendarContract.ExtendedProperties.CONTENT_URI to properties)){
            val fields=(expected.flatMap{it.keys}+listOf("_id","event_id")).distinct().toTypedArray()
            val actual=rows(uri,fields,"event_id = ?",arrayOf(id.toString()),readBudgets.maxSeriesChildren)
            if(actual.size!=expected.size)return false
            val remaining=actual.toMutableList()
            for(row in expected){val match=remaining.indexOfFirst{candidate->row.all{(key,value)->candidate[key]==value}&&candidate["event_id"]==id.toString()};if(match<0)return false;remaining.removeAt(match)}
        };return true
    }
    private suspend fun verifyRow(id:Long,expected:Map<String,String?>):Boolean=rows(eventUri(id),expected.keys.toTypedArray(),maxRows=1).singleOrNull()==expected
    private suspend fun noExceptions(id:Long,sync:String?,calendar:CalendarId):Boolean{
        val sql="deleted = 0 AND (original_id = ?"+if(sync.isNullOrBlank())")"else" OR (calendar_id = ? AND original_sync_id = ?))"
        val args=listOf(id.toString())+if(sync.isNullOrBlank())emptyList()else listOf(calendar.value.toString(),sync)
        if(rows(CalendarContract.Events.CONTENT_URI,arrayOf("_id"),sql,args.toTypedArray(),budget.maxExceptions+1).isNotEmpty())return false
        return !sync.isNullOrBlank()||rows(CalendarContract.Events.CONTENT_URI,arrayOf("_id"),"calendar_id = ? AND original_id IS NULL AND original_sync_id IS NOT NULL AND deleted = 0",arrayOf(calendar.value.toString()),budget.maxExceptions+1).isEmpty()
    }
    private fun proof(operation:CalendarLocalStore.Operation):Pair<JSONObject,Raw>?=try{checkedProof(operation)}catch(_:Exception){null}
    private fun checkedProof(operation:CalendarLocalStore.Operation):Pair<JSONObject,Raw>{
        require(operation.kind==Kind);val receipt=JSONObject(operation.resultRef?:error("Missing preparation"))
        require(receipt.getInt("version")==2&&receipt.getString("feature")==Feature&&receipt.getString("kind")==Kind&&receipt.getString("signature")==operation.signature)
        val raw=decode(receipt.getJSONObject("source").toString());val token=EditToken(receipt.getString("token"))
        require(meetingHash("SERIES_EDIT_V1"+raw.encoded)==token.value)
        val selected=requireNotNull(raw.snapshot.validatedSelection);val command=CalendarSeriesWriter.parseFollowingCommand(raw.snapshot,receipt.getJSONObject("command"))
        require(CalendarSeriesWriter.retirementFingerprint(selected,token,command)==(Kind to operation.signature))
        val model=material(raw,command,receipt.nullable("uid"),issuedTailEndZone(receipt));require(model.json().toString()==receipt.getJSONObject("material").toString())
        if(model.whole)require(receipt.isNull("uid"))
        if(operation.phase in setOf("PREPARED","FAILED"))require(receipt.getString("stage")=="prepared"&&!receipt.has("result"))
        else{require(operation.phase in setOf("COMMITTED","VERIFIED")&&receipt.getString("stage")=="committed");val result=receipt.getLong("result");require(result>0&&if(model.whole)result==raw.snapshot.identity.masterEventId else result!=raw.snapshot.identity.masterEventId)}
        return receipt to raw
    }
    private fun raw(source:CalendarSeriesSource)=Raw(source.canonical(),source.calendar,source.master,source.reminders,source.attendees,source.extendedProperties,source.snapshot)
    private fun decode(encoded:String):Raw{
        checkStoredJson(encoded,readBudgets.maxCanonicalBytes);val json=JSONObject(encoded);require(json.getInt("version")==1&&json.getJSONArray("exceptions").length()==0)
        fun children(key:String):Map<Long,List<Map<String,String?>>>{val list=json.getJSONArray(key);return List(list.length()){list.getJSONObject(it).let{group->val rows=group.getJSONArray("rows");group.getLong("parent") to List(rows.length()){i->meetingRow(rows.getJSONObject(i))}}}.toMap()}
        return Raw(encoded,meetingRow(json.getJSONObject("calendar")),meetingRow(json.getJSONObject("master")),children("reminders"),children("attendees"),children("extendedProperties"),sourceReader.frozenSnapshot(encoded))
    }
    private suspend fun calendar(id:CalendarId):Map<String,String?> {
        val fields=arrayOf("_id","account_name","account_type","ownerAccount","_sync_id","calendar_access_level")
        return try{rows(ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI,id.value),fields,maxRows=1).singleOrNull()}
            catch(_:IllegalArgumentException){rows(ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI,id.value),fields.filter{it!="_sync_id"}.toTypedArray(),maxRows=1).singleOrNull()}?:throw Conflict()
    }
    private fun sameCalendar(expected:Map<String,String?>,actual:Map<String,String?>)=meetingIdentity(expected)==meetingIdentity(actual)&&(actual["calendar_access_level"]?.toIntOrNull()?:-1)>=200
    private suspend fun rows(uri:Uri,fields:Array<String>,selection:String?=null,args:Array<String?>?=null,maxRows:Int):List<Map<String,String?>>{
        ensureRead();val cursor=suspendCancellableCoroutine<Cursor>{c->val signal=CancellationSignal();c.invokeOnCancellation{signal.cancel()};try{val result=resolver.query(uri,fields,selection,args,null,signal)?:error("Provider unavailable");try{ensureRead()}catch(f:Exception){result.close();throw f};c.resume(result,onCancellation={_,resource,_->resource.close()})}catch(f:Exception){c.resumeWithException(f)}}
        return cursor.bounded(maxRows).use{buildList{while(it.moveToNext()){currentCoroutineContext().ensureActive();add(fields.associateWith{field->it.getColumnIndexOrThrow(field).let{index->if(it.isNull(index))null else it.getString(index)}})}}}
    }
    private fun requireCloneProfile(){if(!providerClonePolicy.allows(ProviderCloneOperation.INDEPENDENT_TAIL))throw Review("This provider's independent-tail copy implementation has not been verified")}
    private fun permissions()=hasPermission(Manifest.permission.READ_CALENDAR)&&hasPermission(Manifest.permission.WRITE_CALENDAR)
    private fun ensureRead(){if(!hasPermission(Manifest.permission.READ_CALENDAR))throw SecurityException()}
    companion object{
        private const val Kind="SERIES_V1_FOLLOWING_EDIT"
        private const val Feature="SERIES_FOLLOWING_EDIT_V2"
        fun handles(kind:String)=kind==Kind
        private val Bookkeeping=setOf("dirty","mutators","lastDate","lastSynced")
        private val PublicCopy=setOf("title","description","eventLocation","eventStatus","eventColor","availability","accessLevel","organizer","hasAttendeeData","guestsCanModify","guestsCanInviteOthers","guestsCanSeeGuests")
        // Pinned AOSP16 77bed8f9 Events view: joined Calendar fields survive through the same
        // calendar, not through copying them into a new Events row. Opaque event sync data do not.
        private val KnownNonCopy=Bookkeeping+setOf("_id","calendar_id","_sync_id","uid2445","dtstart","dtend","duration","rrule","rdate","exrule","exdate","allDay","eventTimezone","eventEndTimezone",
            "original_id","original_sync_id","originalInstanceTime","originalAllDay","deleted","hasAlarm","hasExtendedProperties","selfAttendeeStatus","isOrganizer","displayColor","customAppPackage","customAppUri","eventColor_index",
            "account_name","account_type","calendar_color","calendar_color_index","calendar_access_level","visible","calendar_timezone","ownerAccount","calendar_displayName","allowedReminders","allowedAttendeeTypes","allowedAvailability","maxReminders","canOrganizerRespond","canModifyTimeZone","isPrimary")+(1..10).map{"cal_sync$it"}
        private val ReminderCopy=setOf("minutes","method")
        private val AttendeeCopy=setOf("attendeeName","attendeeEmail","attendeeRelationship","attendeeType","attendeeStatus")
        private val ChildIdentity=setOf("_id","event_id")
        private val InstanceFields=arrayOf("event_id","calendar_id","begin","end","allDay","original_id","original_sync_id","originalInstanceTime")
        private fun JSONObject.nullable(key:String):String?=if(isNull(key))null else getString(key)
        private fun values(row:Map<String,String?>)=ContentValues().apply{row.forEach{(key,value)->if(value==null)putNull(key)else put(key,value)}}
        private fun eventUri(id:Long)=ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI,id)
        private fun assertRow(uri:Uri,row:Map<String,String?>)=ContentProviderOperation.newAssertQuery(uri).withValues(values(row)).withExpectedCount(1).withYieldAllowed(false).build()
        private fun normalInsertId(results:Array<ContentProviderResult>,index:Int):Long=CalendarCreateAcknowledgments.insertId(results.getOrNull(index)?.uri,"events")
        private fun templateStart(time:EventTime)=when(time){is EventTime.Timed->time.start;is EventTime.AllDay->time.dates.start.atStartOfDay(ZoneOffset.UTC).toInstant()}
        private fun templateEnd(time:EventTime)=when(time){is EventTime.Timed->time.end;is EventTime.AllDay->time.dates.endExclusive.atStartOfDay(ZoneOffset.UTC).toInstant()}
        private fun previewUid(raw:Raw)=if(raw.master["uid2445"]!="00000000-0000-4000-8000-000000000001")"00000000-0000-4000-8000-000000000001"else"00000000-0000-4000-8000-000000000002"
        private fun validUid(uid:String,old:String?)=runCatching{UUID.fromString(uid).toString()==uid&&!uid.equals(old,true)}.getOrDefault(false)
        private fun newUid(raw:Raw):String{repeat(4){val value=UUID.randomUUID().toString();if(validUid(value,raw.master["uid2445"]))return value};throw Review("An independent tail identity could not be allocated")}
        private fun checkParcel(ops:List<ContentProviderOperation>){val p=Parcel.obtain();try{p.writeTypedList(ops);if(p.dataSize()>256*1024)throw Review("The complete split exceeds the safe local transaction budget")}finally{p.recycle()}}
        private fun common(result:MutationResult)=SeriesMutationResult.Common(result)
    }
}
