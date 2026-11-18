package dev.swapnilraj.calendar.events

import android.provider.CalendarContract
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
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
        compose.onNodeWithText("Edit event").assertDoesNotExist()
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
        compose.onNodeWithText("Edit event").assertDoesNotExist()
    }

    @Test fun editingActionRequiresBothProvenRightsAndAnExplicitCallback() {
        var edited: EventRef? = null
        val value = details().copy(canEdit = AccessDecision.ALLOWED)
        compose.setContent { DaylightTheme {
            Surface(Modifier.fillMaxSize()) { EventDetailsContent(value, {}, {}, zone, onEdit = { edited = it }) }
        } }
        compose.onNodeWithText("Edit event").performScrollTo().performClick()
        assertEquals(value.occurrence.ref, edited)
    }

    @Test fun unknownRightsDoNotExposeProvidedEditCallbacks() {
        compose.setContent { DaylightTheme {
            Surface(Modifier.fillMaxSize()) { EventDetailsContent(details(), {}, {}, zone, onEdit = {}, onDelete = {}) }
        } }
        compose.onNodeWithText("Edit event").assertDoesNotExist()
        compose.onNodeWithText("Delete event").assertDoesNotExist()
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
        val value = details().copy(description = "<script>fixture</script>", guestPermissions = EventGuestPermissions(seeGuests = AccessDecision.ALLOWED), attendees = listOf(
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

    @Test fun defaultVisibilityIsNotMisrepresentedAsPublic() {
        show(details().copy(privacy = CalendarContract.Events.ACCESS_DEFAULT,
            availability = CalendarContract.Events.AVAILABILITY_FREE, status = CalendarContract.Events.STATUS_CONFIRMED,
            isOrganizer = false, selfAttendeeStatus = CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED))
        compose.onNodeWithText("Status: Confirmed").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Availability: Free").assertIsDisplayed()
        compose.onNodeWithText("Visibility: Calendar default").assertIsDisplayed()
        compose.onNodeWithText("Public").assertDoesNotExist()
        compose.onNodeWithText("You are not the organizer").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Your response: Accepted").performScrollTo().assertIsDisplayed()
    }

    @Test fun unknownProviderValuesRemainUnavailableWithoutRawIntegerLeaks() {
        show(details().copy(privacy = 999, availability = 999, status = 999, selfAttendeeStatus = 999))
        compose.onNodeWithText("Visibility: Not provided").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Availability: Not provided").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Status: Not provided").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Your response: Not provided").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("999", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Your response: No response").assertDoesNotExist()
    }

    @Test fun revokingOrLosingGuestPolicyHidesAlreadyLoadedGuestNames() {
        val guest = EventAttendee("private-guest@example.test", "Private guest", CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED,
            CalendarContract.Attendees.RELATIONSHIP_ATTENDEE, CalendarContract.Attendees.TYPE_REQUIRED)
        val state = mutableStateOf(details().copy(isOrganizer = false,
            guestPermissions = EventGuestPermissions(seeGuests = AccessDecision.ALLOWED), attendees = listOf(guest)))
        compose.setContent { DaylightTheme { Surface(Modifier.fillMaxSize()) {
            EventDetailsContent(state.value, {}, {}, zone)
        } } }
        compose.onNodeWithText("Private guest").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(guestPermissions = EventGuestPermissions(seeGuests = AccessDecision.DENIED)) }
        compose.onNodeWithText("Private guest").assertDoesNotExist()
        compose.onNodeWithText("private-guest@example.test").assertDoesNotExist()
        compose.onNodeWithText("Guest list hidden by calendar permissions").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(guestPermissions = EventGuestPermissions()) }
        compose.onNodeWithText("Guest list unavailable").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Private guest").assertDoesNotExist()
        compose.onNodeWithText("No guest details provided").assertDoesNotExist()
    }

    @Test fun provenOrganizerCanReadGuestsDespiteGuestVisibilityRestriction() {
        show(details().copy(isOrganizer = true, guestPermissions = EventGuestPermissions(seeGuests = AccessDecision.DENIED),
            attendees = listOf(EventAttendee("guest@example.test", "Guest", CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED,
                CalendarContract.Attendees.RELATIONSHIP_ATTENDEE, CalendarContract.Attendees.TYPE_REQUIRED))))
        compose.onNodeWithText("You organize this event").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Guest").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Guests may see other guests: No").performScrollTo().assertIsDisplayed()
    }

    @Test fun declaredGuestModifyPermissionDoesNotGrantEffectiveEditRights() {
        val value = details().copy(guestPermissions = EventGuestPermissions(modify = AccessDecision.ALLOWED,
            inviteOthers = AccessDecision.DENIED, seeGuests = AccessDecision.ALLOWED))
        compose.setContent { DaylightTheme { Surface(Modifier.fillMaxSize()) {
            EventDetailsContent(value, {}, {}, zone, onEdit = {}, onDelete = {})
        } } }
        compose.onNodeWithText("Guests may modify the event: Yes").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Guests may invite others: No").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Edit event").assertDoesNotExist()
        compose.onNodeWithText("Delete event").assertDoesNotExist()
    }

    @Test fun cancelledEventHasNoFeaturedJoinOrWriteActionsButRetainsExplicitLinks() {
        val url = "https://meet.google.com/abc-defg-hij"
        var opened: String? = null
        val value = details().copy(status = CalendarContract.Events.STATUS_CANCELED, description = url, canEdit = AccessDecision.ALLOWED)
        compose.setContent { DaylightTheme { Surface(Modifier.fillMaxSize()) {
            EventDetailsContent(value, {}, { opened = it }, zone, onEdit = {}, onDelete = {})
        } } }
        compose.onNodeWithText("Cancelled").assertIsDisplayed()
        compose.onNodeWithText("Join meeting").assertDoesNotExist()
        compose.onNodeWithText("Edit event").assertDoesNotExist()
        compose.onNodeWithText("Delete event").assertDoesNotExist()
        compose.onNode(hasText(url) and hasClickAction()).performScrollTo().performClick()
        assertEquals(url, opened)
    }

    @Test fun googleAccountUsesFriendlyLabelWithoutChangingIdentity() {
        val original = details()
        val google = original.copy(calendar = original.calendar!!.copy(account = CalendarAccount("fixture@example.test", "com.google")))
        show(google)
        compose.onNodeWithText("fixture@example.test · Google account").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("com.google").assertDoesNotExist()
        assertEquals("com.google", google.calendar!!.account.type)
    }

    @Test fun localAccountDoesNotExposeOpaqueBackendName() {
        val original = details()
        show(original.copy(calendar = original.calendar!!.copy(account = CalendarAccount("opaque-backend-name", CalendarContract.ACCOUNT_TYPE_LOCAL))))
        compose.onNodeWithText("On this device").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("opaque-backend-name").assertDoesNotExist()
    }

    @Test @Config(qualifiers = "+notnight") fun detailsContextLight() {
        val base = details()
        val start = Instant.parse("2026-11-17T09:00:00Z"); val end = start.plusSeconds(3600)
        show(base.copy(occurrence = base.occurrence.copy(ref = base.occurrence.ref.copy(instanceStart = start, instanceEnd = end),
            time = EventTime.Timed(start, end)), privacy = CalendarContract.Events.ACCESS_PRIVATE,
            status = CalendarContract.Events.STATUS_CONFIRMED, availability = CalendarContract.Events.AVAILABILITY_BUSY,
            isOrganizer = false, selfAttendeeStatus = CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED,
            guestPermissions = EventGuestPermissions(modify = AccessDecision.DENIED, inviteOthers = AccessDecision.ALLOWED, seeGuests = AccessDecision.DENIED)))
        compose.onRoot().captureRoboImage("src/test/screenshots/events_details_context_light.png")
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
