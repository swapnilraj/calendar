package dev.swapnilraj.calendar.events.meetings.people

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.swapnilraj.calendar.events.meetings.create.MeetingCreationFixture
import dev.swapnilraj.calendar.events.meetings.owned.OwnedFixture
import dev.swapnilraj.calendar.ui.events.meetings.create.*
import dev.swapnilraj.calendar.ui.events.meetings.owned.*
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="en-rUS-w393dp-h851dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GuestPickerParentDraftTest {
    @get:Rule val compose=createComposeRule()
    @Test fun createPickerDonePersistsAdditionOnlyAndLeavesControllerEditable() {
        val f=MeetingCreationFixture();val c=runBlocking {f.ready()}
        runBlocking {c.changeGuests(c.state.fields!!.guests+MeetingCreationGuestFields(email=" Invalid ",name=" raw ",type="UNKNOWN"))}
        val before=c.state.fields!!.guests
        compose.setContent {DaylightTheme {PhoneMeetingCreateEditor(c,listOf(f.calendar),{}, {})}}
        compose.onNodeWithText("Search or enter email").performScrollTo().performClick()
        compose.onNodeWithText("Name or email address").performTextReplacement(" partial@ ")
        compose.onNodeWithText("Add email to draft").performClick();compose.onNodeWithText("Done").performClick();compose.waitForIdle()
        compose.onNodeWithText("New meeting").assertExists()
        val restored=f.controller(f.launch());runBlocking {restored.load()}
        assertEquals(before,restored.state.fields!!.guests.dropLast(1));val added=restored.state.fields!!.guests.last()
        assertEquals(" partial@ ",added.email);assertEquals("",added.type);assertTrue(c.canEditRaw);assertTrue(f.calls.isEmpty());assertEquals(0,f.permissions)
    }
    @Test fun ownedPickerDonePreservesOpaqueChoicesAndAddsOnlyRawGuest() {
        val f=OwnedFixture();val c=runBlocking {f.open()};val before=c.state.fields!!.guests
        compose.setContent {DaylightTheme {PhoneOwnedMeetingEditor(c,{}, {}, {})}}
        compose.onNodeWithText("Search or enter email").performScrollTo().performClick()
        compose.onNodeWithText("Name or email address").performTextReplacement("invalid@")
        compose.onNodeWithText("Add email to draft").performClick();compose.onNodeWithText("Done").performClick();compose.waitForIdle()
        compose.onNodeWithText("Edit meeting").assertExists()
        val restored=f.controller(f.launch());runBlocking {restored.load()}
        assertEquals(before,restored.state.fields!!.guests);assertEquals("invalid@",restored.state.fields!!.added.single().email)
        assertEquals("",restored.state.fields!!.added.single().type);assertTrue(c.canAddGuests);assertTrue(f.calls.isEmpty());assertEquals(0,f.permissions)
    }
    @Test fun ownedReadMaskClosesPickerAndDoesNotResurrectItOnRestore() {
        val f=OwnedFixture();val c=runBlocking {f.open()};var visible by mutableStateOf(true)
        compose.setContent {DaylightTheme {PhoneOwnedMeetingEditor(c,{}, {}, {},presentationVisible=visible)}}
        compose.onNodeWithText("Search or enter email").performScrollTo().performClick();compose.onNodeWithText("Name or email address").assertExists()
        compose.runOnIdle {visible=false};compose.waitForIdle();compose.onNodeWithText("Name or email address").assertDoesNotExist()
        compose.runOnIdle {visible=true};compose.waitForIdle();compose.onNodeWithText("Name or email address").assertDoesNotExist();assertTrue(f.calls.isEmpty())
    }
    @Test fun createFailedDraftFlushKeepsPickerAndRetryDoesNotSaveMeeting() {
        val f=MeetingCreationFixture();val c=runBlocking {f.ready()}
        compose.setContent {DaylightTheme {PhoneMeetingCreateEditor(c,listOf(f.calendar),{}, {})}}
        compose.onNodeWithText("Search or enter email").performScrollTo().performClick()
        compose.runOnIdle {f.store.unknownBefore=true}
        compose.onNodeWithText("Name or email address").performTextReplacement("partial@")
        compose.onNodeWithText("Add email to draft").performClick();compose.onNodeWithText("Done").performClick();compose.waitForIdle()
        compose.onNodeWithText("Name or email address").assertExists();assertTrue(c.hasPendingRawInput);assertTrue(f.calls.isEmpty())
        compose.runOnIdle {f.store.unknownBefore=false}
        compose.onNodeWithText("Done").performClick();compose.waitForIdle();compose.onNodeWithText("New meeting").assertExists()
        assertFalse(c.hasPendingRawInput);assertEquals("partial@",c.state.fields!!.guests.last().email);assertTrue(c.canEditRaw);assertTrue(f.calls.isEmpty())
    }
    @Test fun ownedForeignSuccessorClosesPickerAndPreservesForeignRecord() {
        val f=OwnedFixture();val c=runBlocking {f.open()}
        compose.setContent {DaylightTheme {PhoneOwnedMeetingEditor(c,{}, {}, {})}}
        compose.onNodeWithText("Search or enter email").performScrollTo().performClick()
        val foreign=f.record().copy(updatedAt=f.clock.instant().plusSeconds(1))
        compose.runOnIdle {f.store.rows["owned"]=foreign}
        compose.onNodeWithText("Name or email address").performTextReplacement("new@example.test")
        compose.onNodeWithText("Add email to draft").performClick();compose.waitForIdle()
        compose.onNodeWithText("Name or email address").assertDoesNotExist();assertEquals(foreign,f.record());assertTrue(f.calls.isEmpty())
    }

    @Test fun suspendedActualCasThenSecondAppendAndDoneDrainInOrderWithoutStoppingController()=runBlocking {
        val f=MeetingCreationFixture();val c=f.ready()
        val invalid=MeetingCreationGuestFields(email=" invalid@ ",name=" raw ",type="UNKNOWN")
        c.changeGuests(c.state.fields!!.guests+invalid);val before=c.state.fields!!.guests
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var first=true
        f.store.beforeCas={_,_->if(first) {first=false;entered.complete(Unit);release.await()}}
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val actions=PhoneMeetingCreateActions(c,scope)
            val a=MeetingCreationGuestFields(email="first@example.test")
            val b=MeetingCreationGuestFields(email="second@example.test")
            actions.guests {it+a};assertTrue(entered.isCompleted)
            actions.guests {it+b};var done:Boolean?=null;actions.finishGuestPicker {done=it}
            assertNull(done);assertTrue(actions.inputLocked);assertEquals(before+listOf(a,b),actions.fields!!.guests)
            release.complete(Unit)
            assertEquals(true,done);assertFalse(actions.inputLocked);assertFalse(c.hasPendingRawInput)
            assertEquals(before+listOf(a,b),c.state.fields!!.guests)
            assertEquals(2,c.state.fields!!.guests.takeLast(2).map {it.rowId}.distinct().size)
            val restored=f.controller(f.launch());restored.load();assertEquals(before+listOf(a,b),restored.state.fields!!.guests)
            actions.event {it.copy(title="Still editable")};assertEquals("Still editable",c.state.fields!!.event.title)
            assertTrue(c.canEditRaw);assertTrue(f.calls.isEmpty());assertEquals(0,f.permissions)
        } finally {release.complete(Unit);scope.cancel()}
    }

}
