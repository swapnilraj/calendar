package dev.swapnilraj.calendar.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import dev.swapnilraj.calendar.ui.theme.LocalDaylightDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.swapnilraj.calendar.data.CalEvent
import dev.swapnilraj.calendar.core.ClockChangeKind
import dev.swapnilraj.calendar.core.DayGeometry
import dev.swapnilraj.calendar.R
import java.time.*
import java.time.format.DateTimeFormatter

@Composable
fun DayGrid(date: LocalDate, events: List<CalEvent>, hourHeight: Float, now: ZonedDateTime,
    zone: ZoneId, use24Hour: Boolean, onEventClick: (CalEvent) -> Unit,
    modifier: Modifier = Modifier,
    background: @Composable BoxScope.() -> Unit = {},
    showTimeGutter: Boolean = true) {
    val gutterWidth = if (showTimeGutter) calendarGutter(date, use24Hour) else 0.dp
    val minimumHeight = minimumEventHeight().value
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val fontScale = LocalDensity.current.fontScale
    val geometry = remember(date, zone) { DayGeometry(date, zone) }
    val placements = remember(events, date, zone, hourHeight, minimumHeight) {
        layoutEvents(timedEvents(events, date, zone), minimumHeight.toDouble() * 60 / hourHeight)
    }
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    val surface = if (LocalDaylightDarkTheme.current) androidx.compose.ui.graphics.Color(0xff191919) else MaterialTheme.colorScheme.surface
    BoxWithConstraints(modifier.fillMaxWidth().height((hourHeight * 24).dp).clipToBounds()
        .background(surface)) {
        CompositionLocalProvider(LocalCalendarGutter provides gutterWidth) { background() }
        // Restore the plain calendar substrate only where the fixed clock rows cannot identify
        // a unique forecast instant. Ordinary-day weather rendering remains untouched.
        Canvas(Modifier.matchParentSize()) {
            val width = (size.width - gutterWidth.toPx()).coerceAtLeast(0f)
            val left = if (rtl) 0f else gutterWidth.toPx()
            geometry.clockChanges.forEach { band ->
                val top = (band.startMinute / 60 * hourHeight).toFloat().dp.toPx()
                val bottom = (band.endMinute / 60 * hourHeight).toFloat().dp.toPx()
                drawRect(surface, Offset(left, top), Size(width, bottom - top))
                drawLine(gridColor, Offset(left, top), Offset(left + width, top), 1.dp.toPx())
                drawLine(gridColor, Offset(left, bottom), Offset(left + width, bottom), 1.dp.toPx())
            }
        }
        Canvas(Modifier.matchParentSize()) {
            val gutter = if (rtl) size.width - gutterWidth.toPx() else gutterWidth.toPx()
            val hourPx = hourHeight.dp.toPx()
            for (hour in 0..24) drawLine(gridColor, Offset(gutter, hour * hourPx), Offset(if (rtl) 0f else size.width, hour * hourPx), 0.5.dp.toPx())
            if (showTimeGutter) drawLine(gridColor, Offset(gutter, 0f), Offset(gutter, size.height), 0.5.dp.toPx())
        }
        val locale = LocalLocale.current.platformLocale
        val formatter = remember(use24Hour, locale) { DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "h a", locale) }
        val bandFormatter = remember(use24Hour, locale) { DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "h:mm a", locale) }
        if (showTimeGutter) geometry.clockChanges.forEach { band ->
            fun clock(minute: Double): String = if (minute >= 1440.0) "24:00"
                else LocalTime.MIDNIGHT.plusSeconds((minute * 60).toLong()).format(bandFormatter)
            val label = stringResource(
                if (band.kind == ClockChangeKind.GAP) R.string.dst_clock_forward else R.string.dst_clock_back,
                "\u202a${clock(band.startMinute)} – ${clock(band.endMinute)} · ${band.offsetBeforeLabel} → ${band.offsetAfterLabel}\u202c",
            )
            // Keep offset cues in the gutter: event surfaces must never cover half a legend,
            // and the legend must never cover an event. Its full clock-change wording is spoken.
            fun compact(offset: ZoneOffset): String = if (offset.totalSeconds % 3600 == 0)
                String.format(java.util.Locale.ROOT, "%+03d", offset.totalSeconds / 3600) else offset.id
            val cue = if (hourHeight >= 40 * fontScale) "${compact(band.offsetBefore)} →\n${compact(band.offsetAfter)}"
                else "${compact(band.offsetBefore)}→${compact(band.offsetAfter)}"
            Text("\u202a$cue\u202c", color = labelColor,
                fontSize = 8.sp, lineHeight = 10.sp, maxLines = 2, softWrap = false, textAlign = TextAlign.End,
                modifier = Modifier.offset(
                    y = (kotlin.math.floor(band.startMinute / 60) * hourHeight + 12 * fontScale).toFloat().dp)
                    .width(gutterWidth).padding(end = 8.dp)
                    .clearAndSetSemantics { contentDescription = label })
        }
        if (showTimeGutter) DayTimeGutter(hourHeight, use24Hour, Modifier.width(gutterWidth))
        // Current-time decoration is below event surfaces so it cannot strike through their text.
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
        val areaWidth = (maxWidth - gutterWidth - 4.dp).coerceAtLeast(1.dp)
        placements.forEach { placed ->
            key(placed.timed.key) {
                val start = (placed.timed.startMinute / 60 * hourHeight).toFloat()
                val height = maxOf(minimumHeight, ((placed.timed.endMinute - placed.timed.startMinute) / 60 * hourHeight).toFloat())
                    .coerceAtMost(hourHeight * 24 - start)
                val columnWidth = areaWidth / placed.columns
                EventBlock(placed.timed.event, height, zone, use24Hour,
                    Modifier.offset(x = gutterWidth + columnWidth * placed.column + 2.dp, y = start.dp)
                        .width((columnWidth - 2.dp).coerceAtLeast(1.dp)).height(height.dp), onEventClick,
                    timedSegment = placed.timed)
            }
        }
    }
}
