package dev.swapnilraj.calendar.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.swapnilraj.calendar.data.CalEvent
import java.time.*
import java.time.format.DateTimeFormatter

@Composable
fun DayGrid(date: LocalDate, events: List<CalEvent>, hourHeight: Float, now: ZonedDateTime,
    zone: ZoneId, use24Hour: Boolean, onEventClick: (CalEvent) -> Unit,
    modifier: Modifier = Modifier,
    background: @Composable BoxScope.() -> Unit = {}) {
    val gutterWidth = calendarGutter(date, use24Hour)
    val minimumHeight = minimumEventHeight().value
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val fontScale = LocalDensity.current.fontScale
    val placements = remember(events, date, zone, hourHeight, minimumHeight) {
        layoutEvents(timedEvents(events, date, zone), minimumHeight.toDouble() * 60 / hourHeight)
    }
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    val surface = if (isSystemInDarkTheme()) androidx.compose.ui.graphics.Color(0xff191919) else MaterialTheme.colorScheme.surface
    BoxWithConstraints(modifier.fillMaxWidth().height((hourHeight * 24).dp).clipToBounds()
        .background(surface)) {
        CompositionLocalProvider(LocalCalendarGutter provides gutterWidth) { background() }
        Canvas(Modifier.matchParentSize()) {
            val gutter = if (rtl) size.width - gutterWidth.toPx() else gutterWidth.toPx()
            val hourPx = hourHeight.dp.toPx()
            for (hour in 0..24) drawLine(gridColor, Offset(gutter, hour * hourPx), Offset(if (rtl) 0f else size.width, hour * hourPx), 0.5.dp.toPx())
            drawLine(gridColor, Offset(gutter, 0f), Offset(gutter, size.height), 0.5.dp.toPx())
        }
        val locale = LocalLocale.current.platformLocale
        val formatter = remember(use24Hour, locale) { DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "h a", locale) }
        for (hour in 1..23) Text(LocalTime.of(hour, 0).format(formatter), color = labelColor, fontSize = 10.sp,
            textAlign = TextAlign.End, maxLines = 1, softWrap = false,
            modifier = Modifier.offset(y = (hourHeight * hour - 8 * fontScale).dp).width(gutterWidth).padding(end = 8.dp))
        val areaWidth = (maxWidth - gutterWidth - 4.dp).coerceAtLeast(1.dp)
        placements.forEach { placed ->
            key(placed.timed.event.key) {
                val start = (placed.timed.startMinute / 60 * hourHeight).toFloat()
                val height = maxOf(minimumHeight, ((placed.timed.endMinute - placed.timed.startMinute) / 60 * hourHeight).toFloat())
                    .coerceAtMost(hourHeight * 24 - start)
                val columnWidth = areaWidth / placed.columns
                EventBlock(placed.timed.event, height, zone, use24Hour,
                    Modifier.offset(x = gutterWidth + columnWidth * placed.column + 2.dp, y = start.dp)
                        .width((columnWidth - 2.dp).coerceAtLeast(1.dp)).height(height.dp), onEventClick)
            }
        }
        if (date == now.withZoneSameInstant(zone).toLocalDate()) {
            val time = now.withZoneSameInstant(zone).toLocalTime()
            val y = time.toSecondOfDay() / 3600f * hourHeight
            Canvas(Modifier.matchParentSize().semantics { contentDescription = "Current time ${time.format(formatter)}" }) {
                val lineY = y.dp.toPx()
                val edge = if (rtl) size.width - gutterWidth.toPx() else gutterWidth.toPx()
                drawLine(primary, Offset(edge, lineY), Offset(if (rtl) 0f else size.width, lineY), 1.5.dp.toPx())
                drawCircle(primary, 4.dp.toPx(), Offset(edge, lineY))
            }
        }
    }
}
