package dev.swapnilraj.calendar

import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.eventCreationDraft
import dev.swapnilraj.calendar.ui.settings.*
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class EventCreationDefaultsTest {
    @Test fun timedDefaultsStayOnBrowsedDateAndUseConfiguredDurationAndReminders() {
        val now = ZonedDateTime.parse("2026-11-17T09:30:05Z[Europe/London]")
        val options = CalendarSettingsOptions(defaultDurationMinutes = 75, timedReminderMinutes = 30)
        val draft = eventCreationDraft(now.toLocalDate(), false, now, options, CalendarId(3))!!
        assertEquals(EventTime.Timed(Instant.parse("2026-11-17T09:45:00Z"), Instant.parse("2026-11-17T11:00:00Z")), draft.time)
        assertEquals(listOf(30), draft.alertMinutes)
        val other = eventCreationDraft(LocalDate.parse("2026-12-02"), false, now, options.copy(timedReminderMinutes = null), null)!!
        assertEquals(Instant.parse("2026-12-02T09:00:00Z"), (other.time as EventTime.Timed).start)
        assertTrue(other.alertMinutes.isEmpty())
    }

    @Test fun aDefaultSlotCrossingSpringGapAdvancesToRealClockTimeWithoutChangingDuration() {
        val now = ZonedDateTime.parse("2026-03-29T00:50:00Z[Europe/London]")
        val draft = eventCreationDraft(now.toLocalDate(), false, now, CalendarSettingsOptions(defaultDurationMinutes = 75), CalendarId(3))!!
        val time = draft.time as EventTime.Timed
        assertEquals(Instant.parse("2026-03-29T01:00:00Z"), time.start)
        assertEquals(2, time.start.atZone(now.zone).hour)
        assertEquals(Duration.ofMinutes(75), Duration.between(time.start, time.end))
    }

    @Test fun defaultDuringRepeatedHourRetainsKnownCurrentOffset() {
        val now = ZonedDateTime.ofLocal(LocalDateTime.parse("2026-10-25T01:10:00"), ZoneId.of("Europe/London"), ZoneOffset.UTC)
        val time = eventCreationDraft(now.toLocalDate(), false, now, CalendarSettingsOptions(), null)!!.time as EventTime.Timed
        assertEquals(Instant.parse("2026-10-25T01:15:00Z"), time.start)
        assertEquals(Duration.ofHours(1), Duration.between(time.start, time.end))
    }

    @Test fun monthAllDayDefaultsKeepUtcDatesAndIndependentAllDayReminder() {
        val date = LocalDate.parse("2026-11-17")
        val draft = eventCreationDraft(date, true, ZonedDateTime.parse("2026-11-17T23:59:00-08:00[America/Los_Angeles]"),
            CalendarSettingsOptions(allDayReminder = AllDayReminderDefault.TWO_DAYS_BEFORE_0900), CalendarId(4))!!
        assertEquals(EventTime.AllDay(CalendarDateRange.day(date)), draft.time)
        assertNull(draft.eventZone)
        assertEquals(listOf(2340), draft.alertMinutes)
    }
}
