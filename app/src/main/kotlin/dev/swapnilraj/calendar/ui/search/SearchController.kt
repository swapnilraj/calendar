package dev.swapnilraj.calendar.ui.search

import dev.swapnilraj.calendar.core.*
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

private val MinSearchDate = LocalDate.of(1, 1, 1)
private val MaxSearchDate = LocalDate.of(9999, 12, 31)

fun defaultSearchRange(today: LocalDate): CalendarDateRange {
    require(today in MinSearchDate..MaxSearchDate)
    return CalendarDateRange(maxOf(MinSearchDate, today.minusMonths(12)), minOf(MaxSearchDate, today.plusMonths(24)).plusDays(1))
}

fun validSearchRange(range: CalendarDateRange): Boolean = range.start >= MinSearchDate &&
    range.endExclusive <= MaxSearchDate.plusDays(1) && range.endExclusive <= range.start.plusYears(10)

fun expandedSearchRange(range: CalendarDateRange, earlier: Boolean): CalendarDateRange? {
    if (!validSearchRange(range)) return null
    val expanded = if (earlier) CalendarDateRange(maxOf(MinSearchDate, range.start.minusMonths(12)), range.endExclusive)
        else CalendarDateRange(range.start, minOf(MaxSearchDate, range.endExclusive.minusDays(1).plusMonths(12)).plusDays(1))
    return expanded.takeIf { it != range && validSearchRange(it) }
}

enum class SearchStatus { EMPTY_QUERY, INVALID_QUERY, INVALID_RANGE, WAITING, LOADING, PERMISSION_REQUIRED, UNAVAILABLE, NO_CALENDARS, NO_SELECTION, NO_MATCHES, RESULTS }
data class SearchUiState(
    val query: String,
    val calendar: CalendarRequest,
    val limit: Int = 200,
    val includeDeclined: Boolean = true,
    val status: SearchStatus = SearchStatus.EMPTY_QUERY,
    val snapshot: CalendarSearchSnapshot? = null,
) {
    /** Rows are exposed only from the current successfully matched request. */
    val hits: List<CalendarSearchHit> get() = if (status == SearchStatus.RESULTS) snapshot?.hits.orEmpty() else emptyList()
}

/**
 * Scope-bound, UI-independent search orchestration. Every edit invalidates rows/cancels the old flow
 * immediately; only starting a valid new read waits 300ms. No provider or detail operations live here.
 * Call [close] on dismissal to cancel this controller's child job, leaving the supplied scope alive.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchController(scope: CoroutineScope, private val port: CalendarSearch,
    initialCalendar: CalendarRequest, includeDeclined: Boolean = true) : AutoCloseable {
    private data class Input(val query: String, val calendar: CalendarRequest, val limit: Int, val includeDeclined: Boolean, val revision: Long = 0)
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val controllerScope = CoroutineScope(scope.coroutineContext + job)
    private val input = MutableStateFlow(Input("", initialCalendar, 200, includeDeclined))
    private val mutableState = MutableStateFlow(SearchUiState("", initialCalendar, includeDeclined = includeDeclined))
    val state: StateFlow<SearchUiState> = mutableState.asStateFlow()

    init {
        controllerScope.launch(start = CoroutineStart.UNDISPATCHED) {
            input.collectLatest { value ->
                fun publish(status: SearchStatus, snapshot: CalendarSearchSnapshot? = null) {
                    if (input.value == value) mutableState.value = SearchUiState(value.query, value.calendar, value.limit, value.includeDeclined, status, snapshot)
                }
                when {
                    value.query.isBlank() -> publish(SearchStatus.EMPTY_QUERY)
                    value.query.length > 256 -> publish(SearchStatus.INVALID_QUERY)
                    !validSearchRange(value.calendar.range) -> publish(SearchStatus.INVALID_RANGE)
                    (value.calendar.selection as? CalendarSelection.Explicit)?.ids?.isEmpty() == true -> publish(SearchStatus.NO_SELECTION)
                    else -> {
                        publish(SearchStatus.WAITING)
                        delay(300)
                        val request = CalendarSearchRequest(value.query.trim(), value.calendar, value.limit, value.includeDeclined)
                        publish(SearchStatus.LOADING)
                        try {
                            port.observe(request).collect { result ->
                                when (result) {
                                    CalendarReadState.Loading -> publish(SearchStatus.LOADING)
                                    CalendarReadState.PermissionRequired -> publish(SearchStatus.PERMISSION_REQUIRED)
                                    CalendarReadState.Unavailable -> publish(SearchStatus.UNAVAILABLE)
                                    is CalendarReadState.Ready -> if (result.value.request == request) {
                                        val snapshot = normalized(result.value)
                                        val selected = when (val selection = value.calendar.selection) {
                                            CalendarSelection.ProviderVisible -> snapshot.calendars.any { it.providerVisible }
                                            is CalendarSelection.Explicit -> snapshot.calendars.any { it.id in selection.ids }
                                        }
                                        publish(when {
                                            snapshot.calendars.isEmpty() -> SearchStatus.NO_CALENDARS
                                            !selected -> SearchStatus.NO_SELECTION
                                            snapshot.hits.isEmpty() -> SearchStatus.NO_MATCHES
                                            else -> SearchStatus.RESULTS
                                        }, snapshot)
                                    }
                                }
                            }
                        } catch (failure: CancellationException) { throw failure }
                        catch (_: Exception) { publish(SearchStatus.UNAVAILABLE) }
                    }
                }
            }
        }
    }
    private fun change(transform: (Input) -> Input) {
        val previous = input.value
        val value = input.updateAndGet(transform)
        if (value != previous) {
            val status = when {
                value.query.isBlank() -> SearchStatus.EMPTY_QUERY
                value.query.length > 256 -> SearchStatus.INVALID_QUERY
                !validSearchRange(value.calendar.range) -> SearchStatus.INVALID_RANGE
                (value.calendar.selection as? CalendarSelection.Explicit)?.ids?.isEmpty() == true -> SearchStatus.NO_SELECTION
                else -> SearchStatus.WAITING
            }
            if (input.value == value) mutableState.value = SearchUiState(value.query, value.calendar, value.limit, value.includeDeclined, status)
        }
    }
    fun setQuery(raw: String) { change { it.copy(query = raw, limit = 200) } }
    fun setRange(range: CalendarDateRange) { change { it.copy(calendar = it.calendar.copy(range = range), limit = 200) } }
    fun updateContext(selection: CalendarSelection, zone: ZoneId, includeDeclined: Boolean = input.value.includeDeclined) {
        change { it.copy(calendar = it.calendar.copy(selection = selection, displayZone = zone), includeDeclined = includeDeclined, limit = 200) }
    }
    fun loadMore() {
        if (state.value.status in setOf(SearchStatus.RESULTS, SearchStatus.NO_MATCHES) && state.value.snapshot?.truncated == true) change { it.copy(limit = (it.limit + 200).coerceAtMost(1000)) }
    }
    fun retry() { change { it.copy(revision = it.revision + 1) } }
    override fun close() { job.cancel() }

    private fun normalized(snapshot: CalendarSearchSnapshot): CalendarSearchSnapshot {
        val request = snapshot.request
        val selection = request.calendar.selection
        val selectedIds = when (selection) {
            CalendarSelection.ProviderVisible -> snapshot.calendars.filter { it.providerVisible }.mapTo(mutableSetOf()) { it.id }
            is CalendarSelection.Explicit -> selection.ids
        }
        val eligible = snapshot.hits.filter { hit ->
            hit.occurrence.ref.calendarId in selectedIds &&
                (request.includeDeclined || !hit.occurrence.declined) && hit.occurrence.time.intersects(request.calendar.range, request.calendar.displayZone)
        }.distinctBy { it.occurrence.ref }.sortedWith(compareBy<CalendarSearchHit> { it.occurrence.ref.instanceStart }
            .thenBy { it.occurrence.ref.calendarId.value }.thenBy { it.occurrence.ref.eventId }
            .thenBy { it.occurrence.ref.instanceEnd }.thenBy { it.occurrence.ref.recurrence?.masterEventId }
            .thenBy { it.occurrence.ref.recurrence?.originalStart })
        return snapshot.copy(hits = eligible.take(request.limit), truncated = snapshot.truncated || eligible.size > request.limit)
    }
}
