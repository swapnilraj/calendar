package dev.swapnilraj.calendar.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import dev.swapnilraj.calendar.R
import dev.swapnilraj.calendar.data.settings.CalendarSettingChange
import dev.swapnilraj.calendar.ui.calendars.CalendarViewOption
import java.time.ZoneId

/** Presentation only; the caller applies one typed change and acknowledges Saved with a revision. */
@Composable
fun PhoneSettingsScreen(
    options: CalendarSettingsOptions,
    availableViews: List<CalendarViewOption>,
    onChange: (CalendarSettingChange) -> Unit,
    onClose: () -> Unit,
    onChooseDefaultCalendar: () -> Unit,
    onOpenReminders: (() -> Unit)?,
    saving: Boolean = false,
    saveError: String? = null,
    savedChangeRevision: Long = 0,
    deviceZone: ZoneId = ZoneId.systemDefault(),
    defaultCalendarSummary: String? = null,
    modifier: Modifier = Modifier,
) {
    val state = rememberSaveable(saver = PhoneSettingsState.Saver) { PhoneSettingsState() }
    LaunchedEffect(saving, saveError, savedChangeRevision, state.awaitingSave) {
        state.observeResult(saving, saveError, savedChangeRevision)
    }
    val busy = saving || state.awaitingSave
    fun submit(change: CalendarSettingChange) {
        if (saving || state.awaitingSave) return
        state.submit(change, savedChangeRevision)
        onChange(change)
    }
    fun back() {
        if (saving || state.awaitingSave) return
        when {
            state.editor != PhoneSettingEditor.NONE -> state.cancelEditor()
            state.page == PhoneSettingsPage.GENERAL -> state.page = PhoneSettingsPage.HOME
            else -> onClose()
        }
    }
    BackHandler(onBack = ::back)
    val title = if (state.editor != PhoneSettingEditor.NONE) phoneSettingsEditorTitle(state.editor)
        else stringResource(if (state.page == PhoneSettingsPage.HOME) R.string.settings_title else R.string.phone_settings_general)
    Surface(modifier.fillMaxSize().testTag("phone_settings_screen").semantics { paneTitle = title }) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            PhoneSettingsHeader(title, !busy, ::back)
            if (busy) {
                val savingLabel = stringResource(R.string.phone_settings_saving)
                LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = savingLabel })
            }
            if (saveError != null && state.editor == PhoneSettingEditor.NONE) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                    Text(saveError, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.ContentOrLtr),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    state.lastChange?.let { change ->
                        TextButton(enabled = !busy, onClick = { submit(change) }) { Text(stringResource(R.string.phone_settings_retry)) }
                    }
                }
            }
            if (state.editor != PhoneSettingEditor.NONE) {
                PhoneSettingsEditor(state, availableViews, busy,
                    saveError = saveError.takeIf { state.submittedGeneration == state.generation },
                    onSubmit = ::submit, onCancel = state::cancelEditor, modifier = Modifier.weight(1f))
            } else if (state.page == PhoneSettingsPage.HOME) {
                LazyColumn(Modifier.fillMaxSize().testTag("phone_settings_home_list"), contentPadding = PaddingValues(bottom = 24.dp)) {
                    item {
                        PhoneSettingsRow(stringResource(R.string.phone_settings_general), tag = "phone_settings_open_general", enabled = !busy,
                            onClick = { state.page = PhoneSettingsPage.GENERAL })
                    }
                    item {
                        PhoneSettingsRow(stringResource(R.string.phone_settings_default_calendar), defaultCalendarSummary,
                            "phone_settings_default_calendar", !busy, onChooseDefaultCalendar)
                    }
                    onOpenReminders?.let { open -> item {
                        PhoneSettingsRow(stringResource(R.string.phone_settings_reminders), tag = "phone_settings_reminders", enabled = !busy, onClick = open)
                    } }
                }
            } else {
                PhoneSettingsGeneral(options, availableViews, deviceZone, busy,
                    onOpen = { state.open(it, options, deviceZone) }, onChange = ::submit)
            }
        }
    }
}
