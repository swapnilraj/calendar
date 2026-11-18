package dev.swapnilraj.calendar.ui.events

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.swapnilraj.calendar.R
import dev.swapnilraj.calendar.core.*
import java.time.*

internal enum class PhoneEditorPanel { CALENDAR, START_DATE, END_DATE, START_TIME, END_TIME, ZONE, LOCATION, DESCRIPTION, ALERTS, REPEAT, PRIVACY, AVAILABILITY }

@Composable
internal fun PhoneEditorPanelContent(panel: PhoneEditorPanel, view: PhoneEventEditorPresentation, use24Hour: Boolean, onDismiss: () -> Unit) {
    val fields = view.fields; val controller = view.controller
    when (panel) {
        PhoneEditorPanel.START_DATE, PhoneEditorPanel.END_DATE -> PhoneEditorDatePicker(
            if (panel == PhoneEditorPanel.START_DATE) fields.startDate else fields.endDate,
            stringResource(if (panel == PhoneEditorPanel.START_DATE) R.string.phone_event_start_date else R.string.phone_event_end_date),
            view.canEdit(EditableField.TIME), onDismiss) { value -> controller.change { if (panel == PhoneEditorPanel.START_DATE) it.copy(startDate = value) else it.copy(endDate = value) } }
        PhoneEditorPanel.START_TIME, PhoneEditorPanel.END_TIME -> PhoneEditorTimePicker(
            if (panel == PhoneEditorPanel.START_TIME) fields.startTime else fields.endTime,
            stringResource(if (panel == PhoneEditorPanel.START_TIME) R.string.phone_event_start_time else R.string.phone_event_end_time), use24Hour,
            view.canEdit(EditableField.TIME), onDismiss) { value -> controller.change { if (panel == PhoneEditorPanel.START_TIME) it.copy(startTime = value) else it.copy(endTime = value) } }
        PhoneEditorPanel.CALENDAR -> PhoneEditorPanelFrame(stringResource(R.string.phone_event_choose_calendar), onDismiss) {
            LazyColumn(Modifier.fillMaxSize()) { items(view.calendars.filter { it.capabilities.createEvents == AccessDecision.ALLOWED }, key = { "${it.id}:${it.identity}" }) { calendar ->
                val selected = fields.targetIdentity == calendar.identity && fields.calendarId == calendar.id.value.toString()
                Row(Modifier.fillMaxWidth().selectable(selected = selected, enabled = view.canChooseCalendar && calendar.account.name.isNotBlank() && calendar.account.type.isNotBlank(), role = Role.RadioButton) { controller.selectCalendar(calendar); onDismiss() }.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.size(16.dp).background(Color(calendar.color or 0xff000000.toInt()), CircleShape))
                    Column(Modifier.weight(1f)) { Text(calendar.displayName, style = MaterialTheme.typography.bodyLarge); Text(calendar.account.name, style = MaterialTheme.typography.bodyMedium) }
                    RadioButton(selected, onClick = null, enabled = view.canChooseCalendar)
                }
            } }
        }
        PhoneEditorPanel.ZONE -> PhoneEditorZonePanel(fields.zone, view.canEdit(EditableField.ZONE), onDismiss) { value -> controller.change { it.copy(zone = value) } }
        PhoneEditorPanel.LOCATION, PhoneEditorPanel.DESCRIPTION -> {
            val location = panel == PhoneEditorPanel.LOCATION
            PhoneEditorPanelFrame(stringResource(if (location) R.string.events_location else R.string.events_notes), onDismiss) {
                OutlinedTextField(if (location) fields.location else fields.description, { value -> controller.change { if (location) it.copy(location = value) else it.copy(description = value) } },
                    label = { Text(stringResource(if (location) R.string.events_location else R.string.events_notes)) }, enabled = view.canEdit(if (location) EditableField.LOCATION else EditableField.DESCRIPTION),
                    modifier = Modifier.fillMaxWidth().padding(24.dp), minLines = if (location) 1 else 5)
            }
        }
        PhoneEditorPanel.REPEAT -> PhoneEditorPanelFrame(stringResource(R.string.recurrence_editor_repeat), onDismiss) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
                RecurrenceEditorControls(fields.recurrence, fields.startDate, !view.blocked) { value -> controller.change { it.copy(recurrence = value) } }
            }
        }
        PhoneEditorPanel.ALERTS -> PhoneEditorAlertPanel(fields.alerts, view.canEdit(EditableField.ALERTS), onDismiss) { value -> controller.change { it.copy(alerts = value) } }
        PhoneEditorPanel.PRIVACY, PhoneEditorPanel.AVAILABILITY -> {
            val privacy = panel == PhoneEditorPanel.PRIVACY
            val enabled = view.canEdit(if (privacy) EditableField.PRIVACY else EditableField.AVAILABILITY)
            val value = if (privacy) fields.privacy else fields.availability
            PhoneEditorPanelFrame(stringResource(if (privacy) R.string.events_visibility else R.string.events_availability), onDismiss) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    (if (privacy) listOf("", "0", "1", "2", "3") else listOf("", "0", "1", "2")).forEach { option ->
                        Row(Modifier.fillMaxWidth().selectable(selected = option == value, enabled = enabled, role = Role.RadioButton) {
                            controller.change { if (privacy) it.copy(privacy = option) else it.copy(availability = option) }; onDismiss()
                        }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(option == value, onClick = null, enabled = enabled)
                            Text(phoneMetadataLabel(option, privacy), modifier = Modifier.padding(start = 16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PhoneEditorPanelFrame(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val closeLabel = stringResource(R.string.phone_event_close)
                    IconButton(onClick = onDismiss, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = closeLabel }) { PhoneEventIcon(PhoneEventGlyph.CLOSE) }
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.phone_event_done)) }
                }
                Box(Modifier.weight(1f)) { content() }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneEditorDatePicker(raw: String, title: String, enabled: Boolean, onDismiss: () -> Unit, onChange: (String) -> Unit) {
    var manual by remember { mutableStateOf(false) }
    val date = runCatching { LocalDate.parse(raw) }.getOrNull()?.takeIf { it.year in 1..9999 }
    val picker = key(raw) { rememberDatePickerState(initialSelectedDateMillis = date?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(), yearRange = 1..9999) }
    DatePickerDialog(onDismissRequest = onDismiss,
        confirmButton = { TextButton(enabled = manual || enabled && picker.selectedDateMillis != null, onClick = {
            if (!manual && enabled) picker.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC).toLocalDate().toString()) }; onDismiss()
        }) { Text(stringResource(R.string.phone_event_done)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.events_editor_cancel)) } }) {
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
            TextButton(onClick = { manual = !manual }, modifier = Modifier.padding(horizontal = 12.dp)) { Text(stringResource(if (manual) R.string.phone_event_use_picker else R.string.phone_event_enter_text)) }
            if (manual) OutlinedTextField(raw, onChange, enabled = enabled, label = { Text(stringResource(R.string.phone_event_date_input)) }, modifier = Modifier.fillMaxWidth().padding(24.dp))
            else DatePicker(picker)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneEditorTimePicker(raw: String, title: String, use24Hour: Boolean, enabled: Boolean, onDismiss: () -> Unit, onChange: (String) -> Unit) {
    var manual by remember { mutableStateOf(false) }
    val time = runCatching { LocalTime.parse(raw) }.getOrNull()
    val picker = key(raw) { rememberTimePickerState(initialHour = time?.hour ?: 0, initialMinute = time?.minute ?: 0, is24Hour = use24Hour) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Column {
        TextButton(onClick = { manual = !manual }) { Text(stringResource(if (manual) R.string.phone_event_use_picker else R.string.phone_event_enter_text)) }
        if (manual) OutlinedTextField(raw, onChange, enabled = enabled, label = { Text(stringResource(R.string.phone_event_time_input)) }) else TimePicker(picker)
    } }, confirmButton = { TextButton(enabled = enabled, onClick = { if (!manual) onChange(PhoneEventEditorText.acceptedMinuteTime(raw, picker.hour, picker.minute)); onDismiss() }) { Text(stringResource(R.string.phone_event_done)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.events_editor_cancel)) } })
}

@Composable
private fun PhoneEditorZonePanel(raw: String, enabled: Boolean, onDismiss: () -> Unit, onChange: (String) -> Unit) {
    var query by remember { mutableStateOf("") }; var manual by remember { mutableStateOf(false) }
    val choices = remember { (ZoneId.getAvailableZoneIds() + setOf("UTC", "Z")).sorted() }
    PhoneEditorPanelFrame(stringResource(R.string.phone_event_zone), onDismiss) {
        Column(Modifier.fillMaxSize()) {
            TextButton(onClick = { manual = !manual }, modifier = Modifier.padding(horizontal = 16.dp)) { Text(stringResource(if (manual) R.string.phone_event_use_picker else R.string.phone_event_enter_text)) }
            if (manual) OutlinedTextField(raw, onChange, enabled = enabled, label = { Text(stringResource(R.string.phone_event_zone)) }, modifier = Modifier.fillMaxWidth().padding(24.dp))
            else {
                OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.phone_event_search_zone)) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp))
                LazyColumn { items(choices.filter { it.contains(query.trim(), ignoreCase = true) }) { zone ->
                    Row(Modifier.fillMaxWidth().selectable(selected = raw == zone, enabled = enabled, role = Role.RadioButton) { onChange(zone); onDismiss() }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(raw == zone, onClick = null, enabled = enabled); Text(zone.replace('_', ' '), modifier = Modifier.padding(start = 16.dp))
                    }
                } }
            }
        }
    }
}

@Composable
private fun PhoneEditorAlertPanel(raw: String, enabled: Boolean, onDismiss: () -> Unit, onChange: (String) -> Unit) {
    var custom by remember { mutableStateOf(PhoneEventEditorText.alerts(raw) == null) }
    val values = PhoneEventEditorText.alerts(raw)
    PhoneEditorPanelFrame(stringResource(R.string.phone_event_notifications), onDismiss) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (values != null) {
                if (values.isEmpty()) Text(stringResource(R.string.phone_event_no_notification))
                values.forEach { minutes ->
                    val label = phoneAlertLabel(minutes)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, modifier = Modifier.weight(1f))
                        val removeLabel = stringResource(R.string.phone_event_remove_notification, label)
                        TextButton(onClick = { onChange(values.filterNot { it == minutes }.joinToString(",")) }, enabled = enabled, modifier = Modifier.semantics { contentDescription = removeLabel }) { Text(stringResource(R.string.phone_event_remove)) }
                    }
                }
                Text(stringResource(R.string.phone_event_add_notification), style = MaterialTheme.typography.titleSmall)
                listOf(0,5,10,15,30,60,1440).filterNot(values::contains).forEach { minutes ->
                    TextButton(onClick = { onChange((values + minutes).joinToString(",")) }, enabled = enabled) { Text(phoneAlertLabel(minutes)) }
                }
            }
            TextButton(onClick = { custom = !custom }) { Text(stringResource(R.string.phone_event_custom)) }
            if (custom) {
                OutlinedTextField(raw, onChange, enabled = enabled, label = { Text(stringResource(R.string.phone_event_custom_notifications)) }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.phone_event_custom_notification_help), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
