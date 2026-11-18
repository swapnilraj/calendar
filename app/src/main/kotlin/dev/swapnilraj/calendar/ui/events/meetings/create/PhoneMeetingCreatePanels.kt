package dev.swapnilraj.calendar.ui.events.meetings.create

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.swapnilraj.calendar.R
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.*

@Composable
internal fun PhoneMeetingCreatePanel(panel:PhoneEditorPanel,c:MeetingCreationController,calendars:List<CalendarInfo>,
    enabled:Boolean,actions:PhoneMeetingCreateActions,use24Hour:Boolean,onDismiss:()->Unit) {
    val raw=actions.fields?.event ?: return
    when(panel) {
        PhoneEditorPanel.START_DATE,PhoneEditorPanel.END_DATE -> {
            val start=panel==PhoneEditorPanel.START_DATE
            PhoneEditorDatePicker(if(start) raw.startDate else raw.endDate,stringResource(if(start) R.string.phone_event_start_date else R.string.phone_event_end_date),enabled,onDismiss) { value -> actions.event {if(start) it.copy(startDate=value) else it.copy(endDate=value)} }
        }
        PhoneEditorPanel.START_TIME,PhoneEditorPanel.END_TIME -> {
            val start=panel==PhoneEditorPanel.START_TIME
            PhoneEditorTimePicker(if(start) raw.startTime else raw.endTime,stringResource(if(start) R.string.phone_event_start_time else R.string.phone_event_end_time),use24Hour,enabled,onDismiss) {value -> actions.event {if(start) it.copy(startTime=value) else it.copy(endTime=value)} }
        }
        PhoneEditorPanel.ZONE -> PhoneEditorZonePanel(raw.zone,enabled,onDismiss) {value -> actions.event {it.copy(zone=value)}}
        PhoneEditorPanel.ALERTS -> PhoneEditorAlertPanel(raw.alerts,enabled,onDismiss) {value -> actions.event {it.copy(alerts=value)}}
        PhoneEditorPanel.CALENDAR -> PhoneEditorPanelFrame(stringResource(R.string.phone_event_calendar),onDismiss) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                calendars.forEach { calendar ->
                    val selected=raw.targetIdentity==calendar.identity
                    Row(Modifier.fillMaxWidth().selectable(selected,enabled=c.canChange,role=Role.RadioButton,onClick={actions.submit {c.selectCalendar(calendar)};onDismiss()}).padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                        RadioButton(selected,onClick=null,enabled=c.canChange)
                        Column {Text(calendar.displayName);Text(calendar.account.name,style=MaterialTheme.typography.bodySmall)}
                    }
                }
            }
        }
        PhoneEditorPanel.LOCATION,PhoneEditorPanel.DESCRIPTION -> {
            val location=panel==PhoneEditorPanel.LOCATION
            PhoneEditorPanelFrame(stringResource(if(location) R.string.events_location else R.string.events_notes),onDismiss) {
                OutlinedTextField(if(location) raw.location else raw.description,{value -> actions.event {if(location) it.copy(location=value) else it.copy(description=value)}},enabled=enabled,
                    label={Text(stringResource(if(location) R.string.events_location else R.string.events_notes))},modifier=Modifier.fillMaxWidth().padding(24.dp),minLines=if(location) 1 else 5)
            }
        }
        PhoneEditorPanel.PRIVACY,PhoneEditorPanel.AVAILABILITY -> {
            val privacy=panel==PhoneEditorPanel.PRIVACY
            PhoneEditorPanelFrame(stringResource(if(privacy) R.string.events_visibility else R.string.events_availability),onDismiss) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    (if(privacy) listOf("","0","1","2","3") else listOf("","0","1","2")).forEach {value ->
                        val selected=(if(privacy) raw.privacy else raw.availability)==value
                        Row(Modifier.fillMaxWidth().selectable(selected,enabled=enabled,role=Role.RadioButton,onClick={actions.event {if(privacy) it.copy(privacy=value) else it.copy(availability=value)};onDismiss()}).padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
                            RadioButton(selected,onClick=null,enabled=enabled);Text(phoneMetadataLabel(value,privacy),Modifier.padding(start=16.dp))
                        }
                    }
                }
            }
        }
        PhoneEditorPanel.REPEAT -> if(c.canEditRepeat) PhoneEditorPanelFrame(stringResource(R.string.recurrence_editor_repeat),onDismiss) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
                RecurrenceEditorControls(raw.recurrence,raw.startDate,enabled && c.canEditRepeat) { value -> actions.event {it.copy(recurrence=value)} }
            }
        }
    }
}
