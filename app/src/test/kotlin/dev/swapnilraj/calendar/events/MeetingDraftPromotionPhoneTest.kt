package dev.swapnilraj.calendar.events

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import dev.swapnilraj.calendar.ui.events.*
import dev.swapnilraj.calendar.ui.events.meetings.create.*
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
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
@Config(sdk=[35],qualifiers="en-rUS-w393dp-h851dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeetingDraftPromotionPhoneTest {
    @get:Rule val compose=createComposeRule()
    private val fixture=PromotionFixture()
    @After fun cleanup() {fixture.release.complete(Unit);fixture.scope.cancel()}

    @Test fun addPeoplePromotesLatestRawAndOnlyThenHandsOffValidatedMeetingLaunch() {
        val controller=runBlocking {fixture.open()};var promoted:MeetingCreationLaunch?=null
        compose.setContent {DaylightTheme {PhoneEventEditor(controller,listOf(eventTestCalendar()),{}, {}, {},onPromotedMeeting={promoted=it})}}
        compose.onNodeWithContentDescription("Title").performTextReplacement("Latest raw title")
        compose.onNodeWithText("Add people").performScrollTo().assertIsDisplayed()
        compose.onNode(isDialog()).captureRoboImage("src/test/screenshots/phone_event_editor_add_people_light.png")
        compose.onNodeWithText("Add people").performClick()
        compose.waitUntil(5_000) {promoted!=null}
        assertEquals(promoted,MeetingCreationDraftCodec.describe(fixture.record()))
        assertEquals("Latest raw title",MeetingCreationDraftCodec.decode(fixture.record()).fields.event.title)
        assertEquals(1,fixture.promotionAttempts);assertEquals(0,fixture.providerCalls);assertEquals(0,fixture.permissions)
    }

    @Test fun optOutOrMissingHostCallbackDoesNotOfferPromotion() {
        runBlocking {fixture.open()};val controller=fixture.restore(allowed=false)
        compose.setContent {DaylightTheme {PhoneEventEditor(controller,listOf(eventTestCalendar()),{}, {}, {},onPromotedMeeting={error("not offered")})}}
        compose.onNodeWithText("Add people").assertDoesNotExist()
        assertEquals(0,fixture.promotionAttempts)
    }

    @Test fun unknownPromotionNeverCallsHostButCloseStillLeavesRecoverableSingleDraft() {
        val controller=runBlocking {fixture.open()};fixture.failPromotionReadback=true
        var promoted:MeetingCreationLaunch?=null;var dismissed=false
        compose.setContent {DaylightTheme {PhoneEventEditor(controller,listOf(eventTestCalendar()),{dismissed=true},{},{},onPromotedMeeting={promoted=it})}}
        compose.onNodeWithText("Add people").performScrollTo().performClick();compose.waitForIdle()
        assertNull(promoted);compose.onNodeWithText("Add people").assertDoesNotExist()
        compose.onNodeWithContentDescription("Title").assertIsNotEnabled();compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Close event editor").performClick();compose.waitUntil(5_000) {dismissed}
        assertNotNull(MeetingCreationDraftCodec.describe(fixture.record()));assertEquals(1,fixture.rows.size)
        assertEquals(0,fixture.providerCalls)
    }
}
