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
import dev.swapnilraj.calendar.data.CalEvent
import java.time.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun DayPage(date: LocalDate, events: List<CalEvent>, now: ZonedDateTime, zone: ZoneId, use24Hour: Boolean,
    hourHeight: Float, sharedScroll: Int, active: Boolean, initialScrollHour: Double?,
    onScroll: (Int) -> Unit, onHourHeight: (Float) -> Unit, onZoomFinished: () -> Unit,
    onEventClick: (CalEvent) -> Unit,
    summary: @Composable RowScope.() -> Unit = {},
    background: @Composable BoxScope.() -> Unit = {}) {
    val scroll = rememberScrollState(maxOf(0, sharedScroll))
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val latestHeight by rememberUpdatedState(hourHeight)
    val latestOnHeight by rememberUpdatedState(onHourHeight)
    val latestOnFinish by rememberUpdatedState(onZoomFinished)
    val latestActive by rememberUpdatedState(active)
    val latestOnScroll by rememberUpdatedState(onScroll)
    var positioned by remember { mutableStateOf(false) }
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.value }.distinctUntilChanged().collect { if (latestActive && positioned) latestOnScroll(it) }
    }
    Column(Modifier.fillMaxSize()) {
        DayHeader(date, now.withZoneSameInstant(zone).toLocalDate(), events.filter { it.allDay }, zone, use24Hour, onEventClick, summary)
        HorizontalDivider(thickness = 0.5.dp)
        BoxWithConstraints(Modifier.weight(1f)) {
            val viewport = with(density) { maxHeight.toPx() }
            LaunchedEffect(active, sharedScroll < 0) {
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
            Box(Modifier.fillMaxSize().clipScrollableContainer(Orientation.Vertical)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var zoomed = false
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.count { it.pressed } >= 2) {
                                val scale = event.calculateZoom()
                                if (scale.isFinite() && scale != 1f) {
                                    val old = latestHeight
                                    val next = (old * scale).coerceIn(32f, 160f)
                                    val pivot = event.calculateCentroid(useCurrent = false).y
                                    val newOffset = ((scroll.value + pivot) * next / old - pivot).toInt().coerceAtLeast(0)
                                    latestOnHeight(next)
                                    scope.launch { withFrameNanos { }; scroll.scrollTo(newOffset) }
                                    zoomed = true
                                }
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                        if (zoomed) latestOnFinish()
                    }
                }.verticalScroll(scroll)) {
                DayGrid(date, events, hourHeight, now, zone, use24Hour, onEventClick, background = background)
            }
        }
    }
}
