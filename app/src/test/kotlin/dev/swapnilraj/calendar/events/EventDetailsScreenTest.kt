package dev.swapnilraj.calendar.events

import android.provider.CalendarContract
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.*
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
import java.time.*
import java.util.Locale
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
class EventDetailsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val zone = ZoneId.of("America/Los_Angeles")
    private fun details(title: String = "Personal planning", allDay: Boolean = true): EventDetails {
        val start = Instant.parse("2026-11-17T00:00:00Z")
        val end = Instant.parse("2026-11-19T00:00:00Z")
        return EventDetails(
            occurrence = EventOccurrence(EventRef(CalendarId(2), 7, start, end),
                if (allDay) EventTime.AllDay.fromProvider(start, end) else EventTime.Timed(start, end), title, 0xff397d70.toInt()),
            calendar = CalendarInfo(CalendarId(2), "Personal", 0xff397d70.toInt(),
                CalendarAccount("fixture@example.test", "Fixture account"), "fixture@example.test", zone, 700, true, true),
        )
    }
    private fun show(value: EventDetails, onLink: (String) -> Unit = {}, onDismiss: () -> Unit = {}) {
        compose.setContent { DaylightTheme {
            Surface(Modifier.fillMaxSize()) { EventDetailsContent(value, onDismiss, onLink, zone) }
        } }
        compose.waitForIdle()
    }

    @Test fun loadingDoesNotPresentStaleEventDetails() {
        compose.setContent { DaylightTheme {
            EventDetailsStateSheet(CalendarReadState.Loading, {}, {}, {}, zone)
        } }
        compose.onNodeWithText("Loading event…").assertIsDisplayed()
        compose.onNodeWithText("Personal planning").assertDoesNotExist()
    }

    @Test fun unavailableStateOffersExplicitRetry() {
        var retried = false
        compose.setContent { DaylightTheme {
            EventDetailsStateSheet(CalendarReadState.Unavailable, {}, { retried = true }, {}, zone)
        } }
        compose.onNodeWithText("Retry").performClick()
        assertTrue(retried)
    }

    @Test fun missingPermissionHasNoUnimplementedGrantOrEditAction() {
        compose.setContent { DaylightTheme {
            EventDetailsStateSheet(CalendarReadState.PermissionRequired, {}, {}, {}, zone)
        } }
        compose.onNodeWithText("Calendar access is needed to read this event.").assertIsDisplayed()
        compose.onNodeWithText("Edit").assertDoesNotExist()
        compose.onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test fun allDayDatesNeverBecomePreviousDayTimes() {
        show(details())
        compose.onNodeWithText("All day").assertIsDisplayed()
        val text = allDayDateText(details().occurrence.time as EventTime.AllDay, Locale.US)
        compose.onNodeWithText(text).assertIsDisplayed()
        assertTrue(text.contains("17"))
        assertTrue(text.contains("18"))
        assertFalse(text.contains("16:00"))
        assertFalse(text.contains("19"))
    }

    @Test fun fullTitleAndBlankTitleRemainReadable() {
        val title = "A complete event title that wraps over several lines without disappearing behind a fixed height"
        show(details(title))
        compose.onNodeWithText(title).assertIsDisplayed()
        compose.onNodeWithText("Edit").assertDoesNotExist()
    }

    @Test fun emptyTitleUsesFallbackAndCloseIsAnAction() {
        var dismissed = false
        show(details("  "), onDismiss = { dismissed = true })
        compose.onNodeWithText("No title").assertIsDisplayed()
        compose.onNodeWithText("Close").performScrollTo().performClick()
        assertTrue(dismissed)
    }

    @Test fun meetingActionOpensExactDeduplicatedLink() {
        var opened: String? = null
        val value = details().copy(description = "Call https://meet.google.com/abc-defg-hij.\nhttps://meet.google.com/abc-defg-hij")
        show(value, onLink = { opened = it })
        compose.onNodeWithText("Join meeting").performScrollTo().performClick()
        assertEquals("https://meet.google.com/abc-defg-hij", opened)
    }

    @Test fun multipleMeetingsAreListedInsteadOfChoosingOne() {
        show(details().copy(description = "https://meet.google.com/abc-defg-hij https://example.zoom.us/j/123"))
        compose.onNodeWithText("Join meeting").assertDoesNotExist()
        compose.onNodeWithText("https://example.zoom.us/j/123").performScrollTo().assertIsDisplayed()
    }

    @Test fun guestsShowResponseLabelsAndNotesArePlainText() {
        val value = details().copy(description = "<script>fixture</script>", attendees = listOf(
            EventAttendee("guest@example.test", "Guest", CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED,
                CalendarContract.Attendees.RELATIONSHIP_ATTENDEE, CalendarContract.Attendees.TYPE_OPTIONAL)))
        show(value)
        compose.onNodeWithText("<script>fixture</script>").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Accepted").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Optional guest").performScrollTo().assertIsDisplayed()
    }

    @Test fun defaultReminderMinutesAreNotMisrepresentedAsAfterStart() {
        show(details().copy(reminders = listOf(EventReminder(CalendarContract.Reminders.MINUTES_DEFAULT,
            CalendarContract.Reminders.METHOD_DEFAULT))))
        compose.onNodeWithText("Default reminder · Calendar default timing").performScrollTo().assertIsDisplayed()
    }

    @Test fun linksRejectScriptSchemesUserInfoAndSpoofedMeetingHosts() {
        assertEquals(listOf("https://example.test/path"), eventWebLinks(
            "javascript:alert(1) https://user:password@example.test https://example.test/path). https://example.test/path"))
        assertFalse(isMeetingLink("https://meet.google.com.evil.test/abc"))
        assertFalse(isMeetingLink("https://evilzoom.us/j/123"))
        assertTrue(isMeetingLink("https://work.zoom.us/j/123"))
    }

    @Test fun fallBackTimeDescriptionContainsBothOffsetsAndRealDuration() {
        val time = EventTime.Timed(Instant.parse("2026-10-25T00:45:00Z"), Instant.parse("2026-10-25T01:15:00Z"))
        val text = timedDateText(time, ZoneId.of("Europe/London"), Locale.US, true)
        assertTrue(text.contains("01:45 GMT+1"))
        assertTrue(text.contains("01:15 GMT"))
        assertEquals(30L, time.duration.toMinutes())
    }

    @Test @Config(qualifiers = "+notnight") fun detailsLight() {
        show(details().copy(location = "Library meeting room", description = "Bring the weekly plan.",
            organizer = "fixture@example.test", reminders = listOf(EventReminder(10, CalendarContract.Reminders.METHOD_ALERT))))
        compose.onRoot().captureRoboImage("src/test/screenshots/events_details_light.png")
    }

    @Test @Config(qualifiers = "+night") fun detailsDark() {
        show(details().copy(location = "Library meeting room", description = "Bring the weekly plan.",
            organizer = "fixture@example.test", reminders = listOf(EventReminder(10, CalendarContract.Reminders.METHOD_ALERT))))
        compose.onRoot().captureRoboImage("src/test/screenshots/events_details_dark.png")
    }
}
