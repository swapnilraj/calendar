package dev.swapnilraj.calendar.events

import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.*
import dev.swapnilraj.calendar.ui.events.meetings.create.*
import java.time.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class MeetingDraftPromotionTest {
    private val fixtures=mutableListOf<PromotionFixture>()
    private fun fixture()=PromotionFixture().also(fixtures::add)
    @After fun cleanup() { fixtures.forEach {it.release.complete(Unit);it.scope.cancel()} }

    @Test fun promotionPreservesEveryRawFieldAndUsesOneNewUnprivilegedFeatureGeneration()=runBlocking {
        val f=fixture();val c=f.open();val old=OrdinaryDraftDocument.decode(f.record())
        c.change {it.copy(title=" raw title ",startDate="2028-",endDate="later",startTime="1:",endTime="2:",
            allDay=true,zone="unknown/raw",location=" raw location ",description="raw\nnotes",alerts="default?,10",
            availability="unknown",privacy="partial",defaultVisibilityCalendar="raw",recurrence=RecurrenceEditorFields(frequency="FUTURE",count="partial"))}
        // Offset and identity values are raw data too; authored date changes clear old offsets first.
        c.change {it.copy(startOffset="+99:00",endOffset="raw offset")}
        val raw=c.state.fields;val result=c.promoteToMeeting() as MeetingDraftPromotion.Ready
        val next=MeetingCreationDraftCodec.decode(f.record())
        assertEquals(raw,next.fields.event);assertEquals(f.id,next.id);assertEquals(f.id,result.launch.draftId)
        assertNotEquals(old.generation,next.generation);assertNotEquals(old.revision,next.revision);assertNotEquals(old.operationId,next.operationId)
        assertTrue(next.fields.guests.isEmpty());assertFalse(next.policyAccepted);assertNull(next.authority);assertNull(next.frozen)
        assertEquals(1,f.rows.size);assertEquals(1,f.promotionAttempts);assertEquals(0,f.providerCalls);assertEquals(0,f.permissions)
        assertOldControllerInert(f,c)
        val reopened=MeetingCreationController(result.launch,f.meetings,f.store,{f.permissions++;true})
        reopened.load();assertEquals(raw,reopened.state.fields!!.event);assertFalse(reopened.canSave)
        assertEquals(0,f.meetingReads);assertEquals(0,f.providerCalls)
    }

    @Test fun appliedAndUnknownAcknowledgmentsBothRequireExactReadback()=runBlocking {
        for(unknown in listOf(false,true)) {
            val f=fixture();val c=f.open();f.unknownAfter=unknown
            assertTrue(c.promoteToMeeting() is MeetingDraftPromotion.Ready)
            assertEquals(1,f.promotionReadbacks);assertEquals(MeetingCreationDraftCodec.VERSION,f.record().version)
            assertOldControllerInert(f,c)
        }
    }

    @Test fun unavailableBeforeCommitOrUnreadableAfterCommitLocksOldButAllowsDismissAndBrowserRecovery()=runBlocking {
        for(before in listOf(false,true)) {
            val f=fixture();val c=f.open();val old=f.record()
            if(before) f.unknownBefore=true else f.failPromotionReadback=true
            assertEquals(MeetingDraftPromotion.Unavailable,c.promoteToMeeting())
            assertOldControllerInert(f,c)
            f.failPromotionReadback=false
            if(before) assertEquals(old,f.record()) else assertNotNull(MeetingCreationDraftCodec.describe(f.record()))
            assertEquals(1,f.rows.size);assertEquals(0,f.providerCalls)
        }
    }

    @Test fun appliedButExternalSuccessorBeforeReadbackNeverReturnsOrOverwritesOldLaunch()=runBlocking {
        val f=fixture();val c=f.open();f.afterCommit={replacement ->
            if(replacement.version==MeetingCreationDraftCodec.VERSION) f.rows[f.id]=replacement.copy(updatedAt=replacement.updatedAt.plusSeconds(1))
        }
        assertEquals(MeetingDraftPromotion.Unavailable,c.promoteToMeeting())
        val successor=f.record();assertOldControllerInert(f,c);assertEquals(successor,f.record());assertEquals(1,f.promotionAttempts)
    }

    @Test fun latestTypingSurvivesCancelledCommittedAutosaveBeforePromotion()=runBlocking {
        val f=fixture();val c=f.open();f.holdVersion=EventEditorController.VERSION
        c.change {it.copy(title="First")};f.committed.awaitBounded()
        c.change {it.copy(title="Latest",location="Latest location")}
        val promoted=async(start=CoroutineStart.UNDISPATCHED) {c.promoteToMeeting()}
        assertFalse(promoted.isCompleted);f.release.complete(Unit)
        assertTrue(promoted.awaitBounded() is MeetingDraftPromotion.Ready)
        val raw=MeetingCreationDraftCodec.decode(f.record()).fields.event
        assertEquals("Latest",raw.title);assertEquals("Latest location",raw.location);assertEquals(0,f.providerCalls)
    }

    @Test fun competingOrdinaryFreezeCannotOverwritePromotedMeetingOrDispatch()=runBlocking {
        val f=fixture();val promoter=f.open();val saver=f.restore();f.holdVersion=MeetingCreationDraftCodec.VERSION
        val promoted=async(start=CoroutineStart.UNDISPATCHED) {promoter.promoteToMeeting()};f.committed.awaitBounded()
        saver.save();f.settle();assertEquals(0,f.providerCalls)
        f.release.complete(Unit);assertTrue(promoted.awaitBounded() is MeetingDraftPromotion.Ready)
        assertEquals(MeetingCreationDraftCodec.VERSION,f.record().version);assertEquals(1,f.rows.size)
        assertOldControllerInert(f,saver)
    }

    @Test fun competingFreezeWinnerLeavesExactFrozenOrdinaryAndPromotionCannotDispatchOrDelete()=runBlocking {
        val f=fixture();val promoter=f.open();val saver=f.restore();f.holdVersion=EventEditorController.VERSION;f.holdFrozenOnly=true
        saver.save();val frozen=f.committed.awaitBounded();assertTrue(OrdinaryDraftDocument.decode(frozen).hasRequest)
        assertEquals(MeetingDraftPromotion.Unavailable,promoter.promoteToMeeting());assertEquals(frozen,f.record())
        assertOldControllerInert(f,promoter);f.release.complete(Unit);f.settle()
        assertEquals(1,f.providerCalls);assertEquals(frozen,f.record());assertEquals(1,f.rows.size)
    }

    @Test fun staleQueuedAutosaveAndOldGenerationRestoreCannotReplaceMeetingRecord()=runBlocking {
        val f=fixture();val promoter=f.open();val launch=DraftBrowserCodec.read(f.record()).launch!!;val stale=f.restore()
        stale.change {it.copy(title="Late ordinary input")}
        assertTrue(promoter.promoteToMeeting() is MeetingDraftPromotion.Ready);val exact=f.record();f.settle()
        assertEquals(exact,f.record());assertOldControllerInert(f,stale)
        val oldRestore=f.controller(launch);assertFalse(oldRestore.canPromoteToMeeting);assertTrue(oldRestore.state.draftReadFailed)
        oldRestore.save();assertEquals(exact,f.record());assertEquals(0,f.providerCalls)
    }

    @Test fun cancellationBeforeCasDoesNothingAndAfterCommitAdoptsOneRecoverableMeeting()=runBlocking {
        val before=fixture();val beforeController=before.open()
        val cancelled=launch(start=CoroutineStart.UNDISPATCHED) {currentCoroutineContext().cancel();beforeController.promoteToMeeting()}
        cancelled.join();assertEquals(0,before.promotionAttempts);assertEquals(EventEditorController.VERSION,before.record().version)
        for(unknown in listOf(false,true)) {
            val f=fixture();val c=f.open();f.holdVersion=MeetingCreationDraftCodec.VERSION;f.unknownAfter=unknown
            val job=launch(start=CoroutineStart.UNDISPATCHED) {c.promoteToMeeting()};val exact=f.committed.awaitBounded()
            job.cancel();f.release.complete(Unit);withTimeout(5_000) {job.join()}
            assertTrue(job.isCancelled);assertEquals(exact,f.record());assertNotNull(MeetingCreationDraftCodec.describe(exact))
            assertOldControllerInert(f,c);assertEquals(0,f.providerCalls)
        }
    }

    @Test fun doublePromotionIsSingleFlightAndCannotIssueTwoGenerations()=runBlocking {
        val f=fixture();val c=f.open();f.holdVersion=MeetingCreationDraftCodec.VERSION
        val first=async(start=CoroutineStart.UNDISPATCHED) {c.promoteToMeeting()};f.committed.awaitBounded()
        assertEquals(MeetingDraftPromotion.Rejected,c.promoteToMeeting());f.release.complete(Unit)
        val launch=(first.awaitBounded() as MeetingDraftPromotion.Ready).launch
        assertEquals(launch,MeetingCreationDraftCodec.describe(f.record()));assertEquals(1,f.promotionAttempts)
    }

    @Test fun optOutIncomingPinnedLegacyEditRecoveryAndCopyWarningsNeverPromote()=runBlocking {
        for(reason in listOf("optout","incoming:","incoming-edit:","incoming-review:","incoming-edit-review:","legacy","edit","recovery")+EditorNotice.entries.filter {it!=EditorNotice.CALENDAR_RESELECTION_REQUIRED}.map {it.name}) {
            val f=fixture();if(reason.endsWith(":")) f.id=reason+"session"
            val record=EventEditorController.initialRecord(f.initial,if(reason=="edit") f.editable else null,f.id,true,f.clock)
            val changed=when {
                reason=="legacy" -> record.copy(version=EventEditorController.PREVIOUS_VERSION)
                EditorNotice.entries.any {it.name==reason} -> record.copy(payload=JSONObject(record.payload).put("notices",org.json.JSONArray(listOf(reason))).toString())
                else -> record
            }
            f.rows[f.id]=changed;val c=f.restore(allowed=reason!="optout",existing=if(reason=="edit") f.editable else null,recovery=reason=="recovery")
            assertFalse("reason=$reason",c.canPromoteToMeeting)
            assertEquals(MeetingDraftPromotion.Rejected,c.promoteToMeeting());assertEquals(changed,f.record());assertEquals(0,f.promotionAttempts)
        }
    }

    @Test fun frozenOrdinaryCommandRejectsPromotionWithoutChangingItsRecoveryAuthority()=runBlocking {
        val f=fixture();val c=f.open();c.save();f.settle();val exact=f.record()
        assertTrue(c.hasFrozenSubmission);assertFalse(c.canPromoteToMeeting)
        assertEquals(MeetingDraftPromotion.Rejected,c.promoteToMeeting());assertEquals(exact,f.record());assertEquals(1,f.providerCalls)
    }

    private suspend fun assertOldControllerInert(f:PromotionFixture,c:EventEditorController) {
        val exact=f.record();val calls=f.providerCalls
        c.change {it.copy(title="Late overwrite")};c.save();assertFalse(c.discardDraft());assertNull(c.replaceDraft())
        assertEquals(MeetingDraftPromotion.Rejected,c.promoteToMeeting());assertTrue(c.prepareDismiss())
        assertEquals(exact,f.record());assertEquals(calls,f.providerCalls);assertFalse(c.canPromoteToMeeting)
    }
}

private suspend fun <T> Deferred<T>.awaitBounded():T=withTimeout(5_000) {await()}
internal class PromotionFixture {
    val clock=Clock.fixed(Instant.parse("2028-06-01T00:00:00Z"),ZoneOffset.UTC)
    val initial=EventDraft(CalendarId(2),EventTime.Timed(Instant.parse("2028-06-15T09:00:00Z"),Instant.parse("2028-06-15T10:00:00Z")),ZoneOffset.UTC,"Initial",targetIdentity=eventTestIdentity())
    private val time=initial.time as EventTime.Timed
    val editable=EditableEvent(EventDetails(EventOccurrence(EventRef(initial.calendarId,42,time.start,time.end),initial.time,initial.title,1),eventTestCalendar()),EditToken("original"),EditableField.entries.toSet())
    val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
    var id="ordinary";val rows=java.util.concurrent.ConcurrentHashMap<String,DraftRecord>()
    var promotionAttempts=0;var promotionReadbacks=0;var providerCalls=0;var permissions=0;var meetingReads=0
    var unknownBefore=false;var unknownAfter=false;var failPromotionReadback=false;var holdVersion:Int?=null;var holdFrozenOnly=false
    val committed=CompletableDeferred<DraftRecord>();val release=CompletableDeferred<Unit>()
    var afterCommit:suspend (DraftRecord)->Unit={}
    private var promotionAttempted=false
    val store=object:AtomicDraftStore {
        override suspend fun load(id:String):DraftRecord? {
            if(promotionAttempted) {promotionReadbacks++;if(failPromotionReadback) error("unreadable")}
            return rows[id]
        }
        override suspend fun list()=rows.values.toList()
        override suspend fun upsert(record:DraftRecord):Unit=error("blind write")
        override suspend fun delete(id:String):Unit=error("blind delete")
        override suspend fun compareAndSet(id:String,expected:DraftRecord?,replacement:DraftRecord?):DraftChangeResult {
            val promotion=replacement?.version==MeetingCreationDraftCodec.VERSION
            if(promotion) {promotionAttempts++;promotionAttempted=true;if(unknownBefore) return DraftChangeResult.Unavailable}
            synchronized(rows) {
                if(rows[id]!=expected) return DraftChangeResult.Changed
                if(replacement==null) rows.remove(id) else rows[id]=replacement
            }
            if(replacement!=null) {
                if(replacement.version==holdVersion && (!holdFrozenOnly || OrdinaryDraftDocument.decode(replacement).hasRequest)) {
                    holdVersion=null;committed.complete(replacement);withContext(NonCancellable) {release.await()}
                }
                afterCommit(replacement)
            }
            return if(promotion && unknownAfter) DraftChangeResult.Unavailable else DraftChangeResult.Applied
        }
    }
    val mutations=object:CalendarMutations {
        override suspend fun loadForEdit(ref:EventRef):EditReadResult=error("no provider read")
        override suspend fun create(operationId:String,draft:EventDraft):MutationResult {providerCalls++;return MutationResult.Uncertain(operationId,null)}
        override suspend fun update(operationId:String,ref:EventRef,token:EditToken,patch:EventPatch):MutationResult=error("not update")
        override suspend fun delete(operationId:String,ref:EventRef,token:EditToken):MutationResult=error("not delete")
        override suspend fun recover(operationId:String):MutationResult=error("not recover")
    }
    val meetings=object:CalendarMeetings {
        override suspend fun loadForCreate(target:CalendarIdentity):MeetingReadResult<MeetingCreation> {meetingReads++;error("no authority read")}
        override suspend fun loadForEdit(selected:EventRef):MeetingReadResult<EditableMeeting> =error("not edit")
        override suspend fun create(operationId:String,token:EditToken,draft:EventDraft,guests:List<MeetingGuestInput>):MeetingMutationResult=error("not create")
        override suspend fun mutate(operationId:String,selected:EventRef,token:EditToken,command:MeetingCommand):MeetingMutationResult=error("not mutate")
        override suspend fun recover(operationId:String):MeetingMutationResult=error("not recover")
    }
    fun record()=rows.getValue(id)
    suspend fun open():EventEditorController {
        rows[id]=EventEditorController.initialRecord(initial,null,id,true,clock)
        return restore()
    }
    fun restore(allowed:Boolean=true,existing:EditableEvent?=null,recovery:Boolean=false)=controller(DraftBrowserCodec.read(record()).launch!!,allowed,existing,recovery)
    fun controller(launch:EditorDraftLaunch,allowed:Boolean=true,existing:EditableEvent?=null,recovery:Boolean=false)=EventEditorController(initial,existing,id,mutations,store,scope,clock=clock,
        requireStoredDraft=true,expectedGeneration=launch.generation,recoveryOnly=recovery,allowMeetingPromotion=allowed,requestWritePermission={permissions++;true})
    suspend fun settle()=withTimeout(5_000) {scope.coroutineContext[Job]!!.children.toList().forEach {it.join()}}
}
