package dev.swapnilraj.calendar.events

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.*
import dev.swapnilraj.calendar.ui.events.meetings.create.*
import dev.swapnilraj.calendar.ui.events.meetings.owned.*
import dev.swapnilraj.calendar.events.meetings.create.MeetingCreationFixture
import dev.swapnilraj.calendar.events.meetings.owned.OwnedFixture
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
import java.time.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercises the phone's actual optimistic projection, beyond the controller's persisted fields. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="en-rUS-w393dp-h851dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneEditorConflictSlotTest {
    @get:Rule val compose=createComposeRule()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
    @After fun stop() {scope.cancel()}
    private val at=Instant.parse("2028-06-15T09:00:00Z")
    private val draft=EventDraft(CalendarId(2),EventTime.Timed(at,at.plusSeconds(3600)),ZoneOffset.UTC,
        "Synthetic slot fixture",targetIdentity=eventTestIdentity())

    @Test fun meetingQueuedRawChangeBackMasksEqualTemporalProjectionUntilEveryCasAcknowledges() {
        val f=MeetingCreationFixture();val c=runBlocking {f.ready()};var shown:PhoneEditorConflictSnapshot?=null
        compose.setContent {DaylightTheme {PhoneMeetingCreateEditor(c,listOf(f.calendar),{}, {},conflictAdvisory={
            SideEffect {shown=it}
        })}}
        compose.waitUntil(5_000) {shown?.binding!=null}
        val before=shown!!;assertNull(before.binding!!.editedRef)
        compose.onNodeWithText("Save").assertIsEnabled()
        val gate=CompletableDeferred<Unit>();val entered=CompletableDeferred<Unit>()
        f.store.beforeCas={_,replacement -> if(replacement!=null) {entered.complete(Unit);gate.await()}}
        val title=compose.onNodeWithContentDescription("Title")
        title.performTextReplacement("Synthetic temporary title")
        compose.waitUntil(5_000) {entered.isCompleted && shown?.pendingRaw==true}
        title.performTextReplacement("Planning")
        compose.waitForIdle()
        // Equal temporal fields and a change-back must not reuse the old assessed result.
        assertEquals(before.visibleTime,shown!!.visibleTime);assertTrue(shown!!.pendingRaw)
        assertTrue(f.calls.isEmpty());assertEquals(0,f.permissions)
        f.store.beforeCas={_,_->};gate.complete(Unit)
        compose.waitUntil(5_000) {shown?.pendingRaw==false && shown?.binding!=null}
        assertNotEquals(before.binding!!.revision,shown!!.binding!!.revision)
        compose.onNodeWithText("Save").assertIsEnabled();assertTrue(f.calls.isEmpty())
    }

    @Test fun ownedQueuedRawChangeBackKeepsExactExclusionButCannotPublishOldClear() {
        val f=OwnedFixture();val c=runBlocking {f.open().also {
            it.changeFields(it.state.fields!!.copy(event=it.state.fields!!.event.copy(title="Synthetic retained title")))
        }}
        var shown:PhoneEditorConflictSnapshot?=null
        compose.setContent {DaylightTheme {PhoneOwnedMeetingEditor(c,{}, {}, {},conflictAdvisory={SideEffect {shown=it}})}}
        compose.waitUntil(5_000) {shown?.binding!=null}
        val before=shown!!;assertEquals(f.ref,before.binding!!.editedRef)
        assertEquals(f.calendar.identity,before.binding!!.calendarIdentity)
        compose.onNodeWithText("Save").assertIsEnabled()
        val gate=CompletableDeferred<Unit>();val entered=CompletableDeferred<Unit>()
        f.store.afterCas={if(it!=null) {entered.complete(Unit);gate.await()}}
        val title=compose.onNodeWithContentDescription("Title")
        title.performTextReplacement("Synthetic temporary title")
        compose.waitUntil(5_000) {entered.isCompleted && shown?.pendingRaw==true}
        title.performTextReplacement("Synthetic retained title");compose.waitForIdle()
        assertEquals(before.visibleTime,shown!!.visibleTime);assertTrue(shown!!.pendingRaw)
        f.store.afterCas={};gate.complete(Unit)
        compose.waitUntil(5_000) {shown?.pendingRaw==false && shown?.binding!=null}
        assertEquals(f.ref,shown!!.binding!!.editedRef)
        assertNotEquals(before.binding!!.revision,shown!!.binding!!.revision)
        compose.onNodeWithText("Save").assertIsEnabled();assertTrue(f.calls.isEmpty());assertEquals(0,f.permissions)
    }

    @Test fun ordinaryExistingSlotRetainsExactSourceAndMasksWithEntireEditor() {
        val ref=EventRef(CalendarId(2),42,at,at.plusSeconds(3600))
        val source=EditableEvent(EventDetails(EventOccurrence(ref,draft.time,draft.title,1),eventTestCalendar(),
            eventZone=ZoneOffset.UTC),EditToken("synthetic-source"),EditableField.entries.toSet())
        val writer=EventEditorControllerTest().Writer();val c=preparedEditor(draft,source,"slot",writer,
            EventEditorControllerTest.Store(),scope)
        var visible by mutableStateOf(true);var shown:PhoneEditorConflictSnapshot?=null
        compose.setContent {DaylightTheme {PhoneEventEditor(c,listOf(eventTestCalendar()),{}, {}, {},
            contentVisible=visible,conflictAdvisory={snapshot -> SideEffect {shown=snapshot};Text("Synthetic advisory slot")})}}
        compose.waitUntil(5_000) {shown?.binding!=null}
        assertEquals(ref,shown!!.binding!!.editedRef);assertEquals(source.details.calendar!!.identity,shown!!.binding!!.calendarIdentity)
        assertFalse(shown!!.firstOccurrenceOnly)
        compose.onNodeWithText("Synthetic advisory slot").assertExists()
        compose.runOnIdle {visible=false}
        compose.onNodeWithText("Synthetic advisory slot").assertDoesNotExist()
        compose.onNodeWithContentDescription("Title").assertDoesNotExist()
        assertEquals(0,writer.updates);assertEquals(0,writer.creates)
    }

    @Test fun ordinaryRecurringCreateSlotUsesOnlyFirstOccurrenceAndHasNoEditExclusion() {
        val writer=EventEditorControllerTest().Writer();val c=preparedEditor(draft,null,"slot",writer,
            EventEditorControllerTest.Store(),scope,recurringCreationSupported=true)
        c.change {it.copy(recurrence=RecurrenceEditorFields(frequency="DAILY"))};runBlocking {assertTrue(c.flushDraft())}
        var shown:PhoneEditorConflictSnapshot?=null
        compose.setContent {DaylightTheme {PhoneEventEditor(c,listOf(eventTestCalendar()),{}, {}, {},
            conflictAdvisory={SideEffect {shown=it}})}}
        compose.waitUntil(5_000) {shown?.binding!=null}
        assertTrue(shown!!.firstOccurrenceOnly);assertNull(shown!!.binding!!.editedRef)
        compose.onNodeWithText("Save").assertIsEnabled()
        assertEquals(0,writer.creates)
    }
}
