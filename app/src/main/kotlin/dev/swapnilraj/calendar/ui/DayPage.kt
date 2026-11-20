package dev.swapnilraj.calendar.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.testTag
import dev.swapnilraj.calendar.ui.grid.*
import dev.swapnilraj.calendar.data.CalEvent
import java.time.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun DayPage(date: LocalDate, events: List<CalEvent>, now: ZonedDateTime, zone: ZoneId, use24Hour: Boolean,
    hourHeight: Float, sharedScroll: Int, active: Boolean, initialScrollHour: Double?,
    onScroll: (Int) -> Unit, onHourHeight: (Float) -> Unit, onZoomFinished: (Float) -> Unit,
    onEventClick: (CalEvent) -> Unit,
    summary: @Composable RowScope.() -> Unit = {},
    background: @Composable BoxScope.() -> Unit = {},
    interactions: TimedGridInteractions? = null,
    onCancelPendingEdit: (() -> Unit)? = null) {
    val scroll = rememberScrollState(maxOf(0, sharedScroll))
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val latestHeight by rememberUpdatedState(hourHeight)
    val latestOnHeight by rememberUpdatedState(onHourHeight)
    val latestOnFinish by rememberUpdatedState(onZoomFinished)
    val latestActive by rememberUpdatedState(active)
    val latestOnScroll by rememberUpdatedState(onScroll)
    var positioned by remember { mutableStateOf(false) }
    var viewportBounds by remember { mutableStateOf(Rect.Zero) }
    var preview by remember { mutableStateOf<TimedGridPreview?>(null) }
    var cancelSignal by remember { mutableLongStateOf(0) }
    fun cancelGesture() { cancelSignal++; preview = null; onCancelPendingEdit?.invoke() }
    val enabledInteractions = interactions?.takeIf { active }?.let { config ->
        val editable = events.any { config.eligible(it) }
        when {
            !editable && !config.creationEnabled -> null
            !editable -> config.copy(onEdit = null, onBeginEdit = null, onOpenEditor = null)
            else -> config
        }
    }
    val effective = enabledInteractions?.copy(
        onOpenEditor = enabledInteractions.onOpenEditor?.let { open -> { ref -> cancelGesture(); open(ref) } },
        onCreateInEditor = enabledInteractions.onCreateInEditor?.let { create -> { date -> cancelGesture(); create(date) } },
    )
    LaunchedEffect(effective?.contextKey, events, active) { preview = null }
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.value }.distinctUntilChanged().collect { if (latestActive && positioned) latestOnScroll(it) }
    }
    Column(Modifier.fillMaxSize()) {
        DayHeader(date, now.withZoneSameInstant(zone).toLocalDate(), events.filter { it.allDay }, zone, use24Hour,
            { event -> cancelGesture(); onEventClick(event) }, summary, dayEvents = events, onBeforeShowEvents = { cancelGesture() })
        HorizontalDivider(thickness = 0.5.dp)
        if (effective != null) TimedGridFeedback(date, zone, use24Hour, effective, preview) { cancelGesture() }
        BoxWithConstraints(Modifier.weight(1f)) {
            val viewport = with(density) { maxHeight.toPx() }
            LaunchedEffect(active, sharedScroll < 0, density.density) {
                if (active) {
                    withFrameNanos { }
                    val initial = initialScrollHour?.times(hourHeight)?.let { with(density) { it.toFloat().dp.toPx() } }
                        ?: (with(density) { (now.hour * hourHeight + now.minute / 60f * hourHeight).dp.toPx() } - viewport / 3)
                    scroll.scrollTo(if (sharedScroll >= 0) sharedScroll else initial.toInt().coerceAtLeast(0))
                    positioned = true
                    latestOnScroll(scroll.value)
                }
            }
            LaunchedEffect(sharedScroll, active) {
                if (!active && sharedScroll >= 0) scroll.scrollTo(sharedScroll)
            }
            Box(Modifier.fillMaxSize().onGloballyPositioned { viewportBounds = it.boundsInRoot() }.then(if (effective != null) Modifier.testTag("timed_grid_viewport_$date") else Modifier).clipScrollableContainer(Orientation.Vertical)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var zoomed = false
                        var gestureHeight = latestHeight
                        var gestureOffset = scroll.value
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.count { it.pressed } >= 2) {
                                val scale = event.calculateZoom()
                                if (scale.isFinite() && scale != 1f) {
                                    val old = gestureHeight
                                    val next = (old * scale).coerceIn(32f, 160f)
                                    val pivot = event.calculateCentroid(useCurrent = false).y
                                    val newOffset = ((gestureOffset + pivot) * next / old - pivot).toInt().coerceAtLeast(0)
                                    gestureHeight = next
                                    gestureOffset = newOffset
                                    latestOnHeight(next)
                                    scope.launch {
                                        // Wait for the new grid to be measured before clamping against its scroll range.
                                        withFrameNanos { }; withFrameNanos { }
                                        scroll.scrollTo(newOffset)
                                    }
                                    zoomed = true
                                }
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                        if (zoomed) latestOnFinish(gestureHeight)
                    }
                }.verticalScroll(scroll)) {
                DayGrid(date, events, hourHeight, now, zone, use24Hour,
                    { event -> cancelGesture(); onEventClick(event) }, background = background,
                    interactions = effective, viewport = if (effective != null) TimedGridViewport(viewportBounds, { scroll.value }, { scroll.scrollBy(it) }) else null,
                    onPreviewChanged = { preview = it }, cancelSignal = cancelSignal)
            }
        }
    }
}
