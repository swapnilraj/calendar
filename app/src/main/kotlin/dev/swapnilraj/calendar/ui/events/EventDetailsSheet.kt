package dev.swapnilraj.calendar.ui.events

import android.provider.CalendarContract
import androidx.core.text.BidiFormatter
import androidx.core.text.TextDirectionHeuristicsCompat
import androidx.compose.foundation.layout.*
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
    val joinLink = meetingLinks.singleOrNull()
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(details.occurrence.title.ifBlank { stringResource(R.string.events_no_title) },
            style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() },
            textDecoration = if (details.occurrence.declined) TextDecoration.LineThrough else null)
        if (details.occurrence.declined) Text(stringResource(R.string.events_declined))
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
        details.calendar?.let { calendar ->
            DetailSection(stringResource(R.string.events_calendar)) {
                Text(calendar.displayName)
                Text(stringResource(R.string.events_account, calendar.account.name, calendar.account.type))
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
        if (details.attendees.isNotEmpty()) DetailSection(stringResource(R.string.events_guests)) {
            details.attendees.forEach { attendee ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(attendee.name?.takeIf(String::isNotBlank) ?: attendee.email)
                    if (!attendee.name.isNullOrBlank() && attendee.email.isNotBlank()) Text(attendee.email)
                    Text(stringResource(attendeeStatus(attendee.status)))
                    if (attendee.type == CalendarContract.Attendees.TYPE_OPTIONAL) Text(stringResource(R.string.events_optional))
                }
            }
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
        if (details.canEdit == AccessDecision.ALLOWED) {
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
    else -> R.string.events_no_response
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
