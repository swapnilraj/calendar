package dev.swapnilraj.calendar.data.calendar

import android.Manifest
import android.content.ContentValues
import android.provider.CalendarContract
import dev.swapnilraj.calendar.core.*
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
@Config(sdk = [35])
class CalendarOperationRetirementTest {
    private val start = Instant.parse("2026-11-17T09:00:00Z")
    private val ref = EventRef(CalendarId(2), 10, start, start.plusSeconds(3600))
    private val identity = CalendarIdentity(CalendarId(2), CalendarAccount("owner@example.invalid", "com.google"), "owner@example.invalid", "calendar-A")
    private val draft = EventDraft(CalendarId(2), EventTime.Timed(start,start.plusSeconds(3600)), ZoneId.of("Europe/London"), "Synthetic frozen command", targetIdentity = identity)
    private val token = EditToken("original-opaque-source-token")
    private suspend fun stores(block: suspend (CalendarLocalStore, CalendarLocalStore, CalendarOperationRetirement, CalendarOperationRetirement) -> Unit) {
        val context = RuntimeEnvironment.getApplication(); val name = "retirement-${UUID.randomUUID()}.db"
        val one = CalendarLocalStore(context,name); val two = CalendarLocalStore(context,name)
        try { block(one,two,CalendarOperationRetirement(one),CalendarOperationRetirement(two)) }
        finally { one.close();two.close();context.deleteDatabase(name) }
    }
    private fun fingerprint(request: RetirableOperation): Pair<String,String> = when(request) {
        is RetirableOperation.Create -> CalendarWriter.createFingerprint(request.draft,1024*1024)
        is RetirableOperation.Update -> CalendarWriter.updateFingerprint(request.ref,request.token,request.patch,1024*1024)
        is RetirableOperation.Delete -> CalendarWriter.deleteFingerprint(request.ref,request.token)
        else -> error("Unsupported fixture")
    }
    private fun CalendarLocalStore.prepare(request:RetirableOperation,metadata:String?=null):CalendarLocalStore.Operation {
        val (kind,signature)=fingerprint(request);prepare(request.operationId,signature,kind,metadata);return requireNotNull(operation(request.operationId))
    }

    @Test fun missingFenceSurvivesReopenAndCannotBePreparedAttemptedOrMarked() = runBlocking { stores { one,two,a,b ->
        val request=RetirableOperation.Create("missing",draft)
        assertEquals(OperationRetirementResult.NotRetired("missing"),a.inspect(request));assertNull(one.operationHeader("missing"))
        assertEquals(OperationRetirementResult.Retired("missing"),a.retire(request));one.close()
        assertEquals(OperationRetirementResult.Retired("missing"),b.inspect(request));assertEquals(OperationRetirementResult.Retired("missing"),a.retire(request))
        val (kind,signature)=fingerprint(request);one.prepare("missing",signature,kind,"not-json")
        val abandoned=requireNotNull(one.operation("missing"));assertEquals("ABANDONED",abandoned.phase)
        assertFalse(two.attempt(abandoned));assertFalse(one.attempt(abandoned.copy(phase="PREPARED")))
        assertFalse(one.mark(abandoned,"COMMITTED"));assertFalse(one.mark(abandoned,"FAILED"));assertFalse(one.mark(abandoned,"VERIFIED"))
        assertEquals("ABANDONED",two.operationHeader("missing")!!.phase)
    } }

    @Test fun preparedAndFailedFencePreserveOpaqueInvalidAndOversizeReceiptWithoutReadingIt() = runBlocking {
        listOf("PREPARED","FAILED").forEach { phase -> stores { one,two,a,b ->
            val request=RetirableOperation.Update("opaque",ref,token,EventPatch(title=FieldChange.Set("Changed")))
            val prepared=one.prepare(request)
            if(phase=="FAILED")assertTrue(one.mark(prepared,"FAILED"))
            val opaque="[".repeat(1024*1024+20)
            one.writableDatabase.update("mutation_journal",ContentValues().apply {put("phase",phase);put("result_ref",opaque)},"id = ?",arrayOf("opaque"))
            assertEquals(OperationRetirementResult.NotRetired("opaque"),a.inspect(request))
            assertEquals(OperationRetirementResult.Retired("opaque"),b.retire(request))
            assertNull(two.operation("opaque")!!.resultRef)
            one.readableDatabase.rawQuery("SELECT length(result_ref) FROM mutation_journal WHERE id='opaque'",null).use {it.moveToFirst();assertEquals(opaque.length,it.getInt(0))}
        } }
    }

    @Test fun attemptedAndRecordedCommitsStayBlockedEvenWhenReceiptIsCorrupt() = runBlocking {
        listOf("ATTEMPTED","COMMITTED","VERIFIED").forEach { phase -> stores {one,_,a,b ->
            val request=RetirableOperation.Delete("blocked",ref,token);one.prepare(request)
            one.forceOperationForTest("blocked",phase,"not-json")
            val reason=if(phase=="ATTEMPTED")RetirementBlockReason.MAY_HAVE_DISPATCHED else RetirementBlockReason.RECORDED_COMMIT
            val expected=OperationRetirementResult.Blocked("blocked",reason)
            assertEquals(expected,a.retire(request));assertEquals(expected,b.inspect(request));assertEquals(phase,one.operationHeader("blocked")!!.phase)
        } }
    }

    @Test fun requestConflictsAndUnknownHeaderCannotBeUsedAsRetirementProof() = runBlocking { stores {one,_,a,b ->
        val original=RetirableOperation.Create("same",draft);assertEquals(OperationRetirementResult.Retired("same"),a.retire(original))
        assertEquals(OperationRetirementResult.Conflict("same"),b.retire(original.copy(draft=draft.copy(privacy=2))))
        assertEquals(OperationRetirementResult.Conflict("same"),b.inspect(RetirableOperation.Delete("same",ref,token)))
        val unknown=RetirableOperation.Create("unknown",draft);one.prepare(unknown);one.forceOperationForTest("unknown","FUTURE_PHASE")
        assertEquals(OperationRetirementResult.Unavailable,a.retire(unknown));assertEquals("FUTURE_PHASE",one.operationHeader("unknown")!!.phase)
        assertEquals(OperationRetirementResult.Unsupported,a.retire(RetirableOperation.Meeting("future",ref,token,MeetingCommand.CancelOwned(RecurrenceScope.THIS))))
        assertNull(one.operationHeader("future"))
    } }

    @Test fun recurringEditsStayClosedWhilePublishedMeetingEncodersRemainDistinct() = runBlocking { stores {one,_,a,b ->
        val recurring=ref.copy(recurrence=RecurrenceRef(ref.eventId,start))
        val requests=listOf<RetirableOperation>(
            RetirableOperation.Update("recurring-update",recurring,token,EventPatch(title=FieldChange.Set("Changed"))),
            RetirableOperation.Delete("recurring-delete",recurring,token))
        requests.forEach{request->
            assertEquals(OperationRetirementResult.Unsupported,a.retire(request))
            assertEquals(OperationRetirementResult.Unsupported,b.inspect(request))
            assertNull(one.operationHeader(request.operationId))
        }
        val meetings=listOf<RetirableOperation>(
            RetirableOperation.Meeting("meeting",ref,token,MeetingCommand.Respond(RecurrenceScope.THIS,MeetingResponse.YES)),
            RetirableOperation.MeetingCreate("meeting-create",token,draft,listOf(MeetingGuestInput("guest@example.invalid",MeetingGuestType.REQUIRED))))
        meetings.forEach { request ->
            assertEquals(OperationRetirementResult.Retired(request.operationId),a.retire(request))
            assertEquals(OperationRetirementResult.Retired(request.operationId),b.inspect(request))
            assertNotEquals("CREATE",one.operationHeader(request.operationId)!!.kind)
        }
        val plain=RetirableOperation.Create("plain",draft)
        assertEquals(OperationRetirementResult.Retired("plain"),a.retire(plain));val before=one.operationHeader("plain")
        assertEquals(OperationRetirementResult.Conflict("plain"),b.retire(plain.copy(draft=draft.copy(recurrence=RecurrenceSpec(RecurrencePattern.Daily)))))
        assertEquals(before,one.operationHeader("plain"))
    } }

    @Test fun exactAttemptAndMarkRejectChangedSignatureKindReceiptAndPriorPhase() = runBlocking { stores {one,two,_,_ ->
        val request=RetirableOperation.Create("exact",draft);val prepared=one.prepare(request,"first opaque receipt")
        assertFalse(one.attempt(prepared.copy(signature="0".repeat(64))))
        assertFalse(one.attempt(prepared.copy(kind="DELETE")))
        assertFalse(one.attempt(prepared.copy(resultRef="other opaque receipt")))
        assertFalse(one.attempt(prepared.copy(phase="FAILED")))
        assertTrue(two.attempt(prepared));val attempted=one.operation("exact")!!
        assertFalse(one.attempt(prepared));assertFalse(one.mark(prepared,"FAILED"))
        assertFalse(one.mark(attempted.copy(resultRef="other opaque receipt"),"FAILED"))
        assertTrue(two.mark(attempted,"COMMITTED","known receipt"))
        assertFalse(one.mark(attempted,"FAILED"));assertEquals("COMMITTED",one.operationHeader("exact")!!.phase)
    } }

    @Test fun ordinaryFingerprintsKeepTheRemoteCommandJsonAndHashBytes() {
        fun hash(kind:String,raw:String)=java.security.MessageDigest.getInstance("SHA-256")
            .digest((kind+org.json.JSONObject(raw).toString()).toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
        val create="""{"calendar":2,"event":0,"start":"2026-11-17T09:00:00Z","end":"2026-11-17T10:00:00Z","allDay":false,"zone":"Europe/London","title":"Synthetic frozen command","location":"","description":"","alerts":[],"targetIdentity":{"calendar":2,"name":"owner@example.invalid","type":"com.google","owner":"owner@example.invalid","providerId":"calendar-A"}}"""
        val delete="""{"calendar":2,"event":10,"start":"2026-11-17T09:00:00Z","end":"2026-11-17T10:00:00Z","token":"original-opaque-source-token"}"""
        val update="""{"calendar":2,"event":10,"start":"2026-11-17T09:00:00Z","end":"2026-11-17T10:00:00Z","token":"original-opaque-source-token","title":"Changed","privacy":null}"""
        assertEquals("CREATE" to hash("CREATE",create),CalendarWriter.createFingerprint(draft,1024*1024))
        assertEquals("DELETE" to hash("DELETE",delete),CalendarWriter.deleteFingerprint(ref,token))
        assertEquals("UPDATE" to hash("UPDATE",update),CalendarWriter.updateFingerprint(ref,token,EventPatch(title=FieldChange.Set("Changed"),privacy=FieldChange.Set(null)),1024*1024))
    }

    @Test fun independentHelpersRaceRetirementAgainstExactAttemptWithoutAmbiguousDispatchableState() = runBlocking {
        repeat(12) { stores {one,two,_,b ->
            val request=RetirableOperation.Create("race",draft);val prepared=one.prepare(request)
            val gate=CompletableDeferred<Unit>()
            val attempt=async(Dispatchers.IO){gate.await();one.attempt(prepared)}
            val retirement=async(Dispatchers.IO){gate.await();b.retire(request)}
            gate.complete(Unit);val sent=attempt.await();val result=retirement.await()
            if(sent){assertEquals(OperationRetirementResult.Blocked("race",RetirementBlockReason.MAY_HAVE_DISPATCHED),result);assertEquals("ATTEMPTED",two.operationHeader("race")!!.phase)}
            else {assertEquals(OperationRetirementResult.Retired("race"),result);assertEquals("ABANDONED",two.operationHeader("race")!!.phase)}
        } }
    }

    @Test fun staleFailureAndVerifiedEnrichmentCannotEraseRetirementOrNewKnownIdentity() = runBlocking { stores {one,two,a,_ ->
        val request=RetirableOperation.Create("stale",draft);val prepared=one.prepare(request,"{\"uid\":null}")
        assertTrue(one.attempt(prepared));val attempted=prepared.copy(phase="ATTEMPTED")
        assertTrue(one.mark(attempted,"FAILED"));assertEquals(OperationRetirementResult.Retired("stale"),a.retire(request))
        assertFalse(two.mark(attempted,"FAILED"));assertFalse(two.mark(attempted,"COMMITTED","{\"uid\":\"late\"}"))
        val known=RetirableOperation.Create("known",draft);val pre=one.prepare(known,"{\"uid\":null}")
        assertTrue(one.attempt(pre));assertTrue(one.mark(pre.copy(phase="ATTEMPTED"),"COMMITTED"))
        assertTrue(one.mark(one.operation("known")!!,"VERIFIED"));val before=one.operation("known")!!
        assertTrue(two.mark(before,"VERIFIED","{\"uid\":\"known\"}"));assertFalse(one.mark(before,"VERIFIED","{\"uid\":null}"))
        assertEquals("{\"uid\":\"known\"}",one.operation("known")!!.resultRef)
        assertEquals(OperationRetirementResult.Blocked("known",RetirementBlockReason.RECORDED_COMMIT),a.retire(known))
    } }

    @Test fun ordinaryFamiliesFenceLateWorkersBeforePermissionsAndAnyProviderRead() = runBlocking { stores {store,_,retirement,_ ->
        val context=RuntimeEnvironment.getApplication();val provider=CalendarWriterTest.Provider()
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY,provider)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val reader=CalendarReader(context.contentResolver,scope,stopTimeoutMillis=0)
        val ordinary=CalendarWriter(context.contentResolver,reader,store,{false})
        val requests=listOf<RetirableOperation>(RetirableOperation.Create("create",draft),
            RetirableOperation.Update("update",ref,token,EventPatch(privacy=FieldChange.Set(null))),RetirableOperation.Delete("delete",ref,token))
        try {for(request in requests){
            assertEquals(OperationRetirementResult.Retired(request.operationId),retirement.retire(request))
            val expected=MutationResult.Retired(request.operationId)
            when(request){
                is RetirableOperation.Create -> assertEquals(expected,ordinary.create(request.operationId,request.draft))
                is RetirableOperation.Update -> assertEquals(expected,ordinary.update(request.operationId,request.ref,request.token,request.patch))
                is RetirableOperation.Delete -> assertEquals(expected,ordinary.delete(request.operationId,request.ref,request.token))
                else -> error("Unsupported fixture")
            }
            assertEquals(expected,ordinary.recover(request.operationId))
        }
        assertEquals(0,provider.batchCount);assertTrue(provider.queryCounts.isEmpty())
        }finally{scope.cancel();scope.coroutineContext[Job]!!.join()}
    } }
    @Test fun crashAfterFenceLeavesDraftAndExactCleanupCannotDeleteReplacement() = runBlocking { stores {one,two,a,b ->
        val request=RetirableOperation.Create("draft-command",draft)
        val old=DraftRecord("draft-id",3,"{\"frozen\":\"original\"}",start)
        assertEquals(DraftChangeResult.Applied,one.compareAndSet(old.id,null,old))
        assertEquals(OperationRetirementResult.Retired(request.operationId),a.retire(request))
        one.close();assertEquals(old,two.load(old.id));assertEquals(OperationRetirementResult.Retired(request.operationId),b.inspect(request))
        val replacement=old.copy(payload="{\"replacement\":true}",updatedAt=start.plusSeconds(1))
        assertEquals(DraftChangeResult.Applied,two.compareAndSet(old.id,old,replacement))
        assertEquals(DraftChangeResult.Changed,one.compareAndSet(old.id,old,null));assertEquals(replacement,one.load(old.id))
    } }

    @Test fun unknownAcknowledgementRequiresInspectionOfSameTypedFence() = runBlocking { stores {one,_,_,b ->
        val request=RetirableOperation.Delete("lost-fence-ack",ref,token)
        val (kind,signature)=fingerprint(request)
        assertTrue(runCatching {one.retirement(request.operationId,kind,signature,true);error("Synthetic caller acknowledgement loss")}.isFailure)
        assertEquals(OperationRetirementResult.Retired(request.operationId),b.inspect(request))
        assertEquals(OperationRetirementResult.Conflict(request.operationId),b.inspect(request.copy(token=EditToken("changed"))))
    } }

    @Test fun actualCreateAttemptWinsBeforeFenceAndRecordedCommitRemainsBlockedAfterSourceGone() = runBlocking { stores {store,_,_,retirement ->
        val context=RuntimeEnvironment.getApplication();val provider=CalendarWriterTest.Provider()
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY,provider)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val reader=CalendarReader(context.contentResolver,scope,stopTimeoutMillis=0)
        val writer=CalendarWriter(context.contentResolver,reader,store,{true})
        val request=RetirableOperation.Create("actual-create",draft)
        var during:OperationRetirementResult?=null
        provider.beforeBatch={during=runBlocking {retirement.retire(request)}}
        try {
            assertTrue(writer.create(request.operationId,draft) is MutationResult.LocalSaved)
            assertEquals(OperationRetirementResult.Blocked(request.operationId,RetirementBlockReason.MAY_HAVE_DISPATCHED),during)
            provider.events.clear();provider.resetQueryCounts()
            assertEquals(OperationRetirementResult.Blocked(request.operationId,RetirementBlockReason.RECORDED_COMMIT),retirement.retire(request))
            assertTrue(provider.queryCounts.isEmpty());assertEquals(1,provider.batchCount)
        }finally{scope.cancel();scope.coroutineContext[Job]!!.join()}
    } }

    @Test fun retirementDuringCreatePreflightStopsOldWorkerBeforeJournalPreparationOrBatch() = runBlocking { stores {store,_,_,retirement ->
        val context=RuntimeEnvironment.getApplication();val provider=CalendarWriterTest.Provider()
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY,provider)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val reader=CalendarReader(context.contentResolver,scope,stopTimeoutMillis=0)
        val writer=CalendarWriter(context.contentResolver,reader,store,{true})
        val request=RetirableOperation.Create("in-flight",draft)
        provider.afterQuery={path -> if(path=="calendars") {
            provider.afterQuery=null
            assertEquals(OperationRetirementResult.Retired(request.operationId),runBlocking {retirement.retire(request)})
        }}
        try {
            assertEquals(MutationResult.Retired(request.operationId),writer.create(request.operationId,draft))
            assertEquals("ABANDONED",store.operationHeader(request.operationId)!!.phase)
            assertEquals(0,provider.batchCount);assertTrue(provider.events.isEmpty())
            assertTrue(provider.queryCounts.keys.all {it=="calendars"})
        }finally{scope.cancel();scope.coroutineContext[Job]!!.join()}
    } }

    @Test fun retirementDuringUpdateOrDeletePreflightStopsDispatchAndPreservesTheSource() = runBlocking {
        for(delete in listOf(false,true))stores {store,_,_,retirement ->
            val context=RuntimeEnvironment.getApplication();val provider=CalendarWriterTest.Provider()
            ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY,provider)
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
            val writer=CalendarWriter(context.contentResolver,CalendarReader(context.contentResolver,scope,stopTimeoutMillis=0),store,{true})
            try {
                val selected=(writer.create("seed",draft) as MutationResult.LocalSaved).ref
                val edit=(writer.loadForEdit(selected) as EditReadResult.Ready).event
                val patch=EventPatch(title=FieldChange.Set("Never written"))
                val request=if(delete)RetirableOperation.Delete("in-flight",selected,edit.token) else RetirableOperation.Update("in-flight",selected,edit.token,patch)
                val before=provider.events.map{it.toMap()};provider.batchCount=0
                provider.afterQuery={path->if(path=="calendars"){
                    provider.afterQuery=null
                    assertEquals(OperationRetirementResult.Retired("in-flight"),runBlocking{retirement.retire(request)})
                }}
                val result=if(delete)writer.delete("in-flight",selected,edit.token) else writer.update("in-flight",selected,edit.token,patch)
                assertEquals(MutationResult.Retired("in-flight"),result)
                assertEquals(before,provider.events);assertEquals(0,provider.batchCount)
                assertEquals("ABANDONED",store.operationHeader("in-flight")!!.phase)
            } finally {scope.cancel();scope.coroutineContext[Job]!!.join()}
        }
    }

    @Test fun authoredRecurringCreateRetirementBindsExactRuleAndFencesBeforePermissionsOrProviderReads() = runBlocking { stores {store,_,a,b ->
        val recurring=draft.copy(recurrence=RecurrenceSpec(RecurrencePattern.Daily,end=RecurrenceEnd.Count(3)))
        val request=RetirableOperation.Create("recurring",recurring)
        assertNotEquals(CalendarWriter.createFingerprint(draft,1024*1024),CalendarWriter.createFingerprint(recurring,1024*1024))
        assertEquals(OperationRetirementResult.Retired(request.operationId),a.retire(request))
        assertEquals(OperationRetirementResult.Retired(request.operationId),b.inspect(request))
        val changed=request.copy(draft=recurring.copy(recurrence=RecurrenceSpec(RecurrencePattern.Daily,end=RecurrenceEnd.Count(4))))
        assertEquals(OperationRetirementResult.Conflict(request.operationId),b.inspect(changed))
        assertEquals(OperationRetirementResult.Conflict(request.operationId),b.retire(request.copy(draft=draft)))
        val context=RuntimeEnvironment.getApplication();val provider=CalendarWriterTest.Provider()
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY,provider)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val writer=CalendarWriter(context.contentResolver,CalendarReader(context.contentResolver,scope,stopTimeoutMillis=0),store,{false})
        try {
            assertEquals(MutationResult.Retired(request.operationId),writer.create(request.operationId,request.draft))
            assertEquals(MutationResult.Conflict(null),writer.create(request.operationId,changed.draft))
            assertEquals(0,provider.batchCount);assertTrue(provider.queryCounts.isEmpty())
        } finally {scope.cancel();scope.coroutineContext[Job]!!.join()}
    } }

}
