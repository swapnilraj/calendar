package dev.swapnilraj.calendar.ui.grid

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.CalEvent
import kotlinx.coroutines.*
import java.time.*
import kotlin.math.abs
import kotlin.math.min

internal data class GridGestureRuntime(val date: LocalDate, val zone: ZoneId, val events: List<CalEvent>, val hourHeight: Float,
    val density: Float, val fontScale: Float, val rtl: Boolean, val gutterPx: Float, val interactions: TimedGridInteractions?,
    val viewport: TimedGridViewport?, val coordinates: LayoutCoordinates?, val occupiedBounds: List<Rect>, val onPreview: (TimedGridPreview?) -> Unit)
internal class GridDragSession(val contextKey: Any, val events: List<CalEvent>, val runtimeKey: List<Any?>,
    val source: TimedGridSource?, val segment: TimedSegmentKey?, val kind: TimedGridEditKind?, val initialMinute: Double,
    val initialRoot: Offset, val touchSlop: Float, val initialDate: LocalDate) {
    var rootPoint = initialRoot
    var alive = true
    var authorized = source == null
    var rejected = false
    var physicallyMoved = false
    var result: GridPreviewResult? = null
    var target: GridTarget? = null
    var pointerPath: TimedGridPointerPath? = null
    var job: Job? = null
}
internal class TimedGridGestureController {
    var runtime: GridGestureRuntime? = null
    var active by mutableStateOf<GridDragSession?>(null)
        private set
    var suppressTap by mutableStateOf(false)
        private set
    private fun key(rt: GridGestureRuntime): List<Any?> = listOf(rt.date, rt.zone, rt.hourHeight, rt.density, rt.fontScale, rt.rtl,
        rt.gutterPx, rt.viewport?.boundsInRoot, rt.coordinates?.size, rt.viewport?.layoutKey, rt.viewport?.targetRange)
    private fun current(session: GridDragSession): Boolean {
        val rt = runtime ?: return false
        return session.alive && active === session && rt.interactions?.contextKey == session.contextKey && key(rt) == session.runtimeKey &&
            rt.events == session.events && rt.coordinates?.isAttached == true && (session.source == null || rt.events.any { editableGridSource(it) == session.source })
    }
    private fun bounds(rt: GridGestureRuntime): Rect? = rt.viewport?.boundsInRoot ?: rt.coordinates?.takeIf { it.isAttached }?.boundsInRoot()
    private fun contentPoint(point: Offset, rt: GridGestureRuntime): Offset? {
        val visible = bounds(rt) ?: return null
        if (!visible.contains(point)) return null
        if (rt.viewport?.dateAtRoot?.let { it(point) == null } == true) return null
        if ((!rt.rtl && point.x < visible.left + rt.gutterPx) || (rt.rtl && point.x > visible.right - rt.gutterPx)) return null
        return rt.viewport?.let { Offset(point.x - visible.left, point.y - visible.top + it.scrollOffset()) }
            ?: rt.coordinates?.let { point - it.localToRoot(Offset.Zero) }
    }
    private fun minute(point: Offset, rt: GridGestureRuntime): Double? = contentPoint(point, rt)?.y?.let {
        (it / (rt.hourHeight * rt.density) * 60.0).takeIf { value -> value.isFinite() && value in 0.0..1440.0 }
    }
    fun begin(point: Offset, source: TimedGridSource?, segment: TimedSegmentKey?, kind: TimedGridEditKind?, touchSlop: Float): GridDragSession? {
        val rt = runtime ?: return null
        val config = rt.interactions ?: return null
        if (source == null && !config.creationEnabled) return null
        if (source != null && (!config.editingEnabled || !config.canEdit(source.ref) || segment == null)) return null
        val initial = minute(point, rt) ?: return null
        val initialDate = rt.viewport?.dateAtRoot?.invoke(point) ?: rt.date
        // Occupancy is in this grid's local coordinates even when the shared viewport spans days.
        val local = rt.coordinates?.takeIf { it.isAttached }?.let { point - it.localToRoot(Offset.Zero) }
        if (source == null && local?.let { p -> rt.occupiedBounds.any { it.contains(p) } } == true) return null
        cancel()
        return GridDragSession(config.contextKey, rt.events.toList(), key(rt), source, segment, kind, initial, point, touchSlop, initialDate).also {
            active = it; suppressTap = true
            rt.onPreview(TimedGridPreview(config.contextKey, if (source == null) TimedGridGesturePhase.PREVIEW else TimedGridGesturePhase.CHECKING, source, kind))
        }
    }
    suspend fun authorize(session: GridDragSession) {
        if (session.source == null) return
        val callback = runtime?.interactions?.onBeginEdit ?: return
        val allowed = try { callback(session.source.ref) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
        if (!current(session)) return
        session.authorized = allowed; session.rejected = !allowed
        preview(session)
    }
    fun preview(session: GridDragSession) {
        val rt = runtime ?: return
        if (!current(session)) { cancel(session); return }
        if (!session.authorized) {
            rt.onPreview(TimedGridPreview(session.contextKey, if (session.rejected) TimedGridGesturePhase.REJECTED else TimedGridGesturePhase.CHECKING, session.source, session.kind)); return
        }
        val position = minute(session.rootPoint, rt)
        if (position == null) { session.result = null; rt.onPreview(TimedGridPreview(session.contextKey, TimedGridGesturePhase.REJECTED, session.source, session.kind)); return }
        val date = rt.viewport?.dateAtRoot?.invoke(session.rootPoint) ?: rt.date
        val dayDelta = java.time.temporal.ChronoUnit.DAYS.between(session.initialDate, date)
        if (session.source != null && dayDelta == 0L && abs((position - session.initialMinute) / 60 * rt.hourHeight * rt.density) <= session.touchSlop) {
            session.result = null
            rt.onPreview(TimedGridPreview(session.contextKey, TimedGridGesturePhase.ORIGINAL, session.source, session.kind)); return
        }
        val target = if (session.source == null) GridTarget(date, position, rt.zone)
            else translatedGridTarget(session.source, requireNotNull(session.segment), requireNotNull(session.kind), position - session.initialMinute + dayDelta * 1440.0, rt.zone)
        val planned = when {
            session.source == null -> TimedGridPlanning.create(target, requireNotNull(rt.interactions).defaultCreateDuration)
            else -> planGridEdit(session.source, requireNotNull(session.segment), requireNotNull(session.kind), target)
        }
        val snapped = snappedGridTarget(planned, target)
        val result = if (rt.viewport?.targetRange?.let { range ->
                if (session.kind == TimedGridEditKind.MOVE) session.initialDate !in range || date !in range
                else !gridTargetWithinRange(range, snapped, session.kind == TimedGridEditKind.RESIZE_END)
            } == true)
            GridPreviewResult.Invalid(GridInvalidReason.DATE_RANGE) else planned
        session.result = result; session.target = snapped
        session.pointerPath = TimedGridPointerPath(GridTarget(session.initialDate, session.initialMinute, rt.zone), GridTarget(date, position, rt.zone))
        rt.onPreview(TimedGridPreview(session.contextKey, TimedGridGesturePhase.PREVIEW, session.source, session.kind, result))
    }
    suspend fun autoScroll(session: GridDragSession, seconds: Float) {
        val rt = runtime ?: return
        if (!current(session) || !session.authorized || session.rejected || !session.physicallyMoved) return
        val viewport = rt.viewport ?: return
        val point = session.rootPoint
        if (!viewport.boundsInRoot.contains(point)) return
        val edge = min(48f * rt.density, viewport.boundsInRoot.height / 4f)
        if (edge <= 0) return
        val speed = min(3 * rt.hourHeight, 240f) * rt.density
        val delta = when {
            point.y < viewport.boundsInRoot.top + edge -> -speed * (1 - (point.y - viewport.boundsInRoot.top) / edge)
            point.y > viewport.boundsInRoot.bottom - edge -> speed * (1 - (viewport.boundsInRoot.bottom - point.y) / edge)
            else -> 0f
        }
        if (delta != 0f) viewport.scrollBy(delta * seconds.coerceIn(0f, 0.05f))
    }
    fun finish(session: GridDragSession) {
        if (!current(session) || !session.authorized || session.rejected) { cancel(session); return }
        preview(session)
        if (!current(session)) return
        val result = session.result
        val target = session.target
        val rt = runtime
        val config = rt?.interactions
        val unchanged = result is GridPreviewResult.Ready && session.source?.time == result.time
        if (target != null && !unchanged && (result is GridPreviewResult.Ready || result is GridPreviewResult.NeedsReview)) {
            if (session.source == null) config?.onCreate?.invoke(TimedGridCreateProposal(session.contextKey, target, result))
            else config?.onEdit?.invoke(TimedGridEditProposal(session.contextKey, requireNotNull(session.kind), session.source, requireNotNull(session.segment), target, result, session.pointerPath))
        }
        cancel(session)
    }
    fun cancel(session: GridDragSession? = active) {
        session ?: return
        session.alive = false; session.job?.cancel()
        if (active === session) { active = null; runtime?.onPreview?.invoke(null) }
    }
    fun releaseTapSuppression() { suppressTap = false }
}

@Composable
internal fun rememberTimedGridController(runtime: GridGestureRuntime): TimedGridGestureController {
    val controller = remember(runtime.date, runtime.zone) { TimedGridGestureController() }
    controller.runtime = runtime
    DisposableEffect(controller, runtime.interactions?.contextKey, runtime.events, runtime.hourHeight, runtime.density, runtime.fontScale, runtime.rtl, runtime.viewport?.boundsInRoot, runtime.viewport?.layoutKey, runtime.viewport?.targetRange) {
        onDispose { controller.cancel() }
    }
    BackHandler(enabled = controller.active != null) { controller.cancel() }
    return controller
}

/** Background requires an unconsumed down: real event click targets, including expanded hit areas, win. */
@Composable
internal fun Modifier.timedGridGestureInput(controller: TimedGridGestureController, source: TimedGridSource? = null,
    segment: TimedSegmentKey? = null, canResize: Boolean = false): Modifier {
    val rt = requireNotNull(controller.runtime)
    val config = rt.interactions ?: return this
    val enabled = if (source == null) config.creationEnabled else config.editingEnabled && config.canEdit(source.ref)
    if (!enabled) return this
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    return onGloballyPositioned { coordinates = it }.onPreviewKeyEvent {
        if (it.key == Key.Escape && it.type == KeyEventType.KeyDown && controller.active != null) { controller.cancel(); true } else false
    }.pointerInput(controller, config.contextKey, source, segment, rt.events, rt.hourHeight, density.density, density.fontScale, direction, rt.viewport?.boundsInRoot, rt.viewport?.layoutKey, rt.viewport?.targetRange) {
        coroutineScope {
            val gestureScope = this
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = source == null)
                if (source != null && (down.position.x !in 0f..size.width.toFloat() || down.position.y !in 0f..size.height.toFloat())) return@awaitEachGesture
                val pressed = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                val location = coordinates?.takeIf { it.isAttached } ?: return@awaitEachGesture
                val root = location.localToRoot(pressed.position)
                val resize = source != null && canResize && pressed.position.y >= size.height - min(24.dp.toPx(), size.height / 2f)
                val session = controller.begin(root, source, segment, if (source == null) null else if (resize) TimedGridEditKind.RESIZE_END else TimedGridEditKind.MOVE, viewConfiguration.touchSlop)
                    ?: return@awaitEachGesture
                session.job = gestureScope.launch {
                    controller.authorize(session)
                    var previous = withFrameNanos { it }
                    while (session.alive) {
                        val now = withFrameNanos { it }
                        controller.autoScroll(session, ((now - previous) / 1_000_000_000.0).toFloat())
                        previous = now
                        controller.preview(session)
                    }
                }
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } > 1) { controller.cancel(session); break }
                        val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                        coordinates?.takeIf { it.isAttached }?.let { session.rootPoint = it.localToRoot(pointer.position) }
                        if ((session.rootPoint - session.initialRoot).getDistance() > session.touchSlop) session.physicallyMoved = true
                        event.changes.forEach { it.consume() }
                        if (!pointer.pressed) { controller.finish(session); break }
                    }
                } finally {
                    controller.cancel(session)
                }
            }
        }
    }
}

/** Stable until every pointer is up, even when source/context invalidation cancels the edit detector. */
@Composable
internal fun Modifier.timedGridTapGuard(controller: TimedGridGestureController): Modifier {
    if (controller.runtime?.interactions == null && !controller.suppressTap) return this
    return pointerInput(controller) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.none { it.pressed }) {
                    if (controller.suppressTap) event.changes.forEach { it.consume() }
                    controller.releaseTapSuppression()
                    break
                }
            }
        }
    }
}
