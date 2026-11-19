package dev.swapnilraj.calendar.events.meetings

import android.provider.CalendarContract
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.meetings.*
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
import java.time.ZoneOffset
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w393dp-h851dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeetingResponseScreenTest {
    @get:Rule val compose = createComposeRule()
    private val f = MeetingResponseFixture()
    @After fun stop() { f.scope.cancel() }
    private fun show(controller: MeetingResponseController, review: ((MeetingReadResult<EditableMeeting>) -> Unit)? = null) {
        compose.setContent { DaylightTheme { Surface(Modifier.fillMaxSize()) { MeetingResponseContent(controller, {}, ZoneOffset.UTC, review) } } }
    }
    @Test fun openingHasNoDefaultScopeOrResponseEvenWhenPreviousResponseWasYes() = runBlocking<Unit> {
        val source = f.source.copy(details = f.source.details.copy(selfAttendeeStatus = CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED))
        val controller = f.controller(f.prepare(source), source); show(controller)
        compose.onNodeWithText("Response when this draft was opened: Yes").assertIsDisplayed()
        compose.onNodeWithText("This event").assertIsNotSelected(); compose.onNodeWithText("Yes").performScrollTo().assertIsNotSelected()
        compose.onNodeWithText("Entire series").assertIsNotEnabled()
        compose.onNodeWithText("Save response on this device").performScrollTo().assertIsNotEnabled()
        assertEquals(0, f.permissionCalls); assertTrue(f.calls.isEmpty())
    }
    @Test fun noIsExplicitDeclineAndLocalSaveNeverClaimsServerDelivery() = runBlocking<Unit> {
        val controller = f.controller(f.prepare()); show(controller)
        compose.onNodeWithText("This event").performClick()
        compose.onNodeWithText("No").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("No declines your invitation for the chosen scope. It does not cancel the event for the organizer or other guests.").assertIsDisplayed()
        compose.onNodeWithText("Save response on this device").performScrollTo().assertIsEnabled()
        compose.onRoot().captureRoboImage("src/test/screenshots/meeting_response_light.png")
        assertTrue(f.calls.isEmpty()); assertEquals(0, f.permissionCalls)
        compose.onNodeWithText("Save response on this device").performClick(); compose.waitForIdle()
        compose.onNodeWithText("Saved on this device. Delivery to the organizer has not been verified.").performScrollTo().assertIsDisplayed()
        assertEquals(MeetingCommand.Respond(RecurrenceScope.THIS, MeetingResponse.NO), f.calls.single().command)
        compose.onAllNodesWithText("Cancel event", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Response sent", substring = true).assertCountEquals(0)
    }
    @Test fun frozenSourceMissingShowsReadOnlyRecoveryAndExactRetryOnlyAfterProof() = runBlocking<Unit> {
        f.pending(); val controller = f.controller(f.launch(), null); show(controller)
        compose.onNodeWithText("The original response request is locked until its outcome is resolved. Its scope, response, and destination cannot be replaced here.").assertIsDisplayed()
        compose.onNodeWithText("Yes").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Save response on this device").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Retry original response").assertDoesNotExist()
        f.proof = true; compose.onNodeWithText("Review original operation").performScrollTo().performClick()
        compose.onNodeWithText("Retry original response").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Entire series").performScrollTo().assertIsNotEnabled(); assertEquals(1, f.calls.size)
    }
    @Test fun technicalBackendReasonAndUnwiredReviewActionAreNotRendered() = runBlocking<Unit> {
        val controller = f.controller(f.prepare()); f.choose(controller); f.next = MeetingMutationResult.NeedsReview("SECRET opaque-source-id"); controller.save(); show(controller)
        compose.onNodeWithText("This meeting response needs review. The original request is retained.").assertIsDisplayed()
        compose.onAllNodesWithText("SECRET", substring = true).assertCountEquals(0)
        compose.onNodeWithText("Discard draft and review latest meeting").assertDoesNotExist()
        compose.onNodeWithText("No").performScrollTo().assertIsNotEnabled()
    }
    @Test fun explicitUnsubmittedReviewConfirmsAndReadsFreshBeforeExactDeletion() = runBlocking<Unit> {
        val controller = f.controller(f.prepare(), f.source.copy(token = EditToken("fresh-token")))
        var reviewed: MeetingReadResult<EditableMeeting>? = null
        f.onRead = { assertTrue(f.store.rows.isNotEmpty()); assertFalse(controller.canChange) }
        show(controller) { reviewed = it }
        compose.onNodeWithText("Discard draft and review latest meeting").performScrollTo().performClick()
        assertTrue(f.store.rows.isNotEmpty()); assertEquals(0, f.reads)
        compose.onNodeWithText("Discard draft").performClick(); compose.waitForIdle()
        assertTrue(reviewed is MeetingReadResult.Ready<*>); assertTrue(f.store.rows.isEmpty()); assertEquals(1, f.reads); assertEquals(0, f.permissionCalls); assertTrue(f.calls.isEmpty())
    }
    @Test fun failedNativeSwipeFlushReshowsResponseChoicesAndCanCloseAfterRepair() = runBlocking<Unit> {
        val controller = f.controller(f.prepare()); f.choose(controller); var closed = false
        f.store.unavailableBefore = true
        compose.setContent { DaylightTheme { MeetingResponseSheet(controller, { closed = true }, {}, ZoneOffset.UTC) } }
        compose.onNodeWithText("Respond to invitation").assertIsDisplayed()
        val before = f.store.compares
        compose.onNodeWithText("Respond to invitation").onParent().performTouchInput { swipeDown(startY = height * 0.1f, endY = height * 0.95f, durationMillis = 250) }
        compose.waitUntil(5_000) { f.store.compares > before }; compose.waitForIdle()
        assertFalse(closed)
        compose.onNodeWithText("Respond to invitation").assertIsDisplayed()
        compose.onNodeWithText("Yes").performScrollTo().assertIsSelected()
        assertTrue(f.calls.isEmpty()); assertEquals(0, f.permissionCalls)
        f.store.unavailableBefore = false; compose.onNodeWithText("Close").performScrollTo().performClick(); compose.waitForIdle()
        assertTrue(closed); assertEquals(MeetingResponseFields("THIS", "YES"), MeetingResponseDraftCodec.decode(f.record()).fields)
    }
    @Test fun savingSwipeIsGuardedAndLocalCallbackUsesVerifiedRefAndScope() = runBlocking<Unit> {
        val controller = f.controller(f.prepare()); f.choose(controller); val gate = CompletableDeferred<Unit>(); f.permissionGate = gate
        controller.save(); var closed = false; var saved: MeetingMutationResult.LocalSaved? = null
        compose.setContent { DaylightTheme { MeetingResponseSheet(controller, { closed = true }, { saved = it }, ZoneOffset.UTC) } }
        compose.onNodeWithText("Respond to invitation").onParent().performTouchInput { swipeDown(startY = height * 0.1f, endY = height * 0.95f, durationMillis = 250) }
        compose.waitForIdle(); assertFalse(closed); compose.onNodeWithText("Respond to invitation").assertIsDisplayed()
        assertNull(saved); assertTrue(f.calls.isEmpty()); gate.complete(Unit); compose.waitForIdle()
        assertEquals(MeetingMutationResult.LocalSaved(f.selected, RecurrenceScope.THIS), saved); assertEquals(1, f.calls.size)
    }
}
