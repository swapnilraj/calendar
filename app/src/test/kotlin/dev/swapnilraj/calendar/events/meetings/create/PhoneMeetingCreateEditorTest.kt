package dev.swapnilraj.calendar.events.meetings.create

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.*
import dev.swapnilraj.calendar.ui.events.meetings.create.*
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="en-rUS-w393dp-h851dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneMeetingCreateEditorTest {
    @get:Rule val compose=createComposeRule()
    private val f=MeetingCreationFixture()
    private fun show(c:MeetingCreationController,dark:Boolean=false,rtlLarge:Boolean=false,onDismiss:()->Unit={},onSaved:(EventRef)->Unit={},onDiscarded:()->Unit=onDismiss) {
        RuntimeEnvironment.setFontScale(if(rtlLarge) 2f else 1f)
        compose.setContent { CompositionLocalProvider(LocalLayoutDirection provides if(rtlLarge) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            DaylightTheme(dark=dark) {PhoneMeetingCreateEditor(c,listOf(f.calendar),onDismiss,onSaved,onDiscarded)}
        } }
        compose.waitForIdle()
    }
    @After fun reset() {RuntimeEnvironment.setFontScale(1f)}
    @Test fun initialFormHasStableToolbarAndNeverWritesOnOpenOrClose() {
        val c=runBlocking {f.open()}; var closed=false; show(c,onDismiss={closed=true})
        compose.onNodeWithText("New meeting").assertIsDisplayed(); compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Saving adds the meeting on this device. Your calendar service handles invitations and delivery.").performScrollTo()
        compose.onNodeWithContentDescription("Close meeting editor").assertIsDisplayed().performClick()
        assertTrue(closed); assertTrue(f.calls.isEmpty()); assertEquals(0,f.reads); assertEquals(0,f.permissions)
    }
    @Test fun calendarChoiceReadsBackendOnlyAndNeverSubmitsMeeting() {
        val c=runBlocking {f.open()}; show(c)
        compose.onNodeWithText("Work").performClick(); compose.onNode(hasText("Work") and hasClickAction()).performClick(); compose.waitForIdle()
        assertEquals(1,f.reads); assertEquals(f.calendar.identity,c.state.fields!!.event.targetIdentity)
        assertTrue(f.calls.isEmpty()); assertEquals(0,f.permissions)
    }
    @Test fun orderedPeopleKeepPartialEmailNameAndRoleAcrossCloseAndReopen() {
        val c=runBlocking {f.ready()}; var closed=false; show(c,onDismiss={closed=true})
        compose.onNodeWithText("Add person").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Person 2: Email address").performScrollTo().performTextReplacement("partial@")
        compose.onNodeWithContentDescription("Person 2: Name (optional)").performScrollTo().performTextReplacement(" raw name ")
        compose.onAllNodesWithText("Optional").onLast().performScrollTo().performClick()
        compose.onNodeWithContentDescription("Close meeting editor").performClick(); compose.waitForIdle()
        assertTrue(closed); assertEquals(listOf("guest@example.test","partial@"),c.state.fields!!.guests.map {it.email})
        val reopened=f.controller(f.launch()); runBlocking {reopened.load()}
        assertEquals(c.state.fields,reopened.state.fields); assertEquals(" raw name ",reopened.state.fields!!.guests.last().name)
        assertEquals("OPTIONAL",reopened.state.fields!!.guests.last().type); assertTrue(f.calls.isEmpty())
    }
    @Test fun fixedPolicyNeedsExplicitAckAndLocalSaveNeedsExactCleanupBeforeCallback() {
        val c=runBlocking {f.ready().also {it.acceptFixedGuestPolicy(false)}}; var saved:EventRef?=null
        f.next=MeetingMutationResult.LocalSaved(f.ref,RecurrenceScope.THIS); show(c,onSaved={saved=it})
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Guests can see the other guests. They cannot change this meeting or invite other people.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Use these guest permissions").performClick(); compose.onNodeWithText("Save").assertIsEnabled().performClick(); compose.waitForIdle()
        assertEquals(1,f.calls.size); assertNull(saved); assertTrue(f.store.rows.isNotEmpty())
        compose.onNodeWithText("Saved on this device").assertIsDisplayed()
        compose.onNodeWithText("Your calendar service handles invitation delivery. Daylight cannot confirm that guests received an invitation.").assertIsDisplayed()
        compose.onNodeWithText("Done").performClick(); compose.waitForIdle(); assertEquals(f.ref,saved); assertTrue(f.store.rows.isEmpty()); assertTrue(c.state.acknowledged)
    }
    @Test fun frozenRestoreDoesNotReadSourceAndOnlyExplicitExactRetryWrites() {
        runBlocking {f.ready().save()}; val c=f.controller(f.launch()); val reads=f.reads
        val operation=MeetingCreationDraftCodec.decode(f.record()).operationId
        f.recovery=MeetingMutationResult.Common(MutationResult.SafeToRetry(operation)); show(c)
        assertEquals(reads,f.reads); compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNode(hasText("Check save result") and hasClickAction()).performClick(); compose.waitForIdle(); assertEquals(1,f.calls.size)
        compose.onNodeWithText("Retry original save").performClick(); compose.waitForIdle()
        assertEquals(2,f.calls.size); assertEquals(f.calls.first(),f.calls.last()); assertEquals(reads,f.reads)
    }
    @Test fun failedRawPersistenceKeepsEditorOpenUntilExplicitFlushAndNeverWritesEvent() {
        val c=runBlocking {f.ready()}; var closed=false; show(c,onDismiss={closed=true}); f.store.unknownBefore=true
        compose.onNodeWithContentDescription("Title").performTextReplacement("Unfinished typed title"); compose.waitForIdle()
        compose.onNodeWithContentDescription("Close meeting editor").performClick(); compose.waitForIdle(); assertFalse(closed)
        assertEquals("Unfinished typed title",c.state.fields!!.event.title)
        compose.onNodeWithText("Your latest entries could not be saved as a draft. Keep this editor open and try again.").assertIsDisplayed()
        f.store.unknownBefore=false; compose.onNodeWithText("Try saving draft").performClick(); compose.waitForIdle()
        compose.onNodeWithContentDescription("Close meeting editor").performClick(); compose.waitForIdle(); assertTrue(closed)
        assertEquals("Unfinished typed title",MeetingCreationDraftCodec.decode(f.record()).fields.event.title); assertTrue(f.calls.isEmpty())
    }
    @Test fun discardRequiresConfirmationAndDoesNotCreateOrCancelProviderEvent() {
        val c=runBlocking {f.ready()}; var discarded=false; show(c,onDiscarded={discarded=true})
        compose.onNodeWithText("Discard draft").performScrollTo().performClick(); assertFalse(discarded); assertTrue(f.store.rows.isNotEmpty())
        compose.onNodeWithText("Cancel").performClick(); assertFalse(discarded)
        compose.onNodeWithText("Discard draft").performClick(); compose.onAllNodesWithText("Discard draft").onLast().performClick(); compose.waitForIdle()
        assertTrue(discarded); assertTrue(f.store.rows.isEmpty()); assertTrue(f.calls.isEmpty()); assertEquals(0,f.retireCalls)
    }
    @Test fun importedRawRecurrenceIsKeptVisibleUnsupportedAndNeverOffersRuleEditor() {
        val c=runBlocking {f.ready().also {it.changeFields(it.state.fields!!.event.copy(recurrence=RecurrenceEditorFields(frequency="DAILY",count="partial")))}}
        val exact=f.record(); show(c)
        compose.onNodeWithText("Repeating meetings cannot be created here yet. Your repeat entries remain in this draft.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsNotEnabled(); compose.onNodeWithText("Repeat").assertDoesNotExist()
        assertEquals(exact,f.record()); assertTrue(f.calls.isEmpty())
    }
    @Test fun failedLocalOutcomeCleanupNeverReportsAcknowledgedOrDeletesChangedDraft() {
        val c=runBlocking {f.ready()}; f.next=MeetingMutationResult.LocalSaved(f.ref,RecurrenceScope.THIS)
        runBlocking {c.save()}; val original=f.record(); f.store.rows["draft"]=original.copy(updatedAt=original.updatedAt.plusSeconds(1))
        var saved:EventRef?=null; show(c,onSaved={saved=it}); compose.onNodeWithText("Done").performClick(); compose.waitForIdle()
        assertNull(saved); assertFalse(c.state.acknowledged); assertTrue(f.store.rows.isNotEmpty()); assertEquals(1,f.calls.size)
    }
    @Test fun rawDatePickerFallbackKeepsIncompleteDateInMeetingDraft() {
        val c=runBlocking {f.ready()}; show(c)
        compose.onNodeWithContentDescription("Starts: Thursday, June 15, 2028").performClick()
        compose.onNodeWithText("Enter as text").performClick(); compose.onNodeWithText("Date (YYYY-MM-DD)").performTextReplacement("2028-")
        compose.onNodeWithText("Done").performClick(); compose.waitForIdle()
        assertEquals("2028-",c.state.fields!!.event.startDate); assertEquals("2028-",MeetingCreationDraftCodec.decode(f.record()).fields.event.startDate)
        compose.onNodeWithText("Save").performClick(); assertTrue(f.calls.isEmpty())
    }
    @Test fun phoneMeetingCreateLight() {val c=runBlocking {f.ready()};show(c);compose.onNodeWithText("Person 1").performScrollTo();compose.onRoot().captureRoboImage("src/test/screenshots/phone_meeting_create_light.png")}
    @Test fun phoneMeetingCreateDark() {val c=runBlocking {f.ready()};show(c,dark=true);compose.onRoot().captureRoboImage("src/test/screenshots/phone_meeting_create_dark.png")}
    @Test fun phoneMeetingCreateLargeRtl() {
        val c=runBlocking {f.ready()};show(c,rtlLarge=true);compose.onNodeWithText("Person 1").performScrollTo()
        compose.onNodeWithContentDescription("Close meeting editor").assertIsDisplayed();compose.onNodeWithText("Save").assertIsDisplayed()
        compose.onRoot().captureRoboImage("src/test/screenshots/phone_meeting_create_rtl_large.png")
    }
}
