package dev.swapnilraj.calendar.events

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.*
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
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
class SeriesFollowingScreenTest {
    @get:Rule val compose = createComposeRule()
    private val f = SeriesEditorFixture()
    @After fun stop() { f.scope.cancel() }
    private fun show(controller: SeriesEventEditorController) {
        compose.setContent { DaylightTheme { Surface(Modifier.fillMaxSize()) { SeriesEventEditorContent(controller, {}) } } }
    }

    @Test fun backendFollowingCapabilitySelectsActualFollowingScope() {
        var selected: RecurrenceScope? = null
        compose.setContent { DaylightTheme { SeriesScopeContent(f.followingSource(), SeriesEditorAction.EDIT, {}, { selected = it }) } }
        compose.onNodeWithText("This and following occurrences").assertIsEnabled().performClick()
        assertEquals(RecurrenceScope.FOLLOWING, selected)
        assertTrue(f.calls.isEmpty())
    }

    @Test fun temporalAndRepeatControlsAreWiredForFollowing() = runBlocking<Unit> {
        val source = f.followingSource()
        val controller = f.controller(f.prepare(RecurrenceScope.FOLLOWING, source = source), source)
        show(controller)
        compose.onNodeWithText("Selected and following event times").assertExists()
        compose.onNodeWithText("Keep existing end limit").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Daily").performScrollTo().performClick()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        compose.onNodeWithText("Review this and following events").assertIsDisplayed()
        assertTrue(f.calls.isEmpty())
        compose.onNodeWithText("Confirm following changes").performClick()
        assertTrue(f.calls.single().command is SeriesCommand.FollowingEdit)
    }

    @Test fun cancelReviewRetainsRawDraftWithoutProviderMutation() = runBlocking<Unit> {
        val source = f.followingSource()
        val controller = f.controller(f.prepare(RecurrenceScope.FOLLOWING, source = source), source)
        controller.change { it.copy(title = "Pending tail") }; controller.save(); show(controller)
        compose.onNodeWithText("Review this and following events").assertIsDisplayed()
        compose.onNode(hasText("Cancel") and hasAnyAncestor(isDialog())).performClick()
        assertNull(controller.state.followingReview); assertTrue(f.calls.isEmpty())
        assertEquals("Pending tail", SeriesDraftCodec.decode(f.store.records.getValue("series")).fields.title)
    }

    @Test @Config(qualifiers = "+notnight") fun followingReviewLight() = runBlocking<Unit> {
        val source = f.followingSource()
        val controller = f.controller(f.prepare(RecurrenceScope.FOLLOWING, source = source), source)
        controller.change { it.copy(title = "Planning from this week") }; controller.save(); show(controller)
        compose.onNodeWithText("Review this and following events").assertIsDisplayed()
        compose.onNode(isDialog()).captureRoboImage("src/test/screenshots/events_series_following_review_light.png")
        assertTrue(f.calls.isEmpty())
    }
}
