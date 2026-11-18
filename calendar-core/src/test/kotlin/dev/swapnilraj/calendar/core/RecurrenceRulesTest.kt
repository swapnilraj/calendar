package dev.swapnilraj.calendar.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.MonthDay
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class RecurrenceRulesTest {
    private fun allDay(date: String) = RecurrenceStart.AllDay(LocalDate.parse(date))
    private fun timed(value: String, zone: String = "Europe/London") =
        RecurrenceStart.Timed(LocalDateTime.parse(value), ZoneId.of(zone))
    private fun dates(raw: String, start: RecurrenceStart.AllDay, limit: Int = 10) =
        RecurrenceRules.preview(raw, start, limit).map { (it as RecurrenceOccurrence.AllDay).date.toString() }

    @Test fun dailyCountAndCustomIntervalRoundTrip() {
        val start = allDay("2026-11-17")
        val spec = RecurrenceSpec(RecurrencePattern.Daily, 2, RecurrenceEnd.Count(3))
        val raw = RecurrenceRules.build(spec, start)
        assertEquals(spec, (RecurrenceRules.parse(raw, start) as ParsedRecurrence.Supported).spec)
        assertEquals(listOf("2026-11-17", "2026-11-19", "2026-11-21"), dates(raw, start))
    }

    @Test fun weekdayPresetSkipsWeekend() {
        val start = allDay("2026-11-20")
        val weekdays = DayOfWeek.entries.filter { it.value <= 5 }.toSet()
        val raw = RecurrenceRules.build(RecurrenceSpec(RecurrencePattern.Weekly(weekdays)), start)
        assertEquals(listOf("2026-11-20", "2026-11-23", "2026-11-24", "2026-11-25"), dates(raw, start, 4))
    }

    @Test fun customWeeklyDaysKeepWeekStartSemanticsForMultiWeekIntervals() {
        val start = allDay("2026-11-01")
        val pattern = RecurrencePattern.Weekly(setOf(DayOfWeek.SUNDAY, DayOfWeek.TUESDAY))
        val sunday = RecurrenceRules.build(RecurrenceSpec(pattern, 2, RecurrenceEnd.Count(4), DayOfWeek.SUNDAY), start)
        val monday = RecurrenceRules.build(RecurrenceSpec(pattern, 2, RecurrenceEnd.Count(4), DayOfWeek.MONDAY), start)
        assertEquals(listOf("2026-11-01", "2026-11-03", "2026-11-15", "2026-11-17"), dates(sunday, start))
        assertEquals(listOf("2026-11-01", "2026-11-10", "2026-11-15", "2026-11-24"), dates(monday, start))
        assertEquals(DayOfWeek.SUNDAY, (RecurrenceRules.parse(sunday, start) as ParsedRecurrence.Supported).spec.weekStart)
    }

    @Test fun monthlyThirtyFirstSkipsMissingDatesInsteadOfClamping() {
        val start = allDay("2026-01-31")
        val raw = RecurrenceRules.build(RecurrenceSpec(RecurrencePattern.MonthlyDate(31), end = RecurrenceEnd.Count(4)), start)
        assertEquals(listOf("2026-01-31", "2026-03-31", "2026-05-31", "2026-07-31"), dates(raw, start))
    }

    @Test fun monthlyLastWeekdayRoundTrips() {
        val start = allDay("2026-01-30")
        val spec = RecurrenceSpec(RecurrencePattern.MonthlyWeekday(-1, DayOfWeek.FRIDAY), end = RecurrenceEnd.Count(3))
        val raw = RecurrenceRules.build(spec, start)
        assertEquals(spec, (RecurrenceRules.parse(raw, start) as ParsedRecurrence.Supported).spec)
        assertEquals(listOf("2026-01-30", "2026-02-27", "2026-03-27"), dates(raw, start))
    }

    @Test fun yearlyLeapDaySkipsNonLeapYears() {
        val start = allDay("2028-02-29")
        val raw = RecurrenceRules.build(RecurrenceSpec(RecurrencePattern.Yearly(MonthDay.of(2, 29)), end = RecurrenceEnd.Count(3)), start)
        assertEquals(listOf("2028-02-29", "2032-02-29", "2036-02-29"), dates(raw, start))
    }

    @Test fun allDayThroughDateUsesInclusiveDateUntil() {
        val start = allDay("2026-11-17")
        val spec = RecurrenceSpec(RecurrencePattern.Daily, end = RecurrenceEnd.ThroughDate(LocalDate.of(2026, 11, 19)))
        val raw = RecurrenceRules.build(spec, start)
        assertTrue(raw.contains("UNTIL=20261119"))
        assertFalse(raw.contains("20261119T"))
        assertEquals(listOf("2026-11-17", "2026-11-18", "2026-11-19"), dates(raw, start))
    }

    @Test fun timedInclusiveEndDateIsResolvedInEventZoneAndWrittenAsUtc() {
        val start = timed("2026-03-28T09:00:00")
        val spec = RecurrenceSpec(RecurrencePattern.Daily, end = RecurrenceEnd.ThroughDate(LocalDate.of(2026, 3, 29)))
        val raw = RecurrenceRules.build(spec, start)
        assertTrue(raw.endsWith("UNTIL=20260329T225959Z"))
        assertEquals(spec, (RecurrenceRules.parse(raw, start) as ParsedRecurrence.Supported).spec)
        val occurrences = RecurrenceRules.preview(raw, start, 10).map { it as RecurrenceOccurrence.Timed }
        assertEquals(listOf(Instant.parse("2026-03-28T09:00:00Z"), Instant.parse("2026-03-29T08:00:00Z")), occurrences.map { it.instant })
    }

    @Test fun weeklyMeetingRetainsLocalTimeAcrossDst() {
        val start = timed("2026-03-22T09:00:00")
        val raw = RecurrenceRules.build(RecurrenceSpec(RecurrencePattern.Weekly(setOf(DayOfWeek.SUNDAY)), end = RecurrenceEnd.Count(3)), start)
        val occurrences = RecurrenceRules.preview(raw, start, 10).map { it as RecurrenceOccurrence.Timed }
        assertEquals(listOf("2026-03-22T09:00", "2026-03-29T09:00", "2026-04-05T09:00"), occurrences.map { it.localDateTime.toString() })
        assertEquals(listOf("2026-03-22T09:00:00Z", "2026-03-29T08:00:00Z", "2026-04-05T08:00:00Z"), occurrences.map { it.instant.toString() })
    }

    @Test fun importedExactUntilIsNotWidenedToEndOfDate() {
        val start = timed("2026-11-17T09:00:00")
        val raw = "FREQ=DAILY;UNTIL=20261119T085959Z"
        val parsed = RecurrenceRules.parse(raw, start) as ParsedRecurrence.Supported
        assertEquals(raw, parsed.raw)
        assertEquals(RecurrenceEnd.ExactUntil(Instant.parse("2026-11-19T08:59:59Z")), parsed.spec.end)
        assertEquals(2, RecurrenceRules.preview(raw, start, 10).size)
        assertTrue(RecurrenceRules.build(parsed.spec, start).endsWith("UNTIL=20261119T085959Z"))
    }

    @Test fun untilAtOccurrenceTimeIncludesThatOccurrence() {
        val start = timed("2026-11-17T09:00:00")
        assertEquals(3, RecurrenceRules.preview("FREQ=DAILY;UNTIL=20261119T090000Z", start, 10).size)
    }

    @Test fun importedRuleRetainsExactOrderingAndRawTextForUnrelatedEdits() {
        val raw = "COUNT=3;WKST=SU;BYDAY=TU,TH;FREQ=WEEKLY;INTERVAL=2"
        val parsed = RecurrenceRules.parse(raw, allDay("2026-11-17")) as ParsedRecurrence.Supported
        assertEquals(raw, parsed.raw)
        assertEquals(DayOfWeek.SUNDAY, parsed.spec.weekStart)
        assertEquals(setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY), (parsed.spec.pattern as RecurrencePattern.Weekly).days)
    }

    @Test fun omittedPatternFieldsDeriveFromDtstartWithoutChangingRaw() {
        val raw = "FREQ=MONTHLY;COUNT=2"
        val parsed = RecurrenceRules.parse(raw, allDay("2026-01-31")) as ParsedRecurrence.Supported
        assertEquals(RecurrencePattern.MonthlyDate(31), parsed.spec.pattern)
        assertEquals(raw, parsed.raw)
        assertEquals(listOf("2026-01-31", "2026-03-31"), dates(raw, allDay("2026-01-31")))
    }

    @Test fun validUnrepresentableRulesAreExplicitlyUnsupportedAndRetained() {
        for (raw in listOf(
            "FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1",
            "FREQ=MONTHLY;BYMONTHDAY=1,15",
            "FREQ=MONTHLY;BYMONTHDAY=-1",
            "FREQ=YEARLY;BYMONTH=1,2",
            "FREQ=DAILY;BYHOUR=8,9",
            "FREQ=HOURLY;COUNT=3",
        )) {
            val parsed = RecurrenceRules.parse(raw, allDay("2026-01-01"))
            assertTrue("Must be unsupported: $raw, got $parsed", parsed is ParsedRecurrence.Unsupported)
            assertEquals(raw, parsed.raw)
        }
    }

    @Test fun malformedAndDuplicateRulesAreInvalidButRetained() {
        for (raw in listOf("", "FREQ=DAILY;FREQ=WEEKLY", "FREQ=NOPE", "FREQ=DAILY;COUNT=0", "FREQ=DAILY;INTERVAL=0", "FREQ=DAILY;COUNT=3;UNTIL=20261119", "FREQ")) {
            val parsed = RecurrenceRules.parse(raw, allDay("2026-11-17"))
            assertTrue("Must be invalid: $raw, got $parsed", parsed is ParsedRecurrence.Invalid)
            assertEquals(raw, parsed.raw)
        }
    }

    @Test fun neverEndingRuleRequiresAnExplicitBoundedPreview() {
        val start = allDay("2026-11-17")
        val raw = RecurrenceRules.build(RecurrenceSpec(RecurrencePattern.Daily), start)
        assertEquals(3, dates(raw, start, 3).size)
        assertThrows(IllegalArgumentException::class.java) { RecurrenceRules.preview(raw, start, 0) }
        assertThrows(IllegalArgumentException::class.java) { RecurrenceRules.preview(raw, start, 1001) }
    }

    @Test fun unsupportedConstructsAreNeverPreviewedAsSimplifiedRules() {
        assertThrows(IllegalArgumentException::class.java) {
            RecurrenceRules.preview("FREQ=DAILY;BYHOUR=8,9", allDay("2026-11-17"), 3)
        }
    }

    @Test fun dtstartMustMatchThePatternInsteadOfGuessingFirstOccurrence() {
        val start = allDay("2026-11-17")
        assertThrows(IllegalArgumentException::class.java) {
            RecurrenceRules.build(RecurrenceSpec(RecurrencePattern.Weekly(setOf(DayOfWeek.WEDNESDAY))), start)
        }
        assertTrue(RecurrenceRules.parse("FREQ=WEEKLY;BYDAY=WE", start) is ParsedRecurrence.Unsupported)
    }

    @Test fun ambiguousStartCanPreserveTheSelectedOffsetAndGapInputIsRejected() {
        val local = LocalDateTime.parse("2026-10-25T01:30:00")
        val zone = ZoneId.of("Europe/London")
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), RecurrenceStart.Timed(local, zone).instant)
        assertEquals(Instant.parse("2026-10-25T01:30:00Z"), RecurrenceStart.Timed(local, zone, ZoneOffset.UTC).instant)
        assertThrows(IllegalArgumentException::class.java) { timed("2026-03-29T01:30:00") }
        assertThrows(IllegalArgumentException::class.java) { RecurrenceStart.Timed(local, zone, ZoneOffset.ofHours(2)) }
    }

    @Test fun previewPreservesTheChosenLaterOffsetForTheOriginalOccurrence() {
        val start = RecurrenceStart.Timed(LocalDateTime.parse("2026-10-25T01:30:00"), ZoneId.of("Europe/London"), ZoneOffset.UTC)
        val raw = RecurrenceRules.build(RecurrenceSpec(RecurrencePattern.Weekly(setOf(DayOfWeek.SUNDAY)), end = RecurrenceEnd.Count(2)), start)
        val occurrences = RecurrenceRules.preview(raw, start, 2).map { it as RecurrenceOccurrence.Timed }
        assertEquals(listOf("2026-10-25T01:30:00Z", "2026-11-01T01:30:00Z"), occurrences.map { it.instant.toString() })
    }

    @Test fun invalidAuthoringValuesAndEndBeforeStartAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { RecurrenceSpec(RecurrencePattern.Daily, 0) }
        assertThrows(IllegalArgumentException::class.java) { RecurrenceEnd.Count(0) }
        assertThrows(IllegalArgumentException::class.java) { RecurrencePattern.Weekly(emptySet()) }
        assertThrows(IllegalArgumentException::class.java) { RecurrencePattern.MonthlyDate(32) }
        assertThrows(IllegalArgumentException::class.java) { RecurrencePattern.MonthlyWeekday(0, DayOfWeek.MONDAY) }
        assertThrows(IllegalArgumentException::class.java) {
            RecurrenceRules.build(RecurrenceSpec(RecurrencePattern.Daily, end = RecurrenceEnd.ThroughDate(LocalDate.of(2026, 11, 16))), allDay("2026-11-17"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RecurrenceRules.build(RecurrenceSpec(RecurrencePattern.Daily, end = RecurrenceEnd.ExactUntil(Instant.parse("2026-11-19T09:00:00Z"))), allDay("2026-11-17"))
        }
    }
}
