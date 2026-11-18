package dev.swapnilraj.calendar.ui.widgets

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
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import dev.swapnilraj.calendar.R
import dev.swapnilraj.calendar.android.widgets.*
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.calendars.selectedCalendarIds

private val WidgetDraftSaver = listSaver<WidgetSetupDraft, Any>(save = {
    listOf(it.followApp, it.ids.map { id -> id.value }.toLongArray(), it.privacy.name)
}, restore = { WidgetSetupDraft(it[0] as Boolean, (it[1] as LongArray).map(::CalendarId).toSet(), WidgetPrivacy.valueOf(it[2] as String)) })

/**
 * Metadata/choice presentation only. [widgetId] identifies the local form, never an event or generation.
 * Caller validates current widget context and saved calendar namespace proofs before persisting [onSave].
 * Keeping a checked ID, or seeing it reappear in metadata, never proves namespace adoption here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetSetupSheet(
    widgetId: Int,
    initialChoice: WidgetSetupChoice,
    calendars: CalendarReadState<List<CalendarInfo>>,
    appSelection: CalendarSelection,
    onSave: (WidgetSetupChoice) -> Unit,
    onDismiss: () -> Unit,
    onRetryMetadata: () -> Unit,
    modifier: Modifier = Modifier,
    saving: Boolean = false,
    saveError: String? = null,
    onRequestPermission: (() -> Unit)? = null,
) {
    require(widgetId > 0)
    var draft by rememberSaveable(widgetId, initialChoice, stateSaver = WidgetDraftSaver) { mutableStateOf(WidgetSetupDraft.from(initialChoice)) }
    val ready = (calendars as? CalendarReadState.Ready<List<CalendarInfo>>)?.value
    val unique = remember(ready) { ready.orEmpty().distinctBy { it.id } }
    val groups = remember(unique) { unique.groupBy { it.account }.toSortedMap(compareBy<CalendarAccount> { it.name }.thenBy { it.type }) }
    val missing = draft.ids - unique.mapTo(mutableSetOf()) { it.id }
    val appIds = selectedCalendarIds(unique, appSelection)
    val latestSaving by rememberUpdatedState(saving)
    val density = LocalDensity.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !latestSaving || it != SheetValue.Hidden })
    ModalBottomSheet(onDismissRequest = { if (!saving) onDismiss() }, sheetState = sheetState, modifier = modifier.testTag("widget_setup_sheet")) {
        CompositionLocalProvider(LocalDensity provides density) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.95f)) {
                Text(stringResource(R.string.widget_setup_title), style = MaterialTheme.typography.titleLarge.copy(textDirection = TextDirection.Content),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).semantics { heading() })
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("widget_setup_fields"), contentPadding = PaddingValues(bottom = 24.dp)) {
                    item(key = "mode") {
                        Column(Modifier.selectableGroup()) {
                            SetupRadio(stringResource(R.string.widget_setup_follow_app), stringResource(R.string.widget_setup_follow_app_note), draft.followApp, !saving, "widget_setup_follow") { draft = draft.copy(followApp = true) }
                            SetupRadio(stringResource(R.string.widget_setup_independent), stringResource(R.string.widget_setup_independent_note), !draft.followApp, !saving, "widget_setup_independent") { draft = draft.copy(followApp = false) }
                        }
                    }
                    item(key = "metadata") {
                        val label = when (calendars) {
                            CalendarReadState.Loading -> R.string.widget_setup_loading
                            CalendarReadState.PermissionRequired -> R.string.widget_setup_permission
                            CalendarReadState.LimitExceeded -> R.string.read_limit_widget_metadata
                            CalendarReadState.Unavailable -> R.string.widget_setup_unavailable
                            is CalendarReadState.Ready -> if (unique.isEmpty()) R.string.calendars_no_calendars else null
                        }
                        if (label != null) Text(stringResource(label), style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content), modifier = Modifier.padding(24.dp))
                        if (calendars == CalendarReadState.Loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp))
                        if (calendars == CalendarReadState.PermissionRequired && onRequestPermission != null) TextButton(enabled = !saving, onClick = onRequestPermission, modifier = Modifier.padding(horizontal = 16.dp)) { Text(stringResource(R.string.widget_setup_allow)) }
                        if (calendars in setOf(CalendarReadState.Unavailable, CalendarReadState.PermissionRequired, CalendarReadState.LimitExceeded)) TextButton(enabled = !saving, onClick = onRetryMetadata, modifier = Modifier.padding(horizontal = 16.dp)) { Text(stringResource(R.string.widget_setup_retry)) }
                    }
                    if (draft.followApp) {
                        item(key = "app_preview") {
                            Text(stringResource(R.string.widget_setup_app_preview), style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.Content), modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp).semantics { heading() })
                            if (ready != null && appIds.isEmpty()) Text(stringResource(R.string.widget_setup_app_none), modifier = Modifier.padding(horizontal = 24.dp))
                        }
                        items(unique.filter { it.id in appIds }, key = { "app:${it.id.value}" }) { calendar ->
                            CalendarInformation(calendar, Modifier.padding(horizontal = 24.dp, vertical = 8.dp).testTag("widget_app_calendar_${calendar.id.value}"))
                        }
                        if (appSelection is CalendarSelection.Explicit) items((appSelection.ids - unique.mapTo(mutableSetOf()) { it.id }).sortedBy { it.value }, key = { "app_missing:${it.value}" }) { id ->
                            Text(stringResource(if (ready == null) R.string.widget_setup_saved_calendar else R.string.widget_setup_missing_calendar, id.value), modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                        }
                    } else {
                        item(key = "selection_actions") {
                            Row(Modifier.fillMaxWidth()) {
                                TextButton(enabled = !saving && unique.any(::widgetCalendarSelectable), onClick = { draft = draft.copy(ids = draft.ids + unique.filter(::widgetCalendarSelectable).map { it.id }) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.widget_setup_all_available)) }
                                TextButton(enabled = !saving && draft.ids.isNotEmpty(), onClick = { draft = draft.copy(ids = emptySet()) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.calendars_select_none)) }
                            }
                            if (draft.ids.isEmpty()) Text(stringResource(R.string.widget_setup_no_selection), modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                        }
                        groups.forEach { (account, values) ->
                            item(key = "account:${account.name.length}:${account.name}:${account.type}") {
                                Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp).semantics { heading() }) {
                                    Text(account.name.ifBlank { stringResource(R.string.calendars_device_account) }, style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.Content))
                                    Text(account.type.ifBlank { stringResource(R.string.calendars_unknown_type) }, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content))
                                }
                            }
                            items(values.sortedWith(compareBy<CalendarInfo> { it.displayName }.thenBy { it.id.value }), key = { "calendar:${it.id.value}" }) { calendar ->
                                val checked = calendar.id in draft.ids
                                val enabled = !saving && (checked || widgetCalendarSelectable(calendar))
                                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("widget_calendar_${calendar.id.value}")
                                    .toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = { draft = draft.toggle(calendar.id, it) })
                                    .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked, null, enabled = enabled)
                                    CalendarInformation(calendar, Modifier.weight(1f).padding(start = 12.dp))
                                }
                            }
                        }
                        items(missing.sortedBy { it.value }, key = { "missing:${it.value}" }) { id ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("widget_missing_${id.value}")
                                .toggleable(true, enabled = !saving, role = Role.Checkbox, onValueChange = { draft = draft.toggle(id, false) })
                                .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(true, null, enabled = !saving)
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(stringResource(if (ready == null) R.string.widget_setup_saved_calendar else R.string.widget_setup_missing_calendar, id.value), style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content))
                                    Text(stringResource(if (ready == null) R.string.widget_setup_unchecked_note else R.string.widget_setup_missing_note), style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content))
                                }
                            }
                        }
                    }
                    item(key = "privacy") {
                        HorizontalDivider(Modifier.padding(vertical = 16.dp))
                        Text(stringResource(R.string.widget_setup_privacy), style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content), modifier = Modifier.padding(horizontal = 24.dp).semantics { heading() })
                        Column(Modifier.selectableGroup()) {
                            SetupRadio(stringResource(R.string.widget_setup_busy), stringResource(R.string.widget_setup_busy_note), draft.privacy == WidgetPrivacy.BUSY_ONLY, !saving, "widget_privacy_busy") { draft = draft.copy(privacy = WidgetPrivacy.BUSY_ONLY) }
                            SetupRadio(stringResource(R.string.widget_setup_titles), stringResource(R.string.widget_setup_titles_note), draft.privacy == WidgetPrivacy.EVENT_TITLES, !saving, "widget_privacy_titles") { draft = draft.copy(privacy = WidgetPrivacy.EVENT_TITLES) }
                        }
                    }
                }
                if (saveError != null) Text(saveError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite })
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(enabled = !saving, onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.widget_setup_cancel)) }
                    Button(enabled = !saving && calendars != CalendarReadState.LimitExceeded, onClick = { onSave(draft.choice()) }, modifier = Modifier.weight(1f).testTag("widget_setup_save")) { Text(stringResource(if (saving) R.string.widget_setup_saving else R.string.widget_setup_save)) }
                }
            }
        }
    }
}

@Composable private fun SetupRadio(label: String, detail: String, selected: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag).selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, null, enabled = enabled)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content))
            Text(detail, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content))
        }
    }
}

@Composable private fun CalendarInformation(calendar: CalendarInfo, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(Color(calendar.color or 0xff000000.toInt()), CircleShape))
            Text(calendar.displayName.ifBlank { stringResource(R.string.calendars_unnamed) }, style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content), modifier = Modifier.padding(start = 8.dp))
        }
        Text(calendar.account.name.ifBlank { stringResource(R.string.calendars_device_account) }, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content))
        val accountType = when (calendar.account.type) {
            "com.google" -> stringResource(R.string.calendars_google_type)
            "com.android.exchange", "com.microsoft.exchange" -> stringResource(R.string.calendars_exchange_type)
            "" -> stringResource(R.string.calendars_unknown_type)
            else -> calendar.account.type
        }
        Text(stringResource(R.string.calendars_account_type, accountType), style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content))
        val access = calendar.accessLevel
        if (access == null) Text(stringResource(R.string.widget_setup_unknown_note), style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content))
        else if (access < CalendarContract.Calendars.CAL_ACCESS_FREEBUSY) Text(stringResource(R.string.calendars_no_access), style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content))
        else if (access < CalendarContract.Calendars.CAL_ACCESS_READ) Text(stringResource(R.string.widget_setup_availability_note), style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content))
        else if (access < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) Text(stringResource(R.string.calendars_read_only), style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content))
        if (!calendar.providerVisible) Text(stringResource(R.string.calendars_provider_hidden), style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content))
        if (!calendar.syncEnabled) Text(stringResource(R.string.calendars_sync_off), style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content))
    }
}
