package dev.swapnilraj.calendar.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalContext
import dev.swapnilraj.calendar.android.navigation.CalendarEditReviewActivity
import dev.swapnilraj.calendar.android.navigation.CalendarSeedReviewActivity
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
import dev.swapnilraj.calendar.ui.grid.*
import dev.swapnilraj.calendar.ui.multiday.MultiDayView
import dev.swapnilraj.calendar.ui.multiday.multiDayWindow
import dev.swapnilraj.calendar.ui.multiday.MultiDayMinDate
import dev.swapnilraj.calendar.ui.multiday.MultiDayMaxDate
import dev.swapnilraj.calendar.ui.events.meetings.*
import dev.swapnilraj.calendar.ui.events.meetings.owned.*
import java.util.UUID
import dev.swapnilraj.calendar.ui.events.PhoneEventDetails
import dev.swapnilraj.calendar.ui.events.SeriesEditorAction
import dev.swapnilraj.calendar.ui.events.canPrepareSeriesScope
import dev.swapnilraj.calendar.ui.events.DraftBrowserSheet
import dev.swapnilraj.calendar.ui.events.DraftPreparationResult
import dev.swapnilraj.calendar.ui.events.prepareNewDraft
import dev.swapnilraj.calendar.ui.events.prepareEditDraft
import dev.swapnilraj.calendar.ui.events.prepareDuplicateDraft
import dev.swapnilraj.calendar.ui.settings.*
import dev.swapnilraj.calendar.ui.search.defaultSearchRange
import dev.swapnilraj.calendar.ui.search.validSearchRange
import androidx.compose.foundation.lazy.LazyColumn
import dev.swapnilraj.calendar.ui.month.MonthView
import dev.swapnilraj.calendar.ui.month.monthDates
import dev.swapnilraj.calendar.ui.month.MonthDaySheet
import dev.swapnilraj.calendar.ui.month.monthEventsByDate
import java.time.*
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

private data class OpenedEvent(
    val ref: EventRef, val dateText: String, val view: CalendarViewOption,
    val selection: CalendarSelection, val zone: ZoneId,
)

private data class NativeDetails(val details: EventDetails, val series: EditableSeries? = null, val meeting: EditableMeeting? = null)

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
    onDraftFailure: () -> Unit = {},
    draftClock: Clock = Clock.systemUTC(),
    preferenceOverride: CalendarPreferenceState? = null,
    seriesMutations: CalendarSeriesMutations? = null,
    search: CalendarSearch? = null,
    onOpenReminders: (() -> Unit)? = null,
    meetings: CalendarMeetings? = null,
    requestMeetingWritePermission: (suspend () -> Boolean)? = null,
    widgetNavigation: WidgetCalendarNavigation? = null,
    onWidgetNavigationConsumed: (String) -> Unit = {},
    recurringCreationSupported: Boolean = false,
    operationRetirement: OperationRetirement? = null,
    dateNavigation: CalendarDateNavigation? = null,
    onDateNavigationConsumed: (String) -> Unit = {},
    onLocalCancelled: () -> Unit = onLocalSaved,
    onRetainedOwnedMeetingChanged: (Boolean) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val selectedDateState = rememberSaveable { mutableStateOf<String?>(null) }
    var writeRequestText by rememberSaveable { mutableStateOf<String?>(null) }
    var seriesScopeText by rememberSaveable { mutableStateOf<String?>(null) }
    var meetingWriteText by rememberSaveable { mutableStateOf<String?>(null) }
    var meetingCreationText by rememberSaveable { mutableStateOf<String?>(null) }
    var ownedMeetingText by rememberSaveable { mutableStateOf<String?>(null) }
    val retainedOwnedMeetingChanged by rememberUpdatedState(onRetainedOwnedMeetingChanged)
    SideEffect { retainedOwnedMeetingChanged(ownedMeetingText != null) }
    DisposableEffect(Unit) { onDispose { retainedOwnedMeetingChanged(false) } }
    var seriesWriteText by rememberSaveable { mutableStateOf<String?>(null) }
    var showDrafts by rememberSaveable { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchRangeText by rememberSaveable { mutableStateOf<String?>(null) }
    var preparingWrite by remember { mutableStateOf(false) }
    var phoneMonthExpanded by rememberSaveable { mutableStateOf(false) }
    var phoneMonthText by rememberSaveable { mutableStateOf<String?>(null) }
    var phoneDatePicker by rememberSaveable { mutableStateOf(false) }
    var phoneZoomOpen by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showDefaultCalendar by rememberSaveable { mutableStateOf(false) }
    var savingSettings by remember { mutableStateOf(false) }
    var settingsSaveFailed by remember { mutableStateOf(false) }
    var scrollMinute by rememberSaveable { mutableDoubleStateOf(-1.0) }
    var dayNavigationRevision by rememberSaveable { mutableIntStateOf(0) }
    val preferencesFlow = remember(settings, preferenceOverride != null) { if (preferenceOverride != null) flowOf(CalendarPreferenceState.Loading) else settings?.calendarPreferences ?: flowOf(CalendarPreferenceState.Loading) }
    val observedPreferences by preferencesFlow.collectAsStateWithLifecycle(initialValue = CalendarPreferenceState.Loading)
    val preferenceState = preferenceOverride ?: observedPreferences
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
    // On a cold external date launch the first pager must already use that date. Otherwise its
    // initial visible-date callback can race the navigation effect and restore today's anchor.
    val initialRequestedDate = dateNavigation?.instant?.let { instant ->
        runCatching { instant.atZone(displayNow.zone).toLocalDate() }.getOrNull()
    }?.takeIf { it in MultiDayMinDate..MultiDayMaxDate }
    val visibleDate = selectedDateState.value?.let(LocalDate::parse) ?: initialRequestedDate ?: displayNow.toLocalDate()
    val dateText = visibleDate.toString()
    fun currentDate(): LocalDate = selectedDateState.value?.let(LocalDate::parse) ?: displayNow.toLocalDate()
    LaunchedEffect(Unit) { if (selectedDateState.value == null) selectedDateState.value = dateText }
    val effectiveSelection = preferences.selection
    val availableViews = listOf(CalendarViewOption.DAY, CalendarViewOption.THREE_DAY, CalendarViewOption.WEEK, CalendarViewOption.MONTH, CalendarViewOption.AGENDA)
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
    val seriesWritable = seriesMutations != null && drafts != null
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
        if (preparingWrite) return
        if (ref.recurrence != null && seriesWritable) {
            openedEvent = null
            seriesScopeText = SeriesScopeRequest(ref, if (delete) SeriesEditorAction.DELETE else SeriesEditorAction.EDIT).encode()
            return
        }
        if (!writable) return
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
            (preparingWrite || writeRequestText != null || seriesScopeText != null || seriesWriteText != null || meetingWriteText != null || meetingCreationText != null || ownedMeetingText != null)) {
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
        if (writable || seriesWritable || meetingWritable) {
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
    // A context is an observation fence, never edit authority. Refreshing either data stream,
    // changing a namespace/filter/range, or opening another flow cancels the held gesture/review.
    val observedGridContext = GridEditorContext(listOf(reader, mutations, drafts, widgetNavigation?.id, dayNavigationRevision), request,
        (snapshot as? CalendarReadState.Ready)?.value, (metadata as? CalendarReadState.Ready)?.value,
        preferences, view.name, visibleDate,
        openedEvent != null || writeRequestText != null || seriesScopeText != null || seriesWriteText != null ||
            meetingWriteText != null || meetingCreationText != null || ownedMeetingText != null || showDrafts || showSearch || showSettings || showDefaultCalendar || drawer.isOpen || monthDayText != null ||
            phoneMonthExpanded || phoneDatePicker || phoneZoomOpen)
    val gridRevision = remember(observedGridContext) { Any() }
    val gridContext = observedGridContext.copy(owners = observedGridContext.owners + gridRevision)
    val latestGridContext by rememberUpdatedState(gridContext)
    var gridOperationEpoch by remember { mutableLongStateOf(0) }
    var gridReview by remember { mutableStateOf<Pair<GridEditorContext, GridEditorPreparation.Review>?>(null) }
    LaunchedEffect(gridContext) { if (gridReview?.first != gridContext) gridReview = null }
    fun cancelPhoneNavigation() { gridOperationEpoch++; gridReview = null }
    fun navigatePhoneDate(date: LocalDate) {
        cancelPhoneNavigation()
        val selected = date.coerceIn(MultiDayMinDate, MultiDayMaxDate)
        selectedDateState.value = selected.toString()
        phoneMonthExpanded = false
        phoneMonthText = YearMonth.from(selected).toString()
        dayNavigationRevision++
        if (view == CalendarViewOption.AGENDA) agendaWindow(selected, 31).let { agendaRangeText = "${it.start}/${it.endExclusive}" }
        if (view == CalendarViewOption.THREE_DAY || view == CalendarViewOption.WEEK)
            planningMultiDayRange(selected, multiDays, firstWeekday).let { multiDayRangeText = "${it.start}/${it.endExclusive}" }
    }
    var handledDateNavigation by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(dateNavigation, displayNow.zone) {
        val navigation = dateNavigation ?: return@LaunchedEffect
        if (handledDateNavigation != navigation.id) {
            val date = runCatching { navigation.instant.atZone(displayNow.zone).toLocalDate() }.getOrNull()
            if (date != null && date in MultiDayMinDate..MultiDayMaxDate) navigatePhoneDate(date)
            else onDraftFailure()
            handledDateNavigation = navigation.id
        }
        onDateNavigationConsumed(navigation.id)
    }
    BackHandler(enabled = phoneMonthExpanded || phoneDatePicker || phoneZoomOpen) {
        cancelPhoneNavigation(); phoneMonthExpanded = false; phoneDatePicker = false; phoneZoomOpen = false
    }
    if (phoneDatePicker) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = visibleDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), yearRange = 1..9999)
        DatePickerDialog(onDismissRequest = { cancelPhoneNavigation(); phoneDatePicker = false }, confirmButton = {
            TextButton(enabled = picker.selectedDateMillis != null, onClick = {
                picker.selectedDateMillis?.let { navigatePhoneDate(Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC).toLocalDate()) }
                phoneDatePicker = false
            }) { Text(stringResource(R.string.phone_host_choose)) }
        }, dismissButton = { TextButton(onClick = { cancelPhoneNavigation(); phoneDatePicker = false }) { Text(stringResource(R.string.phone_host_cancel)) } }) {
            DatePicker(picker, showModeToggle = true)
        }
    }
    if (phoneZoomOpen) {
        var scale by remember(preferences.hourHeight) { mutableFloatStateOf(preferences.hourHeight) }
        AlertDialog(onDismissRequest = { phoneZoomOpen = false },
            title = { Text(stringResource(R.string.phone_host_time_scale)) },
            text = { Column {
                Text(stringResource(R.string.phone_host_scale_percent, (scale / 64f * 100).toInt()))
                Slider(value = scale, onValueChange = { scale = it }, valueRange = 32f..160f,
                    modifier = Modifier.semantics { contentDescription = "Calendar time scale" })
            } },
            confirmButton = { TextButton(onClick = {
                cancelPhoneNavigation(); onSaveHourHeight(scale); dayNavigationRevision++; phoneZoomOpen = false
            }) { Text(stringResource(R.string.phone_host_choose)) } },
            dismissButton = { TextButton(onClick = { phoneZoomOpen = false }) { Text(stringResource(R.string.phone_host_cancel)) } })
    }

    fun launchGridEditor(context: GridEditorContext, intent: GridEditorIntent, offset: ZoneOffset? = null, alternative: Boolean = false) {
        if (!writable || preparingWrite || context != latestGridContext || !context.available) return
        val epoch = gridOperationEpoch
        preparingWrite = true
        scope.launch {
            try {
                when (val prepared = prepareGridEditor(context, intent, requireNotNull(mutations), requireNotNull(drafts),
                    displayNow, { if (epoch == gridOperationEpoch) latestGridContext else context.copy(blocked = true) }, draftClock, offset, alternative)) {
                    is GridEditorPreparation.Ready -> if (latestGridContext == context && epoch == gridOperationEpoch) {
                        gridReview = null
                        writeRequestText = EventWriteRequest.fromLaunch(prepared.launch, context.date, context.request.displayZone).encode()
                    }
                    is GridEditorPreparation.Review -> if (latestGridContext == context && epoch == gridOperationEpoch) gridReview = context to prepared
                    GridEditorPreparation.Stale -> Unit
                    GridEditorPreparation.Unavailable -> onDraftFailure()
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { if (latestGridContext == context && epoch == gridOperationEpoch) onDraftFailure() }
            finally { preparingWrite = false }
        }
    }
    fun gridEditorAlternative(ref: EventRef) {
        val occurrence = gridContext.occurrence(ref) ?: return
        val time = occurrence.time as? EventTime.Timed ?: return
        val segmentDate = maxOf(request.range.start, time.start.atZone(displayNow.zone).toLocalDate())
        val segment = DayGeometry(segmentDate, displayNow.zone).segments(ref).firstOrNull()?.key ?: return
        val target = time.start.atZone(displayNow.zone).let { GridTarget(it.toLocalDate(), gridMinute(it.toLocalTime()), displayNow.zone) }
        val source = TimedGridSource(ref, time)
        launchGridEditor(gridContext, GridEditorIntent.Edit(TimedGridEditProposal(gridContext, TimedGridEditKind.MOVE,
            source, segment, target, TimedGridPlanning.move(source, target, segment))), alternative = true)
    }
    val gridCanCreate = writable && onCreateDate == null && calendars.any { it.capabilities.createEvents == AccessDecision.ALLOWED }
    val gridInteractions = if (writable && gridContext.available && !preparingWrite && gridReview == null) TimedGridInteractions(
        gridContext, Duration.ofMinutes(options.defaultDurationMinutes.toLong()),
        onCreate = if (gridCanCreate) ({ proposal -> launchGridEditor(gridContext, GridEditorIntent.Create(proposal)) }) else null,
        onEdit = { proposal -> launchGridEditor(gridContext, GridEditorIntent.Edit(proposal)) },
        canEdit = { ref -> ref.recurrence == null && gridContext.calendar(ref)?.capabilities?.modifyEvents == AccessDecision.ALLOWED },
        onBeginEdit = { ref -> gridEditPreflight(gridContext, ref, requireNotNull(mutations)) { latestGridContext } },
        onCreateInEditor = if (gridCanCreate) ({ date ->
            val target = GridTarget(date, 540.0, displayNow.zone)
            launchGridEditor(gridContext, GridEditorIntent.Create(TimedGridCreateProposal(gridContext, target,
                TimedGridPlanning.create(target, Duration.ofMinutes(options.defaultDurationMinutes.toLong())))), alternative = true)
        }) else null,
        onOpenEditor = ::gridEditorAlternative,
    ) else null
    gridReview?.takeIf { it.first == gridContext }?.let { (context, review) ->
        val overlap = review.preview.reason == GridReviewReason.CLOCK_OVERLAP
        val target = review.preview.snappedTarget
        val local = target.date.atStartOfDay().plusSeconds((target.minute * 60).toLong())
        val locale = LocalLocale.current.platformLocale
        val clock = java.time.format.DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "h:mm a", locale)
        val dates = java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.SHORT).withLocale(locale)
        val targetLabel = "\u202a${target.date.format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(locale))} ${local.toLocalTime().format(clock)}\u202c"
        AlertDialog(onDismissRequest = { gridOperationEpoch++; gridReview = null },
            title = { Text(stringResource(if (overlap) R.string.grid_editor_overlap_title else R.string.grid_editor_gap_title)) },
            text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(if (overlap) R.string.grid_editor_overlap_message else R.string.grid_editor_gap_message,
                    targetLabel, "\u202a${target.zone.id}\u202c"), style = MaterialTheme.typography.bodyMedium.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Content))
                if (overlap) review.preview.candidateOffsets.forEachIndexed { index, offset ->
                    val explicitTarget = target.copy(offsetChoice = GridOffsetChoice.Explicit(offset))
                    val result = when (val intent = review.intent) {
                        is GridEditorIntent.Create -> TimedGridPlanning.create(explicitTarget, Duration.ofMinutes(options.defaultDurationMinutes.toLong()))
                        is GridEditorIntent.Edit -> planGridEdit(intent.proposal.source, intent.proposal.selectedSegment, intent.proposal.kind, explicitTarget)
                    } as? GridPreviewResult.Ready
                    TextButton(enabled = !preparingWrite && result != null, onClick = { launchGridEditor(context, review.intent, offset) }) {
                        Column {
                            Text(stringResource(if (index == 0) R.string.events_editor_first_offset else R.string.events_editor_second_offset, offset.id))
                            if (result != null) {
                                val start = result.time.start.atZone(target.zone)
                                val end = result.time.end.atZone(target.zone)
                                val range = "\u202a${start.toLocalDate().format(dates)} ${start.toLocalTime().format(clock)} UTC${if (start.offset == ZoneOffset.UTC) "+00:00" else start.offset.id} – ${end.toLocalDate().format(dates)} ${end.toLocalTime().format(clock)} UTC${if (end.offset == ZoneOffset.UTC) "+00:00" else end.offset.id}\u202c"
                                val elapsed = gridElapsedAmount(result.time.duration, locale)
                                Text(stringResource(if (elapsed.seconds) R.string.grid_editor_elapsed_seconds else R.string.grid_editor_elapsed_choice, range, elapsed.value))
                            } else Text(stringResource(R.string.grid_editor_invalid_choice))
                        }
                    }
                }
            } },
            confirmButton = { TextButton(enabled = !preparingWrite, onClick = { launchGridEditor(context, review.intent, alternative = true) }) {
                Text(stringResource(R.string.grid_editor_choose_another))
            } },
            dismissButton = { TextButton(onClick = { gridOperationEpoch++; gridReview = null }) { Text(stringResource(R.string.timed_grid_cancel)) } })
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
                    cancelPhoneNavigation(); phoneMonthExpanded = false
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
                    onOpenDrawer = { cancelPhoneNavigation(); phoneMonthExpanded = false; scope.launch { drawer.open() } },
                    onToggleMonth = {
                        cancelPhoneNavigation()
                        if (!phoneMonthExpanded) phoneMonthText = YearMonth.from(visibleDate).toString()
                        phoneMonthExpanded = !phoneMonthExpanded
                    }, onToday = { navigatePhoneDate(displayNow.toLocalDate()) },
                    onSearch = if (search != null) ({ cancelPhoneNavigation(); phoneMonthExpanded = false; showSearch = true }) else null)
                if (phoneMonthExpanded) CalendarMonthNavigator(
                    displayedMonth = phoneMonthText?.let(YearMonth::parse) ?: YearMonth.from(visibleDate),
                    selectedDate = visibleDate, today = displayNow.toLocalDate(), firstDayOfWeek = firstWeekday,
                    onNavigateMonth = { cancelPhoneNavigation(); phoneMonthText = it.toString() },
                    onSelectDate = ::navigatePhoneDate,
                    onChooseDate = { cancelPhoneNavigation(); phoneDatePicker = true })
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
                        onChooseCalendars = if (settings != null) ({ scope.launch { drawer.open() }; Unit }) else null, interactions = gridInteractions,
                        onCancelPendingEdit = { gridOperationEpoch++; gridReview = null }, showNavigationChrome = settings == null) }
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
                                summary = summary, background = background, modifier = Modifier.weight(1f), showWeekends = options.showWeekends,
                                interactions = gridInteractions, onCancelPendingEdit = { gridOperationEpoch++; gridReview = null }, showNavigationChrome = settings == null) }
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
        val details: Flow<CalendarReadState<NativeDetails>> = remember(ref, snapshots, mutations, seriesMutations) {
            snapshots.transformLatest { state ->
                emit(CalendarReadState.Loading)
                emit(when (state) {
                    CalendarReadState.Loading -> CalendarReadState.Loading
                    CalendarReadState.PermissionRequired -> CalendarReadState.PermissionRequired
                    CalendarReadState.Unavailable -> CalendarReadState.Unavailable
                    CalendarReadState.LimitExceeded -> CalendarReadState.LimitExceeded
                    is CalendarReadState.Ready -> if (state.value.occurrences.any { it.ref == ref }) {
                        when (val result = reader.readDetails(ref)) {
                            is CalendarReadState.Ready -> {
                                if (ref.recurrence != null && seriesMutations != null) {
                                    when (val eligible = readSeries(seriesMutations, ref)) {
                                        is SeriesReadResult.Ready -> {
                                            val source = eligible.series
                                            val anyAction = SeriesEditorAction.entries.any { action ->
                                                RecurrenceScope.entries.any { canPrepareSeriesScope(source, it, action) }
                                            }
                                            CalendarReadState.Ready(NativeDetails(source.selectedDetails.copy(
                                                canEdit = if (anyAction) AccessDecision.ALLOWED else AccessDecision.DENIED), source))
                                        }
                                        SeriesReadResult.PermissionRequired -> CalendarReadState.PermissionRequired
                                        else -> CalendarReadState.Ready(NativeDetails(result.value.copy(canEdit = AccessDecision.DENIED)))
                                    }
                                } else if (mutations != null && ref.recurrence == null) {
                                    val eligible = mutations.loadForEdit(ref)
                                    val value = if (eligible is EditReadResult.Ready && eligible.event.details.occurrence.ref == ref)
                                        eligible.event.details.copy(canEdit = AccessDecision.ALLOWED)
                                    else result.value.copy(canEdit = AccessDecision.DENIED)
                                    CalendarReadState.Ready(NativeDetails(value))
                                } else CalendarReadState.Ready(NativeDetails(result.value.copy(canEdit = AccessDecision.DENIED)))
                            }
                            CalendarReadState.Loading -> CalendarReadState.Loading
                            CalendarReadState.PermissionRequired -> CalendarReadState.PermissionRequired
                            CalendarReadState.Unavailable -> CalendarReadState.Unavailable
                            CalendarReadState.LimitExceeded -> CalendarReadState.LimitExceeded
                        }
                    } else CalendarReadState.Unavailable
                })
            }
        }
        val responseDetails = remember(details, meetings, meetingWritable) {
            details.mapLatest { state ->
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
        val source = (state as? CalendarReadState.Ready)?.value?.series
        fun allowed(action: SeriesEditorAction): Boolean = if (ref.recurrence == null) writable else
            seriesWritable && source != null && RecurrenceScope.entries.any { canPrepareSeriesScope(source, it, action) }
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
                else if (allowed(SeriesEditorAction.EDIT)) ({ value -> beginEdit(value, false) }) else null,
            onDelete = if (meetingSource?.let { OwnedBaseline.eligible(it) } == true) null
                else if (allowed(SeriesEditorAction.DELETE)) ({ value -> beginEdit(value, true) }) else null,
            onDuplicate = if (writable) ::duplicate else null,
            onRespond = if (meetingWritable && meetingSource != null && meetingSource.scopes.values.any { it.respond == AccessDecision.ALLOWED })
                ({ value -> if (value == meetingSource.details.occurrence.ref) beginRespond(meetingSource) }) else null)
    }
    if (mutations != null && drafts != null) writeRequestText?.let { raw ->
        val request = remember(raw) { runCatching { EventWriteRequest.decode(raw) }.getOrNull() }
        if (request == null) AlertDialog(onDismissRequest = { writeRequestText = null },
            text = { Text(stringResource(R.string.navigation_draft_unavailable)) },
            confirmButton = { TextButton(onClick = { writeRequestText = null }) { Text(stringResource(R.string.events_close)) } })
        else EventWriteWorkflow(request, mutations, drafts, calendars, use24Hour, clock = draftClock,
            recurringCreationSupported = recurringCreationSupported,
            retirement = operationRetirement, requestWritePermission = requestMeetingWritePermission,
            onReplacedLaunch = { launch -> writeRequestText = EventWriteRequest.fromLaunch(launch, visibleDate, displayNow.zone, request.delete).encode() },
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
            operationRetirement, requireNotNull(requestMeetingWritePermission), use24Hour,
            onDismiss = { meetingCreationText = null },
            onSaved = { meetingCreationText = null; reader.refresh(); onLocalSaved() }, clock = draftClock)
    }
    if (meetingWritable) ownedMeetingText?.let { raw ->
        val route = remember(raw) { runCatching { OwnedMeetingRouteCodec.decode(raw) }.getOrNull() }
        if (route == null) AlertDialog(onDismissRequest = { ownedMeetingText = null },
            text = { Text(stringResource(R.string.navigation_draft_unavailable)) },
            confirmButton = { TextButton(onClick = { ownedMeetingText = null }) { Text(stringResource(R.string.events_close)) } })
        else OwnedMeetingWorkflow(route, requireNotNull(meetings), requireNotNull(atomicDrafts),
            operationRetirement, requireNotNull(requestMeetingWritePermission), use24Hour,
            presentationVisible = LocalCalendarReadAccess.current && snapshot != CalendarReadState.PermissionRequired && metadata != CalendarReadState.PermissionRequired,
            onDismiss = { ownedMeetingText = null },
            onSaved = { ownedMeetingText = null; reader.refresh(); onLocalSaved() },
            onCancelled = { ownedMeetingText = null; reader.refresh(); onLocalCancelled() }, clock = draftClock)
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
    if (seriesMutations != null && drafts != null) {
        seriesScopeText?.let { raw ->
            val request = remember(raw) { SeriesScopeRequest.decode(raw) }
            SeriesScopeWorkflow(request, seriesMutations, drafts, onDismiss = { seriesScopeText = null },
                onPrepared = { launch -> seriesScopeText = null; seriesWriteText = SeriesWriteRequest.from(launch).encode() }, clock = draftClock)
        }
        seriesWriteText?.let { raw ->
            val request = remember(raw) { SeriesWriteRequest.decode(raw) }
            SeriesWriteWorkflow(request, seriesMutations, drafts, onDismiss = { seriesWriteText = null },
                onSaved = { seriesWriteText = null; reader.refresh(); onLocalSaved() },
                onDeleted = { seriesWriteText = null; reader.refresh(); onLocalDeleted() }, clock = draftClock,
                requestWritePermission = requestMeetingWritePermission,
                onReplacedLaunch = { seriesWriteText = SeriesWriteRequest.from(it).encode() })
        }
    }
    if (meetingWritable) meetingWriteText?.let { raw ->
        val launch = remember(raw) { MeetingResponseRouteCodec.decode(raw) }
        if (launch != null) MeetingResponseWorkflow(launch, requireNotNull(meetings), requireNotNull(atomicDrafts),
            requireNotNull(requestMeetingWritePermission), onDismiss = { meetingWriteText = null },
            onLocalSaved = { meetingWriteText = null; reader.refresh(); onLocalSaved() },
            onReplacedLaunch = { meetingWriteText = MeetingResponseRouteCodec.encode(it) }, displayZone = displayNow.zone)
        else ModalBottomSheet(onDismissRequest = { meetingWriteText = null }) {
            Text(stringResource(R.string.meeting_response_draft_review), Modifier.padding(24.dp))
            TextButton(onClick = { meetingWriteText = null }) { Text(stringResource(R.string.events_close)) }
        }
    }
    if (showSettings && settings != null) SettingsSheet(options, availableViews,
        onSave = { updated ->
            if (!savingSettings) scope.launch {
                savingSettings = true
                try {
                    if (settings.setDisplaySettings(updated)) { showSettings = false; settingsSaveFailed = false }
                    else settingsSaveFailed = true
                } finally { savingSettings = false }
            }
        }, onDismiss = { showSettings = false; settingsSaveFailed = false },
        onChooseDefaultCalendar = { showDefaultCalendar = true }, onOpenReminderSetup = null,
        saving = savingSettings,
        saveError = if (settingsSaveFailed) stringResource(R.string.navigation_preferences_unavailable) else null)
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
    if (showDrafts && drafts != null && (writable || seriesWritable || meetingWritable)) DraftBrowserSheet(drafts, onDismiss = { showDrafts = false },
        onOpen = { launch ->
            showDrafts = false
            writeRequestText = EventWriteRequest.fromLaunch(launch, visibleDate, displayNow.zone).encode()
        }, displayZone = displayNow.zone, onOpenSeries = if (seriesWritable) ({ launch ->
            showDrafts = false
            seriesWriteText = SeriesWriteRequest.from(launch).encode()
        }) else null, onOpenMeeting = if (meetingWritable) ({ launch ->
            showDrafts = false
            meetingWriteText = MeetingResponseRouteCodec.encode(launch)
        }) else null, onOpenMeetingCreation = if (meetingWritable) ({ launch ->
            showDrafts = false
            meetingCreationText = MeetingCreationRouteCodec.encode(launch)
        }) else null, onOpenOwnedMeeting = if (meetingWritable) ({ launch ->
            showDrafts = false
            ownedMeetingText = OwnedMeetingRouteCodec.encode(launch)
        }) else null, onOpenSeedReview = { record ->
            val intent = CalendarSeedReviewActivity.intentFromDraft(context, record)
            if (intent == null) onDraftFailure() else try {
                context.startActivity(intent); showDrafts = false
            } catch (_: android.content.ActivityNotFoundException) { onDraftFailure() }
            catch (_: SecurityException) { onDraftFailure() }
        }, onOpenEditReview = { record, companion ->
            val intent = if (record.id.startsWith("incoming-edit:"))
                CalendarEditReviewActivity.intentForNative(context, record, companion)
            else CalendarEditReviewActivity.intentFromReview(context, record)
            if (intent == null) onDraftFailure() else try {
                context.startActivity(intent); showDrafts = false
            } catch (_: android.content.ActivityNotFoundException) { onDraftFailure() }
            catch (_: SecurityException) { onDraftFailure() }
        })
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
