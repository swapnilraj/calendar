package dev.swapnilraj.calendar.ui.agenda

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.swapnilraj.calendar.ui.eventTextColor
import dev.swapnilraj.calendar.R
import dev.swapnilraj.calendar.core.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Ready provider data only; the caller owns loading, errors, calendar filtering, and coverage notes.
 * Ranges contain 1..366 dates within years 1..9999; the exclusive end may be 10000-01-01.
 * Selected-date callbacks never imply a provider write. Pass [today] from the application clock.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgendaView(
    range: CalendarDateRange,
    selectedDate: LocalDate,
    events: List<EventOccurrence>,
    displayZone: ZoneId,
    onSelectDate: (LocalDate) -> Unit,
    onOpenEvent: (EventRef) -> Unit,
    onRequestRange: (CalendarDateRange) -> Unit,
    today: LocalDate = LocalDate.now(displayZone),
    modifier: Modifier = Modifier,
    showNavigationChrome: Boolean = true,
) {
    val groups = remember(range, selectedDate, events, displayZone) { agendaDays(range, selectedDate, events, displayZone) }
    val locale = LocalLocale.current.platformLocale
    val use24Hour = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    val dates = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)
    val shortDates = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    val selectedLabel = selectedDate.format(dates)
    val pickerLabel = stringResource(R.string.agenda_date_picker, selectedLabel)
    val todayActionLabel = stringResource(R.string.agenda_go_to_today)
    val days = ChronoUnit.DAYS.between(range.start, range.endExclusive)
    val earlier = adjacentAgendaRange(range, later = false)
    val later = adjacentAgendaRange(range, later = true)
    val noEvents = groups.all { it.events.isEmpty() }
    val outside = selectedDate !in range
    val listState = rememberLazyListState()
    var pickingDate by remember { mutableStateOf(false) }
    fun navigateTo(date: LocalDate) {
        onSelectDate(date)
        if (date !in range) onRequestRange(agendaWindow(date, days))
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val compact = maxWidth >= 360.dp && LocalDensity.current.fontScale <= 1.3f
        LaunchedEffect(range, selectedDate, compact) {
            var selectedIndex = if (noEvents) 1 else 0
            if (outside) selectedIndex++
            val group = groups.indexOfFirst { it.date == selectedDate }
            if (group >= 0 && !noEvents) {
                for (index in 0 until group) selectedIndex += (if (compact) 0 else 1) + maxOf(1, groups[index].events.size)
                listState.scrollToItem(selectedIndex)
            } else listState.scrollToItem(0)
        }
        Surface(Modifier.fillMaxSize()) {
            Column {
                if (showNavigationChrome) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.agenda_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(enabled = today in AgendaMinDate..AgendaMaxDate, onClick = { navigateTo(today) },
                        modifier = Modifier.semantics { contentDescription = todayActionLabel }) { Text(stringResource(R.string.agenda_today)) }
                }
                TextButton(onClick = { pickingDate = true }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = pickerLabel }) {
                    Text(selectedLabel, style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content))
                }
                }
                val first = range.start.format(shortDates)
                val last = range.endExclusive.minusDays(1).format(shortDates)
                Text(stringResource(R.string.agenda_loaded_range, if (first == last) first else "\u202a$first – $last\u202c"), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(horizontal = 16.dp, vertical = 4.dp).testTag("agenda_loaded_range"))
                Row(Modifier.fillMaxWidth()) {
                    TextButton(enabled = earlier != null, onClick = { earlier?.let { onSelectDate(it.start); onRequestRange(it) } },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("agenda_earlier")) { Text(stringResource(R.string.agenda_earlier)) }
                    TextButton(enabled = later != null, onClick = { later?.let { onSelectDate(it.start); onRequestRange(it) } },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("agenda_later")) { Text(stringResource(R.string.agenda_later)) }
                }
                if (earlier == null || later == null) Text(stringResource(if (earlier == null) R.string.agenda_start_boundary else R.string.agenda_end_boundary),
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
                HorizontalDivider()
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("agenda_list"), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
                    if (noEvents) item(key = "empty_range") {
                        Text(stringResource(R.string.agenda_no_events_range), style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                            modifier = Modifier.padding(24.dp))
                    }
                    if (outside) item(key = "outside_selection") {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(stringResource(R.string.agenda_selected_outside), style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content))
                            TextButton(onClick = { onRequestRange(agendaWindow(selectedDate, days)) }) { Text(stringResource(R.string.agenda_show_selected)) }
                        }
                    }
                    groups.forEach { group ->
                        if (!compact) item(key = "date:${group.date}") {
                            AgendaDateHeader(group.date, selectedDate, today, locale) { onSelectDate(group.date) }
                        }
                        if (group.events.isEmpty()) item(key = "empty:${group.date}") {
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
                                if (compact) AgendaDateHeader(group.date, selectedDate, today, locale, compact = true) { onSelectDate(group.date) }
                                Text(stringResource(R.string.agenda_no_events_day), style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                                    modifier = Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 16.dp))
                            }
                        }
                        items(group.events, key = { event ->
                            "event:${group.date}:${event.ref.calendarId.value}:${event.ref.eventId}:${event.ref.instanceStart}:${event.ref.instanceEnd}:${event.ref.recurrence}"
                        }) { event ->
                            Row(Modifier.fillMaxWidth().padding(top = if (event == group.events.first()) 8.dp else 0.dp), verticalAlignment = Alignment.Top) {
                                if (compact) {
                                    if (event == group.events.first()) AgendaDateHeader(group.date, selectedDate, today, locale, compact = true) { onSelectDate(group.date) }
                                    else Spacer(Modifier.width(64.dp))
                                }
                                AgendaEventRow(group.date, event, displayZone, use24Hour, locale, Modifier.weight(1f)) {
                                    onSelectDate(group.date)
                                    onOpenEvent(event.ref)
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
                picker.selectedDateMillis?.let { millis ->
                    pickingDate = false
                    navigateTo(Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC).toLocalDate())
                }
            }) { Text(stringResource(R.string.agenda_go_to_date)) }
        }, dismissButton = { TextButton(onClick = { pickingDate = false }) { Text(stringResource(R.string.agenda_cancel)) } }) {
            DatePicker(picker, showModeToggle = true)
        }
    }
}

@Composable
private fun AgendaDateHeader(date: LocalDate, selectedDate: LocalDate, today: LocalDate, locale: Locale, compact: Boolean = false, onSelect: () -> Unit) {
    val label = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale))
    val selectLabel = stringResource(R.string.agenda_select_date, label)
    val todayLabel = stringResource(R.string.agenda_today)
    TextButton(onClick = onSelect, modifier = (if (compact) Modifier.width(64.dp) else Modifier.fillMaxWidth()).heightIn(min = 48.dp).testTag("agenda_date_$date")
        .semantics { heading(); selected = date == selectedDate; contentDescription = selectLabel + if (date == today) ", $todayLabel" else "" },
        contentPadding = PaddingValues(horizontal = if (compact) 4.dp else 16.dp, vertical = 8.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = if (compact) Alignment.CenterHorizontally else Alignment.Start) {
            if (compact) {
                Text(date.format(DateTimeFormatter.ofPattern("EEE", locale)), style = MaterialTheme.typography.labelMedium)
                Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.titleLarge)
            } else Text(label, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.ContentOrLtr),
                textAlign = if (LocalLayoutDirection.current == LayoutDirection.Rtl) TextAlign.Right else TextAlign.Left)
            if (date == today) Text(stringResource(R.string.agenda_today), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun AgendaEventRow(date: LocalDate, event: EventOccurrence, zone: ZoneId, use24Hour: Boolean, locale: Locale, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val time = agendaTimeLabel(event, zone, use24Hour, locale)
    val details = buildList {
        add(if (event.time is EventTime.AllDay) "${stringResource(R.string.agenda_all_day)}, $time" else time)
        val bounds = CalendarDateRange.day(date).instantBounds(zone)
        val before: Boolean
        val after: Boolean
        when (val interval = event.time) {
            is EventTime.AllDay -> { before = interval.dates.start < date; after = interval.dates.endExclusive > date.plusDays(1) }
            is EventTime.Timed -> { before = interval.start < bounds.start; after = interval.end > bounds.endExclusive }
        }
        if (before) add(stringResource(R.string.agenda_continues_before))
        if (after) add(stringResource(R.string.agenda_continues_after))
        if (event.declined) add(stringResource(R.string.agenda_declined))
        val timed = event.time as? EventTime.Timed
        if (timed != null && timed.start.atZone(zone).offset != timed.end.atZone(zone).offset) {
            val minutes = timed.duration.toMinutes()
            if (minutes > 0) add(pluralStringResource(R.plurals.agenda_duration_minutes,
                minutes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), minutes))
        }
    }.joinToString(" · ")
    val color = Color(event.color or 0xff000000.toInt())
    val paragraph = if (LocalLayoutDirection.current == LayoutDirection.Rtl) TextAlign.Right else TextAlign.Left
    Surface(modifier.padding(start = 4.dp, end = 12.dp, bottom = 6.dp).fillMaxWidth().heightIn(min = 48.dp)
        .testTag("agenda_event_${date}_${event.ref.calendarId.value}_${event.ref.eventId}")
        .clickable(onClickLabel = stringResource(R.string.agenda_open_event), onClick = onOpen),
        shape = MaterialTheme.shapes.medium, color = if (event.declined) MaterialTheme.colorScheme.surface else color,
        contentColor = if (event.declined) MaterialTheme.colorScheme.onSurface else eventTextColor(color),
        border = if (event.declined) BorderStroke(1.dp, color) else null) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(event.title.ifBlank { stringResource(R.string.agenda_no_title) }, Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.ContentOrLtr), textAlign = paragraph,
                textDecoration = if (event.declined) TextDecoration.LineThrough else null)
            Text(details, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.ContentOrLtr), textAlign = paragraph)
        }
    }
}

internal fun agendaTimeLabel(event: EventOccurrence, zone: ZoneId, use24Hour: Boolean, locale: Locale): String = when (val time = event.time) {
    is EventTime.AllDay -> {
        val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
        val first = time.dates.start.format(formatter)
        val last = time.dates.endExclusive.minusDays(1).format(formatter)
        if (first == last) first else "\u202a$first – $last\u202c"
    }
    is EventTime.Timed -> {
        val start = time.start.atZone(zone)
        val end = time.end.atZone(zone)
        val includeDates = start.toLocalDate() != end.toLocalDate()
        val offsets = start.offset != end.offset || zone.rules.getValidOffsets(start.toLocalDateTime()).size > 1 ||
            zone.rules.getValidOffsets(end.toLocalDateTime()).size > 1
        val clock = DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "h:mm a", locale)
        val dates = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
        fun value(date: ZonedDateTime): String = buildString {
            if (includeDates) append(date.toLocalDate().format(dates)).append(' ')
            append(date.format(clock))
            if (offsets) append(" UTC").append(if (date.offset == ZoneOffset.UTC) "+00:00" else date.offset.id)
        }
        "\u202a${value(start)} – ${value(end)}\u202c"
    }
}
