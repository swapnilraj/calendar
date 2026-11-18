package dev.swapnilraj.calendar.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class RecurrenceMasterTemplateTest {
    private val london = ZoneId.of("Europe/London")
    private fun timed(local: String, zone: ZoneId = london, offset: ZoneOffset? = null) =
        RecurrenceStart.Timed(LocalDateTime.parse(local), zone, offset)
    private fun ready(start: RecurrenceStart, raw: String) =
        (RecurrenceMasterTemplates.resolve(start, raw) as RecurrenceMasterTemplateResult.Ready).template
    private fun review(problem: RecurrenceMasterTemplateProblem, start: RecurrenceStart, raw: String?) =
        assertEquals(RecurrenceMasterTemplateResult.NeedsReview(problem), RecurrenceMasterTemplates.resolve(start, raw))

    @Test fun logicalMasterDoesNotAdoptSelectedLaterOccurrenceDates() {
        val start = timed("2026-11-18T09:00:00")
        val later = Instant.parse("2026-12-18T09:00:00Z")
        val selected = EventRef(CalendarId(4), 8, later, later.plusSeconds(3600), RecurrenceRef(8, later))
        val snapshot = RecurrenceSeriesSnapshot(SeriesIdentity(CalendarId(4), 8), start,
            "FREQ=DAILY;COUNT=100", "PT3600S", validatedSelection = selected)
        val template = (RecurrenceMasterTemplates.resolve(snapshot) as RecurrenceMasterTemplateResult.Ready).template
        assertEquals(EventTime.Timed(start.instant, start.instant.plusSeconds(3600)), template.time)
        assertEquals("PT3600S", template.durationRaw)
        assertEquals(london, template.eventZone)
        assertEquals(selected, snapshot.validatedSelection)
    }

    @Test fun importedExactUntilAndOtherSourceFieldsAreNeverParsedOrRewritten() {
        val start = timed("2026-11-18T09:00:00")
        val rule = "FREQ=WEEKLY;BYDAY=WE;INTERVAL=2;WKST=SU;UNTIL=20261230T093000Z"
        val snapshot = RecurrenceSeriesSnapshot(SeriesIdentity(CalendarId(2), 9), start, rule, "+PT060M",
            exDatesRaw = "20261202T090000Z", coverage = SeriesCoverage.INCOMPLETE)
        val before = snapshot.copy()
        assertTrue(RecurrenceRules.parse(rule, start) is ParsedRecurrence.Supported)
        val template = (RecurrenceMasterTemplates.resolve(snapshot) as RecurrenceMasterTemplateResult.Ready).template
        assertEquals("+PT060M", template.durationRaw)
        assertEquals(before, snapshot)
        assertTrue((RecurrenceRules.parse(snapshot.ruleRaw!!, start) as ParsedRecurrence.Supported).spec.end is RecurrenceEnd.ExactUntil)
    }

    @Test fun representationDoesNotGrantRuleSupportOrCoverageAuthority() {
        val start = timed("2026-11-18T09:00:00")
        val snapshot = RecurrenceSeriesSnapshot(SeriesIdentity(CalendarId(2), 9), start,
            "unknown imported rule", "PT1H", rDatesRaw = "unknown period", coverage = SeriesCoverage.INCOMPLETE)
        assertTrue(RecurrenceMasterTemplates.resolve(snapshot) is RecurrenceMasterTemplateResult.Ready)
        assertEquals(SeriesCoverage.INCOMPLETE, snapshot.coverage)
    }

    @Test fun bothExplicitFoldOffsetsRetainExactSourceInstant() {
        for (offset in listOf(ZoneOffset.UTC, ZoneOffset.ofHours(1))) {
            val start = timed("2026-10-25T01:15:00", offset = offset)
            val time = ready(start, "PT30M").time as EventTime.Timed
            assertEquals(start.localDateTime.toInstant(offset), time.start)
            assertEquals(time.start.plusSeconds(1800), time.end)
        }
        review(RecurrenceMasterTemplateProblem.AMBIGUOUS_START, timed("2026-10-25T01:15:00"), "PT30M")
    }

    @Test fun elapsedHoursCrossLondonAndLordHoweTransitionsWithoutReResolvingEndOffset() {
        for ((start, end) in listOf(
            timed("2026-03-29T00:30:00") to "2026-03-29T02:30:00",
            timed("2026-10-25T01:45:00", offset = ZoneOffset.ofHours(1)) to "2026-10-25T01:45:00",
            timed("2026-10-04T01:45:00", ZoneId.of("Australia/Lord_Howe")) to "2026-10-04T03:15:00",
        )) {
            val time = ready(start, "PT1H").time as EventTime.Timed
            assertEquals(Duration.ofHours(1), time.duration)
            assertEquals(LocalDateTime.parse(end), time.end.atZone(start.zone).toLocalDateTime())
        }
    }

    @Test fun nominalTimedDaysAndWeeksNeedReviewEvenWithoutAnImmediateClockChange() {
        for (start in listOf(timed("2026-03-28T12:00:00"), timed("2026-10-24T12:00:00"), timed("2026-11-18T12:00:00")))
            for (raw in listOf("P1D", "P1W", "P1DT1H"))
                review(RecurrenceMasterTemplateProblem.TIMED_NOMINAL_DURATION, start, raw)
        assertEquals("PT24H", ready(timed("2026-03-28T12:00:00"), "PT24H").durationRaw)
    }

    @Test fun rawElapsedSyntaxIsPreservedIncludingZeroNominalComponent() {
        val start = timed("2026-11-18T09:00:00")
        for (raw in listOf("+PT0001H", "PT60M", "PT3600S", "P0DT1H")) {
            val template = ready(start, raw)
            assertEquals(raw, template.durationRaw)
            assertEquals(Duration.ofHours(1), (template.time as EventTime.Timed).duration)
        }
    }

    @Test fun allDayWholeDatesUseUtcAndRetainNominalUnitsAcrossLeapAndSkippedDates() {
        for ((date, raw, end) in listOf(Triple("2028-02-28", "P2D", "2028-03-01"),
            Triple("2011-12-30", "P1D", "2011-12-31"), Triple("2026-03-28", "P1W", "2026-04-04"))) {
            val start = RecurrenceStart.AllDay(LocalDate.parse(date))
            val template = ready(start, raw)
            assertEquals(EventTime.AllDay(CalendarDateRange(start.date, LocalDate.parse(end))), template.time)
            assertEquals(ZoneOffset.UTC, template.eventZone)
            assertEquals(raw, template.durationRaw)
        }
    }

    @Test fun allDayDoesNotConvertTimeUnitsEvenWhenEquivalentOrZero() {
        val start = RecurrenceStart.AllDay(LocalDate.parse("2026-11-18"))
        for (raw in listOf("PT24H", "PT86400S", "P1DT0S"))
            review(RecurrenceMasterTemplateProblem.DURATION_KIND, start, raw)
    }

    @Test fun missingMalformedAndUnsupportedDurationsNeverAcquireDefaults() {
        val start = timed("2026-11-18T09:00:00")
        for (raw in listOf(null, "")) review(RecurrenceMasterTemplateProblem.MISSING_DURATION, start, raw)
        for (raw in listOf(" ", "PT", "PT1H1S", "P1Dgarbage", "PT9223372036854775808S"))
            review(RecurrenceMasterTemplateProblem.INVALID_DURATION, start, raw)
        for (raw in listOf("P1M", "P1Y", "PT0.5S")) review(RecurrenceMasterTemplateProblem.UNSUPPORTED_DURATION, start, raw)
        review(RecurrenceMasterTemplateProblem.INPUT_BUDGET, start, "PT" + "0".repeat(128) + "1S")
    }

    @Test fun signedAndZeroOffsetsAreNotPositiveEventDurations() {
        val start = timed("2026-11-18T09:00:00")
        for (raw in listOf("-PT1S", "PT0S", "P0D", "-P0W"))
            review(RecurrenceMasterTemplateProblem.NON_POSITIVE_DURATION, start, raw)
    }

    @Test fun nativeIntArithmeticLimitIsCheckedBeforeLongDateArithmetic() {
        val start = timed("2026-11-18T09:00:00")
        assertEquals(Duration.ofSeconds(Int.MAX_VALUE.toLong()), (ready(start, "PT2147483647S").time as EventTime.Timed).duration)
        for (raw in listOf("PT2147483648S", "PT9223372036854775807H", "PT596523H15M"))
            review(RecurrenceMasterTemplateProblem.PROVIDER_DURATION_OVERFLOW, start, raw)
        val allDay = RecurrenceStart.AllDay(LocalDate.parse("2026-11-18"))
        ready(allDay, "P24855D")
        for (raw in listOf("P24856D", "P9223372036854775807W"))
            review(RecurrenceMasterTemplateProblem.PROVIDER_DURATION_OVERFLOW, allDay, raw)
    }

    @Test fun rfcEndpointBoundsRejectYearTenThousandAndUtcYearZero() {
        review(RecurrenceMasterTemplateProblem.DATE_RANGE, RecurrenceStart.AllDay(LocalDate.parse("9999-12-31")), "P1D")
        review(RecurrenceMasterTemplateProblem.DATE_RANGE, timed("9999-12-31T23:59:59", ZoneOffset.UTC), "PT1S")
        review(RecurrenceMasterTemplateProblem.DATE_RANGE, timed("0001-01-01T00:00:00", ZoneId.of("Asia/Kolkata")), "PT1H")
    }

    private fun snapshot(start: RecurrenceStart, rule: String) = RecurrenceSeriesSnapshot(SeriesIdentity(CalendarId(2), 9), start, rule, "PT1H")

    @Test fun allKeepRetainsLiteralCountRuleWithOmittedDefaults() {
        val start = timed("2026-11-18T09:00:00")
        val raw = "COUNT=20;FREQ=DAILY"
        val source = snapshot(start, raw)
        val replacement = RecurrenceRules.build((RecurrenceRules.parse(raw, start) as ParsedRecurrence.Supported).spec, start)
        assertNotEquals(raw, replacement)
        assertEquals(RecurrenceMasterRuleResult.Ready(raw), RecurrenceMasterTemplates.effectiveRule(source,
            RecurrenceEditIntent.WholeSeriesEdit(start, duration = RecurrenceDurationChange.TimedElapsed(Duration.ofHours(2))),
            RecurrencePlanAction.UpdateMaster(rule = FieldChange.Set(replacement), duration = RecurrenceDurationChange.TimedElapsed(Duration.ofHours(2)))))
    }

    @Test fun zoneChangePreservesExactUtcUntilWithoutTurningItIntoNewLocalEndOfDay() {
        val start = timed("2026-11-18T09:00:00")
        val changed = timed("2026-11-18T09:00:00", ZoneId.of("Asia/Kathmandu"))
        val raw = "FREQ=DAILY;UNTIL=20261230T235959Z"
        val source = snapshot(start, raw)
        val replacement = "FREQ=DAILY;INTERVAL=1;WKST=MO;UNTIL=20261230T235959Z"
        assertEquals(RecurrenceMasterRuleResult.Ready(raw), RecurrenceMasterTemplates.effectiveRule(source,
            RecurrenceEditIntent.WholeSeriesEdit(start, newMasterStart = FieldChange.Set(changed)),
            RecurrencePlanAction.UpdateMaster(rule = FieldChange.Set(replacement), anchor = FieldChange.Set(changed))))
    }

    @Test fun dirtyRuleUsesValidatedPlannedReplacementRatherThanOldRaw() {
        val start = timed("2026-11-18T09:00:00")
        val raw = "FREQ=DAILY;COUNT=3"
        val planned = "FREQ=DAILY;INTERVAL=2;COUNT=3;WKST=SU"
        assertEquals(RecurrenceMasterRuleResult.Ready(planned), RecurrenceMasterTemplates.effectiveRule(snapshot(start, raw),
            RecurrenceEditIntent.WholeSeriesEdit(start, rule = RecurrenceRuleChanges(interval = FieldChange.Set(2), weekStart = FieldChange.Set(java.time.DayOfWeek.SUNDAY))),
            RecurrencePlanAction.UpdateMaster(rule = FieldChange.Set(planned))))
    }

    @Test fun keepCannotHideChangedLimitsOrIncompatibleAnchor() {
        val start = timed("2026-11-18T09:00:00")
        val source = snapshot(start, "FREQ=DAILY;COUNT=3")
        assertEquals(RecurrenceMasterRuleResult.NeedsReview(RecurrenceMasterRuleProblem.INCOMPATIBLE_RULE),
            RecurrenceMasterTemplates.effectiveRule(source, RecurrenceEditIntent.WholeSeriesEdit(start),
                RecurrencePlanAction.UpdateMaster(rule = FieldChange.Set("FREQ=DAILY;COUNT=4"))))
        val changed = timed("2026-11-19T09:00:00")
        assertEquals(RecurrenceMasterRuleResult.NeedsReview(RecurrenceMasterRuleProblem.ANCHOR),
            RecurrenceMasterTemplates.effectiveRule(source, RecurrenceEditIntent.WholeSeriesEdit(start),
                RecurrencePlanAction.UpdateMaster(anchor = FieldChange.Set(changed))))
    }

    @Test fun sourceStartExcludedFromRuleIsReviewNotARewrittenIncludedSeed() {
        val start = timed("2026-11-18T09:00:00") // Wednesday; explicit Tuesdays exclude it.
        val raw = "FREQ=WEEKLY;BYDAY=TU;COUNT=3"
        assertEquals(RecurrenceMasterRuleResult.NeedsReview(RecurrenceMasterRuleProblem.UNSUPPORTED_RULE),
            RecurrenceMasterTemplates.effectiveRule(snapshot(start, raw), RecurrenceEditIntent.WholeSeriesEdit(start),
                RecurrencePlanAction.UpdateMaster()))
    }
}
