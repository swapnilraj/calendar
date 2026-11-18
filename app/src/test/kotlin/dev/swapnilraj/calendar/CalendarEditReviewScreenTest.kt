package dev.swapnilraj.calendar

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import dev.swapnilraj.calendar.android.navigation.*
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = CalendarEditReviewTestApplication::class, qualifiers = "en-rUS-w393dp-h851dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CalendarEditReviewScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun proposedEditsRemainSeparateFromOriginalUntilExplicitApply() {
        val f = EditReviewFixture()
        val result = runBlocking { f.services().open(f.request(f.seed().copy(location = "Proposed room", description = "Proposed notes"))) }
        compose.setContent { DaylightTheme { CalendarEditReviewScreen(result, false, {}, {}, {}, {}) } }
        compose.onNodeWithText("Original synthetic event").assertExists()
        compose.onNodeWithText("Proposed synthetic title").assertExists()
        compose.onNodeWithTag("incoming_edit_apply").assertExists()
        compose.onRoot().captureRoboImage("src/test/screenshots/incoming_edit_review_light.png")
    }
    @Test fun unsupportedGuestsAndRuleAreRetainedVisiblyWithoutOrdinarySaveFallback() {
        val f = EditReviewFixture()
        val result = runBlocking { f.services().open(f.request(f.seed().copy(guests = listOf("invalid guest input"), recurrenceRule = "FREQ=INVALID"))) }
        compose.setContent { DaylightTheme { CalendarEditReviewScreen(result, false, {}, {}, {}, {}) } }
        compose.onNodeWithText("invalid guest input").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("FREQ=INVALID").assertExists()
        compose.onNodeWithTag("incoming_edit_apply").assertDoesNotExist()
        compose.onRoot().captureRoboImage("src/test/screenshots/incoming_edit_unsupported_light.png")
    }
}
