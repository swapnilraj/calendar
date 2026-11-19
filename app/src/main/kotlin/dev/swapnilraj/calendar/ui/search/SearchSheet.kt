package dev.swapnilraj.calendar.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import dev.swapnilraj.calendar.R
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.agenda.agendaTimeLabel
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Stateless ready-state presentation; callbacks never query providers or mutate events. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchSheet(
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onRangeChange: (CalendarDateRange) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onChooseCalendars: () -> Unit,
    onOpenEvent: (EventRef) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onRequestPermission: (() -> Unit)? = null,
) {
    val shown = if (state.snapshot != null && state.matchingSnapshot == null &&
        state.status in setOf(SearchStatus.RESULTS, SearchStatus.NO_MATCHES, SearchStatus.NO_CALENDARS, SearchStatus.NO_SELECTION))
        state.copy(status = SearchStatus.WAITING, snapshot = null)
        else if (state.status == SearchStatus.RESULTS && state.matchingSnapshot?.hits?.isEmpty() == true)
            state.copy(status = SearchStatus.NO_MATCHES, snapshot = state.matchingSnapshot) else state
    val latestShown by rememberUpdatedState(shown)
    val density = LocalDensity.current
    val locale = LocalLocale.current.platformLocale
    val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    val range = shown.calendar.range
    val rangeLabel = stringResource(R.string.search_range, range.start.format(formatter), range.endExclusive.minusDays(1).format(formatter))
    val changeRangeLabel = stringResource(R.string.search_change_range)
    val readySnapshot = shown.matchingSnapshot.takeIf { shown.status in setOf(SearchStatus.RESULTS, SearchStatus.NO_MATCHES, SearchStatus.NO_CALENDARS, SearchStatus.NO_SELECTION) }
    val calendarsById = remember(readySnapshot?.calendars) { readySnapshot?.calendars.orEmpty().associateBy { it.id } }
    var pickingRange by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val use24Hour = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = modifier.testTag("search_sheet")) {
        CompositionLocalProvider(LocalDensity provides density) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.95f)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(stringResource(R.string.search_title), style = MaterialTheme.typography.titleLarge.copy(textDirection = TextDirection.Content),
                        modifier = Modifier.weight(1f).semantics { heading() })
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.search_close)) }
                }
                OutlinedTextField(shown.query, onQueryChange, label = { Text(stringResource(R.string.search_query)) },
                    singleLine = true, isError = shown.status == SearchStatus.INVALID_QUERY,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("search_query"),
                    supportingText = { Text(stringResource(if (shown.status == SearchStatus.INVALID_QUERY) R.string.search_query_error else R.string.search_query_help)) })
                LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("search_results"), contentPadding = PaddingValues(bottom = 24.dp)) {
                    item(key = "range") {
                        TextButton(onClick = { pickingRange = true }, modifier = Modifier.fillMaxWidth().testTag("search_date_range")
                            .semantics { contentDescription = "$changeRangeLabel, $rangeLabel" }) {
                            Column {
                                Text(rangeLabel, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content))
                                Text(stringResource(R.string.search_change_range), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        if (shown.calendar.invitationFilter == InvitationFilter.PENDING_ONLY) {
                            Text(stringResource(R.string.invitation_filter_pending), style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                        }
                        Text(stringResource(R.string.search_range_limit), style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content), modifier = Modifier.padding(horizontal = 16.dp))
                        Text(stringResource(R.string.search_zone, shown.calendar.displayZone.id), style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content), modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                        Row(Modifier.fillMaxWidth()) {
                            val earlier = expandedSearchRange(range, true)
                            val later = expandedSearchRange(range, false)
                            TextButton(enabled = earlier != null, onClick = { earlier?.let(onRangeChange) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.search_earlier)) }
                            TextButton(enabled = later != null, onClick = { later?.let(onRangeChange) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.search_later)) }
                        }
                        TextButton(onClick = onChooseCalendars, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.search_choose_calendars)) }
                    }
                    val snapshot = readySnapshot
                    snapshot?.coverage?.distinct()?.forEach { issue ->
                        item(key = "coverage:${issue.calendarId.value}:${issue.reason}") {
                            val calendar = calendarsById[issue.calendarId]
                            val name = calendar?.let { stringResource(R.string.search_calendar, it.displayName, it.account.name) }
                            val label = if (name == null) stringResource(
                                if (issue.reason == CoverageReason.INVITATION_CLASSIFICATION_UNKNOWN) R.string.invitation_filter_unknown_generic
                                else R.string.search_coverage_missing) else when (issue.reason) {
                                CoverageReason.PROVIDER_HIDDEN -> stringResource(R.string.search_coverage_hidden, name)
                                CoverageReason.SYNC_DISABLED -> stringResource(R.string.search_coverage_sync, name)
                                CoverageReason.MISSING_CALENDAR -> stringResource(R.string.search_coverage_missing)
                                CoverageReason.INSUFFICIENT_ACCESS -> stringResource(R.string.search_coverage_access, name)
                                CoverageReason.INVITATION_CLASSIFICATION_UNKNOWN -> stringResource(R.string.invitation_filter_unknown_coverage, name)
                            }
                            Text(label, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content), modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                        }
                    }
                    item(key = "status") {
                        val message = when (shown.status) {
                            SearchStatus.EMPTY_QUERY -> R.string.search_empty_query
                            SearchStatus.INVALID_QUERY -> R.string.search_query_error
                            SearchStatus.INVALID_RANGE -> R.string.search_range_error
                            SearchStatus.WAITING -> R.string.search_waiting
                            SearchStatus.LOADING -> R.string.search_loading
                            SearchStatus.PERMISSION_REQUIRED -> R.string.search_permission
                            SearchStatus.LIMIT_EXCEEDED -> R.string.read_limit_search
                            SearchStatus.UNAVAILABLE -> R.string.search_unavailable
                            SearchStatus.NO_CALENDARS -> R.string.search_no_calendars
                            SearchStatus.NO_SELECTION -> R.string.search_no_selection
                            SearchStatus.NO_MATCHES -> if (shown.calendar.invitationFilter == InvitationFilter.PENDING_ONLY &&
                                (snapshot?.truncated == true || !snapshot?.coverage.isNullOrEmpty())) R.string.invitation_filter_partial_empty
                                else if (snapshot?.truncated == true) R.string.search_partial_no_matches else R.string.search_no_matches
                            SearchStatus.RESULTS -> null
                        }
                        if (message != null) Text(stringResource(message), style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                            modifier = Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite })
                        if (shown.status == SearchStatus.WAITING || shown.status == SearchStatus.LOADING) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                        if (shown.status == SearchStatus.PERMISSION_REQUIRED && onRequestPermission != null) TextButton(onClick = onRequestPermission) { Text(stringResource(R.string.search_allow_calendar)) }
                        if (shown.status in setOf(SearchStatus.UNAVAILABLE, SearchStatus.PERMISSION_REQUIRED, SearchStatus.LIMIT_EXCEEDED)) TextButton(onClick = onRetry) { Text(stringResource(R.string.search_retry)) }
                        if (shown.status == SearchStatus.RESULTS) Text(pluralStringResource(R.plurals.search_result_count, shown.hits.size, shown.hits.size),
                            style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.Content), modifier = Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    if (shown.status == SearchStatus.RESULTS) {
                        items(shown.hits, key = { "hit:${it.occurrence.ref}" }) { hit ->
                            val event = hit.occurrence
                            val calendar = calendarsById[event.ref.calendarId]
                            val time = searchTimeLabel(event, shown.calendar.displayZone, use24Hour, locale)
                            val details = buildList {
                                add(if (event.time is EventTime.AllDay) "${stringResource(R.string.search_all_day)} · $time" else time)
                                calendar?.let { add(stringResource(R.string.search_calendar, it.displayName, it.account.name)) }
                                if (event.declined) add(stringResource(R.string.search_declined))
                            }.joinToString("\n")
                            val action = stringResource(R.string.search_open_event)
                            ListItem(headlineContent = { Text(event.title.ifBlank { stringResource(R.string.search_no_title) },
                                style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content), textDecoration = if (event.declined) TextDecoration.LineThrough else null) },
                                supportingContent = { Column {
                                    Text(details, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content))
                                    hit.location?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content)) }
                                    hit.snippet?.takeIf { it.isNotBlank() }?.let { Text(it.take(160), style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content)) }
                                } },
                                leadingContent = { Box(Modifier.size(12.dp).background(Color(event.color or 0xff000000.toInt()), CircleShape)) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("search_hit_${event.ref.calendarId.value}_${event.ref.eventId}")
                                    .clickable(onClickLabel = action) {
                                        val current = latestShown
                                        if (current.calendar == shown.calendar && current.query == shown.query &&
                                            current.limit == shown.limit && current.includeDeclined == shown.includeDeclined &&
                                            current.hits.any { it == hit }) onOpenEvent(event.ref)
                                    })
                        }
                    }
                    if (shown.status in setOf(SearchStatus.RESULTS, SearchStatus.NO_MATCHES) && snapshot?.truncated == true) item(key = "truncated") {
                            if (shown.hits.isNotEmpty()) Text(stringResource(R.string.search_truncated, shown.hits.size), style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content), modifier = Modifier.padding(16.dp))
                            if (shown.limit < 1000) TextButton(onClick = onLoadMore, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.search_more)) }
                            else Text(stringResource(R.string.search_narrow), style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content), modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
        }
    }
    if (pickingRange) {
        val picker = rememberDateRangePickerState(initialSelectedStartDateMillis = range.start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            initialSelectedEndDateMillis = range.endExclusive.minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), yearRange = 1..9999)
        val chosen = picker.selectedStartDateMillis?.let { start -> picker.selectedEndDateMillis?.let { end ->
            val first = Instant.ofEpochMilli(start).atOffset(ZoneOffset.UTC).toLocalDate()
            val last = Instant.ofEpochMilli(end).atOffset(ZoneOffset.UTC).toLocalDate()
            if (last >= first) CalendarDateRange(first, last.plusDays(1)) else null
        } }
        DatePickerDialog(onDismissRequest = { pickingRange = false }, confirmButton = {
            TextButton(enabled = chosen != null && validSearchRange(chosen), onClick = { chosen?.let { onRangeChange(it); pickingRange = false } }) { Text(stringResource(R.string.search_confirm_range)) }
        }, dismissButton = { TextButton(onClick = { pickingRange = false }) { Text(stringResource(R.string.search_cancel)) } }) {
            DateRangePicker(picker, modifier = Modifier.heightIn(max = 520.dp))
            if (chosen != null && !validSearchRange(chosen)) Text(stringResource(R.string.search_range_error), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        }
    }
}

/** Ungrouped search rows always identify their local date; agenda groups supply that context separately. */
internal fun searchTimeLabel(event: EventOccurrence, zone: ZoneId, use24Hour: Boolean, locale: Locale): String {
    val label = agendaTimeLabel(event, zone, use24Hour, locale)
    val timed = event.time as? EventTime.Timed ?: return label
    val date = timed.start.atZone(zone).toLocalDate()
    if (date != timed.end.atZone(zone).toLocalDate()) return label
    val dateLabel = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
    return "\u202a$dateLabel · ${label.removeSurrounding("\u202a", "\u202c")}\u202c"
}
