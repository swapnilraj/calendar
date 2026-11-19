package dev.swapnilraj.calendar.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class RecurrenceGraphProofTest {
    private val identity = SeriesIdentity(CalendarId(2), 10)
    private val seed = Instant.parse("2026-11-17T09:00:00Z")
    private val start = RecurrenceStart.Timed(LocalDateTime.ofInstant(seed, ZoneOffset.UTC), ZoneOffset.UTC)
    private fun key(index: Int) = RecurrenceKey.Timed(seed.plusSeconds(index * 86_400L))
    private fun ref(index: Int, id: Long = 10, actual: Int = index) = EventRef(identity.calendarId, id,
        seed.plusSeconds(actual * 86_400L), seed.plusSeconds(actual * 86_400L + 3_600), RecurrenceRef(10, key(index).instant))
    private val past get() = SeriesException(ref(1, 21, 5), key(1))
    private val moved get() = SeriesException(ref(4, 24, 0), key(4))
    private val cancelled get() = SeriesException(ref(5, 25), key(5), cancelled = true)
    private fun source(selected: EventRef = ref(3)) = RecurrenceSeriesSnapshot(identity, start, "FREQ=DAILY;COUNT=6",
        durationRaw = "PT1H", exDatesRaw = "20261118T090000Z,20261122T090000Z", exDates = linkedSetOf(key(1), key(5)),
        exDatesDecoded = true, exceptions = listOf(past, moved, cancelled), coverage = SeriesCoverage.COMPLETE,
        validatedSelection = selected)
    private fun following() = RecurrenceGraphRequest.Scoped(RecurrenceScope.FOLLOWING,
        RecurrenceMutation.Edit(EventPatch(title = FieldChange.Set("Changed master"))))
    private fun computed(s: RecurrenceSeriesSnapshot, r: RecurrenceGraphRequest,
        budget: RecurrencePlanningBudget = RecurrencePlanningBudget()): RecurrencePlanningResult.Ready = when (r) {
        is RecurrenceGraphRequest.Scoped -> RecurrencePlanning.plan(s, requireNotNull(s.validatedSelection), r.scope, r.mutation, budget)
        is RecurrenceGraphRequest.Edited -> RecurrenceEditing.plan(s, requireNotNull(s.validatedSelection), r.intent, budget)
    } as RecurrencePlanningResult.Ready
    private fun result(s: RecurrenceSeriesSnapshot, r: RecurrenceGraphRequest, p: RecurrencePlanningResult.Ready = computed(s, r),
        budget: RecurrencePlanningBudget = RecurrencePlanningBudget()) = RecurrenceGraphProof.prove(s, requireNotNull(s.validatedSelection), r, p, budget)
    private fun complete(s: RecurrenceSeriesSnapshot, r: RecurrenceGraphRequest, p: RecurrencePlanningResult.Ready = computed(s, r)) =
        (result(s, r, p) as RecurrenceGraphProofResult.Complete).proof
    private fun review(problem: RecurrenceGraphProblem, result: RecurrenceGraphProofResult) =
        assertEquals(RecurrenceGraphProofResult.NeedsReview(problem), result)

    @Test fun followingPartitionUsesOriginalsAndConservesMovedAndCancelledActualRows() {
        val s = source(); val p = computed(s, following()); val proof = complete(s, following(), p)
        assertEquals(p, proof.plan)
        assertEquals(RecurrenceExceptionDisposition.Retain(past), proof.exceptions[0])
        assertEquals(RecurrenceExceptionDisposition.Rebind(moved, SeriesTarget.TAIL, key(4)), proof.exceptions[1])
        assertEquals(RecurrenceExceptionDisposition.Rebind(cancelled, SeriesTarget.TAIL, key(5)), proof.exceptions[2])
        assertEquals(seed, proof.exceptions[1].source.ref.instanceStart)
        assertTrue(proof.exceptions[2].source.cancelled)
        val ex = proof.exclusions as RecurrenceExclusionProof.Transform
        assertEquals(listOf(RecurrenceExclusionMapping(key(1), SeriesTarget.EXISTING, key(1)),
            RecurrenceExclusionMapping(key(5), SeriesTarget.TAIL, key(5))), ex.mappings)
        assertEquals(s.exDatesRaw, ex.sourceRaw)
    }

    @Test fun omittingEitherMovedOrCancelledFutureTransferNeverBecomesImplicitRetention() {
        val s = source(); val r = following(); val p = computed(s, r)
        for (omitted in listOf(moved, cancelled)) review(RecurrenceGraphProblem.MISSING_EXCEPTION_ACTION,
            result(s, r, p.copy(actions = p.actions.filterNot { it is RecurrencePlanAction.RebindExceptionToTail && it.exception == omitted })))
    }

    @Test fun duplicateDispositionAndForeignActualRefOrCancellationAreRejected() {
        val s = source(); val r = following(); val p = computed(s, r)
        val action = p.actions.filterIsInstance<RecurrencePlanAction.RebindExceptionToTail>().first()
        review(RecurrenceGraphProblem.DUPLICATE_EXCEPTION_ACTION, result(s, r, p.copy(actions = p.actions + action)))
        for (foreign in listOf(action.exception.copy(cancelled = true), action.exception.copy(ref = action.exception.ref.copy(instanceEnd = seed.plusSeconds(7_200))))) {
            review(RecurrenceGraphProblem.FOREIGN_EXCEPTION,
                result(s, r, p.copy(actions = p.actions.map { if (it == action) action.copy(exception = foreign) else it })))
        }
    }

    @Test fun tailReferenceCannotSurviveMissingParentAndPastRowsCannotBeTransferredSilently() {
        val s = source(); val r = following(); val p = computed(s, r)
        review(RecurrenceGraphProblem.TARGET_PARENT_MISSING, result(s, r,
            p.copy(actions = p.actions.filterNot { it is RecurrencePlanAction.InsertTail })))
        review(RecurrenceGraphProblem.PLAN_MISMATCH,
            result(s, r, p.copy(actions = p.actions + RecurrencePlanAction.RebindExceptionToTail(past))))
    }

    private fun transformed() = RecurrenceGraphRequest.Edited(RecurrenceEditIntent.FollowingSeriesEdit(key(3),
        newTailStart = FieldChange.Set(RecurrenceStart.Timed(LocalDateTime.parse("2026-11-23T15:00:00"), ZoneOffset.UTC)),
        rule = RecurrenceRuleChanges(pattern = FieldChange.Set(RecurrencePattern.Weekly(setOf(DayOfWeek.MONDAY))))))

    @Test fun transformedTailHasTotalOrdinalMappingsWithoutMovingActualOverrides() {
        val s = source(); val r = transformed(); val proof = complete(s, r)
        val mappings = proof.exceptions.filterIsInstance<RecurrenceExceptionDisposition.Remap>()
        assertEquals(listOf(moved, cancelled), mappings.map { it.source })
        assertEquals(listOf("2026-11-30T15:00:00Z", "2026-12-07T15:00:00Z"), mappings.map { (it.original as RecurrenceKey.Timed).instant.toString() })
        assertEquals(moved.ref, mappings[0].source.ref); assertTrue(mappings[1].source.cancelled)
        val ex = (proof.exclusions as RecurrenceExclusionProof.Transform).mappings
        assertEquals(RecurrenceExclusionMapping(key(1), SeriesTarget.EXISTING, key(1)), ex[0])
        assertEquals(RecurrenceExclusionMapping(key(5), SeriesTarget.TAIL,
            RecurrenceKey.Timed(Instant.parse("2026-12-07T15:00:00Z"))), ex[1])
    }

    @Test fun targetCollisionCannotCollapseTwoOverridesOrHideAgainstARetainedRow() {
        val s = source(); val r = transformed(); val p = computed(s, r)
        val first = p.actions.filterIsInstance<RecurrencePlanAction.RemapException>().first()
        val second = p.actions.filterIsInstance<RecurrencePlanAction.RemapException>().last()
        review(RecurrenceGraphProblem.TARGET_ORIGINAL_COLLISION, result(s, r,
            p.copy(actions = p.actions.map { if (it == second) second.copy(newOriginal = first.newOriginal) else it })))
        review(RecurrenceGraphProblem.TARGET_ORIGINAL_COLLISION, result(s, r,
            p.copy(actions = p.actions.map { if (it == first) first.copy(target = SeriesTarget.EXISTING, newOriginal = past.original) else it })))
    }

    @Test fun changingParentOrDroppingAnExclusionCannotPassRecomputedPlan() {
        val s = source(); val r = transformed(); val p = computed(s, r)
        val first = p.actions.filterIsInstance<RecurrencePlanAction.RemapException>().first()
        review(RecurrenceGraphProblem.PLAN_MISMATCH, result(s, r,
            p.copy(actions = p.actions.map { if (it == first) first.copy(target = SeriesTarget.EXISTING) else it })))
        val tail = p.actions.filterIsInstance<RecurrencePlanAction.InsertTail>().single()
        review(RecurrenceGraphProblem.PLAN_MISMATCH, result(s, r,
            p.copy(actions = p.actions.map { if (it == tail) tail.copy(exclusions = emptySet()) else it })))
    }

    @Test fun duplicateOrForeignSourceGraphAndIncompleteCoverageNeverProducePartialProof() {
        val s = source(); val r = following(); val p = computed(s, r)
        review(RecurrenceGraphProblem.INCOMPLETE_SOURCE, result(s.copy(coverage = SeriesCoverage.INCOMPLETE), r, p))
        for (bad in listOf(s.copy(exceptions = s.exceptions + moved),
            s.copy(exceptions = listOf(moved, moved.copy(ref = ref(4, 99)))),
            s.copy(exceptions = listOf(moved.copy(ref = moved.ref.copy(calendarId = CalendarId(8))))))) {
            review(RecurrenceGraphProblem.SOURCE_GRAPH_INVALID, result(bad, r, p))
        }
        review(RecurrenceGraphProblem.INVALID_SELECTION,
            RecurrenceGraphProof.prove(s, ref(2), r, p))
    }

    @Test fun opaqueDateSetsArePreservedExactlyOnlyWhenUnchanged() {
        val s = source().copy(ruleRaw = "FREQ=DAILY;BYHOUR=9,10", exDatesRaw = "opaque exclusion", exDates = emptySet(),
            exDatesDecoded = false, rDatesRaw = "opaque inclusion", exRuleRaw = "opaque exclusion rule")
        val r = RecurrenceGraphRequest.Scoped(RecurrenceScope.ALL, RecurrenceMutation.Edit(EventPatch(title = FieldChange.Set("Metadata"))))
        val proof = complete(s, r)
        assertEquals(s.rDatesRaw, proof.rawDates.rDatesRaw); assertEquals(s.exRuleRaw, proof.rawDates.exRuleRaw)
        assertEquals(RecurrenceExclusionProof.PreserveRaw(s.exDatesRaw, false, emptySet()), proof.exclusions)
        assertTrue(proof.exceptions.all { it is RecurrenceExceptionDisposition.Retain })
    }

    @Test fun rdateTransformRequiresTypedReviewInsteadOfInventingRuleOrdinals() {
        val s = source().copy(rDatesRaw = "20261201T090000Z")
        for (r in listOf(following(), transformed(), RecurrenceGraphRequest.Edited(RecurrenceEditIntent.WholeSeriesEdit(start,
            newMasterStart = FieldChange.Set(start.copy(localDateTime = start.localDateTime.plusHours(1))))))) {
            review(RecurrenceGraphProblem.RDATE_MAPPING_UNPROVED, result(s, r, RecurrencePlanningResult.Ready(identity, emptyList())))
        }
    }

    @Test fun falseDecodedExclusionClaimAndWrongPropertyNameCannotAuthorizeMapping() {
        val r = following()
        for (s in listOf(source().copy(exDatesRaw = "20261118T090000Z"),
            source().copy(exDatesRaw = "RDATE:20261118T090000Z,20261122T090000Z"))) {
            review(RecurrenceGraphProblem.EXCLUSION_MAPPING_UNPROVED, result(s, r))
        }
    }

    @Test fun duplicateRawExclusionTokensKeepTheirExactProvenance() {
        val s = source().copy(exDatesRaw = "20261118T090000Z,20261118T090000Z,20261122T090000Z")
        val proof = complete(s, following())
        assertEquals(s.exDatesRaw, proof.rawDates.exDatesRaw)
        assertEquals(2, (proof.exclusions as RecurrenceExclusionProof.Transform).mappings.size)
    }

    @Test fun followingDeletionAccountsForExclusionsWithNoFutureExceptionRows() {
        val s = source().copy(exceptions = listOf(past))
        val r = RecurrenceGraphRequest.Scoped(RecurrenceScope.FOLLOWING, RecurrenceMutation.Delete)
        val proof = complete(s, r)
        assertEquals(listOf(RecurrenceExceptionDisposition.Retain(past)), proof.exceptions)
        assertEquals(listOf(RecurrenceExclusionMapping(key(1), SeriesTarget.EXISTING, key(1)),
            RecurrenceExclusionMapping(key(5), null, null)), (proof.exclusions as RecurrenceExclusionProof.Transform).mappings)
    }

    @Test fun allDeletionRemovesEveryKnownExceptionAndExclusionOnlyUnderExplicitPlan() {
        val s = source(); val r = RecurrenceGraphRequest.Scoped(RecurrenceScope.ALL, RecurrenceMutation.Delete)
        val p = computed(s, r); val proof = complete(s, r, p)
        assertEquals(s.exceptions, proof.exceptions.map { (it as RecurrenceExceptionDisposition.Remove).source })
        assertTrue((proof.exclusions as RecurrenceExclusionProof.Transform).mappings.all { it.targetParent == null && it.targetOriginal == null })
        val deletion = p.actions.single() as RecurrencePlanAction.DeleteSeries
        review(RecurrenceGraphProblem.MISSING_EXCEPTION_ACTION, result(s, r,
            p.copy(actions = listOf(deletion.copy(exceptions = deletion.exceptions.dropLast(1))))))
    }

    @Test fun thisCancellationRetainsSourceEvidenceAndNeverUncancelsOtherRows() {
        val s = source(moved.ref); val r = RecurrenceGraphRequest.Scoped(RecurrenceScope.THIS, RecurrenceMutation.Delete)
        val proof = complete(s, r)
        val row = proof.exceptions[1] as RecurrenceExceptionDisposition.Retain
        assertEquals(moved, row.source); assertFalse(row.source.cancelled); assertEquals(FieldChange.Set(true), row.cancellation)
        assertEquals(RecurrenceExceptionDisposition.Retain(cancelled), proof.exceptions[2])
        assertTrue(proof.newExceptions.isEmpty())
    }

    @Test fun newThisExceptionNeverReplacesOrAllocatesAnExistingNativeIdentity() {
        val s = source(); val r = RecurrenceGraphRequest.Scoped(RecurrenceScope.THIS, RecurrenceMutation.Delete)
        val proof = complete(s, r)
        assertEquals(listOf(RecurrenceNewExceptionProof(SeriesTarget.EXISTING, key(3), EventPatch(), true)), proof.newExceptions)
        assertEquals(s.exceptions, proof.exceptions.map { (it as RecurrenceExceptionDisposition.Retain).source })
    }

    @Test fun countAndExactUntilPlansAreNotRecountedByExclusionsOrCancelledRows() {
        for (raw in listOf("FREQ=DAILY;COUNT=6", "FREQ=DAILY;UNTIL=20261122T090017Z;WKST=SU")) {
            val s = source().copy(ruleRaw = raw); val r = following(); val p = computed(s, r); val proof = complete(s, r, p)
            assertEquals(p, proof.plan)
            val prefix = proof.plan.actions[0] as RecurrencePlanAction.UpdateMaster
            val tail = proof.plan.actions[1] as RecurrencePlanAction.InsertTail
            if (raw.contains("COUNT")) {
                assertEquals(FieldChange.Set("FREQ=DAILY;COUNT=3"), prefix.rule)
                assertEquals("FREQ=DAILY;COUNT=3", tail.rule)
            } else assertEquals(raw, tail.rule)
        }
    }

    @Test fun ordinalZeroKeepsExistingParentAndDoesNotInventATail() {
        val s = source(ref(0)); val r = following(); val proof = complete(s, r)
        assertFalse(proof.plan.actions.any { it is RecurrencePlanAction.InsertTail })
        assertTrue(proof.exceptions.all { it is RecurrenceExceptionDisposition.Retain })
        assertTrue(proof.exclusions is RecurrenceExclusionProof.PreserveRaw)
    }

    @Test fun wholeTemporalMappingRetainsActualMovedBoundsAndMapsEveryExclusion() {
        val s = source(); val r = RecurrenceGraphRequest.Edited(RecurrenceEditIntent.WholeSeriesEdit(start,
            newMasterStart = FieldChange.Set(start.copy(localDateTime = start.localDateTime.plusHours(1)))))
        val proof = complete(s, r)
        assertEquals(s.exceptions, proof.exceptions.map { (it as RecurrenceExceptionDisposition.Remap).source })
        val ex = (proof.exclusions as RecurrenceExclusionProof.Transform).mappings
        assertEquals(listOf(key(1), key(5)), ex.map { it.source })
        assertEquals(listOf(key(1).instant.plusSeconds(3_600), key(5).instant.plusSeconds(3_600)), ex.map { (it.targetOriginal as RecurrenceKey.Timed).instant })
    }

    @Test fun exhaustionAndForgedCountEndNeverYieldPartialComplete() {
        val s = source(); val r = following(); val p = computed(s, r)
        review(RecurrenceGraphProblem.PLANNING_NOT_READY, result(s, r, p, RecurrencePlanningBudget(maxInstances = 2)))
        val tail = p.actions[1] as RecurrencePlanAction.InsertTail
        review(RecurrenceGraphProblem.PLAN_MISMATCH, result(s, r,
            p.copy(actions = p.actions.map { if (it == tail) tail.copy(rule = "FREQ=DAILY;COUNT=6") else it })))
    }

    @Test fun proofOwnsNestedActionsSourceSetsAndReminderListsAndRejectsOutputMutation() {
        val alerts = mutableListOf(5, 10); val nodes = source().exceptions.toMutableList(); val dates = source().exDates.toMutableSet()
        val s = source().copy(exceptions = nodes, exDates = dates)
        val r = RecurrenceGraphRequest.Scoped(RecurrenceScope.ALL,
            RecurrenceMutation.Edit(EventPatch(alertMinutes = FieldChange.Set(alerts))))
        val actions = computed(s, r).actions.toMutableList(); val proof = complete(s, r, RecurrencePlanningResult.Ready(identity, actions))
        alerts.clear(); nodes.clear(); dates.clear(); actions.clear()
        val patch = (proof.plan.actions.single() as RecurrencePlanAction.UpdateMaster).patch
        assertEquals(listOf(5, 10), (patch.alertMinutes as FieldChange.Set).value)
        assertEquals(3, proof.exceptions.size)
        assertEquals(setOf(key(1), key(5)), (proof.exclusions as RecurrenceExclusionProof.PreserveRaw).knownKeys)
        assertThrows(UnsupportedOperationException::class.java) { (proof.plan.actions as MutableList<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { ((patch.alertMinutes as FieldChange.Set).value as MutableList<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (proof.exceptions as MutableList<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { ((proof.exclusions as RecurrenceExclusionProof.PreserveRaw).knownKeys as MutableSet<*>).clear() }
    }

    @Test fun deletionProofSnapshotsTheNestedExceptionList() {
        val s = source(); val r = RecurrenceGraphRequest.Scoped(RecurrenceScope.ALL, RecurrenceMutation.Delete)
        val nodes = s.exceptions.toMutableList(); val p = RecurrencePlanningResult.Ready(identity, listOf(RecurrencePlanAction.DeleteSeries(nodes)))
        val proof = complete(s, r, p); nodes.clear()
        val captured = (proof.plan.actions.single() as RecurrencePlanAction.DeleteSeries).exceptions
        assertEquals(s.exceptions, captured)
        assertThrows(UnsupportedOperationException::class.java) { (captured as MutableList<*>).clear() }
    }

    @Test fun preservedNullAndEmptyRawDateValuesRemainDistinct() {
        val r = RecurrenceGraphRequest.Scoped(RecurrenceScope.ALL, RecurrenceMutation.Edit(EventPatch()))
        val a = complete(source().copy(exDatesRaw = null, exDates = emptySet(), rDatesRaw = null, exRuleRaw = null), r)
        val b = complete(source().copy(exDatesRaw = "", exDates = emptySet(), rDatesRaw = "", exRuleRaw = ""), r)
        assertNotEquals(a.rawDates, b.rawDates)
        assertNull(a.rawDates.exDatesRaw); assertEquals("", b.rawDates.exDatesRaw)
    }

    @Test fun allDayTransferUsesDateOriginalsAndRejectsNonMidnightIdentity() {
        val day = java.time.LocalDate.of(2026, 11, 17)
        fun at(index: Int) = day.plusDays(index.toLong()).atStartOfDay(ZoneOffset.UTC).toInstant()
        fun dayRef(index: Int, id: Long = 10, actual: Int = index) = EventRef(identity.calendarId, id,
            at(actual), at(actual + 1), RecurrenceRef(10, at(index)))
        val override = SeriesException(dayRef(2, 22, 0), RecurrenceKey.Date(day.plusDays(2)))
        val cancel = SeriesException(dayRef(3, 23), RecurrenceKey.Date(day.plusDays(3)), true)
        val s = RecurrenceSeriesSnapshot(identity, RecurrenceStart.AllDay(day), "FREQ=DAILY;COUNT=4", "P1D",
            exDatesRaw = "20261120", exDates = setOf(cancel.original), exDatesDecoded = true,
            exceptions = listOf(override, cancel), coverage = SeriesCoverage.COMPLETE, validatedSelection = dayRef(1))
        val proof = complete(s, following())
        assertEquals(listOf(override, cancel), proof.exceptions.map { it.source })
        assertEquals(listOf(RecurrenceExclusionMapping(cancel.original, SeriesTarget.TAIL, cancel.original)),
            (proof.exclusions as RecurrenceExclusionProof.Transform).mappings)
        val bad = dayRef(1).copy(recurrence = RecurrenceRef(10, at(1).plusSeconds(1)))
        review(RecurrenceGraphProblem.SOURCE_GRAPH_INVALID,
            result(s.copy(validatedSelection = bad), following(), proof.plan))
    }

    @Test fun explicitSelectedOverrideDeltaStaysSeparateFromConservedActualBounds() {
        val s = source(moved.ref)
        val authored = EventPatch(time = FieldChange.Set(EventTime.Timed(seed.plusSeconds(7_200), seed.plusSeconds(10_800))))
        val r = RecurrenceGraphRequest.Edited(RecurrenceEditIntent.FollowingSeriesEdit(moved.original,
            fields = EventPatch(title = FieldChange.Set("Future title")), selectedExceptionPatch = authored))
        val proof = complete(s, r)
        val row = proof.exceptions.single { it.source == moved } as RecurrenceExceptionDisposition.Remap
        assertEquals(moved.ref, row.source.ref)
        assertEquals(authored.copy(title = FieldChange.Set("Future title")), row.explicitPatch)
        assertEquals(cancelled, proof.exceptions.single { it.source == cancelled }.source)
    }

    @Test fun whitespaceRdateIsStillUnprovedAndConflictingSelectedPatchesCannotBeAccepted() {
        val s = source().copy(rDatesRaw = " ")
        review(RecurrenceGraphProblem.RDATE_MAPPING_UNPROVED,
            result(s, following(), RecurrencePlanningResult.Ready(identity, emptyList())))
        val existing = source(moved.ref)
        val r = RecurrenceGraphRequest.Edited(RecurrenceEditIntent.FollowingSeriesEdit(moved.original,
            fields = EventPatch(title = FieldChange.Set("Base")),
            selectedExceptionPatch = EventPatch(title = FieldChange.Set("Conflicting override"))))
        review(RecurrenceGraphProblem.PLANNING_NOT_READY,
            result(existing, r, RecurrencePlanningResult.Ready(identity, emptyList())))
    }

}
