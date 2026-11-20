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
class SeriesTemporalScreenTest {
    @get:Rule val compose = createComposeRule()
    private val f = SeriesEditorFixture()
    @After fun stop() { f.scope.cancel() }
    private fun show(controller: SeriesEventEditorController) {
        compose.setContent { DaylightTheme { Surface(Modifier.fillMaxSize()) { SeriesEventEditorContent(controller, {}, {}) } } }
    }
    @Test fun nativeRuleEditRequiresWholeReviewAndFreezesOneExactCommand() = runBlocking<Unit> {
        val source = f.temporalSource(); val controller = f.controller(f.prepare(source = source), source)
        f.next = SeriesMutationResult.Common(MutationResult.Uncertain("original", null)); show(controller)
        compose.onNodeWithText("Keep existing end limit").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Daily").performScrollTo().performClick()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        compose.onNodeWithText("Review entire-series changes").assertIsDisplayed()
        compose.onNodeWithText("Original master interval").assertIsDisplayed()
        assertTrue(f.calls.isEmpty())
        compose.onNodeWithText("Save entire-series changes").performClick(); compose.waitForIdle()
        val command = f.calls.single().command as SeriesCommand.WholeEdit
        assertEquals(FieldChange.Set(RecurrencePattern.Daily), command.intent.rule.pattern)
        assertEquals(FieldChange.Keep, command.intent.rule.end); assertFalse(controller.canChange)
    }
    @Test fun unsupportedScopeDoesNotExposeTemporalOrRepeatControls() = runBlocking<Unit> {
        val controller = f.controller(f.prepare()); show(controller)
        compose.onNodeWithText("Master start and duration").assertDoesNotExist()
        compose.onNodeWithText("Keep existing end limit").assertDoesNotExist()
        compose.onNodeWithText("Daily").assertDoesNotExist()
        compose.onNodeWithText("Time, time zone, and recurrence changes are not available here yet. They remain unchanged.").assertIsDisplayed()
    }
    @Test fun nativeReviewDismissDoesNotSaveAndRawFieldsRemainRecoverable() = runBlocking<Unit> {
        val source = f.temporalSource(); val controller = f.controller(f.prepare(source = source), source)
        controller.change { it.copy(temporal = it.temporal!!.copy(startDate = "2028-06-12")) }; controller.save(); show(controller)
        compose.onNodeWithText("Review entire-series changes").assertIsDisplayed()
        compose.onNode(hasText("Cancel") and hasAnyAncestor(isDialog())).performClick(); compose.waitForIdle()
        assertNull(controller.state.wholeReview); assertTrue(f.calls.isEmpty())
        val restored = f.controller(SeriesDraftCodec.describe(f.store.records.getValue("series"))!!, source)
        assertEquals("2028-06-12", restored.state.fields.temporal!!.startDate)
    }
    @Test fun wholeSeriesReviewShowsExactMasterDatesAndRetainedCount() = runBlocking<Unit> {
        val source = f.temporalSource(); val controller = f.controller(f.prepare(source = source), source)
        controller.change { it.copy(title = "Weekly planning", temporal = it.temporal!!.copy(startDate = "2028-06-12")) }
        controller.changeRule(controller.state.fields.recurrence!!.values.copy(frequency = "DAILY")); controller.save(); show(controller)
        compose.onNodeWithText("Review entire-series changes").assertIsDisplayed()
        compose.onNodeWithText("Original master interval").assertIsDisplayed()
        compose.onNodeWithText("Proposed master interval").assertIsDisplayed()
        compose.onNode(isDialog()).captureRoboImage("src/test/screenshots/events_series_temporal_review_light.png")
        assertEquals(RecurrenceEnd.Count(8), controller.state.wholeReview!!.recurrence.end)
        assertTrue(f.calls.isEmpty())
    }
    @Test fun absentReplacementHandlerNeverExposesADiscardAction() = runBlocking<Unit> {
        val source = f.temporalSource(); val launch = f.prepare(source = source)
        val controller = f.controller(launch, source.copy(token = EditToken("changed-token")))
        compose.setContent { DaylightTheme { Surface(Modifier.fillMaxSize()) { SeriesEventEditorContent(controller, {}, onReview = null) } } }
        assertTrue(controller.state.reviewRequired)
        compose.onNodeWithText("Discard draft and load latest").assertDoesNotExist()
        assertTrue(f.calls.isEmpty())
    }

}
