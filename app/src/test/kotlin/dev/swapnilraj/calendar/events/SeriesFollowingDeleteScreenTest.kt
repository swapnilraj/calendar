package dev.swapnilraj.calendar.events

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.*
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
import java.time.*
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
class SeriesFollowingDeleteScreenTest {
    @get:Rule val compose = createComposeRule()
    private val f = SeriesEditorFixture()
    @After fun stop() { f.scope.cancel() }
    private fun displayedText(prefix: String): String = compose.onNodeWithText(prefix, substring = true).assertIsDisplayed()
        .fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }.replace('\u202f', ' ').replace('\u00a0', ' ')
    @Test fun deleteScopeChooserOnlyEnablesFollowingWhenIssuedAndDoesNotMutate() {
        var selected: RecurrenceScope? = null
        compose.setContent { DaylightTheme { SeriesScopeContent(f.followingSource(), SeriesEditorAction.DELETE, {}, { selected = it }) } }
        assertNull(selected); compose.onNodeWithText("This and following occurrences").assertIsEnabled().performClick()
        assertEquals(RecurrenceScope.FOLLOWING, selected); assertTrue(f.calls.isEmpty())
    }
    @Test fun movedOccurrenceConfirmationShowsOriginalMasterZoneCutoffExplicitly() = runBlocking<Unit> {
        val ready = f.followingSource(); val source = ready.copy(snapshot = ready.snapshot.copy(start = RecurrenceStart.Timed(
            LocalDateTime.parse("2028-06-05T05:00:00"), ZoneId.of("America/New_York"), ZoneOffset.ofHours(-4))))
        val launch = f.prepare(RecurrenceScope.FOLLOWING, SeriesEditorAction.DELETE, source)
        val controller = f.controller(launch, source)
        compose.setContent { DaylightTheme { Surface(Modifier.fillMaxSize()) { SeriesEventEditorContent(controller, {}, {}) } } }
        assertEquals("Original recurrence cutoff: Jun 19, 2028, 5:00:00 AM (Eastern Daylight Time)", displayedText("Original recurrence cutoff:"))
        compose.onAllNodesWithText("Jun 20", substring = true).assertCountEquals(0)
        compose.onNodeWithText("The event information is read-only context for this deletion.").assertIsDisplayed()
        compose.onAllNodesWithText("They remain unchanged.", substring = true).assertCountEquals(0)
        compose.onRoot().captureRoboImage("src/test/screenshots/events_series_following_delete_light.png")
        compose.onAllNodesWithText("Delete recurring event").filter(hasClickAction()).onFirst().performScrollTo().performClick()
        assertEquals("Delete the occurrence originally scheduled for Jun 19, 2028, 5:00:00 AM (Eastern Daylight Time) and all following occurrences, including their exceptions? The original recurrence schedule determines the cutoff, even when an occurrence has moved. Earlier occurrences are not deleted. This cannot be undone here.", displayedText("Delete the occurrence originally scheduled for"))
        assertTrue(f.calls.isEmpty()); f.next = SeriesMutationResult.LocalDeleted(f.identity, RecurrenceScope.FOLLOWING, f.selected)
        compose.onNodeWithText("Delete").performClick(); assertEquals(SeriesCommand.Delete(RecurrenceScope.FOLLOWING), f.calls.single().command)
    }
    @Test fun allDayConfirmationUsesOriginalDateWithoutTimedOrActualDateFallback() = runBlocking<Unit> {
        val source = f.allDayFollowingSource(); val controller = f.controller(f.prepare(RecurrenceScope.FOLLOWING, SeriesEditorAction.DELETE, source), source)
        compose.setContent { DaylightTheme { Surface(Modifier.fillMaxSize()) { SeriesEventEditorContent(controller, {}, {}) } } }
        compose.onNodeWithText("Original recurrence cutoff: Jun 19, 2028").assertIsDisplayed()
        compose.onAllNodesWithText("Jun 20", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Delete recurring event").filter(hasClickAction()).onFirst().performScrollTo().performClick()
        compose.onNodeWithText("Delete the occurrence originally scheduled for Jun 19, 2028 and all following occurrences", substring = true).assertIsDisplayed()
        assertTrue(f.calls.isEmpty())
    }
}
