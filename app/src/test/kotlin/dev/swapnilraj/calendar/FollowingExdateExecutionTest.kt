package dev.swapnilraj.calendar.data.calendar

import android.Manifest
import android.content.ContentValues
import android.provider.CalendarContract
import dev.swapnilraj.calendar.SyntheticCloneProviderPolicy
import dev.swapnilraj.calendar.core.*
import java.time.*
import java.util.UUID
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
@Config(sdk=[35])
class FollowingExdateExecutionTest {
    private val start=Instant.parse("2028-01-03T09:00:00Z")
    private fun common(r:MutationResult)=SeriesMutationResult.Common(r)
    private inner class Fixture(val p:FollowingSqlProvider,val store:CalendarLocalStore,val scope:CoroutineScope,val permissions:MutableSet<String>,val selected:EventRef) {
        val context=RuntimeEnvironment.getApplication()
        val reader=CalendarReader(context.contentResolver,scope,stopTimeoutMillis=0)
        val source=CalendarSeriesReader(context.contentResolver,reader,store,permissions::contains)
        val owner=CalendarSeriesWriter(context.contentResolver,source,store,permissions::contains,providerClonePolicy=SyntheticCloneProviderPolicy)
        val family=family()
        fun family(local:CalendarLocalStore=store,policy:ProviderClonePolicy=SyntheticCloneProviderPolicy,budgets:ProviderReadBudgets=ProviderReadBudgets())=CalendarFollowingSeriesWriter(context.contentResolver,CalendarSeriesReader(context.contentResolver,reader,local,permissions::contains),local,permissions::contains,owner,Dispatchers.Default,RecurrencePlanningBudget(),budgets,policy)
        suspend fun input(patch:EventPatch=EventPatch(title=FieldChange.Set("Synthetic changed")),rule:RecurrenceRuleChanges=RecurrenceRuleChanges()):Pair<EditToken,SeriesCommand.FollowingEdit> {
            val edit=(owner.loadForEdit(selected) as SeriesReadResult.Ready).series
            val key=if(p.value("allDay")=="1")RecurrenceKey.Date(selected.instanceStart.atOffset(ZoneOffset.UTC).toLocalDate())else RecurrenceKey.Timed(selected.instanceStart)
            return edit.token to SeriesCommand.FollowingEdit(RecurrenceEditIntent.FollowingSeriesEdit(key,patch,rule=rule))
        }
        suspend fun save(id:String="change"):SeriesMutationResult {val(t,c)=input();return family.mutate(id,selected,t,c)}
        fun value(id:Long,key:String):String?=p.db.query("events",arrayOf(key),"_id=?",arrayOf(id.toString()),null,null,null).use{check(it.moveToFirst());it.getString(0)}
        fun receipt(id:String="change")=JSONObject(store.operation(id)!!.resultRef!!)
    }
    private suspend fun fixture(raw:String?=null,allDay:Boolean=false,ordinal:Int=2,rule:String="FREQ=DAILY;COUNT=8",block:suspend(Fixture)->Unit) {
        val context=RuntimeEnvironment.getApplication();val p=FollowingSqlProvider().apply{expandInstances=true;allowUpdates=true;db.execSQL("ALTER TABLE calendars ADD COLUMN allowedAttendeeTypes TEXT DEFAULT '0,1,2'")}
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY,p)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val store=CalendarLocalStore(context,"exdate4-${UUID.randomUUID()}.db")
        val permissions=mutableSetOf(Manifest.permission.READ_CALENDAR,Manifest.permission.WRITE_CALENDAR)
        try {
            val seedStart=if(allDay)Instant.parse("2028-01-03T00:00:00Z")else start
            val time=if(allDay)EventTime.AllDay(CalendarDateRange(LocalDate.of(2028,1,3),LocalDate.of(2028,1,4)))else EventTime.Timed(seedStart,seedStart.plusSeconds(3600))
            val identity=CalendarIdentity(CalendarId(2),CalendarAccount("owner@example.invalid","com.google"),"owner@example.invalid","calendar-A")
            val reader=CalendarReader(context.contentResolver,scope,stopTimeoutMillis=0)
            val seed=CalendarWriter(context.contentResolver,reader,store,permissions::contains).create("seed",EventDraft(CalendarId(2),time,if(allDay)null else ZoneId.of("UTC"),"Synthetic prefix","Room","Notes",listOf(10,30),targetIdentity=identity,recurrence=RecurrenceSpec(RecurrencePattern.Daily,end=RecurrenceEnd.Count(8)))) as MutationResult.LocalSaved
            p.db.execSQL("UPDATE events SET uid2445='issued-prefix',_sync_id='prefix-sync',rrule=?,exdate=?,eventEndTimezone=NULL WHERE _id=1",arrayOf(rule,raw))
            val selected=seed.ref.copy(instanceStart=seedStart.plusSeconds(ordinal*86400L),instanceEnd=seed.ref.instanceEnd.plusSeconds(ordinal*86400L),recurrence=RecurrenceRef(seed.ref.eventId,seedStart.plusSeconds(ordinal*86400L)))
            p.batchCount=0;p.committed=false;p.queries.clear();block(Fixture(p,store,scope,permissions,selected))
        } finally {scope.cancel();scope.coroutineContext[Job]!!.join();store.close();p.db.close();context.deleteDatabase(store.databaseName)}
    }
    @Test fun timedLiteralPartitionPreservesDuplicatesOrderGroupAndLf()=runBlocking{fixture("UTC;20280104T090000Z,20280104T090000Z\n20280108T090000Z\n"){f->val saved=f.save();assertTrue(saved.toString(),saved is SeriesMutationResult.LocalSaved);assertEquals("UTC;20280104T090000Z,20280104T090000Z\n",f.value(1,"exdate"));assertEquals("20280108T090000Z\n",f.value(2,"exdate"));assertEquals(4,f.receipt().getInt("version"))}}
    @Test fun allDayDateAndMidnightUtcFormsAreAdmitted()=runBlocking{for(raw in listOf("20280104,20280108","20280104T000000Z,20280108T000000Z"))fixture(raw,allDay=true){f->assertTrue(f.save() is SeriesMutationResult.LocalSaved);assertEquals(if(raw.contains('T'))"20280104T000000Z"else"20280104",f.value(1,"exdate"));assertEquals(4,f.receipt().getInt("version"))}}
    @Test fun nullAndEmptyRemainExactOnSplitAndWhole()=runBlocking{for(raw in listOf(null,""))for(ordinal in listOf(0,2))fixture(raw,ordinal=ordinal){f->assertTrue(f.save() is SeriesMutationResult.LocalSaved);assertEquals(raw,f.value(1,"exdate"));if(ordinal>0)assertEquals(raw,f.value(2,"exdate"));assertEquals(4,f.receipt().getInt("version"))}}
    @Test fun readerUndecodedUtcDateGroupNeverPrepares()=runBlocking{fixture("UTC;20280104,20280108",allDay=true){f->assertTrue(f.save() is SeriesMutationResult.PlanningReview);assertNull(f.store.operation("change"));assertEquals(0,f.p.batchCount)}}
    @Test fun unsupportedNativeFormsNeverPrepareOrDispatch()=runBlocking{for(raw in listOf(" ","20280104T090000Z,","20280104T090000Z\n\n","20280104T090000Z\r\n","EXDATE:20280104T090000Z","20280104T090000","UTC;20280104T090001Z,"))fixture(raw){f->assertTrue(raw,f.save() is SeriesMutationResult.PlanningReview);assertNull(f.store.operation("change"));assertEquals(0,f.p.batchCount)}}
    @Test fun excludedSeedEarlierDatesStillConsumeNominalCountAndFinalTailCountOne()=runBlocking{fixture("20280103T090000Z,20280104T090000Z",ordinal=7){f->assertTrue(f.save() is SeriesMutationResult.LocalSaved);assertEquals("FREQ=DAILY;COUNT=7",f.value(1,"rrule"));assertEquals("FREQ=DAILY;COUNT=1",f.value(2,"rrule"));assertEquals(7,f.receipt().getJSONObject("plan").getInt("ordinal"))}}
    @Test fun untilAndUnboundedTailRulesStayLiteral()=runBlocking{for(rule in listOf("FREQ=DAILY","FREQ=DAILY;UNTIL=20280120T090000Z"))fixture("20280104T090000Z",rule=rule){f->assertTrue(f.save() is SeriesMutationResult.LocalSaved);assertEquals(rule,f.value(2,"rrule"));assertEquals("FREQ=DAILY;UNTIL=20280104T090000Z",f.value(1,"rrule"))}}
    @Test fun offPatternExclusionsPartitionWithoutOrdinalLookup()=runBlocking{fixture("20280104T100000Z,20280108T100000Z"){f->assertTrue(f.save() is SeriesMutationResult.LocalSaved);assertEquals("20280104T100000Z",f.value(1,"exdate"));assertEquals("20280108T100000Z",f.value(2,"exdate"))}}
    @Test fun ordinalZeroPreservesRulesAndExdateWithoutTailUidOrCloneAdmission()=runBlocking{fixture("20280108T090000Z",ordinal=0){f->val(t,c)=f.input();assertTrue(f.family(policy=ProviderClonePolicy.Unverified).mutate("change",f.selected,t,c) is SeriesMutationResult.LocalSaved);assertEquals(1,f.p.count("events"));assertEquals("issued-prefix",f.value(1,"uid2445"));assertEquals("FREQ=DAILY;COUNT=8",f.value(1,"rrule"));assertEquals("20280108T090000Z",f.value(1,"exdate"));assertTrue(f.receipt().getJSONObject("plan").isNull("tail"))}}
    @Test fun metadataPreservesNullAndEmptyEndZoneAndCopiesExactChildren()=runBlocking{for(zone in listOf(null,""))fixture("20280108T090000Z"){f->f.p.db.execSQL("UPDATE events SET eventEndTimezone=?",arrayOf(zone));val(t,c)=f.input(EventPatch(title=FieldChange.Set("Changed"),alertMinutes=FieldChange.Set(listOf(15))));assertTrue(f.family.mutate("change",f.selected,t,c) is SeriesMutationResult.LocalSaved);assertEquals(zone,f.value(2,"eventEndTimezone"));assertEquals(3,f.p.count("reminders"));assertEquals("20280108T090000Z",f.value(2,"exdate"))}}
    @Test fun equivalentDecodedKeysWithChangedRawBytesInvalidateFrozenPreparation()=runBlocking{fixture("20280104T090000Z,20280104T090000Z"){f->val(t,c)=f.input();f.p.failReminder=true;assertEquals(common(MutationResult.Unavailable),f.family.mutate("change",f.selected,t,c));val before=f.store.operation("change");f.p.failReminder=false;f.p.db.execSQL("UPDATE events SET exdate='20280104T090000Z'");assertEquals(common(MutationResult.Conflict(null)),f.family.mutate("change",f.selected,t,c));assertEquals(before,f.store.operation("change"));assertEquals(1,f.p.batchCount)}}
    @Test fun excludedSelectionAndNonmetadataExdateEditsRefuseWithoutJournal()=runBlocking{fixture("20280105T090000Z"){f->assertEquals(SeriesReadResult.Unavailable,f.owner.loadForEdit(f.selected));assertNull(f.store.operation("change"));assertEquals(0,f.p.batchCount)};fixture("20280108T090000Z"){f->val(t,c)=f.input(rule=RecurrenceRuleChanges(end=FieldChange.Set(RecurrenceEnd.Count(3))));assertTrue(f.family.mutate("change",f.selected,t,c) is SeriesMutationResult.PlanningReview);assertNull(f.store.operation("change"));assertEquals(0,f.p.batchCount)}}
    @Test fun definiteRollbackRetainsFrozenFourThenRetriesOnceWithoutCompiler()=runBlocking{fixture("20280104T090000Z"){f->val(t,c)=f.input();f.p.failReminder=true;assertEquals(common(MutationResult.Unavailable),f.family.mutate("change",f.selected,t,c));val before=f.store.operation("change")!!;assertEquals("FAILED",before.phase);assertEquals(4,f.receipt().getInt("version"));f.p.failReminder=false;f.store.writableDatabase.execSQL("DELETE FROM edit_snapshots");f.family.frozenPreparationProbe={error("Current planner unavailable")};assertTrue(f.family.mutate("change",f.selected,t,c) is SeriesMutationResult.LocalSaved);assertEquals(2,f.p.batchCount)}}
    @Test fun malformedMasterAckStaysAttemptedAndNeverReplays()=runBlocking{fixture("20280108T090000Z"){f->f.p.malformedLastEventAck=true;val(t,c)=f.input();assertEquals(common(MutationResult.Uncertain("change",null)),f.family.mutate("change",f.selected,t,c));assertEquals("ATTEMPTED",f.store.operation("change")!!.phase);assertEquals(common(MutationResult.Uncertain("change",null)),f.family.mutate("change",f.selected,t,c));assertEquals(1,f.p.batchCount)}}
    @Test fun reopenedReadOnlyRecoveryNeedsNeitherSourceSnapshotsNorCurrentCompiler()=runBlocking{fixture("20280104T090000Z,20280108T090000Z"){f->assertTrue(f.save() is SeriesMutationResult.LocalSaved);val bytes=f.store.operation("change")!!.resultRef;f.store.writableDatabase.execSQL("DELETE FROM edit_snapshots");f.permissions.remove(Manifest.permission.WRITE_CALENDAR);val reopened=CalendarLocalStore(f.context,f.store.databaseName);try{val reader=f.family(reopened,ProviderClonePolicy.Unverified);reader.frozenPreparationProbe={error("No compiler")};assertTrue(reader.recover("change") is SeriesMutationResult.LocalSaved);assertEquals(bytes,reopened.operation("change")!!.resultRef);assertEquals(1,f.p.batchCount)}finally{reopened.close()}}}
    @Test fun replacedIdentityOrNamespaceSuppressesKnownResultBeforePayload()=runBlocking{for(namespace in listOf(false,true))fixture("20280108T090000Z"){f->assertTrue(f.save() is SeriesMutationResult.LocalSaved);if(namespace)f.p.setCalendar("_sync_id","replacement")else f.p.db.execSQL("UPDATE events SET uid2445='replacement' WHERE _id=2");f.p.queries.clear();assertEquals(common(MutationResult.Uncertain("change",null)),f.family.recover("change"));assertTrue(f.p.queries.none{it.path in setOf("reminders","attendees","extendedproperties","instances")});assertTrue(f.p.queries.filter{it.path=="events"}.all{it.fields?.all{k->k in setOf("_id","calendar_id","uid2445","_sync_id")}==true});assertEquals(1,f.p.batchCount)}}
    @Test fun concurrentCreateAndRetirementRetainOneIssuedPlanAndNoResurrection()=runBlocking{fixture("20280108T090000Z"){f->val(t,c)=f.input();val two=f.family();val a=async(Dispatchers.Default){f.family.mutate("change",f.selected,t,c)};val b=async(Dispatchers.Default){two.mutate("change",f.selected,t,c)};val results=listOf(a.await(),b.await());assertTrue(results.any{it is SeriesMutationResult.LocalSaved});assertEquals(1,f.p.batchCount);assertEquals(2,f.p.count("events"));val request=RetirableOperation.Series("retire",f.selected,t,c);assertEquals(OperationRetirementResult.Retired("retire"),CalendarOperationRetirement(f.store).retire(request));assertEquals(common(MutationResult.Retired("retire")),two.mutate("retire",f.selected,t,c));assertEquals(1,f.p.batchCount)}}
    @Test fun predictableProofCapacityRefusesBeforeJournalOrProviderWrite()=runBlocking{fixture("20280108T090000Z"){f->val(t,c)=f.input();val tiny=ProviderReadBudgets(maxCanonicalBytes=1024);assertTrue(f.family(budgets=tiny).mutate("change",f.selected,t,c) is SeriesMutationResult.PlanningReview);assertEquals(0,f.p.batchCount);assertNull(f.store.operation("change"))}}
    @Test fun nonemptyExdateCapabilitiesAdmitMetadataButNeverTemporalOrRuleEdits()=runBlocking {
        for(raw in listOf(null,"","20280108T090000Z"))fixture(raw){f->
            val edit=(f.owner.loadForEdit(f.selected) as SeriesReadResult.Ready).series
            val rights=edit.scopes.getValue(RecurrenceScope.FOLLOWING)
            assertEquals(AccessDecision.ALLOWED,rights.edit);assertEquals(AccessDecision.ALLOWED,rights.delete)
            if(raw.isNullOrEmpty()){
                assertTrue(EditableField.TIME in rights.fields);assertTrue(EditableField.ZONE in rights.fields)
                assertEquals(AccessDecision.ALLOWED,rights.recurrence)
            }else{
                assertFalse(EditableField.TIME in rights.fields);assertFalse(EditableField.ZONE in rights.fields)
                assertTrue(EditableField.TITLE in rights.fields);assertTrue(EditableField.ALERTS in rights.fields)
                assertEquals(AccessDecision.UNKNOWN,rights.recurrence)
            }
            assertNull(f.store.operation("change"));assertEquals(0,f.p.batchCount)
        }
    }

}
