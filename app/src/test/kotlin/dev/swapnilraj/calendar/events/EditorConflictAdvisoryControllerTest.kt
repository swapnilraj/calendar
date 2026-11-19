package dev.swapnilraj.calendar.events

import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.*
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

/** A deliberately retained collector can deliver late data after cancellation: epoch checks must win. */
@OptIn(InternalCoroutinesApi::class)
internal class ConflictAdvisoryFeed : CalendarSchedulingConflicts {
    val requests=mutableListOf<ConflictReadRequest>()
    val collectors=mutableListOf<FlowCollector<CalendarReadState<ConflictSnapshot>>>()
    var failure:Exception?=null
    var cancelled=0
    override fun observe(request:ConflictReadRequest):Flow<CalendarReadState<ConflictSnapshot>> {
        failure?.let {throw it}
        requests+=request
        return object:Flow<CalendarReadState<ConflictSnapshot>> {
            override suspend fun collect(collector:FlowCollector<CalendarReadState<ConflictSnapshot>>) {
                collectors+=collector
                try {awaitCancellation()} finally {cancelled++}
            }
        }
    }
    suspend fun emit(index:Int,value:CalendarReadState<ConflictSnapshot>) {collectors[index].emit(value)}
    fun snapshot(index:Int,generation:Long=1,candidates:List<ConflictCandidate> = emptyList(),
        coverage:ConflictCoverage=ConflictCoverage.Complete,namespace:String="adapter") =
        ConflictSnapshot(requests[index].context(ConflictSourceRevision(namespace,generation)),candidates,coverage)
}

internal object ConflictAdvisoryFixture {
    val identity=CalendarIdentity(CalendarId(2),CalendarAccount("synthetic","local"),null, "calendar-A")
    val raw=EditorTemporalInput("2026-06-01","2026-06-01","10:00","11:00",false,"UTC")
    val binding=EditorConflictBinding("generation-A","revision-A",raw,identity,null)
    fun input(binding:EditorConflictBinding?=this.binding,raw:EditorTemporalInput?=binding?.input,
        calendars:Set<CalendarIdentity>?=setOf(identity),zone:ZoneId=ZoneOffset.UTC,
        visible:Boolean=true,permission:Boolean=true,pending:Boolean=false,sourceReady:Boolean=true,
        zoneAuthored:Boolean=false,first:Boolean=false,host:Long=0) =
        EditorConflictAdvisoryInput(binding,raw,calendars,zone,visible,permission,pending,sourceReady,zoneAuthored,first,host)
    fun candidate(id:Long=10,time:EventTime=raw.normalizedTime(),availability:ConflictAvailability=ConflictAvailability.BUSY,
        status:ConflictEventStatus=ConflictEventStatus.ACTIVE,participation:ConflictParticipation=ConflictParticipation.ACCEPTED):ConflictCandidate {
        val interval=time as EventTime.Timed
        return ConflictCandidate(identity,EventRef(identity.calendarId,id,interval.start,interval.end),time,availability,status,participation)
    }
}

class EditorConflictAdvisoryControllerTest {
    private suspend fun fixture(block:suspend(ConflictAdvisoryFeed,EditorConflictAdvisoryController)->Unit) {
        val feed=ConflictAdvisoryFeed();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val controller=EditorConflictAdvisoryController(feed,scope)
        try {block(feed,controller)} finally {controller.close();scope.cancel()}
    }
    private fun shown(c:EditorConflictAdvisoryController,i:EditorConflictAdvisoryInput)=c.presentation(i)

    @Test fun oldControllerClearCannotRenderOnNewControllerWithSameInputAndEpoch()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val oldFeed=ConflictAdvisoryFeed();val newFeed=ConflictAdvisoryFeed()
        val old=EditorConflictAdvisoryController(oldFeed,scope);val current=EditorConflictAdvisoryController(newFeed,scope)
        try {
            val sameInput=ConflictAdvisoryFixture.input()
            old.update(sameInput)
            oldFeed.emit(0,CalendarReadState.Ready(oldFeed.snapshot(0,namespace="old-source")))
            val heldClear=old.state.value
            assertTrue(old.presentation(sameInput,heldClear).canReportNoConflict)
            current.update(sameInput)
            newFeed.emit(0,CalendarReadState.Ready(newFeed.snapshot(0,namespace="new-source",candidates=listOf(ConflictAdvisoryFixture.candidate()))))
            assertEquals(heldClear.epoch,current.state.value.epoch)
            assertEquals(1,current.presentation(sameInput).knownConflicts)
            assertFalse("A retained old-controller clear observation must be masked before any effect",current.presentation(sameInput,heldClear).canReportNoConflict)
        } finally {old.close();current.close();scope.cancel()}
    }
    @Test fun renderMasksChangedTimeBeforeUpdateOrEffectCanRun()=runBlocking {fixture {f,c->
        val a=ConflictAdvisoryFixture.input();c.update(a)
        f.emit(0,CalendarReadState.Ready(f.snapshot(0,candidates=listOf(ConflictAdvisoryFixture.candidate()))))
        val observed=c.state.value;assertEquals(1,c.presentation(a,observed).knownConflicts)
        val raw=ConflictAdvisoryFixture.raw.copy(startTime="09:00")
        val b=ConflictAdvisoryFixture.input(binding=ConflictAdvisoryFixture.binding.copy(input=raw))
        assertEquals(EditorConflictAdvisoryStatus.LOADING,c.presentation(b,observed).status)
        assertEquals(0,c.presentation(b,observed).knownConflicts)
        assertEquals(1,f.requests.size)
    }}
    @Test fun pendingProjectionAndChangeBackOwnNewEpochsBeforePersistenceCatchesUp()=runBlocking {fixture {f,c->
        val a=ConflictAdvisoryFixture.input();c.update(a);val old=f.requests[0]
        val pending=ConflictAdvisoryFixture.input(raw=ConflictAdvisoryFixture.raw.copy(startTime="09:00"),pending=true)
        c.update(pending);assertEquals(EditorConflictAdvisoryStatus.PENDING_INPUT,shown(c,pending).status)
        assertEquals(1,f.requests.size)
        c.update(a);assertEquals(2,f.requests.size)
        assertNotEquals(old.draftRevision,f.requests[1].draftRevision)
        f.emit(0,CalendarReadState.Ready(f.snapshot(0,candidates=listOf(ConflictAdvisoryFixture.candidate()))))
        assertEquals(EditorConflictAdvisoryStatus.LOADING,shown(c,a).status)
        f.emit(1,CalendarReadState.Ready(f.snapshot(1)));assertTrue(shown(c,a).canReportNoConflict)
    }}
    @Test fun lateOldRequestErrorsAndReadyNeverRepublishAcrossLeaseChange()=runBlocking {fixture {f,c->
        val a=ConflictAdvisoryFixture.input();c.update(a)
        val b=ConflictAdvisoryFixture.input(binding=ConflictAdvisoryFixture.binding.copy(revision="revision-B"));c.update(b)
        f.emit(1,CalendarReadState.Ready(f.snapshot(1)));assertTrue(shown(c,b).canReportNoConflict)
        f.emit(0,CalendarReadState.PermissionRequired)
        f.emit(0,CalendarReadState.Ready(f.snapshot(0,candidates=listOf(ConflictAdvisoryFixture.candidate()))))
        assertTrue(shown(c,b).canReportNoConflict);assertTrue(f.cancelled>=1)
    }}
    @Test fun sourceLoadingClearsCountsAndOldSourceGenerationCannotRestoreThem()=runBlocking {fixture {f,c->
        val i=ConflictAdvisoryFixture.input();c.update(i)
        f.emit(0,CalendarReadState.Ready(f.snapshot(0,generation=7,candidates=listOf(ConflictAdvisoryFixture.candidate()))))
        assertEquals(1,shown(c,i).knownConflicts)
        f.emit(0,CalendarReadState.Loading);assertEquals(EditorConflictAdvisoryStatus.LOADING,shown(c,i).status)
        f.emit(0,CalendarReadState.Ready(f.snapshot(0,generation=7,candidates=listOf(ConflictAdvisoryFixture.candidate()))))
        assertEquals(0,shown(c,i).knownConflicts);assertFalse(shown(c,i).canReportNoConflict)
        f.emit(0,CalendarReadState.Ready(f.snapshot(0,generation=8)));assertTrue(shown(c,i).canReportNoConflict)
    }}
    @Test fun sourceNamespaceReplacementAndWrongRequestCannotCertifyClear()=runBlocking {fixture {f,c->
        val i=ConflictAdvisoryFixture.input();c.update(i);f.emit(0,CalendarReadState.Ready(f.snapshot(0)))
        f.emit(0,CalendarReadState.Ready(f.snapshot(0,generation=2,namespace="replacement")))
        assertEquals(EditorConflictAdvisoryStatus.UNAVAILABLE,shown(c,i).status)
        val wrong=ConflictReadRequest(f.requests[0].draftRevision,ConflictAdvisoryFixture.raw.normalizedTime(),ZoneId.of("Asia/Tokyo"),setOf(ConflictAdvisoryFixture.identity))
        f.emit(0,CalendarReadState.Ready(ConflictSnapshot(wrong.context(ConflictSourceRevision("adapter",3)),emptyList(),ConflictCoverage.Complete)))
        assertFalse(shown(c,i).canReportNoConflict)
    }}
    @Test fun onlyExactEditedReferenceIsExcludedAndCreateRetainsAllCandidates()=runBlocking {fixture {f,c->
        val first=ConflictAdvisoryFixture.candidate();val other=ConflictAdvisoryFixture.candidate(time=EventTime.Timed(first.ref.instanceStart.plusSeconds(60),first.ref.instanceEnd.plusSeconds(60)))
        val edit=ConflictAdvisoryFixture.input(binding=ConflictAdvisoryFixture.binding.copy(editedRef=first.ref));c.update(edit)
        assertEquals(first.ref,f.requests[0].exclude)
        f.emit(0,CalendarReadState.Ready(f.snapshot(0,candidates=listOf(first,other))))
        assertEquals(1,shown(c,edit).knownConflicts)
        val create=ConflictAdvisoryFixture.input();c.update(create);assertNull(f.requests[1].exclude)
        f.emit(1,CalendarReadState.Ready(f.snapshot(1,candidates=listOf(first,other))))
        assertEquals(2,shown(c,create).knownConflicts)
    }}
    @Test fun partialAndUnknownOccupancyAreExplicitAndCannotBecomeClear()=runBlocking {fixture {f,c->
        val i=ConflictAdvisoryFixture.input();c.update(i)
        val candidates=listOf(ConflictAdvisoryFixture.candidate(),ConflictAdvisoryFixture.candidate(11,availability=ConflictAvailability.UNKNOWN),
            ConflictAdvisoryFixture.candidate(12,availability=ConflictAvailability.FREE),ConflictAdvisoryFixture.candidate(13,participation=ConflictParticipation.DECLINED))
        f.emit(0,CalendarReadState.Ready(f.snapshot(0,candidates=candidates,coverage=ConflictCoverage.Partial(setOf(ConflictCoverageProblem.ACCESS_LIMITED)))))
        val result=shown(c,i);assertEquals(1,result.knownConflicts);assertEquals(1,result.possibleConflicts);assertFalse(result.complete);assertFalse(result.canReportNoConflict)
        f.emit(0,CalendarReadState.Ready(f.snapshot(0,generation=2,coverage=ConflictCoverage.Partial(setOf(ConflictCoverageProblem.MISSING_CALENDAR)))))
        assertFalse(shown(c,i).canReportNoConflict)
    }}
    @Test fun explicitEmptyMissingNamespacesAndCurrentCompleteEmptyStayDistinct()=runBlocking {fixture {f,c->
        val empty=ConflictAdvisoryFixture.input(calendars=emptySet());c.update(empty)
        assertEquals(EditorConflictAdvisoryStatus.NO_CALENDARS_SELECTED,shown(c,empty).status);assertTrue(f.requests.isEmpty())
        val missing=ConflictAdvisoryFixture.input(calendars=null);c.update(missing)
        assertEquals(EditorConflictAdvisoryStatus.SELECTION_UNAVAILABLE,shown(c,missing).status);assertTrue(f.requests.isEmpty())
        val selected=ConflictAdvisoryFixture.input();c.update(selected);f.emit(0,CalendarReadState.Ready(f.snapshot(0)))
        assertTrue(shown(c,selected).canReportNoConflict)
    }}
    @Test fun permissionVisibilitySourceReadinessAndInactiveDraftMaskWithoutIo()=runBlocking {fixture {f,c->
        val cases=listOf(ConflictAdvisoryFixture.input(visible=false) to EditorConflictAdvisoryStatus.HIDDEN,
            ConflictAdvisoryFixture.input(permission=false) to EditorConflictAdvisoryStatus.PERMISSION_REQUIRED,
            ConflictAdvisoryFixture.input(sourceReady=false) to EditorConflictAdvisoryStatus.LOADING,
            ConflictAdvisoryFixture.input(binding=null) to EditorConflictAdvisoryStatus.INACTIVE_DRAFT)
        for((input,status)in cases){c.update(input);assertEquals(status,shown(c,input).status)}
        assertTrue(f.requests.isEmpty())
    }}
    @Test fun allCorrelationDimensionsRestartOwnedEpochAndRecurringQualification()=runBlocking {fixture {f,c->
        val variants=listOf(ConflictAdvisoryFixture.input(),ConflictAdvisoryFixture.input(binding=ConflictAdvisoryFixture.binding.copy(generation="generation-B")),
            ConflictAdvisoryFixture.input(binding=ConflictAdvisoryFixture.binding.copy(revision="revision-B")),
            ConflictAdvisoryFixture.input(zone=ZoneId.of("Asia/Tokyo")),ConflictAdvisoryFixture.input(zoneAuthored=true),
            ConflictAdvisoryFixture.input(first=true),ConflictAdvisoryFixture.input(host=1),
            ConflictAdvisoryFixture.input(calendars=setOf(ConflictAdvisoryFixture.identity.copy(providerId="calendar-B"))))
        for((index,input)in variants.withIndex()) {c.update(input);assertEquals(index+1,f.requests.size)
            assertEquals(input.firstOccurrenceOnly,shown(c,input).firstOccurrenceOnly)}
        assertEquals(variants.size,f.requests.map{it.draftRevision}.distinct().size)
    }}
    @Test fun incompleteGapFoldAndPreservedPointDoNotInventRequestedTimes()=runBlocking {fixture {f,c->
        val raw=ConflictAdvisoryFixture.raw
        for(bad in listOf(raw.copy(startDate="2026-"),raw.copy(startDate="2026-03-29",endDate="2026-03-29",startTime="01:30",endTime="03:00",zone="Europe/London"),
            raw.copy(startDate="2026-10-25",endDate="2026-10-25",startTime="01:30",endTime="03:00",zone="Europe/London"))) {
            val input=ConflictAdvisoryFixture.input(binding=ConflictAdvisoryFixture.binding.copy(input=bad));c.update(input)
            assertEquals(EditorConflictAdvisoryStatus.UNFINISHED_TIME,shown(c,input).status)
        }
        val point=Instant.parse("2026-06-01T10:00:00.123Z")
        val preserved=ConflictAdvisoryFixture.binding.copy(baselineInput=raw,preservedTime=EventTime.Timed(point,point))
        val i=ConflictAdvisoryFixture.input(binding=preserved);c.update(i)
        assertEquals(EventTime.Timed(point,point),preserved.normalizedTime());assertEquals(EditorConflictAdvisoryStatus.UNSUPPORTED_TIME,shown(c,i).status)
        assertTrue(f.requests.isEmpty())
    }}
    @Test fun unavailableLimitPermissionAndTerminalCloseNeverKeepPriorCounts()=runBlocking {fixture {f,c->
        val i=ConflictAdvisoryFixture.input();c.update(i)
        f.emit(0,CalendarReadState.Ready(f.snapshot(0,candidates=listOf(ConflictAdvisoryFixture.candidate()))))
        for((read,status)in listOf(CalendarReadState.LimitExceeded to EditorConflictAdvisoryStatus.LIMIT_EXCEEDED,
            CalendarReadState.PermissionRequired to EditorConflictAdvisoryStatus.PERMISSION_REQUIRED,CalendarReadState.Unavailable to EditorConflictAdvisoryStatus.UNAVAILABLE)) {
            f.emit(0,read);assertEquals(status,shown(c,i).status);assertEquals(0,shown(c,i).knownConflicts)
        }
        c.close();f.emit(0,CalendarReadState.Ready(f.snapshot(0,generation=99)));assertEquals(EditorConflictAdvisoryStatus.HIDDEN,shown(c,i).status)
        c.update(i);assertEquals(1,f.requests.size)
    }}
}
