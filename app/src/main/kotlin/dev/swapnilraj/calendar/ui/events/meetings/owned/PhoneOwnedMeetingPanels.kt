package dev.swapnilraj.calendar.ui.events.meetings.owned

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.swapnilraj.calendar.R
import dev.swapnilraj.calendar.core.EditableField
import dev.swapnilraj.calendar.ui.events.*

@Composable
internal fun OwnedMeetingPanel(panel:PhoneEditorPanel,c:OwnedMeetingEditorController,a:OwnedPhoneActions,use24Hour:Boolean,dismiss:()->Unit) {
    val raw=a.fields?.event ?: return
    fun enabled(f:EditableField)=c.canEdit(f) && !a.locked
    when(panel) {
        PhoneEditorPanel.START_DATE,PhoneEditorPanel.END_DATE -> {
            val start=panel==PhoneEditorPanel.START_DATE
            PhoneEditorDatePicker(if(start) raw.startDate else raw.endDate,stringResource(if(start) R.string.phone_event_start_date else R.string.phone_event_end_date),enabled(EditableField.TIME),dismiss) {v->a.event {if(start) it.copy(startDate=v) else it.copy(endDate=v)}}
        }
        PhoneEditorPanel.START_TIME,PhoneEditorPanel.END_TIME -> {
            val start=panel==PhoneEditorPanel.START_TIME
            PhoneEditorTimePicker(if(start) raw.startTime else raw.endTime,stringResource(if(start) R.string.phone_event_start_time else R.string.phone_event_end_time),use24Hour,enabled(EditableField.TIME),dismiss) {v->a.event {if(start) it.copy(startTime=v) else it.copy(endTime=v)}}
        }
        PhoneEditorPanel.ZONE -> PhoneEditorZonePanel(raw.zone,enabled(EditableField.ZONE),dismiss,a::zone)
        PhoneEditorPanel.ALERTS -> PhoneEditorAlertPanel(raw.alerts,enabled(EditableField.ALERTS),dismiss) {v->a.event {it.copy(alerts=v)}}
        PhoneEditorPanel.LOCATION,PhoneEditorPanel.DESCRIPTION -> {
            val location=panel==PhoneEditorPanel.LOCATION
            PhoneEditorPanelFrame(stringResource(if(location) R.string.events_location else R.string.events_notes),dismiss) {
                OutlinedTextField(if(location) raw.location else raw.description,{v->a.event {if(location) it.copy(location=v) else it.copy(description=v)}},
                    enabled=enabled(if(location) EditableField.LOCATION else EditableField.DESCRIPTION),label={Text(stringResource(if(location) R.string.events_location else R.string.events_notes))},modifier=Modifier.fillMaxWidth().padding(24.dp),minLines=if(location) 1 else 5)
            }
        }
        PhoneEditorPanel.PRIVACY -> PhoneEditorPanelFrame(stringResource(R.string.events_visibility),dismiss) {
            Column(Modifier.verticalScroll(rememberScrollState())) {listOf("","0","1","2","3").forEach {value->
                Row(Modifier.fillMaxWidth().selectable(raw.privacy==value,enabled=enabled(EditableField.PRIVACY),role=Role.RadioButton,onClick={a.event {it.copy(privacy=value)};dismiss()}).padding(16.dp)) {
                    RadioButton(raw.privacy==value,null,enabled=enabled(EditableField.PRIVACY));Text(phoneMetadataLabel(value,true),Modifier.padding(start=16.dp))
                }
            }}
        }
        PhoneEditorPanel.CALENDAR,PhoneEditorPanel.AVAILABILITY,PhoneEditorPanel.REPEAT -> Unit
    }
}
