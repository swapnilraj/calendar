package dev.swapnilraj.calendar

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainActivityScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    @Config(qualifiers = "+notnight")
    fun emptyScreenLight() {
        compose.onRoot().captureRoboImage("src/test/screenshots/empty_screen_light.png")
    }

    @Test
    @Config(qualifiers = "+night")
    fun emptyScreenDark() {
        compose.onRoot().captureRoboImage("src/test/screenshots/empty_screen_dark.png")
    }
}
