package dev.swapnilraj.calendar.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class RecurrencePlanningTest {
    private val identity = SeriesIdentity(CalendarId(2), 10)
    private val seed = Instant.parse("2026-11-17T09:00:00Z")
    private val start = RecurrenceStart.Timed(LocalDateTime.ofInstant(seed, ZoneOffset.UTC), ZoneOffset.UTC)
    private val title = EventPatch(title = FieldChange.Set("Updated appointment"))
    private fun key(index: Int) = RecurrenceKey.Timed(seed.plusSeconds(index * 86400L))
    private fun ref(index: Int, id: Long = 10, actual: Int = index): EventRef {
        val begin = seed.plusSeconds(actual * 86400L)
        return EventRef(identity.calendarId, id, begin, begin.plusSeconds(3600), RecurrenceRef(10, key(index).instant))
    }
    private fun snapshot(rule: String = "FREQ=DAILY;COUNT=6") = RecurrenceSeriesSnapshot(
        identity, start, rule, durationRaw = "PT1H", coverage = SeriesCoverage.COMPLETE,
    )
    private fun ready(result: RecurrencePlanningResult) = (result as RecurrencePlanningResult.Ready).actions
    /** Fixtures explicitly stand in for A's successful fresh provider selection validation. */
    private fun plan(snapshot: RecurrenceSeriesSnapshot, selected: EventRef, scope: RecurrenceScope,
        mutation: RecurrenceMutation, budget: RecurrencePlanningBudget = RecurrencePlanningBudget()) =
        RecurrencePlanning.plan(snapshot.copy(validatedSelection = selected), selected, scope, mutation, budget)
    private fun rule(action: RecurrencePlanAction.UpdateMaster) = (action.rule as FieldChange.Set).value
    private fun exclusions(action: RecurrencePlanAction.UpdateMaster) = (action.exclusions as FieldChange.Set).value

    @Test fun incompleteSnapshotNeverProducesExecutableActions() {
        val source = snapshot().copy(coverage = SeriesCoverage.INCOMPLETE)
        assertTrue(plan(source, ref(0), RecurrenceScope.THIS, RecurrenceMutation.Edit(title)) is RecurrencePlanningResult.NeedsReview)
    }

    @Test fun ordinaryOneOffCannotBeTransformedIntoASeriesException() {
        val source = snapshot().copy(ruleRaw = null)
        assertTrue(plan(source, ref(0), RecurrenceScope.THIS, RecurrenceMutation.Edit(title)) is RecurrencePlanningResult.NeedsReview)
    }

    @Test fun absentOrStaleFreshSelectionProofCannotAuthorizeUnknownRuleEdits() {
        val selected = ref(3)
        val source = snapshot("FREQ=DAILY;BYHOUR=9,10")
        assertTrue(RecurrencePlanning.plan(source, selected, RecurrenceScope.THIS, RecurrenceMutation.Edit(title))
            is RecurrencePlanningResult.NeedsReview)
        val stale = selected.copy(instanceStart = selected.instanceStart.plusSeconds(300))
        assertTrue(RecurrencePlanning.plan(source.copy(validatedSelection = selected), stale,
            RecurrenceScope.THIS, RecurrenceMutation.Edit(title)) is RecurrencePlanningResult.NeedsReview)
    }

    @Test fun singleEditPreservesUninterpretedRulesAndUsesExistingOriginalIdentity() {
        val existing = SeriesException(ref(2, 20, actual = 4), key(2))
        val source = snapshot("FREQ=DAILY;BYHOUR=9,10").copy(
            rDatesRaw = "unsupported provider date encoding", exRuleRaw = "FREQ=YEARLY", exceptions = listOf(existing),
        )
        val patch = title.copy(time = FieldChange.Set(EventTime.Timed(seed.plusSeconds(10), seed.plusSeconds(3610))))
        val action = ready(plan(source, existing.ref, RecurrenceScope.THIS, RecurrenceMutation.Edit(patch))).single()
            as RecurrencePlanAction.UpsertException
        assertEquals(key(2), action.original)
        assertEquals(existing.ref, action.existing)
        assertEquals(patch, action.patch)
        assertFalse(action.cancelled)
        assertEquals(SeriesTarget.EXISTING, action.target)
    }

    @Test fun singleDeletionCancelsOneOriginalWithoutChangingTheMaster() {
        val source = snapshot()
        val action = ready(plan(source, ref(2), RecurrenceScope.THIS, RecurrenceMutation.Delete)).single()
            as RecurrencePlanAction.UpsertException
        assertTrue(action.cancelled)
        assertEquals(key(2), action.original)
        assertNull(action.existing)
    }

    @Test fun allNonTemporalEditPreservesRawRulesAndExistingExceptions() {
        val exception = SeriesException(ref(2, 20, 4), key(2))
        val source = snapshot("FREQ=DAILY;BYHOUR=9,10").copy(exceptions = listOf(exception), exRuleRaw = "FREQ=YEARLY")
        val action = ready(plan(source, exception.ref, RecurrenceScope.ALL, RecurrenceMutation.Edit(title))).single()
            as RecurrencePlanAction.UpdateMaster
        assertEquals(title, action.patch)
        assertEquals(FieldChange.Keep, action.rule)
        assertEquals(FieldChange.Keep, action.exclusions)
    }

    @Test fun countSplitCountsExcludedNominalSlotsRatherThanVisibleEvents() {
        val source = snapshot().copy(exDatesRaw = "20261118T090000Z", exDates = setOf(key(1)), exDatesDecoded = true)
        val actions = ready(plan(source, ref(3), RecurrenceScope.FOLLOWING, RecurrenceMutation.Edit(title)))
        val prefix = actions[0] as RecurrencePlanAction.UpdateMaster
        val tail = actions[1] as RecurrencePlanAction.InsertTail
        assertEquals("FREQ=DAILY;COUNT=3", rule(prefix))
        assertEquals("FREQ=DAILY;COUNT=3", tail.rule)
        assertEquals(setOf(key(1)), exclusions(prefix))
        assertTrue(tail.exclusions.isEmpty())
        assertEquals(RecurrenceStart.Timed(LocalDateTime.parse("2026-11-20T09:00:00"), ZoneOffset.UTC, ZoneOffset.UTC), tail.start)
        assertEquals(title, tail.patch)
    }

    @Test fun exclusionsAndMovedOrCancelledExceptionsPartitionByOriginalKey() {
        val movedPastOriginal = SeriesException(ref(1, 21, 5), key(1))
        val movedFutureOriginal = SeriesException(ref(4, 24, 0), key(4))
        val cancelledFuture = SeriesException(ref(5, 25), key(5), cancelled = true)
        val source = snapshot().copy(exDatesRaw = "20261118T090000Z,20261122T090000Z", exDates = setOf(key(1), key(5)), exDatesDecoded = true,
            exceptions = listOf(cancelledFuture, movedPastOriginal, movedFutureOriginal))
        val actions = ready(plan(source, ref(3), RecurrenceScope.FOLLOWING, RecurrenceMutation.Edit(title)))
        val transfers = actions.filterIsInstance<RecurrencePlanAction.RebindExceptionToTail>()
        assertEquals(listOf(movedFutureOriginal, cancelledFuture), transfers.map { it.exception })
        assertTrue(transfers.all { it.selectedPatch == EventPatch() })
        assertEquals(setOf(key(5)), (actions[1] as RecurrencePlanAction.InsertTail).exclusions)
        assertFalse(transfers.any { it.exception.ref == movedPastOriginal.ref })
    }

    @Test fun editFollowingAnExistingMovedExceptionAlsoUpdatesTheSelectedOverride() {
        val selected = SeriesException(ref(3, 23, 0), key(3))
        val source = snapshot().copy(exceptions = listOf(selected))
        val actions = ready(plan(source, selected.ref, RecurrenceScope.FOLLOWING, RecurrenceMutation.Edit(title)))
        val transfer = actions.filterIsInstance<RecurrencePlanAction.RebindExceptionToTail>().single()
        assertEquals(selected, transfer.exception)
        assertEquals(title, transfer.selectedPatch)
        assertEquals(LocalDate.of(2026, 11, 20), (actions[1] as RecurrencePlanAction.InsertTail).start.date)
    }

    @Test fun deleteFollowingRemovesFutureOriginalsEvenWhenTheirActualTimeMovedIntoPast() {
        val past = SeriesException(ref(1, 21, 5), key(1))
        val future = SeriesException(ref(4, 24, 0), key(4))
        val source = snapshot().copy(exceptions = listOf(future, past))
        val actions = ready(plan(source, ref(3), RecurrenceScope.FOLLOWING, RecurrenceMutation.Delete))
        assertEquals("FREQ=DAILY;COUNT=3", rule(actions.first() as RecurrencePlanAction.UpdateMaster))
        assertEquals(listOf(future), actions.filterIsInstance<RecurrencePlanAction.RemoveException>().map { it.exception })
        assertTrue(actions.none { it is RecurrencePlanAction.InsertTail })
    }

    @Test fun untilCutoffIsRetainedExactlyOnTailAndPrefixUsesLastGeneratedOriginal() {
        val raw = "WKST=SU;FREQ=DAILY;UNTIL=20261130T120000Z"
        val actions = ready(plan(snapshot(raw), ref(3), RecurrenceScope.FOLLOWING, RecurrenceMutation.Edit(title)))
        assertEquals("WKST=SU;FREQ=DAILY;UNTIL=20261119T090000Z", rule(actions[0] as RecurrencePlanAction.UpdateMaster))
        assertEquals(raw, (actions[1] as RecurrencePlanAction.InsertTail).rule)
    }

    @Test fun firstOccurrenceFutureScopeDoesNotCreateCountZeroOrRedundantMaster() {
        val source = snapshot()
        val edit = ready(plan(source, ref(0), RecurrenceScope.FOLLOWING, RecurrenceMutation.Edit(title)))
        assertEquals(listOf(RecurrencePlanAction.UpdateMaster(patch = title)), edit)
        val delete = ready(plan(source, ref(0), RecurrenceScope.FOLLOWING, RecurrenceMutation.Delete))
        assertTrue(delete.single() is RecurrencePlanAction.DeleteSeries)
    }

    @Test fun lastCountedOccurrenceLeavesOneRemainingTailOccurrence() {
        val actions = ready(plan(snapshot(), ref(5), RecurrenceScope.FOLLOWING, RecurrenceMutation.Edit(title)))
        assertEquals("FREQ=DAILY;COUNT=5", rule(actions[0] as RecurrencePlanAction.UpdateMaster))
        assertEquals("FREQ=DAILY;COUNT=1", (actions[1] as RecurrencePlanAction.InsertTail).rule)
    }

    @Test fun allDaySplitUsesDateUntilAndUtcEncodedOriginalIdentity() {
        val date = LocalDate.of(2026, 11, 17)
        val source = snapshot("FREQ=DAILY;UNTIL=20261125").copy(start = RecurrenceStart.AllDay(date), durationRaw = "P1D")
        val original = date.plusDays(3).atStartOfDay().toInstant(ZoneOffset.UTC)
        val selected = EventRef(identity.calendarId, 10, original, original.plusSeconds(86400), RecurrenceRef(10, original))
        val actions = ready(plan(source, selected, RecurrenceScope.FOLLOWING, RecurrenceMutation.Edit(title)))
        assertEquals("FREQ=DAILY;UNTIL=20261119", rule(actions[0] as RecurrencePlanAction.UpdateMaster))
        assertEquals(RecurrenceStart.AllDay(date.plusDays(3)), (actions[1] as RecurrencePlanAction.InsertTail).start)
        assertEquals("FREQ=DAILY;UNTIL=20261125", (actions[1] as RecurrencePlanAction.InsertTail).rule)
    }

    @Test fun dstWeeklyPrefixUsesTheActualPreviousOccurrenceInstant() {
        val zone = ZoneId.of("Europe/London")
        val source = snapshot("FREQ=WEEKLY;BYDAY=SU").copy(start = RecurrenceStart.Timed(LocalDateTime.parse("2026-03-22T09:00:00"), zone))
        val instant = Instant.parse("2026-03-29T08:00:00Z")
        val selected = EventRef(identity.calendarId, 10, instant, instant.plusSeconds(3600), RecurrenceRef(10, instant))
        val actions = ready(plan(source, selected, RecurrenceScope.FOLLOWING, RecurrenceMutation.Edit(title)))
        assertEquals("FREQ=WEEKLY;BYDAY=SU;UNTIL=20260322T090000Z", rule(actions[0] as RecurrencePlanAction.UpdateMaster))
        val tail = actions[1] as RecurrencePlanAction.InsertTail
        assertEquals(LocalDateTime.parse("2026-03-29T09:00:00"), (tail.start as RecurrenceStart.Timed).localDateTime)
        assertEquals(ZoneOffset.ofHours(1), tail.start.preferredOffset)
    }

    @Test fun weeklyWkstPhaseIsPreservedWhenSplittingMidweek() {
        val source = snapshot("FREQ=WEEKLY;BYDAY=SU,TU;INTERVAL=2;WKST=SU;COUNT=4")
            .copy(start = RecurrenceStart.AllDay(LocalDate.of(2026, 11, 1)))
        val instant = Instant.parse("2026-11-03T00:00:00Z")
        val selected = EventRef(identity.calendarId, 10, instant, instant.plusSeconds(86400), RecurrenceRef(10, instant))
        val actions = ready(plan(source, selected, RecurrenceScope.FOLLOWING, RecurrenceMutation.Edit(title)))
        val tail = actions[1] as RecurrencePlanAction.InsertTail
        assertEquals("FREQ=WEEKLY;BYDAY=SU,TU;INTERVAL=2;WKST=SU;COUNT=3", tail.rule)
        assertEquals(listOf("2026-11-03", "2026-11-15", "2026-11-17"),
            RecurrenceRules.preview(tail.rule, tail.start, 10).map { (it as RecurrenceOccurrence.AllDay).date.toString() })
    }

    @Test fun unsupportedSetTransformsDoNotReturnPartialPlans() {
        for (source in listOf(snapshot().copy(rDatesRaw = "20261120T100000Z"), snapshot().copy(exRuleRaw = "FREQ=WEEKLY;BYDAY=SU"),
            snapshot("FREQ=DAILY;BYHOUR=9,10"))) {
            assertTrue(plan(source, ref(3), RecurrenceScope.FOLLOWING, RecurrenceMutation.Edit(title)) is RecurrencePlanningResult.Unsupported)
        }
    }

    @Test fun incompleteExdateDecodeAndMixedDateTypesRequireReview() {
        val undecoded = snapshot().copy(exDatesRaw = "uninterpreted date encoding")
        assertTrue(plan(undecoded, ref(3), RecurrenceScope.FOLLOWING, RecurrenceMutation.Delete) is RecurrencePlanningResult.NeedsReview)
        val mixed = snapshot().copy(exDatesRaw = "20261118", exDatesDecoded = true,
            exDates = setOf(RecurrenceKey.Date(LocalDate.of(2026, 11, 18))))
        assertTrue(plan(mixed, ref(3), RecurrenceScope.FOLLOWING, RecurrenceMutation.Delete) is RecurrencePlanningResult.NeedsReview)
    }

    @Test fun boundedScanAndExceptionLimitsRequireReviewInsteadOfTruncation() {
        assertTrue(plan(snapshot(), ref(3), RecurrenceScope.FOLLOWING, RecurrenceMutation.Delete,
            RecurrencePlanningBudget(maxInstances = 2)) is RecurrencePlanningResult.NeedsReview)
        val source = snapshot().copy(exceptions = listOf(SeriesException(ref(4, 24), key(4))))
        assertTrue(plan(source, ref(3), RecurrenceScope.FOLLOWING, RecurrenceMutation.Delete,
            RecurrencePlanningBudget(maxExceptions = 0)) is RecurrencePlanningResult.NeedsReview)
    }

    @Test fun orphanExceptionBeyondCountCannotBeSilentlyTransferred() {
        val source = snapshot().copy(exceptions = listOf(SeriesException(ref(10, 30), key(10))))
        assertTrue(plan(source, ref(3), RecurrenceScope.FOLLOWING, RecurrenceMutation.Edit(title)) is RecurrencePlanningResult.NeedsReview)
    }

    @Test fun invalidSelectionsAndCancelledOccurrencesAreRejected() {
        assertTrue(plan(snapshot(), ref(6), RecurrenceScope.FOLLOWING, RecurrenceMutation.Delete) is RecurrencePlanningResult.Invalid)
        assertTrue(plan(snapshot(), ref(3).copy(calendarId = CalendarId(99)), RecurrenceScope.THIS,
            RecurrenceMutation.Delete) is RecurrencePlanningResult.Invalid)
        val cancelled = SeriesException(ref(3, 23), key(3), cancelled = true)
        assertTrue(plan(snapshot().copy(exceptions = listOf(cancelled)), cancelled.ref, RecurrenceScope.THIS,
            RecurrenceMutation.Delete) is RecurrencePlanningResult.Invalid)
        assertTrue(plan(snapshot(), ref(3).copy(recurrence = null), RecurrenceScope.THIS,
            RecurrenceMutation.Delete) is RecurrencePlanningResult.Invalid)
    }

    @Test fun timeZoneOrPatternChangesAreExplicitReviewRequests() {
        val temporal = RecurrenceMutation.Edit(title.copy(eventZone = FieldChange.Set(ZoneId.of("Europe/London"))))
        assertTrue(plan(snapshot(), ref(3), RecurrenceScope.ALL, temporal) is RecurrencePlanningResult.NeedsReview)
        val replacement = RecurrenceMutation.Edit(title, FieldChange.Set(RecurrenceSpec(RecurrencePattern.Weekly(setOf(DayOfWeek.TUESDAY)))))
        assertTrue(plan(snapshot(), ref(3), RecurrenceScope.FOLLOWING, replacement) is RecurrencePlanningResult.NeedsReview)
    }

    @Test fun allDeleteCarriesKnownExceptionsWithoutReinterpretingUnsupportedRules() {
        val moved = SeriesException(ref(2, 22, 5), key(2))
        val cancelled = SeriesException(ref(4, 24), key(4), cancelled = true)
        val source = snapshot().copy(rDatesRaw = "uninterpreted RDATE", exceptions = listOf(cancelled, moved))
        val action = ready(plan(source, ref(3), RecurrenceScope.ALL, RecurrenceMutation.Delete)).single()
            as RecurrencePlanAction.DeleteSeries
        assertEquals(listOf(moved, cancelled), action.exceptions)
    }

    @Test fun duplicateExceptionOriginalsRequireReviewAndPlansAreDeterministic() {
        val one = SeriesException(ref(4, 24), key(4))
        val duplicate = one.copy(ref = ref(4, 25))
        assertTrue(plan(snapshot().copy(exceptions = listOf(one, duplicate)), ref(3), RecurrenceScope.FOLLOWING,
            RecurrenceMutation.Edit(title)) is RecurrencePlanningResult.NeedsReview)
        val source = snapshot().copy(exceptions = listOf(one))
        assertEquals(plan(source, ref(3), RecurrenceScope.FOLLOWING, RecurrenceMutation.Edit(title)),
            plan(source, ref(3), RecurrenceScope.FOLLOWING, RecurrenceMutation.Edit(title)))
    }
}
