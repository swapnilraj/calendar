package dev.swapnilraj.calendar.events.meetings.create

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.*
import dev.swapnilraj.calendar.ui.events.meetings.create.*
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
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
class RecurringMeetingCreationPhoneTest {
    @get:Rule val compose=createComposeRule()
    private val f=MeetingCreationFixture()
    private fun show(c:MeetingCreationController) {
        compose.setContent {DaylightTheme {PhoneMeetingCreateEditor(c,listOf(f.calendar),{}, {})}}
        compose.waitForIdle()
    }
    private fun ready(repeat:Boolean=true)=runBlocking {
        f.source=f.source.copy(recurringCreation=AccessDecision.ALLOWED)
        f.ready().also {if(repeat) it.changeFields(it.state.fields!!.event.copy(recurrence=RecurrenceEditorFields(frequency="DAILY",endMode="COUNT",count="3")))}
    }

    @Test fun issuedRepeatRightOpensExistingRawControlsWithoutWritingMeeting() {
        val c=ready(repeat=false);show(c)
        compose.onNodeWithText("Does not repeat").performScrollTo().performClick()
        compose.onNodeWithText("Daily").performClick();compose.waitForIdle()
        assertEquals("DAILY",c.state.fields!!.event.recurrence.frequency)
        assertEquals("DAILY",MeetingCreationDraftCodec.decode(f.record()).fields.event.recurrence.frequency)
        assertTrue(f.calls.isEmpty());assertEquals(0,f.permissions)
    }

    @Test fun saveThenExplicitReviewConfirmationCreatesOnlyFrozenTypedRecurrence() {
        val c=ready();show(c)
        compose.onNodeWithText("Save").assertIsEnabled().performClick();compose.waitForIdle()
        compose.onNodeWithText("Review recurring event").assertIsDisplayed()
        compose.onNodeWithText("Create recurring event").assertIsDisplayed()
        assertTrue(f.calls.isEmpty());assertEquals(0,f.permissions);assertFalse(c.frozen)
        compose.onNodeWithText("Create recurring event").performClick();compose.waitForIdle()
        assertEquals(1,f.calls.size);assertNotNull(f.calls.single().draft.recurrence);assertTrue(c.frozen)
    }

    @Test fun cancelReviewAndPermissionGrantNeverSubmitWithoutAnotherExplicitConfirmation() {
        val c=ready();show(c)
        compose.onNodeWithText("Save").performClick();compose.onNodeWithText("Cancel").performClick();compose.waitForIdle()
        assertNull(c.state.recurrenceReview);assertTrue(f.calls.isEmpty());assertEquals(0,f.permissions)
        f.granted=false;compose.onNodeWithText("Save").performClick();compose.onNodeWithText("Create recurring event").performClick();compose.waitForIdle()
        assertFalse(c.frozen);assertTrue(f.calls.isEmpty());f.granted=true;compose.waitForIdle();assertTrue(f.calls.isEmpty())
        compose.onNodeWithText("Create recurring event").performClick();compose.waitForIdle();assertEquals(1,f.calls.size)
    }

    @Test fun missingRecurringRightNeverOffersRepeatControlsForOneOffOrRetainedRawRule() {
        val c=runBlocking {f.ready()};show(c)
        compose.onNodeWithText("Does not repeat").assertDoesNotExist();compose.onNodeWithText("Repeat").assertDoesNotExist()
        runBlocking {c.changeFields(c.state.fields!!.event.copy(recurrence=RecurrenceEditorFields(frequency="DAILY")))}
        compose.onNodeWithText("Repeating meetings cannot be created here yet. Your repeat entries remain in this draft.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsNotEnabled();assertTrue(f.calls.isEmpty())
    }
}
