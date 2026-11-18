package dev.swapnilraj.calendar.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.platform.testTag
import dev.swapnilraj.calendar.R
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.swapnilraj.calendar.data.*
import dev.swapnilraj.calendar.ui.grid.TimedGridInteractions
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

private const val MiddlePage = Int.MAX_VALUE / 2

@Composable
fun DayScreen(now: ZonedDateTime, use24Hour: Boolean, initialHourHeight: Float,
    eventsForDay: (LocalDate, ZoneId) -> Flow<CalendarState>, onSaveHourHeight: (Float) -> Unit,
    onEventClick: (CalEvent) -> Unit, initialDate: LocalDate = now.toLocalDate(), initialScrollHour: Double? = null,
    actions: @Composable RowScope.() -> Unit = {},
    summary: @Composable RowScope.(LocalDate) -> Unit = {},
    background: @Composable BoxScope.(LocalDate) -> Unit = {},
    onVisibleDateChanged: (LocalDate) -> Unit = {},
    onRetry: () -> Unit = {},
    leadingActions: @Composable RowScope.() -> Unit = {},
    compactTitle: Boolean = false,
    onScrollMinuteChanged: (Double) -> Unit = {},
    onChooseCalendars: (() -> Unit)? = null,
    interactions: TimedGridInteractions? = null,
    onCancelPendingEdit: (() -> Unit)? = null) {
    val zone = now.zone
    val anchorText = rememberSaveable { initialDate.toString() }
    val anchor = LocalDate.parse(anchorText)
    val pager = rememberPagerState(initialPage = MiddlePage, pageCount = { Int.MAX_VALUE })
    val scope = rememberCoroutineScope()
    val latestCancelPendingEdit by rememberUpdatedState(onCancelPendingEdit)
    LaunchedEffect(pager) {
        snapshotFlow { pager.isScrollInProgress }.collect { moving -> if (moving) latestCancelPendingEdit?.invoke() }
    }
    var height by rememberSaveable { mutableFloatStateOf(initialHourHeight.coerceIn(32f, 160f)) }
    // A new saved-state key prevents interpreting a pre-upgrade pixel offset as wall minutes.
    var sharedMinute by key("day-wall-minute-v1") { rememberSaveable { mutableDoubleStateOf(initialScrollHour?.times(60) ?: -1.0) } }
    val density = LocalDensity.current
    val sharedScroll = if (sharedMinute < 0) -1 else (sharedMinute / 60 * with(density) { height.dp.toPx() }).roundToInt()
    val visibleDate = anchor.plusDays((pager.currentPage - MiddlePage).toLong())
    LaunchedEffect(visibleDate) { onVisibleDateChanged(visibleDate) }
    val locale = LocalLocale.current.platformLocale
    val fontScale = LocalDensity.current.fontScale
    Surface(Modifier.fillMaxSize()) {
      Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column {
          if (fontScale > 1.3f) Text(visibleDate.format(DateTimeFormatter.ofPattern("MMMM yyyy", locale)),
              style = MaterialTheme.typography.titleLarge, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
          Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.End) {
            leadingActions()
            if (fontScale > 1.3f) Spacer(Modifier.weight(1f))
            if (fontScale <= 1.3f) Text(visibleDate.format(DateTimeFormatter.ofPattern(if (compactTitle) "MMM yyyy" else "MMMM yyyy", locale)), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            actions()
            IconButton(onClick = { onCancelPendingEdit?.invoke(); scope.launch { pager.animateScrollToPage((pager.currentPage - 1).coerceAtLeast(0)) } }) {
                Chevron(false, "Previous day")
            }
            IconButton(onClick = {
                onCancelPendingEdit?.invoke()
                val target = (MiddlePage.toLong() + ChronoUnit.DAYS.between(anchor, now.toLocalDate())).toInt()
                scope.launch { pager.animateScrollToPage(target) }
            }, modifier = Modifier.semantics { contentDescription = "Go to today" }) {
                val color = MaterialTheme.colorScheme.onSurface
                Box(Modifier.size((28f * fontScale).dp.coerceAtMost(40.dp)), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.matchParentSize()) {
                        drawRoundRect(color, Offset(2.dp.toPx(), 4.dp.toPx()), Size(size.width - 4.dp.toPx(), size.height - 6.dp.toPx()),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()), style = Stroke(1.5.dp.toPx()))
                        drawLine(color, Offset(2.dp.toPx(), 10.dp.toPx()), Offset(size.width - 2.dp.toPx(), 10.dp.toPx()), 1.5.dp.toPx())
                    }
                    Text(now.dayOfMonth.toString(), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
                }
            }
            IconButton(onClick = { onCancelPendingEdit?.invoke(); scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }) { Chevron(true, "Next day") }
        }
        }
        HorizontalPager(pager, modifier = Modifier.weight(1f), key = { it }) { page ->
            val date = anchor.plusDays((page - MiddlePage).toLong())
            var retry by remember { mutableIntStateOf(0) }
            val flow = remember(date, zone, retry, eventsForDay) { eventsForDay(date, zone) }
            val state by key(flow) { flow.collectAsStateWithLifecycle(initialValue = CalendarState.Loading) }
            Column(Modifier.fillMaxSize().semantics { contentDescription = "Day ${date}" }) {
                if (state == CalendarState.Loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state == CalendarState.Unavailable || state == CalendarState.PermissionRequired) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (state == CalendarState.PermissionRequired) "Calendar access is needed" else "Calendars couldn’t be read", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { onRetry(); retry++ }) { Text("Retry") }
                    }
                }
                if (state == CalendarState.LimitExceeded) {
                    Column(Modifier.fillMaxWidth().padding(24.dp).testTag("day_read_limit"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.read_limit_day), style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        if (onChooseCalendars != null) TextButton(onClick = onChooseCalendars) { Text(stringResource(R.string.read_limit_choose_calendars)) }
                        TextButton(onClick = { onRetry(); retry++ }) { Text(stringResource(R.string.read_limit_retry)) }
                    }
                } else DayPage(date, (state as? CalendarState.Ready)?.events.orEmpty(), now, zone, use24Hour, height,
                    sharedScroll, page == pager.currentPage, initialScrollHour,
                    onScroll = { pixels ->
                        sharedMinute = (pixels.toDouble() / with(density) { height.dp.toPx() } * 60).coerceIn(0.0, 1440.0)
                        onScrollMinuteChanged(sharedMinute)
                    }, onHourHeight = { onCancelPendingEdit?.invoke(); height = it },
                    onZoomFinished = onSaveHourHeight, onEventClick,
                    summary = { summary(date) }, background = { background(date) },
                    interactions = interactions?.takeIf { state is CalendarState.Ready && page == pager.currentPage },
                    onCancelPendingEdit = onCancelPendingEdit)
            }
        }
    }
    }
}

@Composable
private fun Chevron(right: Boolean, description: String) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val pointRight = right != (LocalLayoutDirection.current == LayoutDirection.Rtl)
    Canvas(Modifier.size(20.dp).semantics { contentDescription = description }) {
        val tip = if (pointRight) 0.65f else 0.35f
        val other = 1 - tip
        drawLine(color, Offset(size.width * other, size.height * 0.25f), Offset(size.width * tip, size.height / 2), 1.5.dp.toPx(), StrokeCap.Round)
        drawLine(color, Offset(size.width * tip, size.height / 2), Offset(size.width * other, size.height * 0.75f), 1.5.dp.toPx(), StrokeCap.Round)
    }
}
