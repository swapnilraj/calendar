package dev.swapnilraj.calendar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.swapnilraj.calendar.R
import dev.swapnilraj.calendar.data.CalEvent
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayHeader(date: LocalDate, today: LocalDate, allDayEvents: List<CalEvent>, zone: ZoneId,
    use24Hour: Boolean, onEventClick: (CalEvent) -> Unit,
    summary: @Composable RowScope.() -> Unit = {},
    dayEvents: List<CalEvent> = allDayEvents) {
    val locale = LocalLocale.current.platformLocale
    val dateLabel = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale))
    val gutter = calendarGutter(date, use24Hour)
    val scaledDateSize = (34f * LocalDensity.current.fontScale).dp.coerceAtLeast(34.dp)
    val chipHeight = minimumEventHeight().coerceAtLeast(24.dp)
    var showEvents by remember(date) { mutableStateOf(false) }
    val dateFocus = remember { FocusRequester() }
    val overflowFocus = remember { FocusRequester() }
    var returnFocus by remember(date) { mutableStateOf<FocusRequester?>(null) }
    val overflowVisible by rememberUpdatedState(allDayEvents.size > 3)
    LaunchedEffect(showEvents) {
        if (!showEvents && returnFocus != null) {
            // Let the modal window detach before restoring keyboard focus in the activity.
            withFrameNanos { }
            withFrameNanos { }
            (if (overflowVisible) returnFocus else dateFocus)?.requestFocus()
        }
    }
    val viewLabel = stringResource(R.string.calendar_view_day_events, dateLabel)
    Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(bottom = 8.dp)) {
        Column(Modifier.width(gutter).padding(top = 8.dp).heightIn(min = 48.dp)
            .focusRequester(dateFocus).semantics { contentDescription = viewLabel }
            .clickable(onClickLabel = viewLabel) { returnFocus = dateFocus; showEvents = true }, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(date.format(DateTimeFormatter.ofPattern("EEE", locale)), fontSize = 11.sp, lineHeight = 14.sp,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, maxLines = 1, softWrap = false,
                color = if (date == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Box(Modifier.size(scaledDateSize).then(if (date == today) Modifier.background(MaterialTheme.colorScheme.primary, CircleShape) else Modifier),
                contentAlignment = Alignment.Center) {
                Text(date.dayOfMonth.toString(), fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Normal,
                    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, maxLines = 1, softWrap = false,
                    color = if (date == today) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
            }
        }
        Column(Modifier.weight(1f).padding(end = 12.dp, top = 8.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 22.dp), horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically, content = summary)
            allDayEvents.take(3).forEach { event ->
                EventBlock(event, chipHeight.value, zone, use24Hour,
                    Modifier.fillMaxWidth().height(chipHeight + 2.dp).padding(bottom = 2.dp), onEventClick)
            }
            if (allDayEvents.size > 3) TextButton(onClick = { returnFocus = overflowFocus; showEvents = true }, modifier = Modifier.focusRequester(overflowFocus)) {
                Text(pluralStringResource(R.plurals.calendar_more_events, allDayEvents.size - 3, allDayEvents.size - 3))
            }
        }
    }
    if (showEvents) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showEvents = false }, sheetState = sheetState) {
            Text(stringResource(R.string.calendar_day_events, dateLabel), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            TextButton(onClick = { showEvents = false }, modifier = Modifier.align(Alignment.End).padding(horizontal = 16.dp)) {
                Text(stringResource(R.string.calendar_close_events))
            }
            LazyColumn(Modifier.testTag("calendar_day_events").fillMaxWidth().weight(1f, fill = false).padding(horizontal = 16.dp),
                contentPadding = PaddingValues(bottom = 24.dp)) {
                items(dayEvents.sortedWith(compareBy<CalEvent> { !it.allDay }.thenBy { it.start }.thenBy { it.key }), key = { it.key }) { event ->
                    val label = eventTimeLabel(event, zone, use24Hour, locale)
                    ListItem(headlineContent = { Text(event.title.ifBlank { stringResource(R.string.calendar_no_title) },
                        textDecoration = if (event.declined) TextDecoration.LineThrough else null) },
                        supportingContent = { Text((if (event.allDay) "${stringResource(R.string.calendar_all_day)}, $label" else label) +
                            if (event.declined) ", ${stringResource(R.string.calendar_declined)}" else "") },
                        leadingContent = { Box(Modifier.size(12.dp).background(androidx.compose.ui.graphics.Color(event.color or 0xff000000.toInt()), CircleShape)) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                            returnFocus = null // Event details will own focus in the next modal.
                            showEvents = false
                            onEventClick(event)
                        })
                }
            }
        }
    }
}
