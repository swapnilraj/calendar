package dev.swapnilraj.calendar.events.meetings.create

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.swapnilraj.calendar.core.AccessDecision
import dev.swapnilraj.calendar.ui.events.RecurrenceEditorFields
import dev.swapnilraj.calendar.ui.events.meetings.create.*
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
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
class RecurringMeetingCreationScreenshotsTest {
    @get:Rule val compose=createComposeRule()
    private val f=MeetingCreationFixture()
    @After fun resetFontScale() {RuntimeEnvironment.setFontScale(1f)}

    private fun show(review:Boolean,largeRtl:Boolean=false,use24Hour:Boolean=true) {
        val controller=runBlocking {
            f.source=f.source.copy(recurringCreation=AccessDecision.ALLOWED)
            f.ready().also {
                it.changeFields(it.state.fields!!.event.copy(recurrence=RecurrenceEditorFields(frequency="DAILY",endMode="COUNT",count="3")))
                if(review) it.save()
            }
        }
        RuntimeEnvironment.setFontScale(if(largeRtl) 2f else 1f)
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides if(largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                DaylightTheme {PhoneMeetingCreateEditor(controller,listOf(f.calendar),{}, {},use24Hour=use24Hour)}
            }
        }
        compose.waitForIdle()
    }

    @Test fun recurringMeetingFormLight() {
        show(review=false)
        compose.onNodeWithText("Daily").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsEnabled()
        compose.onNode(isDialog()).captureRoboImage("src/test/screenshots/phone_recurring_meeting_form_light.png")
    }

    @Test fun recurringMeetingReviewLight() {
        show(review=true)
        compose.onNodeWithText("Create recurring event").assertIsDisplayed()
        compose.onNode(isDialog() and hasAnyDescendant(hasText("Review recurring event")))
            .captureRoboImage("src/test/screenshots/phone_recurring_meeting_review_light.png")
    }

    @Test fun meetingHostCarriesTwelveHourChoiceIntoBoundsAndPreview() {
        show(review=true,use24Hour=false)
        val inReview=hasAnyAncestor(isDialog() and hasAnyDescendant(hasText("Review recurring event")))
        compose.onAllNodes(hasText("AM",substring=true) and inReview).assertCountEquals(4)
        compose.onAllNodes(hasText("09:00",substring=true) and inReview).assertCountEquals(0)
    }

    @Test fun recurringMeetingReviewLargeRtl() {
        show(review=true,largeRtl=true)
        compose.onNodeWithText("Create recurring event").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
        compose.onNode(isDialog() and hasAnyDescendant(hasText("Review recurring event")))
            .captureRoboImage("src/test/screenshots/phone_recurring_meeting_review_rtl_large.png")
    }
}
