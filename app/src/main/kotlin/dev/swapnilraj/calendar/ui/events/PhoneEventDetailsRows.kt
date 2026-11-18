package dev.swapnilraj.calendar.ui.events

import android.provider.CalendarContract
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.swapnilraj.calendar.R
import dev.swapnilraj.calendar.core.*
import java.time.ZoneId

@Composable
internal fun PhoneDetailsPeople(details: EventDetails) {
    val visible = details.isOrganizer == true || details.guestPermissions.seeGuests == AccessDecision.ALLOWED
    PhoneEditorRow(PhoneEventGlyph.PEOPLE, stringResource(R.string.phone_details_people), if (visible) null else stringResource(
        if (details.guestPermissions.seeGuests == AccessDecision.DENIED) R.string.events_guests_hidden else R.string.events_guests_unavailable))
    Column(Modifier.fillMaxWidth().padding(start = 64.dp, end = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        details.organizer?.takeIf(String::isNotBlank)?.let { Text(stringResource(R.string.events_context_item, stringResource(R.string.events_organizer), it), style = MaterialTheme.typography.bodyMedium) }
        details.selfAttendeeStatus?.let { Text(stringResource(R.string.events_context_item, stringResource(R.string.events_your_response), stringResource(phoneAttendeeStatus(it)))) }
        if (visible) {
            if (details.attendees.isEmpty()) Text(stringResource(R.string.events_no_guest_details))
            details.attendees.forEach { attendee -> Column(Modifier.padding(vertical = 4.dp)) {
                Text(attendee.name?.takeIf(String::isNotBlank) ?: attendee.email, style = MaterialTheme.typography.bodyLarge)
                if (!attendee.name.isNullOrBlank() && attendee.email.isNotBlank()) Text(attendee.email, style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(phoneAttendeeStatus(attendee.status)), style = MaterialTheme.typography.bodyMedium)
                if (attendee.type == CalendarContract.Attendees.TYPE_OPTIONAL) Text(stringResource(R.string.events_optional), style = MaterialTheme.typography.bodySmall)
            } }
        }
    }
}

@Composable
internal fun PhoneDetailsSecondary(details: EventDetails, displayZone: ZoneId) {
    Column(Modifier.fillMaxWidth().padding(start = 64.dp, end = 24.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        @Composable fun Item(label: Int, value: Int) { Text(stringResource(R.string.events_context_item, stringResource(label), stringResource(value))) }
        Item(R.string.events_event_status, when(details.status) {
            CalendarContract.Events.STATUS_CONFIRMED -> R.string.events_event_confirmed
            CalendarContract.Events.STATUS_TENTATIVE -> R.string.events_event_tentative
            CalendarContract.Events.STATUS_CANCELED -> R.string.events_cancelled
            else -> R.string.events_not_provided
        })
        Item(R.string.events_availability, when (details.availability) {
            CalendarContract.Events.AVAILABILITY_BUSY -> R.string.events_busy; CalendarContract.Events.AVAILABILITY_FREE -> R.string.events_free
            CalendarContract.Events.AVAILABILITY_TENTATIVE -> R.string.events_event_tentative; else -> R.string.events_not_provided
        })
        Item(R.string.events_visibility, when (details.privacy) {
            CalendarContract.Events.ACCESS_DEFAULT -> R.string.events_calendar_default_visibility; CalendarContract.Events.ACCESS_PRIVATE -> R.string.events_private
            CalendarContract.Events.ACCESS_PUBLIC -> R.string.events_public; CalendarContract.Events.ACCESS_CONFIDENTIAL -> R.string.events_confidential
            else -> R.string.events_not_provided
        })
        details.isOrganizer?.let { Text(stringResource(if (it) R.string.events_you_organize else R.string.events_not_organizer)) }
        details.eventZone?.takeIf { it != displayZone && details.occurrence.time is EventTime.Timed }?.let { Text(stringResource(R.string.events_event_zone, PhoneEventEditorText.zone(it.id) ?: it.id)) }
        (details.occurrence.time as? EventTime.Timed)?.let { time ->
            val seconds = time.duration.seconds
            Text(when {
                time.duration.isZero -> stringResource(R.string.events_zero_duration)
                seconds == 0L -> stringResource(R.string.events_subsecond_duration)
                seconds % 60 == 0L -> pluralStringResource(R.plurals.events_duration_minutes, (seconds / 60).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), seconds / 60)
                else -> pluralStringResource(R.plurals.events_duration_seconds, seconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), seconds)
            })
        }
        if (details.guestPermissions != EventGuestPermissions()) {
            fun policy(value: AccessDecision) = when(value) { AccessDecision.ALLOWED -> R.string.events_policy_yes; AccessDecision.DENIED -> R.string.events_policy_no; AccessDecision.UNKNOWN -> R.string.events_not_provided }
            Item(R.string.events_guests_modify, policy(details.guestPermissions.modify))
            Item(R.string.events_guests_invite, policy(details.guestPermissions.inviteOthers))
            Item(R.string.events_guests_see, policy(details.guestPermissions.seeGuests))
            Text(stringResource(R.string.events_guest_policy_advisory), style = MaterialTheme.typography.bodySmall)
        }
    }
}
internal fun phoneAttendeeStatus(value: Int): Int = when(value) {
    CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED -> R.string.events_accepted
    CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED -> R.string.events_declined
    CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE -> R.string.events_tentative
    CalendarContract.Attendees.ATTENDEE_STATUS_INVITED -> R.string.events_invited
    CalendarContract.Attendees.ATTENDEE_STATUS_NONE -> R.string.events_no_response
    else -> R.string.events_not_provided
}
@Composable
internal fun phoneReminderMethod(value: Int): String = stringResource(when(value) {
    CalendarContract.Reminders.METHOD_ALERT -> R.string.events_notification
    CalendarContract.Reminders.METHOD_EMAIL -> R.string.events_email
    CalendarContract.Reminders.METHOD_SMS -> R.string.events_sms
    CalendarContract.Reminders.METHOD_ALARM -> R.string.events_alarm
    CalendarContract.Reminders.METHOD_DEFAULT -> R.string.events_default_reminder
    else -> R.string.events_other_reminder
})
