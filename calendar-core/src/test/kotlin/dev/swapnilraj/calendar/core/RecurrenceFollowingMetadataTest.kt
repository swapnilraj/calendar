package dev.swapnilraj.calendar.core

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class RecurrenceFollowingMetadataTest {
    private val calendar = CalendarId(2)
    private val base = LocalDate.of(2026, 1, 1)
    private fun key(day: Long) = RecurrenceKey.Date(base.plusDays(day))
    private fun ref(day: Long, id: Long = 7, actual: Long = day) = EventRef(calendar, id,
        base.plusDays(actual).atStartOfDay(ZoneOffset.UTC).toInstant(),
        base.plusDays(actual + 1).atStartOfDay(ZoneOffset.UTC).toInstant(),
        RecurrenceRef(7, base.plusDays(day).atStartOfDay(ZoneOffset.UTC).toInstant()))
    private fun snapshot(day: Long = 3, raw: String = "WKST=SU;COUNT=010;FREQ=DAILY",
        exclusions: String? = null, exceptions: List<SeriesException> = emptyList()): RecurrenceSeriesSnapshot {
        val start = RecurrenceStart.AllDay(base)
        val keys = (RecurrenceDateCodec.parse(exclusions, start,
            allDayPolicy = AllDayDateTimePolicy.PROVIDER_UTC_MIDNIGHT) as? ParsedRecurrenceDates.Supported)?.keys ?: emptySet()
        return RecurrenceSeriesSnapshot(SeriesIdentity(calendar, 7), start, raw, "P1D",
            exDatesRaw = exclusions, exDates = keys, exDatesDecoded = true, exceptions = exceptions,
            coverage = SeriesCoverage.COMPLETE, validatedSelection = exceptions.singleOrNull { it.original == key(day) }?.ref ?: ref(day))
    }
    private fun intent(s: RecurrenceSeriesSnapshot, fields: EventPatch = EventPatch(), extra: EventPatch = EventPatch()) =
        RecurrenceEditIntent.FollowingSeriesEdit(when (s.start) {
            is RecurrenceStart.AllDay -> RecurrenceKey.Date(s.validatedSelection!!.recurrence!!.originalStart.atOffset(ZoneOffset.UTC).toLocalDate())
            is RecurrenceStart.Timed -> RecurrenceKey.Timed(s.validatedSelection!!.recurrence!!.originalStart)
        }, fields, selectedExceptionPatch = extra)
    private fun resolve(s: RecurrenceSeriesSnapshot, i: RecurrenceEditIntent.FollowingSeriesEdit = intent(s),
        budget: RecurrencePlanningBudget = RecurrencePlanningBudget(), dates: RecurrenceDateBudget = RecurrenceDateBudget()) =
        RecurrenceFollowingMetadata.resolve(s, s.validatedSelection!!, i, budget, dates)
    private fun split(s: RecurrenceSeriesSnapshot, i: RecurrenceEditIntent.FollowingSeriesEdit = intent(s)) =
        resolve(s, i) as RecurrenceFollowingMetadataResult.Split

    @Test fun nominalCountIncludesExcludedSeedAndEarlierExcludedOccurrences() {
        val s = snapshot(exclusions = "20260101,20260102,20260108")
        val result = split(s)
        assertEquals(3, result.boundaryOrdinal)
        assertEquals("WKST=SU;COUNT=3;FREQ=DAILY", result.prefixRuleRaw)
        assertEquals("WKST=SU;COUNT=7;FREQ=DAILY", result.tailRuleRaw)
        assertEquals("20260101,20260102", result.prefixExDatesRaw)
        assertEquals("20260108", result.tailExDatesRaw)
        assertEquals(RecurrenceStart.AllDay(base.plusDays(3)), result.tailStart)
    }

    @Test fun duplicateOrderGroupingAndTrailingLfAreRetainedWithoutCanonicalizing() {
        // CalendarCommon accepts UTC-prefixed DATE lists; the broader RFC date decoder rejects
        // that combination. Supply the independently known complete native source keys here.
        val s = snapshot(exclusions = "UTC;20260108,20260101,20260108\n20260102,20260109\n")
            .copy(exDates = setOf(key(0), key(1), key(7), key(8)))
        val result = split(s)
        assertEquals("UTC;20260101\n20260102\n", result.prefixExDatesRaw)
        assertEquals("UTC;20260108,20260108\n20260109\n", result.tailExDatesRaw)
    }

    @Test fun offPatternExclusionsRemainLiteralAndDoNotBecomeRequiredOrdinals() {
        val s = snapshot(day = 7, raw = "FREQ=WEEKLY;BYDAY=TH;COUNT=5", exclusions = "20260103,20260202")
        val result = split(s)
        assertEquals(1, result.boundaryOrdinal)
        assertEquals("20260103", result.prefixExDatesRaw)
        assertEquals("20260202", result.tailExDatesRaw)
        assertEquals("FREQ=WEEKLY;BYDAY=TH;COUNT=4", result.tailRuleRaw)
    }

    @Test fun movedAndCancelledExceptionsArePartitionedByOriginalKeysOnly() {
        val pastMovedFuture = SeriesException(ref(1, 21, 20), key(1))
        val futureMovedPast = SeriesException(ref(5, 25, 0), key(5))
        val cancelled = SeriesException(ref(6, 26, 1), key(6), cancelled = true)
        val s = snapshot(exceptions = listOf(cancelled, pastMovedFuture, futureMovedPast))
        val actions = split(s).plan.actions.filterIsInstance<RecurrencePlanAction.RebindExceptionToTail>()
        assertEquals(listOf(futureMovedPast, cancelled), actions.map { it.exception })
        assertTrue(actions.all { it.selectedPatch == EventPatch() })
    }

    @Test fun dirtySharedFieldsReachSelectedOverrideAndOtherOverridesKeepEverything() {
        val selected = SeriesException(ref(3, 23, 1), key(3))
        val other = SeriesException(ref(5, 25, 0), key(5))
        val s = snapshot(exceptions = listOf(selected, other))
        val shared = EventPatch(title = FieldChange.Set("updated"), privacy = FieldChange.Set<Int?>(null))
        val extra = EventPatch(location = FieldChange.Set("override"))
        val result = split(s, intent(s, shared, extra))
        assertEquals(shared, (result.plan.actions[1] as RecurrencePlanAction.InsertTail).patch)
        val moved = result.plan.actions.filterIsInstance<RecurrencePlanAction.RebindExceptionToTail>()
        assertEquals(shared.copy(location = extra.location), moved[0].selectedPatch)
        assertEquals(selected, moved[0].exception)
        assertEquals(EventPatch(), moved[1].selectedPatch)
        assertEquals(EventPatch(), (result.plan.actions[0] as RecurrencePlanAction.UpdateMaster).patch)
    }

    @Test fun explicitSelectedOverrideTimeRetainsOriginalKeyAndDoesNotMoveTheTailAnchor() {
        val selected = SeriesException(ref(3, 23, 1), key(3))
        val s = snapshot(exceptions = listOf(selected))
        val time = EventTime.AllDay(CalendarDateRange(base.plusDays(12), base.plusDays(14)))
        val extra = EventPatch(time = FieldChange.Set(time))
        val result = split(s, intent(s, extra = extra))
        assertEquals(RecurrenceStart.AllDay(base.plusDays(3)), result.tailStart)
        val transfer = result.plan.actions.filterIsInstance<RecurrencePlanAction.RebindExceptionToTail>().single()
        assertEquals(selected, transfer.exception)
        assertEquals(extra, transfer.selectedPatch)
        assertEquals(EventPatch(), (result.plan.actions[1] as RecurrencePlanAction.InsertTail).patch)
    }

    @Test fun nullableSharedAndExtraConflictsRejectInsteadOfChoosingOne() {
        val selected = SeriesException(ref(3, 23), key(3))
        val s = snapshot(exceptions = listOf(selected))
        for (a in listOf<Int?>(null, 0, 1)) for (b in listOf<Int?>(null, 0, 1)) {
            val i = intent(s, EventPatch(privacy = FieldChange.Set(a)), EventPatch(privacy = FieldChange.Set(b)))
            if (a == b) assertTrue(resolve(s, i) is RecurrenceFollowingMetadataResult.Split)
            else assertEquals(RecurrenceFollowingMetadataResult.NeedsReview(RecurrenceFollowingMetadataProblem.INTENT), resolve(s, i))
        }
    }

    @Test fun extraOverridePatchWithoutSelectedExceptionRejects() {
        val s = snapshot()
        assertEquals(RecurrenceFollowingMetadataResult.NeedsReview(RecurrenceFollowingMetadataProblem.SELECTION),
            resolve(s, intent(s, extra = EventPatch(description = FieldChange.Set("extra")))))
    }

    @Test fun ordinalZeroRetainsRawRuleAndExclusionsAndUpdatesSelectedOverride() {
        val selected = SeriesException(ref(0, 20, 4), key(0))
        val s = snapshot(day = 0, exclusions = "20260102,20260102\n", exceptions = listOf(selected))
        val shared = EventPatch(availability = FieldChange.Set<Int?>(null))
        val result = resolve(s, intent(s, shared)) as RecurrenceFollowingMetadataResult.Whole
        assertEquals(s.ruleRaw, result.ruleRaw)
        assertEquals(s.exDatesRaw, result.exDatesRaw)
        assertEquals(2, result.plan.actions.size)
        assertEquals(shared, (result.plan.actions[0] as RecurrencePlanAction.UpdateMaster).patch)
        assertEquals(shared, (result.plan.actions[1] as RecurrencePlanAction.UpsertException).patch)
    }

    @Test fun lastNominalOccurrenceProducesCountOneTail() {
        val result = split(snapshot(day = 9))
        assertEquals("WKST=SU;COUNT=9;FREQ=DAILY", result.prefixRuleRaw)
        assertEquals("WKST=SU;COUNT=1;FREQ=DAILY", result.tailRuleRaw)
    }

    @Test fun dateUntilAndUnboundedTailKeepLiteralSourceRule() {
        for (raw in listOf("WKST=SU;FREQ=DAILY;UNTIL=20261231", "WKST=SU;FREQ=DAILY")) {
            val result = split(snapshot(raw = raw))
            assertEquals(raw, result.tailRuleRaw)
            assertEquals("WKST=SU;FREQ=DAILY;UNTIL=20260103", result.prefixRuleRaw)
        }
    }

    private fun timed(startText: String, boundaryText: String, zoneName: String = "Europe/London",
        raw: String = "FREQ=DAILY;UNTIL=20261231T093012Z", exclusions: String? = null): RecurrenceSeriesSnapshot {
        val zone = ZoneId.of(zoneName)
        val start = RecurrenceStart.Timed(LocalDateTime.parse(startText), zone)
        val instant = LocalDateTime.parse(boundaryText).atZone(zone).toInstant()
        val selected = EventRef(calendar, 7, instant, instant.plusSeconds(3600), RecurrenceRef(7, instant))
        return RecurrenceSeriesSnapshot(SeriesIdentity(calendar, 7), start, raw, "PT1H", exDatesRaw = exclusions,
            exDates = (RecurrenceDateCodec.parse(exclusions, start) as ParsedRecurrenceDates.Supported).keys,
            exDatesDecoded = true, coverage = SeriesCoverage.COMPLETE, validatedSelection = selected)
    }

    @Test fun timedExactUntilSurvivesOffsetTransitionWithoutDateRounding() {
        val s = timed("2026-03-27T09:00:00", "2026-03-30T09:00:00", exclusions = "UTC;20260328T090000Z,20260401T080000Z")
        val result = split(s)
        assertEquals(s.ruleRaw, result.tailRuleRaw)
        assertEquals("FREQ=DAILY;UNTIL=20260329T080000Z", result.prefixRuleRaw)
        assertEquals("UTC;20260328T090000Z", result.prefixExDatesRaw)
        assertEquals("UTC;20260401T080000Z", result.tailExDatesRaw)
    }

    @Test fun lordHoweHalfHourOffsetTransitionKeepsWallTimeAndNominalCount() {
        val s = timed("2026-04-03T09:00:00", "2026-04-06T09:00:00", "Australia/Lord_Howe", "FREQ=DAILY;COUNT=10")
        val result = split(s)
        assertEquals(3, result.boundaryOrdinal)
        assertEquals(LocalTime.of(9, 0), (result.tailStart as RecurrenceStart.Timed).localDateTime.toLocalTime())
        assertEquals("FREQ=DAILY;COUNT=7", result.tailRuleRaw)
    }

    @Test fun ambiguousOrGapScannedClockRequiresReview() {
        for (s in listOf(timed("2026-10-24T01:30:00", "2026-10-26T01:30:00"),
            timed("2026-03-28T01:30:00", "2026-03-30T01:30:00"))) {
            assertTrue(resolve(s) is RecurrenceFollowingMetadataResult.NeedsReview)
        }
    }

    @Test fun malformedOpaqueOrRfcExclusionsNeverProducePartialPartition() {
        for (raw in listOf("EXDATE:20260102", "VALUE=DATE:20260102", "Europe/London;20260102",
            "20260102,garbage", "20260102,", "20260102\r\n20260105", "20260102\n\n20260105", "20260102 ")) {
            assertTrue(raw, resolve(snapshot(exclusions = raw)) is RecurrenceFollowingMetadataResult.NeedsReview)
        }
    }

    @Test fun utcMidnightAllDayTokensKeepTheirOriginalRepresentation() {
        val result = split(snapshot(exclusions = "UTC;20260102T000000Z,20260108T000000Z"))
        assertEquals("UTC;20260102T000000Z", result.prefixExDatesRaw)
        assertEquals("UTC;20260108T000000Z", result.tailExDatesRaw)
    }

    @Test fun mismatchedDecodedExclusionsOrExcludedSelectionRejects() {
        val s = snapshot(exclusions = "20260102")
        assertTrue(resolve(s.copy(exDates = setOf(key(8)))) is RecurrenceFollowingMetadataResult.NeedsReview)
        assertTrue(resolve(s.copy(exDatesDecoded = false)) is RecurrenceFollowingMetadataResult.NeedsReview)
        assertTrue(resolve(snapshot(exclusions = "20260104")) is RecurrenceFollowingMetadataResult.NeedsReview)
        val moved = SeriesException(ref(3, 23, 6), key(3))
        assertTrue(resolve(snapshot(exclusions = "20260104", exceptions = listOf(moved))) is RecurrenceFollowingMetadataResult.Split)
    }

    @Test fun missingEmptyAndEntirelyOneSidedRawExclusionsStayDistinct() {
        assertNull(split(snapshot()).prefixExDatesRaw)
        assertEquals("", split(snapshot(exclusions = "")).tailExDatesRaw)
        val result = split(snapshot(exclusions = "20260102\n"))
        assertEquals("20260102\n", result.prefixExDatesRaw)
        assertNull(result.tailExDatesRaw)
    }

    @Test fun orphanPastAndFutureExceptionOriginalsBothRequireReview() {
        for (day in listOf(1L, 8L)) {
            val ex = SeriesException(ref(day, 20 + day), key(day))
            val s = snapshot(day = 7, raw = "FREQ=WEEKLY;BYDAY=TH;COUNT=5", exceptions = listOf(ex))
            assertTrue(resolve(s) is RecurrenceFollowingMetadataResult.NeedsReview)
        }
    }

    @Test fun duplicateExceptionIdsOrOriginalsAndChangedSelectionRejects() {
        val first = SeriesException(ref(1, 21), key(1))
        val second = SeriesException(ref(5, 21), key(5))
        val s = snapshot(exceptions = listOf(first, second))
        assertTrue(resolve(s) is RecurrenceFollowingMetadataResult.NeedsReview)
        val valid = snapshot()
        assertTrue(resolve(valid.copy(coverage = SeriesCoverage.INCOMPLETE)) is RecurrenceFollowingMetadataResult.NeedsReview)
        assertTrue(resolve(valid.copy(validatedSelection = ref(4)), intent(valid)) is RecurrenceFollowingMetadataResult.NeedsReview)
        assertTrue(resolve(valid.copy(exceptions = listOf(first, first))) is RecurrenceFollowingMetadataResult.NeedsReview)
    }

    @Test fun exactBoundaryAndExceptionScanBudgetIsEnforcedWithoutClamping() {
        val s = snapshot()
        assertTrue(resolve(s, budget = RecurrencePlanningBudget(3)) is RecurrenceFollowingMetadataResult.NeedsReview)
        assertTrue(resolve(s, budget = RecurrencePlanningBudget(4)) is RecurrenceFollowingMetadataResult.Split)
        assertEquals(RecurrenceFollowingMetadataResult.NeedsReview(RecurrenceFollowingMetadataProblem.INPUT_BUDGET),
            resolve(s, budget = RecurrencePlanningBudget(10001)))
        val far = SeriesException(ref(8, 28), key(8))
        assertTrue(resolve(snapshot(exceptions = listOf(far)), budget = RecurrencePlanningBudget(8)) is RecurrenceFollowingMetadataResult.NeedsReview)
        assertTrue(resolve(snapshot(exceptions = listOf(far)), budget = RecurrencePlanningBudget(9)) is RecurrenceFollowingMetadataResult.Split)
    }

    @Test fun rawInputAndDuplicateDateCountsConsumeTheirOwnBudgets() {
        val s = snapshot(exclusions = "20260102,20260102,20260102")
        assertTrue(resolve(s, dates = RecurrenceDateBudget(maxValues = 2)) is RecurrenceFollowingMetadataResult.NeedsReview)
        assertTrue(resolve(s, dates = RecurrenceDateBudget(maxChars = 4)) is RecurrenceFollowingMetadataResult.NeedsReview)
        assertTrue(resolve(s.copy(ruleRaw = "FREQ=DAILY;" + "X".repeat(4096))) is RecurrenceFollowingMetadataResult.NeedsReview)
        val ex = SeriesException(ref(5, 25), key(5))
        assertTrue(resolve(snapshot(exceptions = listOf(ex)), budget = RecurrencePlanningBudget(maxExceptions = 0)) is RecurrenceFollowingMetadataResult.NeedsReview)
    }

    @Test fun temporalOrRuleChangesAndUnsupportedSetsRequireSeparatePlanner() {
        val s = snapshot()
        for (i in listOf(intent(s).copy(newTailStart = FieldChange.Set(RecurrenceStart.AllDay(base))),
            intent(s).copy(duration = RecurrenceDurationChange.AllDayDays(2)),
            intent(s).copy(rule = RecurrenceRuleChanges(interval = FieldChange.Set(2))),
            intent(s).copy(fields = EventPatch(time = FieldChange.Set(EventTime.AllDay(CalendarDateRange(base, base.plusDays(1)))))))) {
            assertEquals(RecurrenceFollowingMetadataResult.NeedsReview(RecurrenceFollowingMetadataProblem.INTENT), resolve(s, i))
        }
        assertTrue(resolve(s.copy(rDatesRaw = "20260120")) is RecurrenceFollowingMetadataResult.NeedsReview)
        assertTrue(resolve(s.copy(exRuleRaw = "FREQ=DAILY")) is RecurrenceFollowingMetadataResult.NeedsReview)
    }

    @Test fun unknownDurationMayStayInPlaceButCannotBecomeNewTail() {
        assertTrue(resolve(snapshot(day = 0).copy(durationRaw = "PTUNKNOWN")) is RecurrenceFollowingMetadataResult.Whole)
        assertEquals(RecurrenceFollowingMetadataResult.NeedsReview(RecurrenceFollowingMetadataProblem.DURATION),
            resolve(snapshot().copy(durationRaw = "PTUNKNOWN")))
    }

    @Test fun repeatedProofReturnsTheSameImmutableSemanticPlan() {
        val s = snapshot(exclusions = "20260102,20260108")
        assertEquals(resolve(s), resolve(s))
    }
}
