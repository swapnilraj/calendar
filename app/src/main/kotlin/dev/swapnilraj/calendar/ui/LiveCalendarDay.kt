package dev.swapnilraj.calendar.ui

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.CalEvent
import dev.swapnilraj.calendar.data.CalendarState
import dev.swapnilraj.calendar.data.calendar.CalendarReader
import dev.swapnilraj.calendar.ui.events.EventDetailsStateSheet
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest

/** One three-day request serves all pager pages and the selected event detail refresh. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Composable
internal fun LiveCalendarDay(
    reader: CalendarReader,
    selection: CalendarSelection,
    now: ZonedDateTime,
    use24Hour: Boolean,
    hourHeight: Float,
    onSaveHourHeight: (Float) -> Unit,
    onOpenLink: (String) -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    summary: @Composable RowScope.(LocalDate) -> Unit = {},
    background: @Composable BoxScope.(LocalDate) -> Unit = {},
) {
    var dateText by rememberSaveable { mutableStateOf(now.toLocalDate().toString()) }
    val visibleDate = LocalDate.parse(dateText)
    val request = remember(visibleDate, now.zone, selection) {
        CalendarRequest(CalendarDateRange(visibleDate.minusDays(1), visibleDate.plusDays(2)), now.zone, selection)
    }
    var selected by remember(request) { mutableStateOf<EventRef?>(null) }
    val snapshots = remember(reader, request) { reader.observeEvents(request) }
    val forDay: (LocalDate, ZoneId) -> Flow<CalendarState> = remember(snapshots) {
        { date, zone -> snapshots.map { it.forDay(date, zone) } }
    }
    DayScreen(now, use24Hour, hourHeight, forDay, onSaveHourHeight,
        onEventClick = { selected = it.ref }, initialDate = visibleDate,
        actions = actions, summary = summary, background = background,
        onVisibleDateChanged = { dateText = it.toString() }, onRetry = reader::refresh)
    selected?.let { ref ->
        val details: Flow<CalendarReadState<EventDetails>> = remember(ref, snapshots) {
            snapshots.transformLatest { state ->
                emit(CalendarReadState.Loading)
                emit(when (state) {
                    CalendarReadState.Loading -> CalendarReadState.Loading
                    CalendarReadState.PermissionRequired -> CalendarReadState.PermissionRequired
                    CalendarReadState.Unavailable -> CalendarReadState.Unavailable
                    is CalendarReadState.Ready -> if (state.value.occurrences.any { it.ref == ref })
                        reader.readDetails(ref) else CalendarReadState.Unavailable
                })
            }
        }
        val state by key(details) { details.collectAsStateWithLifecycle(initialValue = CalendarReadState.Loading) }
        EventDetailsStateSheet(state, onDismiss = { selected = null }, onRetry = reader::refresh,
            onOpenLink = onOpenLink, displayZone = now.zone, use24Hour = use24Hour)
    }
}

internal fun CalendarReadState<CalendarSnapshot>.forDay(date: LocalDate, zone: ZoneId): CalendarState = when (this) {
    CalendarReadState.Loading -> CalendarState.Loading
    CalendarReadState.PermissionRequired -> CalendarState.PermissionRequired
    CalendarReadState.Unavailable -> CalendarState.Unavailable
    is CalendarReadState.Ready -> if (date !in value.request.range) CalendarState.Loading else CalendarState.Ready(
        value.occurrences.filter { it.time.intersects(CalendarDateRange.day(date), zone) }.map { occurrence ->
            val ref = occurrence.ref
            CalEvent(ref.eventId, ref.instanceStart, ref.instanceEnd, occurrence.title, occurrence.color,
                occurrence.time is EventTime.AllDay, occurrence.declined, ref)
        },
    )
}
