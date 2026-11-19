package dev.swapnilraj.calendar.data.calendar

import android.Manifest
import android.app.Application
import android.content.*
import android.database.Cursor
import android.database.CursorWrapper
import android.net.Uri
import android.provider.CalendarContract
import dev.swapnilraj.calendar.SyntheticCloneProviderPolicy
import dev.swapnilraj.calendar.core.*
import java.time.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class FollowingExecutionDispatchTest {
    private enum class Ack { GOOD,MASTER_SCHEME,MASTER_QUERY,MASTER_ZERO,CHILD_MALFORMED,CHILD_MISSING,CHILD_REORDERED,CHILD_DUPLICATE,ATTENDEE_MALFORMED,ASSERT_COUNT,UPDATE_COUNT,DELETE_COUNT,EXTRA }
    private val start=Instant.parse("2028-01-03T09:00:00Z")
    private fun common(r:MutationResult)=SeriesMutationResult.Common(r)
    @Test fun wrongSchemeMasterNeverBecomesKnownOrReplayable()=bad(Ack.MASTER_SCHEME,master=true)
    @Test fun queriedMasterNeverBecomesKnownOrReplayable()=bad(Ack.MASTER_QUERY,master=true)
    @Test fun zeroMasterNeverBecomesKnownOrReplayable()=bad(Ack.MASTER_ZERO,master=true)
    @Test fun malformedReminderAckRetainsCommitWithoutGuessingChildIdentity()=bad(Ack.CHILD_MALFORMED)
    @Test fun missingReminderAckRetainsCommitWithoutGuessingChildIdentity()=bad(Ack.CHILD_MISSING)
    @Test fun reorderedReminderAcksCannotBorrowPayloadMultisetProof()=bad(Ack.CHILD_REORDERED)
    @Test fun duplicateReminderAcksNeverBecomeDistinctKnownChildren()=bad(Ack.CHILD_DUPLICATE)
    @Test fun malformedOrganizerAttendeeAckIsNotPayloadReadbackAuthority()=bad(Ack.ATTENDEE_MALFORMED,attendee=true)
    @Test fun forgedAssertionCountCannotClaimSave()=bad(Ack.ASSERT_COUNT)
    @Test fun forgedPrefixUpdateCountCannotClaimSave()=bad(Ack.UPDATE_COUNT)
    @Test fun forgedWholeAlertDeleteCountCannotClaimSave()=bad(Ack.DELETE_COUNT,whole=true)
    @Test fun extraResultCannotBeIgnored()=bad(Ack.EXTRA)
    private fun bad(mode:Ack,master:Boolean=false,whole:Boolean=false,attendee:Boolean=false)=runBlocking{fixture(whole,attendee){f->
        val input=f.command(alerts=if(whole)listOf(15)else null);f.p.ack=mode
        val result=f.family.mutate("change",f.selected,f.edit.token,input)
        val known=if(master)null else if(whole)1L else 2L
        assertEquals(common(MutationResult.Uncertain("change",known)),result)
        assertEquals(if(master)"ATTEMPTED"else"COMMITTED",f.store.operation("change")!!.phase)
        assertFalse(JSONObject(f.store.operation("change")!!.resultRef!!).has("ref"))
        f.permissions.remove(Manifest.permission.WRITE_CALENDAR)
        assertEquals(common(MutationResult.Uncertain("change",known)),f.family.recover("change"))
        f.permissions+=Manifest.permission.WRITE_CALENDAR
        assertEquals(common(MutationResult.Uncertain("change",known)),f.family.mutate("change",f.selected,f.edit.token,input));assertEquals(1,f.p.base.batchCount)
    }}
    @Test fun rollbackRetryReusesFrozenSourceUidAndNativePlanWithoutAuthoringOrSnapshot()=runBlocking{fixture{f->
        val input=f.command();f.p.base.failReminder=true
        assertEquals(common(MutationResult.Unavailable),f.family.mutate("change",f.selected,f.edit.token,input))
        val before=f.store.operation("change")!!;val receipt=FollowingExecutionReceipt.parse(before,1024*1024).second
        assertEquals("FAILED",before.phase);f.p.base.failReminder=false
        f.store.writableDatabase.execSQL("DELETE FROM edit_snapshots")
        f.family.frozenPreparationProbe={error("Current preparation must not run for frozen retry")}
        assertEquals(common(MutationResult.SafeToRetry("change")),f.family.recover("change"))
        f.p.base.queries.clear()
        val saved=f.family.mutate("change",f.selected,f.edit.token,input) as SeriesMutationResult.LocalSaved
        val properties=f.p.base.queries.filter{it.path=="extendedproperties"}
        assertTrue("Empty property sources need only absence/count fields",properties.isNotEmpty()&&properties.all{q->q.fields!=null&&q.fields.none{it=="name"||it=="value"}})
        assertEquals(receipt.uid,f.p.value(saved.identity.masterEventId,"uid2445"));assertEquals(receipt.tail!!["eventEndTimezone"],f.p.value(saved.identity.masterEventId,"eventEndTimezone"));assertEquals(2,f.p.base.batchCount)
    }}
    @Test fun knownCommitReopensReadOnlyWithoutSnapshotsCompilerOrFreshCloneAdmission()=runBlocking{fixture{f->
        val input=f.command();f.p.base.failReadsAfterCommit=true
        assertEquals(common(MutationResult.Uncertain("change",2)),f.family.mutate("change",f.selected,f.edit.token,input))
        assertEquals("COMMITTED",f.store.operation("change")!!.phase);f.p.base.failReadsAfterCommit=false
        f.permissions.remove(Manifest.permission.WRITE_CALENDAR);f.store.writableDatabase.execSQL("DELETE FROM edit_snapshots")
        val reopened=CalendarLocalStore(f.context,f.store.databaseName)
        try{val family=f.family(reopened,ProviderClonePolicy.Unverified);family.frozenPreparationProbe={error("No current authoring for known recovery")}
            val saved=family.recover("change") as SeriesMutationResult.LocalSaved
            assertEquals(2L,saved.identity.masterEventId);assertEquals(f.selected.instanceStart,saved.selected!!.instanceStart)
            assertEquals(saved,family.recover("change"));assertEquals(1,f.p.base.batchCount)
        }finally{reopened.close()}
    }}
    @Test fun prefixAndTailUidReplacementStopBeforePrivatePayloadCells()=runBlocking {
        for(target in listOf(1L,2L))fixture{f->
            assertTrue(f.family.mutate("change",f.selected,f.edit.token,f.command()) is SeriesMutationResult.LocalSaved)
            f.p.base.db.execSQL("UPDATE events SET uid2445='replacement' WHERE _id=?",arrayOf(target));f.p.privateCells.clear()
            assertEquals(common(MutationResult.Uncertain("change",null)),f.family.recover("change"))
            assertTrue("No private payload before issued identities bind: ${f.p.privateCells}",f.p.privateCells.isEmpty());assertEquals(1,f.p.base.batchCount)
        }
    }
    @Test fun unissuedEmptySyncIsObservedExactlyWithoutInventingAStableSyncIdentity()=runBlocking{fixture{f->
        assertTrue(f.family.mutate("change",f.selected,f.edit.token,f.command()) is SeriesMutationResult.LocalSaved)
        f.p.base.db.execSQL("UPDATE events SET _sync_id='' WHERE _id=2")
        assertTrue(f.family.recover("change") is SeriesMutationResult.LocalSaved)
        assertEquals("",f.p.value(2,"_sync_id"));assertTrue(JSONObject(f.store.operation("change")!!.resultRef!!).isNull("tailSync"))
        assertEquals(1,f.p.base.batchCount)
    }}
    @Test fun stickySyncDisagreementKeepsUidKnownWithoutPayloadOrProofRewriteAndChangedUidDoesNot()=runBlocking{fixture{f->
        assertTrue(f.family.mutate("change",f.selected,f.edit.token,f.command()) is SeriesMutationResult.LocalSaved)
        val issuedUid=f.p.value(2,"uid2445")
        f.p.base.db.execSQL("UPDATE events SET _sync_id='learned-tail-sync' WHERE _id=2")
        assertTrue(f.family.recover("change") is SeriesMutationResult.LocalSaved)
        val proof=f.store.operation("change")!!
        assertEquals("learned-tail-sync",JSONObject(proof.resultRef!!).getString("tailSync"))
        f.p.base.db.execSQL("UPDATE events SET _sync_id=NULL WHERE _id=2");f.p.privateCells.clear()
        assertEquals(common(MutationResult.Uncertain("change",2)),f.family.recover("change"))
        assertEquals(issuedUid,f.p.value(2,"uid2445"));assertTrue(f.p.privateCells.toString(),f.p.privateCells.isEmpty());assertEquals(proof,f.store.operation("change"))
        f.p.base.db.execSQL("UPDATE events SET uid2445='replacement',_sync_id='learned-tail-sync' WHERE _id=2");f.p.privateCells.clear()
        assertEquals(common(MutationResult.Uncertain("change",null)),f.family.recover("change"))
        assertTrue(f.p.privateCells.toString(),f.p.privateCells.isEmpty());assertEquals(proof,f.store.operation("change"));assertEquals(1,f.p.base.batchCount)
    }}
    @Test fun identityDriftAfterMinimalBindingSuppressesKnownResultBeforeParentPayload()=runBlocking{fixture{f->
        assertTrue(f.family.mutate("change",f.selected,f.edit.token,f.command()) is SeriesMutationResult.LocalSaved)
        f.p.privateCells.clear()
        f.p.afterIdentityRead={id->if(id=="1"){f.p.afterIdentityRead=null;f.p.base.db.execSQL("UPDATE events SET uid2445='replacement' WHERE _id=1")}}
        assertEquals(common(MutationResult.Uncertain("change",null)),f.family.recover("change"))
        assertTrue(f.p.privateCells.toString(),f.p.privateCells.isEmpty());assertEquals(1,f.p.base.batchCount)
    }}
    @Test fun namespaceDriftAtGuardedParentMismatchSuppressesKnownResult()=runBlocking{fixture{f->
        assertTrue(f.family.mutate("change",f.selected,f.edit.token,f.command()) is SeriesMutationResult.LocalSaved)
        f.p.privateCells.clear()
        f.p.afterIdentityRead={id->if(id=="1"){f.p.afterIdentityRead=null;f.p.base.setCalendar("_sync_id","replacement-calendar");f.p.base.db.execSQL("UPDATE events SET title='Changed externally' WHERE _id=1")}}
        assertEquals(common(MutationResult.Uncertain("change",null)),f.family.recover("change"))
        assertTrue(f.p.privateCells.none{it.startsWith("reminders.")||it.startsWith("attendees.")||it.startsWith("extendedproperties.")});assertEquals(1,f.p.base.batchCount)
    }}
    @Test fun identityDriftWhenInstancesAreUnavailableCannotRetainKnownResult()=runBlocking{fixture{f->
        assertTrue(f.family.mutate("change",f.selected,f.edit.token,f.command()) is SeriesMutationResult.LocalSaved)
        f.p.base.beforeQuery={path->if(path=="instances"){f.p.base.beforeQuery=null;f.p.base.db.execSQL("UPDATE events SET uid2445='replacement' WHERE _id=2");f.p.base.delayedInstances=true}}
        assertEquals(common(MutationResult.Uncertain("change",null)),f.family.recover("change"));assertEquals(1,f.p.base.batchCount)
    }}
    @Test fun simultaneousCandidatesUseOnlyWinningUidAndSingleNativeBatch()=runBlocking{fixture{f->
        val otherStore=CalendarLocalStore(f.context,f.store.databaseName);val other=f.family(otherStore)
        val barrier=CountDownLatch(2);val prepare={barrier.countDown();check(barrier.await(5,TimeUnit.SECONDS));Unit}
        f.family.frozenCandidateReady=prepare;other.frozenCandidateReady=prepare
        try{val input=f.command();val results=withTimeout(15000){listOf(async(Dispatchers.Default){f.family.mutate("change",f.selected,f.edit.token,input)},async(Dispatchers.Default){other.mutate("change",f.selected,f.edit.token,input)}).awaitAll()}
            assertTrue(results.toString(),results.any{it is SeriesMutationResult.LocalSaved});assertEquals(1,f.p.base.batchCount)
            val receipt=FollowingExecutionReceipt.parse(f.store.operation("change")!!,1024*1024).second
            assertEquals(receipt.uid,f.p.value(2,"uid2445"));assertTrue(f.family.recover("change") is SeriesMutationResult.LocalSaved)
        }finally{otherStore.close()}
    }}
    @Test fun retirementDuringPreparedSourceCheckFencesAttemptWithoutProviderWrite()=runBlocking{fixture{f->
        val input=f.command();val retirement=CalendarOperationRetirement(f.store)
        f.p.base.beforeQuery={path->if(path=="calendars"&&f.store.operationHeader("change")?.phase=="PREPARED"){
            f.p.base.beforeQuery=null;assertEquals(OperationRetirementResult.Retired("change"),runBlocking{retirement.retire(RetirableOperation.Series("change",f.selected,f.edit.token,input))})}}
        assertEquals(common(MutationResult.Retired("change")),f.family.mutate("change",f.selected,f.edit.token,input));assertEquals(0,f.p.base.batchCount)
        f.permissions.clear();assertEquals(common(MutationResult.Retired("change")),f.family.recover("change"))
    }}
    @Test fun cancellationOfPreparedSourceCheckKeepsExactPlanAndNoBatch()=runBlocking{fixture{f->
        lateinit var task:Deferred<SeriesMutationResult>;val input=f.command()
        f.p.base.beforeQuery={path->if(path=="calendars"&&f.store.operationHeader("change")?.phase=="PREPARED")task.cancel()}
        task=async(start=CoroutineStart.LAZY){f.family.mutate("change",f.selected,f.edit.token,input)};task.start();task.join()
        assertTrue(task.isCancelled);assertEquals("PREPARED",f.store.operation("change")!!.phase);assertEquals(0,f.p.base.batchCount)
        val before=f.store.operation("change")!!.resultRef;f.p.base.beforeQuery=null
        f.family.frozenPreparationProbe={error("Retained plan is authoritative")}
        assertTrue(f.family.mutate("change",f.selected,f.edit.token,input) is SeriesMutationResult.LocalSaved)
        assertEquals(JSONObject(before!!).getString("planDigest"),JSONObject(f.store.operation("change")!!.resultRef!!).getString("planDigest"));assertEquals(1,f.p.base.batchCount)
    }}
    @Test fun anonymousSplitNeedsReviewButAnonymousWholeRetainsInPlaceEligibility()=runBlocking {
        for(whole in listOf(false,true))fixture(whole){f->
            // Refresh the issued source after removing identities; no prefix UID is authored.
            f.p.base.db.execSQL("UPDATE events SET uid2445=NULL,_sync_id=NULL WHERE _id=1");f.edit=(f.owner.loadForEdit(f.selected) as SeriesReadResult.Ready).series
            val result=f.family.mutate("change",f.selected,f.edit.token,f.command())
            if(whole){assertTrue(result.toString(),result is SeriesMutationResult.LocalSaved);assertNull(f.p.value(1,"uid2445"));assertEquals(1,f.p.base.batchCount)}
            else{assertTrue(result.toString(),result is SeriesMutationResult.PlanningReview);assertNull(f.store.operation("change"));assertEquals(0,f.p.base.batchCount)}
        }
    }

    @Test fun recoverAndRetrySerializeWithoutNestedMutexOrSecondCommit()=runBlocking{fixture{f->
        val input=f.command();f.p.base.failReminder=true;f.family.mutate("change",f.selected,f.edit.token,input);f.p.base.failReminder=false
        val results=withTimeout(10000){listOf(async{f.family.recover("change")},async{f.family.mutate("change",f.selected,f.edit.token,input)}).awaitAll()}
        assertTrue(results.toString(),results.any{it is SeriesMutationResult.LocalSaved});assertEquals(2,f.p.base.batchCount);assertEquals(2,f.p.base.count("events"))
    }}
    @Test fun changedOriginalSourceCannotRebasePreparedNativePlanOrUid()=runBlocking{fixture{f->
        val input=f.command();f.p.base.failReminder=true;f.family.mutate("change",f.selected,f.edit.token,input);f.p.base.failReminder=false
        val before=f.store.operation("change")!!;f.p.base.db.execSQL("UPDATE events SET description='Changed externally' WHERE _id=1")
        assertEquals(common(MutationResult.Conflict(null)),f.family.mutate("change",f.selected,f.edit.token,input))
        assertEquals(before,f.store.operation("change"));assertEquals(1,f.p.base.batchCount);assertEquals(1,f.p.base.count("events"))
    }}
    @Test fun insufficientCompleteReadBudgetRejectsBeforePreparationOrBatch()=runBlocking{fixture{f->
        val family=f.family(budgets=ProviderReadBudgets(mutation=ProviderReadLimits(99,250000,4*1024*1024)))
        assertTrue(family.mutate("change",f.selected,f.edit.token,f.command()) is SeriesMutationResult.PlanningReview)
        assertNull(f.store.operation("change"));assertEquals(0,f.p.base.batchCount);assertEquals(1,f.p.base.count("events"))
    }}
    private fun legacy(r:FollowingExecutionReceipt,phase:String,result:Long?=null):String {
        require(r.master["exdate"].isNullOrEmpty())
        val historicalTail=r.tail?.filterKeys{it!="exdate"}
        val material=JSONObject().put("whole",r.whole).put("ordinal",r.ordinal).put("prefix",CreateSixJson.row(r.prefix)).put("tail",historicalTail?.let(CreateSixJson::row)?:JSONObject.NULL)
            .put("prefixReminders",FollowingJson.rows(r.prefixReminders)).put("tailReminders",FollowingJson.rows(r.tailReminders)).put("tailAttendees",FollowingJson.rows(r.tailAttendees))
        return JSONObject().put("version",2).put("feature","SERIES_FOLLOWING_EDIT_V2").put("kind",FollowingExecutionReceipt.Kind).put("signature",r.signature)
            .put("token",r.token).put("command",r.command).put("source",r.source).put("uid",r.uid?:JSONObject.NULL).put("material",material).put("stage",if(phase in setOf("PREPARED","FAILED","ATTEMPTED"))"prepared"else"committed")
            .apply{result?.let{put("result",it)}}.toString()
    }
    @Test fun historicalKnownCommitNeverGainsInventedAckProofOrCurrentPlannerSuccess()=runBlocking{fixture{f->
        val input=f.command();assertTrue(f.family.mutate("change",f.selected,f.edit.token,input) is SeriesMutationResult.LocalSaved)
        val r=FollowingExecutionReceipt.parse(f.store.operation("change")!!,1024*1024).second
        val old=legacy(r,"COMMITTED",2);f.store.forceOperationForTest("change","COMMITTED",old)
        f.family.frozenPreparationProbe={error("Historical known commits must not invoke current planning")}
        f.p.privateCells.clear();f.permissions.remove(Manifest.permission.WRITE_CALENDAR)
        assertEquals(common(MutationResult.Uncertain("change",2)),f.family.recover("change"))
        assertEquals(common(MutationResult.Uncertain("change",2)),f.family.mutate("change",f.selected,f.edit.token,input))
        assertEquals(old,f.store.operation("change")!!.resultRef);assertEquals("COMMITTED",f.store.operation("change")!!.phase)
        assertTrue(f.p.privateCells.toString(),f.p.privateCells.isEmpty());assertEquals(1,f.p.base.batchCount)
    }}
    @Test fun explicitLegacyRetryRetainsOldBytesAndCanonicalizesItsNewMasterAck()=runBlocking{fixture{f->
        val input=f.command();f.p.base.failReminder=true;assertEquals(common(MutationResult.Unavailable),f.family.mutate("change",f.selected,f.edit.token,input));f.p.base.failReminder=false
        val r=FollowingExecutionReceipt.parse(f.store.operation("change")!!,1024*1024).second
        val old=legacy(r,"FAILED");f.store.forceOperationForTest("change","FAILED",old)
        f.p.base.beforeBatch={assertEquals(old,f.store.operation("change")!!.resultRef);assertEquals("ATTEMPTED",f.store.operation("change")!!.phase)}
        f.p.ack=Ack.MASTER_SCHEME
        assertEquals(common(MutationResult.Uncertain("change",null)),f.family.mutate("change",f.selected,f.edit.token,input))
        assertEquals("ATTEMPTED",f.store.operation("change")!!.phase);assertEquals(old,f.store.operation("change")!!.resultRef)
        assertEquals(common(MutationResult.Uncertain("change",null)),f.family.recover("change"));assertEquals(2,f.p.base.batchCount)
    }}

    private class Provider:ContentProvider(){
        val base=CalendarRecurringCreateTest.Provider().apply{nativeMeetingMode=true;expandInstances=true;allowUpdates=true;db.execSQL("ALTER TABLE calendars ADD COLUMN allowedAttendeeTypes TEXT DEFAULT '0,1,2'")}
        var armed=false;var ack=Ack.GOOD;var afterIdentityRead:((String?)->Unit)?=null;val privateCells=mutableListOf<String>()
        fun value(id:Long,key:String):String?=base.db.query("events",arrayOf(key),"_id=?",arrayOf(id.toString()),null,null,null).use{check(it.moveToFirst());it.getString(0)}
        override fun onCreate()=true
        override fun getType(uri:Uri):String?=null
        override fun query(uri:Uri,fields:Array<out String>?,where:String?,args:Array<out String>?,sort:String?):Cursor {
            val cursor=base.query(uri,fields,where,args,sort);val path=uri.pathSegments.first()
            return object:CursorWrapper(cursor){
                private fun access(column:Int){val key=getColumnName(column)
                    if(path=="events"&&key !in setOf("_id","calendar_id","uid2445","_sync_id"))privateCells+="$path.$key"
                    if(path=="instances"&&key !in setOf("event_id","calendar_id"))privateCells+="$path.$key"
                    if(path in setOf("reminders","attendees","extendedproperties")&&key !in setOf("_id","event_id"))privateCells+="$path.$key"
                }
                override fun getString(column:Int):String?{access(column);val result=super.getString(column)
                    if(path=="events"&&fields?.toList()==listOf("_id","calendar_id","uid2445","_sync_id")&&getColumnName(column)=="_sync_id")afterIdentityRead?.invoke(super.getString(getColumnIndexOrThrow("_id")))
                    return result}
                override fun getLong(column:Int):Long{access(column);return super.getLong(column)}
                override fun getInt(column:Int):Int{access(column);return super.getInt(column)}
            }
        }
        override fun insert(uri:Uri,values:ContentValues?)=base.insert(uri,values)
        override fun update(uri:Uri,values:ContentValues?,where:String?,args:Array<out String>?)=base.update(uri,values,where,args)
        override fun delete(uri:Uri,where:String?,args:Array<out String>?)=base.delete(uri,where,args)
        override fun applyBatch(ops:ArrayList<ContentProviderOperation>):Array<ContentProviderResult>{
            val results=base.applyBatch(ops);if(!armed)return results
            val master=results.indexOfFirst{it.uri?.pathSegments?.firstOrNull()=="events"}
            val children=results.indices.filter{results[it].uri?.pathSegments?.firstOrNull()=="reminders"}
            when(ack){
                Ack.GOOD->Unit
                Ack.MASTER_SCHEME->results[master]=ContentProviderResult(Uri.parse("https://com.android.calendar/events/2"))
                Ack.MASTER_QUERY->results[master]=ContentProviderResult(Uri.parse("content://com.android.calendar/events/2?q=1"))
                Ack.MASTER_ZERO->results[master]=ContentProviderResult(Uri.parse("content://com.android.calendar/events/0"))
                Ack.CHILD_MALFORMED->results[children.first()]=ContentProviderResult(Uri.parse("content://com.android.calendar/reminders/no"))
                Ack.CHILD_MISSING->return results.filterIndexed{index,_->index!=children.last()}.toTypedArray()
                Ack.CHILD_REORDERED->{val a=results[children[0]];results[children[0]]=results[children[1]];results[children[1]]=a}
                Ack.CHILD_DUPLICATE->results[children[1]]=results[children[0]]
                Ack.ATTENDEE_MALFORMED->{val index=results.indexOfFirst{it.uri?.pathSegments?.firstOrNull()=="attendees"};results[index]=ContentProviderResult(Uri.parse("content://com.android.calendar/attendees/no"))}
                Ack.ASSERT_COUNT->results[0]=ContentProviderResult(0)
                Ack.UPDATE_COUNT->results[ops.indexOfFirst{it.isUpdate}]=ContentProviderResult(0)
                Ack.DELETE_COUNT->results[ops.indexOfFirst{it.isDelete}]=ContentProviderResult(0)
                Ack.EXTRA->return results+ContentProviderResult(1)
            };return results
        }
    }
    private inner class Fixture(val context:Application,val p:Provider,val store:CalendarLocalStore,val scope:CoroutineScope,val permissions:MutableSet<String>,val selected:EventRef){
        val reader=CalendarReader(context.contentResolver,scope,stopTimeoutMillis=0)
        val source=CalendarSeriesReader(context.contentResolver,reader,store,permissions::contains)
        val owner=CalendarSeriesWriter(context.contentResolver,source,store,permissions::contains,providerClonePolicy=SyntheticCloneProviderPolicy)
        lateinit var edit:EditableSeries
        fun family(s:CalendarLocalStore=store,policy:ProviderClonePolicy=SyntheticCloneProviderPolicy,budgets:ProviderReadBudgets=ProviderReadBudgets())=CalendarFollowingSeriesWriter(context.contentResolver,CalendarSeriesReader(context.contentResolver,reader,s,permissions::contains),s,permissions::contains,owner,Dispatchers.Default,RecurrencePlanningBudget(),budgets,policy)
        val family=family()
        fun command(alerts:List<Int>?=null)=SeriesCommand.FollowingEdit(RecurrenceEditIntent.FollowingSeriesEdit(RecurrenceKey.Timed(requireNotNull(selected.recurrence).originalStart),EventPatch(title=FieldChange.Set("Synthetic tail"),alertMinutes=alerts?.let{FieldChange.Set(it)}?:FieldChange.Keep)))
    }
    private suspend fun fixture(whole:Boolean=false,attendee:Boolean=false,block:suspend(Fixture)->Unit){
        val context=RuntimeEnvironment.getApplication();val p=Provider();ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY,p)
        val store=CalendarLocalStore(context,"following3-${UUID.randomUUID()}.db");val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val permissions=mutableSetOf(Manifest.permission.READ_CALENDAR,Manifest.permission.WRITE_CALENDAR)
        try{
            val reader=CalendarReader(context.contentResolver,scope,stopTimeoutMillis=0);val ordinary=CalendarWriter(context.contentResolver,reader,store,permissions::contains)
            val identity=CalendarIdentity(CalendarId(2),CalendarAccount("owner@example.invalid","com.google"),"owner@example.invalid","calendar-A")
            val seed=ordinary.create("seed",EventDraft(CalendarId(2),EventTime.Timed(start,start.plusSeconds(3600)),ZoneId.of("Europe/London"),"Synthetic prefix","Synthetic room","Synthetic notes",listOf(10,30),targetIdentity=identity,recurrence=RecurrenceSpec(RecurrencePattern.Daily,end=RecurrenceEnd.Count(8)))) as MutationResult.LocalSaved
            p.base.db.execSQL("UPDATE events SET uid2445='prefix-issued',_sync_id='prefix-sync',eventEndTimezone=NULL WHERE _id=1")
            if(attendee){p.base.db.execSQL("INSERT INTO attendees(event_id,attendeeName,attendeeEmail,attendeeStatus,attendeeRelationship,attendeeType) VALUES(1,'Synthetic owner','owner@example.invalid',1,2,0)");p.base.db.execSQL("UPDATE events SET selfAttendeeStatus=1 WHERE _id=1")}
            val selected=seed.ref.copy(instanceStart=start.plusSeconds(if(whole)0L else 172800L),instanceEnd=start.plusSeconds((if(whole)0L else 172800L)+3600L),recurrence=RecurrenceRef(1,start.plusSeconds(if(whole)0L else 172800L)))
            val f=Fixture(context,p,store,scope,permissions,selected);f.edit=(f.owner.loadForEdit(selected) as SeriesReadResult.Ready).series
            p.armed=true;p.base.batchCount=0;p.base.committed=false;p.privateCells.clear();block(f)
        }finally{scope.cancel();scope.coroutineContext[Job]!!.join();store.close();p.base.db.close();context.deleteDatabase(store.databaseName)}
    }
}
