package dev.swapnilraj.calendar

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.events.EventEditorControllerTest
import dev.swapnilraj.calendar.events.eventTestCalendar
import dev.swapnilraj.calendar.events.meetings.create.MeetingCreationFixture
import dev.swapnilraj.calendar.ui.*
import dev.swapnilraj.calendar.ui.events.*
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
import java.time.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Workflow → actual phone slot → host → injected local coverage → readable passive prose.
 * Synthetic fixtures only; no installed-provider coverage or device behavior is inferred. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="en-rUS-w393dp-h851dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneEditorConflictWorkflowScreenshotsTest {
    @get:Rule val compose=createComposeRule()
    private val clear="No overlapping local events on the selected calendars."
    private val partial="Availability is incomplete for some selected calendars."
    private val first="Only the first occurrence is checked. Repeating events may have other overlaps."
    private val local="Based on events on this device. This does not check other people’s availability."
    private val calendar=eventTestCalendar().copy(providerId="synthetic-capture-calendar")
    private val date=LocalDate.of(2028,6,15)
    private val start=date.atTime(9,0).toInstant(ZoneOffset.UTC)
    private val draft=EventDraft(calendar.id,EventTime.Timed(start,start.plusSeconds(3600)),ZoneOffset.UTC,
        "Synthetic planning appointment",targetIdentity=calendar.identity)
    @After fun resetFont() {RuntimeEnvironment.setFontScale(1f)}

    private class Feed(private val coverage:ConflictCoverage):CalendarSchedulingConflicts {
        val requests=mutableListOf<ConflictReadRequest>()
        override fun observe(request:ConflictReadRequest):Flow<CalendarReadState<ConflictSnapshot>> {
            requests+=request
            return flowOf(CalendarReadState.Ready(ConflictSnapshot(
                request.context(ConflictSourceRevision("synthetic-workflow-capture",1)),emptyList(),coverage)))
        }
    }
    private class Writer:CalendarMutations {
        var writes=0
        override suspend fun loadForEdit(ref:EventRef)=EditReadResult.Unavailable
        override suspend fun create(operationId:String,draft:EventDraft):MutationResult {writes++;error("A capture never saves")}
        override suspend fun update(operationId:String,ref:EventRef,token:EditToken,patch:EventPatch):MutationResult {writes++;error("A capture never edits")}
        override suspend fun delete(operationId:String,ref:EventRef,token:EditToken):MutationResult {writes++;error("A capture never deletes")}
        override suspend fun recover(operationId:String):MutationResult=error("A capture never recovers")
    }
    private fun ordinary(store:EventEditorControllerTest.Store,repeating:Boolean=false):EventWriteRequest {
        val ready=runBlocking {
            if(repeating) prepareEditorFieldsDraft(draft,null,"conflict-capture",true,
                EventEditorController.fieldsFor(draft).copy(recurrence=RecurrenceEditorFields(frequency="DAILY")),store)
            else prepareNewDraft(draft,"conflict-capture",true,store)
        } as DraftPreparationResult.Ready
        return EventWriteRequest.fromLaunch(ready.launch,date,ZoneOffset.UTC)
    }
    private fun await(text:String)=compose.waitUntil(10_000) {
        compose.waitForIdle();compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
    private fun capture(path:String,vararg messages:String) {
        await(local)
        compose.onNodeWithText(local).performScrollTo()
        messages.forEach {compose.onNodeWithText(it).assertIsDisplayed()}
        compose.onNodeWithText(local).assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsEnabled().assertIsDisplayed()
        compose.onNode(isDialog()).captureRoboImage(path)
    }

    @Test fun ordinaryCompleteLocalCoverageRemainsPassiveAndFullyLabelled() {
        val store=EventEditorControllerTest.Store();val request=ordinary(store);val writer=Writer();val feed=Feed(ConflictCoverage.Complete)
        compose.setContent {DaylightTheme {
            EventWriteWorkflow(request,writer,store,listOf(calendar),true,{}, {}, {},conflictAdvisory={snapshot ->
                PhoneEditorConflictHost(feed,snapshot,setOf(calendar.identity),ZoneOffset.UTC)
            })
        }}
        capture("src/test/screenshots/phone_conflict_workflow_ordinary_clear.png",clear)
        assertEquals(1,feed.requests.size);assertNull(feed.requests.single().exclude)
        assertEquals(draft.time,feed.requests.single().proposedTime);assertEquals(0,writer.writes)
    }

    @Test fun meetingPartialCoverageInDarkThemeNeverPromisesAClearScheduleOrDisablesSave() {
        val f=MeetingCreationFixture();runBlocking {f.ready()};val route=f.launch()
        val feed=Feed(ConflictCoverage.Partial(setOf(ConflictCoverageProblem.UNVERIFIED_COVERAGE)))
        compose.setContent {DaylightTheme(dark=true) {
            MeetingCreationWorkflow(route,f.meetings,f.store,listOf(f.calendar),f.retirement,{true},true,{}, {},
                conflictAdvisory={snapshot ->PhoneEditorConflictHost(feed,snapshot,setOf(f.calendar.identity),ZoneOffset.UTC)})
        }}
        capture("src/test/screenshots/phone_conflict_workflow_meeting_partial_dark.png",partial)
        compose.onNodeWithText(clear).assertDoesNotExist()
        assertNull(feed.requests.single().exclude);assertTrue(f.calls.isEmpty());assertEquals(0,f.permissions)
    }

    @Test fun recurringCreateFirstOccurrenceDisclosureFitsLargeRtlPhoneWithoutChangingSave() {
        RuntimeEnvironment.setFontScale(2f)
        val store=EventEditorControllerTest.Store();val request=ordinary(store,repeating=true);val writer=Writer();val feed=Feed(ConflictCoverage.Complete)
        compose.setContent {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,2f),LocalLayoutDirection provides LayoutDirection.Rtl) {
                DaylightTheme {EventWriteWorkflow(request,writer,store,listOf(calendar),true,{}, {}, {},
                    recurringCreationSupported=true,conflictAdvisory={snapshot ->
                        PhoneEditorConflictHost(feed,snapshot,setOf(calendar.identity),ZoneOffset.UTC)
                    })}
            }
        }
        capture("src/test/screenshots/phone_conflict_workflow_first_rtl_large.png",clear,first)
        assertEquals(1,feed.requests.size);assertNull(feed.requests.single().exclude);assertEquals(0,writer.writes)
    }
}
