package dev.swapnilraj.calendar.ui.events.meetings.create

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import dev.swapnilraj.calendar.R
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PhoneMeetingCreatePeople(guests: List<MeetingCreationGuestFields>, types: Set<MeetingGuestType>,
    enabled: Boolean, actions: PhoneMeetingCreateActions) {
    PhoneEditorRow(PhoneEventGlyph.PEOPLE,stringResource(R.string.phone_meeting_create_people))
    Column(Modifier.fillMaxWidth().padding(start=64.dp,end=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        guests.forEachIndexed { index,guest -> key(guest.rowId) {
            val person=stringResource(R.string.phone_meeting_create_person,index+1)
            val emailLabel=stringResource(R.string.phone_meeting_create_email)
            val nameLabel=stringResource(R.string.phone_meeting_create_name)
            val removeLabel=stringResource(R.string.phone_meeting_create_remove,index+1)
            Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text(person,Modifier.weight(1f),style=MaterialTheme.typography.titleSmall)
                    TextButton(onClick={ actions.guests { rows -> rows.filterNot { it.rowId==guest.rowId } } },enabled=enabled,modifier=Modifier.semantics { contentDescription=removeLabel }) { Text(stringResource(R.string.phone_event_remove)) }
                }
                OutlinedTextField(guest.email,{ value -> actions.guests { rows -> rows.map { if(it.rowId==guest.rowId) it.copy(email=value) else it } } },
                    label={Text(emailLabel)},enabled=enabled,singleLine=true,textStyle=LocalTextStyle.current.copy(textDirection=TextDirection.ContentOrLtr),modifier=Modifier.fillMaxWidth().semantics {contentDescription="$person: $emailLabel"})
                OutlinedTextField(guest.name,{ value -> actions.guests { rows -> rows.map { if(it.rowId==guest.rowId) it.copy(name=value) else it } } },
                    label={Text(nameLabel)},enabled=enabled,textStyle=LocalTextStyle.current.copy(textDirection=TextDirection.ContentOrLtr),modifier=Modifier.fillMaxWidth().semantics {contentDescription="$person: $nameLabel"})
                if(guest.type.isBlank()) Text(stringResource(R.string.phone_meeting_create_choose_role),style=MaterialTheme.typography.bodySmall)
                else if(types.none { it.name==guest.type }) Text(stringResource(R.string.phone_meeting_create_raw_role,guest.type),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                if(types.isEmpty()) Text(stringResource(R.string.phone_meeting_create_role_unavailable),style=MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { MeetingGuestType.entries.filter(types::contains).forEach { type ->
                    FilterChip(selected=guest.type==type.name,enabled=enabled,onClick={ actions.guests { rows -> rows.map { if(it.rowId==guest.rowId) it.copy(type=type.name) else it } } },label={ Text(stringResource(when(type) {
                        MeetingGuestType.REQUIRED -> R.string.phone_meeting_create_required
                        MeetingGuestType.OPTIONAL -> R.string.phone_meeting_create_optional
                        MeetingGuestType.UNSPECIFIED -> R.string.phone_meeting_create_unspecified
                    })) })
                } }
            }
        } }
        if(guests.isEmpty()) Text(stringResource(R.string.phone_meeting_create_guests_required),style=MaterialTheme.typography.bodySmall)
        TextButton(enabled=enabled,onClick={actions.guests { it+MeetingCreationGuestFields() }}) {Text(stringResource(R.string.phone_meeting_create_add_person))}
    }
}

@Composable
internal fun PhoneMeetingCreatePolicy(accepted: Boolean, enabled: Boolean, onChange:(Boolean)->Unit) {
    Column(Modifier.fillMaxWidth().padding(start=64.dp,end=24.dp,top=12.dp,bottom=16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.phone_meeting_create_policy_title),style=MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.phone_meeting_create_policy),style=MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth().toggleable(accepted,enabled=enabled,role=Role.Checkbox,onValueChange=onChange).heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically) {
            Checkbox(accepted,onCheckedChange=null,enabled=enabled)
            Text(stringResource(R.string.phone_meeting_create_policy_ack),modifier=Modifier.weight(1f).padding(start=8.dp))
        }
    }
}

@Composable
internal fun PhoneMeetingCreateStatus(c:MeetingCreationController, actions:PhoneMeetingCreateActions) {
    val state=c.state
    Column(Modifier.fillMaxWidth().padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        if(state.localSaved!=null) {
            Text(stringResource(R.string.phone_meeting_create_saved),style=MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.phone_meeting_create_saved_help))
            TextButton(onClick={actions.submit { c.acknowledgeLocalSave() }},enabled=!state.busy) {Text(stringResource(R.string.phone_event_done))}
        } else if(c.frozen) {
            Text(stringResource(R.string.phone_meeting_create_recovery_help),style=MaterialTheme.typography.bodyMedium)
            TextButton(onClick={actions.submit { c.recover() }},enabled=!state.busy) {Text(stringResource(R.string.phone_meeting_create_recovery))}
            if(state.retryReady) TextButton(onClick={actions.submit {c.retryOriginal()}},enabled=!state.busy) {Text(stringResource(R.string.phone_meeting_create_retry))}
        }
        state.problem?.let { problem ->
            Text(stringResource(if(problem==MeetingCreationProblem.PERMISSION && c.frozen) R.string.phone_meeting_create_pending_permission else if(problem==MeetingCreationProblem.DRAFT_UNAVAILABLE && !c.hasPendingRawInput) R.string.phone_meeting_create_draft_unavailable else phoneMeetingCreateProblem(problem)),color=MaterialTheme.colorScheme.error)
            if(problem==MeetingCreationProblem.DRAFT_UNAVAILABLE && c.hasPendingRawInput) TextButton(onClick={actions.submit {c.flushDraft()}},enabled=!state.busy) {Text(stringResource(R.string.phone_meeting_create_save_draft))}
        }
    }
}

internal fun phoneMeetingCreateProblem(p:MeetingCreationProblem)=when(p) {
    MeetingCreationProblem.DRAFT_UNAVAILABLE -> R.string.phone_meeting_create_draft_error
    MeetingCreationProblem.DRAFT_REVIEW -> R.string.phone_meeting_create_draft_review
    MeetingCreationProblem.STALE_DRAFT -> R.string.phone_meeting_create_stale
    MeetingCreationProblem.CALENDAR_REQUIRED -> R.string.phone_meeting_create_calendar_required
    MeetingCreationProblem.SOURCE_CHANGED -> R.string.phone_meeting_create_source_changed
    MeetingCreationProblem.PERMISSION -> R.string.phone_meeting_create_permission
    MeetingCreationProblem.DENIED -> R.string.phone_meeting_create_denied
    MeetingCreationProblem.LIMIT_EXCEEDED -> R.string.phone_meeting_create_limit
    MeetingCreationProblem.UNAVAILABLE -> R.string.phone_meeting_create_unavailable
    MeetingCreationProblem.INVALID_EVENT -> R.string.phone_meeting_create_invalid_event
    MeetingCreationProblem.INVALID_GUEST -> R.string.phone_meeting_create_invalid_guest
    MeetingCreationProblem.GUESTS_REQUIRED -> R.string.phone_meeting_create_guests_required
    MeetingCreationProblem.POLICY_REQUIRED -> R.string.phone_meeting_create_policy_required
    MeetingCreationProblem.RECURRENCE_UNSUPPORTED -> R.string.phone_meeting_create_recurrence
    MeetingCreationProblem.RECURRENCE_INVALID -> R.string.phone_meeting_create_recurrence_invalid
    MeetingCreationProblem.RECURRENCE_REVIEW -> R.string.phone_meeting_create_recurrence_review
    MeetingCreationProblem.UNCERTAIN -> R.string.phone_meeting_create_uncertain
    MeetingCreationProblem.SAFE_TO_RETRY -> R.string.phone_meeting_create_safe_retry
    MeetingCreationProblem.RETIRED -> R.string.phone_meeting_create_retired
    MeetingCreationProblem.RETIRE_UNAVAILABLE -> R.string.phone_meeting_create_retire_unavailable
    MeetingCreationProblem.RETIRE_BLOCKED_DISPATCH -> R.string.phone_meeting_create_retire_dispatch
    MeetingCreationProblem.RETIRE_BLOCKED_COMMIT -> R.string.phone_meeting_create_retire_commit
    MeetingCreationProblem.RETIRE_CONFLICT -> R.string.phone_meeting_create_retire_conflict
    MeetingCreationProblem.RETIRE_UNSUPPORTED -> R.string.phone_meeting_create_retire_unsupported
}
