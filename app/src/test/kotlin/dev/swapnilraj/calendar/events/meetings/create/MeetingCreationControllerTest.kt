package dev.swapnilraj.calendar.events.meetings.create

import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.*
import dev.swapnilraj.calendar.ui.events.meetings.create.*
import java.time.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MeetingCreationControllerTest {
    @Test fun preparationAndOpenNeverReadCalendarRequestPermissionOrWriteEvent() = runBlocking {
        val f = Fixture(); val launch = f.prepare(); val record = f.record(); val c = f.controller(launch); c.load()
        assertEquals(f.fields,c.state.fields!!.event); assertFalse(c.canSave); c.save()
        assertEquals(0,f.reads); assertEquals(0,f.permissions); assertTrue(f.calls.isEmpty()); assertEquals(record,f.record())
        assertEquals(0,f.store.legacy); assertEquals(launch,MeetingCreationDraftCodec.describe(record))
    }
    @Test fun rawInvalidGuestsDatesAndRecurrenceSurviveProcessReopenWithoutNormalization() = runBlocking {
        val f = Fixture(); val c = f.open()
        val raw = f.fields.copy(startDate="2028-",endTime="1:",recurrence=RecurrenceEditorFields(frequency="FUTURE",count="partial"))
        val guest = MeetingCreationGuestFields(email=" Bad@",name=" name ",type="PARTIAL")
        c.changeFields(raw); c.changeGuests(listOf(guest)); val record = f.record()
        val reopened = f.controller(f.launch()); reopened.load()
        assertEquals(raw,reopened.state.fields!!.event); assertEquals(listOf(guest),reopened.state.fields!!.guests)
        assertEquals(record,f.record()); assertTrue(f.calls.isEmpty()); assertEquals(0,f.reads)
    }
    @Test fun factoryCollisionNeverOverwritesEvenCompatibleRecordAndUnknownAckNeedsExactProof() = runBlocking {
        val f=Fixture(); f.prepare(); val old=f.record()
        assertEquals(MeetingCreationPreparation.RetainedForReview,prepareMeetingCreationDraft(f.fields.copy(title="Other"),"draft",f.store,f.clock))
        assertEquals(old,f.record())
        val g=Fixture(); g.store.unknownAfter=true
        assertTrue(prepareMeetingCreationDraft(g.fields,"draft",g.store,g.clock) is MeetingCreationPreparation.Ready)
        val h=Fixture(); h.store.unknownBefore=true
        assertEquals(MeetingCreationPreparation.Unavailable,prepareMeetingCreationDraft(h.fields,"draft",h.store,h.clock))
        assertTrue(h.store.rows.isEmpty())
    }
    @Test fun missingOrWrongGenerationRestoreNeverReseedsOrLoadsSource() = runBlocking {
        val f=Fixture(); val launch=f.prepare(); f.store.rows.clear()
        val c=f.controller(launch); c.load(); c.save(); assertEquals(MeetingCreationProblem.DRAFT_UNAVAILABLE,c.state.problem)
        assertTrue(f.store.rows.isEmpty()); assertEquals(0,f.reads)
        val g=Fixture(); val wrong=g.prepare().copy(generation=java.util.UUID.randomUUID().toString()); val old=g.record()
        val other=g.controller(wrong); other.load(); assertEquals(MeetingCreationProblem.STALE_DRAFT,other.state.problem)
        other.requestDiscard(); other.confirmDiscard(); assertEquals(old,g.record())
    }
    @Test fun unknownSchemaOrFrozenPayloadRetainsExactRecordAndOffersNoDiscard() = runBlocking {
        for (key in listOf("schema","feature")) {
            val f=Fixture(); val launch=f.prepare(); val old=f.record(); val json=JSONObject(old.payload)
            if(key=="schema") json.put(key,9) else json.put(key,"future-feature")
            val bad=old.copy(payload=json.toString()); f.store.rows["draft"]=bad
            val c=f.controller(launch); c.load(); assertEquals(MeetingCreationProblem.DRAFT_REVIEW,c.state.problem)
            assertFalse(c.canDiscard); c.save(); c.requestDiscard(); c.confirmDiscard(); assertEquals(bad,f.record())
        }
    }
    @Test fun calendarSelectionOnlyUsesBackendIssuedExactNamespaceAndDoesNotSave() = runBlocking {
        val f=Fixture(); val c=f.open(); c.selectCalendar(f.calendar)
        assertEquals(1,f.reads); assertEquals(0,f.permissions); assertTrue(f.calls.isEmpty())
        assertEquals(f.calendar.identity,c.state.fields!!.event.targetIdentity)
        assertEquals(f.source.allowedGuestTypes,c.state.allowedGuestTypes)
        f.source=f.source.copy(calendar=f.calendar.copy(providerId="foreign")); c.selectCalendar(f.calendar)
        assertEquals(MeetingCreationProblem.SOURCE_CHANGED,c.state.problem); c.save(); assertTrue(f.calls.isEmpty())
    }
    @Test fun sourceTokenNamespaceAndAllowedTypeDriftRetainRawAndBlockSave() = runBlocking {
        for (kind in 0..2) {
            val f=Fixture(); f.ready(); val old=f.record()
            f.source=when(kind) {
                0 -> f.source.copy(token=EditToken("changed"))
                1 -> f.source.copy(calendar=f.calendar.copy(providerId="replacement"))
                else -> f.source.copy(allowedGuestTypes=setOf(MeetingGuestType.OPTIONAL))
            }
            val c=f.controller(f.launch()); c.load(); c.save()
            assertEquals(MeetingCreationProblem.SOURCE_CHANGED,c.state.problem); assertEquals(old,f.record()); assertTrue(f.calls.isEmpty()); assertEquals(0,f.permissions)
        }
    }
    @Test fun permissionGrantNeverSubmitsOrFreezesUntilExplicitSecondSave() = runBlocking {
        val f=Fixture(); val c=f.ready(); f.granted=false; c.save()
        assertEquals(MeetingCreationProblem.PERMISSION,c.state.problem); assertFalse(c.frozen); assertTrue(f.calls.isEmpty())
        f.granted=true; assertTrue(f.calls.isEmpty()); c.save(); assertEquals(1,f.calls.size); assertTrue(c.frozen)
    }
    @Test fun exactFrozenCommandIsDurableBeforeDispatchAndDoubleSaveIsSingleFlight() = runBlocking {
        val f=Fixture(); val c=f.ready(); f.permissionGate=CompletableDeferred(); f.callGate=CompletableDeferred()
        val job=launch(start=CoroutineStart.UNDISPATCHED) { c.save() }; c.save(); assertEquals(1,f.permissions); assertTrue(f.calls.isEmpty())
        f.permissionGate!!.complete(Unit); yield(); val frozen=MeetingCreationDraftCodec.decode(f.record()).frozen!!
        assertEquals(frozen.draft,f.calls.single().draft); assertEquals(frozen.guests,f.calls.single().guests); assertEquals(frozen.token,f.calls.single().token)
        assertEquals(frozen.operationId,f.calls.single().id); assertTrue(f.launch().needsRecovery)
        c.save(); c.changeGuests(emptyList()); assertEquals(1,f.calls.size); assertEquals(frozen,MeetingCreationDraftCodec.decode(f.record()).frozen)
        f.callGate!!.complete(Unit); job.join()
    }
    @Test fun validationPreservesRawAndBlocksUnsupportedRecurrenceGuestFreeDuplicatesAndDst() = runBlocking {
        for (kind in 0..6) {
            val f=Fixture(); val c=f.ready()
            when(kind) {
                0 -> c.changeFields(c.state.fields!!.event.copy(recurrence=RecurrenceEditorFields(frequency="DAILY")))
                1 -> c.changeGuests(emptyList())
                2 -> c.changeGuests(listOf(f.guest,f.guest.copy(rowId=java.util.UUID.randomUUID().toString(),email="GUEST@example.test")))
                3 -> c.changeGuests(listOf(f.guest.copy(type="ORGANIZER")))
                4 -> c.changeGuests(listOf(f.guest.copy(email=" guest@example.test")))
                5 -> c.changeFields(c.state.fields!!.event.copy(zone="America/New_York",startDate="2028-03-12",endDate="2028-03-12",startTime="02:30",endTime="04:30"))
                6 -> c.changeFields(c.state.fields!!.event.copy(zone="America/New_York",startDate="2028-11-05",endDate="2028-11-05",startTime="01:30",endTime="02:30",startOffset=""))
            }
            val exact=f.record(); c.save(); assertEquals(exact,f.record()); assertTrue(f.calls.isEmpty()); assertEquals(0,f.permissions)
        }
    }
    @Test fun frozenRestoreIsSourceFreeAndOnlyExplicitMatchingRecoveryProofAllowsExactRetry() = runBlocking {
        val f=Fixture(); f.ready().save(); val frozen=MeetingCreationDraftCodec.decode(f.record()).frozen!!; val reads=f.reads
        val c=f.controller(f.launch().copy(needsRecovery=false)); c.load(); assertEquals(reads,f.reads)
        c.save(); c.retryOriginal(); assertEquals(1,f.calls.size)
        f.recovery=MeetingMutationResult.Common(MutationResult.SafeToRetry("wrong")); c.recover(); assertFalse(c.state.retryReady)
        f.recovery=MeetingMutationResult.Common(MutationResult.SafeToRetry(frozen.operationId)); c.recover(); assertTrue(c.state.retryReady); assertEquals(1,f.calls.size)
        c.retryOriginal(); assertEquals(f.calls.first(),f.calls.last()); assertEquals(2,f.calls.size)
    }
    @Test fun unknownRecoveryAndDefiniteErrorsNeverInventRetryOrFreshOperation() = runBlocking {
        val f=Fixture(); f.ready().save(); val c=f.controller(f.launch()); c.load(); val exact=f.record()
        for (result in listOf(MeetingMutationResult.Common(MutationResult.Unavailable),MeetingMutationResult.Common(MutationResult.PermissionRequired),MeetingMutationResult.NeedsReview("private backend detail"),MeetingMutationResult.Invalid("raw reason"))) {
            f.recovery=result; c.recover(); c.retryOriginal(); assertFalse(c.state.retryReady); assertEquals(exact,f.record()); assertEquals(1,f.calls.size)
        }
    }
    @Test fun staleControllerCasAndUnknownReadbackNeverDispatchOrOverwriteSuccessor() = runBlocking {
        val f=Fixture(); val a=f.ready(); val b=f.controller(f.launch()); b.load()
        a.changeFields(a.state.fields!!.event.copy(title="New raw")); val newest=f.record(); b.save()
        assertEquals(newest,f.record()); assertTrue(f.calls.isEmpty()); assertEquals(MeetingCreationProblem.STALE_DRAFT,b.state.problem)
        val g=Fixture(); val c=g.ready(); g.store.unknownAfter=true; g.store.failRead=true; c.save()
        assertTrue(g.calls.isEmpty()); assertEquals(MeetingCreationProblem.DRAFT_UNAVAILABLE,c.state.problem)
        g.store.failRead=false; assertNotNull(MeetingCreationDraftCodec.decode(g.record()).frozen)
    }
    @Test fun committedResultRequiresExactCalendarBoundsKindPositiveIdAndThisScope() = runBlocking {
        for (kind in 0..5) {
            val f=Fixture(); val c=f.ready(); val good=f.ref
            f.next=MeetingMutationResult.LocalSaved(when(kind) {
                0 -> good.copy(calendarId=CalendarId(8))
                1 -> good.copy(eventId=0)
                2 -> good.copy(instanceStart=good.instanceStart.plusSeconds(1))
                3 -> good.copy(instanceEnd=good.instanceEnd.plusSeconds(1))
                4 -> good.copy(recurrence=RecurrenceRef(4,good.instanceStart))
                else -> good
            },if(kind==5) RecurrenceScope.ALL else RecurrenceScope.THIS)
            c.save(); assertNull(c.state.localSaved); assertTrue(f.store.rows.isNotEmpty()); assertEquals(MeetingCreationProblem.UNCERTAIN,c.state.problem)
        }
    }
    @Test fun knownLocalSaveAcknowledgesByExactCleanupWithoutRetirementOrSecondCreate() = runBlocking {
        val f=Fixture(); val c=f.ready(); f.next=MeetingMutationResult.LocalSaved(f.ref,RecurrenceScope.THIS); c.save()
        assertEquals(f.ref,c.state.localSaved); assertFalse(c.canDiscard); c.acknowledgeLocalSave(); assertTrue(f.store.rows.isEmpty()); c.save(); assertEquals(1,f.calls.size); assertEquals(0,f.retireCalls)
    }
    @Test fun allDaySuccessUsesUtcExclusiveDateBounds() = runBlocking {
        val f=Fixture(); val c=f.ready(); c.changeFields(c.state.fields!!.event.copy(allDay=true,startDate="2028-06-15",endDate="2028-06-16",zone="Pacific/Auckland"))
        val start=LocalDate.parse("2028-06-15").atStartOfDay(ZoneOffset.UTC).toInstant()
        f.next=MeetingMutationResult.LocalSaved(EventRef(f.calendar.id,42,start,start.plusSeconds(172800)),RecurrenceScope.THIS)
        c.save(); assertNotNull(c.state.localSaved); assertEquals(EventTime.AllDay(CalendarDateRange(LocalDate.parse("2028-06-15"),LocalDate.parse("2028-06-17"))),f.calls.single().draft.time)
    }
    @Test fun unsubmittedDiscardIsExplicitExactAndRequiresNoCalendarOrPermission() = runBlocking {
        val f=Fixture(); val c=f.open(); val old=f.record(); c.confirmDiscard(); assertEquals(old,f.record())
        c.requestDiscard(); c.confirmDiscard(); assertTrue(c.state.discarded); assertTrue(f.store.rows.isEmpty()); assertEquals(0,f.reads); assertEquals(0,f.permissions); assertEquals(0,f.retireCalls)
    }
    @Test fun frozenWithoutRetirementStaysLockedAndRetirementUsesExactOriginalRequest() = runBlocking {
        val f=Fixture(); f.ready().save(); val exact=f.record(); val c=f.controller(f.launch(),withRetirement=false); c.load()
        assertFalse(c.canDiscard); c.requestDiscard(); c.confirmDiscard(); assertEquals(exact,f.record())
        val other=f.controller(f.launch()); other.load(); f.retired=true; other.requestDiscard(); other.confirmDiscard()
        assertEquals(MeetingCreationDraftCodec.decode(exact).frozen!!.retirement(),f.retiredRequest)
        assertTrue(other.state.discarded); assertTrue(f.store.rows.isEmpty()); assertEquals(1,f.calls.size)
    }
    @Test fun retirementBlockedOrUnknownNeverDeletesAndMatchingInspectCanConfirmUnknownAck() = runBlocking {
        val f=Fixture(); val c=f.ready(); c.save(); val exact=f.record(); c.requestDiscard(); c.confirmDiscard()
        assertEquals(exact,f.record()); assertEquals(MeetingCreationProblem.RETIRE_BLOCKED_DISPATCH,c.state.problem)
        f.unknownRetirement=true; f.retired=true; c.requestDiscard(); c.confirmDiscard()
        assertEquals(1,f.inspects); assertTrue(f.store.rows.isEmpty()); assertTrue(c.state.discarded)
    }
    @Test fun retirementCleanupChangedOrUnreadableNeverDeletesReplacementOrReportsDiscarded() = runBlocking {
        val f=Fixture(); val c=f.ready(); c.save(); f.retired=true
        f.store.beforeCas={ _, replacement -> if(replacement==null) f.store.rows["draft"]=f.record().copy(updatedAt=f.clock.instant().plusSeconds(1)) }
        c.requestDiscard(); c.confirmDiscard(); assertFalse(c.state.discarded); assertFalse(c.state.retryReady); assertEquals(MeetingCreationProblem.STALE_DRAFT,c.state.problem)
        val g=Fixture(); val d=g.ready(); d.save(); g.retired=true; g.store.unknownBefore=true; g.store.failRead=true
        d.requestDiscard(); d.confirmDiscard(); assertFalse(d.state.discarded); assertTrue(g.store.rows.isNotEmpty())
    }
}

private class Fixture {
    val clock=Clock.fixed(Instant.parse("2028-06-01T00:00:00Z"),ZoneOffset.UTC)
    val start=Instant.parse("2028-06-15T09:00:00Z")
    val calendar=CalendarInfo(CalendarId(2),"Work",1,CalendarAccount("self@example.test","Fixture"),"self@example.test",ZoneOffset.UTC,500,true,true,providerId="uid-A")
    val fields=EventEditorController.fieldsFor(EventDraft(calendar.id,EventTime.Timed(start,start.plusSeconds(3600)),ZoneOffset.UTC,"Planning",targetIdentity=calendar.identity))
    val guest=MeetingCreationGuestFields(email="guest@example.test",name="Guest",type="REQUIRED")
    val ref=EventRef(calendar.id,42,start,start.plusSeconds(3600))
    var source=MeetingCreation(calendar,EditToken("source-token"),setOf(MeetingGuestType.REQUIRED,MeetingGuestType.OPTIONAL))
    val store=AtomicCreationStore(); var reads=0; var permissions=0; var granted=true
    var permissionGate:CompletableDeferred<Unit>?=null; var callGate:CompletableDeferred<Unit>?=null
    data class Call(val id:String,val token:EditToken,val draft:EventDraft,val guests:List<MeetingGuestInput>)
    val calls=mutableListOf<Call>()
    var next:MeetingMutationResult=MeetingMutationResult.Common(MutationResult.Unavailable)
    var recovery:MeetingMutationResult=MeetingMutationResult.Common(MutationResult.Unavailable)
    var retired=false; var unknownRetirement=false; var retireCalls=0; var inspects=0; var retiredRequest:RetirableOperation?=null
    val retirement=object:OperationRetirement {
        override suspend fun retire(request:RetirableOperation):OperationRetirementResult { retireCalls++; retiredRequest=request
            return if(unknownRetirement) OperationRetirementResult.Unavailable else if(retired) OperationRetirementResult.Retired(request.operationId) else OperationRetirementResult.Blocked(request.operationId,RetirementBlockReason.MAY_HAVE_DISPATCHED) }
        override suspend fun inspect(request:RetirableOperation):OperationRetirementResult { inspects++; return if(retired) OperationRetirementResult.Retired(request.operationId) else OperationRetirementResult.Unavailable }
    }
    val meetings=object:CalendarMeetings {
        override suspend fun loadForCreate(target:CalendarIdentity):MeetingReadResult<MeetingCreation> { reads++; return MeetingReadResult.Ready(source) }
        override suspend fun loadForEdit(selected:EventRef):MeetingReadResult<EditableMeeting> = error("wrong feature")
        override suspend fun create(operationId:String,token:EditToken,draft:EventDraft,guests:List<MeetingGuestInput>):MeetingMutationResult {
            calls+=Call(operationId,token,draft,guests); assertNotNull(MeetingCreationDraftCodec.decode(record()).frozen); callGate?.await(); return next
        }
        override suspend fun mutate(operationId:String,selected:EventRef,token:EditToken,command:MeetingCommand):MeetingMutationResult = error("wrong feature")
        override suspend fun recover(operationId:String)=recovery
    }
    suspend fun prepare()=(prepareMeetingCreationDraft(fields,"draft",store,clock) as MeetingCreationPreparation.Ready).launch
    fun record()=store.rows.getValue("draft")
    fun launch()=MeetingCreationDraftCodec.describe(record())!!
    fun controller(launch:MeetingCreationLaunch,withRetirement:Boolean=true)=MeetingCreationController(launch,meetings,store,{ permissions++; permissionGate?.await(); granted },if(withRetirement) retirement else null,clock)
    suspend fun open()=controller(prepare()).also { it.load() }
    suspend fun ready()=open().also { it.selectCalendar(calendar); it.changeGuests(listOf(guest)); it.acceptFixedGuestPolicy(true) }
}
private class AtomicCreationStore:AtomicDraftStore {
    val rows=mutableMapOf<String,DraftRecord>(); var legacy=0; var unknownBefore=false; var unknownAfter=false; var failRead=false
    var beforeCas:suspend (DraftRecord?,DraftRecord?)->Unit={_,_->}
    override suspend fun load(id:String):DraftRecord? { if(failRead) error("unreadable"); return rows[id] }
    override suspend fun list()=rows.values.toList()
    override suspend fun upsert(record:DraftRecord) { legacy++; error("no blind upsert") }
    override suspend fun delete(id:String) { legacy++; error("no blind delete") }
    override suspend fun compareAndSet(id:String,expected:DraftRecord?,replacement:DraftRecord?):DraftChangeResult {
        beforeCas(expected,replacement); if(unknownBefore) return DraftChangeResult.Unavailable
        if(rows[id]!=expected) return DraftChangeResult.Changed
        if(replacement==null) rows.remove(id) else rows[id]=replacement
        return if(unknownAfter) DraftChangeResult.Unavailable else DraftChangeResult.Applied
    }
}
