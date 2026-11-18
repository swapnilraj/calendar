package dev.swapnilraj.calendar.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class TimedGridPlanningTest {
    private val london = ZoneId.of("Europe/London")
    private fun target(date: String, minute: Double, zone: ZoneId = london, offset: ZoneOffset? = null) =
        GridTarget(LocalDate.parse(date), minute, zone, offset?.let { GridOffsetChoice.Explicit(it) } ?: GridOffsetChoice.Unspecified)
    private fun source(start: String, end: String, recurrence: RecurrenceRef? = null): TimedGridSource {
        val time = EventTime.Timed(Instant.parse(start), Instant.parse(end))
        return TimedGridSource(EventRef(CalendarId(4), 9, time.start, time.end, recurrence), time)
    }
    private fun ready(result: GridPreviewResult) = result as GridPreviewResult.Ready
    private fun create(target: GridTarget) = TimedGridPlanning.create(target, Duration.ofHours(1))
    private fun invalid(reason: GridInvalidReason, result: GridPreviewResult) = assertEquals(GridPreviewResult.Invalid(reason), result)

    @Test fun nearestQuarterHourSnapsWithTiesUpward() {
        for ((minute, snapped) in listOf(0.0 to 0.0, 7.49 to 0.0, 7.5 to 15.0, 22.5 to 30.0, 59.9 to 60.0)) {
            assertEquals(snapped, ready(create(target("2026-11-18", minute))).snappedTarget.minute, 0.0)
        }
    }

    @Test fun bottomEdgeNormalizesToNextMidnightWithoutClamping() {
        for (minute in listOf(1432.5, 1439.0, 1440.0)) {
            val result = ready(create(target("2026-11-18", minute)))
            assertEquals(LocalDate.parse("2026-11-19"), result.snappedTarget.date)
            assertEquals(0.0, result.snappedTarget.minute, 0.0)
            assertEquals(Instant.parse("2026-11-19T00:00:00Z"), result.time.start)
        }
    }

    @Test fun nonfiniteAndOutOfGridMinutesAreInvalid() {
        for (minute in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.01, 1440.01))
            invalid(GridInvalidReason.TARGET_MINUTE, create(target("2026-11-18", minute)))
    }

    @Test fun createRequiresExplicitPositiveWholeMinuteDefault() {
        val target = target("2026-11-18", 600.0)
        for (duration in listOf(Duration.ZERO, Duration.ofMinutes(-15), Duration.ofSeconds(61), Duration.ofSeconds(60, 1)))
            invalid(GridInvalidReason.DURATION, TimedGridPlanning.create(target, duration))
        assertEquals(Duration.ofMinutes(45), ready(TimedGridPlanning.create(target, Duration.ofMinutes(45))).time.duration)
        assertNull(ready(create(target)).source)
    }

    @Test fun londonGapRequiresReviewEvenWithAnOffsetHint() {
        for (offset in listOf(null, ZoneOffset.UTC, ZoneOffset.ofHours(1))) {
            assertEquals(GridPreviewResult.NeedsReview(GridReviewReason.CLOCK_GAP, emptyList<ZoneOffset>()),
                create(target("2026-03-29", 90.0, offset = offset)))
        }
    }

    @Test fun overlapNeedsExplicitValidTargetOffset() {
        val result = create(target("2026-10-25", 90.0)) as GridPreviewResult.NeedsReview
        assertEquals(GridReviewReason.CLOCK_OVERLAP, result.reason)
        assertEquals(listOf(ZoneOffset.ofHours(1), ZoneOffset.UTC), result.candidateOffsets)
        for ((offset, instant) in listOf(ZoneOffset.UTC to "2026-10-25T01:30:00Z", ZoneOffset.ofHours(1) to "2026-10-25T00:30:00Z")) {
            val preview = ready(create(target("2026-10-25", 90.0, offset = offset)))
            assertEquals(Instant.parse(instant), preview.time.start)
            assertEquals(offset, preview.resolvedOffset)
        }
        invalid(GridInvalidReason.OFFSET, create(target("2026-10-25", 90.0, offset = ZoneOffset.ofHours(2))))
    }

    @Test fun snapResolvesTheSnappedLocalTimeRatherThanUnsnappedPointer() {
        assertTrue(create(target("2026-03-29", 112.49)) is GridPreviewResult.NeedsReview)
        assertEquals(Instant.parse("2026-03-29T01:00:00Z"), ready(create(target("2026-03-29", 112.5))).time.start)
    }

    @Test fun lordHoweHalfHourGapAndOverlapAreExplicit() {
        val zone = ZoneId.of("Australia/Lord_Howe")
        assertEquals(GridReviewReason.CLOCK_GAP, (create(target("2026-10-04", 135.0, zone)) as GridPreviewResult.NeedsReview).reason)
        val overlap = create(target("2026-04-05", 105.0, zone)) as GridPreviewResult.NeedsReview
        assertEquals(setOf(ZoneOffset.ofHours(11), ZoneOffset.ofHoursMinutes(10, 30)), overlap.candidateOffsets.toSet())
    }

    @Test fun skippedDateNeedsReviewInsteadOfMovingToAnotherDay() {
        val zone = ZoneId.of("Pacific/Apia")
        assertEquals(GridReviewReason.CLOCK_GAP, (create(target("2011-12-30", 600.0, zone)) as GridPreviewResult.NeedsReview).reason)
    }

    @Test fun movePreservesFullRealDurationAndOriginalRecurrenceIdentity() {
        val original = Instant.parse("2026-10-25T00:45:00Z")
        val source = source("2026-10-25T00:45:00Z", "2026-10-25T01:15:00Z", RecurrenceRef(8, original))
        val segment = DayGeometry(LocalDate.parse("2026-10-25"), london).segments(source.ref).last().key
        val moved = ready(TimedGridPlanning.move(source, target("2026-10-26", 600.0), segment))
        assertEquals(Instant.parse("2026-10-26T10:00:00Z"), moved.time.start)
        assertEquals(Duration.ofMinutes(30), moved.time.duration)
        assertEquals(source.ref, moved.source)
        assertEquals(original, moved.source!!.recurrence!!.originalStart)
        assertEquals(FieldChange.Set(moved.time), moved.patch.time)
        assertEquals(EventPatch(time = FieldChange.Set(moved.time)), moved.patch)
    }

    @Test fun multiDayMoveRetainsElapsedHoursAcrossDifferentClockChanges() {
        val source = source("2026-03-28T12:00:00Z", "2026-03-30T11:00:00Z")
        val moved = ready(TimedGridPlanning.move(source, target("2026-10-24", 720.0)))
        assertEquals(Duration.ofHours(47), moved.time.duration)
        assertEquals(Instant.parse("2026-10-26T10:00:00Z"), moved.time.end)
    }

    @Test fun movePreservesZeroAndNanosecondDuration() {
        for (end in listOf("2026-11-18T10:00:00.000000007Z", "2026-11-18T10:00:00.123456789Z")) {
            val source = source("2026-11-18T10:00:00.000000007Z", end)
            val moved = ready(TimedGridPlanning.move(source, target("2026-11-19", 615.0)))
            assertEquals(source.time.duration, moved.time.duration)
        }
    }

    @Test fun resizeKeepsExactStartAndRejectsNonpositiveEnd() {
        val source = source("2026-11-18T10:00:00.000000007Z", "2026-11-18T11:00:00Z")
        val resized = ready(TimedGridPlanning.resizeEnd(source, target("2026-11-19", 0.0)))
        assertEquals(source.time.start, resized.time.start)
        assertEquals(Instant.parse("2026-11-19T00:00:00Z"), resized.time.end)
        invalid(GridInvalidReason.END_NOT_AFTER_START, TimedGridPlanning.resizeEnd(source, target("2026-11-18", 600.0)))
        invalid(GridInvalidReason.END_NOT_AFTER_START, TimedGridPlanning.resizeEnd(source, target("2026-11-18", 585.0)))
    }

    @Test fun segmentEndingAtTransitionValidatesWithBeforeOffset() {
        val source = source("2026-10-25T00:45:00Z", "2026-10-25T01:00:00Z")
        val segment = DayGeometry(LocalDate.parse("2026-10-25"), london).segments(source.ref).single().key
        assertEquals(ZoneOffset.ofHours(1), segment.offset)
        assertEquals(ZoneOffset.UTC, london.rules.getOffset(segment.end))
        val resized = ready(TimedGridPlanning.resizeEnd(source, target("2026-10-25", 120.0), segment))
        assertEquals(Instant.parse("2026-10-25T02:00:00Z"), resized.time.end)
        // A valid source segment is not explicit user consent for either target overlap offset.
        assertEquals(GridReviewReason.CLOCK_OVERLAP,
            (TimedGridPlanning.resizeEnd(source, target("2026-10-25", 90.0), segment) as GridPreviewResult.NeedsReview).reason)
    }

    @Test fun forgedOrStaleSegmentsAndMismatchedSourceBoundsAreRejected() {
        val source = source("2026-10-25T00:45:00Z", "2026-10-25T01:15:00Z")
        val segment = DayGeometry(LocalDate.parse("2026-10-25"), london).segments(source.ref).first().key
        for (bad in listOf(segment.copy(offset = ZoneOffset.UTC), segment.copy(end = segment.end.plusSeconds(1)),
            segment.copy(event = source.ref.copy(eventId = 10))))
            invalid(GridInvalidReason.SOURCE_SEGMENT, TimedGridPlanning.move(source, target("2026-11-18", 600.0), bad))
        val stale = source.copy(time = source.time.copy(end = source.time.end.plusSeconds(1)))
        invalid(GridInvalidReason.SOURCE_BOUNDS, TimedGridPlanning.move(stale, target("2026-11-18", 600.0)))
        invalid(GridInvalidReason.SOURCE_BOUNDS, TimedGridPlanning.resizeEnd(stale, target("2026-11-18", 600.0)))
    }

    @Test fun displayZoneDoesNotReplaceStoredEventZone() {
        val source = source("2026-11-18T10:00:00Z", "2026-11-18T11:00:00Z")
        val moved = ready(TimedGridPlanning.move(source, target("2026-11-19", 600.0, ZoneId.of("Asia/Kathmandu"))))
        assertEquals(Instant.parse("2026-11-19T04:15:00Z"), moved.time.start)
        assertEquals(FieldChange.Keep, moved.patch.eventZone)
    }

    @Test fun dateAndDurationOverflowReturnInvalidWithoutPartialPreview() {
        invalid(GridInvalidReason.DATE_RANGE, create(target("9999-12-31", 1440.0)))
        invalid(GridInvalidReason.DATE_RANGE, create(GridTarget(LocalDate.of(0, 1, 1), 0.0, london)))
        invalid(GridInvalidReason.DATE_RANGE, TimedGridPlanning.create(target("2026-11-18", 0.0), Duration.ofSeconds(Long.MAX_VALUE / 60 * 60)))
    }

    @Test fun explicitTransitionEndRoundTripsLondonAndLordHoweSourceBoundaries() {
        for ((zone, end) in listOf(london to "2026-03-29T01:00:00Z", london to "2026-10-25T01:00:00Z",
            ZoneId.of("Australia/Lord_Howe") to "2026-10-03T15:30:00Z",
            ZoneId.of("Australia/Lord_Howe") to "2026-04-04T15:00:00Z")) {
            val endInstant = Instant.parse(end)
            val source = source(endInstant.minusSeconds(900).toString(), end)
            val date = source.time.start.atZone(zone).toLocalDate()
            val segment = DayGeometry(date, zone).segments(source.ref).last()
            val target = GridTarget(date, segment.endMinute, zone, boundary = GridBoundaryChoice.TransitionEnd(segment.key))
            val result = ready(TimedGridPlanning.resizeEnd(source, target))
            assertEquals(source.time, result.time)
            assertEquals(segment.offset, result.resolvedOffset)
            assertEquals(source.ref, result.source)
        }
    }

    @Test fun transitionEndIsNotAcceptedForCreateMoveDisplacementOrSpoofedKeys() {
        val source = source("2026-10-25T00:45:00Z", "2026-10-25T01:00:00Z")
        val segment = DayGeometry(LocalDate.parse("2026-10-25"), london).segments(source.ref).last()
        val target = target("2026-10-25", 120.0).copy(boundary = GridBoundaryChoice.TransitionEnd(segment.key))
        invalid(GridInvalidReason.BOUNDARY, create(target))
        invalid(GridInvalidReason.BOUNDARY, TimedGridPlanning.move(source, target))
        for (bad in listOf(target.copy(minute = 135.0), target.copy(minute = 119.9), target.copy(date = target.date.plusDays(1)),
            target.copy(zone = ZoneOffset.UTC), target.copy(boundary = GridBoundaryChoice.TransitionEnd(segment.key.copy(offset = ZoneOffset.UTC)))))
            invalid(GridInvalidReason.BOUNDARY, TimedGridPlanning.resizeEnd(source, bad))
        invalid(GridInvalidReason.OFFSET, TimedGridPlanning.resizeEnd(source,
            target.copy(offsetChoice = GridOffsetChoice.Explicit(ZoneOffset.UTC))))
        assertEquals(source.time, ready(TimedGridPlanning.resizeEnd(source,
            target.copy(offsetChoice = GridOffsetChoice.Explicit(ZoneOffset.ofHours(1))))).time)
    }

    @Test fun transitionEndRequiresFinalSourceEndpointAndActualTransition() {
        val source = source("2026-10-25T00:45:00Z", "2026-10-25T01:15:00Z")
        val segments = DayGeometry(LocalDate.parse("2026-10-25"), london).segments(source.ref)
        for (segment in segments) {
            val target = target("2026-10-25", segment.endMinute).copy(boundary = GridBoundaryChoice.TransitionEnd(segment.key))
            invalid(GridInvalidReason.BOUNDARY, TimedGridPlanning.resizeEnd(source, target))
        }
    }

    @Test fun nonQuarterHourHistoricalTransitionBoundaryIsNotSilentlySnapped() {
        val zone = ZoneId.of("America/St_Johns")
        val transition = zone.rules.nextTransition(Instant.parse("1988-04-01T00:00:00Z"))!!
        val source = source(transition.instant.minusSeconds(30).toString(), transition.instant.toString())
        val date = source.time.start.atZone(zone).toLocalDate()
        val segment = DayGeometry(date, zone).segments(source.ref).last()
        assertTrue(segment.endMinute % 15.0 != 0.0)
        val target = GridTarget(date, segment.endMinute, zone, boundary = GridBoundaryChoice.TransitionEnd(segment.key))
        invalid(GridInvalidReason.BOUNDARY, TimedGridPlanning.resizeEnd(source, target))
    }
}
