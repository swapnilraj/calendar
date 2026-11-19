package dev.swapnilraj.calendar.data.calendar

import android.app.Application
import android.provider.CalendarContract
import dev.swapnilraj.calendar.core.*
import java.security.MessageDigest
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class ScopedSeriesPublicationBoundaryTest {
    private val start=Instant.parse("2028-01-03T09:00:00Z")
    private val selected=EventRef(CalendarId(2),10,start.plusSeconds(86400),start.plusSeconds(90000),RecurrenceRef(10,start.plusSeconds(86400)))
    private val token=EditToken("issued-token")
    private val anchor=RecurrenceStart.Timed(LocalDateTime.of(2028,1,3,9,0),ZoneId.of("UTC"))
    private val whole=SeriesCommand.WholeEdit(RecurrenceEditIntent.WholeSeriesEdit(anchor,EventPatch(title=FieldChange.Set("Frozen"))))
    private val following=SeriesCommand.FollowingEdit(RecurrenceEditIntent.FollowingSeriesEdit(RecurrenceKey.Timed(selected.instanceStart)))
    private fun common(r:MutationResult)=SeriesMutationResult.Common(r)
    private fun signature(commandJson:String):String {
        val envelope="""{"version":1,"selected":{"calendar":2,"event":10,"start":"2028-01-04T09:00:00Z","end":"2028-01-04T10:00:00Z","master":10,"original":"2028-01-04T09:00:00Z"},"token":"issued-token","command":$commandJson}"""
        return MessageDigest.getInstance("SHA-256").digest(envelope.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
    }
    @Test fun publishedScopeGoldenFingerprintsFenceLateWorkersBeforePermissionOrSourceReads()=runBlocking{fixture(false){f->
        val commands=listOf(
            Triple(whole,"SERIES_V1_ALL_EDIT","""{"type":"whole","expected":{"local":"2028-01-03T09:00","zone":"UTC","offset":null},"patch":{"title":"Frozen"},"duration":"keep","rule":{}}"""),
            Triple(SeriesCommand.OccurrenceEdit(EventPatch(title=FieldChange.Set("Frozen"))),"SERIES_V1_THIS_EDIT","""{"type":"this","patch":{"title":"Frozen"}}"""),
            Triple(SeriesCommand.Delete(RecurrenceScope.ALL),"SERIES_V1_ALL_DELETE","""{"type":"delete","scope":"ALL"}"""),
            Triple(SeriesCommand.Delete(RecurrenceScope.THIS),"SERIES_V1_THIS_DELETE","""{"type":"delete","scope":"THIS"}"""),
            Triple(SeriesCommand.Delete(RecurrenceScope.FOLLOWING),"SERIES_V1_FOLLOWING_DELETE","""{"type":"delete","scope":"FOLLOWING"}"""))
        for((index,item)in commands.withIndex()){
            val(command,kind,json)=item;val id="scope-$index";val request=RetirableOperation.Series(id,selected,token,command)
            assertEquals(kind to signature(json),CalendarSeriesWriter.retirementFingerprint(selected,token,command))
            assertEquals(OperationRetirementResult.Retired(id),f.retirement.retire(request))
            assertEquals(kind,f.store.operationHeader(id)!!.kind);assertEquals(signature(json),f.store.operationHeader(id)!!.signature)
            assertEquals(common(MutationResult.Retired(id)),f.writer.mutate(id,selected,token,command))
            assertEquals(common(MutationResult.Retired(id)),f.writer.recover(id))
        }
        assertTrue(f.provider.calls.isEmpty())
    }}
    @Test fun followingEditIsUnsupportedUnretirableAndCannotCreateAJournal()=runBlocking{fixture(false){f->
        assertTrue(f.writer.mutate("closed",selected,token,following) is SeriesMutationResult.Unsupported)
        val request=RetirableOperation.Series("closed",selected,token,following)
        assertEquals(OperationRetirementResult.Unsupported,f.retirement.inspect(request));assertEquals(OperationRetirementResult.Unsupported,f.retirement.retire(request))
        assertNull(f.store.operationHeader("closed"));assertTrue(f.provider.calls.isEmpty())
    }}
    @Test fun unknownFollowingReceiptsNeverOfferRetryReadPayloadOrReplay()=runBlocking{fixture(true){f->
        val(kind,hash)=CalendarSeriesWriter.retirementFingerprint(selected,token,following)
        for(phase in listOf("PREPARED","FAILED","ATTEMPTED","COMMITTED","VERIFIED")){
            val id="closed-$phase";f.store.prepare(id,hash,kind)
            f.store.forceOperationForTest(id,phase,if(phase=="PREPARED")null else "Opaque unshipped receipt")
            val before=f.store.operation(id)
            assertEquals(common(MutationResult.Uncertain(id,null)),f.writer.recover(id))
            assertTrue(f.writer.mutate(id,selected,token,following) is SeriesMutationResult.Unsupported)
            assertEquals(before,f.store.operation(id))
            assertEquals(OperationRetirementResult.Unsupported,f.retirement.retire(RetirableOperation.Series(id,selected,token,following)))
        }
        assertTrue(f.provider.calls.isEmpty())
    }}
    @Test fun seriesRetirementRacesOnlyTheExactAttemptAndCannotBeResurrected()=runBlocking{fixture(false){f->
        val other=CalendarLocalStore(f.context,f.store.databaseName)
        try{for((index,command)in listOf<SeriesCommand>(whole,SeriesCommand.OccurrenceEdit(EventPatch(title=FieldChange.Set("Frozen"))),SeriesCommand.Delete(RecurrenceScope.FOLLOWING)).withIndex()){
            val id="race-$index";val(kind,hash)=CalendarSeriesWriter.retirementFingerprint(selected,token,command);f.store.prepare(id,hash,kind)
            val prepared=f.store.operation(id)!!;val gate=CompletableDeferred<Unit>()
            val attempt=async(Dispatchers.Default){gate.await();f.store.attempt(prepared)}
            val retire=async(Dispatchers.Default){gate.await();CalendarOperationRetirement(other).retire(RetirableOperation.Series(id,selected,token,command))}
            gate.complete(Unit);val sent=attempt.await();val retired=retire.await()
            if(sent){assertEquals(OperationRetirementResult.Blocked(id,RetirementBlockReason.MAY_HAVE_DISPATCHED),retired);assertEquals("ATTEMPTED",other.operationHeader(id)!!.phase)}
            else{assertEquals(OperationRetirementResult.Retired(id),retired);assertEquals("ABANDONED",other.operationHeader(id)!!.phase);assertFalse(f.store.mark(prepared,"FAILED"));assertFalse(f.store.attempt(prepared))}
        }}finally{other.close()}
        assertTrue(f.provider.calls.isEmpty())
    }}
    @Test fun defaultPolicyKeepsFollowingEditRightsClosedWhilePrefixDeleteRefreshesWarmSyntheticCache()=runBlocking{
        val context=RuntimeEnvironment.getApplication();val p=ScopedSeriesSqlProvider().apply{allowUpdates=true;expandInstances=true;cacheExpandedTemporalRows=true}
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY,p)
        val store=CalendarLocalStore(context,"scoped-warm-${UUID.randomUUID()}.db");val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try{
            val reader=CalendarReader(context.contentResolver,scope,stopTimeoutMillis=0)
            val identity=CalendarIdentity(CalendarId(2),CalendarAccount("owner@example.invalid","com.google"),"owner@example.invalid","calendar-A")
            val seed=CalendarWriter(context.contentResolver,reader,store,{true}).create("seed",EventDraft(CalendarId(2),EventTime.Timed(start,start.plusSeconds(3600)),ZoneId.of("UTC"),"Synthetic scoped source",targetIdentity=identity,recurrence=RecurrenceSpec(RecurrencePattern.Daily,end=RecurrenceEnd.Count(8)))) as MutationResult.LocalSaved
            val ref=seed.ref.copy(instanceStart=start.plusSeconds(172800),instanceEnd=start.plusSeconds(176400),recurrence=RecurrenceRef(seed.ref.eventId,start.plusSeconds(172800)))
            val source=CalendarSeriesReader(context.contentResolver,reader,store,{true});val writer=CalendarSeriesWriter(context.contentResolver,source,store,{true})
            val edit=(writer.loadForEdit(ref) as SeriesReadResult.Ready).series
            assertEquals(AccessDecision.DENIED,edit.scopes.getValue(RecurrenceScope.FOLLOWING).edit)
            assertEquals(AccessDecision.UNKNOWN,edit.scopes.getValue(RecurrenceScope.FOLLOWING).recurrence)
            assertEquals(AccessDecision.ALLOWED,edit.scopes.getValue(RecurrenceScope.FOLLOWING).delete)
            fun rows():Int= p.query(CalendarContract.Instances.CONTENT_URI.buildUpon().appendPath("${start.minusSeconds(1).toEpochMilli()}").appendPath("${start.plusSeconds(10*86400).toEpochMilli()}").build(),arrayOf("event_id","begin","end"),null,null,null).use{it.count}
            assertEquals(8,rows());p.batchCount=0
            assertTrue(writer.mutate("truncate",ref,edit.token,SeriesCommand.Delete(RecurrenceScope.FOLLOWING)) is SeriesMutationResult.LocalDeleted)
            assertEquals(2,rows());assertEquals(1,p.batchCount)
            val patch=p.updatedEventValues.single().second
            assertEquals(setOf("dtstart","dtend","duration","eventTimezone","allDay","originalInstanceTime","originalAllDay","rrule"),patch.keySet())
            assertFalse(patch.containsKey("eventEndTimezone"));assertTrue(patch.containsKey("dtend"));assertNull(patch.get("dtend"))
        }finally{scope.cancel();scope.coroutineContext[Job]!!.join();store.close();p.db.close();context.deleteDatabase(store.databaseName)}
    }
    private class Fixture(val context:Application,val provider:CalendarSeriesReaderTest.Provider,val store:CalendarLocalStore,val scope:CoroutineScope,permission:Boolean){
        val source=CalendarSeriesReader(context.contentResolver,CalendarReader(context.contentResolver,scope,stopTimeoutMillis=0),store,{permission})
        val writer=CalendarSeriesWriter(context.contentResolver,source,store,{permission})
        val retirement=CalendarOperationRetirement(store)
    }
    private suspend fun fixture(permission:Boolean,block:suspend(Fixture)->Unit){
        val context=RuntimeEnvironment.getApplication();val p=CalendarSeriesReaderTest.Provider();ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY,p)
        val store=CalendarLocalStore(context,"scoped-boundary-${UUID.randomUUID()}.db");val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try{block(Fixture(context,p,store,scope,permission))}finally{scope.cancel();scope.coroutineContext[Job]!!.join();store.close();context.deleteDatabase(store.databaseName)}
    }
}
