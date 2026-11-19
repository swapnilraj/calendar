package dev.swapnilraj.calendar.events

import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.*
import dev.swapnilraj.calendar.ui.events.meetings.create.*
import dev.swapnilraj.calendar.events.meetings.create.MeetingCreationFixture
import dev.swapnilraj.calendar.events.meetings.owned.OwnedFixture
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class EditorConflictBindingTest {
    private val at=Instant.parse("2028-06-15T09:00:00Z")
    private val draft=EventDraft(CalendarId(2),EventTime.Timed(at,at.plusSeconds(3600)),ZoneOffset.UTC,"",targetIdentity=eventTestIdentity())
    private fun ordinary(scope:CoroutineScope,store:EventEditorControllerTest.Store=EventEditorControllerTest.Store(),
        initial:EventDraft=draft,existing:EditableEvent?=null,writer:EventEditorControllerTest.Writer=EventEditorControllerTest().Writer()):EventEditorController =
        preparedEditor(initial,existing,"draft-fixture",writer,store,scope)

    @Test fun ordinaryRawChangeBackStaysMaskedThroughHeldCasAndPublishesObservableAcknowledgedRevision()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val store=EventEditorControllerTest.Store();val c=ordinary(scope,store);val before=c.conflictBinding(true)!!
            val observations=Channel<EditorConflictBinding?>(Channel.UNLIMITED)
            val watch=launch(start=CoroutineStart.UNDISPATCHED) {snapshotFlow {c.conflictBinding(true)}.collect {observations.send(it)}}
            try {
                assertEquals(before,observations.receive())
                c.change {it.copy(startTime="08:00")};assertNull(c.conflictBinding(true))
                c.change {it.copy(startTime="09:00")};assertNull(c.conflictBinding(true))
                val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
                store.beforeCas={_,_,_->entered.complete(Unit);release.await()}
                val flush=launch(start=CoroutineStart.UNDISPATCHED) {assertTrue(c.flushDraft())}
                entered.await();assertNull(c.conflictBinding(true));release.complete(Unit);flush.join()
                val after=c.conflictBinding(true)!!
                assertEquals(before.generation,after.generation);assertNotEquals(before.revision,after.revision)
                Snapshot.sendApplyNotifications()
                withTimeout(5000) {while(observations.receive()!=after) {}}
                assertNull(c.conflictBinding(false))
            } finally {watch.cancelAndJoin();observations.close()}
        } finally {scope.cancel()}
    }
    @Test fun olderAcknowledgementCannotAdoptLaterRawChangeBackEpoch()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val store=EventEditorControllerTest.Store();val c=ordinary(scope,store)
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            store.beforeCas={_,_,_->entered.complete(Unit);release.await()}
            c.change {it.copy(startTime="08:00")}
            val first=launch(start=CoroutineStart.UNDISPATCHED) {assertTrue(c.flushDraft())}
            entered.await()
            c.change {it.copy(startTime="07:00")};c.change {it.copy(startTime="08:00")}
            release.complete(Unit);first.join()
            assertNull(c.conflictBinding(true))
            store.beforeCas=null;assertTrue(c.flushDraft());assertNotNull(c.conflictBinding(true))
        } finally {scope.cancel()}
    }
    @Test fun ordinaryImportedPointPrecisionAndExactExclusionRemainReadOnly()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val point=at.plusMillis(123);val initial=draft.copy(time=EventTime.Timed(point,point))
            val ref=EventRef(CalendarId(2),42,point,point)
            val source=EditableEvent(EventDetails(EventOccurrence(ref,initial.time,"",1),eventTestCalendar(),eventZone=ZoneOffset.UTC),EditToken("source"),EditableField.entries.toSet())
            val writer=EventEditorControllerTest().Writer();val c=ordinary(scope,initial=initial,existing=source,writer=writer)
            val binding=c.conflictBinding(true)!!
            assertEquals(initial.time,binding.normalizedTime());assertEquals(ref,binding.editedRef)
            assertEquals(source.details.calendar!!.identity,binding.calendarIdentity)
            assertEquals(0,writer.updates);assertEquals(0,writer.creates)
        } finally {scope.cancel()}
    }
    @Test fun legacyWithoutExactLeaseNeverExposesBinding()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val store=EventEditorControllerTest.Store()
            store.records["draft-fixture"]=EventEditorController.initialRecord(draft,null,"draft-fixture",true,Clock.systemUTC()).copy(version=EventEditorController.PREVIOUS_VERSION)
            val c=ordinary(scope,store);assertTrue(c.state.legacy);assertNull(c.conflictBinding(true))
        } finally {scope.cancel()}
    }
    @Test fun ordinaryFrozenAndRestoredRecoveryExposeNoActiveBinding()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val store=EventEditorControllerTest.Store();val writer=EventEditorControllerTest().Writer().apply {hold=CompletableDeferred()}
            val c=ordinary(scope,store,writer=writer);assertNotNull(c.conflictBinding(true));c.save()
            assertNull(c.conflictBinding(true));assertNull(ordinary(scope,store,writer=writer).conflictBinding(true))
            writer.hold!!.complete(Unit);yield();assertNull(c.conflictBinding(true))
        } finally {scope.cancel()}
    }
    @Test fun meetingCreateHeldPersistenceMasksAndAcknowledgedRevisionHasNoEditedExclusion()=runBlocking {
        val f=MeetingCreationFixture();val c=f.open();val before=c.conflictBinding(true)!!
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        f.store.beforeCas={_,_->entered.complete(Unit);release.await()}
        val change=launch(start=CoroutineStart.UNDISPATCHED) {c.changeFields(c.state.fields!!.event.copy(startTime="08:00"))}
        entered.await();assertNull(c.conflictBinding(true));release.complete(Unit);change.join()
        val after=c.conflictBinding(true)!!
        assertEquals(before.generation,after.generation);assertNotEquals(before.revision,after.revision)
        assertNull(after.editedRef);assertNull(after.preservedTime);assertNull(c.conflictBinding(false));assertTrue(f.calls.isEmpty())
    }
    @Test fun meetingCreateStaleAndFrozenRecoveryNeverExposeBinding()=runBlocking {
        val f=MeetingCreationFixture();val c=f.ready();assertNotNull(c.conflictBinding(true))
        f.callGate=CompletableDeferred();val save=launch(start=CoroutineStart.UNDISPATCHED) {c.save()}
        assertNull(c.conflictBinding(true));val reopened=f.controller(f.launch());reopened.load();assertNull(reopened.conflictBinding(true))
        f.callGate!!.complete(Unit);save.join();assertNull(c.conflictBinding(true))
        val g=MeetingCreationFixture();val stale=g.open();g.store.rows.clear();stale.changeFields(stale.state.fields!!.event.copy(startTime="08:00"))
        assertNull(stale.conflictBinding(true));assertTrue(g.calls.isEmpty())
    }
    @Test fun ownedRetainsBaselinePrecisionAndMasksPermissionAndUnacknowledgedPersistence()=runBlocking {
        val f=OwnedFixture();val point=f.start.plusMillis(321);val ref=f.ref.copy(instanceStart=point,instanceEnd=point)
        f.source=f.source.copy(details=f.source.details.copy(occurrence=f.source.details.occurrence.copy(ref=ref,time=EventTime.Timed(point,point))))
        val c=f.open();val before=c.conflictBinding(true)!!
        assertEquals(EventTime.Timed(point,point),before.normalizedTime());assertEquals(ref,before.editedRef)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        f.store.afterCas={entered.complete(Unit);release.await()}
        val change=launch(start=CoroutineStart.UNDISPATCHED) {c.changeFields(c.state.fields!!.let {it.copy(event=it.event.copy(title="Changed"))})}
        entered.await();assertNull(c.conflictBinding(true));release.complete(Unit);change.join()
        val after=c.conflictBinding(true)!!;assertNotEquals(before.revision,after.revision);assertEquals(before.normalizedTime(),after.normalizedTime())
        c.observeReadPermission(false);assertNull(c.conflictBinding(true));assertNull(c.conflictBinding(false));assertTrue(f.calls.isEmpty())
    }
    @Test fun ownedSourceDriftAndFrozenRecoveryExposeNoActiveBinding()=runBlocking {
        val f=OwnedFixture();val c=f.open();f.source=f.source.copy(token=EditToken("changed"));c.refreshSource()
        assertNull(c.conflictBinding(true));assertTrue(f.calls.isEmpty())
        val g=OwnedFixture();val frozen=g.open();frozen.changeFields(frozen.state.fields!!.let {it.copy(event=it.event.copy(title="Changed"))})
        frozen.save();assertTrue(frozen.frozen);assertNull(frozen.conflictBinding(true))
        val restored=g.controller(g.launch());restored.load();assertNull(restored.conflictBinding(true))
    }
}
