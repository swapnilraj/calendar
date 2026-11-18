package dev.swapnilraj.calendar.events

import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.*
import java.time.*
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class DraftCasCancellationTest {
    @Test fun ordinaryCommittedAutosaveCancelledByNextEditOrSaveKeepsLatestRawAndCanSubmit() = runBlocking {
        for(nextEdit in listOf(false,true)) for(unknownAck in listOf(false,true)) {
            val f=OrdinaryCancellationFixture()
            try {
                val c=f.open(); f.store.unknownAck=unknownAck; val gate=f.store.holdNext()
                c.change {it.copy(title="First raw title")}; gate.committed.awaitBounded()
                if(nextEdit) c.change {it.copy(title="Latest raw title",location="Latest raw location")}
                c.save(); gate.release.complete(Unit)
                until { !c.state.saving }; f.scope.settle()
                assertEquals(1,f.calls.size)
                assertEquals(if(nextEdit) "Latest raw title" else "First raw title",f.calls.single().title)
                assertEquals(if(nextEdit) "Latest raw location" else "",f.calls.single().location)
                assertEquals(f.calls.single().title,OrdinaryDraftDocument.decode(f.store.record("ordinary")).fields.title)
                assertTrue(c.hasFrozenSubmission); assertEquals(0,f.store.blindWrites)
            } finally {f.close()}
        }
    }

    @Test fun cancellingOrdinaryUiDuringCommittedFreezeRetainsExactFrozenCommandWithoutDispatch() = runBlocking {
        val f=OrdinaryCancellationFixture()
        try {
            val c=f.open(); c.change {it.copy(title="Explicit save before cancellation")}
            val gate=f.store.holdNext {JSONObject(it.payload).has("submission")}
            c.save(); val exact=gate.committed.awaitBounded()
            f.scope.cancel(); gate.release.complete(Unit); f.scope.coroutineContext[Job]!!.joinBounded()
            assertEquals(exact,f.store.record("ordinary")); assertTrue(c.hasFrozenSubmission); assertTrue(f.calls.isEmpty())
            val launch=DraftBrowserCodec.read(exact).launch!!; assertTrue(launch.needsRecovery)
            val replacementScope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
            try {
                val restored=f.controller(replacementScope,launch); until { !restored.state.loading }
                assertTrue(restored.state.recoveryRequired); restored.save(); restored.recover(); until {!restored.state.saving}; replacementScope.settle()
                assertEquals(listOf(OrdinaryDraftDocument.decode(exact).operationId),f.recoveries)
                assertTrue(f.calls.isEmpty()); assertEquals(exact,f.store.record("ordinary"))
            } finally {replacementScope.cancel()}
        } finally {f.close()}
    }

    @Test fun ordinaryExternalSuccessorAfterOwnCommitIsNeverAdoptedOrOverwritten() = runBlocking {
        val f=OrdinaryCancellationFixture()
        try {
            val c=f.open(); val gate=f.store.holdNext(); c.change {it.copy(title="Own first edit")}
            val own=gate.committed.awaitBounded(); val successor=foreignGeneration(own); f.store.external(successor)
            c.change {it.copy(title="Own later edit")}; c.save(); gate.release.complete(Unit); until {!c.state.saving}
            assertTrue(f.calls.isEmpty()); assertEquals(successor,f.store.record("ordinary")); assertEquals("Own later edit",c.state.fields.title)
            assertEquals(EditorProblem.DRAFT_UNAVAILABLE,c.state.problem)
        } finally {f.close()}
    }

}

private suspend fun until(predicate:()->Boolean) = withTimeout(5_000) {while(!predicate()) delay(5)}
private suspend fun <T> CompletableDeferred<T>.awaitBounded():T = withTimeout(5_000) {await()}
private suspend fun CoroutineScope.settle() = withTimeout(5_000) {coroutineContext[Job]!!.children.toList().forEach {it.join()}}
private suspend fun Job.joinBounded() = withTimeout(5_000) {join()}
private fun foreignGeneration(record:DraftRecord)=record.copy(payload=JSONObject(record.payload).put("generation",UUID.randomUUID().toString()).put("revision",UUID.randomUUID().toString()).toString())

/** Models CalendarLocalStore's withContext(IO): commit can succeed before cancellation discards ACK.
 * The inner wait represents an uninterruptible SQLite transaction returning after caller cancellation.
 */
private class CommitThenAckStore:AtomicDraftStore {
    class Gate(val matches:(DraftRecord)->Boolean) {
        val committed=CompletableDeferred<DraftRecord>(); val release=CompletableDeferred<Unit>()
    }
    private val lock=Any(); private val rows=mutableMapOf<String,DraftRecord>()
    private var pending:Gate?=null; private val gates=mutableListOf<Gate>()
    @Volatile var unknownAck=false
    var blindWrites=0
    fun holdNext(matches:(DraftRecord)->Boolean={true}):Gate=synchronized(lock) {
        Gate(matches).also {pending=it;gates+=it}
    }
    fun external(record:DraftRecord) {synchronized(lock) {rows[record.id]=record}}
    fun record(id:String)=synchronized(lock) {rows.getValue(id)}
    fun releaseAll() {synchronized(lock) {gates.toList()}.forEach {it.release.complete(Unit)}}
    override suspend fun load(id:String)=withContext(Dispatchers.IO) {synchronized(lock) {rows[id]}}
    override suspend fun list()=withContext(Dispatchers.IO) {synchronized(lock) {rows.values.toList()}}
    override suspend fun upsert(record:DraftRecord) {blindWrites++;error("blind upsert forbidden")}
    override suspend fun delete(id:String) {blindWrites++;error("blind delete forbidden")}
    override suspend fun compareAndSet(id:String,expected:DraftRecord?,replacement:DraftRecord?):DraftChangeResult=withContext(Dispatchers.IO) {
        var gate:Gate?=null
        val result=synchronized(lock) {
            if(rows[id]!=expected) DraftChangeResult.Changed else {
                if(replacement==null) rows.remove(id) else rows[id]=replacement
                if(replacement!=null && pending?.matches?.invoke(replacement)==true) {gate=pending;pending=null}
                DraftChangeResult.Applied
            }
        }
        gate?.let {held -> held.committed.complete(replacement!!);withContext(NonCancellable) {held.release.await()}}
        if(result==DraftChangeResult.Applied && unknownAck) DraftChangeResult.Unavailable else result
    }
}
private class OrdinaryCancellationFixture {
    val store=CommitThenAckStore(); val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
    val initial=EventDraft(CalendarId(2),EventTime.Timed(Instant.parse("2028-06-15T09:00:00Z"),Instant.parse("2028-06-15T10:00:00Z")),ZoneOffset.UTC,"Initial",targetIdentity=eventTestIdentity())
    val calls=CopyOnWriteArrayList<EventDraft>(); val recoveries=CopyOnWriteArrayList<String>()
    private val port=object:CalendarMutations {
        override suspend fun loadForEdit(ref:EventRef):EditReadResult=error("no source read")
        override suspend fun create(operationId:String,draft:EventDraft):MutationResult {calls+=draft;return MutationResult.Uncertain(operationId,null)}
        override suspend fun update(operationId:String,ref:EventRef,token:EditToken,patch:EventPatch):MutationResult=error("not update")
        override suspend fun delete(operationId:String,ref:EventRef,token:EditToken):MutationResult=error("not delete")
        override suspend fun recover(operationId:String):MutationResult {recoveries+=operationId;return MutationResult.Unavailable}
    }
    suspend fun open():EventEditorController {
        val launch=(prepareNewDraft(initial,"ordinary",true,store) as DraftPreparationResult.Ready).launch
        return controller(scope,launch).also {until {!it.state.loading}}
    }
    fun controller(scope:CoroutineScope,launch:EditorDraftLaunch)=EventEditorController(initial,null,"ordinary",port,store,scope,
        recoveryOnly=launch.needsRecovery,requireStoredDraft=true,expectedGeneration=launch.generation,requestWritePermission={true})
    suspend fun close() {store.releaseAll();scope.cancel();scope.coroutineContext[Job]!!.joinBounded()}
}
