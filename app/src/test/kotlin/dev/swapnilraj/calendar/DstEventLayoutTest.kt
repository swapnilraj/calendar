package dev.swapnilraj.calendar

import dev.swapnilraj.calendar.core.CalendarId
import dev.swapnilraj.calendar.core.EventRef
import dev.swapnilraj.calendar.core.RecurrenceRef
import dev.swapnilraj.calendar.data.CalEvent
import dev.swapnilraj.calendar.ui.*
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class DstEventLayoutTest {
    private fun event(start: String, end: String) = CalEvent(7, Instant.parse(start), Instant.parse(end), "Meeting", 0xff397d70.toInt())
    private fun segments(event: CalEvent, day: String, zone: String = "Europe/London") =
        timedEvents(listOf(event), LocalDate.parse(day), ZoneId.of(zone))

    @Test fun londonFallBackSplitsPositiveDurationAndRetainsClickEvent() {
        val event = event("2026-10-25T00:45:00Z", "2026-10-25T01:15:00Z")
        val parts = segments(event, "2026-10-25")
        assertEquals(listOf(105.0 to 120.0, 60.0 to 75.0), parts.map { it.startMinute to it.endMinute })
        assertTrue(parts.all { it.event === event && it.clockChangeAffected })
        assertTrue(parts.all { it.segment!!.eventDuration == Duration.ofMinutes(30) })
        assertEquals(listOf(0, 1), parts.map { it.partIndex })
        assertTrue(parts.all { it.partCount == 2 })
        assertEquals(2, parts.map { it.key }.distinct().size)
    }

    @Test fun springForwardLeavesMissingHourOutsideBothFragments() {
        val parts = segments(event("2026-03-29T00:30:00Z", "2026-03-29T01:30:00Z"), "2026-03-29")
        assertEquals(listOf(30.0 to 60.0, 120.0 to 150.0), parts.map { it.startMinute to it.endMinute })
        assertTrue(parts.all { it.segment!!.eventDuration == Duration.ofHours(1) })
    }

    @Test fun sameEventFragmentsThatShareClockSpaceOccupyDifferentColumns() {
        val parts = segments(event("2026-10-25T00:15:00Z", "2026-10-25T01:45:00Z"), "2026-10-25")
        val placed = layoutEvents(parts, 20.0)
        assertEquals(2, placed.size)
        assertTrue(placed.all { it.columns == 2 })
        assertEquals(2, placed.map { it.column }.distinct().size)
        assertEquals(parts.map { it.key }.toSet(), placed.map { it.timed.key }.toSet())
    }

    @Test fun lordHoweHalfHourAndMidnightEndpointsRemainExact() {
        val parts = segments(event("2026-04-04T14:45:00Z", "2026-04-04T15:15:00Z"), "2026-04-05", "Australia/Lord_Howe")
        assertEquals(listOf(105.0 to 120.0, 90.0 to 105.0), parts.map { it.startMinute to it.endMinute })
        val midnight = segments(event("2026-11-17T23:30:00Z", "2026-11-18T00:00:00Z"), "2026-11-17").single()
        assertEquals(1440.0, midnight.endMinute, 0.0)
    }

    @Test fun actualProviderReferenceAndMovedExceptionArePreserved() {
        val original = Instant.parse("2026-10-24T09:00:00Z")
        val plain = event("2026-10-25T00:45:00Z", "2026-10-25T01:15:00Z")
        val ref = EventRef(CalendarId(12), plain.id, plain.start, plain.end, RecurrenceRef(3, original))
        val event = plain.copy(ref = ref)
        val parts = segments(event, "2026-10-25")
        assertTrue(parts.all { it.event.ref === ref && it.segment!!.event == ref })
    }

    @Test fun pointEventsStayZeroButMinimumLayoutDoesNotChangeTemporalIdentity() {
        val event = event("2026-11-17T23:59:00Z", "2026-11-17T23:59:00Z")
        val part = segments(event, "2026-11-17").single()
        val visual = layoutEvents(listOf(part), 20.0).single().timed
        assertEquals(1420.0, visual.startMinute, 0.0)
        assertEquals(Duration.ZERO, visual.segment!!.eventDuration)
        assertEquals(part.key, visual.key)
        assertTrue(segments(event.copy(start = Instant.parse("2026-11-18T00:00:00Z"), end = Instant.parse("2026-11-18T00:00:00Z")), "2026-11-17").isEmpty())
    }

    @Test fun transitionEndLabelUsesBeforeOffsetAndIsDirectionIsolated() {
        val part = segments(event("2026-10-25T00:45:00Z", "2026-10-25T01:15:00Z"), "2026-10-25").first()
        val label = eventSegmentTimeLabel(part, true, Locale.UK)
        assertEquals("\u202a01:45 – 02:00 UTC+01:00\u202c", label)
    }

    @Test fun skippedDateProducesNoPhantomTimedEvents() {
        assertTrue(segments(event("2011-12-29T00:00:00Z", "2012-01-01T00:00:00Z"), "2011-12-30", "Pacific/Apia").isEmpty())
    }
}
