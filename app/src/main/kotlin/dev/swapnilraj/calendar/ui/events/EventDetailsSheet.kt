package dev.swapnilraj.calendar.ui.events

import android.provider.CalendarContract
import androidx.core.text.BidiFormatter
import androidx.core.text.TextDirectionHeuristicsCompat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.swapnilraj.calendar.R
import dev.swapnilraj.calendar.core.*
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** State adapter for the live detail reader. Retry and permission recovery remain caller-owned. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetailsStateSheet(
    state: CalendarReadState<EventDetails>,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onOpenLink: (String) -> Unit,
    displayZone: ZoneId = ZoneId.systemDefault(),
    use24Hour: Boolean = true,
    onEdit: ((EventRef) -> Unit)? = null,
    onDelete: ((EventRef) -> Unit)? = null,
) {
    if (state is CalendarReadState.Ready) {
        EventDetailsSheet(state.value, onDismiss, onOpenLink, displayZone, use24Hour, onEdit, onDelete)
    } else {
        ModalBottomSheet(onDismissRequest = onDismiss) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (state) {
                    CalendarReadState.Loading -> {
                        Text(stringResource(R.string.events_loading))
                        CircularProgressIndicator()
                    }
                    CalendarReadState.PermissionRequired -> Text(stringResource(R.string.events_permission_required))
                    CalendarReadState.Unavailable -> {
                        Text(stringResource(R.string.events_unavailable))
                        TextButton(onClick = onRetry) { Text(stringResource(R.string.events_retry)) }
                    }
                    is CalendarReadState.Ready -> Unit
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.events_close)) }
            }
        }
    }
}

/** Stateless: the caller owns loading, navigation, permission checks and external activity handling. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetailsSheet(
    details: EventDetails,
    onDismiss: () -> Unit,
    onOpenLink: (String) -> Unit,
    displayZone: ZoneId = ZoneId.systemDefault(),
    use24Hour: Boolean = true,
    onEdit: ((EventRef) -> Unit)? = null,
    onDelete: ((EventRef) -> Unit)? = null,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        EventDetailsContent(details, onDismiss, onOpenLink, displayZone, use24Hour, onEdit, onDelete)
    }
}

@Composable
fun EventDetailsContent(
    details: EventDetails,
    onDismiss: () -> Unit,
    onOpenLink: (String) -> Unit,
    displayZone: ZoneId = ZoneId.systemDefault(),
    use24Hour: Boolean = true,
    onEdit: ((EventRef) -> Unit)? = null,
    onDelete: ((EventRef) -> Unit)? = null,
) {
    val locale = LocalLocale.current.platformLocale
    val links = remember(details.description, details.location) {
        eventWebLinks(details.location + "\n" + details.description)
    }
    val meetingLinks = links.filter(::isMeetingLink)
    val cancelled = details.status == CalendarContract.Events.STATUS_CANCELED
    val joinLink = if (cancelled) null else meetingLinks.singleOrNull()
    val guestListVisible = details.isOrganizer == true || details.guestPermissions.seeGuests == AccessDecision.ALLOWED
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.padding(top = 9.dp).size(12.dp).background(Color(details.occurrence.color or 0xff000000.toInt()), CircleShape))
            Text(details.occurrence.title.ifBlank { stringResource(R.string.events_no_title) },
                style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f).semantics { heading() },
                textDecoration = if (details.occurrence.declined || cancelled) TextDecoration.LineThrough else null)
        }
        if (cancelled) Text(stringResource(R.string.events_cancelled), color = MaterialTheme.colorScheme.error)
        if (details.occurrence.declined && details.selfAttendeeStatus == null) Text(stringResource(R.string.events_declined))
        when (val time = details.occurrence.time) {
            is EventTime.AllDay -> {
                Text(stringResource(R.string.events_all_day))
                Text(allDayDateText(time, locale))
            }
            is EventTime.Timed -> {
                Text(timedDateText(time, displayZone, locale, use24Hour))
                val seconds = time.duration.seconds
                val minutes = seconds / 60
                Text(when {
                    time.duration.isZero -> stringResource(R.string.events_zero_duration)
                    seconds == 0L -> stringResource(R.string.events_subsecond_duration)
                    seconds % 60 == 0L -> pluralStringResource(R.plurals.events_duration_minutes,
                        minutes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), minutes)
                    else -> pluralStringResource(R.plurals.events_duration_seconds,
                        seconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), seconds)
                })
                Text(stringResource(R.string.events_display_zone, displayZone.id))
                details.eventZone?.takeIf { it != displayZone }?.let { eventZone ->
                    Text(stringResource(R.string.events_event_zone, eventZone.id))
                }
            }
        }
        if (details.status != null || details.availability != null || details.privacy != null) {
            DetailSection(stringResource(R.string.events_event_context)) {
                Text(stringResource(R.string.events_context_item, stringResource(R.string.events_event_status),
                    stringResource(eventStatus(details.status))))
                Text(stringResource(R.string.events_context_item, stringResource(R.string.events_availability),
                    stringResource(eventAvailability(details.availability))))
                Text(stringResource(R.string.events_context_item, stringResource(R.string.events_visibility),
                    stringResource(eventPrivacy(details.privacy))))
            }
        }
        details.isOrganizer?.let { organizer ->
            Text(stringResource(if (organizer) R.string.events_you_organize else R.string.events_not_organizer))
        }
        details.selfAttendeeStatus?.let { response ->
            Text(stringResource(R.string.events_context_item, stringResource(R.string.events_your_response), stringResource(attendeeStatus(response))))
        }
        details.calendar?.let { calendar ->
            DetailSection(stringResource(R.string.events_calendar)) {
                Text(calendar.displayName)
                val accountType = stringResource(accountLabel(calendar.account.type))
                Text(if (calendar.account.type == CalendarContract.ACCOUNT_TYPE_LOCAL || calendar.account.name.isBlank()) accountType
                    else stringResource(R.string.events_account, calendar.account.name, accountType))
            }
        }
        if (joinLink != null) {
            Button(onClick = { onOpenLink(joinLink) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.events_join))
            }
        }
        if (details.location.isNotBlank()) DetailSection(stringResource(R.string.events_location)) {
            Text(details.location)
            TextButton(onClick = { onOpenLink("geo:0,0?q=" + encode(details.location)) }) {
                Text(stringResource(R.string.events_open_map))
            }
        }
        if (details.description.isNotBlank()) DetailSection(stringResource(R.string.events_notes)) {
            // Plain text deliberately never executes provider-supplied HTML or script markup.
            Text(details.description)
        }
        if (links.any { it != joinLink }) DetailSection(stringResource(R.string.events_links)) {
            links.filter { it != joinLink }.forEach { link ->
                TextButton(onClick = { onOpenLink(link) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(link) }
            }
        }
        details.organizer?.takeIf(String::isNotBlank)?.let { organizer ->
            DetailSection(stringResource(R.string.events_organizer)) { Text(organizer) }
        }
        DetailSection(stringResource(R.string.events_guests)) {
            if (!guestListVisible) Text(stringResource(if (details.guestPermissions.seeGuests == AccessDecision.DENIED)
                R.string.events_guests_hidden else R.string.events_guests_unavailable))
            else if (details.attendees.isEmpty()) Text(stringResource(R.string.events_no_guest_details))
            if (guestListVisible) details.attendees.forEach { attendee ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(attendee.name?.takeIf(String::isNotBlank) ?: attendee.email)
                    if (!attendee.name.isNullOrBlank() && attendee.email.isNotBlank()) Text(attendee.email)
                    Text(stringResource(attendeeStatus(attendee.status)))
                    if (attendee.type == CalendarContract.Attendees.TYPE_OPTIONAL) Text(stringResource(R.string.events_optional))
                }
            }
        }
        if (details.guestPermissions != EventGuestPermissions()) DetailSection(stringResource(R.string.events_guest_policy)) {
            Text(stringResource(R.string.events_context_item, stringResource(R.string.events_guests_modify),
                stringResource(policyDecision(details.guestPermissions.modify))))
            Text(stringResource(R.string.events_context_item, stringResource(R.string.events_guests_invite),
                stringResource(policyDecision(details.guestPermissions.inviteOthers))))
            Text(stringResource(R.string.events_context_item, stringResource(R.string.events_guests_see),
                stringResource(policyDecision(details.guestPermissions.seeGuests))))
            Text(stringResource(R.string.events_guest_policy_advisory), style = MaterialTheme.typography.bodySmall)
        }
        details.recurrenceRule?.takeIf(String::isNotBlank)?.let { rule ->
            DetailSection(stringResource(R.string.events_repeat)) { Text(stringResource(R.string.events_repeat_rule, rule)) }
        }
        DetailSection(stringResource(R.string.events_reminders)) {
            if (details.reminders.isEmpty()) Text(stringResource(R.string.events_no_reminders))
            details.reminders.forEach { reminder ->
                val method = stringResource(when (reminder.method) {
                    CalendarContract.Reminders.METHOD_ALERT -> R.string.events_notification
                    CalendarContract.Reminders.METHOD_EMAIL -> R.string.events_email
                    CalendarContract.Reminders.METHOD_SMS -> R.string.events_sms
                    CalendarContract.Reminders.METHOD_ALARM -> R.string.events_alarm
                    CalendarContract.Reminders.METHOD_DEFAULT -> R.string.events_default_reminder
                    else -> R.string.events_other_reminder
                })
                val minutes = reminder.minutesBefore.toLong()
                val timing = if (reminder.minutesBefore == CalendarContract.Reminders.MINUTES_DEFAULT) {
                    stringResource(R.string.events_default_timing)
                } else if (minutes == 0L) stringResource(R.string.events_at_start) else if (minutes > 0) {
                    pluralStringResource(R.plurals.events_minutes_before, minutes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), minutes)
                } else {
                    pluralStringResource(R.plurals.events_minutes_after, (-minutes).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), -minutes)
                }
                Text(stringResource(R.string.events_reminder_item, method, timing))
            }
        }
        if (details.canEdit == AccessDecision.ALLOWED && !cancelled) {
            onEdit?.let { action -> Button(onClick = { action(details.occurrence.ref) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.events_editor_edit))
            } }
            onDelete?.let { action -> TextButton(onClick = { action(details.occurrence.ref) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.events_editor_delete))
            } }
        }
        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.events_close)) }
    }
}

@Composable
private fun DetailSection(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() })
        content()
    }
}

private fun attendeeStatus(status: Int): Int = when (status) {
    CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED -> R.string.events_accepted
    CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED -> R.string.events_declined
    CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE -> R.string.events_tentative
    CalendarContract.Attendees.ATTENDEE_STATUS_INVITED -> R.string.events_invited
    CalendarContract.Attendees.ATTENDEE_STATUS_NONE -> R.string.events_no_response
    else -> R.string.events_not_provided
}

private fun eventStatus(value: Int?): Int = when (value) {
    CalendarContract.Events.STATUS_TENTATIVE -> R.string.events_event_tentative
    CalendarContract.Events.STATUS_CONFIRMED -> R.string.events_event_confirmed
    CalendarContract.Events.STATUS_CANCELED -> R.string.events_cancelled
    else -> R.string.events_not_provided
}

private fun eventAvailability(value: Int?): Int = when (value) {
    CalendarContract.Events.AVAILABILITY_BUSY -> R.string.events_busy
    CalendarContract.Events.AVAILABILITY_FREE -> R.string.events_free
    CalendarContract.Events.AVAILABILITY_TENTATIVE -> R.string.events_event_tentative
    else -> R.string.events_not_provided
}

private fun eventPrivacy(value: Int?): Int = when (value) {
    CalendarContract.Events.ACCESS_DEFAULT -> R.string.events_calendar_default_visibility
    CalendarContract.Events.ACCESS_CONFIDENTIAL -> R.string.events_confidential
    CalendarContract.Events.ACCESS_PRIVATE -> R.string.events_private
    CalendarContract.Events.ACCESS_PUBLIC -> R.string.events_public
    else -> R.string.events_not_provided
}

private fun policyDecision(value: AccessDecision): Int = when (value) {
    AccessDecision.ALLOWED -> R.string.events_policy_yes
    AccessDecision.DENIED -> R.string.events_policy_no
    AccessDecision.UNKNOWN -> R.string.events_not_provided
}

private fun accountLabel(type: String): Int = when (type) {
    "com.google" -> R.string.events_google_account
    CalendarContract.ACCOUNT_TYPE_LOCAL -> R.string.events_device_account
    else -> R.string.events_calendar_account
}

internal fun allDayDateText(time: EventTime.AllDay, locale: Locale): String {
    val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    val start = time.dates.start.format(formatter)
    val last = time.dates.endExclusive.minusDays(1)
    return if (last == time.dates.start) start else "$start – ${last.format(formatter)}"
}

internal fun timedDateText(time: EventTime.Timed, zone: ZoneId, locale: Locale, use24Hour: Boolean): String {
    val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    val clock = DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm O" else "h:mm a O", locale)
    val start = time.start.atZone(zone)
    val end = time.end.atZone(zone)
    return BidiFormatter.getInstance(locale).unicodeWrap(
        "${start.format(date)} ${start.format(clock)} – ${end.format(date)} ${end.format(clock)}",
        TextDirectionHeuristicsCompat.LTR, true,
    )
}

/** Only absolute HTTP(S) links with hosts. Strip prose punctuation without accepting script schemes. */
internal fun eventWebLinks(text: String): List<String> = Regex("https?://[^\\s<>\\\"']+", RegexOption.IGNORE_CASE)
    .findAll(text).map { it.value.trimEnd('.', ',', ';', '!', '?', ')', ']', '}') }
    .filter { value -> runCatching { URI(value).let { it.host != null && it.userInfo == null } }.getOrDefault(false) }
    .distinct().toList()

internal fun isMeetingLink(link: String): Boolean {
    val host = runCatching { URI(link).host?.lowercase(Locale.ROOT) }.getOrNull() ?: return false
    return host == "meet.google.com" || host == "zoom.us" || host.endsWith(".zoom.us") ||
        host == "teams.microsoft.com" || host == "teams.live.com" || host == "meet.jit.si" ||
        host == "webex.com" || host.endsWith(".webex.com")
}

private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
