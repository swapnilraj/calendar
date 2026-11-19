package dev.swapnilraj.calendar.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class CalendarInvitationTest {
    @Test fun pendingRequiresProvenNonOrganizerAndExplicitInvitedStatus() {
        assertEquals(InvitationState.PENDING, classifyInvitation(false, 3L))
        assertEquals(InvitationState.UNKNOWN, classifyInvitation(true, 3L))
        assertEquals(InvitationState.UNKNOWN, classifyInvitation(null, 3L))
        assertEquals(InvitationState.UNKNOWN, classifyInvitation(false, 0L))
    }

    @Test fun answeredStatusesAreNotPendingForEitherKnownRole() {
        for (status in listOf(1L, 2L, 4L)) {
            assertEquals(InvitationState.NOT_PENDING, classifyInvitation(false, status))
            assertEquals(InvitationState.NOT_PENDING, classifyInvitation(true, status))
        }
    }

    @Test fun organizerWithNoResponseIsNotAnIncomingInvitation() {
        assertEquals(InvitationState.NOT_PENDING, classifyInvitation(true, 0L))
        assertEquals(InvitationState.UNKNOWN, classifyInvitation(false, 0L))
        assertEquals(InvitationState.UNKNOWN, classifyInvitation(null, 0L))
    }

    @Test fun missingRoleOrResponseRemainsUnknown() {
        for (status in 0L..4L) assertEquals(InvitationState.UNKNOWN, classifyInvitation(null, status))
        for (role in listOf(true, false, null)) assertEquals(InvitationState.UNKNOWN, classifyInvitation(role, null))
    }

    @Test fun invalidRawLongEvidenceCannotWrapIntoInvitedOrDeclined() {
        val wrappedInvited = 4_294_967_299L
        val wrappedDeclined = 4_294_967_298L
        assertEquals(3, wrappedInvited.toInt())
        assertEquals(2, wrappedDeclined.toInt())
        for (status in listOf(Long.MIN_VALUE, -1L, 5L, Int.MAX_VALUE.toLong(), wrappedInvited, wrappedDeclined, Long.MAX_VALUE)) {
            for (role in listOf(true, false, null)) assertEquals(InvitationState.UNKNOWN, classifyInvitation(role, status))
        }
    }

    @Test fun existingPositionalCallsKeepAllFilterAndUnknownOccurrenceDefaults() {
        val start = Instant.parse("2030-01-03T09:00:00Z")
        val ref = EventRef(CalendarId(2), 7, start, start.plusSeconds(3600))
        val occurrence = EventOccurrence(ref, EventTime.Timed(ref.instanceStart, ref.instanceEnd), "Synthetic event", 123, false)
        val selection = CalendarSelection.Explicit(setOf(ref.calendarId))
        val request = CalendarRequest(CalendarDateRange.day(LocalDate.parse("2030-01-03")), ZoneOffset.UTC, selection)
        assertEquals(InvitationState.UNKNOWN, occurrence.invitation)
        assertEquals(InvitationFilter.ALL, request.invitationFilter)
        assertEquals(selection, request.selection)
        assertEquals(InvitationFilter.ALL, CalendarSearchRequest("Synthetic", request, 20, true).calendar.invitationFilter)
    }
}
