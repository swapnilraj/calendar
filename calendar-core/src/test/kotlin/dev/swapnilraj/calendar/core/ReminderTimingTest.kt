package dev.swapnilraj.calendar.core

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class ReminderTimingTest {
    private val london = ZoneId.of("Europe/London")

    private fun allDay(date: String) = EventTime.AllDay(CalendarDateRange.day(LocalDate.parse(date)))

    @Test fun timedOffsetsUseElapsedMinutesAcrossFallBack() {
        val event = EventTime.Timed(Instant.parse("2026-10-25T01:15:00Z"), Instant.parse("2026-10-25T02:15:00Z"))
        assertEquals(Instant.parse("2026-10-25T00:45:00Z"), dueInstant(event, 30, london))
    }

    @Test fun timedOffsetsIgnoreDeviceZoneAndPreserveNegativeAndZeroValues() {
        val start = Instant.parse("2026-11-17T00:05:00Z")
        val event = EventTime.Timed(start, start.plusSeconds(3600))
        for (zone in listOf(london, ZoneId.of("Asia/Kathmandu"), ZoneId.of("Pacific/Kiritimati"))) {
            assertEquals(Instant.parse("2026-11-16T23:55:00Z"), dueInstant(event, 10, zone))
            assertEquals(start, dueInstant(event, 0, zone))
            assertEquals(Instant.parse("2026-11-17T00:15:00Z"), dueInstant(event, -10, zone))
        }
    }

    @Test fun allDayPreviousMorningUsesDeviceDateRatherThanStorageUtc() {
        val device = ZoneId.of("America/Los_Angeles")
        assertEquals(Instant.parse("2026-11-16T17:00:00Z"), dueInstant(allDay("2026-11-17"), 900, device))
        assertEquals(Instant.parse("2026-11-17T17:00:00Z"), dueInstant(allDay("2026-11-17"), -540, device))
        assertEquals(Instant.parse("2026-11-17T08:00:00Z"), dueInstant(allDay("2026-11-17"), 0, device))
    }

    @Test fun previousMorningAcrossSpringForwardRemainsAtNineLocal() {
        assertEquals(Instant.parse("2026-03-29T08:00:00Z"), dueInstant(allDay("2026-03-30"), 900, london))
    }

    @Test fun previousMorningAcrossFallBackRemainsAtNineLocal() {
        assertEquals(Instant.parse("2026-10-25T09:00:00Z"), dueInstant(allDay("2026-10-26"), 900, london))
    }

    @Test fun localOffsetCrossingSpringTransitionIsNotElapsedTimeSubtraction() {
        // Monday 00:00 minus 24h30 wall-clock is Saturday 23:30, before the transition.
        assertEquals(Instant.parse("2026-03-28T23:30:00Z"), dueInstant(allDay("2026-03-30"), 1470, london))
    }

    @Test fun localOffsetCrossingFallTransitionIsNotElapsedTimeSubtraction() {
        // Saturday 23:30 is still UTC+01:00; subtracting elapsed minutes would be one hour late.
        assertEquals(Instant.parse("2026-10-24T22:30:00Z"), dueInstant(allDay("2026-10-26"), 1470, london))
    }

    @Test fun overlapReminderChoosesEarlierOffset() {
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), dueInstant(allDay("2026-10-25"), -90, london))
    }

    @Test fun gapReminderMovesForwardByGapLength() {
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"), dueInstant(allDay("2026-03-29"), -90, london))
    }

    @Test fun halfHourGapMovesForwardOnlyHalfAnHour() {
        // 02:15 doesn't exist; it becomes 02:45 at UTC+11.
        assertEquals(Instant.parse("2026-10-03T15:45:00Z"),
            dueInstant(allDay("2026-10-04"), -135, ZoneId.of("Australia/Lord_Howe")))
    }

    @Test fun halfHourOverlapChoosesEarlierOffset() {
        assertEquals(Instant.parse("2026-04-04T14:45:00Z"),
            dueInstant(allDay("2026-04-05"), -105, ZoneId.of("Australia/Lord_Howe")))
    }

    @Test fun fractionalHourZoneAndMultipleDayOffsetsArePreserved() {
        val zone = ZoneId.of("Asia/Kathmandu")
        assertEquals(Instant.parse("2026-11-16T03:15:00Z"), dueInstant(allDay("2026-11-17"), 900, zone))
        assertEquals(Instant.parse("2026-11-15T17:45:00Z"), dueInstant(allDay("2026-11-17"), 1470, zone))
    }

    @Test fun allDayUsesOnlyFirstDateAndDoesNotDependOnPresentationZone() {
        val event = EventTime.AllDay(CalendarDateRange(LocalDate.of(2026, 11, 17), LocalDate.of(2026, 11, 20)))
        val deviceZone = ZoneId.of("America/Los_Angeles")
        val expected = Instant.parse("2026-11-16T17:00:00Z")
        // The event is visible on the 17th in both zones; neither is the device reminder zone.
        for (displayZone in listOf(london, ZoneId.of("Pacific/Kiritimati"))) {
            assertTrue(event.intersects(CalendarDateRange.day(LocalDate.of(2026, 11, 17)), displayZone))
            assertEquals(expected, dueInstant(event, 900, deviceZone))
        }
    }

    @Test fun extremeIntOffsetsAreNotNegatedOrTruncated() {
        val event = EventTime.Timed(Instant.EPOCH, Instant.EPOCH)
        assertEquals(Instant.EPOCH.plusSeconds(128849018880L), dueInstant(event, Int.MIN_VALUE, ZoneOffset.UTC))
        assertEquals(Instant.EPOCH.minusSeconds(128849018820L), dueInstant(event, Int.MAX_VALUE, ZoneOffset.UTC))
    }

    @Test(expected = DateTimeException::class)
    fun instantUnderflowIsNotClamped() {
        dueInstant(EventTime.Timed(Instant.MIN, Instant.MIN), 1, ZoneOffset.UTC)
    }

    @Test(expected = DateTimeException::class)
    fun instantOverflowIsNotClamped() {
        dueInstant(EventTime.Timed(Instant.MAX, Instant.MAX), -1, ZoneOffset.UTC)
    }

    @Test(expected = DateTimeException::class)
    fun allDayWallClockUnderflowIsNotClamped() {
        dueInstant(EventTime.AllDay(CalendarDateRange.day(LocalDate.MIN)), 1, ZoneOffset.UTC)
    }
}
