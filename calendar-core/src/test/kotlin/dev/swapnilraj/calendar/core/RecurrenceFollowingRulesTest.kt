package dev.swapnilraj.calendar.core

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class RecurrenceFollowingRulesTest {
    private val zone = ZoneId.of("Europe/London")
    private fun date(value: String) = RecurrenceStart.AllDay(LocalDate.parse(value))
    private fun timed(value: String) = RecurrenceStart.Timed(LocalDateTime.parse(value), zone)
    private fun snapshot(start: RecurrenceStart, raw: String, original: String): RecurrenceSeriesSnapshot {
        val instant = if (start is RecurrenceStart.AllDay) LocalDate.parse(original).atStartOfDay(ZoneOffset.UTC).toInstant()
            else LocalDateTime.parse(original).atZone(zone).toInstant()
        val ref = EventRef(CalendarId(2), 7, instant, instant.plusSeconds(3600), RecurrenceRef(7, instant))
        return RecurrenceSeriesSnapshot(SeriesIdentity(CalendarId(2), 7), start, raw,
            if (start is RecurrenceStart.AllDay) "P1D" else "PT1H", coverage = SeriesCoverage.COMPLETE, validatedSelection = ref)
    }
    private fun intent(s: RecurrenceSeriesSnapshot, rule: RecurrenceRuleChanges = RecurrenceRuleChanges(),
        anchor: FieldChange<RecurrenceStart> = FieldChange.Keep) = RecurrenceEditIntent.FollowingSeriesEdit(
        if (s.start is RecurrenceStart.AllDay) RecurrenceKey.Date(s.validatedSelection!!.instanceStart.atOffset(ZoneOffset.UTC).toLocalDate())
        else RecurrenceKey.Timed(s.validatedSelection!!.instanceStart), newTailStart = anchor, rule = rule)
    private fun resolve(s: RecurrenceSeriesSnapshot, i: RecurrenceEditIntent.FollowingSeriesEdit = intent(s),
        budget: RecurrencePlanningBudget = RecurrencePlanningBudget()): RecurrenceFollowingRuleResult {
        val plan = RecurrenceEditing.plan(s, s.validatedSelection!!, i, budget) as RecurrencePlanningResult.Ready
        return RecurrenceFollowingRules.resolve(s, s.validatedSelection!!, i, plan, budget)
    }
    private fun tail(s: RecurrenceSeriesSnapshot, i: RecurrenceEditIntent.FollowingSeriesEdit = intent(s)) =
        resolve(s, i) as RecurrenceFollowingRuleResult.Tail

    @Test fun countPartitionsNominalOrdinalAndPreservesLiteralParts() {
        val s = snapshot(date("2026-01-05"), "WKST=SU;COUNT=010;BYDAY=MO;FREQ=WEEKLY;INTERVAL=1", "2026-01-19")
        val result = tail(s)
        assertEquals(2, result.boundaryOrdinal)
        assertEquals("WKST=SU;COUNT=2;BYDAY=MO;FREQ=WEEKLY;INTERVAL=1", result.prefixRuleRaw)
        assertEquals("WKST=SU;COUNT=8;BYDAY=MO;FREQ=WEEKLY;INTERVAL=1", result.tailRuleRaw)
    }

    @Test fun exactTimedUntilIsRetainedWithChangedAnchorAndDuration() {
        val raw = "WKST=SU;FREQ=DAILY;UNTIL=20260410T093012Z"
        val s = snapshot(timed("2026-03-27T09:00:00"), raw, "2026-03-30T09:00:00")
        val i = intent(s, anchor = FieldChange.Set(timed("2026-03-30T10:00:00")))
            .copy(duration = RecurrenceDurationChange.TimedElapsed(Duration.ofMinutes(90)))
        val result = tail(s, i)
        assertEquals(3, result.boundaryOrdinal)
        assertEquals(raw, result.tailRuleRaw)
        assertEquals("WKST=SU;FREQ=DAILY;UNTIL=20260329T080000Z", result.prefixRuleRaw)
        assertEquals(timed("2026-03-30T10:00:00"), result.tailStart)
    }

    @Test fun dateUntilAndUnboundedPrefixPreserveSourceSyntax() {
        for (end in listOf(";UNTIL=20261231", "")) {
            val raw = "INTERVAL=1;FREQ=MONTHLY;BYMONTHDAY=31;WKST=SU$end"
            val result = tail(snapshot(date("2026-01-31"), raw, "2026-05-31"))
            assertEquals(2, result.boundaryOrdinal) // February and April do not generate dates.
            assertEquals(raw, result.tailRuleRaw)
            assertEquals("INTERVAL=1;FREQ=MONTHLY;BYMONTHDAY=31;WKST=SU;UNTIL=20260331", result.prefixRuleRaw)
        }
    }

    @Test fun leapDayOrdinalCountsGeneratedYearsOnly() {
        val result = tail(snapshot(date("2024-02-29"), "FREQ=YEARLY;BYMONTH=2;BYMONTHDAY=29;COUNT=5", "2032-02-29"))
        assertEquals(2, result.boundaryOrdinal)
        assertTrue(result.prefixRuleRaw.endsWith("COUNT=2"))
        assertTrue(result.tailRuleRaw.endsWith("COUNT=3"))
    }

    @Test fun dirtyPatternRetainsPartitionedCountAndIndependentWeekStart() {
        val s = snapshot(date("2026-01-05"), "WKST=SU;FREQ=DAILY;COUNT=10", "2026-01-07")
        val i = intent(s, RecurrenceRuleChanges(pattern = FieldChange.Set(RecurrencePattern.Weekly(setOf(DayOfWeek.WEDNESDAY)))))
        val result = tail(s, i)
        val spec = (RecurrenceRules.parse(result.tailRuleRaw, result.tailStart) as ParsedRecurrence.Supported).spec
        assertEquals(RecurrenceEnd.Count(8), spec.end)
        assertEquals(DayOfWeek.SUNDAY, spec.weekStart)
        assertEquals(RecurrencePattern.Weekly(setOf(DayOfWeek.WEDNESDAY)), spec.pattern)
    }

    @Test fun dirtyIntervalKeepsExactUntilAndExplicitEndOverridesPartition() {
        val s = snapshot(timed("2026-03-27T09:00:00"), "FREQ=DAILY;UNTIL=20260430T093012Z", "2026-03-30T09:00:00")
        val result = tail(s, intent(s, RecurrenceRuleChanges(interval = FieldChange.Set(2))))
        assertTrue(result.tailRuleRaw.contains("UNTIL=20260430T093012Z"))
        val count = snapshot(date("2026-01-01"), "FREQ=DAILY;COUNT=10", "2026-01-03")
        val edited = tail(count, intent(count, RecurrenceRuleChanges(end = FieldChange.Set(RecurrenceEnd.Count(20)))))
        assertTrue(edited.tailRuleRaw.contains("COUNT=20"))
    }

    @Test fun ordinalZeroReturnsWholeAndRetainsRawWithoutAllocatingTail() {
        val raw = "WKST=SU;COUNT=010;FREQ=DAILY"
        val s = snapshot(date("2026-01-01"), raw, "2026-01-01")
        assertEquals(RecurrenceFollowingRuleResult.Whole(raw), resolve(s))
    }

    @Test fun forgedPlanIsNotAValidatedReplacement() {
        val s = snapshot(date("2026-01-01"), "FREQ=DAILY;COUNT=10", "2026-01-03")
        val i = intent(s)
        val plan = RecurrenceEditing.plan(s, s.validatedSelection!!, i) as RecurrencePlanningResult.Ready
        assertEquals(RecurrenceFollowingRuleResult.NeedsReview(RecurrenceFollowingRuleProblem.PLAN),
            RecurrenceFollowingRules.resolve(s, s.validatedSelection!!, i, plan.copy(actions = plan.actions.reversed())))
    }

    @Test fun budgetsRejectRatherThanClampAndBoundaryMustBeScanned() {
        val s = snapshot(date("2026-01-01"), "FREQ=DAILY;COUNT=10", "2026-01-03")
        val i = intent(s)
        val plan = RecurrenceEditing.plan(s, s.validatedSelection!!, i) as RecurrencePlanningResult.Ready
        assertEquals(RecurrenceFollowingRuleResult.NeedsReview(RecurrenceFollowingRuleProblem.INPUT_BUDGET),
            RecurrenceFollowingRules.resolve(s, s.validatedSelection!!, i, plan, RecurrencePlanningBudget(10001)))
        assertEquals(RecurrenceFollowingRuleResult.NeedsReview(RecurrenceFollowingRuleProblem.PLAN),
            RecurrenceFollowingRules.resolve(s, s.validatedSelection!!, i, plan, RecurrencePlanningBudget(2)))
    }

    @Test fun exclusionsAndSelectedOverridesRefuseWholePacket() {
        val s = snapshot(date("2026-01-01"), "FREQ=DAILY;COUNT=10", "2026-01-03")
        val i = intent(s)
        val plan = RecurrenceEditing.plan(s, s.validatedSelection!!, i) as RecurrencePlanningResult.Ready
        for (bad in listOf(s.copy(exDatesRaw = "20260104"), s.copy(rDatesRaw = "20260104"), s.copy(exRuleRaw = "FREQ=DAILY")))
            assertEquals(RecurrenceFollowingRuleResult.NeedsReview(RecurrenceFollowingRuleProblem.SOURCE_SHAPE),
                RecurrenceFollowingRules.resolve(bad, s.validatedSelection!!, i, plan))
    }

    @Test fun ambiguousScannedOccurrenceCannotBecomeProvenOrdinal() {
        val s = snapshot(timed("2026-10-24T01:30:00"), "FREQ=DAILY;COUNT=5", "2026-10-26T01:30:00")
        val i = intent(s)
        val plan = RecurrenceEditing.plan(s, s.validatedSelection!!, i) as RecurrencePlanningResult.Ready
        assertEquals(RecurrenceFollowingRuleResult.NeedsReview(RecurrenceFollowingRuleProblem.ORDINAL),
            RecurrenceFollowingRules.resolve(s, s.validatedSelection!!, i, plan))
    }
}
