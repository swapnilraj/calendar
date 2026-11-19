package dev.swapnilraj.calendar.data.calendar

import android.Manifest
import android.app.Application
import android.content.*
import android.os.Parcel
import android.provider.CalendarContract
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
class OrdinaryCreateReceiptDispatchTest {
    private val start=Instant.parse("2028-03-10T09:00:00Z")
    private val identity=CalendarIdentity(CalendarId(2),CalendarAccount("owner@example.invalid","com.google"),"owner@example.invalid","calendar-A")
    private val draft=EventDraft(CalendarId(2),EventTime.Timed(start,start.plusSeconds(3600)),ZoneId.of("Europe/London"),"Synthetic immutable create",
        description="Synthetic details",alertMinutes=listOf(10,30),targetIdentity=identity)
    private fun recurring()=draft.copy(recurrence=RecurrenceSpec(RecurrencePattern.Daily,end=RecurrenceEnd.ThroughDate(LocalDate.of(2028,3,15))))

    @Test fun definiteRollbackRetryReusesWinningPlanAndUidWithoutAuthoring()=runBlocking {
        for(input in listOf(draft,recurring()))fixture {f->
            f.p.failReminder=true
            assertEquals(MutationResult.Unavailable,f.writer.create("create",input))
            val failed=f.store.operation("create")!!;assertEquals("FAILED",failed.phase)
            val plan=OrdinaryCreateReceipt.parse(failed,1024*1024).second
            assertEquals(0,f.p.count("events"));f.p.failReminder=false
            f.writer.ordinaryCreateAuthoringProbe={error("Current authoring must not run on stored retry")}
            assertEquals(MutationResult.SafeToRetry("create"),f.writer.recover("create"))
            val saved=f.writer.create("create",input) as MutationResult.LocalSaved
            assertEquals(plan.plan.uid,f.p.value("uid2445"));assertEquals(plan.plan.event["dtstart"],f.p.value("dtstart"))
            assertEquals(plan.plan.event["rrule"],f.p.value("rrule"));assertEquals(2,f.p.batchCount)
            assertEquals(saved,f.writer.recover("create"))
        }
    }
    @Test fun committedRecoveryAfterTravelReopenAndSnapshotPurgeNeverAuthors()=runBlocking {
        for(input in listOf(draft,recurring()))fixture {f->
            f.p.failReadsAfterCommit=true
            assertTrue(f.writer.create("create",input) is MutationResult.Uncertain)
            val committed=f.store.operation("create")!!;assertEquals("COMMITTED",committed.phase)
            val plan=OrdinaryCreateReceipt.parse(committed,1024*1024).second.plan
            f.p.failReadsAfterCommit=false;f.permissions.remove(Manifest.permission.WRITE_CALENDAR)
            f.store.writableDatabase.execSQL("DELETE FROM edit_snapshots")
            val original=java.util.TimeZone.getDefault()
            val reopened=CalendarLocalStore(f.context,f.store.databaseName)
            try {
                java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Pacific/Auckland"))
                val writer=f.writer(reopened).apply {ordinaryCreateAuthoringProbe={error("No current compiler/validator during recovery")}}
                val saved=writer.recover("create") as MutationResult.LocalSaved
                assertEquals(plan.firstStart,saved.ref.instanceStart);assertEquals(plan.firstEnd,saved.ref.instanceEnd)
                assertEquals(plan.uid,f.p.value("uid2445"));assertEquals(1,f.p.batchCount)
                assertEquals(saved,writer.create("create",input))
            }finally {java.util.TimeZone.setDefault(original);reopened.close()}
        }
    }
    @Test fun cancellationAfterPreparedCannotDispatchAndRetryUsesSameMaterial()=runBlocking {fixture {f->
        var calendars=0;lateinit var pending:Deferred<MutationResult>
        f.p.beforeQuery={path->if(path=="calendars"&&++calendars==2)pending.cancel()}
        pending=async(start=CoroutineStart.LAZY){f.writer.create("create",recurring())};pending.start();pending.join()
        assertTrue(pending.isCancelled);assertEquals(0,f.p.batchCount)
        val prepared=f.store.operation("create")!!;assertEquals("PREPARED",prepared.phase)
        val uid=OrdinaryCreateReceipt.parse(prepared,1024*1024).second.plan.uid
        f.p.beforeQuery=null;f.writer.ordinaryCreateAuthoringProbe={error("Retry must use immutable prepared material")}
        assertTrue(f.writer.create("create",recurring()) is MutationResult.LocalSaved)
        assertEquals(uid,f.p.value("uid2445"));assertEquals(1,f.p.batchCount)
    }}
    @Test fun freshCapabilitiesAndNamespaceBlockPreparedDispatchWithoutChangingReceipt()=runBlocking {
        for(column in listOf("maxReminders","_sync_id","calendar_access_level"))fixture {f->
            f.p.failReminder=true;assertEquals(MutationResult.Unavailable,f.writer.create("create",draft));f.p.failReminder=false
            val bytes=f.store.operation("create")!!.resultRef
            f.p.setCalendar(column,when(column){"maxReminders"->"1";"_sync_id"->"replacement";else->"200"})
            f.writer.ordinaryCreateAuthoringProbe={error("No revalidation")}
            val result=f.writer.create("create",draft)
            assertTrue(result.toString(),result is MutationResult.Conflict||result is MutationResult.Denied||result is MutationResult.ValidationFailed)
            assertEquals(bytes,f.store.operation("create")!!.resultRef);assertEquals("FAILED",f.store.operation("create")!!.phase)
            assertEquals(1,f.p.batchCount);assertEquals(0,f.p.count("events"))
        }
    }
    @Test fun concurrentCandidatesAdoptOneDurableUidAndDispatchOnce()=runBlocking {fixture {f->
        val otherStore=CalendarLocalStore(f.context,f.store.databaseName);val other=f.writer(otherStore)
        val candidates=CountDownLatch(2)
        val probe={candidates.countDown();check(candidates.await(5,TimeUnit.SECONDS));Unit}
        f.writer.ordinaryCreateAuthoringProbe=probe;other.ordinaryCreateAuthoringProbe=probe
        try {
            val results=withTimeout(15000){listOf(async(Dispatchers.Default){f.writer.create("create",recurring())},async(Dispatchers.Default){other.create("create",recurring())}).awaitAll()}
            assertTrue(results.toString(),results.any{it is MutationResult.LocalSaved});assertEquals(1,f.p.batchCount);assertEquals(1,f.p.count("events"))
            val winner=OrdinaryCreateReceipt.parse(f.store.operation("create")!!,1024*1024).second
            assertEquals(winner.plan.uid,f.p.value("uid2445"));assertTrue(f.writer.recover("create") is MutationResult.LocalSaved)
        }finally {otherStore.close()}
    }}
    @Test fun recoverAndCreateShareLockWithoutReentryOrReplay()=runBlocking {fixture {f->
        f.p.failReminder=true;f.writer.create("create",draft);f.p.failReminder=false
        f.writer.ordinaryCreateAuthoringProbe={error("Stored operation must never author")}
        val results=withTimeout(10000){listOf(async{f.writer.recover("create")},async{f.writer.create("create",draft)}).awaitAll()}
        assertTrue(results.toString(),results.any{it is MutationResult.LocalSaved});assertEquals(2,f.p.batchCount)
        assertEquals(1,f.p.count("events"))
    }}
    @Test fun retirementOfPreparedWinnerWinsBeforeAttemptAndHasPriorityWithoutReadPermission()=runBlocking {fixture {f->
        val retire=CalendarOperationRetirement(f.store);var calendars=0
        f.p.beforeQuery={path->if(path=="calendars"&&++calendars==2)assertEquals(OperationRetirementResult.Retired("create"),runBlocking{retire.retire(RetirableOperation.Create("create",draft))})}
        assertEquals(MutationResult.Retired("create"),f.writer.create("create",draft));assertEquals(0,f.p.batchCount)
        f.permissions.clear();f.p.queries.clear()
        assertEquals(MutationResult.Retired("create"),f.writer.recover("create"));assertEquals(MutationResult.Retired("create"),f.writer.create("create",draft));assertTrue(f.p.queries.isEmpty())
    }}
    @Test fun pointEventIsVerifiedUsingNativeExclusiveRangeBoundary()=runBlocking {fixture {f->
        val point=draft.copy(time=EventTime.Timed(start,start),alertMinutes=emptyList())
        val saved=f.writer.create("point",point) as MutationResult.LocalSaved
        assertEquals(start,saved.ref.instanceEnd);assertEquals(saved,f.writer.recover("point"));assertEquals(1,f.p.batchCount)
    }}
    @Test fun worstCaseCanonicalAckCapacityRejectsBeforeAnyProviderWrite()=runBlocking {fixture {f->
        val alerts=(1..680).toList();f.p.setCalendar("maxReminders","1000")
        val operations=arrayListOf(ContentProviderOperation.newAssertQuery(CalendarContract.Calendars.CONTENT_URI).withExpectedCount(1).build(),ContentProviderOperation.newInsert(CalendarContract.Events.CONTENT_URI).withValue("title","Synthetic").build())
        alerts.forEach {operations+=ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI).withValueBackReference("event_id",1).withValue("minutes",it).withValue("method",1).withYieldAllowed(false).build()}
        val parcel=Parcel.obtain();try{operations.forEach{it.writeToParcel(parcel,0)};assertTrue("Fixture must fit native transaction before ACK cap: ${parcel.dataSize()}",parcel.dataSize()<250*1024)}finally{parcel.recycle()}
        val worst=Array(operations.size){i->if(i==0)ContentProviderResult(1)else ContentProviderResult(ContentUris.withAppendedId(if(i==1)CalendarContract.Events.CONTENT_URI else CalendarContract.Reminders.CONTENT_URI,Long.MAX_VALUE-i))}
        assertTrue("Worst-case ACK facts must exceed their independent cap",CalendarCreateAcknowledgments.capture(worst,worst.size)==null)
        assertEquals(MutationResult.ValidationFailed(setOf(InvalidEventField.ALERTS)),f.writer.create("oversized-proof",draft.copy(alertMinutes=alerts)))
        assertEquals(0,f.p.batchCount);assertEquals(0,f.p.count("events"));assertNull(f.store.operation("oversized-proof"))
    }}
    @Test fun foreignTombstoneAtInnerRoutingFenceIsConflictWithoutFactoryOrProviderReads()=runBlocking {fixture {f->
        val foreign=draft.copy(title="Other synthetic command")
        assertEquals(OperationRetirementResult.Retired("create"),CalendarOperationRetirement(f.store).retire(RetirableOperation.Create("create",foreign)))
        f.p.queries.clear()
        val handler=CalendarOrdinaryCreateWriterV6(f.context.contentResolver,CalendarReader(f.context.contentResolver,f.scope,stopTimeoutMillis=0),f.store,{true},
            kotlinx.coroutines.sync.Mutex(),Dispatchers.Default,ProviderReadBudgets(),{_,_,_,_->error("No factory authority after foreign tombstone")},{error("No provider authority after foreign tombstone")})
        assertEquals(MutationResult.Conflict(null),handler.createIfNew("create",draft));assertTrue(f.p.queries.isEmpty());assertEquals(0,f.p.batchCount)
    }}
    @Test fun insufficientWholeVerificationBudgetStopsBeforeBatch()=runBlocking {fixture {f->
        // Two alerts need 9+3n=15 rows, including the initial/dispatch calendar reads.
        // The previous 7+3n estimate would admit this budget of14.
        val budgets=ProviderReadBudgets(mutation=ProviderReadLimits(14,250000,4*1024*1024))
        val writer=CalendarWriter(f.context.contentResolver,CalendarReader(f.context.contentResolver,f.scope,stopTimeoutMillis=0),f.store,f.permissions::contains,readBudgets=budgets)
        assertEquals(MutationResult.Unavailable,writer.create("budget",draft));assertEquals(0,f.p.batchCount);assertEquals(0,f.p.count("events"))
    }}
    @Test fun genuineLegacyTwoAndFivePreparedRetryKeepOldBytesAndNeverIssueReceiptSixUid()=runBlocking {
        for(version in listOf(2,5))fixture {f->
            val input=if(version==2)draft else recurring()
            // Stop after new preparation only to obtain the existing immutable command bytes;
            // explicitly build the genuine historical wire shape instead of relabeling6.
            f.store.writableDatabase.execSQL("CREATE TRIGGER stop_prepare BEFORE UPDATE OF phase ON mutation_journal WHEN NEW.phase='ATTEMPTED' BEGIN SELECT RAISE(FAIL,'Synthetic pause'); END")
            assertEquals(MutationResult.Unavailable,f.writer.create("legacy",input))
            val initial=f.store.operation("legacy")!!
            val (raw,frozen)=OrdinaryCreateReceipt.parse(initial,1024*1024)
            val legacy=JSONObject().put("version",version).put("stage","prepared").put("kind","CREATE").put("signature",initial.signature)
                .put("command",raw.getJSONObject("command")).put("identity",raw.getJSONObject("identity"))
            if(version==5)legacy.put("provider",JSONObject()
                .put("dtstart",Instant.ofEpochMilli(frozen.plan.event.getValue("dtstart")!!.toLong()).toString()).put("allDay",false)
                .put("zone",frozen.plan.event["eventTimezone"]).put("rrule",frozen.plan.event["rrule"]).put("duration",frozen.plan.event["duration"])
                .put("firstStart",frozen.plan.firstStart.toString()).put("firstEnd",frozen.plan.firstEnd.toString())).put("layout",CalendarCreateAcknowledgments.layout(input.alertMinutes.size))
            val bytes=legacy.toString();f.store.forceOperationForTest("legacy","PREPARED",bytes)
            assertEquals(MutationResult.SafeToRetry("legacy"),f.writer.recover("legacy"))
            f.permissions.remove(Manifest.permission.WRITE_CALENDAR)
            assertEquals(MutationResult.PermissionRequired,f.writer.create("legacy",input));assertEquals(bytes,f.store.operation("legacy")!!.resultRef)
            f.permissions+=Manifest.permission.WRITE_CALENDAR;f.store.writableDatabase.execSQL("DROP TRIGGER stop_prepare")
            f.p.beforeBatch={assertEquals("ATTEMPTED",f.store.operation("legacy")!!.phase);assertEquals(bytes,f.store.operation("legacy")!!.resultRef)}
            val outcome=f.writer.create("legacy",input)
            assertTrue("Legacy $version result=$outcome phase=${f.store.operation("legacy")}",outcome is MutationResult.LocalSaved)
            val saved=outcome as MutationResult.LocalSaved
            val committed=f.store.operation("legacy")!!
            assertEquals(version,JSONObject(committed.resultRef!!).getInt("version"));assertEquals(initial.signature,committed.signature)
            assertNull(f.p.value("uid2445"));assertEquals(1,f.p.batchCount)
            f.permissions.remove(Manifest.permission.WRITE_CALENDAR);assertEquals(saved,f.writer.recover("legacy"))
            assertEquals(version,JSONObject(f.store.operation("legacy")!!.resultRef!!).getInt("version"));assertNull(f.p.value("uid2445"))
            f.store.forceOperationForTest("legacy","ATTEMPTED",bytes)
            assertEquals(MutationResult.Uncertain("legacy",null),f.writer.recover("legacy"))
            f.permissions+=Manifest.permission.WRITE_CALENDAR
            assertEquals(MutationResult.Uncertain("legacy",null),f.writer.create("legacy",input));assertEquals(bytes,f.store.operation("legacy")!!.resultRef);assertEquals(1,f.p.batchCount)
        }
    }
    private class Fixture(val context:Application,val p:CalendarRecurringCreateTest.Provider,val store:CalendarLocalStore,val scope:CoroutineScope) {
        val permissions=mutableSetOf(Manifest.permission.READ_CALENDAR,Manifest.permission.WRITE_CALENDAR)
        fun writer(store:CalendarLocalStore)=CalendarWriter(context.contentResolver,CalendarReader(context.contentResolver,scope,stopTimeoutMillis=0),store,permissions::contains)
        val writer=writer(store)
    }
    private suspend fun fixture(block:suspend(Fixture)->Unit) {
        val context=RuntimeEnvironment.getApplication();val p=CalendarRecurringCreateTest.Provider();ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY,p)
        val store=CalendarLocalStore(context,"synthetic-receipt6-dispatch-${UUID.randomUUID()}.db");val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try{block(Fixture(context,p,store,scope))}finally{scope.cancel();scope.coroutineContext[Job]!!.join();store.close();p.db.close();context.deleteDatabase(store.databaseName)}
    }
}
