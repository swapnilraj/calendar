package dev.swapnilraj.calendar.events

import android.provider.CalendarContract
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.*
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
import java.time.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w393dp-h851dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneEventDetailsTest {
    @get:Rule val compose = createComposeRule()
    private val start = Instant.parse("2028-06-15T09:00:00Z")
    private val ref = EventRef(CalendarId(2), 42, start, start.plusSeconds(3600))
    private val details = EventDetails(EventOccurrence(ref, EventTime.Timed(ref.instanceStart,ref.instanceEnd), "Weekly planning", 0xff7755aa.toInt()), eventTestCalendar(),
        location = "Garden room", description = "Agenda and decisions\nhttps://meet.google.com/abc-defg-hij", organizer = "organizer@example.test", eventZone = ZoneId.of("Europe/London"),
        attendees = listOf(EventAttendee("guest@example.test", "Jamie", CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE, relationship = CalendarContract.Attendees.RELATIONSHIP_ATTENDEE, type = CalendarContract.Attendees.TYPE_REQUIRED)),
        reminders = listOf(EventReminder(10, CalendarContract.Reminders.METHOD_ALERT)), canEdit = AccessDecision.ALLOWED, canRespond = AccessDecision.ALLOWED,
        availability = CalendarContract.Events.AVAILABILITY_BUSY, privacy = CalendarContract.Events.ACCESS_PRIVATE, status = CalendarContract.Events.STATUS_CONFIRMED,
        isOrganizer = false, selfAttendeeStatus = CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED, guestPermissions = EventGuestPermissions(seeGuests = AccessDecision.ALLOWED))
    private var state by mutableStateOf<CalendarReadState<EventDetails>>(CalendarReadState.Ready(details))
    private val edited = mutableListOf<EventRef>(); private val deleted = mutableListOf<EventRef>(); private val copies = mutableListOf<EventDetails>(); private val responses = mutableListOf<EventRef>(); private val links = mutableListOf<String>()
    private fun show(dark: Boolean = false, large: Boolean = false) {
        RuntimeEnvironment.setFontScale(if (large) 2f else 1f)
        compose.setContent { CompositionLocalProvider(LocalLayoutDirection provides if (large) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            DaylightTheme(dark = dark) { PhoneEventDetails(state, {}, {}, { links += it }, ZoneOffset.UTC, true, { edited += it }, { deleted += it }, { copies += it }, { responses += it }) }
        } }
        compose.waitForIdle()
    }
    @After fun reset() { RuntimeEnvironment.setFontScale(1f) }
    @Test fun fixedToolbarRoutesExactEditDeleteAndCopyWithoutWriting() {
        show(); compose.onNodeWithContentDescription("Edit event").performClick(); assertEquals(listOf(ref), edited)
        compose.onNodeWithContentDescription("More event actions").performClick()
        compose.onNodeWithText("Delete event").performClick(); assertEquals(listOf(ref), deleted)
        compose.onNodeWithContentDescription("More event actions").performClick()
        compose.onNodeWithText("Duplicate as draft").performClick(); assertEquals(listOf(details), copies)
    }
    @Test fun joinMapAndResponseDelegateOnlyExactSafeValues() {
        show(); compose.onNodeWithText("Join meeting").performClick()
        assertEquals(listOf("https://meet.google.com/abc-defg-hij"), links)
        compose.onNodeWithText("Garden room").performClick(); assertEquals("geo:0,0?q=Garden+room", links.last())
        compose.onNodeWithText("Respond to invitation").performScrollTo().performClick(); assertEquals(listOf(ref), responses)
    }
    @Test fun organizerFactsNeverEnableUnknownEditOrResponseAuthority() {
        state = CalendarReadState.Ready(details.copy(isOrganizer = true, canEdit = AccessDecision.UNKNOWN, canRespond = AccessDecision.UNKNOWN))
        show(); compose.onNodeWithContentDescription("Edit event").assertDoesNotExist()
        compose.onNodeWithText("Respond to invitation").assertDoesNotExist()
        compose.onNodeWithContentDescription("More event actions").performClick(); compose.onNodeWithText("Delete event").assertDoesNotExist()
        assertTrue(edited.isEmpty()); assertTrue(deleted.isEmpty()); assertTrue(responses.isEmpty())
    }
    @Test fun cancelledEventHasNoJoinEditDeleteOrResponseButCanCopy() {
        state = CalendarReadState.Ready(details.copy(status = CalendarContract.Events.STATUS_CANCELED)); show()
        compose.onNodeWithText("Join meeting").assertDoesNotExist(); compose.onNodeWithContentDescription("Edit event").assertDoesNotExist()
        compose.onNodeWithText("Respond to invitation").assertDoesNotExist()
        compose.onNodeWithContentDescription("More event actions").performClick(); compose.onNodeWithText("Delete event").assertDoesNotExist()
        compose.onNodeWithText("Duplicate as draft").assertExists()
    }
    @Test fun readyPayloadAndEveryActionDisappearOnPermissionLoadingUnavailableOrLimitState() {
        show(); for (replacement in listOf(CalendarReadState.PermissionRequired, CalendarReadState.Loading, CalendarReadState.Unavailable, CalendarReadState.LimitExceeded)) {
            state = CalendarReadState.Ready(details); compose.waitForIdle(); compose.onNodeWithText("Weekly planning").assertExists()
            compose.onNodeWithContentDescription("More event actions").performClick()
            state = replacement; compose.waitForIdle()
            compose.onNodeWithText("Weekly planning").assertDoesNotExist(); compose.onNodeWithText("Join meeting").assertDoesNotExist()
            compose.onNodeWithContentDescription("Edit event").assertDoesNotExist(); compose.onNodeWithContentDescription("More event actions").assertDoesNotExist()
            compose.onNodeWithText("organizer@example.test", substring = true).assertDoesNotExist()
            compose.onNodeWithText("Garden room").assertDoesNotExist(); compose.onNodeWithText("Personal").assertDoesNotExist()
            compose.onNodeWithText("Respond to invitation").assertDoesNotExist(); compose.onNodeWithText("Duplicate as draft").assertDoesNotExist()
        }
    }
    @Test fun hiddenGuestsStayUnavailableAndNeverLeakProviderRowsOrRawEnums() {
        state = CalendarReadState.Ready(details.copy(guestPermissions = EventGuestPermissions(), isOrganizer = null, status = 819, availability = 818, privacy = 817))
        show(); compose.onNodeWithText("Guest list unavailable").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Jamie").assertDoesNotExist(); compose.onNodeWithText("guest@example.test").assertDoesNotExist()
        compose.onNodeWithText("More details").performScrollTo().performClick()
        for (raw in listOf("819","818","817")) compose.onAllNodesWithText(raw, substring = true).assertCountEquals(0)
        compose.onNodeWithText("Fewer details").assertExists()
    }
    @Test fun allDayDatesUseUtcDatesAndLongTitleIsComplete() {
        val long = "A complete title " + "that remains readable ".repeat(12)
        state = CalendarReadState.Ready(details.copy(occurrence = details.occurrence.copy(title = long, time = EventTime.AllDay(CalendarDateRange(LocalDate.parse("2028-06-15"),LocalDate.parse("2028-06-18"))))))
        show(); compose.onNodeWithText(long).assertExists()
        compose.onNodeWithText("Jun 15, 2028 – Jun 17, 2028").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("All day").assertExists(); compose.onNodeWithContentDescription("Edit event").assertIsDisplayed()
    }
    @Test fun changedOccurrenceClosesOldMenuAndRoutesOnlyNewExactRef() {
        show(); compose.onNodeWithContentDescription("More event actions").performClick()
        val moved = ref.copy(eventId = 99, instanceStart = ref.instanceStart.plusSeconds(3600), instanceEnd = ref.instanceEnd.plusSeconds(3600))
        state = CalendarReadState.Ready(details.copy(occurrence = details.occurrence.copy(ref = moved))); compose.waitForIdle()
        compose.onNodeWithText("Delete event").assertDoesNotExist()
        compose.onNodeWithContentDescription("Edit event").performClick(); assertEquals(listOf(moved), edited)
    }
    @Test fun detailsPhoneLight() { show(); compose.onNode(isDialog()).captureRoboImage("src/test/screenshots/phone_event_details_light.png") }
    @Test fun detailsPhoneDark() { show(dark = true); compose.onNode(isDialog()).captureRoboImage("src/test/screenshots/phone_event_details_dark.png") }
    @Test fun detailsLargeRtlKeepsToolbarReachable() { show(large = true); compose.onNodeWithContentDescription("Edit event").assertIsDisplayed(); compose.onNodeWithContentDescription("Close").assertIsDisplayed(); compose.onNode(isDialog()).captureRoboImage("src/test/screenshots/phone_event_details_rtl_large.png") }
}
