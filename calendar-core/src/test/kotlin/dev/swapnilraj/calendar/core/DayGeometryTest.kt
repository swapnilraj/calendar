package dev.swapnilraj.calendar.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class DayGeometryTest {
    private fun geometry(day: String, zone: String = "Europe/London") =
        DayGeometry(LocalDate.parse(day), ZoneId.of(zone))

    private fun event(start: String, end: String, id: Long = 7) =
        EventRef(CalendarId(2), id, Instant.parse(start), Instant.parse(end))

    private fun assertMinutes(segments: List<TimedSegment>, vararg expected: Pair<Double, Double>) {
        assertEquals(expected.size, segments.size)
        segments.zip(expected.toList()).forEach { (actual, wanted) ->
            assertEquals(wanted.first, actual.startMinute, 0.000001)
            assertEquals(wanted.second, actual.endMinute, 0.000001)
        }
    }

    @Test fun fallBackSplitsAtTheTransitionWithoutCollapsingActualDuration() {
        val day = geometry("2026-10-25")
        val ref = event("2026-10-25T00:45:00Z", "2026-10-25T01:15:00Z")
        val fragments = day.segments(ref)
        assertMinutes(fragments, 105.0 to 120.0, 60.0 to 75.0)
        assertEquals(listOf("UTC+01:00", "UTC+00:00"), fragments.map { it.offsetLabel })
        assertEquals(listOf(Duration.ofMinutes(15), Duration.ofMinutes(15)), fragments.map { it.duration })
        fragments.forEach {
            assertEquals(ref, it.event)
            assertEquals(Duration.ofMinutes(30), it.eventDuration)
        }
        assertNotEquals(fragments[0].key, fragments[1].key)
        assertEquals(fragments.map { it.key }, day.segments(ref).map { it.key })
        val band = day.clockChanges.single()
        assertEquals(ClockChangeKind.OVERLAP, band.kind)
        assertEquals(60.0, band.startMinute, 0.0)
        assertEquals(120.0, band.endMinute, 0.0)
    }

    @Test fun springForwardLeavesTheNonexistentHourEmpty() {
        val day = geometry("2026-03-29")
        val fragments = day.segments(event("2026-03-29T00:30:00Z", "2026-03-29T01:30:00Z"))
        assertMinutes(fragments, 30.0 to 60.0, 120.0 to 150.0)
        assertEquals(Duration.ofHours(1), fragments.fold(Duration.ZERO) { sum, part -> sum + part.duration })
        val band = day.clockChanges.single()
        assertEquals(ClockChangeKind.GAP, band.kind)
        assertEquals(60.0, band.startMinute, 0.0)
        assertEquals(120.0, band.endMinute, 0.0)
    }

    @Test fun transitionEndUsesBeforeOffsetAndDoesNotAddAnEmptyFollowingFragment() {
        val fragment = geometry("2026-10-25").segments(
            event("2026-10-25T00:45:00Z", "2026-10-25T01:00:00Z"),
        ).single()
        assertEquals(120.0, fragment.endMinute, 0.0)
        assertEquals(ZoneOffset.ofHours(1), fragment.offset)
        assertEquals(Duration.ofMinutes(15), fragment.duration)
    }

    @Test fun transitionStartUsesAfterOffset() {
        val fragments = geometry("2026-10-25").segments(
            event("2026-10-25T01:00:00Z", "2026-10-25T01:15:00Z"),
        )
        assertMinutes(fragments, 60.0 to 75.0)
        assertEquals(ZoneOffset.UTC, fragments.single().offset)
    }

    @Test fun ordinaryDayAndEndMidnightRemainInsideTheFixedGrid() {
        val day = geometry("2026-11-17")
        assertTrue(day.clockChanges.isEmpty())
        assertMinutes(day.segments(event("2026-11-17T09:00:00Z", "2026-11-17T10:00:00Z")), 540.0 to 600.0)
        assertMinutes(day.segments(event("2026-11-17T23:30:00Z", "2026-11-18T00:00:00Z")), 1410.0 to 1440.0)
    }

    @Test fun clipsCrossMidnightEventsButKeepsTheirFullDurationAndReference() {
        val ref = event("2026-11-16T23:30:00Z", "2026-11-18T00:30:00Z")
        val fragment = geometry("2026-11-17").segments(ref).single()
        assertEquals(0.0, fragment.startMinute, 0.0)
        assertEquals(1440.0, fragment.endMinute, 0.0)
        assertEquals(Duration.ofHours(24), fragment.duration)
        assertEquals(Duration.ofHours(25), fragment.eventDuration)
        assertEquals(ref, fragment.event)
    }

    @Test fun fullFallBackDayRetainsTwentyFiveHoursAcrossTwoFragments() {
        val day = geometry("2026-10-25")
        val fragments = day.segments(event("2026-10-24T23:00:00Z", "2026-10-26T00:00:00Z"))
        assertMinutes(fragments, 0.0 to 120.0, 60.0 to 1440.0)
        assertEquals(Duration.ofHours(25), fragments.fold(Duration.ZERO) { sum, part -> sum + part.duration })
    }

    @Test fun lordHoweHalfHourOverlapIsNotRoundedToAnHour() {
        val day = geometry("2026-04-05", "Australia/Lord_Howe")
        val fragments = day.segments(event("2026-04-04T14:45:00Z", "2026-04-04T15:15:00Z"))
        assertMinutes(fragments, 105.0 to 120.0, 90.0 to 105.0)
        val band = day.clockChanges.single()
        assertEquals(90.0, band.startMinute, 0.0)
        assertEquals(120.0, band.endMinute, 0.0)
        assertEquals("UTC+11:00", band.offsetBeforeLabel)
        assertEquals("UTC+10:30", band.offsetAfterLabel)
    }

    @Test fun lordHoweHalfHourGapIsNotRoundedToAnHour() {
        val day = geometry("2026-10-04", "Australia/Lord_Howe")
        val fragments = day.segments(event("2026-10-03T15:15:00Z", "2026-10-03T15:45:00Z"))
        assertMinutes(fragments, 105.0 to 120.0, 150.0 to 165.0)
        val band = day.clockChanges.single()
        assertEquals(ClockChangeKind.GAP, band.kind)
        assertEquals(120.0, band.startMinute, 0.0)
        assertEquals(150.0, band.endMinute, 0.0)
    }

    @Test fun skippedDateHasNoTimedSegmentsAndAFullDayGap() {
        val day = geometry("2011-12-30", "Pacific/Apia")
        assertEquals(day.bounds.start, day.bounds.endExclusive)
        assertTrue(day.segments(event("2011-12-29T00:00:00Z", "2012-01-01T00:00:00Z")).isEmpty())
        assertTrue(day.segments(EventRef(CalendarId(2), 1, day.bounds.start, day.bounds.start)).isEmpty())
        val band = day.clockChanges.single()
        assertEquals(ClockChangeKind.GAP, band.kind)
        assertEquals(0.0, band.startMinute, 0.0)
        assertEquals(1440.0, band.endMinute, 0.0)
    }

    @Test fun midnightGapStartsAtTheFirstExistingClockTime() {
        val day = geometry("2018-11-04", "America/Sao_Paulo")
        val ref = EventRef(CalendarId(2), 1, day.bounds.start, day.bounds.start.plusSeconds(1800))
        assertMinutes(day.segments(ref), 60.0 to 90.0)
        val band = day.clockChanges.single()
        assertEquals(0.0, band.startMinute, 0.0)
        assertEquals(60.0, band.endMinute, 0.0)
    }

    @Test fun pointEventsKeepIdentityAndUseTheOffsetAtTheirInstant() {
        val day = geometry("2026-10-25")
        val first = day.segments(event("2026-10-25T00:30:00Z", "2026-10-25T00:30:00Z", 1)).single()
        val second = day.segments(event("2026-10-25T01:30:00Z", "2026-10-25T01:30:00Z", 2)).single()
        assertEquals(90.0, first.startMinute, 0.0)
        assertEquals(first.startMinute, first.endMinute, 0.0)
        assertEquals(90.0, second.startMinute, 0.0)
        assertEquals(Duration.ZERO, first.eventDuration)
        assertNotEquals(first.key, second.key)
        assertNotEquals(first.offset, second.offset)
    }

    @Test fun eventsAtTheExclusiveDayEndAreExcluded() {
        val day = geometry("2026-11-17")
        assertTrue(day.segments(event("2026-11-18T00:00:00Z", "2026-11-18T00:00:00Z")).isEmpty())
        assertTrue(day.segments(event("2026-11-16T23:00:00Z", "2026-11-17T00:00:00Z")).isEmpty())
    }

    @Test fun currentTimePositionsUseActualOffsetsAndExcludeOtherDates() {
        val day = geometry("2026-10-25")
        assertEquals(90.0, day.minuteAt(Instant.parse("2026-10-25T00:30:00Z"))!!, 0.0)
        assertEquals(90.0, day.minuteAt(Instant.parse("2026-10-25T01:30:00Z"))!!, 0.0)
        assertNull(day.minuteAt(day.bounds.endExclusive))
        assertNull(day.minuteAt(day.bounds.start.minusNanos(1)))
    }
}
