package dev.swapnilraj.calendar.events.meetings

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.*
import dev.swapnilraj.calendar.ui.events.meetings.*
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
import java.time.ZoneOffset
import java.time.ZoneId
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
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
class MeetingResponseWorkflowScreenTest {
    @get:Rule val compose = createComposeRule()
    private val f = MeetingResponseFixture()
    @After fun stop() { f.scope.cancel() }

    @Test fun twelveHourWorkflowForwardsClockAndDisplayZoneWithoutChangingDraftOrSubmitting() = runBlocking<Unit> {
        val launch = f.prepare(); val exact = f.record(); val originalTime = f.source.details.occurrence.time
        compose.setContent { DaylightTheme {
            MeetingResponseWorkflow(launch, f.meetings, f.store, f.permission, {}, {},
                displayZone = ZoneId.of("America/New_York"), use24Hour = false)
        } }
        compose.waitUntil { compose.onAllNodesWithText("5:00 AM GMT-4", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("6:00 AM GMT-4", substring = true).assertIsDisplayed()
        compose.onAllNodesWithText("05:00", substring = true).assertCountEquals(0)
        assertEquals(exact, f.record()); assertEquals(originalTime, f.source.details.occurrence.time)
        assertTrue(f.calls.isEmpty()); assertEquals(0, f.permissionCalls)
    }

    @Test fun twentyFourHourWorkflowForwardsClockAndFractionalZoneWithoutChangingDraftOrSubmitting() = runBlocking<Unit> {
        val launch = f.prepare(); val exact = f.record(); val originalTime = f.source.details.occurrence.time
        compose.setContent { DaylightTheme {
            MeetingResponseWorkflow(launch, f.meetings, f.store, f.permission, {}, {},
                displayZone = ZoneId.of("Asia/Kolkata"), use24Hour = true)
        } }
        compose.waitUntil { compose.onAllNodesWithText("14:30 GMT+5:30", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("15:30 GMT+5:30", substring = true).assertIsDisplayed()
        compose.onAllNodesWithText("PM", substring = true).assertCountEquals(0)
        assertEquals(exact, f.record()); assertEquals(originalTime, f.source.details.occurrence.time)
        assertTrue(f.calls.isEmpty()); assertEquals(0, f.permissionCalls)
    }

    @Test fun nativeWorkflowSavesOnlyExplicitInvitationResponse() = runBlocking<Unit> {
        val launch = f.prepare(); var saved: MeetingMutationResult.LocalSaved? = null
        compose.setContent { DaylightTheme {
            MeetingResponseWorkflow(launch, f.meetings, f.store, f.permission, {}, { saved = it }, displayZone = ZoneOffset.UTC)
        } }
        compose.waitUntil { compose.onAllNodesWithText("This event").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Save response on this device").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("This event").performScrollTo().performClick()
        compose.onNodeWithText("Yes").performScrollTo().performClick()
        compose.onNodeWithText("Save response on this device").performScrollTo().performClick()
        compose.waitUntil { saved != null }
        assertEquals(MeetingMutationResult.LocalSaved(f.selected, RecurrenceScope.THIS), saved)
        assertEquals(MeetingCommand.Respond(RecurrenceScope.THIS, MeetingResponse.YES), f.calls.single().command)
        assertEquals(1, f.permissionCalls); assertEquals(0, f.createCalls); assertTrue(f.store.rows.isEmpty())
    }

    @Test fun sourceMissingFrozenWorkflowOffersRecoveryWithoutReadingSource() = runBlocking<Unit> {
        f.pending(); val launch = f.launch().copy(needsRecovery = false); f.readResult = MeetingReadResult.Unavailable
        compose.setContent { DaylightTheme { MeetingResponseWorkflow(launch, f.meetings, f.store, f.permission, {}, {}) } }
        compose.waitUntil { compose.onAllNodesWithText("Review original operation").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Save response on this device").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Review original operation").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(0, f.reads); assertEquals(1, f.recoveries.size); assertEquals(1, f.calls.size)
    }

    @Test fun reviewRequiresExplicitDiscardAndPublishesNewGeneration() = runBlocking<Unit> {
        val launch = f.prepare(); f.readResult = MeetingReadResult.Ready(f.source.copy(token = EditToken("fresh")))
        var replacement: MeetingResponseLaunch? = null
        compose.setContent { DaylightTheme { MeetingResponseWorkflow(launch, f.meetings, f.store, f.permission, {}, {},
            onReplacedLaunch = { replacement = it }) } }
        compose.waitUntil { compose.onAllNodesWithText("Discard draft and review latest meeting").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Discard draft and review latest meeting").performScrollTo().performClick()
        assertTrue(f.store.rows.containsKey(launch.draftId)); assertNull(replacement)
        compose.onNodeWithText("Discard draft").performClick()
        compose.waitUntil { replacement != null }
        assertNotEquals(launch.draftId, replacement!!.draftId); assertNotEquals(launch.generation, replacement!!.generation)
        assertFalse(f.store.rows.containsKey(launch.draftId)); assertTrue(f.store.rows.containsKey(replacement!!.draftId))
        assertTrue(f.calls.isEmpty()); assertEquals(0, f.permissionCalls)
    }
}
