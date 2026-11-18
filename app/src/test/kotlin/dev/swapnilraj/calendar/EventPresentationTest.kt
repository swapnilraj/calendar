package dev.swapnilraj.calendar

import dev.swapnilraj.calendar.data.CalEvent
import dev.swapnilraj.calendar.ui.eventTimeLabel
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EventPresentationTest {
    @Test fun allDayDatesStayOnTheirIntendedDateWithoutStorageTimes() {
        val event = CalEvent(1, Instant.parse("2026-11-17T00:00:00Z"), Instant.parse("2026-11-19T00:00:00Z"), "Holiday", 0, allDay = true)
        val value = eventTimeLabel(event, ZoneId.of("America/Los_Angeles"), true, Locale.US)
        assertTrue(value.contains("Nov 17, 2026"))
        assertTrue(value.contains("Nov 18, 2026"))
        assertFalse(value.contains("16:00"))
        assertFalse(value.contains("Nov 19"))
    }

    @Test fun backwardsClockRangeIncludesBothOffsets() {
        val event = CalEvent(1, Instant.parse("2026-10-25T00:45:00Z"), Instant.parse("2026-10-25T01:15:00Z"), "Meeting", 0)
        val value = eventTimeLabel(event, ZoneId.of("Europe/London"), true, Locale.UK)
        assertTrue(value.contains("01:45 UTC+01:00"))
        assertTrue(value.contains("01:15 UTC+00:00"))
    }

    @Test fun rtlUsesAnIsolatedChronologicalTimeRange() {
        val event = CalEvent(1, Instant.parse("2026-11-17T09:00:00Z"), Instant.parse("2026-11-17T10:00:00Z"), "Meeting", 0)
        val value = eventTimeLabel(event, ZoneId.of("UTC"), true, Locale.forLanguageTag("ar"))
        assertTrue(value.indexOf("09:00") < value.indexOf("10:00"))
        assertTrue(value.contains('\u202a') || value.contains('\u200e'))
    }
}
