package dev.swapnilraj.calendar.ui.grid

import androidx.compose.ui.geometry.Rect
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.CalEvent
import java.time.*
import kotlin.math.floor

/** Preview requests only. Host owns fresh editor/source/permission/recurrence validation and writes. */
data class TimedGridInteractions(
    /** Change whenever the app source, query range, calendar filter, or permission context changes. */
    val contextKey: Any,
    val defaultCreateDuration: Duration,
    val onCreate: ((TimedGridCreateProposal) -> Unit)? = null,
    val onEdit: ((TimedGridEditProposal) -> Unit)? = null,
    val canEdit: (EventRef) -> Boolean = { false },
    /** Read-only, once per long press; no write-permission prompt or mutation is allowed here. */
    val onBeginEdit: (suspend (EventRef) -> Boolean)? = null,
    val onCreateInEditor: ((LocalDate) -> Unit)? = null,
    val onOpenEditor: ((EventRef) -> Unit)? = null,
) {
    val creationEnabled: Boolean get() = onCreate != null && onCreateInEditor != null &&
        !defaultCreateDuration.isNegative && !defaultCreateDuration.isZero && defaultCreateDuration.nano == 0 && defaultCreateDuration.seconds % 60 == 0L
    val editingEnabled: Boolean get() = onEdit != null && onBeginEdit != null && onOpenEditor != null
}
enum class TimedGridEditKind { MOVE, RESIZE_END }
data class TimedGridCreateProposal(val contextKey: Any, val target: GridTarget, val preview: GridPreviewResult)
data class TimedGridEditProposal(val contextKey: Any, val kind: TimedGridEditKind, val source: TimedGridSource,
    val selectedSegment: TimedSegmentKey, val target: GridTarget, val preview: GridPreviewResult)

/** Pixel viewport only. Scroll offset is read live, so a stationary edge drag follows auto-scrolling. */
data class TimedGridViewport(val boundsInRoot: Rect, val scrollOffset: () -> Int, val scrollBy: suspend (Float) -> Float)
enum class TimedGridGesturePhase { CHECKING, ORIGINAL, PREVIEW, REJECTED }
data class TimedGridPreview(val contextKey: Any, val phase: TimedGridGesturePhase, val source: TimedGridSource? = null,
    val kind: TimedGridEditKind? = null, val result: GridPreviewResult? = null)

internal fun editableGridSource(event: CalEvent): TimedGridSource? {
    val ref = event.ref ?: return null // CalendarId(0) geometry fallbacks are never authority.
    if (event.allDay || ref.instanceStart != event.start || ref.instanceEnd != event.end) return null
    return TimedGridSource(ref, EventTime.Timed(event.start, event.end))
}
internal fun TimedGridInteractions.eligible(event: CalEvent): Boolean = editingEnabled && editableGridSource(event)?.let { canEdit(it.ref) } == true
internal fun gridMinute(time: LocalTime): Double = time.toSecondOfDay() / 60.0 + time.nano / 60_000_000_000.0

/** Wall-position translation only. No offset is selected and no target Instant is inferred here. */
internal fun translatedGridTarget(source: TimedGridSource, segment: TimedSegmentKey, kind: TimedGridEditKind,
    deltaMinutes: Double, zone: ZoneId): GridTarget {
    val anchor = if (kind == TimedGridEditKind.MOVE) source.time.start.atZone(zone).toLocalDateTime()
        else LocalDateTime.ofInstant(source.time.end, segment.offset) // Ending offset BEFORE a boundary.
    val total = gridMinute(anchor.toLocalTime()) + deltaMinutes
    val days = floor(total / 1440.0).toLong()
    return GridTarget(anchor.toLocalDate().plusDays(days), total - days * 1440.0, zone)
}
internal fun snappedGridTarget(result: GridPreviewResult, fallback: GridTarget): GridTarget = when (result) {
    is GridPreviewResult.Ready -> result.snappedTarget
    is GridPreviewResult.NeedsReview -> result.snappedTarget
    is GridPreviewResult.Invalid -> fallback
}

/** Only an unchanged original end may retain its already-known ending offset/boundary. */
internal fun planGridEdit(source: TimedGridSource, segment: TimedSegmentKey, kind: TimedGridEditKind, target: GridTarget): GridPreviewResult {
    val result = if (kind == TimedGridEditKind.MOVE) TimedGridPlanning.move(source, target, segment)
        else TimedGridPlanning.resizeEnd(source, target, segment)
    if (kind != TimedGridEditKind.RESIZE_END || result is GridPreviewResult.Invalid) return result
    val snapped = snappedGridTarget(result, target)
    val original = translatedGridTarget(source, segment, kind, 0.0, target.zone)
    if (snapped.date != original.date || snapped.minute != original.minute) return result
    val boundary = TimedGridPlanning.resizeEnd(source, snapped.copy(boundary = GridBoundaryChoice.TransitionEnd(segment)), segment)
    if (boundary is GridPreviewResult.Ready && boundary.time == source.time) return boundary
    val retained = TimedGridPlanning.resizeEnd(source, snapped.copy(offsetChoice = GridOffsetChoice.Explicit(segment.offset)), segment)
    return if (retained is GridPreviewResult.Ready && retained.time == source.time) retained else result
}
