package dev.swapnilraj.calendar.ui

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.search.*
import java.time.ZoneId

/** The route owns retained query/range; this controller owns only cancellable live subscriptions. */
@Composable
internal fun SearchWorkflow(port: CalendarSearch, selection: CalendarSelection, zone: ZoneId, includeDeclined: Boolean,
    query: String, range: CalendarDateRange, onQueryChange: (String) -> Unit, onRangeChange: (CalendarDateRange) -> Unit,
    onChooseCalendars: () -> Unit, onOpen: (CalendarSearchHit) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val controller = remember(port) {
        SearchController(scope, port, CalendarRequest(range, zone, selection), includeDeclined).also { it.setQuery(query) }
    }
    DisposableEffect(controller) { onDispose { controller.close() } }
    LaunchedEffect(controller, selection, zone, includeDeclined) { controller.updateContext(selection, zone, includeDeclined) }
    LaunchedEffect(controller, query) { controller.setQuery(query) }
    LaunchedEffect(controller, range) { controller.setRange(range) }
    val state by controller.state.collectAsStateWithLifecycle()
    val shown = searchStateForContext(state, query, range, selection, zone, includeDeclined)
    SearchSheet(shown,
        onQueryChange = { controller.setQuery(it); onQueryChange(it) },
        onRangeChange = { controller.setRange(it); onRangeChange(it) },
        onLoadMore = controller::loadMore, onRetry = controller::retry,
        onChooseCalendars = onChooseCalendars,
        onOpenEvent = { ref ->
            val current = controller.state.value
            if (current.query == query && current.calendar.range == range && current.calendar.selection == selection &&
                current.calendar.displayZone == zone && current.includeDeclined == includeDeclined)
                current.hits.firstOrNull { it.occurrence.ref == ref }?.let(onOpen)
        }, onDismiss = onDismiss)
}

/** Hide old private rows in the composition that precedes the controller's context-update effect. */
internal fun searchStateForContext(state: SearchUiState, query: String, range: CalendarDateRange,
    selection: CalendarSelection, zone: ZoneId, includeDeclined: Boolean): SearchUiState {
    val calendar = CalendarRequest(range, zone, selection)
    if (state.query == query && state.calendar == calendar && state.includeDeclined == includeDeclined) return state
    val status = when {
        query.isBlank() -> SearchStatus.EMPTY_QUERY
        query.length > 256 -> SearchStatus.INVALID_QUERY
        !validSearchRange(range) -> SearchStatus.INVALID_RANGE
        (selection as? CalendarSelection.Explicit)?.ids?.isEmpty() == true -> SearchStatus.NO_SELECTION
        else -> SearchStatus.WAITING
    }
    return SearchUiState(query, calendar, includeDeclined = includeDeclined, status = status)
}
