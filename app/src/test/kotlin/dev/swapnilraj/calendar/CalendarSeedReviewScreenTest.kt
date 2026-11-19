package dev.swapnilraj.calendar

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import dev.swapnilraj.calendar.android.navigation.*
import dev.swapnilraj.calendar.core.CalendarReadState
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w393dp-h851dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CalendarSeedReviewScreenTest {
    @get:Rule val compose = createComposeRule()
    private fun review(request: CalendarSeedReviewRequest): CalendarSeedReviewResult.Review = runBlocking {
        CalendarSeedReviewServices(SeedReviewStore(), SeedReviewMutations(), SeedReviewRetirement,
            { CalendarReadState.Ready(seedReviewEnvironment) }, MutableSharedFlow()).open(request) as CalendarSeedReviewResult.Review
    }
    @Test fun missingDatesStayBlankInCompletionReview() {
        val state = review(seedReviewRequest())
        assertEquals("", state.snapshot.fields.startTime)
        compose.setContent { DaylightTheme { CalendarSeedReviewScreen(state, false, {}, {}, {}, {}, {}) } }
        compose.onNodeWithTag("seed_start_date").assertTextContains("Start date (YYYY-MM-DD)")
        compose.onNodeWithText("09:00").assertDoesNotExist()
        compose.onRoot().captureRoboImage("src/test/screenshots/incoming_seed_incomplete_light.png")
    }
    @Test fun unsupportedRuleRemainsVisibleForExplicitReview() {
        val state = review(seedReviewRequest(true).let { it.copy(seed = it.seed.copy(recurrenceRule = "FREQ=HOURLY;COUNT=3")) })
        compose.setContent { DaylightTheme { CalendarSeedReviewScreen(state, false, {}, {}, {}, {}, {}) } }
        compose.onNodeWithTag("seed_rule").performScrollTo().assertTextContains("FREQ=HOURLY;COUNT=3")
        compose.onNodeWithText("Remove repeat rule").assertExists()
        compose.onRoot().captureRoboImage("src/test/screenshots/incoming_seed_rule_review_light.png")
    }
}
