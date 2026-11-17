package dev.swapnilraj.calendar.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.swapnilraj.calendar.data.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

private const val MiddlePage = Int.MAX_VALUE / 2

@Composable
fun DayScreen(now: ZonedDateTime, use24Hour: Boolean, initialHourHeight: Float,
    eventsForDay: (LocalDate, ZoneId) -> Flow<CalendarState>, onSaveHourHeight: (Float) -> Unit,
    onEventClick: (CalEvent) -> Unit, initialDate: LocalDate = now.toLocalDate(), initialScrollHour: Double? = null,
    actions: @Composable RowScope.() -> Unit = {},
    summary: @Composable RowScope.(LocalDate) -> Unit = {},
    background: @Composable BoxScope.(LocalDate) -> Unit = {}) {
    val zone = now.zone
    val anchorText = rememberSaveable { initialDate.toString() }
    val anchor = LocalDate.parse(anchorText)
    val pager = rememberPagerState(initialPage = MiddlePage, pageCount = { Int.MAX_VALUE })
    val scope = rememberCoroutineScope()
    var height by rememberSaveable { mutableFloatStateOf(initialHourHeight.coerceIn(32f, 160f)) }
    var sharedScroll by rememberSaveable { mutableIntStateOf(-1) }
    val visibleDate = anchor.plusDays((pager.currentPage - MiddlePage).toLong())
    Surface(Modifier.fillMaxSize()) {
      Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(visibleDate.format(DateTimeFormatter.ofPattern("MMMM yyyy")), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            actions()
            IconButton(onClick = { scope.launch { pager.animateScrollToPage((pager.currentPage - 1).coerceAtLeast(0)) } }) {
                Chevron(false, "Previous day")
            }
            IconButton(onClick = {
                val target = (MiddlePage.toLong() + ChronoUnit.DAYS.between(anchor, now.toLocalDate())).toInt()
                scope.launch { pager.animateScrollToPage(target) }
            }, modifier = Modifier.semantics { contentDescription = "Go to today" }) {
                val color = MaterialTheme.colorScheme.onSurface
                Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.matchParentSize()) {
                        drawRoundRect(color, Offset(2.dp.toPx(), 4.dp.toPx()), Size(size.width - 4.dp.toPx(), size.height - 6.dp.toPx()),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()), style = Stroke(1.5.dp.toPx()))
                        drawLine(color, Offset(2.dp.toPx(), 10.dp.toPx()), Offset(size.width - 2.dp.toPx(), 10.dp.toPx()), 1.5.dp.toPx())
                    }
                    Text(now.dayOfMonth.toString(), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
                }
            }
            IconButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }) { Chevron(true, "Next day") }
        }
        HorizontalPager(pager, modifier = Modifier.weight(1f), key = { it }) { page ->
            val date = anchor.plusDays((page - MiddlePage).toLong())
            var retry by remember { mutableIntStateOf(0) }
            val flow = remember(date, zone, retry) { eventsForDay(date, zone) }
            val state by flow.collectAsStateWithLifecycle(initialValue = CalendarState.Ready(emptyList()))
            Column(Modifier.fillMaxSize().semantics { contentDescription = "Day ${date}" }) {
                if (state == CalendarState.Unavailable || state == CalendarState.PermissionRequired) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (state == CalendarState.PermissionRequired) "Calendar access is needed" else "Calendars couldn’t be read", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { retry++ }) { Text("Retry") }
                    }
                }
                DayPage(date, (state as? CalendarState.Ready)?.events.orEmpty(), now, zone, use24Hour, height,
                    sharedScroll, page == pager.currentPage, initialScrollHour,
                    onScroll = { sharedScroll = it }, onHourHeight = { height = it },
                    onZoomFinished = { onSaveHourHeight(height) }, onEventClick,
                    summary = { summary(date) }, background = { background(date) })
            }
        }
    }
    }
}

@Composable
private fun Chevron(right: Boolean, description: String) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(20.dp).semantics { contentDescription = description }) {
        val tip = if (right) 0.65f else 0.35f
        val other = 1 - tip
        drawLine(color, Offset(size.width * other, size.height * 0.25f), Offset(size.width * tip, size.height / 2), 1.5.dp.toPx(), StrokeCap.Round)
        drawLine(color, Offset(size.width * tip, size.height / 2), Offset(size.width * other, size.height * 0.75f), 1.5.dp.toPx(), StrokeCap.Round)
    }
}
