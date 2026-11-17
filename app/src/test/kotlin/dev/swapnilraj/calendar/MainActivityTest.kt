package dev.swapnilraj.calendar

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainActivityTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun launcherExplainsCalendarPermission() {
        compose.onNodeWithText("Your day, in a new light").assertIsDisplayed()
        compose.onNodeWithText("Allow calendar access").assertIsDisplayed()
    }
}
