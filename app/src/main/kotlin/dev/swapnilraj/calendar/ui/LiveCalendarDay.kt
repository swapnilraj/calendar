package dev.swapnilraj.calendar.ui

import androidx.activity.compose.BackHandler
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
import dev.swapnilraj.calendar.ui.agenda.AgendaView
import dev.swapnilraj.calendar.ui.agenda.agendaWindow
import dev.swapnilraj.calendar.ui.multiday.MultiDayView
import dev.swapnilraj.calendar.ui.multiday.multiDayWindow
import dev.swapnilraj.calendar.ui.multiday.MultiDayMinDate
import dev.swapnilraj.calendar.ui.multiday.MultiDayMaxDate
import dev.swapnilraj.calendar.ui.events.PhoneEventDetails
import dev.swapnilraj.calendar.ui.events.DraftBrowserSheet
import dev.swapnilraj.calendar.ui.events.DraftPreparationResult
import dev.swapnilraj.calendar.ui.events.prepareNewDraft
import dev.swapnilraj.calendar.ui.events.prepareEditDraft
import dev.swapnilraj.calendar.ui.events.prepareDuplicateDraft
import dev.swapnilraj.calendar.ui.settings.*
import androidx.compose.foundation.lazy.LazyColumn
import dev.swapnilraj.calendar.ui.month.MonthView
import dev.swapnilraj.calendar.ui.month.monthDates
import dev.swapnilraj.calendar.ui.month.MonthDaySheet
import dev.swapnilraj.calendar.ui.month.monthEventsByDate
import dev.swapnilraj.calendar.ui.search.defaultSearchRange
import dev.swapnilraj.calendar.ui.search.validSearchRange
import dev.swapnilraj.calendar.ui.events.meetings.*
import dev.swapnilraj.calendar.ui.events.meetings.owned.*
import java.util.UUID
import java.time.*
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

private data class OpenedEvent(
    val ref: EventRef, val dateText: String, val view: CalendarViewOption,
    val selection: CalendarSelection, val zone: ZoneId,
)

private data class NativeDetails(val details: EventDetails, val meeting: EditableMeeting? = null)

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
    retirement: OperationRetirement? = null,
    requestWritePermission: (suspend () -> Boolean)? = null,
    onLocalSaved: () -> Unit = {},
    onLocalDeleted: () -> Unit = {},
    onDraftFailure: () -> Unit = {},
    draftClock: Clock = Clock.systemUTC(),
    preferenceOverride: CalendarPreferenceState? = null,
    onOpenReminders: (() -> Unit)? = null,
    recurringCreationSupported: Boolean = false,
    search: CalendarSearch? = null,
    widgetNavigation: WidgetCalendarNavigation? = null,
    onWidgetNavigationConsumed: (String) -> Unit = {},
    meetings: CalendarMeetings? = null,
    requestMeetingWritePermission: (suspend () -> Boolean)? = null,
    onLocalCancelled: () -> Unit = onLocalSaved,
    onRetainedOwnedMeetingChanged: (Boolean) -> Unit = {},
    guestSuggestions: dev.swapnilraj.calendar.contacts.DeviceContactSuggestionSource? = null,
    contactsPermissionGranted: Boolean = false,
    requestContactsPermission: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val selectedDateState = rememberSaveable { mutableStateOf<String?>(null) }
    var writeRequestText by rememberSaveable { mutableStateOf<String?>(null) }
    var meetingWriteText by rememberSaveable { mutableStateOf<String?>(null) }
    var meetingCreationText by rememberSaveable { mutableStateOf<String?>(null) }
    var ownedMeetingText by rememberSaveable { mutableStateOf<String?>(null) }
    val retainedOwnedMeetingChanged by rememberUpdatedState(onRetainedOwnedMeetingChanged)
    SideEffect { retainedOwnedMeetingChanged(ownedMeetingText != null) }
    DisposableEffect(Unit) { onDispose { retainedOwnedMeetingChanged(false) } }
    var showDrafts by rememberSaveable { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchRangeText by rememberSaveable { mutableStateOf<String?>(null) }
    var preparingWrite by remember { mutableStateOf(false) }
    var phoneMonthExpanded by rememberSaveable { mutableStateOf(false) }
    var phoneMonthText by rememberSaveable { mutableStateOf<String?>(null) }
    var phoneDatePicker by rememberSaveable { mutableStateOf(false) }
    var phoneZoomOpen by rememberSaveable { mutableStateOf(false) }
    var dayNavigationRevision by rememberSaveable { mutableIntStateOf(0) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showDefaultCalendar by rememberSaveable { mutableStateOf(false) }
    var scrollMinute by rememberSaveable { mutableDoubleStateOf(-1.0) }
    val preferencesFlow = remember(settings, preferenceOverride != null) { if (preferenceOverride != null) flowOf(CalendarPreferenceState.Loading) else settings?.calendarPreferences ?: flowOf(CalendarPreferenceState.Loading) }
    val observedPreferences by preferencesFlow.collectAsStateWithLifecycle(initialValue = CalendarPreferenceState.Loading)
    val preferenceState = preferenceOverride ?: observedPreferences
    val availableViews = listOf(CalendarViewOption.DAY, CalendarViewOption.THREE_DAY, CalendarViewOption.WEEK, CalendarViewOption.MONTH, CalendarViewOption.AGENDA)
    // Keep unfinished settings mounted while a preference read is masked.
    if (showSettings && settings != null) PhoneSettingsWorkflow(
        preferences = preferenceState, availableViews = availableViews,
        save = settings::saveCalendarSetting, onRetryPreferences = settings::refreshPreferences,
        onClose = { showSettings = false }, onChooseDefaultCalendar = { showDefaultCalendar = true },
        onOpenReminders = onOpenReminders)
    val preferences = if (settings == null) CalendarPreferences(selection, null, "DAY", hourHeight) else {
        when (val state = preferenceState) {
            is CalendarPreferenceState.Ready -> state.value
            else -> {
                CalendarPreferenceNotice(state, settings::refreshPreferences)
                return
            }
        }
    }
    val options = preferences.options
    val displayNow = when (val zone = options.displayZone) {
        DisplayZoneOption.Device -> now
        is DisplayZoneOption.Fixed -> now.withZoneSameInstant(zone.zone)
    }
    val visibleDate = selectedDateState.value?.let(LocalDate::parse) ?: displayNow.toLocalDate()
    val dateText = visibleDate.toString()
    fun currentDate(): LocalDate = selectedDateState.value?.let(LocalDate::parse) ?: displayNow.toLocalDate()
    LaunchedEffect(Unit) { if (selectedDateState.value == null) selectedDateState.value = dateText }
    val effectiveSelection = preferences.selection
    val view = availableViews.firstOrNull { it.name == preferences.view } ?: CalendarViewOption.DAY
    var monthDayText by rememberSaveable(view, effectiveSelection, displayNow.zone) { mutableStateOf<String?>(null) }
    var agendaRangeText by rememberSaveable(view) {
        val initial = agendaWindow(visibleDate, 31)
        mutableStateOf("${initial.start}/${initial.endExclusive}")
    }
    val agendaRange = remember(agendaRangeText) { agendaRangeText.split('/').let { CalendarDateRange(LocalDate.parse(it[0]), LocalDate.parse(it[1])) } }
    val month = YearMonth.from(visibleDate)
    val firstWeekday = options.firstWeekday.resolve(LocalLocale.current.platformLocale)
    val multiDays = if (view == CalendarViewOption.WEEK) 7 else 3
    var multiDayRangeText by rememberSaveable(view, firstWeekday) {
        val initial = planningMultiDayRange(visibleDate, multiDays, firstWeekday)
        mutableStateOf("${initial.start}/${initial.endExclusive}")
    }
    val multiDayRange = remember(multiDayRangeText) { multiDayRangeText.split('/').let { CalendarDateRange(LocalDate.parse(it[0]), LocalDate.parse(it[1])) } }
    val monthDays = remember(month, firstWeekday) { monthDates(month, firstWeekday) }
    val range = when (view) {
        CalendarViewOption.MONTH -> CalendarDateRange(monthDays.first(), monthDays.last().plusDays(1))
        CalendarViewOption.AGENDA -> agendaRange
        CalendarViewOption.THREE_DAY, CalendarViewOption.WEEK -> multiDayRange
        else -> CalendarDateRange(visibleDate.minusDays(1), visibleDate.plusDays(2))
    }
    val request = remember(range, displayNow.zone, effectiveSelection) { CalendarRequest(range, displayNow.zone, effectiveSelection) }
    var openedEvent by remember { mutableStateOf<OpenedEvent?>(null) }
    fun openEvent(ref: EventRef) { openedEvent = OpenedEvent(ref, currentDate().toString(), view, effectiveSelection, displayNow.zone) }
    val selected = openedEvent?.takeIf { it.dateText == dateText && it.view == view && it.selection == effectiveSelection && it.zone == displayNow.zone }?.ref
    LaunchedEffect(dateText, view, effectiveSelection, displayNow.zone) {
        if (openedEvent != null && selected == null) openedEvent = null
    }
    val providerSnapshots = remember(reader, request) { reader.observeEvents(request) }
    val snapshots = remember(providerSnapshots, options.showDeclined) {
        if (options.showDeclined) providerSnapshots else providerSnapshots.map { state ->
            if (state is CalendarReadState.Ready) CalendarReadState.Ready(state.value.copy(occurrences = state.value.occurrences.filterNot { it.declined }))
            else state
        }
    }
    val snapshot by key(snapshots) { snapshots.collectAsStateWithLifecycle(initialValue = CalendarReadState.Loading) }
    val metadataFlow = remember(reader) { reader.observeCalendars() }
    val metadata by metadataFlow.collectAsStateWithLifecycle(initialValue = CalendarReadState.Loading)
    val calendars = (metadata as? CalendarReadState.Ready)?.value.orEmpty()
    val verifiedDefault = verifiedCreationCalendar(preferences, calendars)
    val writable = mutations != null && drafts != null
    val atomicDrafts = drafts as? AtomicDraftStore
    val meetingWritable = meetings != null && atomicDrafts != null && requestMeetingWritePermission != null
    fun beginRespond(source: EditableMeeting) {
        if (!meetingWritable || preparingWrite || selected != source.details.occurrence.ref) return
        val context = openedEvent
        preparingWrite = true
        scope.launch {
            try {
                val prepared = prepareOrReopenMeetingResponseDraft(source, requireNotNull(atomicDrafts), draftClock)
                if (prepared is MeetingResponsePreparation.Ready) {
                    if (openedEvent == context) {
                        openedEvent = null
                        meetingWriteText = MeetingResponseRouteCodec.encode(prepared.launch)
                    }
                } else onDraftFailure()
            } finally { preparingWrite = false }
        }
    }
    fun beginOwnedMeeting(source: EditableMeeting) {
        if (!meetingWritable || preparingWrite || selected != source.details.occurrence.ref) return
        val context = openedEvent
        preparingWrite = true
        scope.launch {
            try {
                val prepared = prepareOwnedMeetingDraft(source, "owned-meeting:${UUID.randomUUID()}",
                    displayNow.zone, requireNotNull(atomicDrafts), draftClock)
                if (prepared is OwnedMeetingPreparation.Ready) {
                    if (openedEvent == context) {
                        openedEvent = null
                        ownedMeetingText = OwnedMeetingRouteCodec.encode(prepared.launch)
                    }
                } else onDraftFailure()
            } finally { preparingWrite = false }
        }
    }
    fun beginCreate(date: LocalDate, allDay: Boolean) {
        if (writable && !preparingWrite) {
            preparingWrite = true
            scope.launch {
                try {
                    val requested = EventWriteRequest.create(date, displayNow.zone, preferences.defaultCalendar)
                    val calendar = verifiedDefault
                    val initial = eventCreationDraft(date, allDay, displayNow, options, calendar?.id, calendar?.identity)
                    if (initial == null) { onDraftFailure(); return@launch }
                    val prepared = prepareNewDraft(initial, requested.draftId, calendar != null, requireNotNull(drafts), draftClock)
                    if (prepared is DraftPreparationResult.Ready)
                        writeRequestText = EventWriteRequest.fromLaunch(prepared.launch, date, displayNow.zone).encode()
                    else onDraftFailure()
                } finally { preparingWrite = false }
            }
        }
    }
    val createDateAction: ((LocalDate) -> Unit)? = onCreateDate ?: if (writable) ({ date -> beginCreate(date, true) }) else null
    fun beginEdit(ref: EventRef, delete: Boolean) {
        if (!writable || preparingWrite) return
        openedEvent = null
        preparingWrite = true
        scope.launch {
            try {
                val loaded = requireNotNull(mutations).loadForEdit(ref)
                if (loaded is EditReadResult.Ready && loaded.event.details.occurrence.ref == ref) {
                    val requested = EventWriteRequest.edit(ref, displayNow.zone, delete)
                    val prepared = prepareEditDraft(loaded.event.details.editorSeed(), loaded.event, requested.draftId, requireNotNull(drafts), draftClock)
                    if (prepared is DraftPreparationResult.Ready)
                        writeRequestText = EventWriteRequest.fromLaunch(prepared.launch, visibleDate, displayNow.zone, delete).encode()
                    else onDraftFailure()
                } else onDraftFailure()
            } finally { preparingWrite = false }
        }
    }
    fun duplicate(details: EventDetails) {
        if (!writable || preparingWrite) return
        openedEvent = null
        preparingWrite = true
        scope.launch {
            try {
                val sourceIdentity = details.calendar?.identity
                val target = calendars.firstOrNull { it.id == details.occurrence.ref.calendarId &&
                    it.identity == sourceIdentity && it.capabilities.createEvents == AccessDecision.ALLOWED }
                    ?: verifiedDefault
                val requested = EventWriteRequest.create(visibleDate, displayNow.zone, target?.id)
                val prepared = prepareDuplicateDraft(details, target?.id, requested.draftId, requireNotNull(drafts), clock = draftClock,
                    targetIdentity = target?.identity)
                if (prepared is DraftPreparationResult.Ready)
                    writeRequestText = EventWriteRequest.fromLaunch(prepared.launch, visibleDate, displayNow.zone).encode()
                else onDraftFailure()
            } finally { preparingWrite = false }
        }
    }
    val canCreate = createDateAction != null && calendars.any { it.capabilities.createEvents == AccessDecision.ALLOWED }
    var consumedWidgetNavigation by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(widgetNavigation, metadata, view, effectiveSelection, displayNow.zone) {
        val navigation = widgetNavigation ?: return@LaunchedEffect
        if (consumedWidgetNavigation == navigation.id) {
            onWidgetNavigationConsumed(navigation.id)
            return@LaunchedEffect
        }
        if (metadata !is CalendarReadState.Ready) return@LaunchedEffect
        if (navigation.environment.displayZone != displayNow.zone || navigation.environment.appSelection != effectiveSelection) {
            consumedWidgetNavigation = navigation.id
            onWidgetNavigationConsumed(navigation.id)
            onDraftFailure()
            return@LaunchedEffect
        }
        val route = navigation.route
        if (route is dev.swapnilraj.calendar.android.widgets.AgendaWidgetRoute.Create &&
            (preparingWrite || writeRequestText != null || meetingWriteText != null || meetingCreationText != null || ownedMeetingText != null)) {
            consumedWidgetNavigation = navigation.id
            onWidgetNavigationConsumed(navigation.id)
            onDraftFailure()
            return@LaunchedEffect
        }
        if (route is dev.swapnilraj.calendar.android.widgets.AgendaWidgetRoute.More && view != CalendarViewOption.AGENDA) {
            if (settings?.setCalendarView(CalendarViewOption.AGENDA.name) != true) {
                consumedWidgetNavigation = navigation.id
                onWidgetNavigationConsumed(navigation.id)
                onPreferenceFailure()
            }
            return@LaunchedEffect // The successful preference emission remounts the chosen view.
        }
        val date = when (route) {
            is dev.swapnilraj.calendar.android.widgets.AgendaWidgetRoute.More -> route.date
            else -> displayNow.toLocalDate()
        }.coerceIn(MultiDayMinDate, MultiDayMaxDate)
        selectedDateState.value = date.toString()
        dayNavigationRevision++
        if (view == CalendarViewOption.AGENDA) agendaWindow(date, 31).let { agendaRangeText = "${it.start}/${it.endExclusive}" }
        if (view == CalendarViewOption.THREE_DAY || view == CalendarViewOption.WEEK)
            planningMultiDayRange(date, multiDays, firstWeekday).let { multiDayRangeText = "${it.start}/${it.endExclusive}" }
        consumedWidgetNavigation = navigation.id
        onWidgetNavigationConsumed(navigation.id)
        if (route is dev.swapnilraj.calendar.android.widgets.AgendaWidgetRoute.Create) {
            if (writable && !preparingWrite) beginCreate(date, false) else onDraftFailure()
        }
    }
    LaunchedEffect(metadata, settings) {
        if (metadata is CalendarReadState.Ready && settings?.reconcileCalendars(calendars) == false) onPreferenceFailure()
    }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val drawerActions: @Composable ColumnScope.() -> Unit = {
        if (metadata is CalendarReadState.Ready && preferences.defaultCalendar != null && verifiedDefault == null) Text(stringResource(R.string.navigation_default_calendar_changed),
            modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
        if (search != null) TextButton(onClick = { scope.launch { drawer.close(); showSearch = true } }, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Text(stringResource(R.string.search_title))
        }
        if (settings != null) TextButton(onClick = { scope.launch { drawer.close(); showSettings = true } }, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Text(stringResource(R.string.settings_title))
        }
        if (onOpenReminders != null) TextButton(onClick = { scope.launch { drawer.close(); onOpenReminders() } }, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Text(stringResource(R.string.reminders_setup_title))
        }
        if (settings != null) {
            if (view in listOf(CalendarViewOption.DAY, CalendarViewOption.THREE_DAY, CalendarViewOption.WEEK))
                TextButton(onClick = { phoneZoomOpen = true; scope.launch { drawer.close() } }, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                    Text(stringResource(R.string.phone_host_time_scale))
                }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, content = actions)
        }
        if (writable || meetingWritable) {
            HorizontalDivider()
            TextButton(onClick = { scope.launch { drawer.close(); showDrafts = true } }, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                Text(stringResource(R.string.events_drafts_title))
            }
        }
    }
    val calendarsLabel = stringResource(R.string.navigation_calendars)
    val openCalendars: @Composable RowScope.() -> Unit = {
        if (settings != null) IconButton(onClick = { scope.launch { drawer.open() } },
            modifier = Modifier.semantics { contentDescription = calendarsLabel }) { Text("☰") }
    }
    val controls: @Composable RowScope.() -> Unit = { actions() }
    fun navigatePhoneDate(date: LocalDate) {
        val selectedDate = date.coerceIn(MultiDayMinDate, MultiDayMaxDate)
        selectedDateState.value = selectedDate.toString()
        phoneMonthExpanded = false
        phoneMonthText = YearMonth.from(selectedDate).toString()
        dayNavigationRevision++
        if (view == CalendarViewOption.AGENDA) agendaWindow(selectedDate, 31).let { agendaRangeText = "${it.start}/${it.endExclusive}" }
        if (view == CalendarViewOption.THREE_DAY || view == CalendarViewOption.WEEK)
            planningMultiDayRange(selectedDate, multiDays, firstWeekday).let { multiDayRangeText = "${it.start}/${it.endExclusive}" }
    }
    BackHandler(enabled = phoneMonthExpanded || phoneDatePicker || phoneZoomOpen) {
        phoneMonthExpanded = false; phoneDatePicker = false; phoneZoomOpen = false
    }
    if (phoneDatePicker) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = visibleDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), yearRange = 1..9999)
        DatePickerDialog(onDismissRequest = { phoneDatePicker = false }, confirmButton = {
            TextButton(enabled = picker.selectedDateMillis != null, onClick = {
                picker.selectedDateMillis?.let { navigatePhoneDate(Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC).toLocalDate()) }
                phoneDatePicker = false
            }) { Text(stringResource(R.string.phone_host_choose)) }
        }, dismissButton = { TextButton(onClick = { phoneDatePicker = false }) { Text(stringResource(R.string.phone_host_cancel)) } }) {
            DatePicker(picker, showModeToggle = true)
        }
    }
    if (phoneZoomOpen) {
        var scale by remember(preferences.hourHeight) { mutableFloatStateOf(preferences.hourHeight) }
        AlertDialog(onDismissRequest = { phoneZoomOpen = false },
            title = { Text(stringResource(R.string.phone_host_time_scale)) },
            text = { Column {
                Text(stringResource(R.string.phone_host_scale_percent, (scale / 64f * 100).toInt()))
                val scaleLabel = stringResource(R.string.phone_host_time_scale_accessibility)
                Slider(value = scale, onValueChange = { scale = it }, valueRange = 32f..160f,
                    modifier = Modifier.semantics { contentDescription = scaleLabel })
            } },
            confirmButton = { TextButton(onClick = {
                onSaveHourHeight(scale); dayNavigationRevision++; phoneZoomOpen = false
            }) { Text(stringResource(R.string.phone_host_choose)) } },
            dismissButton = { TextButton(onClick = { phoneZoomOpen = false }) { Text(stringResource(R.string.phone_host_cancel)) } })
    }
    val forDay: (LocalDate, ZoneId) -> Flow<CalendarState> = remember(snapshots) {
        { date, zone -> snapshots.map { it.forDay(date, zone) } }
    }
    ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = settings != null, drawerContent = {
        when (metadata) {
            is CalendarReadState.Ready -> CalendarDrawerSheet(calendars, effectiveSelection, verifiedDefault?.id,
                onSelectionChanged = { value -> scope.launch {
                    if (settings?.setCalendarSelection(value, calendars) == false) onPreferenceFailure()
                } },
                onDefaultCalendarChanged = { id -> scope.launch {
                    if (settings?.setDefaultCalendar(id, calendars) == false) onPreferenceFailure()
                } }, onDismiss = { scope.launch { drawer.close() } }, footer = drawerActions,
                currentView = view, availableViews = availableViews,
                onViewSelected = if (settings != null) ({ option ->
                    phoneMonthExpanded = false
                    scope.launch {
                        if (!settings.setCalendarView(option.name)) onPreferenceFailure()
                        drawer.close()
                    }; Unit
                }) else null)
            else -> ModalDrawerSheet {
                CalendarReadNotice(metadata, reader::refresh)
                TextButton(onClick = { scope.launch { drawer.close() } }) { Text(stringResource(R.string.calendar_close_events)) }
                drawerActions()
            }
        }
    }) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            if (settings != null) {
                CalendarPhoneToolbar(visibleDate, displayNow.toLocalDate(), phoneMonthExpanded,
                    onOpenDrawer = { phoneMonthExpanded = false; scope.launch { drawer.open() } },
                    onToggleMonth = {
                        if (!phoneMonthExpanded) phoneMonthText = YearMonth.from(visibleDate).toString()
                        phoneMonthExpanded = !phoneMonthExpanded
                    }, onToday = { navigatePhoneDate(displayNow.toLocalDate()) },
                    onSearch = if (search != null) ({ phoneMonthExpanded = false; showSearch = true }) else null)
                if (phoneMonthExpanded) CalendarMonthNavigator(
                    displayedMonth = phoneMonthText?.let(YearMonth::parse) ?: YearMonth.from(visibleDate),
                    selectedDate = visibleDate, today = displayNow.toLocalDate(), firstDayOfWeek = firstWeekday,
                    onNavigateMonth = { phoneMonthText = it.toString() },
                    onSelectDate = ::navigatePhoneDate,
                    onChooseDate = { phoneDatePicker = true })
            }
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
                    key(dayNavigationRevision) { DayScreen(displayNow, use24Hour, preferences.hourHeight, forDay, onSaveHourHeight,
                        onEventClick = { it.ref?.let(::openEvent) }, initialDate = visibleDate,
                        initialScrollHour = scrollMinute.takeIf { it >= 0 }?.div(60),
                        actions = controls, leadingActions = openCalendars, compactTitle = settings != null,
                        summary = summary, background = background,
                        onVisibleDateChanged = { selectedDateState.value = it.toString() }, onRetry = reader::refresh,
                        onScrollMinuteChanged = { scrollMinute = it },
                        onChooseCalendars = if (settings != null) ({ scope.launch { drawer.open() }; Unit }) else null,
                        showNavigationChrome = settings == null) }
                } else Column(Modifier.fillMaxSize()) {
                    if (settings == null) Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        openCalendars(); controls()
                    }
                    when (val state = snapshot) {
                        is CalendarReadState.Ready -> if (view == CalendarViewOption.AGENDA) {
                            AgendaView(agendaRange, visibleDate, state.value.occurrences, displayNow.zone,
                                onSelectDate = { selectedDateState.value = it.toString() }, onOpenEvent = ::openEvent,
                                onRequestRange = { agendaRangeText = "${it.start}/${it.endExclusive}" },
                                today = displayNow.toLocalDate(), modifier = Modifier.weight(1f), showNavigationChrome = settings == null)
                        } else if (view == CalendarViewOption.THREE_DAY || view == CalendarViewOption.WEEK) {
                            key(view) { MultiDayView(multiDayRange, visibleDate, state.value.occurrences, displayNow, displayNow.zone, use24Hour,
                                preferences.hourHeight, onSaveHourHeight,
                                onSelectDate = { selectedDateState.value = it.toString() }, onOpenEvent = ::openEvent,
                                onShowDateEvents = { monthDayText = it.toString() },
                                onNavigateRange = { next ->
                                    val aligned = if (view == CalendarViewOption.WEEK)
                                        planningMultiDayRange(currentDate(), 7, firstWeekday) else next
                                    multiDayRangeText = "${aligned.start}/${aligned.endExclusive}"
                                }, initialScrollMinute = scrollMinute.takeIf { it >= 0 }, onScrollMinuteChanged = { scrollMinute = it },
                                summary = summary, background = background, modifier = Modifier.weight(1f), showWeekends = options.showWeekends, showNavigationChrome = settings == null) }
                        } else MonthView(month, visibleDate, firstWeekday, state.value.occurrences, displayNow.zone,
                            onSelectDate = { selectedDateState.value = it.toString() }, onOpenEvent = ::openEvent,
                            onCreateDate = { createDateAction?.invoke(it) },
                            onNavigateMonth = { next ->
                                // Date picker/Today select a date immediately before navigating its month.
                                val currentDay = currentDate().dayOfMonth
                                selectedDateState.value = next.atDay(minOf(currentDay, next.lengthOfMonth())).toString()
                            },
                            today = displayNow.toLocalDate(), modifier = Modifier.weight(1f), allowCreation = canCreate,
                            onShowDateEvents = { monthDayText = it.toString() }, showWeekends = options.showWeekends, showNavigationChrome = settings == null)
                        else -> CalendarReadNotice(state, reader::refresh)
                    }
                }
                if (canCreate && createDateAction != null) {
                    ExtendedFloatingActionButton(onClick = { if (onCreateDate != null) onCreateDate(visibleDate) else beginCreate(visibleDate, false) },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) { Text(stringResource(R.string.navigation_create_event)) }
                }
            }
        }
    }
    monthDayText?.let { text ->
        val date = LocalDate.parse(text)
        val ready = (snapshot as? CalendarReadState.Ready)?.value
        if (ready != null && date in ready.request.range) {
            MonthDaySheet(date, monthEventsByDate(listOf(date), ready.occurrences, displayNow.zone).getValue(date), displayNow.zone,
                onDismiss = { monthDayText = null }, onOpenEvent = { monthDayText = null; openEvent(it) },
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
                    CalendarReadState.LimitExceeded -> CalendarReadState.LimitExceeded
                    is CalendarReadState.Ready -> if (state.value.occurrences.any { it.ref == ref }) {
                        val result = reader.readDetails(ref)
                        if (result is CalendarReadState.Ready && mutations != null) {
                            val eligible = mutations.loadForEdit(ref)
                            val freshIdentity = result.value.calendar?.identity?.takeIf {
                                it.calendarId == ref.calendarId && it.account.name.isNotBlank() && it.account.type.isNotBlank()
                            }
                            when {
                                eligible == EditReadResult.PermissionRequired -> CalendarReadState.PermissionRequired
                                eligible is EditReadResult.Ready -> if (eligible.event.details.occurrence.ref == ref &&
                                    freshIdentity != null && eligible.event.details.calendar?.identity == freshIdentity)
                                    CalendarReadState.Ready(eligible.event.details.copy(canEdit = AccessDecision.ALLOWED))
                                else CalendarReadState.Unavailable
                                else -> CalendarReadState.Ready(result.value.copy(canEdit = AccessDecision.DENIED))
                            }
                        } else result
                    } else CalendarReadState.Unavailable
                })
            }
        }
        val nativeDetails = remember(details) { details.map { state ->
            when (state) {
                is CalendarReadState.Ready -> CalendarReadState.Ready(NativeDetails(state.value))
                CalendarReadState.Loading -> CalendarReadState.Loading
                CalendarReadState.PermissionRequired -> CalendarReadState.PermissionRequired
                CalendarReadState.Unavailable -> CalendarReadState.Unavailable
                CalendarReadState.LimitExceeded -> CalendarReadState.LimitExceeded
            }
        } }
        val responseDetails = remember(nativeDetails, meetings, meetingWritable) {
            nativeDetails.mapLatest { state ->
                if (!meetingWritable || state !is CalendarReadState.Ready ||
                    (state.value.details.isOrganizer != false &&
                        !(state.value.details.isOrganizer == true && ref.recurrence == null)) ||
                    (state.value.details.isOrganizer == false &&
                        state.value.details.calendar?.capabilities?.respondToInvitations == AccessDecision.DENIED)) state
                else when (val response = requireNotNull(meetings).loadForEdit(ref)) {
                    is MeetingReadResult.Ready -> {
                        val source = response.value
                        val identity = state.value.details.calendar?.identity
                        if (source.details.occurrence.ref == ref && identity != null && source.details.calendar?.identity == identity) {
                            val owned = OwnedBaseline.eligible(source)
                            CalendarReadState.Ready(state.value.copy(details = source.details.copy(
                                canEdit = if (owned) AccessDecision.ALLOWED else state.value.details.canEdit),
                                meeting = source.takeIf { source.details.isOrganizer == false || owned }))
                        }
                        else CalendarReadState.Unavailable
                    }
                    MeetingReadResult.PermissionRequired -> CalendarReadState.PermissionRequired
                    else -> CalendarReadState.Ready(state.value.copy(details = state.value.details.copy(canRespond = AccessDecision.DENIED)))
                }
            }
        }
        val state by key(responseDetails) { responseDetails.collectAsStateWithLifecycle(initialValue = CalendarReadState.Loading) }
        val meetingSource = (state as? CalendarReadState.Ready)?.value?.meeting
        val shown = when (val value = state) {
            is CalendarReadState.Ready -> CalendarReadState.Ready(value.value.details)
            CalendarReadState.Loading -> CalendarReadState.Loading
            CalendarReadState.PermissionRequired -> CalendarReadState.PermissionRequired
            CalendarReadState.Unavailable -> CalendarReadState.Unavailable
            CalendarReadState.LimitExceeded -> CalendarReadState.LimitExceeded
        }
        PhoneEventDetails(shown, onDismiss = { openedEvent = null }, onRetry = reader::refresh,
            onOpenLink = onOpenLink, displayZone = displayNow.zone, use24Hour = use24Hour,
            onEdit = if (meetingSource?.let { OwnedBaseline.eligible(it) } == true)
                ({ value -> if (value == meetingSource.details.occurrence.ref) beginOwnedMeeting(meetingSource) })
                else if (writable) ({ value -> beginEdit(value, false) }) else null,
            onDelete = if (meetingSource?.let { OwnedBaseline.eligible(it) } == true) null
                else if (writable) ({ value -> beginEdit(value, true) }) else null,
            onDuplicate = if (writable) ::duplicate else null,
            onRespond = if (meetingWritable && meetingSource != null && meetingSource.scopes.values.any { it.respond == AccessDecision.ALLOWED })
                ({ value -> if (value == meetingSource.details.occurrence.ref) beginRespond(meetingSource) }) else null)
    }
    if (mutations != null && drafts != null) writeRequestText?.let { raw ->
        val request = remember(raw) { EventWriteRequest.decode(raw) }
        EventWriteWorkflow(request, mutations, drafts, calendars, use24Hour, clock = draftClock,
            recurringCreationSupported = recurringCreationSupported,
            retirement = retirement, requestWritePermission = requestWritePermission,
            onReplacedLaunch = { replacement ->
                writeRequestText = EventWriteRequest.fromLaunch(replacement, visibleDate, displayNow.zone).encode()
            },
            onPromotedMeeting = if (meetingWritable) ({ launch ->
                meetingCreationText = MeetingCreationRouteCodec.encode(launch)
                writeRequestText = null
            }) else null,
            onDismiss = { writeRequestText = null },
            onSaved = { writeRequestText = null; reader.refresh(); onLocalSaved() },
            onDeleted = { writeRequestText = null; reader.refresh(); onLocalDeleted() })
    }
    if (meetingWritable) meetingCreationText?.let { raw ->
        val route = remember(raw) { runCatching { MeetingCreationRouteCodec.decode(raw) }.getOrNull() }
        if (route == null) AlertDialog(onDismissRequest = { meetingCreationText = null },
            text = { Text(stringResource(R.string.navigation_draft_unavailable)) },
            confirmButton = { TextButton(onClick = { meetingCreationText = null }) { Text(stringResource(R.string.events_close)) } })
        else MeetingCreationWorkflow(route, requireNotNull(meetings), requireNotNull(atomicDrafts), calendars,
            retirement, requireNotNull(requestMeetingWritePermission), use24Hour,
            onDismiss = { meetingCreationText = null },
            onSaved = { meetingCreationText = null; reader.refresh(); onLocalSaved() }, clock = draftClock,
            guestSuggestions = guestSuggestions, contactsPermissionGranted = contactsPermissionGranted,
            requestContactsPermission = requestContactsPermission)
    }
    if (meetingWritable) ownedMeetingText?.let { raw ->
        val route = remember(raw) { runCatching { OwnedMeetingRouteCodec.decode(raw) }.getOrNull() }
        if (route == null) AlertDialog(onDismissRequest = { ownedMeetingText = null },
            text = { Text(stringResource(R.string.navigation_draft_unavailable)) },
            confirmButton = { TextButton(onClick = { ownedMeetingText = null }) { Text(stringResource(R.string.events_close)) } })
        else OwnedMeetingWorkflow(route, requireNotNull(meetings), requireNotNull(atomicDrafts),
            retirement, requireNotNull(requestMeetingWritePermission), use24Hour,
            presentationVisible = LocalCalendarReadAccess.current && snapshot != CalendarReadState.PermissionRequired && metadata != CalendarReadState.PermissionRequired,
            onDismiss = { ownedMeetingText = null },
            onSaved = { ownedMeetingText = null; reader.refresh(); onLocalSaved() },
            onCancelled = { ownedMeetingText = null; reader.refresh(); onLocalCancelled() }, clock = draftClock,
            guestSuggestions = guestSuggestions, contactsPermissionGranted = contactsPermissionGranted,
            requestContactsPermission = requestContactsPermission)
    }
    if (showSearch && search != null) {
        val searchRange = remember(searchRangeText, displayNow.toLocalDate()) {
            runCatching { searchRangeText?.split('/')?.let { CalendarDateRange(LocalDate.parse(it[0]), LocalDate.parse(it[1])) } }
                .getOrNull()?.takeIf(::validSearchRange) ?: defaultSearchRange(displayNow.toLocalDate())
        }
        SearchWorkflow(search, effectiveSelection, displayNow.zone, options.showDeclined, searchQuery, searchRange,
            onQueryChange = { searchQuery = it },
            onRangeChange = { searchRangeText = "${it.start}/${it.endExclusive}" },
            onChooseCalendars = { showSearch = false; scope.launch { drawer.open() } },
            onOpen = { hit ->
                val date = when (val time = hit.occurrence.time) {
                    is EventTime.AllDay -> time.dates.start
                    is EventTime.Timed -> time.start.atZone(displayNow.zone).toLocalDate()
                }.coerceIn(MultiDayMinDate, MultiDayMaxDate)
                selectedDateState.value = date.toString()
                dayNavigationRevision++
                if (view == CalendarViewOption.AGENDA) agendaWindow(date, 31).let { agendaRangeText = "${it.start}/${it.endExclusive}" }
                if (view == CalendarViewOption.THREE_DAY || view == CalendarViewOption.WEEK)
                    planningMultiDayRange(date, multiDays, firstWeekday).let { multiDayRangeText = "${it.start}/${it.endExclusive}" }
                showSearch = false
                openEvent(hit.occurrence.ref)
            }, onDismiss = { showSearch = false })
    }
    if (meetingWritable) meetingWriteText?.let { raw ->
        val launch = remember(raw) { MeetingResponseRouteCodec.decode(raw) }
        if (launch != null) MeetingResponseWorkflow(launch, requireNotNull(meetings), requireNotNull(atomicDrafts),
            requireNotNull(requestMeetingWritePermission), onDismiss = { meetingWriteText = null },
            onLocalSaved = { meetingWriteText = null; reader.refresh(); onLocalSaved() },
            onReplacedLaunch = { meetingWriteText = MeetingResponseRouteCodec.encode(it) }, displayZone = displayNow.zone,
            use24Hour = use24Hour)
        else ModalBottomSheet(onDismissRequest = { meetingWriteText = null }) {
            Text(stringResource(R.string.meeting_response_draft_review), Modifier.padding(24.dp))
            TextButton(onClick = { meetingWriteText = null }) { Text(stringResource(R.string.events_close)) }
        }
    }
    if (showDefaultCalendar && settings != null) AlertDialog(onDismissRequest = { showDefaultCalendar = false },
        title = { Text(stringResource(R.string.calendars_creation_default)) },
        text = { LazyColumn {
            if (metadata is CalendarReadState.Ready) {
                item { TextButton(onClick = { scope.launch {
                    if (settings.setDefaultCalendar(null, calendars)) showDefaultCalendar = false else onPreferenceFailure()
                } }) { Text(stringResource(R.string.calendars_ask_each_time)) } }
                calendars.filter { it.capabilities.createEvents == AccessDecision.ALLOWED }.forEach { calendar -> item {
                    TextButton(onClick = { scope.launch {
                        if (settings.setDefaultCalendar(calendar.id, calendars)) showDefaultCalendar = false else onPreferenceFailure()
                    } }) { Text("${calendar.displayName} · ${calendar.account.name}") }
                } }
            } else item { CalendarReadNotice(metadata, reader::refresh) }
        } },
        confirmButton = { TextButton(onClick = { showDefaultCalendar = false }) { Text(stringResource(R.string.calendars_close)) } })
    if (showDrafts && drafts != null && (writable || meetingWritable)) DraftBrowserSheet(drafts, onDismiss = { showDrafts = false },
        onOpen = { launch ->
            showDrafts = false
            writeRequestText = EventWriteRequest.fromLaunch(launch, visibleDate, displayNow.zone).encode()
        }, displayZone = displayNow.zone, onOpenMeeting = if (meetingWritable) ({ launch ->
            showDrafts = false
            meetingWriteText = MeetingResponseRouteCodec.encode(launch)
        }) else null, onOpenMeetingCreation = if (meetingWritable) ({ launch ->
            showDrafts = false
            meetingCreationText = MeetingCreationRouteCodec.encode(launch)
        }) else null, onOpenOwnedMeeting = if (meetingWritable) ({ launch ->
            showDrafts = false
            ownedMeetingText = OwnedMeetingRouteCodec.encode(launch)
        }) else null)
}

/** Locale week alignment is a navigation policy, independent of event/recurrence expansion. */
internal fun planningMultiDayRange(date: LocalDate, days: Int, firstWeekday: DayOfWeek): CalendarDateRange {
    val supported = date.coerceIn(MultiDayMinDate, MultiDayMaxDate)
    val start = if (days == 7) maxOf(MultiDayMinDate, supported.with(TemporalAdjusters.previousOrSame(firstWeekday))) else supported
    return multiDayWindow(start, days)
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
            CalendarReadState.LimitExceeded -> Text(stringResource(R.string.navigation_read_limit))
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
    CalendarReadState.LimitExceeded -> CalendarState.LimitExceeded
    is CalendarReadState.Ready -> if (date !in value.request.range) CalendarState.Loading else CalendarState.Ready(
        value.occurrences.filter { it.time.intersects(CalendarDateRange.day(date), zone) }.map { occurrence ->
            val ref = occurrence.ref
            CalEvent(ref.eventId, ref.instanceStart, ref.instanceEnd, occurrence.title, occurrence.color,
                occurrence.time is EventTime.AllDay, occurrence.declined, ref)
        },
    )
}
