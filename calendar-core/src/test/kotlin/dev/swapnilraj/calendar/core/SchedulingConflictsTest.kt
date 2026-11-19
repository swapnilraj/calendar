package dev.swapnilraj.calendar.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class SchedulingConflictsTest {
    private val calendar = CalendarIdentity(CalendarId(1), CalendarAccount("synthetic", "local"), "owner", "provider")
    private val start = Instant.parse("2026-06-01T10:00:00Z")
    private fun timed(from: Long = 0, to: Long = 3600) = EventTime.Timed(start.plusSeconds(from), start.plusSeconds(to))
    private fun dates(from: String, to: String) = EventTime.AllDay(CalendarDateRange(LocalDate.parse(from), LocalDate.parse(to)))
    private fun context(
        time: EventTime = timed(), zone: String = "UTC", selection: Set<CalendarIdentity> = setOf(calendar),
        draft: ConflictDraftRevision = ConflictDraftRevision("draft", 1),
        source: ConflictSourceRevision = ConflictSourceRevision("source", 1), exclude: EventRef? = null,
    ) = ConflictContext(draft, source, time, ZoneId.of(zone), selection, exclude)
    private fun row(
        time: EventTime = timed(), id: Long = 1, owner: CalendarIdentity = calendar,
        availability: ConflictAvailability = ConflictAvailability.BUSY,
        status: ConflictEventStatus = ConflictEventStatus.ACTIVE,
        participation: ConflictParticipation = ConflictParticipation.ACCEPTED,
        recurrence: RecurrenceRef? = null,
    ): ConflictCandidate {
        val bounds = when (time) {
            is EventTime.Timed -> InstantBounds(time.start, time.end)
            is EventTime.AllDay -> time.dates.instantBounds(ZoneOffset.UTC)
        }
        return ConflictCandidate(owner, EventRef(owner.calendarId, id, bounds.start, bounds.endExclusive, recurrence),
            time, availability, status, participation)
    }
    private fun assess(
        current: ConflictContext = context(), rows: List<ConflictCandidate> = emptyList(),
        coverage: ConflictCoverage = ConflictCoverage.Complete, issued: ConflictContext = current,
    ) = SchedulingConflicts.assess(current, ConflictSnapshot(issued, rows, coverage))
    private fun result(current: ConflictContext = context(), rows: List<ConflictCandidate> = emptyList(),
        coverage: ConflictCoverage = ConflictCoverage.Complete) = assess(current, rows, coverage) as ConflictAssessment.Assessed
    private fun reject(actual: ConflictAssessment, problem: ConflictProblem) =
        assertEquals(ConflictAssessment.Rejected(problem), actual)

    @Test fun touchingTimedBoundariesDoNotOverlap() {
        assertTrue(result(rows = listOf(row(timed(-3600, 0)), row(timed(3600, 7200), 2))).canReportNoConflict)
    }
    @Test fun containmentAndEqualSpansBlock() {
        val rows = listOf(row(), row(timed(-1, 3601), 2), row(timed(1, 2), 3))
        assertEquals(rows.map { it.ref }, result(rows = rows).conflicts)
    }
    @Test fun candidatePointHasNoOccupancy() {
        assertTrue(result(rows = listOf(row(timed(1, 1)))).canReportNoConflict)
    }
    @Test fun proposedPointIsInvalid() {
        reject(assess(context(timed(1, 1))), ConflictProblem.INVALID_CONTEXT)
    }
    @Test fun exactMovedOccurrenceAloneIsExcluded() {
        val recurrence = RecurrenceRef(100, start.minusSeconds(86400))
        val first = row(recurrence = recurrence)
        val second = row(timed(1, 100), 2, recurrence = RecurrenceRef(100, start))
        assertEquals(listOf(second.ref), result(context(exclude = first.ref), listOf(first, second)).conflicts)
    }
    @Test fun sameOriginalWithDifferentActualBoundsIsNotExcluded() {
        val first = row(recurrence = RecurrenceRef(100, start))
        val moved = row(timed(10, 100), recurrence = first.ref.recurrence)
        assertEquals(listOf(moved.ref), result(context(exclude = first.ref), listOf(moved)).conflicts)
    }
    @Test fun foreignNamespaceWithSameNumericCalendarIdIsRejected() {
        reject(assess(rows = listOf(row(owner = calendar.copy(account = CalendarAccount("other", "local"))))),
            ConflictProblem.INVALID_CANDIDATE)
    }
    @Test fun dateRangesUseExclusiveDates() {
        val proposal = context(dates("2026-06-01", "2026-06-02"))
        assertTrue(result(proposal, listOf(row(dates("2026-06-02", "2026-06-03")))).canReportNoConflict)
        assertEquals(1, result(proposal, listOf(row(dates("2026-05-31", "2026-06-02")))).conflicts.size)
    }
    @Test fun mixedDatesUseExplicitPlanningZoneNotUtcEncoding() {
        val day = row(dates("2026-06-02", "2026-06-03"))
        val proposal = EventTime.Timed(Instant.parse("2026-06-01T12:30:00Z"), Instant.parse("2026-06-01T13:00:00Z"))
        assertEquals(1, result(context(proposal, "Pacific/Kiritimati"), listOf(day)).conflicts.size)
        assertTrue(result(context(proposal), listOf(day)).canReportNoConflict)
    }
    @Test fun springDayHasTwentyThreeHours() {
        val proposal = context(dates("2026-03-29", "2026-03-30"), "Europe/London")
        val last = row(EventTime.Timed(Instant.parse("2026-03-29T22:59:00Z"), Instant.parse("2026-03-29T23:00:00Z")))
        val next = row(EventTime.Timed(Instant.parse("2026-03-29T23:00:00Z"), Instant.parse("2026-03-29T23:01:00Z")), 2)
        assertEquals(listOf(last.ref), result(proposal, listOf(last, next)).conflicts)
    }
    @Test fun fallDayHasTwentyFiveHours() {
        val proposal = context(dates("2026-10-25", "2026-10-26"), "Europe/London")
        val first = row(EventTime.Timed(Instant.parse("2026-10-24T23:00:00Z"), Instant.parse("2026-10-24T23:01:00Z")))
        assertEquals(listOf(first.ref), result(proposal, listOf(first)).conflicts)
    }
    @Test fun skippedDateCannotCertifyClear() {
        val actual = assess(context(dates("2011-12-30", "2011-12-31"), "Pacific/Apia"))
        assertEquals(ConflictUnavailableReason.UNSUPPORTED_TIME_RANGE, (actual as ConflictAssessment.Unavailable).reason)
    }
    @Test fun midnightGapUsesDeterministicStartOfDayAndDateIntersection() {
        val day = dates("2018-11-04", "2018-11-05")
        val candidate = row(day)
        assertEquals(listOf(candidate.ref), result(context(day, "America/Sao_Paulo"), listOf(candidate)).conflicts)
        val proposal = EventTime.Timed(Instant.parse("2018-11-04T03:30:00Z"), Instant.parse("2018-11-04T04:00:00Z"))
        assertEquals(listOf(candidate.ref), result(context(proposal, "America/Sao_Paulo"), listOf(candidate)).conflicts)
    }
    @Test fun midnightFoldUsesDeterministicStartOfDayAndDateIntersection() {
        val day = dates("2026-11-01", "2026-11-02")
        val candidate = row(day)
        assertEquals(listOf(candidate.ref), result(context(day, "America/Havana"), listOf(candidate)).conflicts)
        val proposal = EventTime.Timed(Instant.parse("2026-11-01T04:30:00Z"), Instant.parse("2026-11-01T05:00:00Z"))
        assertEquals(listOf(candidate.ref), result(context(proposal, "America/Havana"), listOf(candidate)).conflicts)
    }
    @Test fun skippedCandidateHasEmptyMixedOccupancyButStillIntersectsDates() {
        val proposal = EventTime.Timed(Instant.parse("2011-12-31T12:00:00Z"), Instant.parse("2011-12-31T13:00:00Z"))
        val candidate = row(dates("2011-12-30", "2011-12-31"))
        assertTrue(result(context(proposal, "Pacific/Apia"), listOf(candidate)).canReportNoConflict)
        assertEquals(listOf(candidate.ref), result(context(dates("2011-12-29", "2011-12-31"), "Pacific/Apia"),
            listOf(candidate)).conflicts)
    }
    @Test fun knownNonblockingObservationsOverrideOtherUnknowns() {
        val unknown = row(availability = ConflictAvailability.UNKNOWN, status = ConflictEventStatus.UNKNOWN,
            participation = ConflictParticipation.UNKNOWN)
        for (candidate in listOf(unknown.copy(availability = ConflictAvailability.FREE),
            unknown.copy(status = ConflictEventStatus.CANCELLED), unknown.copy(participation = ConflictParticipation.DECLINED)))
            assertTrue(result(rows = listOf(candidate)).canReportNoConflict)
    }
    @Test fun invitedTentativeAndPersonalBusyRowsRemainIncluded() {
        for (participation in ConflictParticipation.entries.filter { it != ConflictParticipation.UNKNOWN && it != ConflictParticipation.DECLINED })
            for (availability in listOf(ConflictAvailability.BUSY, ConflictAvailability.TENTATIVE))
                assertEquals(1, result(rows = listOf(row(participation = participation, availability = availability))).conflicts.size)
    }
    @Test fun eachUnknownAxisRemainsPossibleAndNeverClear() {
        for (candidate in listOf(row(availability = ConflictAvailability.UNKNOWN), row(status = ConflictEventStatus.UNKNOWN),
            row(participation = ConflictParticipation.UNKNOWN))) {
            val actual = result(rows = listOf(candidate))
            assertEquals(listOf(candidate.ref), actual.possibleConflicts)
            assertTrue(actual.conflicts.isEmpty())
            assertFalse(actual.canReportNoConflict)
        }
    }
    @Test fun nonoverlappingUnknownDoesNotTaintWindow() {
        assertTrue(result(rows = listOf(row(timed(3600, 7200), availability = ConflictAvailability.UNKNOWN))).canReportNoConflict)
    }
    @Test fun completeEmptyReadAloneCanBeClear() { assertTrue(result().canReportNoConflict) }
    @Test fun partialEmptyReadCannotBeClearAndKeepsKnownConflicts() {
        val coverage = ConflictCoverage.Partial(setOf(ConflictCoverageProblem.TRUNCATED))
        assertFalse(result(coverage = coverage).canReportNoConflict)
        assertEquals(1, result(rows = listOf(row()), coverage = coverage).conflicts.size)
    }
    @Test fun unavailableReturnsNoRetainedRefs() {
        assertTrue(assess(rows = listOf(row()), coverage = ConflictCoverage.Unavailable(ConflictUnavailableReason.PERMISSION_REQUIRED))
            is ConflictAssessment.Unavailable)
    }
    @Test fun emptySelectionIsDistinctButDoesNotBypassStaleness() {
        val empty = context(selection = emptySet())
        assertTrue(assess(empty) is ConflictAssessment.NoCalendarsSelected)
        reject(assess(empty, issued = context()), ConflictProblem.STALE_CONTEXT)
    }
    @Test fun allContextDimensionsInvalidateOldResults() {
        val old = context()
        val changed = listOf(
            context(draft = ConflictDraftRevision("other", 1)), context(draft = ConflictDraftRevision("draft", 2)),
            context(source = ConflictSourceRevision("other", 1)), context(source = ConflictSourceRevision("source", 2)),
            context(time = timed(1, 3600)), context(time = dates("2026-06-01", "2026-06-02")), context(zone = "Europe/London"),
            context(selection = emptySet()), context(selection = setOf(calendar.copy(owner = "other"))),
            context(selection = setOf(calendar.copy(providerId = "other"))),
            context(selection = setOf(calendar.copy(account = CalendarAccount("other", "local")))), context(exclude = row().ref))
        for (current in changed) reject(assess(current, listOf(row()), issued = old), ConflictProblem.STALE_CONTEXT)
    }
    @Test fun malformedContextCannotBeRestoredByMatchingSnapshot() {
        for (current in listOf(context(draft = ConflictDraftRevision(" ", 1)), context(source = ConflictSourceRevision("source", -1)),
            context(selection = setOf(calendar, calendar.copy(owner = "other")))))
            reject(assess(current), ConflictProblem.INVALID_CONTEXT)
    }
    @Test fun malformedRowCannotHideBehindFreeOrExclusion() {
        val candidate = row().copy(time = timed(1, 3600), availability = ConflictAvailability.FREE)
        reject(assess(context(exclude = candidate.ref), listOf(candidate)), ConflictProblem.INVALID_CANDIDATE)
        reject(assess(rows = listOf(row().copy(ref = row().ref.copy(calendarId = CalendarId(2))))), ConflictProblem.INVALID_CANDIDATE)
    }
    @Test fun allDayRefMustUseExactUtcDateEncoding() {
        val candidate = row(dates("2026-06-01", "2026-06-02"))
        reject(assess(rows = listOf(candidate.copy(ref = candidate.ref.copy(instanceStart = candidate.ref.instanceStart.plusSeconds(1))))),
            ConflictProblem.INVALID_CANDIDATE)
    }
    @Test fun duplicateRefsNeverSilentlyCertifyClear() {
        val free = row(availability = ConflictAvailability.FREE)
        reject(assess(rows = listOf(free, free)), ConflictProblem.INVALID_CANDIDATE)
        reject(assess(rows = listOf(free, row())), ConflictProblem.INVALID_CANDIDATE)
    }
    @Test fun oversizedCandidateCollectionIsRejectedBeforeReadingContents() {
        var reads = 0
        val oversized = object : AbstractList<ConflictCandidate>() {
            override val size = 10001
            override fun get(index: Int): ConflictCandidate { reads++; error("Must not traverse") }
        }
        reject(assess(rows = oversized), ConflictProblem.LIMIT_EXCEEDED)
        assertEquals(0, reads)
    }
    @Test fun exactCandidateBudgetIsAccepted() {
        val rows = (1L..10000L).map { row(timed(3600, 7200), it) }
        assertTrue(result(rows = rows).canReportNoConflict)
    }
    @Test fun calendarBudgetAndTokenBoundAreEnforced() {
        val exact = (1L..256L).map { calendar.copy(calendarId = CalendarId(it)) }.toSet()
        assertTrue(result(context(selection = exact)).canReportNoConflict)
        reject(assess(context(selection = exact + calendar.copy(calendarId = CalendarId(257)))), ConflictProblem.LIMIT_EXCEEDED)
        reject(assess(context(draft = ConflictDraftRevision("x".repeat(129), 1))), ConflictProblem.INVALID_CONTEXT)
    }
    @Test fun contextsAndSnapshotsOwnTheirMutableCollections() {
        val selected = mutableSetOf(calendar)
        val current = context(selection = selected)
        val rows = mutableListOf(row())
        val reasons = mutableSetOf(ConflictCoverageProblem.READ_FAILED)
        val snapshot = ConflictSnapshot(current, rows, ConflictCoverage.Partial(reasons))
        selected.clear(); rows.clear(); reasons.clear()
        val actual = SchedulingConflicts.assess(current, snapshot) as ConflictAssessment.Assessed
        assertEquals(1, actual.conflicts.size)
        assertFalse(actual.canReportNoConflict)
        assertEquals(setOf(calendar), actual.context.calendars)
        assertEquals(setOf(ConflictCoverageProblem.READ_FAILED), (actual.coverage as ConflictCoverage.Partial).reasons)
    }
    @Test fun outputCollectionsCannotBeMutated() {
        val actual = result(rows = listOf(row()))
        try { (actual.conflicts as MutableList<EventRef>).clear(); fail("Mutable output") } catch (_: UnsupportedOperationException) { }
        try { (actual.context.calendars as MutableSet<CalendarIdentity>).clear(); fail("Mutable context") } catch (_: UnsupportedOperationException) { }
    }
    @Test fun emptyPartialCoverageIsNotCompleteInDisguise() {
        reject(assess(coverage = ConflictCoverage.Partial(emptySet())), ConflictProblem.INVALID_CONTEXT)
    }
}
