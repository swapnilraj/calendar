package dev.swapnilraj.calendar.core

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class RecurrenceEditingTest {
    private val identity = SeriesIdentity(CalendarId(2), 10)
    private val seed = Instant.parse("2026-11-17T09:00:00Z")
    private val start = RecurrenceStart.Timed(LocalDateTime.ofInstant(seed, ZoneOffset.UTC), ZoneOffset.UTC)
    private val fields = EventPatch(title = FieldChange.Set("New title"))
    private fun key(index: Int) = RecurrenceKey.Timed(seed.plusSeconds(index * 86400L))
    private fun ref(index: Int, id: Long = 10, actual: Int = index) = EventRef(identity.calendarId, id,
        seed.plusSeconds(actual * 86400L), seed.plusSeconds(actual * 86400L + 3600), RecurrenceRef(10, key(index).instant))
    private fun snapshot(rule: String = "FREQ=DAILY;COUNT=6") = RecurrenceSeriesSnapshot(identity, start, rule,
        durationRaw = "PT1H", coverage = SeriesCoverage.COMPLETE)
    private fun plan(source: RecurrenceSeriesSnapshot, selected: EventRef, intent: RecurrenceEditIntent,
        budget: RecurrencePlanningBudget = RecurrencePlanningBudget()) =
        RecurrenceEditing.plan(source.copy(validatedSelection = selected), selected, intent, budget)
    private fun ready(value: RecurrencePlanningResult) = (value as RecurrencePlanningResult.Ready).actions
    private fun value(change: FieldChange<String>) = (change as FieldChange.Set).value
    private fun exclusions(change: FieldChange<Set<RecurrenceKey>>) = (change as FieldChange.Set).value

    @Test fun wholeSeriesEditUsesMasterAnchorEvenWhenSelectedOccurrenceIsLate() {
        val source = snapshot().copy(exDatesRaw = "20261118T090000Z", exDates = setOf(key(1)), exDatesDecoded = true)
        val intent = RecurrenceEditIntent.WholeSeriesEdit(start, rule = RecurrenceRuleChanges(
            pattern = FieldChange.Set(RecurrencePattern.Weekly(setOf(DayOfWeek.TUESDAY)))))
        val action = ready(plan(source, ref(5), intent)).first() as RecurrencePlanAction.UpdateMaster
        assertEquals(FieldChange.Keep, action.anchor)
        assertTrue(value(action.rule).contains("COUNT=6"))
        assertEquals(setOf(RecurrenceKey.Timed(Instant.parse("2026-11-24T09:00:00Z"))), exclusions(action.exclusions))
    }

    @Test fun futurePatternChangeRemapsExdatesAndMovedCancelledOverridesByOrdinal() {
        val past = SeriesException(ref(1, 21, 5), key(1))
        val moved = SeriesException(ref(4, 24, 0), key(4))
        val cancelled = SeriesException(ref(5, 25), key(5), cancelled = true)
        val source = snapshot().copy(exDatesRaw = "20261118T090000Z,20261122T090000Z", exDates = setOf(key(1), key(5)),
            exDatesDecoded = true, exceptions = listOf(cancelled, past, moved))
        val newStart = RecurrenceStart.Timed(LocalDateTime.parse("2026-11-23T15:00:00"), ZoneOffset.UTC)
        val intent = RecurrenceEditIntent.FollowingSeriesEdit(key(3), fields, FieldChange.Set(newStart),
            rule = RecurrenceRuleChanges(pattern = FieldChange.Set(RecurrencePattern.Weekly(setOf(DayOfWeek.MONDAY)))))
        val actions = ready(plan(source, ref(3), intent))
        val prefix = actions[0] as RecurrencePlanAction.UpdateMaster
        val tail = actions[1] as RecurrencePlanAction.InsertTail
        assertEquals("FREQ=DAILY;COUNT=3", value(prefix.rule))
        assertEquals(setOf(key(1)), exclusions(prefix.exclusions))
        assertEquals(newStart, tail.start)
        assertTrue(tail.rule.contains("COUNT=3"))
        assertEquals(setOf(RecurrenceKey.Timed(Instant.parse("2026-12-07T15:00:00Z"))), tail.exclusions)
        val remaps = actions.filterIsInstance<RecurrencePlanAction.RemapException>()
        assertEquals(listOf(moved, cancelled), remaps.map { it.exception })
        assertEquals(listOf("2026-11-30T15:00:00Z", "2026-12-07T15:00:00Z"), remaps.map { (it.newOriginal as RecurrenceKey.Timed).instant.toString() })
        assertTrue(remaps.all { it.target == SeriesTarget.TAIL && it.selectedPatch == EventPatch() })
        assertEquals(seed, remaps.first().exception.ref.instanceStart)
        assertTrue(remaps.last().exception.cancelled)
    }

    @Test fun wholeSeriesTemporalChangeRetainsOverrideActualTimeAndPayload() {
        val moved = SeriesException(ref(2, 22, 4), key(2))
        val source = snapshot().copy(exceptions = listOf(moved))
        val newStart = RecurrenceStart.Timed(LocalDateTime.parse("2026-11-17T10:00:00"), ZoneId.of("Europe/London"))
        val intent = RecurrenceEditIntent.WholeSeriesEdit(start, fields, FieldChange.Set(newStart))
        val actions = ready(plan(source, ref(5), intent))
        assertEquals(FieldChange.Set(newStart), (actions.first() as RecurrencePlanAction.UpdateMaster).anchor)
        val remap = actions.filterIsInstance<RecurrencePlanAction.RemapException>().single()
        assertEquals(SeriesTarget.EXISTING, remap.target)
        assertEquals(moved, remap.exception)
        assertEquals(RecurrenceKey.Timed(Instant.parse("2026-11-19T10:00:00Z")), remap.newOriginal)
        assertEquals(EventPatch(), remap.selectedPatch)
    }

    @Test fun followingDefaultsToOriginalBoundaryInsteadOfMovedActualDate() {
        val moved = SeriesException(ref(3, 23, 0), key(3))
        val source = snapshot().copy(exceptions = listOf(moved))
        val intent = RecurrenceEditIntent.FollowingSeriesEdit(key(3), fields,
            rule = RecurrenceRuleChanges(pattern = FieldChange.Set(RecurrencePattern.Weekly(setOf(DayOfWeek.FRIDAY)))))
        val actions = ready(plan(source, moved.ref, intent))
        val tail = actions[1] as RecurrencePlanAction.InsertTail
        assertEquals(LocalDate.of(2026, 11, 20), tail.start.date)
        val remap = actions.filterIsInstance<RecurrencePlanAction.RemapException>().single()
        assertEquals(moved.ref, remap.exception.ref)
        assertEquals(fields, remap.selectedPatch)
    }

    @Test fun explicitlyEditedSelectedOverrideTimeIsSeparateFromTheTailAnchor() {
        val moved = SeriesException(ref(3, 23, 0), key(3))
        val source = snapshot().copy(exceptions = listOf(moved))
        val newStart = RecurrenceStart.Timed(LocalDateTime.parse("2026-11-20T10:00:00"), ZoneOffset.UTC)
        val selectedTime = EventTime.Timed(Instant.parse("2026-11-21T12:00:00Z"), Instant.parse("2026-11-21T13:00:00Z"))
        val intent = RecurrenceEditIntent.FollowingSeriesEdit(key(3), fields, FieldChange.Set(newStart),
            selectedExceptionPatch = EventPatch(time = FieldChange.Set(selectedTime)))
        val actions = ready(plan(source, moved.ref, intent))
        assertEquals(newStart, (actions[1] as RecurrencePlanAction.InsertTail).start)
        val remap = actions.filterIsInstance<RecurrencePlanAction.RemapException>().single()
        assertEquals(fields.title, remap.selectedPatch.title)
        assertEquals(FieldChange.Set(selectedTime), remap.selectedPatch.time)
        assertEquals(moved.ref, remap.exception.ref)
    }

    @Test fun exactUntilIsPreservedWhenAnchorZoneChanges() {
        val source = snapshot("FREQ=DAILY;UNTIL=20261130T120000Z")
        val newStart = RecurrenceStart.Timed(LocalDateTime.parse("2026-11-17T09:00:00"), ZoneId.of("America/New_York"))
        val action = ready(plan(source, ref(3), RecurrenceEditIntent.WholeSeriesEdit(start,
            newMasterStart = FieldChange.Set(newStart)))).first() as RecurrencePlanAction.UpdateMaster
        assertTrue(value(action.rule).endsWith("UNTIL=20261130T120000Z"))
    }

    @Test fun weeklyTimeChangeAcrossDstRemapsOriginalsUsingLocalPatternOrdinals() {
        val zone = ZoneId.of("Europe/London")
        val oldStart = RecurrenceStart.Timed(LocalDateTime.parse("2026-03-22T09:00:00"), zone)
        val original = Instant.parse("2026-03-29T08:00:00Z")
        val selected = EventRef(identity.calendarId, 10, original, original.plusSeconds(3600), RecurrenceRef(10, original))
        val source = snapshot("FREQ=WEEKLY;BYDAY=SU;COUNT=4").copy(start = oldStart,
            exDatesRaw = "20260405T080000Z", exDatesDecoded = true,
            exDates = setOf(RecurrenceKey.Timed(Instant.parse("2026-04-05T08:00:00Z"))))
        val newStart = RecurrenceStart.Timed(LocalDateTime.parse("2026-03-29T10:00:00"), zone)
        val intent = RecurrenceEditIntent.FollowingSeriesEdit(RecurrenceKey.Timed(original), newTailStart = FieldChange.Set(newStart))
        val actions = ready(plan(source, selected, intent))
        val tail = actions[1] as RecurrencePlanAction.InsertTail
        assertEquals(newStart, tail.start)
        assertEquals(setOf(RecurrenceKey.Timed(Instant.parse("2026-04-05T09:00:00Z"))), tail.exclusions)
        assertTrue(tail.rule.contains("COUNT=3"))
    }

    @Test fun dirtyPatternDoesNotResetOriginalCountToNever() {
        val source = snapshot()
        val intent = RecurrenceEditIntent.FollowingSeriesEdit(key(3), rule = RecurrenceRuleChanges(
            pattern = FieldChange.Set(RecurrencePattern.Weekly(setOf(DayOfWeek.FRIDAY)))))
        val tail = ready(plan(source, ref(3), intent))[1] as RecurrencePlanAction.InsertTail
        assertTrue(tail.rule.contains("COUNT=3"))
        val never = intent.copy(rule = intent.rule.copy(end = FieldChange.Set(RecurrenceEnd.Never)))
        val unlimited = ready(plan(source, ref(3), never))[1] as RecurrencePlanAction.InsertTail
        assertFalse(unlimited.rule.contains("COUNT="))
        assertEquals(5, RecurrenceRules.preview(unlimited.rule, unlimited.start, 5).size)
    }

    @Test fun shorterExplicitCountCannotSilentlyDropAnOverride() {
        val source = snapshot().copy(exceptions = listOf(SeriesException(ref(5, 25), key(5))))
        val intent = RecurrenceEditIntent.FollowingSeriesEdit(key(3), rule = RecurrenceRuleChanges(end = FieldChange.Set(RecurrenceEnd.Count(2))))
        assertTrue(plan(source, ref(3), intent) is RecurrencePlanningResult.NeedsReview)
    }

    @Test fun shorterExplicitUntilCannotSilentlyDropAnExclusion() {
        val source = snapshot().copy(exDatesRaw = "20261122T090000Z", exDates = setOf(key(5)), exDatesDecoded = true)
        val intent = RecurrenceEditIntent.WholeSeriesEdit(start, rule = RecurrenceRuleChanges(
            end = FieldChange.Set(RecurrenceEnd.ExactUntil(Instant.parse("2026-11-20T09:00:00Z")))))
        assertTrue(plan(source, ref(3), intent) is RecurrencePlanningResult.NeedsReview)
    }

    @Test fun allDayMonthlyRemappingKeepsDateKeysAndNominalDuration() {
        val first = RecurrenceStart.AllDay(LocalDate.of(2026, 1, 31))
        val boundary = LocalDate.of(2026, 5, 31)
        val key = RecurrenceKey.Date(boundary)
        val original = boundary.atStartOfDay().toInstant(ZoneOffset.UTC)
        val selected = EventRef(identity.calendarId, 10, original, original.plusSeconds(86400), RecurrenceRef(10, original))
        val oldExcluded = RecurrenceKey.Date(LocalDate.of(2026, 7, 31))
        val source = snapshot("FREQ=MONTHLY;BYMONTHDAY=31;COUNT=5").copy(start = first, durationRaw = "P1D",
            exDatesRaw = "20260731", exDates = setOf(oldExcluded), exDatesDecoded = true)
        val newStart = RecurrenceStart.AllDay(LocalDate.of(2026, 6, 1))
        val intent = RecurrenceEditIntent.FollowingSeriesEdit(key, newTailStart = FieldChange.Set(newStart),
            rule = RecurrenceRuleChanges(pattern = FieldChange.Set(RecurrencePattern.MonthlyDate(1))))
        val actions = ready(plan(source, selected, intent))
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=31;COUNT=2", value((actions[0] as RecurrencePlanAction.UpdateMaster).rule))
        val tail = actions[1] as RecurrencePlanAction.InsertTail
        assertEquals(setOf(RecurrenceKey.Date(LocalDate.of(2026, 7, 1))), tail.exclusions)
        assertTrue(tail.rule.contains("COUNT=3"))
        assertEquals(RecurrenceDurationChange.KeepRaw, tail.duration)
    }

    @Test fun durationChangesAreExplicitAndDoNotReinterpretKeptRawDuration() {
        val source = snapshot().copy(durationRaw = "P1D")
        val newStart = RecurrenceStart.Timed(LocalDateTime.parse("2026-11-17T10:00:00"), ZoneOffset.UTC)
        val kept = ready(plan(source, ref(3), RecurrenceEditIntent.WholeSeriesEdit(start,
            newMasterStart = FieldChange.Set(newStart)))).first() as RecurrencePlanAction.UpdateMaster
        assertEquals(RecurrenceDurationChange.KeepRaw, kept.duration)
        val explicit = RecurrenceDurationChange.TimedElapsed(Duration.ofHours(24))
        val changed = ready(plan(source, ref(3), RecurrenceEditIntent.WholeSeriesEdit(start, duration = explicit)))
            .first() as RecurrencePlanAction.UpdateMaster
        assertEquals(explicit, changed.duration)
    }

    @Test fun firstOccurrenceFollowingEditUpdatesExistingMasterWithoutCountZero() {
        val intent = RecurrenceEditIntent.FollowingSeriesEdit(key(0), rule = RecurrenceRuleChanges(interval = FieldChange.Set(2)))
        val actions = ready(plan(snapshot(), ref(0), intent))
        assertTrue(actions.single() is RecurrencePlanAction.UpdateMaster)
        assertTrue(value((actions.single() as RecurrencePlanAction.UpdateMaster).rule).contains("COUNT=6"))
    }

    @Test fun scopeContextAndLegacyTimePatchCannotChooseWrongMasterDates() {
        val wrongContext = RecurrenceEditIntent.WholeSeriesEdit(start.copy(localDateTime = start.localDateTime.plusDays(3)))
        assertTrue(plan(snapshot(), ref(3), wrongContext) is RecurrencePlanningResult.NeedsReview)
        val wrongBoundary = RecurrenceEditIntent.FollowingSeriesEdit(key(4))
        assertTrue(plan(snapshot(), ref(3), wrongBoundary) is RecurrencePlanningResult.NeedsReview)
        val legacy = RecurrenceEditIntent.WholeSeriesEdit(start,
            fields = EventPatch(time = FieldChange.Set(EventTime.Timed(ref(3).instanceStart, ref(3).instanceEnd))))
        assertTrue(plan(snapshot(), ref(3), legacy) is RecurrencePlanningResult.Invalid)
    }

    @Test fun unrelatedFieldEditPreservesUnsupportedRawRuleWithoutOrdinalRebuild() {
        val raw = "FREQ=DAILY;BYHOUR=9,10"
        val action = ready(plan(snapshot(raw), ref(3), RecurrenceEditIntent.WholeSeriesEdit(start, fields))).single()
            as RecurrencePlanAction.UpdateMaster
        assertEquals(FieldChange.Keep, action.rule)
        assertEquals(FieldChange.Keep, action.anchor)
    }

    @Test fun rdateExruleAndUnsupportedPatternTransformsNeverProducePartialActions() {
        val intent = RecurrenceEditIntent.WholeSeriesEdit(start, rule = RecurrenceRuleChanges(interval = FieldChange.Set(2)))
        for (source in listOf(snapshot().copy(rDatesRaw = "20261120T100000Z"), snapshot().copy(exRuleRaw = "FREQ=WEEKLY;BYDAY=SU"),
            snapshot("FREQ=DAILY;BYHOUR=9,10"))) {
            assertTrue(plan(source, ref(3), intent) is RecurrencePlanningResult.Unsupported)
        }
    }

    @Test fun unmappableOrphanAndIncompleteExdateDecodeRequireReview() {
        val intent = RecurrenceEditIntent.WholeSeriesEdit(start, rule = RecurrenceRuleChanges(interval = FieldChange.Set(2)))
        val orphan = snapshot().copy(exDatesRaw = "20261130T090000Z", exDates = setOf(key(13)), exDatesDecoded = true)
        assertTrue(plan(orphan, ref(3), intent) is RecurrencePlanningResult.NeedsReview)
        assertTrue(plan(snapshot().copy(exDatesRaw = "unknown dates"), ref(3), intent) is RecurrencePlanningResult.NeedsReview)
    }

    @Test fun finiteBudgetExhaustionAndOutOfRfcRangeNeverReturnPartialActions() {
        val source = snapshot("FREQ=DAILY").copy(exceptions = listOf(SeriesException(ref(100, 100), key(100))))
        val intent = RecurrenceEditIntent.WholeSeriesEdit(start, rule = RecurrenceRuleChanges(interval = FieldChange.Set(2)))
        assertTrue(plan(source, ref(3), intent, RecurrencePlanningBudget(maxInstances = 10)) is RecurrencePlanningResult.NeedsReview)
        val endOfRange = RecurrenceStart.Timed(LocalDateTime.parse("9999-12-29T09:00:00"), ZoneOffset.UTC)
        val withExclusion = snapshot().copy(exDatesRaw = "20261122T090000Z", exDates = setOf(key(5)), exDatesDecoded = true)
        assertTrue(plan(withExclusion, ref(3), RecurrenceEditIntent.WholeSeriesEdit(start,
            newMasterStart = FieldChange.Set(endOfRange))) is RecurrencePlanningResult.NeedsReview)
    }

    @Test fun invalidDurationKindIntervalAndConflictingOverridePatchAreRejected() {
        assertTrue(plan(snapshot(), ref(3), RecurrenceEditIntent.WholeSeriesEdit(start,
            duration = RecurrenceDurationChange.AllDayDays(1))) is RecurrencePlanningResult.Invalid)
        assertTrue(plan(snapshot(), ref(3), RecurrenceEditIntent.WholeSeriesEdit(start,
            rule = RecurrenceRuleChanges(interval = FieldChange.Set(0)))) is RecurrencePlanningResult.Invalid)
        val moved = SeriesException(ref(3, 23, 0), key(3))
        val conflict = RecurrenceEditIntent.FollowingSeriesEdit(key(3), fields,
            selectedExceptionPatch = EventPatch(title = FieldChange.Set("Conflicting title")))
        assertTrue(plan(snapshot().copy(exceptions = listOf(moved)), moved.ref, conflict) is RecurrencePlanningResult.Invalid)
    }

    @Test fun timedAllDayConversionRequiresAdditionalReview() {
        val intent = RecurrenceEditIntent.WholeSeriesEdit(start,
            newMasterStart = FieldChange.Set(RecurrenceStart.AllDay(start.date)))
        assertTrue(plan(snapshot(), ref(3), intent) is RecurrencePlanningResult.NeedsReview)
    }

    @Test fun durationArithmeticOverflowIsRejectedBeforeAnyAction() {
        val huge = RecurrenceDurationChange.TimedElapsed(Duration.ofSeconds(Long.MAX_VALUE))
        assertTrue(plan(snapshot(), ref(3), RecurrenceEditIntent.WholeSeriesEdit(start, duration = huge)) is RecurrencePlanningResult.Invalid)
        val date = LocalDate.of(2026, 11, 17)
        val allDay = RecurrenceStart.AllDay(date)
        val instant = date.atStartOfDay().toInstant(ZoneOffset.UTC)
        val selected = EventRef(identity.calendarId, 10, instant, instant.plusSeconds(86400), RecurrenceRef(10, instant))
        val source = snapshot().copy(start = allDay, durationRaw = "P1D")
        assertTrue(plan(source, selected, RecurrenceEditIntent.WholeSeriesEdit(allDay,
            duration = RecurrenceDurationChange.AllDayDays(Long.MAX_VALUE))) is RecurrencePlanningResult.Invalid)
    }

    @Test fun noSelectedOverridePatchCanBeAppliedToAnOrdinaryOccurrence() {
        val intent = RecurrenceEditIntent.FollowingSeriesEdit(key(3),
            selectedExceptionPatch = EventPatch(description = FieldChange.Set("Override-only edit")))
        assertTrue(plan(snapshot(), ref(3), intent) is RecurrencePlanningResult.Invalid)
    }

    @Test fun freshSelectionProofIsRequiredAndRepeatedPlansAreIdentical() {
        val intent = RecurrenceEditIntent.WholeSeriesEdit(start, rule = RecurrenceRuleChanges(interval = FieldChange.Set(2)))
        assertTrue(RecurrenceEditing.plan(snapshot(), ref(3), intent) is RecurrencePlanningResult.NeedsReview)
        assertEquals(plan(snapshot(), ref(3), intent), plan(snapshot(), ref(3), intent))
    }

    @Test fun followingMetadataChangesReachSelectedOverrideIncludingExplicitNull() {
        val moved = SeriesException(ref(3, 23, 0), key(3))
        val source = snapshot().copy(exceptions = listOf(moved))
        for (availability in listOf<Int?>(null, 0, 1, 2)) for (privacy in listOf<Int?>(null, 0, 2, 3)) {
            val patch = EventPatch(availability = FieldChange.Set(availability), privacy = FieldChange.Set(privacy))
            val intent = RecurrenceEditIntent.FollowingSeriesEdit(key(3), patch,
                rule = RecurrenceRuleChanges(interval = FieldChange.Set(2)))
            val actions = ready(plan(source, moved.ref, intent))
            assertEquals(patch, (actions[1] as RecurrencePlanAction.InsertTail).patch)
            val selected = actions.filterIsInstance<RecurrencePlanAction.RemapException>().single()
            assertEquals(patch, selected.selectedPatch)
            assertEquals(moved, selected.exception)
            val prefix = actions[0] as RecurrencePlanAction.UpdateMaster
            assertEquals(FieldChange.Keep, prefix.patch.availability)
            assertEquals(FieldChange.Keep, prefix.patch.privacy)
        }
    }

    @Test fun overrideOnlyMetadataDoesNotAlterOtherFutureOccurrences() {
        val moved = SeriesException(ref(3, 23, 0), key(3))
        val source = snapshot().copy(exceptions = listOf(moved))
        for (availability in listOf<Int?>(null, 0, 1)) for (privacy in listOf<Int?>(null, 0, 2)) {
            val patch = EventPatch(availability = FieldChange.Set(availability), privacy = FieldChange.Set(privacy))
            val intent = RecurrenceEditIntent.FollowingSeriesEdit(key(3),
                rule = RecurrenceRuleChanges(interval = FieldChange.Set(2)), selectedExceptionPatch = patch)
            val actions = ready(plan(source, moved.ref, intent))
            assertEquals(EventPatch(), (actions[1] as RecurrencePlanAction.InsertTail).patch)
            assertEquals(patch, actions.filterIsInstance<RecurrencePlanAction.RemapException>().single().selectedPatch)
        }
    }

    @Test fun metadataKeepCombinesWithExplicitValuesWithoutLosingEitherField() {
        val moved = SeriesException(ref(3, 23, 0), key(3))
        val source = snapshot().copy(exceptions = listOf(moved))
        val shared = EventPatch(availability = FieldChange.Set(1))
        val extra = EventPatch(privacy = FieldChange.Set<Int?>(null))
        val intent = RecurrenceEditIntent.FollowingSeriesEdit(key(3), shared,
            rule = RecurrenceRuleChanges(interval = FieldChange.Set(2)), selectedExceptionPatch = extra)
        val selected = ready(plan(source, moved.ref, intent)).filterIsInstance<RecurrencePlanAction.RemapException>().single()
        assertEquals(shared.availability, selected.selectedPatch.availability)
        assertEquals(extra.privacy, selected.selectedPatch.privacy)
        val identical = intent.copy(selectedExceptionPatch = shared)
        assertTrue(plan(source, moved.ref, identical) is RecurrencePlanningResult.Ready)
    }

    @Test fun conflictingNullableMetadataNeverProducesAnExecutablePlan() {
        val moved = SeriesException(ref(3, 23, 0), key(3))
        val source = snapshot().copy(exceptions = listOf(moved))
        for ((shared, extra) in listOf<Int?>(null, 0, 1).flatMap { a -> listOf<Int?>(null, 0, 1).map { b -> a to b } }.filter { it.first != it.second }) {
            val availabilityConflict = RecurrenceEditIntent.FollowingSeriesEdit(key(3),
                fields = EventPatch(availability = FieldChange.Set(shared)),
                rule = RecurrenceRuleChanges(interval = FieldChange.Set(2)),
                selectedExceptionPatch = EventPatch(availability = FieldChange.Set(extra)))
            assertTrue(plan(source, moved.ref, availabilityConflict) is RecurrencePlanningResult.Invalid)
            val privacyConflict = availabilityConflict.copy(fields = EventPatch(privacy = FieldChange.Set(shared)),
                selectedExceptionPatch = EventPatch(privacy = FieldChange.Set(extra)))
            assertTrue(plan(source, moved.ref, privacyConflict) is RecurrencePlanningResult.Invalid)
        }
    }

    @Test fun allSeriesMetadataEditsRetainEveryExplicitOverride() {
        val moved = SeriesException(ref(3, 23, 0), key(3))
        val other = SeriesException(ref(4, 24, 1), key(4))
        val source = snapshot().copy(exceptions = listOf(moved, other))
        val patch = EventPatch(availability = FieldChange.Set(0), privacy = FieldChange.Set<Int?>(null))
        val intent = RecurrenceEditIntent.WholeSeriesEdit(start, patch,
            rule = RecurrenceRuleChanges(interval = FieldChange.Set(2)))
        val actions = ready(plan(source, moved.ref, intent))
        assertEquals(patch, (actions.first() as RecurrencePlanAction.UpdateMaster).patch)
        val remaps = actions.filterIsInstance<RecurrencePlanAction.RemapException>()
        assertEquals(listOf(moved, other), remaps.map { it.exception })
        assertTrue(remaps.all { it.selectedPatch == EventPatch() })
    }
}
