package dev.swapnilraj.calendar.events

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.events.meetings.create.MeetingCreationFixture
import dev.swapnilraj.calendar.events.meetings.owned.OwnedFixture
import dev.swapnilraj.calendar.ui.events.*
import dev.swapnilraj.calendar.ui.events.meetings.create.*
import dev.swapnilraj.calendar.ui.events.meetings.owned.*
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

/** Semantics/state checks only: actual TalkBack speech still requires runtime observation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="en-rUS-w393dp-h851dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneEditorStatusAccessibilityTest {
    @get:Rule val compose=createComposeRule()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
    private val polite=SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion,LiveRegionMode.Polite)
    private val draft=EventDraft(CalendarId(2),EventTime.Timed(Instant.parse("2028-06-15T09:00:00Z"),Instant.parse("2028-06-15T10:00:00Z")),ZoneOffset.UTC,
        "Private title",location="Private room",description="Private notes",targetIdentity=eventTestIdentity())
    @After fun stop() {scope.cancel()}
    private fun ordinary(writer:EventEditorControllerTest.Writer,store:EventEditorControllerTest.Store)=preparedEditor(draft,null,"a11y",writer,store,scope)
    private fun liveText():List<String> = compose.onAllNodes(polite).fetchSemanticsNodes().flatMap {node->node.config[SemanticsProperties.Text].map {it.text}}
    private fun assertNoPrivateLivePayload() {
        liveText().forEach {text->assertFalse(text.contains("Private"));assertFalse(text.contains("secret@"));assertFalse(text.contains("opaque-"))}
    }
    @Test fun ordinaryConflictIsOneBoundedLiveMessageAndMaskRemovesIt() {
        val writer=EventEditorControllerTest().Writer().apply {next=MutationResult.Conflict(null)}
        val c=ordinary(writer,EventEditorControllerTest.Store());var visible by mutableStateOf(true)
        compose.setContent {DaylightTheme {PhoneEventEditor(c,listOf(eventTestCalendar()),{}, {}, {},contentVisible=visible)}}
        compose.runOnIdle {c.save()};compose.waitForIdle()
        assertEquals(EditorProblem.CONFLICT,c.state.problem);compose.onAllNodes(polite).assertCountEquals(1);assertNoPrivateLivePayload()
        assertEquals(1,writer.creates)
        compose.runOnIdle {visible=false};compose.waitForIdle()
        compose.onAllNodes(polite).assertCountEquals(0);compose.onNodeWithText("Private title").assertDoesNotExist()
        assertEquals("Private title",c.state.fields.title);assertEquals(1,writer.creates)
    }
    @Test fun repeatedOrdinaryFailedAutosavesKeepTraversableWarningWithoutLiveInsertion() {
        val writer=EventEditorControllerTest().Writer();val store=EventEditorControllerTest.Store();val c=ordinary(writer,store)
        compose.setContent {DaylightTheme {PhoneEventEditor(c,listOf(eventTestCalendar()),{}, {}, {})}}
        store.failWrites=true;var attempts=0;store.beforeCas={_,_,_->attempts++}
        repeat(3) { index ->
            compose.runOnIdle {c.change {it.copy(title="Private typing $index")}}
            compose.waitUntil(5_000) {c.state.problem==EditorProblem.DRAFT_UNAVAILABLE}
            compose.waitForIdle();assertEquals(EditorProblem.DRAFT_UNAVAILABLE,c.state.problem)
            compose.onAllNodes(polite).assertCountEquals(0)
            compose.onNodeWithText("Your draft could not be saved on this device. Keep this screen open and try again.").assertExists()
            assertEquals("Private typing $index",c.state.fields.title)
        }
        assertEquals(3,attempts);assertEquals(0,writer.creates)
    }
    @Test fun ordinarySuccessfulSaveAnnouncesFixedOutcomeWithoutPrivatePayload() {
        val writer=EventEditorControllerTest().Writer();val c=ordinary(writer,EventEditorControllerTest.Store())
        compose.setContent {DaylightTheme {PhoneEventEditor(c,listOf(eventTestCalendar()),{}, {}, {})}}
        compose.runOnIdle {c.save()};compose.waitForIdle()
        compose.onNode(hasText("This event was saved on this device. Finish closing its draft; it will not be saved again.") and polite).assertExists()
        compose.onAllNodes(polite).assertCountEquals(1);assertNoPrivateLivePayload();assertEquals(1,writer.creates)
    }
    @Test fun createFlushFailureRemainsBoundedWhileTypingThenClearsAfterExplicitFlush() {
        val f=MeetingCreationFixture();val c=runBlocking {f.ready()}
        compose.setContent {DaylightTheme {PhoneMeetingCreateEditor(c,listOf(f.calendar),{}, {})}}
        compose.runOnIdle {f.store.unknownBefore=true;runBlocking {c.changeFields(c.state.fields!!.event.copy(title="Private first"))}}
        compose.waitForIdle();val first=liveText();val firstNode=compose.onAllNodes(polite).fetchSemanticsNodes().single().id;assertEquals(1,first.size);assertNoPrivateLivePayload()
        compose.runOnIdle {runBlocking {c.changeFields(c.state.fields!!.event.copy(title="Private continued"))}}
        compose.waitForIdle();assertEquals(first,liveText());assertEquals(firstNode,compose.onAllNodes(polite).fetchSemanticsNodes().single().id);assertEquals("Private continued",c.state.fields!!.event.title);assertTrue(f.calls.isEmpty())
        compose.runOnIdle {f.store.unknownBefore=false;runBlocking {c.flushDraft()}};compose.waitForIdle()
        compose.onAllNodes(polite).assertCountEquals(0);assertFalse(c.hasPendingRawInput);assertTrue(f.calls.isEmpty())
    }
    @Test fun createSaveTransitionAnnouncesOnlyFixedSavedSummary() {
        val f=MeetingCreationFixture();val c=runBlocking {f.ready()};f.next=MeetingMutationResult.LocalSaved(f.ref,RecurrenceScope.THIS)
        compose.setContent {DaylightTheme {PhoneMeetingCreateEditor(c,listOf(f.calendar),{}, {})}}
        compose.onAllNodes(polite).assertCountEquals(0)
        compose.runOnIdle {runBlocking {c.save()}};compose.waitForIdle()
        compose.onNode(hasText("Saved on this device") and polite).assertExists();compose.onAllNodes(polite).assertCountEquals(1)
        assertNoPrivateLivePayload();assertEquals(1,f.calls.size)
    }
    @Test fun ownedFlushFailureIsBoundedAndReadMaskRemovesStatusAndRawPayload() {
        val f=OwnedFixture();val c=runBlocking {f.open()};var visible by mutableStateOf(true)
        compose.setContent {DaylightTheme {PhoneOwnedMeetingEditor(c,{}, {}, {},presentationVisible=visible)}}
        compose.runOnIdle {f.store.unknownBefore=true;runBlocking {c.changeFields(c.state.fields!!.copy(event=c.state.fields!!.event.copy(title="Private owned")))}}
        compose.waitForIdle();compose.onAllNodes(polite).assertCountEquals(1);assertNoPrivateLivePayload();assertTrue(f.calls.isEmpty())
        compose.runOnIdle {visible=false};compose.waitForIdle()
        compose.onAllNodes(polite).assertCountEquals(0);compose.onNodeWithText("Private owned").assertDoesNotExist();assertNull(c.state.fields)
        assertTrue(c.hasPendingRawInput);assertTrue(f.calls.isEmpty())
    }
    @Test fun ownedForeignDraftConflictAnnouncesFixedWarningWithoutOpaqueRefs() {
        val f=OwnedFixture();val c=runBlocking {f.open()}
        compose.setContent {DaylightTheme {PhoneOwnedMeetingEditor(c,{}, {}, {})}}
        compose.runOnIdle {f.store.rows["owned"]=f.record().copy(updatedAt=f.clock.instant().plusSeconds(1));runBlocking {c.changeFields(c.state.fields!!.copy(event=c.state.fields!!.event.copy(title="Private lost draft")))}}
        compose.waitForIdle()
        compose.onNode(hasText("This draft changed elsewhere. This editor cannot replace it.") and polite).assertExists()
        compose.onAllNodes(polite).assertCountEquals(1);assertNoPrivateLivePayload();assertTrue(f.calls.isEmpty())
    }
    @Test fun sharedPanelHasNamedPaneAndHeadingWithoutAnnouncingPrivateChildText() {
        compose.setContent {DaylightTheme {PhoneEditorPanelFrame("Location",{}) {Text("Private room")}}}
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle,"Location")).assertExists()
        compose.onNode(hasText("Location") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertExists()
        compose.onAllNodes(polite).assertCountEquals(0)
    }
    @Test fun ordinaryReadMaskRemovesOpenChildPaneAndItsPrivatePayload() {
        val c=ordinary(EventEditorControllerTest().Writer(),EventEditorControllerTest.Store());var visible by mutableStateOf(true)
        compose.setContent {DaylightTheme {PhoneEventEditor(c,listOf(eventTestCalendar()),{}, {}, {},contentVisible=visible)}}
        compose.onNodeWithText("Private room").performScrollTo().performClick();compose.waitForIdle()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle,"Location")).assertExists()
        compose.runOnIdle {visible=false};compose.waitForIdle()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle,"Location")).assertDoesNotExist()
        compose.onNodeWithText("Private room").assertDoesNotExist();assertEquals("Private room",c.state.fields.location)
    }
    @Test fun datePickerHasNamedPaneAndHeadingWithoutChangingRawInput() {
        var raw="2028-06-15"
        compose.setContent {DaylightTheme {PhoneEditorDatePicker(raw,"Start date",true,{}, {raw=it})}}
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle,"Start date"))
            .assertExists().assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
        compose.onNode(hasText("Start date") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .assertExists().assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
        // Material's existing selected-date/month announcements remain untouched.
        assertEquals("2028-06-15",raw)
    }
    @Test fun timePickerHasNamedPaneAndHeadingWithoutChangingRawInput() {
        var raw="09:00"
        compose.setContent {DaylightTheme {PhoneEditorTimePicker(raw,"Start time",true,true,{}, {raw=it})}}
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle,"Start time")).assertExists()
        compose.onNode(hasText("Start time") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertExists()
        assertEquals("09:00",raw);compose.onAllNodes(polite).assertCountEquals(0)
    }
}
