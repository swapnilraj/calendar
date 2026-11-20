package dev.swapnilraj.calendar.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class CalendarContractsTest {
    private val london = ZoneId.of("Europe/London")
    private val date = LocalDate.of(2026, 11, 17)

    @Test fun midnightAndZeroDurationBelongOnlyToTheStartingDate() {
        val midnight = Instant.parse("2026-11-17T00:00:00Z")
        val point = EventTime.Timed(midnight, midnight)
        assertTrue(point.intersects(CalendarDateRange.day(date), ZoneOffset.UTC))
        assertFalse(point.intersects(CalendarDateRange.day(date.minusDays(1)), ZoneOffset.UTC))
        assertFalse(point.intersects(CalendarDateRange.day(date.plusDays(1)), ZoneOffset.UTC))
    }

    @Test fun timedEventsIntersectOnlyTheHalfOpenDateRange() {
        val day = CalendarDateRange.day(date)
        val endsAtStart = EventTime.Timed(
            Instant.parse("2026-11-16T23:00:00Z"), Instant.parse("2026-11-17T00:00:00Z"),
        )
        val beginsAtEnd = EventTime.Timed(
            Instant.parse("2026-11-18T00:00:00Z"), Instant.parse("2026-11-18T01:00:00Z"),
        )
        val spansMidnight = EventTime.Timed(
            Instant.parse("2026-11-16T23:30:00Z"), Instant.parse("2026-11-17T00:30:00Z"),
        )
        assertFalse(endsAtStart.intersects(day, ZoneOffset.UTC))
        assertFalse(beginsAtEnd.intersects(day, ZoneOffset.UTC))
        assertTrue(spansMidnight.intersects(day, ZoneOffset.UTC))
    }

    @Test fun allDayProviderDatesDoNotMoveWithTheDisplayZone() {
        val event = EventTime.AllDay.fromProvider(
            Instant.parse("2026-11-17T00:00:00Z"), Instant.parse("2026-11-19T00:00:00Z"),
        )
        for (zone in listOf(ZoneId.of("America/Los_Angeles"), ZoneId.of("Pacific/Kiritimati"))) {
            assertFalse(event.intersects(CalendarDateRange.day(date.minusDays(1)), zone))
            assertTrue(event.intersects(CalendarDateRange.day(date), zone))
            assertTrue(event.intersects(CalendarDateRange.day(date.plusDays(1)), zone))
            assertFalse(event.intersects(CalendarDateRange.day(date.plusDays(2)), zone))
        }
    }

    @Test fun localDayBoundsUseActualDstLength() {
        fun hours(day: String): Long {
            val bounds = CalendarDateRange.day(LocalDate.parse(day)).instantBounds(london)
            return Duration.between(bounds.start, bounds.endExclusive).toHours()
        }
        assertEquals(23L, hours("2026-03-29"))
        assertEquals(25L, hours("2026-10-25"))
        assertEquals(24L, hours("2026-11-17"))
    }

    @Test fun backwardsWallClockRangeRetainsPositiveElapsedDuration() {
        val event = EventTime.Timed(
            Instant.parse("2026-10-25T00:45:00Z"), Instant.parse("2026-10-25T01:15:00Z"),
        )
        assertTrue(event.start.atZone(london).toLocalTime() > event.end.atZone(london).toLocalTime())
        assertEquals(Duration.ofMinutes(30), event.duration)
        assertTrue(event.intersects(CalendarDateRange.day(LocalDate.of(2026, 10, 25)), london))
    }

    @Test fun fractionalHourTransitionDoesNotAssumeWholeHourDays() {
        val bounds = CalendarDateRange.day(LocalDate.of(2026, 4, 5))
            .instantBounds(ZoneId.of("Australia/Lord_Howe"))
        assertEquals(1470L, Duration.between(bounds.start, bounds.endExclusive).toMinutes())
    }

    @Test fun skippedLocalDateContainsNoTimedInstantsButKeepsItsAllDayDate() {
        val skipped = CalendarDateRange.day(LocalDate.of(2011, 12, 30))
        val zone = ZoneId.of("Pacific/Apia")
        val bounds = skipped.instantBounds(zone)
        assertEquals(bounds.start, bounds.endExclusive)
        assertFalse(EventTime.Timed(bounds.start, bounds.start).intersects(skipped, zone))
        assertFalse(EventTime.Timed(bounds.start.minusSeconds(1), bounds.endExclusive.plusSeconds(1))
            .intersects(skipped, zone))
        assertTrue(EventTime.AllDay(skipped).intersects(skipped, zone))
    }

    @Test fun leapDayAndAdjacentRangesRemainHalfOpen() {
        val range = CalendarDateRange(LocalDate.of(2028, 2, 1), LocalDate.of(2028, 3, 1))
        assertTrue(LocalDate.of(2028, 2, 29) in range)
        assertFalse(LocalDate.of(2028, 3, 1) in range)
        assertFalse(range.intersects(CalendarDateRange.day(LocalDate.of(2028, 3, 1))))
    }

    @Test fun movedExceptionPreservesOriginalRecurrenceIdentity() {
        val original = Instant.parse("2026-11-17T09:00:00Z")
        val recurrence = RecurrenceRef(10, original)
        val ref = EventRef(CalendarId(2), 11, original, original.plusSeconds(3600), recurrence)
        val moved = ref.copy(instanceStart = original.plusSeconds(86400), instanceEnd = original.plusSeconds(90000))
        assertEquals(ref.recurrence, moved.recurrence)
        assertNotEquals(ref, moved)
        assertNotEquals(ref, ref.copy(calendarId = CalendarId(3)))
    }

    @Test fun emptySelectionIsExplicitAndCapabilitiesDefaultToUnknown() {
        assertNotEquals(CalendarSelection.ProviderVisible, CalendarSelection.Explicit(emptySet()))
        val capabilities = CalendarCapabilities()
        assertEquals(AccessDecision.UNKNOWN, capabilities.readDetails)
        assertEquals(AccessDecision.UNKNOWN, capabilities.createEvents)
        assertEquals(AccessDecision.UNKNOWN, capabilities.modifyEvents)
        assertEquals(AccessDecision.UNKNOWN, capabilities.respondToInvitations)
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyDateRangeIsRejected() {
        CalendarDateRange(date, date)
    }

    @Test(expected = IllegalArgumentException::class)
    fun backwardsTimedEventIsRejected() {
        EventTime.Timed(Instant.EPOCH.plusSeconds(1), Instant.EPOCH)
    }
}
