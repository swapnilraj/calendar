package dev.swapnilraj.calendar.events

import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.recurrenceSummary
import java.time.*
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecurrenceSummaryTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val utc = ZoneId.of("UTC")
    private fun details(rule: String? = "FREQ=DAILY", original: String = "2028-06-05T09:00:00Z", actual: String = original,
        zone: ZoneId? = utc, allDay: Boolean = false, series: Boolean = true): EventDetails {
        val start = Instant.parse(actual); val source = Instant.parse(original)
        val time = if (allDay) EventTime.AllDay(CalendarDateRange(start.atOffset(ZoneOffset.UTC).toLocalDate(), start.atOffset(ZoneOffset.UTC).toLocalDate().plusDays(1)))
            else EventTime.Timed(start, start.plusSeconds(3600))
        val ref = EventRef(CalendarId(2), 42, start, start.plusSeconds(3600), if (series) RecurrenceRef(41, source) else null)
        return EventDetails(EventOccurrence(ref, time, "Fixture", 1), null, eventZone = zone, recurrenceRule = rule)
    }
    private fun summary(details: EventDetails, zone: ZoneId = utc, locale: Locale = Locale.US) = recurrenceSummary(context, details, zone, locale)
    @Test fun dailyIntervalsAndTotalCountAreHumanReadable() {
        assertEquals("Every day; for 3 occurrences", summary(details("FREQ=DAILY;COUNT=3")))
        assertEquals("Every 2 days", summary(details("FREQ=DAILY;INTERVAL=2")))
        assertEquals("Every day; for 1 occurrence", summary(details("FREQ=DAILY;COUNT=1")))
    }
    @Test fun weeklyDaysAndImplicitWeekdayUseOriginalRatherThanMovedActualStart() {
        val moved = details("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE", actual = "2028-06-08T09:00:00Z")
        assertEquals("Every 2 weeks on Monday and Wednesday", summary(moved))
        assertEquals("Every week on Monday", summary(moved.copy(recurrenceRule = "FREQ=WEEKLY")))
        assertFalse(summary(moved)!!.contains("Thursday"))
    }
    @Test fun timedUnknownZoneNeverGuessesEvenForExplicitWeekdays() {
        assertEquals("Custom recurrence", summary(details("FREQ=WEEKLY;BYDAY=MO", zone = null)))
        assertEquals("Custom recurrence", summary(details("FREQ=DAILY", zone = null), ZoneId.of("Asia/Tokyo")))
    }
    @Test fun allDayUsesOriginalUtcDateRegardlessOfMovedDateDisplayZoneAndMissingEventZone() {
        val source = details("FREQ=WEEKLY", original = "2028-06-05T00:00:00Z", actual = "2028-06-08T00:00:00Z", allDay = true, zone = null)
        assertEquals("Every week on Monday", summary(source, ZoneId.of("Pacific/Honolulu")))
    }
    @Test fun monthlyDateOrdinalAndNegativeOrdinalUseFriendlyLabels() {
        assertEquals("Every month on day 31", summary(details("FREQ=MONTHLY;BYMONTHDAY=31", "2028-01-31T09:00:00Z")))
        assertEquals("Every 3 months on the second Monday", summary(details("FREQ=MONTHLY;INTERVAL=3;BYDAY=2MO", "2028-06-12T09:00:00Z")))
        assertEquals("Every month on the last Monday", summary(details("FREQ=MONTHLY;BYDAY=-1MO", "2028-06-26T09:00:00Z")))
        assertEquals("Every month on the second to last Monday", summary(details("FREQ=MONTHLY;BYDAY=-2MO", "2028-06-19T09:00:00Z")))
    }
    @Test fun yearlyMonthDayIncludesLeapDayWithoutInventedYear() {
        val result = summary(details("FREQ=YEARLY;INTERVAL=2;BYMONTH=2;BYMONTHDAY=29", "2028-02-29T09:00:00Z"))!!
        assertEquals("Every 2 years on February 29", result)
        assertFalse(result.contains("2000")); assertFalse(result.contains("2028"))
    }
    @Test fun allDayFiniteDateIsLocalizedAndNeverShiftedByDisplayZone() {
        val source = details("FREQ=DAILY;UNTIL=20280630", original = "2028-06-05T00:00:00Z", allDay = true)
        assertEquals("Every day; through Jun 30, 2028", summary(source, ZoneId.of("Pacific/Honolulu")))
    }
    @Test fun timedThroughDateNamesEventZoneRatherThanPretendingDisplayZoneDate() {
        assertEquals("Every day; through Jun 30, 2028 (UTC)", summary(details("FREQ=DAILY;UNTIL=20280630T235959Z"), ZoneId.of("America/New_York")))
    }
    @Test fun exactUntilShowsSecondsAndActualDisplayZoneWithoutWideningBoundary() {
        val result = summary(details("FREQ=DAILY;UNTIL=20280630T003034Z"), ZoneId.of("America/New_York"))!!
        assertTrue(result, result.contains("Jun 29, 2028")); assertTrue(result, result.contains("8:30:34"))
        assertTrue(result, result.contains("Eastern Daylight Time")); assertTrue(result.startsWith("Every day; until"))
        assertFalse(result.contains("through")); assertFalse(result.contains("UNTIL"))
    }
    @Test fun invalidOrUnsupportedSyntaxUsesSafeSummaryAndPreservesRawRule() {
        for (rule in listOf("FREQ=HOURLY", "FREQ=MONTHLY;BYDAY=MO,TU;BYSETPOS=1", "FREQ=DAILY;COUNT=oops", "SECRET=provider-id")) {
            val source = details(rule); assertEquals("Custom recurrence", summary(source)); assertEquals(rule, source.recurrenceRule)
        }
        assertEquals("Custom recurrence", summary(details("FREQ=WEEKLY;BYDAY=TU")))
    }
    @Test fun refOnlySeriesHasFriendlyFallbackAndNonSeriesHasNoSummary() {
        assertEquals("Part of a series", summary(details(null)))
        assertNull(summary(details(null, series = false)))
        assertNull(summary(details(" ", series = false)))
    }
    @Test fun weekdaysMonthDaysAndNumbersUseRequestedLocale() {
        val french = summary(details("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE"), locale = Locale.FRANCE)!!
        assertTrue(french, french.contains("lundi")); assertTrue(french, french.contains("mercredi"))
        val arabic = summary(details("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO"), locale = Locale.forLanguageTag("ar"))!!
        assertTrue(arabic, arabic.contains("٢")); assertTrue(arabic, arabic.contains(DayOfWeek.MONDAY.getDisplayName(java.time.format.TextStyle.FULL, Locale.forLanguageTag("ar"))))
        assertFalse(arabic.contains("BYDAY")); assertFalse(arabic.contains("41"))
    }
    @Test fun masterWithoutExceptionRefUsesProvidedInstanceIdentityNotUiDate() {
        val source = details("FREQ=WEEKLY", series = false)
        assertEquals("Every week on Monday", summary(source))
    }
}
