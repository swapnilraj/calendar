package dev.swapnilraj.calendar.ui.month

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.swapnilraj.calendar.R
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.eventTextColor
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/**
 * Provider reads and persistent navigation state belong to the caller, never individual cells.
 * [onCreateDate] requests an all-day draft on the selected date. Pass [today] from the app clock.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonthView(
    month: YearMonth,
    selectedDate: LocalDate,
    firstDayOfWeek: DayOfWeek,
    events: List<EventOccurrence>,
    displayZone: ZoneId,
    onSelectDate: (LocalDate) -> Unit,
    onOpenEvent: (EventRef) -> Unit,
    onCreateDate: (LocalDate) -> Unit,
    onNavigateMonth: (YearMonth) -> Unit,
    today: LocalDate = LocalDate.now(displayZone),
    modifier: Modifier = Modifier,
    allowCreation: Boolean = true,
    onShowDateEvents: ((LocalDate) -> Unit)? = null,
) {
    val locale = LocalLocale.current.platformLocale
    val chooseDateLabel = stringResource(R.string.month_choose_date)
    val dates = remember(month, firstDayOfWeek) { monthDates(month, firstDayOfWeek) }
    val byDate = remember(dates, events, displayZone) { monthEventsByDate(dates, events, displayZone) }
    var sheetDate by remember { mutableStateOf<LocalDate?>(null) }
    var pickingDate by remember { mutableStateOf(false) }
    val horizontalScroll = rememberScrollState()
    val verticalScroll = rememberScrollState()
    val fontScale = LocalDensity.current.fontScale
    LaunchedEffect(month, firstDayOfWeek) { verticalScroll.scrollTo(0) }
    Surface(modifier.fillMaxSize()) {
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = month > YearMonth.of(1, 1), onClick = { sheetDate = null; onNavigateMonth(month.minusMonths(1)) }) {
                    MonthChevron(false, stringResource(R.string.month_previous))
                }
                TextButton(onClick = { pickingDate = true }, modifier = Modifier.weight(1f)
                    .semantics { contentDescription = "${month.format(DateTimeFormatter.ofPattern("MMMM yyyy", locale))}, ${chooseDateLabel}" }) {
                    Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy", locale)), style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (fontScale <= 1.3f) TextButton(onClick = {
                    sheetDate = null; onSelectDate(today); onNavigateMonth(YearMonth.from(today))
                }) { Text(stringResource(R.string.month_today)) }
                IconButton(enabled = month < YearMonth.of(9999, 12), onClick = { sheetDate = null; onNavigateMonth(month.plusMonths(1)) }) {
                    MonthChevron(true, stringResource(R.string.month_next))
                }
            }
            if (fontScale > 1.3f) TextButton(onClick = { sheetDate = null; onSelectDate(today); onNavigateMonth(YearMonth.from(today)) },
                modifier = Modifier.align(Alignment.End).padding(end = 8.dp)) { Text(stringResource(R.string.month_today)) }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val cellWidth = maxOf(maxWidth / 7, (56f * fontScale).dp)
                val tableWidth = cellWidth * 7
                val cellHeight = maxOf((112f * fontScale).dp, (maxHeight - (16f * fontScale + 16f).dp) / 6)
                Box(Modifier.fillMaxSize().horizontalScroll(horizontalScroll).testTag("month_horizontal_scroll")) {
                    Column(Modifier.width(tableWidth).fillMaxHeight()) {
                        Row(Modifier.width(tableWidth)) {
                            repeat(7) { column ->
                                val weekday = firstDayOfWeek.plus(column.toLong())
                                Text(weekday.getDisplayName(TextStyle.SHORT, locale), style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.width(cellWidth).padding(vertical = 8.dp).semantics {
                                        contentDescription = weekday.getDisplayName(TextStyle.FULL, locale)
                                        heading()
                                    }, textAlign = TextAlign.Center, maxLines = 1)
                            }
                        }
                        Column(Modifier.weight(1f).verticalScroll(verticalScroll).testTag("month_grid")
                            .semantics { collectionInfo = CollectionInfo(6, 7) }) {
                            dates.chunked(7).forEachIndexed { row, week ->
                                Row(Modifier.width(tableWidth).height(IntrinsicSize.Min)) {
                                    week.forEachIndexed { column, date ->
                                        key(date) {
                                            MonthCell(date, month, selectedDate, today, byDate[date].orEmpty(), displayZone,
                                                row, column, Modifier.width(cellWidth).heightIn(min = cellHeight).fillMaxHeight(),
                                                onShowDate = {
                                                    onSelectDate(date)
                                                    if (onShowDateEvents != null) onShowDateEvents(date) else sheetDate = date
                                                }, onOpenEvent)
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
    sheetDate?.let { date ->
        MonthDaySheet(date, byDate[date].orEmpty(), displayZone, onDismiss = { sheetDate = null },
            onOpenEvent = { ref -> sheetDate = null; onOpenEvent(ref) },
            onCreateDate = if (allowCreation) ({ sheetDate = null; onCreateDate(date) }) else null)
    }
    if (pickingDate) {
        val pickerDate = selectedDate.coerceIn(LocalDate.of(1, 1, 1), LocalDate.of(9999, 12, 31))
        val picker = rememberDatePickerState(initialSelectedDateMillis = pickerDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), yearRange = 1..9999)
        DatePickerDialog(onDismissRequest = { pickingDate = false }, confirmButton = {
            TextButton(enabled = picker.selectedDateMillis != null, onClick = {
                picker.selectedDateMillis?.let { millis ->
                    val date = Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC).toLocalDate()
                    pickingDate = false
                    sheetDate = null
                    onSelectDate(date)
                    onNavigateMonth(YearMonth.from(date))
                }
            }) { Text(stringResource(R.string.month_go_to_date)) }
        }, dismissButton = { TextButton(onClick = { pickingDate = false }) { Text(stringResource(R.string.month_cancel)) } }) {
            DatePicker(picker, showModeToggle = true)
        }
    }
}

@Composable
private fun MonthCell(date: LocalDate, month: YearMonth, selectedDate: LocalDate, today: LocalDate,
    events: List<EventOccurrence>, zone: ZoneId, row: Int, column: Int, modifier: Modifier,
    onShowDate: () -> Unit, onOpenEvent: (EventRef) -> Unit) {
    val locale = LocalLocale.current.platformLocale
    val fullDate = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale))
    val count = pluralStringResource(R.plurals.month_event_count, events.size, events.size)
    val openLabel = stringResource(R.string.month_events_on_date, fullDate)
    val selected = date == selectedDate
    val todayLabel = stringResource(R.string.month_today)
    val normal = MaterialTheme.colorScheme.surface
    Column(modifier.border(0.5.dp, MaterialTheme.colorScheme.outlineVariant).background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) else normal)
        .semantics { contentDescription = "$fullDate, $count${if (date == today) ", $todayLabel" else ""}"; this.selected = selected
            collectionItemInfo = CollectionItemInfo(row, 1, column, 1) }
        .clickable(enabled = date.year in 1..9999, onClickLabel = openLabel, onClick = onShowDate).padding(2.dp).testTag("month_date_$date")) {
        Box(Modifier.fillMaxWidth().heightIn(min = (32f * LocalDensity.current.fontScale).dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size((28f * LocalDensity.current.fontScale).dp)
                .then(if (date == today) Modifier.background(MaterialTheme.colorScheme.primary, CircleShape) else Modifier),
                contentAlignment = Alignment.Center) {
                Text(date.dayOfMonth.toString(), fontSize = 14.sp, lineHeight = 18.sp,
                    color = when { date == today -> MaterialTheme.colorScheme.onPrimary
                        YearMonth.from(date) != month -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                        else -> MaterialTheme.colorScheme.onSurface }, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(), maxLines = 1)
            }
        }
        events.take(2).forEach { event -> MonthEventChip(event, date, zone, onOpenEvent) }
        if (events.size > 2) {
            val more = events.size - 2
            val moreLabel = pluralStringResource(R.plurals.month_show_more, more, more)
            TextButton(onClick = onShowDate, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .semantics { contentDescription = moreLabel }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
                Text(stringResource(R.string.month_overflow, more), maxLines = 1)
            }
        }
    }
}

@Composable
private fun MonthEventChip(event: EventOccurrence, date: LocalDate, zone: ZoneId, onOpenEvent: (EventRef) -> Unit) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val title = event.title.ifBlank { stringResource(R.string.month_no_title) }
    val flags = continuation(event, date, zone)
    val before = if (flags.before) stringResource(R.string.month_continues_before) else ""
    val after = if (flags.after) stringResource(R.string.month_continues_after) else ""
    val description = listOf(title, before, after).filter { it.isNotEmpty() }.joinToString(", ")
    val color = Color(event.color or 0xff000000.toInt())
    Surface(Modifier.fillMaxWidth().padding(bottom = 2.dp).heightIn(min = (20f * LocalDensity.current.fontScale).dp)
        .semantics { contentDescription = description }
        .clickable(onClickLabel = stringResource(R.string.month_open_event)) { onOpenEvent(event.ref) },
        color = if (event.declined) Color.Transparent else color,
        contentColor = if (event.declined) MaterialTheme.colorScheme.onSurface else eventTextColor(color),
        border = if (event.declined) BorderStroke(1.dp, color) else null,
        shape = MaterialTheme.shapes.extraSmall) {
        Row(Modifier.padding(horizontal = 3.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            if (flags.before) Text(if (rtl) "→" else "←", fontSize = 11.sp, lineHeight = 14.sp,
                modifier = Modifier.widthIn(min = (10f * LocalDensity.current.fontScale).dp), maxLines = 1)
            Text(title, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textDecoration = if (event.declined) TextDecoration.LineThrough else null, modifier = Modifier.weight(1f))
            if (flags.after) Text(if (rtl) "←" else "→", fontSize = 11.sp, lineHeight = 14.sp,
                modifier = Modifier.widthIn(min = (10f * LocalDensity.current.fontScale).dp), maxLines = 1)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MonthDaySheet(date: LocalDate, events: List<EventOccurrence>, zone: ZoneId,
    onDismiss: () -> Unit, onOpenEvent: (EventRef) -> Unit, onCreateDate: (() -> Unit)?) {
    val locale = LocalLocale.current.platformLocale
    val dateLabel = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale))
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(stringResource(R.string.month_events_on_date, dateLabel), style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.month_close))
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).testTag("month_day_events"), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (onCreateDate != null) item { TextButton(onClick = onCreateDate, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.month_create_on_date, dateLabel))
            } }
            if (events.isEmpty()) item { Text(stringResource(R.string.month_no_events), Modifier.padding(24.dp)) }
            items(events, key = { "${it.ref.calendarId.value}:${it.ref.eventId}:${it.ref.instanceStart}:${it.ref.instanceEnd}" }) { event ->
                val flags = continuation(event, date, zone)
                val detail = listOf(monthEventTime(event, zone, locale),
                    if (flags.before) stringResource(R.string.month_continues_before) else "",
                    if (flags.after) stringResource(R.string.month_continues_after) else "",
                    if (event.declined) stringResource(R.string.month_declined) else "")
                    .filter { it.isNotEmpty() }.joinToString(", ")
                ListItem(headlineContent = { Text(event.title.ifBlank { stringResource(R.string.month_no_title) },
                    textDecoration = if (event.declined) TextDecoration.LineThrough else null) },
                    supportingContent = { Text(detail) },
                    leadingContent = { Box(Modifier.size(12.dp).background(Color(event.color or 0xff000000.toInt()), CircleShape)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = stringResource(R.string.month_open_event)) { onOpenEvent(event.ref) })
            }
        }
    }
}

@Composable
private fun monthEventTime(event: EventOccurrence, zone: ZoneId, locale: Locale): String {
    val dates = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    return when (val time = event.time) {
        is EventTime.AllDay -> {
            val first = time.dates.start.format(dates)
            val last = time.dates.endExclusive.minusDays(1).format(dates)
            "${stringResource(R.string.month_all_day)}, ${if (time.dates.endExclusive == time.dates.start.plusDays(1)) first else "$first – $last"}"
        }
        is EventTime.Timed -> {
            val use24Hour = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
            val formatter = DateTimeFormatter.ofPattern(if (use24Hour) "MMM d, yyyy HH:mm XXX" else "MMM d, yyyy h:mm a XXX", locale)
            "\u202a${time.start.atZone(zone).format(formatter)} – ${time.end.atZone(zone).format(formatter)}\u202c"
        }
    }
}

@Composable
private fun MonthChevron(next: Boolean, description: String) {
    val right = next != (LocalLayoutDirection.current == LayoutDirection.Rtl)
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(20.dp).semantics { contentDescription = description }) {
        val tip = if (right) 0.65f else 0.35f
        val other = 1 - tip
        drawLine(color, Offset(size.width * other, size.height * 0.25f), Offset(size.width * tip, size.height / 2), 1.5.dp.toPx(), StrokeCap.Round)
        drawLine(color, Offset(size.width * tip, size.height / 2), Offset(size.width * other, size.height * 0.75f), 1.5.dp.toPx(), StrokeCap.Round)
    }
}
