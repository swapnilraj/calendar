package dev.swapnilraj.calendar

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.events.*
import dev.swapnilraj.calendar.ui.EventWriteRequest
import dev.swapnilraj.calendar.ui.EventWriteWorkflow
import dev.swapnilraj.calendar.ui.events.*
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
import java.time.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The production durable-draft workflow and phone UI, with an explicit synthetic mutation port.
 * These checks do not establish native provider writes, sync or reference-app parity. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w393dp-h851dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneRecurringCreateWorkflowTest {
    @get:Rule val compose = createComposeRule()
    private val store = EventEditorControllerTest.Store()
    private val writer = EventEditorControllerTest().Writer()
    private val initial = EventDraft(CalendarId(2), EventTime.Timed(Instant.parse("2028-06-15T09:00:00Z"), Instant.parse("2028-06-15T10:00:00Z")),
        ZoneOffset.UTC, "Phone recurring fixture", targetIdentity = eventTestIdentity())
    private val clock = Clock.fixed(Instant.parse("2026-11-18T00:00:00Z"), ZoneOffset.UTC)

    private fun show(enabled: Boolean? = null, use24Hour: Boolean = true) {
        val record = EventEditorController.initialRecord(initial, null, "phone-recurring", true, clock)
        store.records[record.id] = record
        val launch = requireNotNull(DraftBrowserCodec.read(record).launch)
        val request = EventWriteRequest.fromLaunch(launch, LocalDate.parse("2028-06-15"), ZoneOffset.UTC)
        compose.setContent { DaylightTheme {
            if (enabled == null) EventWriteWorkflow(request, writer, store, listOf(eventTestCalendar()), use24Hour, {}, {}, {}, clock = clock, requestWritePermission = { true })
            else EventWriteWorkflow(request, writer, store, listOf(eventTestCalendar()), use24Hour, {}, {}, {}, clock = clock,
                recurringCreationSupported = enabled, requestWritePermission = { true })
        } }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Title").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun daily() {
        compose.onNodeWithText("Does not repeat").performScrollTo().performClick()
        compose.onNodeWithText("Daily").performClick()
        compose.onNodeWithText("Done").performClick()
        assertEquals(0, writer.creates)
    }

    @Test fun workflowDefaultDoesNotExposeRecurringCreation() {
        show()
        compose.onNodeWithText("Does not repeat").assertDoesNotExist()
        compose.onNodeWithText("Save").assertIsDisplayed()
        assertEquals(0, writer.creates)
    }

    @Test fun enabledPhoneWorkflowReviewsBeforeExactlyOneTypedCreate() {
        show(true); daily()
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Review recurring event").assertIsDisplayed()
        compose.onNodeWithText("Every 1 day").assertExists()
        assertEquals(0, writer.creates)
        compose.onNode(isDialog() and hasAnyDescendant(hasText("Review recurring event")))
            .captureRoboImage("src/test/screenshots/phone_recurring_create_review_light.png")
        compose.onNodeWithText("Create recurring event").performClick()
        compose.waitUntil(10_000) { writer.creates == 1 && store.records.isEmpty() }
        assertEquals(RecurrenceSpec(RecurrencePattern.Daily), writer.createdDrafts.single().recurrence)
        assertEquals(initial.time, writer.createdDrafts.single().time)
        compose.onNodeWithText("Create recurring event").assertDoesNotExist()
        assertEquals(1, writer.creates)
    }

    @Test fun phoneTwelveHourPreferenceAppliesToBoundsAndPreviewWithoutWriting() {
        show(true, use24Hour = false); daily()
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Review recurring event").assertIsDisplayed()
        // One bounds summary and every preview occurrence use the caller's clock convention.
        val inReview = hasAnyAncestor(isDialog() and hasAnyDescendant(hasText("Review recurring event")))
        assertEquals(6, compose.onAllNodes(hasText("AM", substring = true) and inReview).fetchSemanticsNodes().size)
        compose.onAllNodesWithText("09:00", substring = true).assertCountEquals(0)
        assertEquals(0, writer.creates)
    }

    @Test fun editAfterDismissingReviewRequiresFreshExplicitReview() {
        writer.next = MutationResult.Uncertain("fixture", null)
        show(true); daily()
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithContentDescription("Title").performTextReplacement("Changed recurring fixture")
        assertEquals(0, writer.creates)
        compose.onNodeWithText("Create recurring event").assertDoesNotExist()
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Review recurring event").assertIsDisplayed()
        compose.onNode(isDialog() and hasAnyDescendant(hasText("Review recurring event")) and
            hasAnyDescendant(hasText("Changed recurring fixture"))).assertExists()
        assertEquals(0, writer.creates)
        compose.onNodeWithText("Create recurring event").performClick()
        compose.waitUntil(10_000) { writer.creates == 1 }
        assertEquals("Changed recurring fixture", writer.createdDrafts.single().title)
        assertEquals(RecurrenceSpec(RecurrencePattern.Daily), writer.createdDrafts.single().recurrence)
    }
}
