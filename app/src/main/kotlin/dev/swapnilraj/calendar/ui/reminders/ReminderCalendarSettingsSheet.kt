package dev.swapnilraj.calendar.ui.reminders

import android.provider.CalendarContract
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import dev.swapnilraj.calendar.R
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.calendar.CalendarReminderSource
import dev.swapnilraj.calendar.data.reminders.*

internal data class ReminderDefaultsInput(val timed: String, val allDay: String, val method: ReminderDefaultMethod) {
    companion object {
        fun from(value: ReminderDefaults) = ReminderDefaultsInput(value.timedMinutesBefore?.toString().orEmpty(), value.allDayMinutesBefore?.toString().orEmpty(), value.defaultMethod)
    }
}
internal data class ReminderSettingsDraft(
    val expected: SavedReminderSettings,
    val enabled: Boolean,
    val pins: Map<CalendarId, ReminderCalendarIdentity>,
    val edits: Map<CalendarId, ReminderDefaultsInput> = emptyMap(),
    val cleared: Set<CalendarId> = emptySet(),
) {
    fun baseline(id: CalendarId) = if (id in cleared) ReminderDefaults() else expected.explicitDefaults[id] ?: ReminderDefaults()
    fun input(id: CalendarId) = edits[id] ?: ReminderDefaultsInput.from(baseline(id))
    fun remove(id: CalendarId) = copy(pins = pins - id, edits = edits - id, cleared = cleared + id)
    fun confirm(id: CalendarId, identity: ReminderCalendarIdentity) = copy(pins = pins + (id to identity), edits = edits - id, cleared = cleared + id)
    fun edit(id: CalendarId, input: ReminderDefaultsInput) = copy(edits = edits + (id to input))
    private fun valid(raw: String, original: Int?) = raw.trim().let { it.isEmpty() ||
        (it == original?.toString() && original != -1) || it.toIntOrNull()?.let { value -> value >= 0 } == true }
    fun invalid(id: CalendarId): Boolean {
        val value = input(id)
        val original = baseline(id)
        return !valid(value.timed, original.timedMinutesBefore) || !valid(value.allDay, original.allDayMinutesBefore)
    }
    fun updated(): SavedReminderSettings? {
        if (edits.keys.any { it in pins && invalid(it) }) return null
        val defaults = expected.explicitDefaults.filterKeys { it in pins && it !in cleared }.toMutableMap()
        edits.filterKeys { it in pins }.forEach { (id, value) ->
            defaults[id] = ReminderDefaults(value.timed.trim().takeIf(String::isNotEmpty)?.toInt(), value.allDay.trim().takeIf(String::isNotEmpty)?.toInt(), value.method)
        }
        return SavedReminderSettings(enabled, pins.toMap(), defaults.toMap())
    }
    companion object {
        fun from(value: SavedReminderSettings): ReminderSettingsDraft {
            val stable = value.copy(expectedCalendarIdentities = value.expectedCalendarIdentities.toMap(), explicitDefaults = value.explicitDefaults.toMap())
            return ReminderSettingsDraft(stable, stable.enabled, stable.expectedCalendarIdentities.toMap())
        }
    }
}

private fun pinStrings(pins: Map<CalendarId, ReminderCalendarIdentity>) = ArrayList(pins.entries.flatMap { listOf(it.key.value.toString(), it.value.fingerprint, it.value.providerUidFingerprint.orEmpty()) })
private fun readPins(values: List<String>) = values.chunked(3).associate { CalendarId(it[0].toLong()) to ReminderCalendarIdentity(it[1], it[2].ifEmpty { null }) }
private fun inputStrings(values: Map<CalendarId, ReminderDefaultsInput>) = ArrayList(values.entries.flatMap { listOf(it.key.value.toString(), it.value.timed, it.value.allDay, it.value.method.name) })
private fun readInputs(values: List<String>) = values.chunked(4).associate { CalendarId(it[0].toLong()) to ReminderDefaultsInput(it[1], it[2], ReminderDefaultMethod.valueOf(it[3])) }
private val ReminderDraftSaver = Saver<ReminderSettingsDraft?, Any>(save = { value ->
    if (value == null) arrayListOf(false) else arrayListOf(true, value.expected.enabled, pinStrings(value.expected.expectedCalendarIdentities),
        inputStrings(value.expected.explicitDefaults.mapValues { ReminderDefaultsInput.from(it.value) }), value.enabled, pinStrings(value.pins), inputStrings(value.edits), value.cleared.map { it.value }.toLongArray())
}, restore = { saved ->
    val values = saved as List<*>
    if (values[0] == false) null else {
        @Suppress("UNCHECKED_CAST")
        val baseInputs = readInputs(values[3] as List<String>)
        @Suppress("UNCHECKED_CAST")
        val base = SavedReminderSettings(values[1] as Boolean, readPins(values[2] as List<String>), baseInputs.mapValues { (_, value) -> ReminderDefaults(value.timed.toIntOrNull(), value.allDay.toIntOrNull(), value.method) })
        @Suppress("UNCHECKED_CAST")
        ReminderSettingsDraft(base, values[4] as Boolean, readPins(values[5] as List<String>), readInputs(values[6] as List<String>), (values[7] as LongArray).map(::CalendarId).toSet())
    }
})

internal fun reminderIdentityVerified(expected: ReminderCalendarIdentity, observed: ReminderCalendarIdentity?): Boolean =
    observed != null && !expected.isKnownDifferentFrom(observed) &&
        (expected.providerUidFingerprint == null || expected.providerUidFingerprint == observed.providerUidFingerprint)

/**
 * Local editable choices only; [onSave] carries the frozen compare-and-save base, never performs IO.
 * Host reloads/reopens (or rekeys) this sheet only after explicit [onReloadSettings]. Metadata changes
 * never rebase a draft, strengthen a pin, or adopt a replacement calendar. Permission/runtime work is hosted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderCalendarSettingsSheet(
    preferences: ReminderPreferenceState,
    calendars: CalendarReadState<List<CalendarInfo>>,
    onSave: (expected: SavedReminderSettings, updated: SavedReminderSettings) -> Unit,
    onDismiss: () -> Unit,
    onPermissions: () -> Unit,
    onReloadSettings: () -> Unit,
    onRetryCalendars: () -> Unit,
    saving: Boolean = false,
    saveResult: ReminderPreferenceSave? = null,
    modifier: Modifier = Modifier,
) {
    var draft by rememberSaveable(stateSaver = ReminderDraftSaver) { mutableStateOf<ReminderSettingsDraft?>(null) }
    val current = (preferences as? ReminderPreferenceState.Ready)?.settings
    LaunchedEffect(current) { if (draft == null && current != null) draft = ReminderSettingsDraft.from(current) }
    fun updateDraft(change: (ReminderSettingsDraft) -> ReminderSettingsDraft) { draft = draft?.let(change) }
    val value = draft
    val savedSuccessfully = saveResult is ReminderPreferenceSave.Saved && value != null && saveResult.settings == value.updated()
    val stale = saveResult == ReminderPreferenceSave.Changed || (value != null && current != null && current != value.expected && !savedSuccessfully)
    val available = (calendars as? CalendarReadState.Ready<List<CalendarInfo>>)?.value.orEmpty().distinctBy { it.id }
    val byId = remember(available) { available.associateBy { it.id } }
    val identities = remember(available) { available.associate { it.id to CalendarReminderSource.calendarIdentity(it) } }
    var expanded by rememberSaveable { mutableStateOf(listOf<Long>()) }
    var confirmation by remember { mutableStateOf<Pair<CalendarId, ReminderCalendarIdentity>?>(null) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    val density = LocalDensity.current
    val latestSaving by rememberUpdatedState(saving)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !latestSaving || it != SheetValue.Hidden })
    ModalBottomSheet(onDismissRequest = { if (!saving) onDismiss() }, sheetState = sheetState, modifier = modifier.testTag("reminder_calendar_sheet")) {
        CompositionLocalProvider(LocalDensity provides density) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.95f)) {
                Text(stringResource(R.string.reminder_calendars_title), style = MaterialTheme.typography.titleLarge.copy(textDirection = TextDirection.Content), modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).semantics { heading() })
                LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("reminder_calendar_fields"), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item(key = "guidance") {
                        ReminderText(stringResource(R.string.reminder_calendars_independent))
                        ReminderText(stringResource(R.string.reminder_calendars_duplicates))
                        TextButton(enabled = !saving, onClick = onPermissions) { Text(stringResource(R.string.reminder_calendars_permissions)) }
                    }
                    if (value == null) item(key = "preferences_unavailable") {
                        ReminderText(stringResource(if (preferences == ReminderPreferenceState.Loading) R.string.reminder_calendars_loading else R.string.reminder_calendars_preferences_error))
                        if (preferences is ReminderPreferenceState.Unavailable) TextButton(enabled = !saving, onClick = onReloadSettings) { Text(stringResource(R.string.reminder_calendars_reload)) }
                    }
                    if (value != null) {
                        if (current == null) item(key = "preferences_read_lost") {
                            ReminderText(stringResource(if (preferences == ReminderPreferenceState.Loading) R.string.reminder_calendars_loading else R.string.reminder_calendars_preferences_error))
                            if (preferences is ReminderPreferenceState.Unavailable) TextButton(enabled = !saving, onClick = onReloadSettings) { Text(stringResource(R.string.reminder_calendars_reload_discard)) }
                        }
                        item(key = "enabled") {
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("reminder_calendars_enabled").toggleable(value.enabled, enabled = !saving, role = Role.Switch, onValueChange = { checked -> updateDraft { it.copy(enabled = checked) } }), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text(stringResource(R.string.reminder_calendars_enabled), style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content), modifier = Modifier.weight(1f).padding(end = 12.dp))
                                Switch(value.enabled, null, enabled = !saving)
                            }
                        }
                        item(key = "metadata") {
                            val label = when (calendars) {
                                CalendarReadState.Loading -> R.string.reminder_calendars_metadata_loading
                                CalendarReadState.PermissionRequired -> R.string.reminder_calendars_metadata_permission
                                CalendarReadState.LimitExceeded -> R.string.reminder_calendars_metadata_limit
                                CalendarReadState.Unavailable -> R.string.reminder_calendars_metadata_unavailable
                                is CalendarReadState.Ready -> if (available.isEmpty()) R.string.reminder_calendars_metadata_empty else null
                            }
                            if (label != null) ReminderText(stringResource(label))
                            if (calendars == CalendarReadState.PermissionRequired) TextButton(enabled = !saving, onClick = onPermissions) { Text(stringResource(R.string.reminder_calendars_permissions)) }
                            if (calendars == CalendarReadState.Unavailable || calendars == CalendarReadState.LimitExceeded) TextButton(enabled = !saving, onClick = onRetryCalendars) { Text(stringResource(R.string.reminder_calendars_retry_calendars)) }
                            if (value.pins.isEmpty()) ReminderText(stringResource(R.string.reminder_calendars_none))
                        }
                        val ids = (byId.keys + value.pins.keys).sortedWith(compareBy<CalendarId> { byId[it]?.account?.name.orEmpty() }.thenBy { byId[it]?.displayName.orEmpty() }.thenBy { it.value })
                        ids.forEach { id ->
                            val calendar = byId[id]
                            val observed = identities[id]
                            val pin = value.pins[id]
                            val readable = calendar?.accessLevel?.let { it >= CalendarContract.Calendars.CAL_ACCESS_READ } == true
                            val verified = pin != null && reminderIdentityVerified(pin, observed)
                            item(key = "calendar:${id.value}") {
                                Column {
                                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("reminder_calendar_${id.value}")
                                        .toggleable(pin != null, enabled = !saving && (pin != null || readable && observed != null), role = Role.Checkbox, onValueChange = { checked ->
                                            if (!checked) updateDraft { it.remove(id) }
                                            else if (observed != null) {
                                                val prior = value.expected.expectedCalendarIdentities[id]
                                                if (prior != null && !reminderIdentityVerified(prior, observed)) confirmation = id to observed
                                                else updateDraft { it.copy(pins = it.pins + (id to (prior ?: observed))) }
                                            }
                                        }).padding(vertical = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                        Checkbox(pin != null, null, enabled = !saving && (pin != null || readable && observed != null))
                                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                            Text(if (calendar == null) stringResource(R.string.reminder_calendars_saved_calendar, id.value) else calendar.displayName.takeIf(String::isNotBlank) ?: stringResource(R.string.calendars_unnamed), style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content))
                                            calendar?.let {
                                                ReminderText(reminderAccountLine(it))
                                                it.owner?.takeIf { owner -> owner.isNotBlank() && owner != it.account.name }?.let { owner -> ReminderText(stringResource(R.string.reminder_calendars_owner, owner)) }
                                                if (!it.providerVisible) ReminderText(stringResource(R.string.reminder_calendars_hidden))
                                                if (!it.syncEnabled) ReminderText(stringResource(R.string.reminder_calendars_sync_off))
                                            }
                                            if (!readable) ReminderText(stringResource(R.string.reminder_calendars_read_unconfirmed))
                                            else if (observed == null) ReminderText(stringResource(R.string.reminder_calendars_identity_unavailable))
                                        }
                                    }
                                    if (pin != null && !verified) {
                                        ReminderText(stringResource(if (observed != null && pin.isKnownDifferentFrom(observed)) R.string.reminder_calendars_identity_changed else R.string.reminder_calendars_identity_unverified))
                                        if (observed != null && readable) TextButton(enabled = !saving, onClick = { confirmation = id to observed }, modifier = Modifier.testTag("reminder_reconfirm_${id.value}")) { Text(stringResource(R.string.reminder_calendars_reconfirm)) }
                                    }
                                    if (verified && readable) TextButton(enabled = !saving, onClick = { expanded = if (id.value in expanded) expanded - id.value else expanded + id.value }, modifier = Modifier.testTag("reminder_defaults_${id.value}")) { Text(stringResource(R.string.reminder_calendars_defaults)) }
                                }
                            }
                            if (verified && readable && id.value in expanded) {
                                val input = value.input(id)
                                item(key = "default_explanation:${id.value}") { ReminderText(stringResource(R.string.reminder_calendars_defaults_explanation)) }
                                item(key = "timed:${id.value}") {
                                    OffsetField(input.timed, { raw -> updateDraft { it.edit(id, it.input(id).copy(timed = raw)) } }, stringResource(R.string.reminder_calendars_timed_offset), !saving, attempted && value.invalid(id), "reminder_timed_${id.value}")
                                }
                                item(key = "all_day:${id.value}") {
                                    AllDayDefaultField(input.allDay, { raw -> updateDraft { it.edit(id, it.input(id).copy(allDay = raw)) } }, !saving, attempted && value.invalid(id), id)
                                }
                                item(key = "method:${id.value}") {
                                    ReminderMethodField(input.method, !saving) { method -> updateDraft { it.edit(id, it.input(id).copy(method = method)) } }
                                }
                            }
                        }
                    }
                    item(key = "delivery_note") { ReminderText(stringResource(R.string.reminder_calendars_not_delivery)) }
                }
                if (stale) {
                    ReminderText(stringResource(R.string.reminder_calendars_changed), Modifier.padding(horizontal = 24.dp).semantics { liveRegion = LiveRegionMode.Polite })
                    TextButton(enabled = !saving, onClick = onReloadSettings, modifier = Modifier.padding(horizontal = 16.dp).testTag("reminder_settings_reload")) { Text(stringResource(R.string.reminder_calendars_reload_discard)) }
                } else if (savedSuccessfully) ReminderText(stringResource(R.string.reminder_calendars_saved_local), Modifier.padding(horizontal = 24.dp))
                else if (saveResult == ReminderPreferenceSave.Invalid || saveResult == ReminderPreferenceSave.Unavailable) ReminderText(stringResource(R.string.reminder_calendars_save_error), Modifier.padding(horizontal = 24.dp).semantics { liveRegion = LiveRegionMode.Polite })
                if (attempted && value?.updated() == null && value != null) ReminderText(stringResource(R.string.reminder_calendars_offset_error), Modifier.padding(horizontal = 24.dp))
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(enabled = !saving, onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.reminder_calendars_cancel)) }
                    Button(enabled = !saving && value != null && current != null && !stale && !savedSuccessfully, onClick = { attempted = true; val pending = draft; pending?.updated()?.let { onSave(pending.expected, it) } }, modifier = Modifier.weight(1f).testTag("reminder_calendars_save")) { Text(stringResource(if (saving) R.string.reminder_calendars_saving else R.string.reminder_calendars_save)) }
                }
            }
        }
    }
    confirmation?.let { (id, target) ->
        val observed = identities[id]
        val readable = byId[id]?.accessLevel?.let { it >= CalendarContract.Calendars.CAL_ACCESS_READ } == true
        val unchanged = observed == target
        AlertDialog(onDismissRequest = { if (!saving) confirmation = null }, title = { Text(stringResource(R.string.reminder_calendars_reconfirm_title)) },
            text = { Column {
                byId[id]?.let { Text(it.displayName); ReminderText(reminderAccountLine(it)); it.owner?.takeIf(String::isNotBlank)?.let { owner -> ReminderText(stringResource(R.string.reminder_calendars_owner, owner)) } }
                Text(stringResource(if (unchanged) R.string.reminder_calendars_reconfirm_warning else R.string.reminder_calendars_reconfirm_changed))
            } }, confirmButton = {
                TextButton(enabled = !saving && unchanged && readable, modifier = Modifier.testTag("reminder_identity_confirm"), onClick = {
                    if (identities[id] == target && draft != null) draft = draft!!.confirm(id, target)
                    confirmation = null
                }) { Text(stringResource(R.string.reminder_calendars_reconfirm_confirm)) }
            }, dismissButton = { TextButton(enabled = !saving, modifier = Modifier.testTag("reminder_identity_cancel"), onClick = { confirmation = null }) { Text(stringResource(R.string.reminder_calendars_cancel)) } })
    }
}

@Composable private fun reminderAccountLine(calendar: CalendarInfo): String {
    val type = when (calendar.account.type) {
        "com.google" -> stringResource(R.string.calendars_google_type)
        "com.android.exchange", "com.microsoft.exchange" -> stringResource(R.string.calendars_exchange_type)
        "" -> stringResource(R.string.calendars_unknown_type)
        else -> calendar.account.type
    }
    return "${calendar.account.name.ifBlank { stringResource(R.string.reminder_calendars_account_unavailable) }} · $type"
}
@Composable private fun ReminderText(text: String, modifier: Modifier = Modifier) { Text(text, modifier, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content)) }
@Composable private fun OffsetField(value: String, onChange: (String) -> Unit, label: String, enabled: Boolean, error: Boolean, tag: String) {
    OutlinedTextField(value, onChange, modifier = Modifier.fillMaxWidth().testTag(tag), enabled = enabled, label = { Text(label) }, singleLine = true,
        isError = error, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
        supportingText = { ReminderText(stringResource(if (error) R.string.reminder_calendars_offset_error else R.string.reminder_calendars_unresolved_note)) })
}
@Composable private fun AllDayDefaultField(raw: String, onChange: (String) -> Unit, enabled: Boolean, error: Boolean, id: CalendarId) {
    var expanded by remember { mutableStateOf(false) }
    var custom by remember(id, raw) { mutableStateOf(raw !in listOf("", "0", "900", "2340", "9540")) }
    val values = listOf("" to R.string.reminder_calendars_unresolved, "0" to R.string.reminder_calendars_midnight,
        "900" to R.string.reminder_calendars_previous_day, "2340" to R.string.reminder_calendars_two_days, "9540" to R.string.reminder_calendars_week)
    val label = values.firstOrNull { it.first == raw }?.second ?: R.string.reminder_calendars_custom
    Column {
        Text(stringResource(R.string.reminder_calendars_all_day_offset), style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content))
        Box {
            OutlinedButton(enabled = enabled, onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().testTag("reminder_all_day_${id.value}")) { Text(stringResource(label)) }
            DropdownMenu(expanded && enabled, onDismissRequest = { expanded = false }) {
                values.forEach { (value, text) -> DropdownMenuItem(text = { Text(stringResource(text)) }, onClick = { expanded = false; custom = false; onChange(value) }) }
                DropdownMenuItem(text = { Text(stringResource(R.string.reminder_calendars_custom)) }, onClick = { expanded = false; custom = true })
            }
        }
        if (custom) OffsetField(raw, onChange, stringResource(R.string.reminder_calendars_custom_offset), enabled, error, "reminder_all_day_custom_${id.value}")
        ReminderText(stringResource(R.string.reminder_calendars_all_day_zone))
    }
}
@Composable private fun ReminderMethodField(value: ReminderDefaultMethod, enabled: Boolean, onChange: (ReminderDefaultMethod) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    fun resource(method: ReminderDefaultMethod) = when (method) {
        ReminderDefaultMethod.UNRESOLVED -> R.string.reminder_calendars_method_unresolved
        ReminderDefaultMethod.LOCAL_ALERT -> R.string.reminder_calendars_method_local
        ReminderDefaultMethod.NON_LOCAL -> R.string.reminder_calendars_method_nonlocal
    }
    Column {
        Text(stringResource(R.string.reminder_calendars_method), style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content))
        Box {
            OutlinedButton(enabled = enabled, onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().testTag("reminder_default_method")) { Text(stringResource(resource(value))) }
            DropdownMenu(expanded && enabled, onDismissRequest = { expanded = false }) {
                ReminderDefaultMethod.entries.forEach { method -> DropdownMenuItem(text = { Text(stringResource(resource(method))) }, onClick = { expanded = false; onChange(method) }) }
            }
        }
    }
}
