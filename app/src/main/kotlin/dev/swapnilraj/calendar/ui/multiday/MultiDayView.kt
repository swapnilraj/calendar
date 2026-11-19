package dev.swapnilraj.calendar.ui.multiday

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.swapnilraj.calendar.R
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Ready, app-filtered occurrences for exactly 3 or 7 consecutive dates in years 1..9999.
 * Caller owns data coverage/loading, range alignment, and the shared selected date. Selecting a column
 * never shifts the range. All columns share a wall-clock anchor (minutes from midnight), zoom and
 * vertical scroll; readable columns pan horizontally when they do not fit. No provider queries.
 * [showWeekends] omits Saturday/Sunday only from a seven-date week; three-day ranges keep every date.
 * Weather/summary slots must contain only actual forecast coverage for their supplied date.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiDayView(
    range: CalendarDateRange,
    selectedDate: LocalDate,
    events: List<EventOccurrence>,
    now: ZonedDateTime,
    displayZone: ZoneId,
    use24Hour: Boolean,
    initialHourHeight: Float,
    onSaveHourHeight: (Float) -> Unit,
    onSelectDate: (LocalDate) -> Unit,
    onOpenEvent: (EventRef) -> Unit,
    onShowDateEvents: (LocalDate) -> Unit,
    onNavigateRange: (CalendarDateRange) -> Unit,
    initialScrollMinute: Double? = null,
    onScrollMinuteChanged: (Double) -> Unit = {},
    summary: @Composable RowScope.(LocalDate) -> Unit = {},
    background: @Composable BoxScope.(LocalDate) -> Unit = {},
    modifier: Modifier = Modifier,
    showWeekends: Boolean = true,
    showNavigationChrome: Boolean = true,
) {
    require(initialHourHeight.isFinite())
    require(initialScrollMinute == null || initialScrollMinute.isFinite())
    require(selectedDate in MultiDayMinDate..MultiDayMaxDate)
    val rangeDates = remember(range) { multiDayDates(range) }
    val dates = remember(rangeDates, showWeekends) {
        if (rangeDates.size == 7 && !showWeekends) rangeDates.filter { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY }
        else rangeDates
    }
    val hiddenSelection = selectedDate in range && selectedDate !in dates
    val byDate = remember(range, events, displayZone) { multiDayEvents(range, events, displayZone) }
    val localNow = now.withZoneSameInstant(displayZone)
    val today = localNow.toLocalDate()
    val locale = LocalLocale.current.platformLocale
    val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    val fullDates = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)
    val pickerLabel = stringResource(R.string.multiday_date_picker, selectedDate.format(fullDates))
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val columnLineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
    val gutter = dates.map { calendarGutter(it, use24Hour) }.max()
    val horizontal = rememberScrollState()
    val vertical = rememberScrollState()
    var hourHeight by remember(initialHourHeight) { mutableFloatStateOf(initialHourHeight.coerceIn(32f, 160f)) }
    var anchor by remember { mutableDoubleStateOf(initialScrollMinute?.coerceIn(0.0, 1440.0) ?: 0.0) }
    var initialPositioned by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(true) }
    var pickingDate by remember { mutableStateOf(false) }
    val latestOnScroll by rememberUpdatedState(onScrollMinuteChanged)
    val latestHeight by rememberUpdatedState(hourHeight)
    val latestDensity by rememberUpdatedState(density)
    val earlier = adjacentMultiDayRange(range, false)
    val later = adjacentMultiDayRange(range, true)
    fun navigate(date: LocalDate) {
        onSelectDate(date)
        if (date !in range) onNavigateRange(multiDayWindow(date, rangeDates.size))
    }
    fun zoom(next: Float, pivot: Float = 0f) {
        val clamped = next.coerceIn(32f, 160f)
        if (clamped == hourHeight) return
        val oldPx = with(density) { hourHeight.dp.toPx() }
        val nextPx = with(density) { clamped.dp.toPx() }
        anchor = (((vertical.value + pivot) / oldPx - pivot / nextPx) * 60).toDouble().coerceIn(0.0, 1440.0)
        restoring = true
        hourHeight = clamped
    }
    val latestSetZoom by rememberUpdatedState<(Float, Double) -> Unit>({ next, minute ->
        restoring = true
        anchor = minute.coerceIn(0.0, 1440.0)
        hourHeight = next
    })
    val latestOnSave by rememberUpdatedState(onSaveHourHeight)
    LaunchedEffect(vertical) {
        snapshotFlow { vertical.value }.distinctUntilChanged().collect { value ->
            if (!restoring && initialPositioned) {
                anchor = (value / with(latestDensity) { latestHeight.dp.toPx() } * 60).toDouble().coerceIn(0.0, 1440.0)
                latestOnScroll(anchor)
            }
        }
    }
    Surface(modifier.fillMaxSize()) {
        Column {
            if (showNavigationChrome) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(if (rangeDates.size == 3) R.string.multiday_three_days else R.string.multiday_week),
                    style = MaterialTheme.typography.titleLarge.copy(textDirection = TextDirection.Content), modifier = Modifier.weight(1f))
                TextButton(enabled = today in MultiDayMinDate..MultiDayMaxDate, onClick = { navigate(today) }) { Text(stringResource(R.string.multiday_today)) }
            }
            TextButton(onClick = { pickingDate = true }, modifier = Modifier.fillMaxWidth().testTag("multiday_date_picker")
                .semantics { contentDescription = pickerLabel }) {
                Text("\u202a${range.start.format(formatter)} – ${range.endExclusive.minusDays(1).format(formatter)}\u202c",
                    style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.Content))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(enabled = earlier != null, onClick = { earlier?.let { onSelectDate(it.start); onNavigateRange(it) } }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.multiday_previous))
                }
                TextButton(enabled = later != null, onClick = { later?.let { onSelectDate(it.start); onNavigateRange(it) } }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.multiday_next))
                }
            }
            }
            if (hiddenSelection) {
                val label = stringResource(R.string.calendar_view_day_events, selectedDate.format(fullDates))
                TextButton(onClick = { onShowDateEvents(selectedDate) }, modifier = Modifier.fillMaxWidth()
                    .testTag("multiday_hidden_selection").semantics { contentDescription = label; selected = true }) {
                    Text(label, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content))
                }
            }
            if (showNavigationChrome) {
            // Explicit controls are the keyboard/TalkBack alternative to a two-finger zoom gesture.
            val zoomOutLabel = stringResource(R.string.multiday_zoom_out)
            val zoomInLabel = stringResource(R.string.multiday_zoom_in)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(enabled = hourHeight > 32f, onClick = { zoom(hourHeight - 16); onSaveHourHeight(hourHeight) },
                    modifier = Modifier.semantics { contentDescription = zoomOutLabel }) { Text("−") }
                Text(stringResource(R.string.multiday_zoom_level, (hourHeight / 64f * 100).toInt()),
                    style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.Content),
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                TextButton(enabled = hourHeight < 160f, onClick = { zoom(hourHeight + 16); onSaveHourHeight(hourHeight) },
                    modifier = Modifier.semantics { contentDescription = zoomInLabel }) { Text("+") }
            }
            }
            HorizontalDivider(thickness = 0.5.dp)
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val columnWidth = maxOf((96f * density.fontScale.coerceAtLeast(1f)).dp, (maxWidth - gutter).coerceAtLeast(0.dp) / dates.size)
                val horizontalViewport = with(density) { (maxWidth - gutter).coerceAtLeast(0.dp).toPx() }
                LaunchedEffect(range, dates, selectedDate, columnWidth, horizontalViewport) {
                    val index = dates.indexOf(selectedDate)
                    if (index >= 0) {
                        withFrameNanos { }
                        val start = index * with(density) { columnWidth.toPx() }
                        val end = start + with(density) { columnWidth.toPx() }
                        if (start < horizontal.value) horizontal.scrollTo(start.toInt())
                        else if (end > horizontal.value + horizontalViewport) horizontal.scrollTo((end - horizontalViewport).toInt())
                    }
                }
                Column {
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                        Spacer(Modifier.width(gutter))
                        Row(Modifier.weight(1f).horizontalScroll(horizontal).testTag("multiday_headers")) {
                            dates.forEach { date ->
                                Box(Modifier.width(columnWidth).testTag("multiday_header_$date")) {
                                    val dayEvents = byDate.getValue(date)
                                    DayHeader(date, today, dayEvents.filter { it.allDay }, displayZone, use24Hour,
                                        onEventClick = { event -> onSelectDate(date); event.ref?.let(onOpenEvent) },
                                        summary = { summary(date) }, dayEvents = dayEvents, stacked = true, selected = date == selectedDate,
                                        onShowDateEvents = { onSelectDate(date); onShowDateEvents(date) })
                                }
                            }
                        }
                    }
                    HorizontalDivider(thickness = 0.5.dp)
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        val viewport = with(density) { maxHeight.toPx() }
                        LaunchedEffect(hourHeight, density.density, viewport) {
                            if (!initialPositioned && initialScrollMinute == null) {
                                val nowMinute = localNow.toLocalTime().toSecondOfDay() / 60.0
                                anchor = (nowMinute - viewport / with(density) { hourHeight.dp.toPx() } * 20).coerceAtLeast(0.0)
                            }
                            restoring = true
                            withFrameNanos { }
                            vertical.scrollTo((anchor / 60 * with(density) { hourHeight.dp.toPx() }).toInt())
                            initialPositioned = true
                            restoring = false
                            anchor = vertical.value / with(density) { hourHeight.dp.toPx() } * 60.0
                            latestOnScroll(anchor)
                        }
                        Box(Modifier.fillMaxSize().clipToBounds().pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                var zoomed = false
                                var gestureHeight = latestHeight
                                var gestureOffset = vertical.value.toFloat()
                                do {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (event.changes.count { it.pressed } >= 2) {
                                        val scale = event.calculateZoom()
                                        if (scale.isFinite() && scale != 1f) {
                                            val next = (gestureHeight * scale).coerceIn(32f, 160f)
                                            val pivot = event.calculateCentroid(useCurrent = false).y
                                            gestureOffset = ((gestureOffset + pivot) * next / gestureHeight - pivot).coerceAtLeast(0f)
                                            gestureHeight = next
                                            latestSetZoom(next, (gestureOffset / with(latestDensity) { next.dp.toPx() } * 60).toDouble())
                                            zoomed = true
                                        }
                                        event.changes.forEach { it.consume() }
                                    }
                                } while (event.changes.any { it.pressed })
                                if (zoomed) latestOnSave(latestHeight)
                            }
                        }.verticalScroll(vertical).testTag("multiday_time_scroll")) {
                            Row {
                                DayTimeGutter(hourHeight, use24Hour, Modifier.width(gutter).testTag("multiday_time_gutter"))
                                Row(Modifier.weight(1f).horizontalScroll(horizontal).testTag("multiday_columns")) {
                                    dates.forEach { date ->
                                        Box(Modifier.width(columnWidth).drawWithContent {
                                            drawContent()
                                            val edge = if (rtl) size.width else 0f
                                            drawLine(columnLineColor, Offset(edge, 0f), Offset(edge, size.height), 0.5.dp.toPx())
                                        }.testTag("multiday_grid_$date")) {
                                            DayGrid(date, byDate.getValue(date), hourHeight, now, displayZone, use24Hour,
                                                onEventClick = { event -> onSelectDate(date); event.ref?.let(onOpenEvent) },
                                                showTimeGutter = false, background = { background(date) })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (pickingDate) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = selectedDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), yearRange = 1..9999)
        DatePickerDialog(onDismissRequest = { pickingDate = false }, confirmButton = {
            TextButton(enabled = picker.selectedDateMillis != null, onClick = {
                picker.selectedDateMillis?.let { navigate(Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC).toLocalDate()); pickingDate = false }
            }) { Text(stringResource(R.string.multiday_confirm)) }
        }, dismissButton = { TextButton(onClick = { pickingDate = false }) { Text(stringResource(R.string.multiday_cancel)) } }) { DatePicker(picker, showModeToggle = true) }
    }
}
