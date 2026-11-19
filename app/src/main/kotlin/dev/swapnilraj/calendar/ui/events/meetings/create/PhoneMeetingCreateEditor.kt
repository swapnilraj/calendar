package dev.swapnilraj.calendar.ui.events.meetings.create

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.swapnilraj.calendar.R
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.contacts.DeviceContactSuggestionSource
import dev.swapnilraj.calendar.ui.events.meetings.people.PhoneGuestPicker
import dev.swapnilraj.calendar.ui.events.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Presentation only: the dedicated controller owns raw persistence, authority, freeze and recovery. */
@Composable
fun PhoneMeetingCreateEditor(controller: MeetingCreationController, calendars: List<CalendarInfo>,
    onDismiss: () -> Unit, onLocalSaved: (EventRef) -> Unit, onDiscarded: () -> Unit = onDismiss,
    use24Hour: Boolean = true, guestSuggestions: DeviceContactSuggestionSource? = null,
    contactsPermissionGranted: Boolean = false, requestContactsPermission: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val actions = remember(controller,scope) { PhoneMeetingCreateActions(controller,scope) }
    val state = controller.state
    var peopleOpen by remember(controller) { mutableStateOf(false) }
    LaunchedEffect(controller.canEditRaw,state.problem) { if(!controller.canEditRaw || state.problem==MeetingCreationProblem.STALE_DRAFT) peopleOpen=false }
    LaunchedEffect(controller) { actions.submit { controller.load() } }
    LaunchedEffect(controller,state.acknowledged) { if (state.acknowledged) state.localSaved?.let(onLocalSaved) }
    LaunchedEffect(controller,state.discarded) { if (state.discarded) onDiscarded() }
    fun close() { actions.submit {
        if (controller.state.localSaved != null) controller.acknowledgeLocalSave()
        else { controller.flushDraft(); if (controller.prepareDismiss()) onDismiss() }
    } }
    Dialog(onDismissRequest = { if(peopleOpen) peopleOpen=false else close() }, properties = DialogProperties(usePlatformDefaultWidth=false,dismissOnClickOutside=false)) {
        PhoneMeetingCreateContent(controller,calendars,actions,::close,use24Hour,peopleOpen,{peopleOpen=it},guestSuggestions,contactsPermissionGranted,requestContactsPermission)
    }
}

internal class PhoneMeetingCreateActions(val controller: MeetingCreationController, private val scope: CoroutineScope) {
    private val serial = Mutex()
    private var blocking by mutableStateOf(0)
    private var projected by mutableStateOf<MeetingCreationFields?>(null)
    private var rawPending = 0
    val inputLocked get() = blocking > 0
    val fields get() = projected ?: controller.state.fields
    /** Lock at enqueue time: no keystroke can enter behind a queued Close or Save. */
    fun submit(action: suspend () -> Unit) {
        if(inputLocked) return
        blocking++
        scope.launch(start=CoroutineStart.UNDISPATCHED) {
            try { serial.withLock { action() } } finally { blocking-- }
        }
    }
    /** Projection keeps fast input visible while ordered raw CAS calls are suspended.
     * It is never authority or a normalized command; the controller still owns every persisted value.
     */
    fun event(change: (EventEditorFields) -> EventEditorFields) {
        if(inputLocked || !controller.canEditRaw) return
        val base=fields ?: return
        val next=change(base.event)
        projected=base.copy(event=next)
        raw { controller.changeFields(next) }
    }
    fun guests(change: (List<MeetingCreationGuestFields>) -> List<MeetingCreationGuestFields>) {
        if(inputLocked || !controller.canEditRaw) return
        val base=fields ?: return
        val next=change(base.guests)
        projected=base.copy(guests=next)
        raw { controller.changeGuests(next) }
    }
    /** Drain queued raw CAS calls, then acknowledge persistence without dismissing/stopping the editor. */
    fun finishGuestPicker(result: (Boolean) -> Unit) {
        if(inputLocked) {result(false);return}
        submit { controller.flushDraft(); result(controller.canEditRaw && !controller.hasPendingRawInput) }
    }
    private fun raw(action: suspend () -> Unit) {
        rawPending++
        scope.launch(start=CoroutineStart.UNDISPATCHED) {
            try { serial.withLock { action() } } finally { rawPending--; if(rawPending==0) projected=null }
        }
    }
}

@Composable
private fun PhoneMeetingCreateContent(c: MeetingCreationController, calendars: List<CalendarInfo>,
    actions: PhoneMeetingCreateActions, onClose: () -> Unit, use24Hour: Boolean,
    peopleOpen: Boolean, showPeople: (Boolean) -> Unit, guestSuggestions: DeviceContactSuggestionSource?,
    contactsPermissionGranted: Boolean, requestContactsPermission: () -> Unit) {
    val state=c.state; val fields=actions.fields; val raw=fields?.event
    var panel by remember(c) { mutableStateOf<PhoneEditorPanel?>(null) }
    val scroll=rememberScrollState()
    LaunchedEffect(state.problem) { if(state.problem!=null) scroll.animateScrollTo(0) }
    LaunchedEffect(state.localSaved,state.recoveryRequired) { if(state.localSaved!=null || state.recoveryRequired) scroll.animateScrollTo(0) }
    if(peopleOpen && c.canEditRaw && state.problem!=MeetingCreationProblem.STALE_DRAFT && fields!=null) {
        PhoneGuestPicker(fields.guests,!actions.inputLocked,guestSuggestions,contactsPermissionGranted,requestContactsPermission,
            { email,name -> actions.guests {it+MeetingCreationGuestFields(email=email,name=name.orEmpty())} },
            actions::finishGuestPicker,{showPeople(false)})
        return
    }
    val editable=c.canEditRaw && !actions.inputLocked
    val selected=calendars.firstOrNull { it.identity==raw?.targetIdentity }
    val canSave=!actions.inputLocked && c.canSave && state.policyAccepted && fields?.guests?.isNotEmpty()==true &&
        (raw?.recurrence?.frequency=="NONE" || c.canEditRepeat) && selected!=null
    Surface(Modifier.fillMaxSize()) {
        Scaffold(topBar={ Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min=64.dp).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
            val closeLabel=stringResource(R.string.phone_meeting_create_close)
            IconButton(onClick=onClose,enabled=!actions.inputLocked,modifier=Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp).semantics { contentDescription=closeLabel }) { PhoneEventIcon(PhoneEventGlyph.CLOSE) }
            Text(stringResource(when {state.localSaved!=null -> R.string.phone_meeting_create_complete; c.frozen -> R.string.phone_meeting_create_recovery; else -> R.string.phone_meeting_create_title}),style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f).padding(horizontal=8.dp).semantics { heading() })
            TextButton(onClick={ actions.submit { c.save() } },enabled=canSave,modifier=Modifier.heightIn(min=48.dp)) { Text(stringResource(R.string.events_editor_save)) }
        } }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(scroll).padding(bottom=32.dp)) {
                if(state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                PhoneMeetingCreateStatus(c,actions)
                if(raw!=null && fields!=null) {
                    val titleLabel=stringResource(R.string.phone_event_title)
                    TextField(raw.title,{ value -> actions.event { it.copy(title=value) } },enabled=editable,
                        placeholder={ Text(stringResource(R.string.phone_event_add_title)) },textStyle=MaterialTheme.typography.headlineMedium,
                        modifier=Modifier.fillMaxWidth().padding(start=48.dp,end=12.dp).heightIn(min=88.dp).semantics { contentDescription=titleLabel },
                        colors=TextFieldDefaults.colors(focusedContainerColor=Color.Transparent,unfocusedContainerColor=Color.Transparent,disabledContainerColor=Color.Transparent,focusedIndicatorColor=Color.Transparent,unfocusedIndicatorColor=Color.Transparent,disabledIndicatorColor=Color.Transparent))
                    PhoneEditorRow(PhoneEventGlyph.CALENDAR,selected?.displayName ?: stringResource(R.string.phone_event_choose_calendar),
                        selected?.account?.name ?: raw.targetIdentity?.account?.name,enabled=c.canChange && !actions.inputLocked,onClick={panel=PhoneEditorPanel.CALENDAR},swatch=selected?.color?.let { Color(it or 0xff000000.toInt()) })
                    HorizontalDivider(Modifier.padding(start=64.dp))
                    Row(Modifier.fillMaxWidth().toggleable(raw.allDay,enabled=editable,role=Role.Switch,onValueChange={ value -> actions.event { it.copy(allDay=value) } }).padding(start=64.dp,end=24.dp).heightIn(min=64.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text(stringResource(R.string.events_all_day),Modifier.weight(1f)); Switch(raw.allDay,onCheckedChange=null,enabled=editable)
                    }
                    PhoneEditorDateRow(stringResource(R.string.phone_event_starts),raw.startDate,raw.startTime,raw.allDay,use24Hour,editable,{panel=PhoneEditorPanel.START_DATE},{panel=PhoneEditorPanel.START_TIME})
                    PhoneEditorDateRow(stringResource(R.string.phone_event_ends),raw.endDate,raw.endTime,raw.allDay,use24Hour,editable,{panel=PhoneEditorPanel.END_DATE},{panel=PhoneEditorPanel.END_TIME})
                    if(!raw.allDay) {
                        PhoneEditorRow(PhoneEventGlyph.ZONE,PhoneEventEditorText.zone(raw.zone) ?: stringResource(R.string.phone_event_choose_zone),enabled=editable,onClick={panel=PhoneEditorPanel.ZONE})
                        PhoneEditorOffsetChoices(raw.startDate,raw.startTime,raw.zone,raw.startOffset,editable,stringResource(R.string.phone_event_start_time)) { value -> actions.event { it.copy(startOffset=value) } }
                        PhoneEditorOffsetChoices(raw.endDate,raw.endTime,raw.zone,raw.endOffset,editable,stringResource(R.string.phone_event_end_time)) { value -> actions.event { it.copy(endOffset=value) } }
                    }
                    if(c.hasFrozenRecurrence) PhoneEditorRow(PhoneEventGlyph.REPEAT,stringResource(R.string.phone_meeting_create_repeating))
                    else if(c.canEditRepeat) PhoneEditorRow(PhoneEventGlyph.REPEAT,phoneRepeatSummary(raw.recurrence),enabled=editable,onClick={panel=PhoneEditorPanel.REPEAT})
                    else if(raw.recurrence.frequency!="NONE") PhoneEditorRow(PhoneEventGlyph.REPEAT,stringResource(R.string.phone_meeting_create_recurrence))
                    HorizontalDivider(Modifier.padding(start=64.dp))
                    PhoneMeetingCreatePeople(fields.guests,state.allowedGuestTypes,editable,actions,
                        if(state.problem==MeetingCreationProblem.STALE_DRAFT) null else ({showPeople(true)}))
                    PhoneMeetingCreatePolicy(state.policyAccepted,editable) { value -> actions.submit { c.acceptFixedGuestPolicy(value) } }
                    HorizontalDivider(Modifier.padding(start=64.dp))
                    PhoneEditorRow(PhoneEventGlyph.PLACE,raw.location.ifBlank { stringResource(R.string.phone_event_add_location) },enabled=editable,onClick={panel=PhoneEditorPanel.LOCATION})
                    PhoneEditorRow(PhoneEventGlyph.ALERT,phoneAlertsSummary(raw.alerts),enabled=editable,onClick={panel=PhoneEditorPanel.ALERTS})
                    PhoneEditorRow(PhoneEventGlyph.NOTES,raw.description.ifBlank { stringResource(R.string.phone_event_add_notes) },enabled=editable,onClick={panel=PhoneEditorPanel.DESCRIPTION})
                    PhoneEditorRow(PhoneEventGlyph.AVAILABILITY,stringResource(R.string.events_availability),phoneMetadataLabel(raw.availability,false),enabled=editable,onClick={panel=PhoneEditorPanel.AVAILABILITY})
                    PhoneEditorRow(PhoneEventGlyph.VISIBILITY,stringResource(R.string.events_visibility),phoneMetadataLabel(raw.privacy,true),enabled=editable,onClick={panel=PhoneEditorPanel.PRIVACY})
                    Text(stringResource(R.string.phone_meeting_create_delivery),style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(horizontal=24.dp,vertical=16.dp))
                    if(c.canDiscard) TextButton(onClick={if(!actions.inputLocked)c.requestDiscard()},enabled=!actions.inputLocked,modifier=Modifier.padding(horizontal=16.dp)) { Text(stringResource(R.string.phone_meeting_create_discard)) }
                }
            }
        }
    }
    if(panel!=null && raw!=null) PhoneMeetingCreatePanel(panel!!,c,calendars,editable,actions,use24Hour) {panel=null}
    state.recurrenceReview?.let { review -> RecurrenceCreationReview(review.plan,review.spec,review.title,
        onConfirm={actions.submit {c.confirmRecurringCreate()}},onDismiss=c::dismissRecurrenceReview,use24Hour=use24Hour) }
    if(state.confirmDiscard) AlertDialog(onDismissRequest=c::cancelDiscard,title={Text(stringResource(R.string.phone_meeting_create_discard_title))},
        text={Text(stringResource(if(c.frozen) R.string.phone_meeting_create_discard_frozen else R.string.phone_meeting_create_discard_raw))},
        confirmButton={TextButton(onClick={actions.submit {c.confirmDiscard()}},enabled=!state.busy){Text(stringResource(R.string.phone_meeting_create_discard))}},
        dismissButton={TextButton(onClick=c::cancelDiscard,enabled=!state.busy){Text(stringResource(R.string.events_editor_cancel))}})
}
