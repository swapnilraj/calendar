package dev.swapnilraj.calendar

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import dev.swapnilraj.calendar.android.reminders.ReminderPermissionState
import dev.swapnilraj.calendar.ui.reminders.ReminderSetupScreen
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidReminderSetupTest {
    @get:Rule val compose = createComposeRule()

    @Test fun deniedSetupDoesNotPromptUntilUserClicksAndTestIsDisabled() {
        var calendar = 0
        var notifications = 0
        var alarms = 0
        var tests = 0
        compose.setContent {
            DaylightTheme {
                ReminderSetupScreen(ReminderPermissionState(false, false, true, true, false),
                    { calendar++ }, { notifications++ }, {}, { alarms++ }, { tests++ })
            }
        }
        compose.waitForIdle()
        assertEquals(0, calendar + notifications + alarms + tests)
        compose.onNodeWithText("Allow calendar access").performScrollTo().performClick()
        assertEquals(1, calendar)
        compose.onNodeWithText("Allow notifications").performScrollTo().performClick()
        assertEquals(1, notifications)
        compose.onNodeWithText("Allow precise alarms").performScrollTo().performClick()
        assertEquals(1, alarms)
        compose.onNodeWithText("Send test notification").performScrollTo().assertIsNotEnabled()
        assertEquals(0, tests)
    }

    @Test fun readySetupCanRequestATestButDoesNotClaimDeliveryVerified() {
        var tests = 0
        compose.setContent {
            DaylightTheme {
                ReminderSetupScreen(ReminderPermissionState(true, true, true, true, true),
                    {}, {}, {}, {}, { tests++ })
            }
        }
        compose.onNodeWithText("Reminder permissions are ready").assertIsDisplayed()
        compose.onNodeWithText("Send test notification").performScrollTo().performClick()
        assertEquals(1, tests)
        compose.onNodeWithText("Reminders are working").assertDoesNotExist()
    }

    @Test @Config(qualifiers = "+notnight") fun setupReadyLight() {
        compose.setContent {
            DaylightTheme {
                ReminderSetupScreen(ReminderPermissionState(true, true, true, true, true), {}, {}, {}, {}, {})
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/android_reminder_setup_light.png")
    }
}
