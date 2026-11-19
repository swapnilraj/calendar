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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.PathEffect
import dev.swapnilraj.calendar.ui.grid.*
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
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
    showTimeGutter: Boolean = true,
    interactions: TimedGridInteractions? = null,
    viewport: TimedGridViewport? = null,
    onPreviewChanged: (TimedGridPreview?) -> Unit = {},
    cancelSignal: Long = 0,
    externalPreview: TimedGridPreview? = null) {
    val gutterWidth = if (showTimeGutter) calendarGutter(date, use24Hour) else 0.dp
    val minimumHeight = minimumEventHeight().value
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current
    val fontScale = density.fontScale
    var gridCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var visiblePreview by remember { mutableStateOf<TimedGridPreview?>(null) }
    val geometry = remember(date, zone) { DayGeometry(date, zone) }
    val placements = remember(events, date, zone, hourHeight, minimumHeight) {
        layoutEvents(timedEvents(events, date, zone), minimumHeight.toDouble() * 60 / hourHeight)
    }
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    val surface = if (LocalDaylightDarkTheme.current) androidx.compose.ui.graphics.Color(0xff191919) else MaterialTheme.colorScheme.surface
    BoxWithConstraints(modifier.fillMaxWidth().height((hourHeight * 24).dp)) {
        val areaWidth = (maxWidth - gutterWidth - 4.dp).coerceAtLeast(1.dp)
        val occupied = placements.map { placed ->
            val start = (placed.timed.startMinute / 60 * hourHeight).toFloat()
            val height = maxOf(minimumHeight, ((placed.timed.endMinute - placed.timed.startMinute) / 60 * hourHeight).toFloat()).coerceAtMost(hourHeight * 24 - start)
            val columnWidth = areaWidth / placed.columns
            val width = (columnWidth - 2.dp).coerceAtLeast(1.dp)
            val relative = gutterWidth + columnWidth * placed.column + 2.dp
            with(density) {
                val x = if (rtl) maxWidth.toPx() - relative.toPx() - width.toPx() else relative.toPx()
                Rect(x, start.dp.toPx(), x + width.toPx(), (start + height).dp.toPx())
            }
        }
        val controller = rememberTimedGridController(GridGestureRuntime(date, zone, events, hourHeight, density.density, fontScale, rtl,
            with(density) { gutterWidth.toPx() }, interactions, viewport, gridCoordinates, occupied) { visiblePreview = it; onPreviewChanged(it) })
        LaunchedEffect(controller, cancelSignal) { controller.cancel() }
        val createLabel = if (viewport?.dateAtRoot != null)
            stringResource(R.string.multiday_grid_create_action, date.format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.FULL).withLocale(LocalLocale.current.platformLocale)))
            else stringResource(R.string.timed_grid_create_action)
        Box(Modifier.matchParentSize().clipToBounds().background(surface).onGloballyPositioned { gridCoordinates = it }
            .timedGridTapGuard(controller).timedGridGestureInput(controller).then(if (interactions?.creationEnabled == true) Modifier.semantics {
                customActions = listOf(CustomAccessibilityAction(createLabel) { interactions.onCreateInEditor?.invoke(date); true })
            } else Modifier)) {
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
        val preview = (externalPreview ?: visiblePreview)?.takeIf { it.contextKey == interactions?.contextKey &&
            (externalPreview != null || controller.active?.events == events) }?.result
        val previewPlacements = (preview as? dev.swapnilraj.calendar.core.GridPreviewResult.Ready)?.time?.let { time ->
            // Synthetic geometry only: this object is never a proposal source, callback or authority.
            layoutEvents(timedEvents(listOf(CalEvent(0, time.start, time.end, "", 0)), date, zone), minimumHeight.toDouble() * 60 / hourHeight)
        }.orEmpty()
        Canvas(Modifier.matchParentSize()) {
            val left = if (rtl) 2.dp.toPx() else (gutterWidth + 2.dp).toPx()
            val width = (size.width - gutterWidth.toPx() - 4.dp.toPx()).coerceAtLeast(0f)
            val stroke = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
            previewPlacements.forEach { placed ->
                val top = (placed.timed.startMinute / 60 * hourHeight).toFloat().dp.toPx()
                val height = ((placed.timed.endMinute - placed.timed.startMinute) / 60 * hourHeight).toFloat().dp.toPx()
                drawRoundRect(primary, Offset(left, top), Size(width, height), style = stroke)
            }
            if (preview is dev.swapnilraj.calendar.core.GridPreviewResult.NeedsReview && preview.snappedTarget.date == date) {
                val y = (preview.snappedTarget.minute / 60 * hourHeight).toFloat().dp.toPx()
                drawLine(primary, Offset(left, y), Offset(left + width, y), 2.dp.toPx(), pathEffect = stroke.pathEffect)
            }
        }
        placements.forEach { placed ->
            key(placed.timed.key) {
                val start = (placed.timed.startMinute / 60 * hourHeight).toFloat()
                val height = maxOf(minimumHeight, ((placed.timed.endMinute - placed.timed.startMinute) / 60 * hourHeight).toFloat())
                    .coerceAtMost(hourHeight * 24 - start)
                val columnWidth = areaWidth / placed.columns
                val source = editableGridSource(placed.timed.event)
                val segment = placed.timed.segment?.key
                val eligible = source != null && segment != null && interactions?.eligible(placed.timed.event) == true
                val resize = eligible && segment?.end == source?.time?.end
                val gestures = if (eligible) Modifier.timedGridGestureInput(controller, source, segment, resize) else Modifier
                EventBlock(placed.timed.event, height, zone, use24Hour,
                    Modifier.offset(x = gutterWidth + columnWidth * placed.column + 2.dp, y = start.dp)
                        .width((columnWidth - 2.dp).coerceAtLeast(1.dp)).height(height.dp).then(gestures),
                    onClick = { if (!controller.suppressTap) onEventClick(it) }, timedSegment = placed.timed,
                    onOpenEditor = if (eligible) ({ interactions?.onOpenEditor?.invoke(requireNotNull(source).ref) }) else null,
                    resizeHandle = resize)
            }
        }
    }
    }
}
