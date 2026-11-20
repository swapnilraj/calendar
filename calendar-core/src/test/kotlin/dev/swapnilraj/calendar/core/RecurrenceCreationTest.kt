package dev.swapnilraj.calendar.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.MonthDay
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class RecurrenceCreationTest {
    private val london = ZoneId.of("Europe/London")
    private fun timed(local: String, zone: ZoneId = london, offset: ZoneOffset? = null) =
        RecurrenceStart.Timed(LocalDateTime.parse(local), zone, offset)
    private fun interval(start: RecurrenceStart.Timed, seconds: Long = 3600) = EventTime.Timed(start.instant, start.instant.plusSeconds(seconds))
    private fun allDay(date: String, days: Long = 1): Pair<RecurrenceStart.AllDay, EventTime.AllDay> {
        val start = RecurrenceStart.AllDay(LocalDate.parse(date))
        return start to EventTime.AllDay(CalendarDateRange(start.date, start.date.plusDays(days)))
    }
    private fun ready(result: RecurrenceCreationResult) = (result as RecurrenceCreationResult.Ready).plan
    private fun invalid(problem: RecurrenceCreationProblem, result: RecurrenceCreationResult) =
        assertEquals(RecurrenceCreationResult.Invalid(problem), result)
    private val daily = RecurrenceSpec(RecurrencePattern.Daily)

    @Test fun timedDefaultsToExactElapsedDurationAndFinitePreview() {
        val start = timed("2026-11-18T09:15:00")
        val plan = ready(RecurrenceCreation.compile(start, interval(start, 5400), daily))
        assertEquals("PT5400S", plan.durationRaw)
        assertEquals(start.instant, plan.dtstart)
        assertEquals(london, plan.eventZone)
        assertFalse(plan.allDay)
        assertEquals(10, plan.preview.size)
        assertEquals(daily, (RecurrenceRules.parse(plan.ruleRaw, start) as ParsedRecurrence.Supported).spec)
    }

    @Test fun allDayUsesUtcDateBoundariesNominalDaysAndDateUntil() {
        val (start, time) = allDay("2026-03-28", 2)
        val spec = daily.copy(end = RecurrenceEnd.ThroughDate(LocalDate.parse("2026-03-30")))
        val plan = ready(RecurrenceCreation.compile(start, time, spec))
        assertEquals("P2D", plan.durationRaw)
        assertTrue(plan.allDay)
        assertEquals(ZoneOffset.UTC, plan.eventZone)
        assertEquals(Instant.parse("2026-03-28T00:00:00Z"), plan.dtstart)
        assertTrue(plan.ruleRaw.contains("UNTIL=20260330"))
        assertFalse(plan.ruleRaw.substringAfter("UNTIL=").contains('T'))
        assertEquals(listOf("2026-03-28", "2026-03-29", "2026-03-30"), plan.preview.map { (it as RecurrenceOccurrence.AllDay).date.toString() })
    }

    @Test fun timedInclusiveEndUsesEventZoneRatherThanUtcOrDisplayDate() {
        val start = timed("2026-03-28T09:00:00")
        val plan = ready(RecurrenceCreation.compile(start, interval(start), daily.copy(end = RecurrenceEnd.ThroughDate(LocalDate.parse("2026-03-29")))))
        assertTrue(plan.ruleRaw.contains("UNTIL=20260329T225959Z"))
        assertEquals(2, plan.preview.size)
    }

    @Test fun weeklyLocalTimeSurvivesSpringAndFallOffsetChanges() {
        for ((local, expected) in listOf("2026-03-22T09:00:00" to "2026-03-29T08:00:00Z", "2026-10-18T09:00:00" to "2026-10-25T09:00:00Z")) {
            val start = timed(local)
            val spec = RecurrenceSpec(RecurrencePattern.Weekly(setOf(start.date.dayOfWeek)), end = RecurrenceEnd.Count(2))
            val plan = ready(RecurrenceCreation.compile(start, interval(start), spec))
            assertEquals(Instant.parse(expected), (plan.preview.last() as RecurrenceOccurrence.Timed).instant)
            assertEquals("PT3600S", plan.durationRaw)
        }
    }

    @Test fun explicitStartOffsetRequiredForFoldButBothChosenInstantsAreRetained() {
        val unspecified = timed("2026-10-25T01:15:00")
        invalid(RecurrenceCreationProblem.START_OFFSET, RecurrenceCreation.compile(unspecified, interval(unspecified), daily))
        for (offset in listOf(ZoneOffset.UTC, ZoneOffset.ofHours(1))) {
            val start = timed("2026-10-25T01:15:00", offset = offset)
            val plan = ready(RecurrenceCreation.compile(start, interval(start), daily))
            assertEquals(start.localDateTime.toInstant(offset), plan.dtstart)
            assertEquals(interval(start), plan.firstTime)
            assertEquals(start.instant, (plan.preview.first() as RecurrenceOccurrence.Timed).instant)
        }
    }

    @Test fun explicitNominalTimedDaysRequireReviewInsteadOfProviderBoundsClaim() {
        for ((local, hours) in listOf("2026-03-28T12:00:00" to 23L, "2026-10-24T12:00:00" to 25L, "2026-11-18T12:00:00" to 24L)) {
            val start = timed(local)
            val time = interval(start, hours * 3600)
            assertEquals(RecurrenceCreationResult.NeedsReview(RecurrenceCreationProblem.PROVIDER_NOMINAL_DURATION),
                RecurrenceCreation.compile(start, time, daily, durationRaw = "P1D"))
            assertEquals("PT86400S", ready(RecurrenceCreation.compile(start, interval(start, 86400), daily)).durationRaw)
        }
    }

    @Test fun explicitElapsedEncodingIsRetainedWithoutNormalizingItsRawUnits() {
        val start = timed("2026-03-28T12:00:00")
        for (raw in listOf("PT24H", "+PT024H", "P0DT24H"))
            assertEquals(raw, ready(RecurrenceCreation.compile(start, interval(start, 86400), daily, raw)).durationRaw)
        invalid(RecurrenceCreationProblem.DURATION, RecurrenceCreation.compile(start, interval(start, 3600), daily, "PT2H"))
    }

    @Test fun explicitWholeWeeksRemainAllDayAndTimeUnitsNeverCoerceIntoDateDays() {
        val (start, time) = allDay("2026-11-18", 7)
        assertEquals("P1W", ready(RecurrenceCreation.compile(start, time, daily, "P1W")).durationRaw)
        for (raw in listOf("PT168H", "P7DT0S", "-P7D"))
            invalid(RecurrenceCreationProblem.DURATION, RecurrenceCreation.compile(start, time, daily, raw))
    }

    @Test fun monthlyThirtyFirstSkipsMissingDatesAndCountRemainsNominal() {
        val (start, time) = allDay("2026-01-31")
        val plan = ready(RecurrenceCreation.compile(start, time, RecurrenceSpec(RecurrencePattern.MonthlyDate(31), end = RecurrenceEnd.Count(3))))
        assertEquals(listOf("2026-01-31", "2026-03-31", "2026-05-31"), plan.preview.map { (it as RecurrenceOccurrence.AllDay).date.toString() })
        assertTrue(plan.ruleRaw.contains("COUNT=3"))
    }

    @Test fun leapDayYearlyAndMonthlyOrdinalUseExistingLibraryPatterns() {
        val (start, time) = allDay("2028-02-29")
        val plan = ready(RecurrenceCreation.compile(start, time, RecurrenceSpec(RecurrencePattern.Yearly(MonthDay.of(2, 29)), end = RecurrenceEnd.Count(2))))
        assertEquals(listOf("2028-02-29", "2032-02-29"), plan.preview.map { (it as RecurrenceOccurrence.AllDay).date.toString() })
        val (ordinalStart, ordinalTime) = allDay("2026-11-30")
        assertTrue(ready(RecurrenceCreation.compile(ordinalStart, ordinalTime,
            RecurrenceSpec(RecurrencePattern.MonthlyWeekday(-1, java.time.DayOfWeek.MONDAY)))).ruleRaw.contains("BYDAY=-1MO"))
    }

    @Test fun budgetsRejectInsteadOfClampingAndCountIsNotEnumeratedToItsEnd() {
        val (start, time) = allDay("2026-11-18")
        invalid(RecurrenceCreationProblem.INTERVAL, RecurrenceCreation.compile(start, time, daily.copy(interval = 1001)))
        invalid(RecurrenceCreationProblem.COUNT, RecurrenceCreation.compile(start, time, daily.copy(end = RecurrenceEnd.Count(10001))))
        for (limit in listOf(0, 51)) invalid(RecurrenceCreationProblem.PREVIEW_BUDGET, RecurrenceCreation.compile(start, time, daily, previewLimit = limit))
        assertEquals(2, ready(RecurrenceCreation.compile(start, time, daily.copy(end = RecurrenceEnd.Count(10000)), previewLimit = 2)).preview.size)
    }

    @Test fun importedExactUntilAndPreStartEndCannotBeNewAuthoredRules() {
        val start = timed("2026-11-18T09:00:00")
        invalid(RecurrenceCreationProblem.END_LIMIT, RecurrenceCreation.compile(start, interval(start), daily.copy(end = RecurrenceEnd.ExactUntil(start.instant.plusSeconds(3600)))))
        invalid(RecurrenceCreationProblem.END_LIMIT, RecurrenceCreation.compile(start, interval(start), daily.copy(end = RecurrenceEnd.ThroughDate(start.date.minusDays(1)))))
    }

    @Test fun invalidKindsBoundariesPatternsPrecisionAndZeroDurationFail() {
        val (date, allDayTime) = allDay("2026-11-18")
        val start = timed("2026-11-18T09:00:00")
        invalid(RecurrenceCreationProblem.START_TIME, RecurrenceCreation.compile(date, interval(start), daily))
        invalid(RecurrenceCreationProblem.START_TIME, RecurrenceCreation.compile(start, allDayTime, daily))
        invalid(RecurrenceCreationProblem.START_TIME, RecurrenceCreation.compile(start, interval(start).copy(start = start.instant.plusSeconds(1)), daily))
        invalid(RecurrenceCreationProblem.DURATION, RecurrenceCreation.compile(start, interval(start, 0), daily))
        invalid(RecurrenceCreationProblem.DURATION, RecurrenceCreation.compile(start, interval(start).copy(end = start.instant.plusNanos(1)), daily))
        invalid(RecurrenceCreationProblem.START_TIME, RecurrenceCreation.compile(date,
            EventTime.AllDay(CalendarDateRange.day(date.date.plusDays(1))), daily))
        invalid(RecurrenceCreationProblem.PATTERN_OR_RULE, RecurrenceCreation.compile(date, allDayTime, RecurrenceSpec(RecurrencePattern.MonthlyDate(19))))
    }

    @Test fun providerDurationArithmeticAndRfcDateBoundariesAreBounded() {
        val start = timed("2026-11-18T09:00:00")
        invalid(RecurrenceCreationProblem.DURATION_RANGE, RecurrenceCreation.compile(start, interval(start, Int.MAX_VALUE.toLong() + 1), daily))
        val (date, time) = allDay("2026-11-18", 24856)
        invalid(RecurrenceCreationProblem.DURATION_RANGE, RecurrenceCreation.compile(date, time, daily))
        val (end, _) = allDay("9999-12-31")
        invalid(RecurrenceCreationProblem.DATE_RANGE, RecurrenceCreation.compile(end,
            EventTime.AllDay(CalendarDateRange(end.date, end.date.plusDays(1))), daily))
    }

    @Test fun futureFoldAndGapPreviewsRequireReviewInsteadOfChoosingOrShifting() {
        for (local in listOf("2026-03-28T01:15:00", "2026-10-24T01:15:00")) {
            val start = timed(local)
            assertEquals(RecurrenceCreationResult.NeedsReview(RecurrenceCreationProblem.PREVIEW_TRANSITION),
                RecurrenceCreation.compile(start, interval(start), daily.copy(end = RecurrenceEnd.Count(3))))
        }
    }
}
