package dev.swapnilraj.calendar.ui.calendars

import android.provider.CalendarContract
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextDirection
import dev.swapnilraj.calendar.R
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.eventTextColor
import java.util.Locale

/**
 * Ready metadata only: the caller owns loading/permission/provider-error presentation and persistence.
 * Potential creation rights do not replace the editor's runtime permission and fresh access checks.
 */
@Composable
fun CalendarDrawerSheet(
    calendars: List<CalendarInfo>,
    selection: CalendarSelection,
    defaultCalendar: CalendarId?,
    onSelectionChanged: (CalendarSelection) -> Unit,
    onDefaultCalendarChanged: (CalendarId?) -> Unit,
    onDismiss: () -> Unit,
    footer: @Composable ColumnScope.() -> Unit = {},
) {
    val unique = remember(calendars) { calendars.distinctBy { it.id } }
    val selected = selectedCalendarIds(unique, selection)
    val groups = remember(unique) {
        unique.groupBy { it.account }.toSortedMap(compareBy<CalendarAccount> { it.name.lowercase(Locale.ROOT) }
            .thenBy { it.type.lowercase(Locale.ROOT) }.thenBy { it.name }.thenBy { it.type })
            .mapValues { (_, calendars) -> calendars.sortedWith(compareBy<CalendarInfo> { it.displayName.lowercase(Locale.ROOT) }.thenBy { it.id.value }) }
    }
    val writable = unique.filter { it.capabilities.createEvents == AccessDecision.ALLOWED }
    val currentDefault = unique.find { it.id == defaultCalendar }
    ModalDrawerSheet(Modifier.fillMaxHeight()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.calendars_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.calendars_close)) }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("calendar_drawer_list"), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Text(stringResource(R.string.calendars_visibility_note), style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    TextButton(enabled = unique.any(::canDisplay), onClick = {
                        onSelectionChanged(CalendarSelection.Explicit(unique.filter(::canDisplay).mapTo(linkedSetOf()) { it.id }))
                    }) { Text(stringResource(R.string.calendars_select_all)) }
                    TextButton(enabled = selection !is CalendarSelection.Explicit || selection.ids.isNotEmpty(),
                        onClick = { onSelectionChanged(CalendarSelection.Explicit(emptySet())) }) { Text(stringResource(R.string.calendars_select_none)) }
                }
            }
            if (unique.isEmpty()) item {
                DrawerMessage(stringResource(R.string.calendars_no_calendars), stringResource(R.string.calendars_no_calendars_detail))
            } else if (unique.none { it.id in selected }) item {
                DrawerMessage(stringResource(R.string.calendars_no_selection), stringResource(R.string.calendars_no_selection_detail))
            }
            groups.forEach { (account, calendars) ->
                item(key = "account:${account.name.length}:${account.name}:${account.type}") {
                    Column(Modifier.fillMaxWidth().testTag("calendar_account_${account.name.length}:${account.name}:${account.type}")
                        .padding(horizontal = 16.dp, vertical = 12.dp).semantics { heading() }) {
                        Text(account.name.ifBlank { stringResource(R.string.calendars_device_account) }, style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.calendars_account_type, accountTypeLabel(account.type)), style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content))
                    }
                }
                items(calendars, key = { "calendar:${it.id.value}" }) { calendar ->
                    CalendarVisibilityRow(calendar, calendar.id in selected) { checked ->
                        onSelectionChanged(toggledCalendarSelection(unique, selection, calendar.id, checked))
                    }
                }
            }
            item {
                HorizontalDivider(Modifier.padding(vertical = 16.dp))
                Text(stringResource(R.string.calendars_creation_default), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() })
                Text(stringResource(R.string.calendars_creation_note), style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                if (defaultCalendar != null && currentDefault == null) Text(stringResource(R.string.calendars_default_missing),
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                else if (currentDefault != null && currentDefault.capabilities.createEvents == AccessDecision.UNKNOWN)
                    Text(stringResource(R.string.calendars_default_unknown), modifier = Modifier.padding(16.dp))
                else if (currentDefault != null && currentDefault.capabilities.createEvents != AccessDecision.ALLOWED)
                    Text(stringResource(R.string.calendars_default_read_only), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                if (writable.isEmpty()) Text(stringResource(R.string.calendars_no_writable), modifier = Modifier.padding(16.dp))
            }
            item {
                Column(Modifier.fillMaxWidth().selectableGroup()) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("calendar_default_none")
                        .selectable(defaultCalendar == null, role = Role.RadioButton, onClick = { onDefaultCalendarChanged(null) })
                        .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = defaultCalendar == null, onClick = null)
                        Text(stringResource(R.string.calendars_ask_each_time), Modifier.padding(start = 12.dp))
                    }
                    unique.sortedWith(compareBy<CalendarInfo> { it.account.name }.thenBy { it.account.type }.thenBy { it.displayName }.thenBy { it.id.value })
                        .forEach { calendar ->
                          key(calendar.id.value) {
                            val eligible = calendar.capabilities.createEvents == AccessDecision.ALLOWED
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("calendar_default_${calendar.id.value}")
                                .selectable(defaultCalendar == calendar.id, enabled = eligible, role = Role.RadioButton,
                                    onClick = { onDefaultCalendarChanged(calendar.id) })
                                .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = defaultCalendar == calendar.id, onClick = null, enabled = eligible)
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(calendar.displayName.ifBlank { stringResource(R.string.calendars_unnamed) }, style = MaterialTheme.typography.bodyLarge)
                                    Text(calendar.account.name.ifBlank { stringResource(R.string.calendars_device_account) }, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content))
                                    Text(accountTypeLabel(calendar.account.type), style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content))
                                    if (!eligible) Text(stringResource(if (calendar.capabilities.createEvents == AccessDecision.UNKNOWN)
                                        R.string.calendars_creation_unknown else R.string.calendars_not_writable), style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content))
                                    val coverage = calendarCoverage(calendar)
                                    if (coverage.isNotEmpty()) Text(coverage, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content))
                                }
                                CalendarColorDot(calendar)
                            }
                          }
                        }
                }
            }
        }
        footer()
    }
}

@Composable
private fun CalendarVisibilityRow(calendar: CalendarInfo, checked: Boolean, onCheckedChanged: (Boolean) -> Unit) {
    val enabled = canDisplay(calendar) || checked // A revoked calendar may still be explicitly deselected.
    val color = Color(calendar.color or 0xff000000.toInt())
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("calendar_toggle_${calendar.id.value}")
        .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChanged)
        .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled,
            colors = CheckboxDefaults.colors(checkedColor = color, checkmarkColor = eventTextColor(color)))
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
            Text(calendar.displayName.ifBlank { stringResource(R.string.calendars_unnamed) }, style = MaterialTheme.typography.bodyLarge)
            val context = calendarContext(calendar)
            if (context.isNotEmpty()) Text(context, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content))
        }
        CalendarColorDot(calendar)
    }
}

@Composable
private fun calendarContext(calendar: CalendarInfo): String {
    val rights = when {
        calendar.accessLevel == CalendarContract.Calendars.CAL_ACCESS_NONE -> stringResource(R.string.calendars_no_access)
        calendar.accessLevel == CalendarContract.Calendars.CAL_ACCESS_FREEBUSY -> stringResource(R.string.calendars_free_busy)
        calendar.capabilities.createEvents == AccessDecision.DENIED && calendar.capabilities.modifyEvents == AccessDecision.DENIED -> stringResource(R.string.calendars_read_only)
        calendar.capabilities.createEvents == AccessDecision.UNKNOWN -> stringResource(R.string.calendars_access_unknown)
        else -> ""
    }
    return listOf(rights,
        if (calendar.capabilities.respondToInvitations == AccessDecision.ALLOWED && calendar.capabilities.createEvents != AccessDecision.ALLOWED)
            stringResource(R.string.calendars_can_respond) else "",
        calendarCoverage(calendar))
        .filter { it.isNotEmpty() }.joinToString(" · ")
}

@Composable
private fun calendarCoverage(calendar: CalendarInfo): String = listOf(
    if (!calendar.providerVisible) stringResource(R.string.calendars_provider_hidden) else "",
    if (!calendar.syncEnabled) stringResource(R.string.calendars_sync_off) else "")
    .filter { it.isNotEmpty() }.joinToString(" · ")

@Composable
private fun accountTypeLabel(type: String): String = when (type) {
    "com.google" -> stringResource(R.string.calendars_google_type)
    "com.android.exchange", "com.microsoft.exchange" -> stringResource(R.string.calendars_exchange_type)
    "" -> stringResource(R.string.calendars_unknown_type)
    else -> type
}

@Composable
private fun DrawerMessage(title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(detail, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content))
    }
}

@Composable
private fun CalendarColorDot(calendar: CalendarInfo) {
    Box(Modifier.size(12.dp).background(Color(calendar.color or 0xff000000.toInt()), CircleShape))
}
