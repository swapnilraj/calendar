package dev.swapnilraj.calendar.ui.events.meetings.owned

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
import dev.swapnilraj.calendar.ui.events.meetings.create.MeetingCreationGuestFields
import dev.swapnilraj.calendar.ui.events.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The host masks synchronously on READ loss without destroying an in-flight controller. */
@Composable
fun PhoneOwnedMeetingEditor(controller:OwnedMeetingEditorController,onDismiss:()->Unit,onLocalSaved:(EventRef)->Unit,
    onLocalCancelled:(EventRef)->Unit,onDiscarded:()->Unit=onDismiss,use24Hour:Boolean=true,presentationVisible:Boolean=true, guestSuggestions:DeviceContactSuggestionSource?=null,
    contactsPermissionGranted:Boolean=false, requestContactsPermission:()->Unit={},
    conflictAdvisory:(@Composable (PhoneEditorConflictSnapshot)->Unit)?=null) {
    val scope=rememberCoroutineScope();val actions=remember(controller,scope) {OwnedPhoneActions(controller,scope)}
    var confirmLeave by remember(controller) {mutableStateOf(false)}
    LaunchedEffect(controller,presentationVisible) {
        controller.observeReadPermission(presentationVisible)
        if(presentationVisible) {controller.load();controller.refreshSource()}
    }
    var peopleOpen by remember(controller) {mutableStateOf(false)}
    LaunchedEffect(controller.canAddGuests,presentationVisible,controller.state.problem) {if(!controller.canAddGuests || !presentationVisible || controller.state.problem==OwnedMeetingProblem.DRAFT_CHANGED) peopleOpen=false}
    val state=controller.state
    LaunchedEffect(controller,state.acknowledged,presentationVisible) {if(presentationVisible && state.acknowledged) when(val outcome=state.localOutcome) {
        is MeetingMutationResult.LocalSaved -> onLocalSaved(outcome.selected)
        is MeetingMutationResult.LocalCancelled -> onLocalCancelled(outcome.selected)
        else -> Unit
    }}
    LaunchedEffect(controller,state.discarded,presentationVisible) {if(presentationVisible && state.discarded) onDiscarded()}
    fun close() {actions.submit {
        if(controller.state.localOutcome!=null) controller.acknowledgeLocalOutcome()
        else if(controller.prepareDismiss()) onDismiss()
        if(controller.canLeaveAfterLeaseLoss) confirmLeave=true
    }}
    Dialog(onDismissRequest={if(peopleOpen) peopleOpen=false else close()},properties=DialogProperties(usePlatformDefaultWidth=false,dismissOnClickOutside=false)) {
        if(presentationVisible && controller.presentationVisible) OwnedMeetingContent(controller,actions,::close,use24Hour,peopleOpen,{peopleOpen=it},guestSuggestions,contactsPermissionGranted,requestContactsPermission,conflictAdvisory)
        else Surface(Modifier.fillMaxSize()) {Column(Modifier.padding(24.dp)) {
            Text(stringResource(R.string.owned_meeting_read_permission))
            TextButton(onClick=::close,enabled=!actions.locked) {Text(stringResource(R.string.owned_meeting_leave))}
        }}
    }
    if(confirmLeave) AlertDialog(onDismissRequest={confirmLeave=false},title={Text(stringResource(R.string.owned_meeting_leave_title))},text={Text(stringResource(when(controller.leaveWarning) {
        OwnedMeetingLeaveWarning.UNSAVED_INPUT->R.string.owned_meeting_leave_unsaved
        OwnedMeetingLeaveWarning.LOCAL_OPERATION->R.string.owned_meeting_leave_saved
        OwnedMeetingLeaveWarning.UNCERTAIN_REQUEST->R.string.owned_meeting_leave_uncertain
    }))},confirmButton={TextButton(onClick={if(controller.confirmLeaveAfterLeaseLoss()) {confirmLeave=false;onDismiss()}}) {Text(stringResource(R.string.owned_meeting_leave))}},
        dismissButton={TextButton(onClick={confirmLeave=false}) {Text(stringResource(R.string.events_editor_cancel))}})
}
internal class OwnedPhoneActions(val c:OwnedMeetingEditorController,private val scope:CoroutineScope) {
    private val serial=Mutex();private var blocking by mutableStateOf(0);private var projection by mutableStateOf<OwnedMeetingFields?>(null);private var rawPending=0
    val locked get()=blocking>0
    val fields get()=if(c.presentationVisible) projection ?: c.state.fields else null
    val hasProjectedInput get()=projection!=null
    fun submit(action:suspend ()->Unit) {
        if(locked) return
        blocking++;scope.launch(start=CoroutineStart.UNDISPATCHED) {try {serial.withLock {action()}} finally {blocking--}}
    }
    fun raw(change:(OwnedMeetingFields)->OwnedMeetingFields) {
        if(locked || !c.canEditRaw) return
        val next=change(fields ?: return);projection=next
        enqueue {c.changeFields(next)}
    }
    fun finishGuestPicker(result:(Boolean)->Unit) {
        if(locked) {result(false);return}
        submit {c.flushDraft();result(c.canAddGuests && !c.hasPendingRawInput)}
    }
    fun event(change:(EventEditorFields)->EventEditorFields)=raw {it.copy(event=change(it.event))}
    fun zone(value:String) {
        if(locked || !c.canEdit(EditableField.ZONE)) return
        val old=fields ?: return
        projection=old.copy(event=old.event.copy(zone=value),zoneIntent="SET")
        enqueue {c.selectZone(value)}
    }
    private fun enqueue(action:suspend ()->Unit) {
        rawPending++;scope.launch(start=CoroutineStart.UNDISPATCHED) {try {serial.withLock {action()}} finally {rawPending--;if(rawPending==0) projection=null}}
    }
}

@Composable
private fun OwnedMeetingContent(c:OwnedMeetingEditorController,a:OwnedPhoneActions,close:()->Unit,use24Hour:Boolean,
    peopleOpen:Boolean,showPeople:(Boolean)->Unit,guestSuggestions:DeviceContactSuggestionSource?,
    contactsPermissionGranted:Boolean,requestContactsPermission:()->Unit,
    conflictAdvisory:(@Composable (PhoneEditorConflictSnapshot)->Unit)?) {
    val state=c.state;val raw=a.fields?.event;var panel by remember(c) {mutableStateOf<PhoneEditorPanel?>(null)}
    var more by remember(c) {mutableStateOf(false)};val scroll=rememberScrollState()
    LaunchedEffect(state.problem,state.localOutcome) {if(state.problem!=null || state.localOutcome!=null) scroll.animateScrollTo(0)}
    if(peopleOpen && c.canAddGuests && state.problem!=OwnedMeetingProblem.DRAFT_CHANGED && a.fields!=null) {
        PhoneGuestPicker(a.fields!!.added,!a.locked,guestSuggestions,contactsPermissionGranted,requestContactsPermission,
            {email,name -> a.raw {it.copy(added=it.added+MeetingCreationGuestFields(email=email,name=name.orEmpty()))}},
            a::finishGuestPicker,{showPeople(false)})
        return
    }
    fun editable(f:EditableField)=c.canEdit(f) && !a.locked
    Surface(Modifier.fillMaxSize()) {Scaffold(topBar={Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min=64.dp).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
        val closeLabel=stringResource(R.string.owned_meeting_close)
        IconButton(onClick=close,enabled=!a.locked,modifier=Modifier.semantics {contentDescription=closeLabel}) {PhoneEventIcon(PhoneEventGlyph.CLOSE)}
        Text(stringResource(R.string.owned_meeting_edit),Modifier.weight(1f).padding(horizontal=8.dp).semantics {heading()},style=MaterialTheme.typography.titleLarge)
        TextButton(onClick={a.submit {c.save()}},enabled=c.canSave && !a.locked) {Text(stringResource(R.string.events_editor_save))}
        if(c.canCancel) Box {
            val moreLabel=stringResource(R.string.owned_meeting_more)
            IconButton(onClick={more=true},enabled=!a.locked,modifier=Modifier.semantics {contentDescription=moreLabel}) {PhoneEventIcon(PhoneEventGlyph.MORE)}
            DropdownMenu(expanded=more,onDismissRequest={more=false}) {DropdownMenuItem(text={Text(stringResource(R.string.owned_meeting_cancel))},onClick={more=false;a.submit {c.requestCancellation()}})}
        }
    }}) {padding->Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(scroll).padding(bottom=32.dp)) {
        if(state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        OwnedMeetingStatus(c,a)
        if(raw!=null) {
            val title=stringResource(R.string.phone_event_title)
            TextField(raw.title,{v->a.event {it.copy(title=v)}},enabled=editable(EditableField.TITLE),placeholder={Text(stringResource(R.string.phone_event_add_title))},
                textStyle=MaterialTheme.typography.headlineMedium,modifier=Modifier.fillMaxWidth().padding(start=48.dp,end=12.dp).heightIn(min=88.dp).semantics {contentDescription=title},
                colors=TextFieldDefaults.colors(focusedContainerColor=Color.Transparent,unfocusedContainerColor=Color.Transparent,disabledContainerColor=Color.Transparent,focusedIndicatorColor=Color.Transparent,unfocusedIndicatorColor=Color.Transparent,disabledIndicatorColor=Color.Transparent))
            PhoneEditorRow(PhoneEventGlyph.CALENDAR,c.calendarLabel,c.accountLabel)
            HorizontalDivider(Modifier.padding(start=64.dp))
            Row(Modifier.fillMaxWidth().toggleable(raw.allDay,enabled=c.canChangeKind && !a.locked,role=Role.Switch,onValueChange={v->a.submit {c.setAllDay(v)}}).padding(start=64.dp,end=24.dp).heightIn(min=64.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(stringResource(R.string.events_all_day),Modifier.weight(1f));Switch(raw.allDay,null,enabled=c.canChangeKind && !a.locked)
            }
            PhoneEditorDateRow(stringResource(R.string.phone_event_starts),raw.startDate,raw.startTime,raw.allDay,use24Hour,editable(EditableField.TIME),{panel=PhoneEditorPanel.START_DATE},{panel=PhoneEditorPanel.START_TIME})
            PhoneEditorDateRow(stringResource(R.string.phone_event_ends),raw.endDate,raw.endTime,raw.allDay,use24Hour,editable(EditableField.TIME),{panel=PhoneEditorPanel.END_DATE},{panel=PhoneEditorPanel.END_TIME})
            if(!raw.allDay) {
                PhoneEditorRow(PhoneEventGlyph.ZONE,PhoneEventEditorText.zone(raw.zone) ?: raw.zone,enabled=editable(EditableField.ZONE),onClick={panel=PhoneEditorPanel.ZONE})
                PhoneEditorOffsetChoices(raw.startDate,raw.startTime,raw.zone,raw.startOffset,editable(EditableField.TIME),stringResource(R.string.phone_event_start_time)) {v->a.event {it.copy(startOffset=v)}}
                PhoneEditorOffsetChoices(raw.endDate,raw.endTime,raw.zone,raw.endOffset,editable(EditableField.TIME),stringResource(R.string.phone_event_end_time)) {v->a.event {it.copy(endOffset=v)}}
            }
            conflictAdvisory?.let { advisory ->
                Box(Modifier.padding(start=64.dp,end=24.dp)) {
                    advisory(PhoneEditorConflictSnapshot(c.conflictBinding(true), EditorTemporalInput.from(raw),
                        pendingRaw=a.hasProjectedInput || c.hasPendingRawInput,
                        zoneAuthored=a.fields?.zoneIntent=="SET", firstOccurrenceOnly=false))
                }
            }
            HorizontalDivider(Modifier.padding(start=64.dp))
            OwnedMeetingPeople(c,a,if(state.problem==OwnedMeetingProblem.DRAFT_CHANGED) null else ({showPeople(true)}))
            PhoneEditorRow(PhoneEventGlyph.PLACE,raw.location.ifBlank {stringResource(R.string.phone_event_add_location)},enabled=editable(EditableField.LOCATION),onClick={panel=PhoneEditorPanel.LOCATION})
            PhoneEditorRow(PhoneEventGlyph.ALERT,phoneAlertsSummary(raw.alerts),enabled=editable(EditableField.ALERTS),onClick={panel=PhoneEditorPanel.ALERTS})
            PhoneEditorRow(PhoneEventGlyph.NOTES,raw.description.ifBlank {stringResource(R.string.phone_event_add_notes)},enabled=editable(EditableField.DESCRIPTION),onClick={panel=PhoneEditorPanel.DESCRIPTION})
            PhoneEditorRow(PhoneEventGlyph.AVAILABILITY,stringResource(R.string.events_availability),phoneMetadataLabel(raw.availability,false))
            PhoneEditorRow(PhoneEventGlyph.VISIBILITY,stringResource(R.string.events_visibility),phoneMetadataLabel(raw.privacy,true),enabled=editable(EditableField.PRIVACY),onClick={panel=PhoneEditorPanel.PRIVACY})
            Text(stringResource(R.string.owned_meeting_delivery),Modifier.padding(24.dp),style=MaterialTheme.typography.bodySmall)
            if(c.canDiscard) TextButton(onClick={a.submit {c.requestDiscard()}},enabled=!a.locked,modifier=Modifier.padding(horizontal=16.dp)) {Text(stringResource(R.string.owned_meeting_discard))}
        }
    }}}
    if(panel!=null && raw!=null) OwnedMeetingPanel(panel!!,c,a,use24Hour) {panel=null}
    if(state.confirmCancel) AlertDialog(onDismissRequest=c::dismissCancellation,title={Text(stringResource(R.string.owned_meeting_cancel))},
        text={Text(stringResource(R.string.owned_meeting_cancel_warning))},confirmButton={TextButton(onClick={a.submit {c.confirmCancellation()}},enabled=!a.locked) {Text(stringResource(R.string.owned_meeting_cancel))}},
        dismissButton={TextButton(onClick=c::dismissCancellation,enabled=!a.locked) {Text(stringResource(R.string.events_editor_cancel))}})
    if(state.confirmDiscard) AlertDialog(onDismissRequest=c::dismissDiscard,title={Text(stringResource(R.string.owned_meeting_discard))},
        text={Text(stringResource(R.string.owned_meeting_discard_warning))},confirmButton={TextButton(onClick={a.submit {c.confirmDiscard()}},enabled=!a.locked) {Text(stringResource(R.string.owned_meeting_discard))}},
        dismissButton={TextButton(onClick=c::dismissDiscard,enabled=!a.locked) {Text(stringResource(R.string.events_editor_cancel))}})
}

@Composable
private fun OwnedMeetingStatus(c:OwnedMeetingEditorController,a:OwnedPhoneActions) {
    val state=c.state
    Column(Modifier.fillMaxWidth().padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        state.localOutcome?.let {outcome->
            Text(stringResource(if(outcome is MeetingMutationResult.LocalCancelled) R.string.owned_meeting_cancelled else R.string.owned_meeting_saved),style=MaterialTheme.typography.titleLarge,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
            Text(stringResource(R.string.owned_meeting_delivery))
            TextButton(onClick={a.submit {c.acknowledgeLocalOutcome()}},enabled=!a.locked) {Text(stringResource(R.string.phone_event_done))}
        }
        if(c.frozen && state.localOutcome==null) {
            Text(stringResource(R.string.owned_meeting_recovery))
            TextButton(onClick={a.submit {c.recover()}},enabled=c.canRecover && !a.locked) {Text(stringResource(R.string.owned_meeting_check))}
            if(state.retryReady) TextButton(onClick={a.submit {c.retryOriginal()}},enabled=!a.locked) {Text(stringResource(R.string.owned_meeting_retry))}
        }
        state.problem?.let {Text(stringResource(when(it) {
            OwnedMeetingProblem.PERMISSION -> R.string.owned_meeting_permission
            OwnedMeetingProblem.SOURCE_CHANGED -> R.string.owned_meeting_source_changed
            OwnedMeetingProblem.INVALID,OwnedMeetingProblem.NO_CHANGES -> R.string.owned_meeting_invalid
            OwnedMeetingProblem.DRAFT_CHANGED -> R.string.owned_meeting_draft_changed
            OwnedMeetingProblem.DRAFT_UNAVAILABLE -> R.string.owned_meeting_draft_unavailable
            OwnedMeetingProblem.DRAFT_REVIEW -> R.string.owned_meeting_draft_review
            OwnedMeetingProblem.SAFE_TO_RETRY -> R.string.owned_meeting_retry_ready
            OwnedMeetingProblem.RETIRED -> R.string.owned_meeting_retired
            OwnedMeetingProblem.RETIRE_BLOCKED -> R.string.owned_meeting_retire_blocked
            OwnedMeetingProblem.RETIRE_UNAVAILABLE -> R.string.owned_meeting_retire_unavailable
            OwnedMeetingProblem.UNAVAILABLE,OwnedMeetingProblem.UNCERTAIN -> R.string.owned_meeting_uncertain
        }),color=MaterialTheme.colorScheme.error,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})}
        if(c.hasPendingRawInput) TextButton(onClick={a.submit {c.flushDraft()}},enabled=!a.locked) {Text(stringResource(R.string.owned_meeting_flush))}
    }
}
