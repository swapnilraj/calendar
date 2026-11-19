package dev.swapnilraj.calendar.ui.events.meetings.owned

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import dev.swapnilraj.calendar.R
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.*
import dev.swapnilraj.calendar.ui.events.meetings.create.MeetingCreationGuestFields

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OwnedMeetingPeople(c:OwnedMeetingEditorController,a:OwnedPhoneActions,onOpenPicker:(()->Unit)?) {
    val focusManager=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    val f=a.fields ?: return;val enabled=c.canEditRaw && !a.locked
    PhoneEditorRow(PhoneEventGlyph.PEOPLE,stringResource(R.string.phone_meeting_create_people))
    Column(Modifier.fillMaxWidth().padding(start=64.dp,end=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        c.guestRows.forEach {row->key(row.ref.value) {
            val choice=f.guests.single {it.ref==row.ref}
            Column(Modifier.fillMaxWidth()) {
                Text(row.attendee.name?.takeIf(String::isNotBlank) ?: row.attendee.email,style=MaterialTheme.typography.titleSmall)
                Text(row.attendee.email,style=MaterialTheme.typography.bodyMedium.copy(textDirection=TextDirection.ContentOrLtr))
                Text(stringResource(when(row.attendee.status) {0->R.string.owned_guest_none;1->R.string.owned_guest_yes;2->R.string.owned_guest_no;3->R.string.owned_guest_invited;4->R.string.owned_guest_maybe;else->R.string.owned_guest_unknown}),style=MaterialTheme.typography.bodySmall)
                if(choice.removed) Text(stringResource(R.string.owned_guest_removed),color=MaterialTheme.colorScheme.error)
                if(MeetingGuestAction.CHANGE_TYPE in row.allowed) FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    c.allowedGuestTypes.forEach {type->FilterChip(selected=if(choice.type.isEmpty()) OwnedPatch.nativeType(type)==row.attendee.type else choice.type==type.name,
                        enabled=enabled && !choice.removed,onClick={a.raw {fields->fields.copy(guests=fields.guests.map {if(it.ref==row.ref) it.copy(type=if(OwnedPatch.nativeType(type)==row.attendee.type) "" else type.name) else it})}},label={Text(ownedGuestTypeLabel(type))})}
                }
                if(MeetingGuestAction.REMOVE in row.allowed) TextButton(enabled=enabled,onClick={a.raw {fields->fields.copy(guests=fields.guests.map {if(it.ref==row.ref) it.copy(removed=!it.removed) else it})}}) {
                    Text(stringResource(if(choice.removed) R.string.owned_guest_undo else R.string.phone_event_remove))
                }
            }
        }}
        f.added.forEachIndexed {index,guest->key(guest.rowId) {
            val person=stringResource(R.string.owned_guest_new,index+1)
            Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(person,style=MaterialTheme.typography.titleSmall)
                val email=stringResource(R.string.phone_meeting_create_email);val name=stringResource(R.string.phone_meeting_create_name)
                OutlinedTextField(guest.email,{value->a.raw {fields->fields.copy(added=fields.added.map {if(it.rowId==guest.rowId) it.copy(email=value) else it})}},
                    enabled=enabled,label={Text(email)},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email,imeAction=ImeAction.Done),
                    keyboardActions=KeyboardActions(onDone={focusManager.clearFocus();keyboard?.hide()}),textStyle=LocalTextStyle.current.copy(textDirection=TextDirection.ContentOrLtr),modifier=Modifier.fillMaxWidth().semantics {contentDescription="$person: $email"})
                OutlinedTextField(guest.name,{value->a.raw {fields->fields.copy(added=fields.added.map {if(it.rowId==guest.rowId) it.copy(name=value) else it})}},
                    enabled=enabled,label={Text(name)},modifier=Modifier.fillMaxWidth().semantics {contentDescription="$person: $name"})
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {c.allowedGuestTypes.forEach {type->FilterChip(selected=guest.type==type.name,enabled=enabled,
                    onClick={a.raw {fields->fields.copy(added=fields.added.map {if(it.rowId==guest.rowId) it.copy(type=type.name) else it})}},label={Text(ownedGuestTypeLabel(type))})}}
                TextButton(enabled=enabled,onClick={a.raw {fields->fields.copy(added=fields.added.filterNot {it.rowId==guest.rowId})}}) {Text(stringResource(R.string.phone_event_remove))}
            }
        }}
        if(c.canAddGuests && onOpenPicker!=null) TextButton(enabled=enabled,onClick=onOpenPicker) {Text(stringResource(R.string.guest_picker_open))}
        if(c.canAddGuests) TextButton(enabled=enabled,onClick={a.raw {it.copy(added=it.added+MeetingCreationGuestFields())}}) {Text(stringResource(R.string.phone_meeting_create_add_person))}
    }
}
@Composable
private fun ownedGuestTypeLabel(type:MeetingGuestType)=stringResource(when(type) {
    MeetingGuestType.REQUIRED->R.string.phone_meeting_create_required
    MeetingGuestType.OPTIONAL->R.string.phone_meeting_create_optional
    MeetingGuestType.UNSPECIFIED->R.string.phone_meeting_create_unspecified
})
