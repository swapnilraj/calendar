package dev.swapnilraj.calendar.events.meetings.owned

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.meetings.owned.*
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
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
class PhoneOwnedMeetingEditorTest {
    @get:Rule val compose=createComposeRule()
    private val f=OwnedFixture()
    private fun show(c:OwnedMeetingEditorController,onSaved:(EventRef)->Unit={},onCancelled:(EventRef)->Unit={},onDismiss:()->Unit={}) {
        compose.setContent {DaylightTheme {PhoneOwnedMeetingEditor(c,onDismiss,onSaved,onCancelled)}}
        compose.waitForIdle()
    }
    @Test fun openAndCloseNeverWriteAndAvailabilityHasNoBorrowedEditor() {
        val c=runBlocking {f.open()};var closed=false;show(c,onDismiss={closed=true})
        compose.onNodeWithText("Edit meeting").assertIsDisplayed();compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Availability").performScrollTo();compose.onNodeWithText("Availability").assertHasNoClickAction()
        compose.onNodeWithContentDescription("Close meeting editor").performClick();compose.waitUntil(5_000) {closed}
        assertTrue(f.calls.isEmpty());assertEquals(0,f.permissions)
    }
    @Test fun nativeRawPeopleInputPersistsPartialEntriesOnCloseWithoutProviderWrite() {
        val c=runBlocking {f.open()};var closed=false;show(c,onDismiss={closed=true})
        compose.onNodeWithText("Add person").performScrollTo().performClick()
        compose.onNodeWithContentDescription("New person 1: Email address").performScrollTo().performTextReplacement("partial@")
        compose.onNodeWithContentDescription("New person 1: Name (optional)").performScrollTo().performTextReplacement("Partial name")
        compose.onNodeWithText("Save").assertIsNotEnabled();compose.onNodeWithContentDescription("Close meeting editor").performClick();compose.waitUntil(5_000) {closed}
        val reopened=f.controller(f.launch());runBlocking {reopened.load()}
        assertEquals("partial@",reopened.state.fields!!.added.single().email);assertEquals("Partial name",reopened.state.fields!!.added.single().name);assertTrue(f.calls.isEmpty())
    }
    @Test fun explicitCancellationWarnsAndNeverReportsSavedBeforeDoneCleanup() {
        val c=runBlocking {f.open()};var cancelled:EventRef?=null;f.next=MeetingMutationResult.LocalCancelled(f.ref,RecurrenceScope.THIS);show(c,onCancelled={cancelled=it})
        compose.onNodeWithContentDescription("More meeting options").performClick();compose.onNodeWithText("Cancel meeting").performClick()
        compose.onNodeWithText("Cancel this meeting for everyone? Your unsaved field and guest changes will not be applied. Your calendar service handles cancellation notices.").assertIsDisplayed()
        assertTrue(f.calls.isEmpty());compose.onAllNodesWithText("Cancel meeting").onLast().performClick();compose.waitForIdle()
        assertEquals(MeetingCommand.CancelOwned(RecurrenceScope.THIS),f.calls.single().command);assertNull(cancelled)
        compose.onNodeWithText("Cancelled on this device").assertIsDisplayed();compose.onNodeWithText("Done").performClick();compose.waitUntil(5_000) {cancelled!=null}
        assertEquals(f.ref,cancelled);assertTrue(f.store.rows.isEmpty());assertTrue(c.state.acknowledged)
    }
    @Test fun titleEditCallbacksWaitForExactCleanupAndNeverFireForChangedDraft() {
        val c=runBlocking {f.open()};var saved:EventRef?=null;f.next=MeetingMutationResult.LocalSaved(f.ref,RecurrenceScope.THIS);show(c,onSaved={saved=it})
        compose.onNodeWithContentDescription("Title").performTextReplacement("Changed title")
        compose.onNodeWithText("Save").assertIsEnabled().performClick();compose.waitForIdle()
        assertNull(saved);compose.onNodeWithText("Saved on this device").assertIsDisplayed()
        val successor=f.record().copy(updatedAt=f.clock.instant().plusSeconds(1));f.store.rows["owned"]=successor
        compose.onNodeWithText("Done").performClick();compose.waitForIdle();assertNull(saved);assertEquals(successor,f.record());assertFalse(c.state.acknowledged)
    }
    @Test fun maskingDuringInFlightSaveHidesMeetingAndPreservesOutcomeUntilGrant() {
        val c=runBlocking {f.open()};f.mutateGate=CompletableDeferred();f.next=MeetingMutationResult.LocalSaved(f.ref,RecurrenceScope.THIS)
        var visible by mutableStateOf(true)
        compose.setContent {DaylightTheme {PhoneOwnedMeetingEditor(c,{}, {}, {},presentationVisible=visible)}}
        compose.onNodeWithContentDescription("Title").performTextReplacement("Private synthetic title")
        compose.onNodeWithText("Save").performClick();compose.waitUntil(5_000) {f.calls.size==1}
        compose.runOnIdle {visible=false};compose.waitForIdle()
        compose.onNodeWithText("Private synthetic title").assertDoesNotExist();compose.onNodeWithText("Calendar access is required to show this meeting.").assertIsDisplayed()
        compose.runOnIdle {f.mutateGate!!.complete(Unit)};compose.waitForIdle();assertNull(c.state.localOutcome)
        val reads=f.reads;compose.runOnIdle {visible=true};compose.waitForIdle()
        compose.onNodeWithText("Saved on this device").assertIsDisplayed();assertEquals(reads,f.reads);assertEquals(1,f.calls.size)
    }
    @Test fun staleLatestRawNeedsExplicitLeaveConfirmationAndNeverDeletesChangedDraft() {
        val c=runBlocking {f.open()};var closed=false;show(c,onDismiss={closed=true})
        val successor=f.record().copy(updatedAt=f.clock.instant().plusSeconds(1));f.store.rows["owned"]=successor
        compose.onNodeWithContentDescription("Title").performTextReplacement("Latest unsaved input");compose.waitForIdle()
        compose.onNodeWithContentDescription("Close meeting editor").performClick();compose.waitForIdle();assertFalse(closed)
        compose.onNodeWithText("Your latest entries could not be saved because the draft changed elsewhere. Close without those latest entries? The changed draft will be kept.").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick();assertFalse(closed)
        compose.onNodeWithContentDescription("Close meeting editor").performClick();val loads=f.store.loads;val changes=f.store.changes
        compose.onNodeWithText("Close").performClick();compose.waitUntil(5_000) {closed}
        assertEquals(successor,f.record());assertEquals(loads,f.store.loads);assertEquals(changes,f.store.changes);assertTrue(f.calls.isEmpty())
    }
    @Test fun completedOperationWithChangedDraftCanLeaveWithoutAcknowledgingForeignRecord() {
        val c=runBlocking {f.open()};f.next=MeetingMutationResult.LocalSaved(f.ref,RecurrenceScope.THIS);var closed=false;var saved:EventRef?=null
        show(c,onSaved={saved=it},onDismiss={closed=true});compose.onNodeWithContentDescription("Title").performTextReplacement("Saved input")
        compose.onNodeWithText("Save").performClick();compose.waitForIdle()
        val successor=f.record().copy(updatedAt=f.clock.instant().plusSeconds(1));f.store.rows["owned"]=successor
        compose.onNodeWithText("Done").performClick();compose.waitForIdle();assertNull(saved)
        compose.onNodeWithContentDescription("Close meeting editor").performClick();compose.waitForIdle()
        compose.onNodeWithText("The operation finished on this device, but its draft changed elsewhere. Close this editor and keep the changed draft? Your calendar service still handles delivery to guests.").assertIsDisplayed()
        val loads=f.store.loads;val changes=f.store.changes;compose.onNodeWithText("Close").performClick();compose.waitUntil(5_000) {closed}
        assertNull(saved);assertEquals(successor,f.record());assertFalse(c.state.acknowledged)
        assertEquals(loads,f.store.loads);assertEquals(changes,f.store.changes);assertEquals(1,f.calls.size)
    }
}
