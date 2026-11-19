package dev.swapnilraj.calendar.core

import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.floor

sealed interface GridOffsetChoice {
    data object Unspecified : GridOffsetChoice
    data class Explicit(val offset: ZoneOffset) : GridOffsetChoice
}

sealed interface GridBoundaryChoice {
    /** Explicitly retain the source final fragment's BEFORE-transition ending boundary. Resize only. */
    data class TransitionEnd(val segment: TimedSegmentKey) : GridBoundaryChoice
}

/** A logical position in a fixed 24-hour wall-clock grid, not a pixel or an elapsed-day fraction. */
data class GridTarget(
    val date: LocalDate,
    val minute: Double,
    val zone: ZoneId,
    val offsetChoice: GridOffsetChoice = GridOffsetChoice.Unspecified,
    val boundary: GridBoundaryChoice? = null,
)

/** The caller must supply a timed occurrence, rather than inferring its kind from provider instants. */
data class TimedGridSource(val ref: EventRef, val time: EventTime.Timed)

enum class GridReviewReason { CLOCK_GAP, CLOCK_OVERLAP }
enum class GridInvalidReason { TARGET_MINUTE, DATE_RANGE, OFFSET, SOURCE_BOUNDS, SOURCE_SEGMENT, BOUNDARY, DURATION, END_NOT_AFTER_START }

sealed interface GridPreviewResult {
    data class Ready(
        val time: EventTime.Timed,
        val source: EventRef?,
        val snappedTarget: GridTarget,
        val resolvedOffset: ZoneOffset,
    ) : GridPreviewResult {
        /** Keep the stored event zone and every unrelated field, even in a different display zone. */
        val patch: EventPatch get() = EventPatch(time = FieldChange.Set(time))
    }
    data class NeedsReview(val reason: GridReviewReason, val candidateOffsets: List<ZoneOffset>,
        /** Snapped logical wall position only; this does not select an offset or imply an instant. */
        val snappedTarget: GridTarget) : GridPreviewResult
    data class Invalid(val reason: GridInvalidReason) : GridPreviewResult
}

/**
 * Preview only: editor/provider still validate access, fresh source tokens and recurrence scope.
 * Move targets always denote the complete event's new start. UI must translate pointer/grab offsets
 * before calling; a clicked rendering fragment is never implicitly treated as the full event.
 */
object TimedGridPlanning {
    /** No hidden default: caller supplies its configured positive, whole-minute elapsed duration. */
    fun create(target: GridTarget, elapsedDefault: Duration): GridPreviewResult = safely {
        if (target.boundary != null) return@safely GridPreviewResult.Invalid(GridInvalidReason.BOUNDARY)
        if (elapsedDefault.isNegative || elapsedDefault.isZero || elapsedDefault.nano != 0 || elapsedDefault.seconds % 60 != 0L)
            return@safely GridPreviewResult.Invalid(GridInvalidReason.DURATION)
        atTarget(target) { instant, snapped, offset ->
            ready(EventTime.Timed(instant, instant.plus(elapsedDefault)), null, snapped, offset)
        }
    }

    /** Move preserves the full real duration, including subsecond and point-event durations. */
    fun move(source: TimedGridSource, targetStart: GridTarget, selectedSegment: TimedSegmentKey? = null): GridPreviewResult = safely {
        if (targetStart.boundary != null) return@safely GridPreviewResult.Invalid(GridInvalidReason.BOUNDARY)
        validate(source, targetStart.zone, selectedSegment)?.let { return@safely it }
        atTarget(targetStart) { instant, snapped, offset ->
            ready(EventTime.Timed(instant, instant.plus(source.time.duration)), source.ref, snapped, offset)
        }
    }

    /** Resize the full event end, retaining its exact original start; minimum visual height is irrelevant. */
    fun resizeEnd(source: TimedGridSource, targetEnd: GridTarget, selectedSegment: TimedSegmentKey? = null): GridPreviewResult = safely {
        validate(source, targetEnd.zone, selectedSegment)?.let { return@safely it }
        atTarget(targetEnd, source) { instant, snapped, offset ->
            if (instant <= source.time.start) GridPreviewResult.Invalid(GridInvalidReason.END_NOT_AFTER_START)
            else ready(EventTime.Timed(source.time.start, instant), source.ref, snapped, offset)
        }
    }

    private fun validate(source: TimedGridSource, zone: ZoneId, segment: TimedSegmentKey?): GridPreviewResult.Invalid? {
        if (source.ref.instanceStart != source.time.start || source.ref.instanceEnd != source.time.end)
            return GridPreviewResult.Invalid(GridInvalidReason.SOURCE_BOUNDS)
        if (segment != null) {
            // Use DayGeometry's ending offset, which is BEFORE a transition at the segment's end.
            // Looking up zone.rules.getOffset(segment.end) would incorrectly use AFTER instead.
            val date = segment.start.atZone(zone).toLocalDate()
            if (DayGeometry(date, zone).segments(source.ref).none { it.key == segment })
                return GridPreviewResult.Invalid(GridInvalidReason.SOURCE_SEGMENT)
        }
        return null
    }

    private fun atTarget(target: GridTarget, source: TimedGridSource? = null, block: (Instant, GridTarget, ZoneOffset) -> GridPreviewResult): GridPreviewResult {
        if (!target.minute.isFinite() || target.minute < 0 || target.minute > 1440)
            return GridPreviewResult.Invalid(GridInvalidReason.TARGET_MINUTE)
        require(target.date.year in 1..9999)
        val snappedMinute = (floor(target.minute / 15.0 + 0.5) * 15).toLong()
        val date = if (snappedMinute == 1440L) target.date.plusDays(1) else target.date
        require(date.year in 1..9999)
        val minute = if (snappedMinute == 1440L) 0L else snappedMinute
        val snapped = target.copy(date = date, minute = minute.toDouble())
        target.boundary?.let { boundary ->
            val key = (boundary as GridBoundaryChoice.TransitionEnd).segment
            if (source == null || validate(source, target.zone, key) != null || key.end != source.time.end)
                return GridPreviewResult.Invalid(GridInvalidReason.BOUNDARY)
            val sourceDate = key.start.atZone(target.zone).toLocalDate()
            val segment = DayGeometry(sourceDate, target.zone).segments(source.ref).lastOrNull()
                ?: return GridPreviewResult.Invalid(GridInvalidReason.BOUNDARY)
            val transition = target.zone.rules.previousTransition(key.end.plusNanos(1))
            if (segment.key != key || transition?.instant != key.end || segment.endMinute % 15.0 != 0.0)
                return GridPreviewResult.Invalid(GridInvalidReason.BOUNDARY)
            val expectedDate = if (segment.endMinute == 1440.0) sourceDate.plusDays(1) else sourceDate
            val expectedMinute = if (segment.endMinute == 1440.0) 0.0 else segment.endMinute
            // Boundary choice never rounds or displaces its exact source endpoint.
            if (target.minute != snappedMinute.toDouble() || date != expectedDate || minute.toDouble() != expectedMinute)
                return GridPreviewResult.Invalid(GridInvalidReason.BOUNDARY)
            if (target.offsetChoice is GridOffsetChoice.Explicit && target.offsetChoice.offset != key.offset)
                return GridPreviewResult.Invalid(GridInvalidReason.OFFSET)
            return block(key.end, snapped, key.offset)
        }
        val local = date.atStartOfDay().plusMinutes(minute)
        val offsets = target.zone.rules.getValidOffsets(local)
        if (offsets.isEmpty()) return GridPreviewResult.NeedsReview(GridReviewReason.CLOCK_GAP, emptyList(), snapped)
        val offset = when (val choice = target.offsetChoice) {
            GridOffsetChoice.Unspecified -> {
                if (offsets.size != 1) return GridPreviewResult.NeedsReview(GridReviewReason.CLOCK_OVERLAP, offsets.toList(), snapped)
                offsets.single()
            }
            is GridOffsetChoice.Explicit -> {
                if (choice.offset !in offsets) return GridPreviewResult.Invalid(GridInvalidReason.OFFSET)
                choice.offset
            }
        }
        return block(local.toInstant(offset), snapped, offset)
    }

    private fun ready(time: EventTime.Timed, source: EventRef?, target: GridTarget, offset: ZoneOffset): GridPreviewResult {
        require(time.start.atZone(target.zone).year in 1..9999 && time.end.atZone(target.zone).year in 1..9999)
        return GridPreviewResult.Ready(time, source, target, offset)
    }

    private inline fun safely(block: () -> GridPreviewResult): GridPreviewResult = try {
        block()
    } catch (_: ArithmeticException) {
        GridPreviewResult.Invalid(GridInvalidReason.DATE_RANGE)
    } catch (_: DateTimeException) {
        GridPreviewResult.Invalid(GridInvalidReason.DATE_RANGE)
    } catch (_: IllegalArgumentException) {
        GridPreviewResult.Invalid(GridInvalidReason.DATE_RANGE)
    }
}
