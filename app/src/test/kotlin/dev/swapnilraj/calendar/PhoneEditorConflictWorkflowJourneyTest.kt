package dev.swapnilraj.calendar

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.events.ConflictAdvisoryFeed
import dev.swapnilraj.calendar.events.EventEditorControllerTest
import dev.swapnilraj.calendar.events.eventTestCalendar
import dev.swapnilraj.calendar.events.meetings.create.MeetingCreationFixture
import dev.swapnilraj.calendar.events.meetings.owned.OwnedFixture
import dev.swapnilraj.calendar.ui.*
import dev.swapnilraj.calendar.ui.events.*
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
import java.time.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real workflow mounts and raw draft CAS, with an injected advisory-only local read port.
 * This does not certify installed-provider coverage, account sync or native device behavior. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="en-rUS-w393dp-h851dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneEditorConflictWorkflowJourneyTest {
    @get:Rule val compose=createComposeRule()
    private val clear="No overlapping local events on the selected calendars."
    private val partial="Availability is incomplete for some selected calendars."
    private val calendar=eventTestCalendar().copy(providerId="synthetic-calendar-A")
    private val date=LocalDate.of(2028,6,15)
    private val start=date.atTime(9,0).toInstant(ZoneOffset.UTC)
    private val draft=EventDraft(calendar.id,EventTime.Timed(start,start.plusSeconds(3600)),ZoneOffset.UTC,
        "Synthetic advisory appointment",targetIdentity=calendar.identity)
    private val ref=EventRef(calendar.id,42,start,start.plusSeconds(3600))
    private class Writer(var source:EditableEvent?=null):CalendarMutations {
        var mutations=0
        override suspend fun loadForEdit(ref:EventRef)=source?.let {EditReadResult.Ready(it)} ?: EditReadResult.Unavailable
        override suspend fun create(operationId:String,draft:EventDraft):MutationResult {mutations++;error("No explicit Save in this journey")}
        override suspend fun update(operationId:String,ref:EventRef,token:EditToken,patch:EventPatch):MutationResult {mutations++;error("No explicit Save in this journey")}
        override suspend fun delete(operationId:String,ref:EventRef,token:EditToken):MutationResult {mutations++;error("No explicit Delete in this journey")}
        override suspend fun recover(operationId:String):MutationResult=error("No recovery write is requested")
    }
    private fun prepare(store:EventEditorControllerTest.Store,source:EditableEvent?=null):EventWriteRequest {
        val ready=runBlocking {if(source==null) prepareNewDraft(draft,"advisory",true,store)
            else prepareEditDraft(draft,source,"advisory",store)} as DraftPreparationResult.Ready
        return EventWriteRequest.fromLaunch(ready.launch,date,ZoneOffset.UTC)
    }
    private fun awaitReads(feed:ConflictAdvisoryFeed,count:Int) {
        try {compose.waitUntil(10_000) {compose.waitForIdle();feed.requests.size>=count && feed.collectors.size>=count}}
        catch(failure:Throwable) {
            println("Advisory timeout expected=$count requests=${feed.requests.size} collectors=${feed.collectors.size} cancelled=${feed.cancelled}")
            println(compose.onAllNodes(isRoot()).onLast().printToString())
            throw failure
        }
    }
    private fun ready(feed:ConflictAdvisoryFeed,index:Int,coverage:ConflictCoverage=ConflictCoverage.Complete,
        candidates:List<ConflictCandidate> = emptyList()) {
        compose.runOnIdle {runBlocking {feed.emit(index,CalendarReadState.Ready(feed.snapshot(index,candidates=candidates,coverage=coverage)))}}
        compose.waitForIdle()
    }
    private fun countCandidate(request:ConflictReadRequest):ConflictCandidate {
        val time=request.proposedTime as EventTime.Timed
        val identity=request.calendars.single()
        return ConflictCandidate(identity,EventRef(identity.calendarId,99,time.start,time.end),time,
            ConflictAvailability.BUSY,ConflictEventStatus.ACTIVE,ConflictParticipation.PERSONAL)
    }

    @Test fun ordinaryMissingSelectedNamespaceCannotShrinkToAnApparentlyClearSubset() {
        val feed=ConflictAdvisoryFeed();val store=EventEditorControllerTest.Store();val request=prepare(store);val writer=Writer()
        var selection by mutableStateOf<CalendarSelection>(CalendarSelection.Explicit(setOf(calendar.id)))
        compose.setContent {DaylightTheme {
            EventWriteWorkflow(request,writer,store,listOf(calendar),true,{}, {}, {},conflictAdvisory={snapshot ->
                PhoneEditorConflictHost(feed,snapshot,conflictCalendarIdentities(CalendarReadState.Ready(listOf(calendar)),selection),ZoneId.of("Pacific/Auckland"))
            })
        }}
        awaitReads(feed,1);assertEquals(setOf(calendar.identity),feed.requests.single().calendars)
        assertEquals(ZoneId.of("Pacific/Auckland"),feed.requests.single().planningZone)
        assertEquals(draft.time,feed.requests.single().proposedTime);assertNull(feed.requests.single().exclude)
        ready(feed,0);compose.onNodeWithText(clear).assertExists();compose.onNodeWithText("Save").assertIsEnabled()
        compose.runOnIdle {selection=CalendarSelection.Explicit(setOf(calendar.id,CalendarId(999)))}
        compose.onNodeWithText(clear).assertDoesNotExist()
        compose.onNodeWithText("The selected calendars are unavailable.").assertExists()
        assertEquals(1,feed.requests.size)
        // Truly empty selection is a separate explicit state, not failed metadata resolution.
        compose.runOnIdle {selection=CalendarSelection.Explicit(emptySet())}
        compose.onNodeWithText("Choose calendars in the sidebar to check for overlaps.").assertExists()
        assertEquals(1,feed.requests.size);assertEquals(0,writer.mutations)
        compose.onNodeWithText("Save").assertIsEnabled()
    }

    @Test fun ordinaryPlanningZoneChangeKeepsExactEditExclusionAndRejectsOldClear() {
        val source=EditableEvent(EventDetails(EventOccurrence(ref,draft.time,draft.title,1),calendar,eventZone=ZoneOffset.UTC),
            EditToken("synthetic-edit-source"),EditableField.entries.toSet())
        val feed=ConflictAdvisoryFeed();val store=EventEditorControllerTest.Store();val request=prepare(store,source);val writer=Writer(source)
        var zone by mutableStateOf<ZoneId>(ZoneOffset.UTC)
        compose.setContent {DaylightTheme {
            EventWriteWorkflow(request,writer,store,listOf(calendar),true,{}, {}, {},conflictAdvisory={snapshot ->
                PhoneEditorConflictHost(feed,snapshot,setOf(calendar.identity),zone)
            })
        }}
        awaitReads(feed,1);assertEquals(ref,feed.requests[0].exclude);ready(feed,0)
        compose.onNodeWithText(clear).assertExists()
        compose.runOnIdle {zone=ZoneId.of("America/New_York")}
        awaitReads(feed,2);assertEquals(ref,feed.requests[1].exclude)
        assertEquals(draft.time,feed.requests[1].proposedTime)
        assertEquals(zone,feed.requests[1].planningZone)
        assertNotEquals(feed.requests[0].draftRevision,feed.requests[1].draftRevision)
        ready(feed,0);compose.onNodeWithText(clear).assertDoesNotExist()
        ready(feed,1,ConflictCoverage.Partial(setOf(ConflictCoverageProblem.UNVERIFIED_COVERAGE)))
        compose.onNodeWithText(partial).assertExists();compose.onNodeWithText(clear).assertDoesNotExist()
        assertEquals(0,writer.mutations)
    }

    @Test fun meetingCreateQueuedChangeBackCannotRedisplayOldClearWhileRawCasIsSuspended() {
        val f=MeetingCreationFixture();runBlocking {f.ready()};val feed=ConflictAdvisoryFeed();val route=f.launch()
        compose.setContent {DaylightTheme {
            MeetingCreationWorkflow(route,f.meetings,f.store,listOf(f.calendar),f.retirement,{true},true,{}, {},
                conflictAdvisory={snapshot ->PhoneEditorConflictHost(feed,snapshot,setOf(f.calendar.identity),ZoneOffset.UTC)})
        }}
        awaitReads(feed,1);assertNull(feed.requests[0].exclude);ready(feed,0)
        compose.onNodeWithText(clear).assertExists();compose.onNodeWithText("Save").assertIsEnabled()
        val gate=CompletableDeferred<Unit>();val entered=CompletableDeferred<Unit>()
        f.store.beforeCas={_,next ->if(next!=null) {entered.complete(Unit);gate.await()}}
        val title=compose.onNodeWithContentDescription("Title")
        title.performTextReplacement("Synthetic temporary title")
        compose.waitUntil(5_000) {entered.isCompleted}
        title.performTextReplacement("Planning");compose.waitForIdle()
        compose.onNodeWithText(clear).assertDoesNotExist();assertEquals(1,feed.requests.size)
        ready(feed,0);compose.onNodeWithText(clear).assertDoesNotExist()
        f.store.beforeCas={_,_->};gate.complete(Unit);awaitReads(feed,2)
        assertEquals(feed.requests[0].proposedTime,feed.requests[1].proposedTime)
        assertNotEquals(feed.requests[0].draftRevision,feed.requests[1].draftRevision)
        compose.onNodeWithText(clear).assertDoesNotExist()
        ready(feed,1);compose.onNodeWithText(clear).assertExists();compose.onNodeWithText("Save").assertIsEnabled()
        assertTrue(f.calls.isEmpty());assertEquals(0,f.permissions)
    }

    @Test fun ownedSourceNamespaceReplacementOnResumeDoesNotAdmitAnotherClearOrMutation() {
        val f=OwnedFixture();runBlocking {f.open()};val feed=ConflictAdvisoryFeed();val launch=f.launch()
        val route=OwnedMeetingRoute(launch.draftId,launch.generation);val retained=f.record()
        var visible by mutableStateOf(true)
        compose.setContent {DaylightTheme {
            OwnedMeetingWorkflow(route,f.meetings,f.store,f.retirement,{true},true,visible,{}, {}, {},
                conflictAdvisory={snapshot ->PhoneEditorConflictHost(feed,snapshot,setOf(f.calendar.identity),ZoneOffset.UTC)})
        }}
        awaitReads(feed,1);assertEquals(f.ref,feed.requests[0].exclude);ready(feed,0)
        compose.onNodeWithText(clear).assertExists()
        compose.runOnIdle {visible=false}
        compose.onNodeWithText(clear).assertDoesNotExist()
        val sourceReads=f.reads
        f.source=f.source.copy(details=f.source.details.copy(calendar=f.calendar.copy(providerId="synthetic-replacement-B")))
        compose.runOnIdle {visible=true}
        compose.waitUntil(10_000) {compose.waitForIdle();f.reads>sourceReads && compose.onAllNodesWithText(
            "This meeting or its permissions changed. Your entries remain here. Reopen its details before starting another draft.").fetchSemanticsNodes().isNotEmpty()}
        compose.waitForIdle();ready(feed,0)
        compose.onNodeWithText(clear).assertDoesNotExist();compose.onNodeWithText("Save").assertIsNotEnabled()
        assertEquals(1,feed.requests.size);assertEquals(retained,f.record());assertTrue(f.calls.isEmpty())
    }

    @Test fun ordinaryPermissionMaskRemovesPrivateUiAndRechecksOnlyAfterFreshMount() {
        val feed=ConflictAdvisoryFeed();val store=EventEditorControllerTest.Store();val request=prepare(store);val writer=Writer()
        var permitted by mutableStateOf(true)
        compose.setContent {DaylightTheme {
            EventWriteWorkflow(request,writer,store,listOf(calendar),true,{}, {}, {},presentationVisible=permitted,
                conflictAdvisory={snapshot ->PhoneEditorConflictHost(feed,snapshot,setOf(calendar.identity),ZoneOffset.UTC,readPermission=permitted)})
        }}
        awaitReads(feed,1);ready(feed,0);compose.onNodeWithText(clear).assertExists()
        compose.runOnIdle {permitted=false}
        compose.onNodeWithText(clear).assertDoesNotExist();compose.onNodeWithContentDescription("Title").assertDoesNotExist()
        compose.onNodeWithText(draft.title).assertDoesNotExist();assertEquals(1,feed.requests.size)
        compose.runOnIdle {permitted=true};awaitReads(feed,2)
        ready(feed,0);compose.onNodeWithText(clear).assertDoesNotExist()
        ready(feed,1);compose.onNodeWithText(clear).assertExists();assertTrue(feed.cancelled>=1)
        assertNotEquals(feed.requests[0].draftRevision,feed.requests[1].draftRevision);assertEquals(0,writer.mutations)
    }

    @Test fun guestPickerReturnMountsFreshAdvisoryOwnerInsteadOfReusingClosedController() {
        val f=MeetingCreationFixture();runBlocking {f.ready()};val feed=ConflictAdvisoryFeed();val route=f.launch()
        compose.setContent {DaylightTheme {
            MeetingCreationWorkflow(route,f.meetings,f.store,listOf(f.calendar),f.retirement,{true},true,{}, {},
                conflictAdvisory={snapshot ->PhoneEditorConflictHost(feed,snapshot,setOf(f.calendar.identity),ZoneOffset.UTC)})
        }}
        awaitReads(feed,1);ready(feed,0);compose.onNodeWithText(clear).assertExists()
        compose.onNodeWithText("Search or enter email").performScrollTo().performClick()
        compose.onNodeWithText("Add people").assertIsDisplayed();compose.onNodeWithText(clear).assertDoesNotExist()
        assertEquals(1,feed.requests.size)
        compose.onNodeWithText("Back").performClick();awaitReads(feed,2)
        assertNotEquals(feed.requests[0].draftRevision,feed.requests[1].draftRevision)
        ready(feed,0);compose.onNodeWithText(clear).assertDoesNotExist()
        ready(feed,1,candidates=listOf(countCandidate(feed.requests[1])))
        compose.onNodeWithText("1 overlapping local event.").assertExists();compose.onNodeWithText(clear).assertDoesNotExist()
        assertTrue(feed.cancelled>=1);assertTrue(f.calls.isEmpty());assertEquals(0,f.permissions)
        compose.onNodeWithText("Save").assertIsEnabled()
    }
}
