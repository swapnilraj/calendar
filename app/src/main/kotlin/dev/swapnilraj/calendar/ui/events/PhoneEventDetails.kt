package dev.swapnilraj.calendar.ui.events

import android.provider.CalendarContract
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.swapnilraj.calendar.R
import dev.swapnilraj.calendar.core.*
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.ZoneId

/** No retained Ready payload or inferred rights: the host owns the current authorized read state. */
@Composable
fun PhoneEventDetails(
    state: CalendarReadState<EventDetails>, onDismiss: () -> Unit, onRetry: () -> Unit,
    onOpenLink: (String) -> Unit, displayZone: ZoneId = ZoneId.systemDefault(), use24Hour: Boolean = true,
    onEdit: ((EventRef) -> Unit)? = null, onDelete: ((EventRef) -> Unit)? = null,
    onDuplicate: ((EventDetails) -> Unit)? = null, onRespond: ((EventRef) -> Unit)? = null,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize()) {
            if (state is CalendarReadState.Ready) PhoneEventDetailsReady(state.value, onDismiss, onOpenLink, displayZone, use24Hour, onEdit, onDelete, onDuplicate, onRespond)
            else Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                PhoneDetailsToolbar(onDismiss)
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    when (state) {
                        CalendarReadState.Loading -> { CircularProgressIndicator(); Text(stringResource(R.string.events_loading)) }
                        CalendarReadState.PermissionRequired -> Text(stringResource(R.string.events_permission_required))
                        CalendarReadState.Unavailable -> { Text(stringResource(R.string.events_unavailable)); TextButton(onClick = onRetry) { Text(stringResource(R.string.events_retry)) } }
                        CalendarReadState.LimitExceeded -> {
                            Text(stringResource(R.string.events_details_limit_title), style = MaterialTheme.typography.titleLarge)
                            Text(stringResource(R.string.events_details_limit_help))
                            TextButton(onClick = onRetry) { Text(stringResource(R.string.events_retry)) }
                        }
                        is CalendarReadState.Ready -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun PhoneEventDetailsReady(details: EventDetails, onDismiss: () -> Unit, onOpenLink: (String) -> Unit,
    displayZone: ZoneId, use24Hour: Boolean, onEdit: ((EventRef) -> Unit)?, onDelete: ((EventRef) -> Unit)?,
    onDuplicate: ((EventDetails) -> Unit)?, onRespond: ((EventRef) -> Unit)?) {
    val locale = LocalLocale.current.platformLocale
    val ref = details.occurrence.ref
    val cancelled = details.status == CalendarContract.Events.STATUS_CANCELED
    val canEdit = details.canEdit == AccessDecision.ALLOWED && !cancelled
    val links = remember(details.location, details.description) { eventWebLinks(details.location + "\n" + details.description) }
    val join = if (cancelled) null else links.filter(::isMeetingLink).singleOrNull()
    var expanded by remember(ref) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        PhoneDetailsToolbar(onDismiss, onEdit = if (canEdit) onEdit?.let { { it(ref) } } else null,
            onDelete = if (canEdit) onDelete?.let { { it(ref) } } else null,
            onDuplicate = onDuplicate?.let { { it(details) } }, ref = ref)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 10.dp).size(12.dp).background(Color(details.occurrence.color or 0xff000000.toInt()), CircleShape))
                Text(details.occurrence.title.ifBlank { stringResource(R.string.events_no_title) }, style = MaterialTheme.typography.headlineMedium,
                    textDecoration = if (cancelled || details.occurrence.declined) TextDecoration.LineThrough else null,
                    modifier = Modifier.weight(1f).semantics { heading() })
            }
            if (cancelled) Text(stringResource(R.string.events_cancelled), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 64.dp, end = 24.dp))
            if (details.occurrence.declined && details.selfAttendeeStatus == null) Text(stringResource(R.string.events_declined), modifier = Modifier.padding(start = 64.dp, end = 24.dp))
            val date = when (val time = details.occurrence.time) {
                is EventTime.AllDay -> allDayDateText(time, locale)
                is EventTime.Timed -> timedDateText(time, displayZone, locale, use24Hour)
            }
            PhoneEditorRow(PhoneEventGlyph.CLOCK, date, if (details.occurrence.time is EventTime.AllDay) stringResource(R.string.events_all_day)
                else stringResource(R.string.events_display_zone, PhoneEventEditorText.zone(displayZone.id) ?: displayZone.id))
            recurrenceSummary(details, displayZone, locale)?.let { PhoneEditorRow(PhoneEventGlyph.REPEAT, it) }
            join?.let { url -> Row(Modifier.fillMaxWidth().padding(start = 64.dp, end = 24.dp, top = 8.dp, bottom = 12.dp)) {
                Button(onClick = { onOpenLink(url) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.events_join)) }
            } }
            if (details.location.isNotBlank()) PhoneEditorRow(PhoneEventGlyph.PLACE, details.location, stringResource(R.string.events_open_map), enabled = true,
                onClick = { onOpenLink("geo:0,0?q=" + URLEncoder.encode(details.location, StandardCharsets.UTF_8.name())) })
            if (details.reminders.isEmpty()) PhoneEditorRow(PhoneEventGlyph.ALERT, stringResource(R.string.events_no_reminders))
            details.reminders.forEach { reminder -> PhoneEditorRow(PhoneEventGlyph.ALERT, phoneReminderMethod(reminder.method), phoneAlertLabel(reminder.minutesBefore)) }
            details.calendar?.let { calendar -> PhoneEditorRow(PhoneEventGlyph.CALENDAR, calendar.displayName,
                if (calendar.account.type == CalendarContract.ACCOUNT_TYPE_LOCAL) stringResource(R.string.events_device_account) else calendar.account.name.ifBlank { stringResource(R.string.events_calendar_account) },
                swatch = Color(calendar.color or 0xff000000.toInt())) }
            if (details.description.isNotBlank()) PhoneEditorRow(PhoneEventGlyph.NOTES, details.description)
            links.filter { it != join }.forEach { url -> PhoneEditorRow(PhoneEventGlyph.LINK, url, stringResource(R.string.phone_details_open_link), true, { onOpenLink(url) }) }
            PhoneDetailsPeople(details)
            if (details.canRespond == AccessDecision.ALLOWED && !cancelled && onRespond != null) Row(Modifier.fillMaxWidth().padding(start = 64.dp, end = 24.dp, top = 8.dp)) {
                Button(onClick = { onRespond(ref) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.meeting_response_title)) }
            }
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.padding(start = 52.dp).heightIn(min = 48.dp)) { Text(stringResource(if (expanded) R.string.phone_details_less else R.string.phone_details_more)) }
            if (expanded) PhoneDetailsSecondary(details, displayZone)
        }
    }
}

@Composable
private fun PhoneDetailsToolbar(onDismiss: () -> Unit, onEdit: (() -> Unit)? = null, onDelete: (() -> Unit)? = null,
    onDuplicate: (() -> Unit)? = null, ref: EventRef? = null) {
    var menu by remember(ref) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        val close = stringResource(R.string.events_close)
        IconButton(onClick = onDismiss, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = close }) { PhoneEventIcon(PhoneEventGlyph.CLOSE) }
        Spacer(Modifier.weight(1f))
        onEdit?.let { action -> val label = stringResource(R.string.events_editor_edit)
            IconButton(onClick = action, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = label }) { PhoneEventIcon(PhoneEventGlyph.EDIT) } }
        if (onDelete != null || onDuplicate != null) Box {
            val label = stringResource(R.string.phone_details_more_actions)
            IconButton(onClick = { menu = true }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = label }) { PhoneEventIcon(PhoneEventGlyph.MORE) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                onDuplicate?.let { action -> DropdownMenuItem(text = { Text(stringResource(R.string.navigation_duplicate_draft)) }, onClick = { menu = false; action() }) }
                onDelete?.let { action -> DropdownMenuItem(text = { Text(stringResource(R.string.events_editor_delete)) }, onClick = { menu = false; action() }) }
            }
        }
    }
}
