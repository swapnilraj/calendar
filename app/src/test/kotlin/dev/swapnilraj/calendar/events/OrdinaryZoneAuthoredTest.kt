package dev.swapnilraj.calendar.events

import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.*
import dev.swapnilraj.calendar.ui.events.meetings.create.*
import java.time.*
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class OrdinaryZoneAuthoredTest {
    private lateinit var oldZone:TimeZone
    private val fixtures=mutableListOf<ZoneIntentFixture>()
    @Before fun setZone() {oldZone=TimeZone.getDefault();TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))}
    @After fun cleanup() {fixtures.forEach {it.release.complete(Unit);it.scopes.forEach {scope->scope.cancel()}};TimeZone.setDefault(oldZone)}
    private fun fixture()=ZoneIntentFixture().also(fixtures::add)

    @Test fun sameRendererChoiceAuthorsOnlyZoneAndNeverChangesRawFieldsOrSharedFingerprint()=runBlocking {
        val f=fixture();val c=f.open();val fields=c.state.fields;val original=f.record();val baseline=JSONObject(original.payload).getString("sourceBaseline")
        val fingerprint=MeetingCreationDraftCodec.fingerprint(MeetingCreationFields(fields))
        c.authorZone(fields.zone);assertTrue(c.state.zoneAuthored);assertEquals(fields,c.state.fields);assertTrue(c.hasAuthoredTemporalChanges)
        assertTrue(c.flushDraft());val raw=JSONObject(f.record().payload)
        assertEquals(true,raw.get("zoneAuthored"));assertEquals(baseline,raw.getString("sourceBaseline"))
        assertFalse(EventEditorController.encodeRawFields(c.state.fields).has("zoneAuthored"))
        assertEquals(fingerprint,MeetingCreationDraftCodec.fingerprint(MeetingCreationFields(c.state.fields)))
        c.save();f.settle()
        assertEquals(FieldChange.Set(ZoneId.of("Asia/Tokyo")),f.updates.single().eventZone);assertEquals(FieldChange.Keep,f.updates.single().time)
        assertFalse(JSONObject(f.record().payload).getJSONObject("submission").has("zoneAuthored"))
        assertFalse(JSONObject(JSONObject(f.record().payload).getString("operationPayload")).has("zoneAuthored"))
    }
    @Test fun editedTimeAndSameRendererChoiceFreezeBothExplicitDeltas()=runBlocking {
        val f=fixture();val c=f.open();c.change {it.copy(startTime="19:00",endTime="20:00")};c.authorZone(c.state.fields.zone);c.save();f.settle()
        val patch=f.updates.single();assertEquals(FieldChange.Set(ZoneId.of("Asia/Tokyo")),patch.eventZone)
        assertEquals(FieldChange.Set(EventTime.Timed(f.start.plusSeconds(3600),f.start.plusSeconds(7200))),patch.time)
    }
    @Test fun genericCallbacksNeverAuthorZoneAndRepeatExplicitChoiceIsAnExactNoop()=runBlocking {
        val f=fixture();val c=f.open();val original=f.record();c.change {it.copy(zone=it.zone)};f.settle()
        assertEquals(original,f.record());assertFalse(c.state.zoneAuthored)
        c.authorZone(c.state.fields.zone);f.settle();val authored=f.record();assertNotEquals(original,authored)
        c.authorZone(c.state.fields.zone);c.change {it.copy(zone=it.zone)};f.settle();assertEquals(authored,f.record())
        assertTrue(f.updates.isEmpty())
    }
    @Test fun absentAndExplicitFalseStayFalseAndFalseIsOmittedOnNextOrdinaryRecord()=runBlocking {
        for(present in listOf(false,true)) {
            val f=fixture();f.prepare();val original=f.record();val raw=JSONObject(original.payload)
            val oldDocument=OrdinaryDraftDocument.decode(original)
            assertEquals(original,oldDocument.record(original.updatedAt,oldDocument.revision!!))
            if(present) raw.put("zoneAuthored",false)
            f.store.rows["draft"]=original.copy(payload=raw.toString())
            val c=f.restore();assertFalse(c.state.zoneAuthored);c.change {it.copy(title="Title only")};assertTrue(c.flushDraft())
            assertFalse(JSONObject(f.record().payload).has("zoneAuthored"));c.save();f.settle();assertEquals(FieldChange.Keep,f.updates.single().eventZone)
        }
    }
    @Test fun malformedOptionalIntentIsReviewOnlyRatherThanCoercedFromStringsNumbersOrNull()=runBlocking {
        for(value in listOf<Any>("true",1,JSONObject.NULL)) {
            val f=fixture();f.prepare();val original=f.record();val broken=original.copy(payload=JSONObject(original.payload).put("zoneAuthored",value).toString());f.store.rows["draft"]=broken
            assertNull(DraftBrowserCodec.read(broken).launch);val c=f.openDirect(original);f.untilReady(c)
            assertTrue(c.state.draftReadFailed);c.authorZone("UTC");c.save();f.settle();assertEquals(broken,f.record());assertTrue(f.updates.isEmpty())
        }
    }
    @Test fun knownSourceSameZoneAndNullSourceWithoutZoneRightNeverSetIntent()=runBlocking {
        for(known in listOf(false,true)) {
            val f=fixture();f.source=f.source.copy(details=f.source.details.copy(eventZone=if(known) ZoneId.of("Asia/Tokyo") else null),allowedFields=if(known) EditableField.entries.toSet() else EditableField.entries.toSet()-EditableField.ZONE)
            val c=f.open();val original=f.record();c.authorZone(c.state.fields.zone);f.settle()
            assertFalse(c.state.zoneAuthored);assertEquals(original,f.record());assertTrue(f.updates.isEmpty())
        }
    }
    @Test fun storedTrueIntentOnKnownZoneIsNotAcceptedAsAValidAuthoredNullSource()=runBlocking {
        val f=fixture();f.source=f.source.copy(details=f.source.details.copy(eventZone=ZoneId.of("Asia/Tokyo")));f.prepare()
        val original=f.record();val invalid=original.copy(payload=JSONObject(original.payload).put("zoneAuthored",true).toString());f.store.rows["draft"]=invalid
        val c=f.openDirect(original);f.untilReady(c);assertTrue(c.state.draftReadFailed)
        c.authorZone("UTC");c.save();f.settle();assertEquals(invalid,f.record());assertTrue(f.updates.isEmpty())
    }
    @Test fun rawInvalidZoneSurvivesReopenWithIntentButCannotFreeze()=runBlocking {
        val f=fixture();val c=f.open();c.authorZone("Incomplete/Zone");assertTrue(c.flushDraft());val exact=f.record()
        val reopened=f.restore();assertTrue(reopened.state.zoneAuthored);assertEquals("Incomplete/Zone",reopened.state.fields.zone)
        reopened.save();f.settle();assertEquals(EditorProblem.INVALID_INPUT,reopened.state.problem);assertEquals(exact,f.record());assertTrue(f.updates.isEmpty())
    }
    @Test fun travelAndCanonicalTokenRefreshRetainIntentAndOriginalRendererButRightsLossDoesNotNarrowIt()=runBlocking {
        val f=fixture();val c=f.open();c.authorZone(c.state.fields.zone);assertTrue(c.flushDraft());val fields=c.state.fields
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"));f.source=f.source.copy(token=EditToken("fresh-same-canonical"))
        val reopened=f.restore();assertFalse(reopened.state.reviewRequired);assertTrue(reopened.state.zoneAuthored);assertEquals(fields,reopened.state.fields)
        assertEquals("fresh-same-canonical",JSONObject(f.record().payload).getString("sourceToken"))
        f.source=f.source.copy(allowedFields=f.source.allowedFields-EditableField.ZONE,token=EditToken("lost-zone-right"))
        val exact=f.record();val denied=f.restore();assertTrue(denied.state.reviewRequired);assertTrue(denied.state.zoneAuthored)
        denied.authorZone("UTC");denied.save();f.settle();assertEquals(exact,f.record());assertTrue(f.updates.isEmpty())
    }
    @Test fun freshReplacementResetsIntentWhileLegacyAndRecoveryCannotAuthorNewIntent()=runBlocking {
        val f=fixture();val c=f.open();c.authorZone(c.state.fields.zone);assertTrue(c.flushDraft());val replacement=c.replaceDraft()!!
        val replacementRecord=f.store.rows.getValue(replacement.draftId);assertFalse(JSONObject(replacementRecord.payload).has("zoneAuthored"))
        assertFalse(OrdinaryDraftDocument.decode(replacementRecord).zoneAuthored)
        val legacy=fixture();legacy.prepare();val old=legacy.record().copy(version=EventEditorController.PREVIOUS_VERSION);legacy.store.rows["draft"]=old
        val legacyController=legacy.restore();assertTrue(legacyController.state.legacy);legacyController.authorZone("UTC");legacy.settle();assertEquals(old,legacy.record())
        val frozen=fixture();val editor=frozen.open();editor.authorZone(editor.state.fields.zone);editor.save();frozen.settle();val exact=frozen.record();val launch=DraftBrowserCodec.read(exact).launch!!
        val recovery=frozen.openDirect(exact,recovery=true);frozen.untilReady(recovery);recovery.authorZone("UTC");recovery.change {it.copy(zone="UTC")};recovery.save();frozen.settle()
        assertEquals(exact,frozen.record());assertTrue(recovery.state.zoneAuthored);assertEquals(1,frozen.updates.size);assertTrue(launch.needsRecovery)
    }
    @Test fun committedIntentAutosaveAdoptsThroughCancellationAndRestoresExactly()=runBlocking {
        for(unknown in listOf(false,true)) {
            val f=fixture();val c=f.open();f.store.unknownAfter=unknown;f.hold={it.version==EventEditorController.VERSION && JSONObject(it.payload).optBoolean("zoneAuthored")}
            c.authorZone(c.state.fields.zone);val exact=withTimeout(5_000) {f.committed.await()}
            f.scopes.first().cancel();f.release.complete(Unit);withTimeout(5_000) {f.scopes.first().coroutineContext[Job]!!.join()}
            assertEquals(exact,f.record());assertTrue(f.updates.isEmpty());f.hold=null
            val reopened=f.restore();assertTrue(reopened.state.zoneAuthored);reopened.save();f.settle()
            assertEquals(FieldChange.Set(ZoneId.of("Asia/Tokyo")),f.updates.single().eventZone);assertEquals(FieldChange.Keep,f.updates.single().time)
        }
    }
    @Test fun foreignGenerationAfterIntentCommitNeverGetsOverwrittenOrSubmitted()=runBlocking {
        val f=fixture();val c=f.open();f.hold={JSONObject(it.payload).optBoolean("zoneAuthored")}
        c.authorZone(c.state.fields.zone);val own=withTimeout(5_000) {f.committed.await()}
        val foreign=own.copy(payload=JSONObject(own.payload).put("generation",UUID.randomUUID().toString()).put("revision",UUID.randomUUID().toString()).toString());f.store.rows["draft"]=foreign
        c.change {it.copy(title="Latest local text")};c.save();f.release.complete(Unit);f.settle()
        assertEquals(foreign,f.record());assertTrue(f.updates.isEmpty());assertEquals(EditorProblem.DRAFT_UNAVAILABLE,c.state.problem)
    }
    @Test fun cancelledFreezeKeepsAuthoredZoneInExactTypedRequestAndOnlyExplicitRecoveryCanRetry()=runBlocking {
        val f=fixture();val c=f.open();c.authorZone(c.state.fields.zone);assertTrue(c.flushDraft())
        f.hold={JSONObject(it.payload).has("submission")};c.save();val exact=withTimeout(5_000) {f.committed.await()}
        f.scopes.first().cancel();f.release.complete(Unit);withTimeout(5_000) {f.scopes.first().coroutineContext[Job]!!.join()}
        assertTrue(f.updates.isEmpty());assertTrue(c.hasFrozenSubmission);assertEquals(exact,f.record())
        val frozen=OrdinaryDraftDocument.decode(exact).frozen as FrozenEditorSubmission.Update
        assertEquals(FieldChange.Keep,frozen.patch.time);assertEquals(FieldChange.Set(ZoneId.of("Asia/Tokyo")),frozen.patch.eventZone)
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"));val recovered=f.openDirect(exact,recovery=true);f.untilReady(recovered)
        f.recovery=MutationResult.SafeToRetry(frozen.operationId);recovered.recover();f.settle();assertTrue(recovered.state.retryReady)
        assertEquals(exact,f.record());assertTrue(f.updates.isEmpty());recovered.retryOriginal();f.settle()
        assertEquals(listOf(frozen.patch),f.updates);assertEquals(exact,f.record());assertEquals(listOf(frozen.operationId),f.recoveries)
    }
}

internal class ZoneIntentFixture {
    val start=Instant.parse("2028-06-15T09:00:00Z");val ref=EventRef(CalendarId(2),42,start,start.plusSeconds(3600))
    val clock=Clock.fixed(Instant.parse("2028-06-01T00:00:00Z"),ZoneOffset.UTC)
    var source=EditableEvent(EventDetails(EventOccurrence(ref,EventTime.Timed(ref.instanceStart,ref.instanceEnd),"Synthetic event",1),eventTestCalendar(),eventZone=null),EditToken("source-A"),EditableField.entries.toSet())
    val store=ZoneIntentStore();val scopes=mutableListOf<CoroutineScope>();val updates=mutableListOf<EventPatch>();val recoveries=mutableListOf<String>()
    var recovery:MutationResult=MutationResult.Unavailable
    var hold:((DraftRecord)->Boolean)?=null;val committed=CompletableDeferred<DraftRecord>();val release=CompletableDeferred<Unit>()
    init {store.afterCas={r->if(r!=null && hold?.invoke(r)==true) {hold=null;committed.complete(r);withContext(NonCancellable) {release.await()}}}}
    val port=object:CalendarMutations {
        override suspend fun loadForEdit(ref:EventRef)=EditReadResult.Ready(source)
        override suspend fun create(operationId:String,draft:EventDraft):MutationResult=error("not create")
        override suspend fun update(operationId:String,ref:EventRef,token:EditToken,patch:EventPatch):MutationResult {updates+=patch;return MutationResult.Uncertain(operationId,ref.eventId)}
        override suspend fun delete(operationId:String,ref:EventRef,token:EditToken):MutationResult=error("not delete")
        override suspend fun recover(operationId:String):MutationResult {recoveries+=operationId;return recovery}
    }
    fun seed()=source.details.let {EventDraft(it.occurrence.ref.calendarId,it.occurrence.time,it.eventZone,it.occurrence.title,it.location,it.description,it.reminders.filter {r->r.method==1}.map {r->r.minutesBefore},it.availability,it.privacy)}
    suspend fun prepare() {store.rows["draft"]=EventEditorController.initialRecord(seed(),source,"draft",true,clock)}
    fun record()=store.rows.getValue("draft")
    suspend fun open():EventEditorController {prepare();return restore()}
    fun openDirect(record:DraftRecord,recovery:Boolean=false):EventEditorController {
        val launch=DraftBrowserCodec.read(record).launch!!;val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined).also(scopes::add)
        return EventEditorController(seed(),if(recovery) null else source,"draft",port,store,scope,clock,recoveryOnly=recovery,recoveryRef=if(recovery) ref else null,
            requireStoredDraft=true,expectedGeneration=launch.generation,requestWritePermission={true})
    }
    suspend fun untilReady(c:EventEditorController) {withTimeout(5_000) {while(c.state.loading) yield()}}
    suspend fun restore()=openDirect(record()).also {untilReady(it)}
    suspend fun settle() {withTimeout(5_000) {scopes.filter {it.isActive}.forEach {scope->scope.coroutineContext[Job]!!.children.toList().forEach {it.join()}}}}
}
internal class ZoneIntentStore:AtomicDraftStore {
    val rows=mutableMapOf<String,DraftRecord>();var unknownAfter=false;var afterCas:suspend(DraftRecord?)->Unit={}
    override suspend fun load(id:String)=rows[id]
    override suspend fun list()=rows.values.toList()
    override suspend fun upsert(record:DraftRecord):Unit=error("no blind write")
    override suspend fun delete(id:String):Unit=error("no blind delete")
    override suspend fun compareAndSet(id:String,expected:DraftRecord?,replacement:DraftRecord?):DraftChangeResult {
        if(rows[id]!=expected) return DraftChangeResult.Changed
        if(replacement==null) rows.remove(id) else rows[id]=replacement
        afterCas(replacement);return if(unknownAfter) DraftChangeResult.Unavailable else DraftChangeResult.Applied
    }
}
