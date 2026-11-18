package dev.swapnilraj.calendar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.swapnilraj.calendar.R
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.CalEvent
import dev.swapnilraj.calendar.data.CalendarState
import dev.swapnilraj.calendar.data.Settings
import dev.swapnilraj.calendar.data.CalendarPreferences
import dev.swapnilraj.calendar.data.CalendarPreferenceState
import dev.swapnilraj.calendar.data.calendar.CalendarReader
import dev.swapnilraj.calendar.ui.calendars.*
import dev.swapnilraj.calendar.ui.events.EventDetailsStateSheet
import dev.swapnilraj.calendar.ui.month.MonthView
import dev.swapnilraj.calendar.ui.month.monthDates
import dev.swapnilraj.calendar.ui.month.MonthDaySheet
import dev.swapnilraj.calendar.ui.month.monthEventsByDate
import java.time.*
import java.time.temporal.WeekFields
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** A shared date window drives the active view and selected details; filters belong to this app. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, ExperimentalMaterial3Api::class)
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
    settings: Settings? = null,
    onPreferenceFailure: () -> Unit = {},
    onCreateDate: ((LocalDate) -> Unit)? = null,
    mutations: CalendarMutations? = null,
    drafts: DraftStore? = null,
    onLocalSaved: () -> Unit = {},
    onLocalDeleted: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var dateText by rememberSaveable { mutableStateOf(now.toLocalDate().toString()) }
    var writeRequestText by rememberSaveable { mutableStateOf<String?>(null) }
    val preferencesFlow = remember(settings) { settings?.calendarPreferences ?: flowOf(CalendarPreferenceState.Loading) }
    val preferenceState by preferencesFlow.collectAsStateWithLifecycle(initialValue = CalendarPreferenceState.Loading)
    val preferences = if (settings == null) CalendarPreferences(selection, null, "DAY", hourHeight) else {
        when (val state = preferenceState) {
            is CalendarPreferenceState.Ready -> state.value
            else -> {
                CalendarPreferenceNotice(state, settings::refreshPreferences)
                return
            }
        }
    }
    val effectiveSelection = preferences.selection
    val view = if (preferences.view == "MONTH") CalendarViewOption.MONTH else CalendarViewOption.DAY
    var monthDayText by rememberSaveable(view, effectiveSelection, now.zone) { mutableStateOf<String?>(null) }
    val visibleDate = LocalDate.parse(dateText)
    val month = YearMonth.from(visibleDate)
    val firstWeekday = WeekFields.of(LocalLocale.current.platformLocale).firstDayOfWeek
    val monthDays = remember(month, firstWeekday) { monthDates(month, firstWeekday) }
    val request = remember(visibleDate, now.zone, effectiveSelection, view, firstWeekday) {
        val range = if (view == CalendarViewOption.MONTH) CalendarDateRange(monthDays.first(), monthDays.last().plusDays(1))
            else CalendarDateRange(visibleDate.minusDays(1), visibleDate.plusDays(2))
        CalendarRequest(range, now.zone, effectiveSelection)
    }
    var selected by remember(request, visibleDate, view) { mutableStateOf<EventRef?>(null) }
    val snapshots = remember(reader, request) { reader.observeEvents(request) }
    val snapshot by key(snapshots) { snapshots.collectAsStateWithLifecycle(initialValue = CalendarReadState.Loading) }
    val metadataFlow = remember(reader) { reader.observeCalendars() }
    val metadata by metadataFlow.collectAsStateWithLifecycle(initialValue = CalendarReadState.Loading)
    val calendars = (metadata as? CalendarReadState.Ready)?.value.orEmpty()
    val writable = mutations != null && drafts != null
    val createDateAction: ((LocalDate) -> Unit)? = onCreateDate ?: if (writable) ({ date ->
        writeRequestText = EventWriteRequest.create(date, now.zone, preferences.defaultCalendar).encode()
    }) else null
    val canCreate = createDateAction != null && calendars.any { it.capabilities.createEvents == AccessDecision.ALLOWED }
    LaunchedEffect(metadata, settings) {
        if (metadata is CalendarReadState.Ready && settings?.reconcileCalendars(calendars) == false) onPreferenceFailure()
    }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val calendarsLabel = stringResource(R.string.navigation_calendars)
    val openCalendars: @Composable RowScope.() -> Unit = {
        if (settings != null) IconButton(onClick = { scope.launch { drawer.open() } },
            modifier = Modifier.semantics { contentDescription = calendarsLabel }) { Text("☰") }
    }
    val controls: @Composable RowScope.() -> Unit = {
        if (settings != null) CalendarViewChooser(listOf(CalendarViewOption.DAY, CalendarViewOption.MONTH), view,
            onViewSelected = { option -> scope.launch {
                if (!settings.setCalendarView(option.name)) onPreferenceFailure()
            } })
        actions()
    }
    val forDay: (LocalDate, ZoneId) -> Flow<CalendarState> = remember(snapshots) {
        { date, zone -> snapshots.map { it.forDay(date, zone) } }
    }
    ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = settings != null, drawerContent = {
        when (metadata) {
            is CalendarReadState.Ready -> CalendarDrawerSheet(calendars, effectiveSelection, preferences.defaultCalendar,
                onSelectionChanged = { value -> scope.launch {
                    if (settings?.setCalendarSelection(value, calendars) == false) onPreferenceFailure()
                } },
                onDefaultCalendarChanged = { id -> scope.launch {
                    if (settings?.setDefaultCalendar(id, calendars) == false) onPreferenceFailure()
                } }, onDismiss = { scope.launch { drawer.close() } })
            else -> ModalDrawerSheet {
                CalendarReadNotice(metadata, reader::refresh)
                TextButton(onClick = { scope.launch { drawer.close() } }) { Text(stringResource(R.string.calendar_close_events)) }
            }
        }
    }) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            (snapshot as? CalendarReadState.Ready)?.value?.let { ready ->
                val chosenCalendars = ready.request.selection
                val notice = when {
                    ready.calendars.isEmpty() -> R.string.navigation_no_calendars
                    chosenCalendars is CalendarSelection.Explicit && chosenCalendars.ids.isEmpty() -> R.string.navigation_no_selection
                    ready.coverage.isNotEmpty() -> R.string.navigation_partial_coverage
                    else -> null
                }
                if (notice != null) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(notice), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    if (settings != null) TextButton(onClick = { scope.launch { drawer.open() } }) { Text(calendarsLabel) }
                }
            }
            Box(Modifier.weight(1f)) {
                if (view == CalendarViewOption.DAY) {
                    DayScreen(now, use24Hour, preferences.hourHeight, forDay, onSaveHourHeight,
                        onEventClick = { selected = it.ref }, initialDate = visibleDate,
                        actions = controls, leadingActions = openCalendars, compactTitle = settings != null,
                        summary = summary, background = background,
                        onVisibleDateChanged = { dateText = it.toString() }, onRetry = reader::refresh)
                } else Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        openCalendars(); controls()
                    }
                    when (val state = snapshot) {
                        is CalendarReadState.Ready -> MonthView(month, visibleDate, firstWeekday, state.value.occurrences, now.zone,
                            onSelectDate = { dateText = it.toString() }, onOpenEvent = { selected = it },
                            onCreateDate = { createDateAction?.invoke(it) },
                            onNavigateMonth = { next ->
                                // Date picker/Today select a date immediately before navigating its month.
                                val currentDay = LocalDate.parse(dateText).dayOfMonth
                                dateText = next.atDay(minOf(currentDay, next.lengthOfMonth())).toString()
                            },
                            today = now.toLocalDate(), modifier = Modifier.weight(1f), allowCreation = canCreate,
                            onShowDateEvents = { monthDayText = it.toString() })
                        else -> CalendarReadNotice(state, reader::refresh)
                    }
                }
                if (canCreate && createDateAction != null) {
                    ExtendedFloatingActionButton(onClick = { createDateAction(visibleDate) },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) { Text(stringResource(R.string.navigation_create_event)) }
                }
            }
        }
    }
    monthDayText?.let { text ->
        val date = LocalDate.parse(text)
        val ready = (snapshot as? CalendarReadState.Ready)?.value
        if (ready != null && date in ready.request.range) {
            MonthDaySheet(date, monthEventsByDate(listOf(date), ready.occurrences, now.zone).getValue(date), now.zone,
                onDismiss = { monthDayText = null }, onOpenEvent = { monthDayText = null; selected = it },
                onCreateDate = if (canCreate) createDateAction?.let { create -> { monthDayText = null; create(date) } } else null)
        } else ModalBottomSheet(onDismissRequest = { monthDayText = null }) {
            CalendarReadNotice(snapshot, reader::refresh)
            TextButton(onClick = { monthDayText = null }) { Text(stringResource(R.string.month_close)) }
        }
    }
    selected?.let { ref ->
        val details: Flow<CalendarReadState<EventDetails>> = remember(ref, snapshots, mutations) {
            snapshots.transformLatest { state ->
                emit(CalendarReadState.Loading)
                emit(when (state) {
                    CalendarReadState.Loading -> CalendarReadState.Loading
                    CalendarReadState.PermissionRequired -> CalendarReadState.PermissionRequired
                    CalendarReadState.Unavailable -> CalendarReadState.Unavailable
                    is CalendarReadState.Ready -> if (state.value.occurrences.any { it.ref == ref }) {
                        val result = reader.readDetails(ref)
                        if (result is CalendarReadState.Ready && mutations != null) {
                            val eligible = mutations.loadForEdit(ref)
                            if (eligible is EditReadResult.Ready && eligible.event.details.occurrence.ref == ref)
                                CalendarReadState.Ready(eligible.event.details.copy(canEdit = AccessDecision.ALLOWED))
                            else CalendarReadState.Ready(result.value.copy(canEdit = AccessDecision.DENIED))
                        } else result
                    } else CalendarReadState.Unavailable
                })
            }
        }
        val state by key(details) { details.collectAsStateWithLifecycle(initialValue = CalendarReadState.Loading) }
        EventDetailsStateSheet(state, onDismiss = { selected = null }, onRetry = reader::refresh,
            onOpenLink = onOpenLink, displayZone = now.zone, use24Hour = use24Hour,
            onEdit = if (writable) ({ value -> selected = null; writeRequestText = EventWriteRequest.edit(value, now.zone, false).encode() }) else null,
            onDelete = if (writable) ({ value -> selected = null; writeRequestText = EventWriteRequest.edit(value, now.zone, true).encode() }) else null)
    }
    if (mutations != null && drafts != null) writeRequestText?.let { raw ->
        val request = remember(raw) { EventWriteRequest.decode(raw) }
        EventWriteWorkflow(request, mutations, drafts, calendars, use24Hour,
            onDismiss = { writeRequestText = null },
            onSaved = { writeRequestText = null; reader.refresh(); onLocalSaved() },
            onDeleted = { writeRequestText = null; reader.refresh(); onLocalDeleted() })
    }
}

@Composable
private fun CalendarPreferenceNotice(state: CalendarPreferenceState, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp)) {
        if (state == CalendarPreferenceState.Loading) {
            Text(stringResource(R.string.navigation_preferences_loading))
            CircularProgressIndicator()
        } else {
            Text(stringResource(R.string.navigation_preferences_load_failed))
            TextButton(onClick = onRetry) { Text(stringResource(R.string.events_retry)) }
        }
    }
}

@Composable
private fun CalendarReadNotice(state: CalendarReadState<*>, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp)) {
        when (state) {
            CalendarReadState.Loading -> { Text(stringResource(R.string.navigation_loading)); CircularProgressIndicator() }
            CalendarReadState.PermissionRequired -> Text(stringResource(R.string.events_permission_required))
            CalendarReadState.Unavailable -> {
                Text(stringResource(R.string.navigation_unavailable))
                TextButton(onClick = onRetry) { Text(stringResource(R.string.events_retry)) }
            }
            is CalendarReadState.Ready -> Unit
        }
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
