package dev.swapnilraj.calendar.events

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.swapnilraj.calendar.core.*
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w393dp-h851dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EventDetailsLimitTest {
    @get:Rule val compose = createComposeRule()
    @Test fun oversizedDetailsOfferOnlyWiredRecoveryAndCloseActions() {
        var retried = 0; var closed = 0; var external = 0; var edits = 0; var deletes = 0; var copies = 0
        compose.setContent { DaylightTheme { EventDetailsStateSheet(CalendarReadState.LimitExceeded,
            { closed++ }, { retried++ }, { external++ }, ZoneOffset.UTC,
            onEdit = { edits++ }, onDelete = { deletes++ }, onDuplicate = { copies++ }) } }
        compose.onNodeWithText("Event is too large to open here").assertIsDisplayed()
        compose.onNodeWithText("View this event manually in the app that provides your calendar.").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick(); assertEquals(1, retried)
        compose.onNodeWithText("Close").performClick(); assertEquals(1, closed)
        assertEquals(0, external); assertEquals(0, edits); assertEquals(0, deletes); assertEquals(0, copies)
        compose.onNodeWithText("Open calendar app").assertDoesNotExist()
    }
    @Test fun readyToOversizedTransitionRemovesOldPrivatePayloadAndActions() {
        val start = Instant.parse("2028-06-15T09:00:00Z")
        val details = EventDetails(EventOccurrence(EventRef(CalendarId(2), 42, start, start.plusSeconds(3600)),
            EventTime.Timed(start, start.plusSeconds(3600)), "Synthetic private appointment", 1), eventTestCalendar(),
            description = "Synthetic confidential notes https://meet.google.com/fixture-meeting", eventZone = ZoneOffset.UTC,
            attendees = listOf(EventAttendee("private-fixture@example.test", "Synthetic attendee", 1, 1, 1)), canEdit = AccessDecision.ALLOWED)
        val state = mutableStateOf<CalendarReadState<EventDetails>>(CalendarReadState.Ready(details))
        var actions = 0
        compose.setContent { DaylightTheme { EventDetailsStateSheet(state.value, {}, {}, { actions++ }, ZoneOffset.UTC,
            onEdit = { actions++ }, onDelete = { actions++ }, onDuplicate = { actions++ }) } }
        compose.onNodeWithText("Synthetic private appointment").assertIsDisplayed()
        compose.onNodeWithText("Synthetic confidential notes", substring = true).assertExists()
        compose.runOnIdle { state.value = CalendarReadState.LimitExceeded }; compose.waitForIdle()
        compose.onNodeWithText("Event is too large to open here").assertIsDisplayed()
        for (privateText in listOf("Synthetic private appointment", "Synthetic confidential notes", "private-fixture@example.test", "Synthetic attendee", "fixture-meeting"))
            compose.onAllNodesWithText(privateText, substring = true).assertCountEquals(0)
        compose.onNodeWithText("Edit event").assertDoesNotExist(); compose.onNodeWithText("Delete event").assertDoesNotExist()
        compose.onNodeWithText("Duplicate event").assertDoesNotExist(); compose.onAllNodesWithText("Join", substring = true).assertCountEquals(0)
        assertEquals(0, actions)
    }
}
