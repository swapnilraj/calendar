package dev.swapnilraj.calendar.events.meetings.owned

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.swapnilraj.calendar.ui.events.meetings.owned.*
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
class OwnedMeetingEditorScreenshotsTest {
    @get:Rule val compose=createComposeRule()
    @After fun resetFontScale() {RuntimeEnvironment.setFontScale(1f)}
    private fun show(dark:Boolean=false,largeRtl:Boolean=false) {
        val f=OwnedFixture();f.source=f.source.copy(details=f.source.details.copy(availability=0,privacy=0))
        val controller=runBlocking {f.open()}
        RuntimeEnvironment.setFontScale(if(largeRtl) 2f else 1f)
        compose.setContent {CompositionLocalProvider(LocalLayoutDirection provides if(largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            DaylightTheme(dark=dark) {PhoneOwnedMeetingEditor(controller,{}, {}, {})}
        }}
        compose.waitForIdle()
    }
    @Test fun ownedMeetingFormLight() {
        show();compose.onNodeWithText("A").performScrollTo()
        compose.onNodeWithContentDescription("Close meeting editor").assertIsDisplayed()
        compose.onNode(isDialog()).captureRoboImage("src/test/screenshots/phone_owned_meeting_form_light.png")
    }
    @Test fun ownedMeetingFormDark() {
        show(dark=true);compose.onNodeWithText("A").performScrollTo()
        compose.onNodeWithContentDescription("More meeting options").assertIsDisplayed()
        compose.onNode(isDialog()).captureRoboImage("src/test/screenshots/phone_owned_meeting_form_dark.png")
    }
    @Test fun ownedMeetingFormLargeRtl() {
        show(largeRtl=true);compose.onNodeWithText("a@example.test").performScrollTo()
        compose.onNodeWithContentDescription("Close meeting editor").assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsDisplayed()
        compose.onNode(isDialog()).captureRoboImage("src/test/screenshots/phone_owned_meeting_form_rtl_large.png")
    }
}
