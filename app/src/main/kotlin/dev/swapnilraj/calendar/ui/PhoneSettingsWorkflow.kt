package dev.swapnilraj.calendar.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.swapnilraj.calendar.R
import dev.swapnilraj.calendar.data.CalendarPreferenceState
import dev.swapnilraj.calendar.data.settings.CalendarSettingChange
import dev.swapnilraj.calendar.data.settings.CalendarSettingSave
import dev.swapnilraj.calendar.ui.calendars.CalendarViewOption
import dev.swapnilraj.calendar.ui.settings.*
import kotlinx.coroutines.launch

/** Keep the editor mounted through preference refreshes; only explicit actions can save. */
@Composable
internal fun PhoneSettingsWorkflow(
    preferences: CalendarPreferenceState,
    availableViews: List<CalendarViewOption>,
    save: suspend (CalendarSettingChange) -> CalendarSettingSave,
    onRetryPreferences: () -> Unit,
    onClose: () -> Unit,
    onChooseDefaultCalendar: () -> Unit,
    onOpenReminders: (() -> Unit)?,
    defaultCalendarSummary: String? = null,
) {
    val scope = rememberCoroutineScope()
    var lastReady by remember { mutableStateOf<CalendarSettingsOptions?>(null) }
    val ready = (preferences as? CalendarPreferenceState.Ready)?.value?.options
    SideEffect { if (ready != null) lastReady = ready }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by rememberSaveable { mutableStateOf(false) }
    var savedRevision by rememberSaveable { mutableLongStateOf(0L) }
    val currentSave by rememberUpdatedState(save)
    val options = ready ?: lastReady
    Dialog(onDismissRequest = { if (ready != null && !saving) onClose() }, properties = DialogProperties(
        usePlatformDefaultWidth = false, dismissOnClickOutside = false, decorFitsSystemWindows = false,
    )) {
        Box(Modifier.fillMaxSize()) {
            if (options != null) {
                Box(if (ready != null) Modifier else Modifier.clearAndSetSemantics {}) {
                    PhoneSettingsScreen(options, availableViews,
                        onChange = { change ->
                            if (ready != null && !saving) {
                                // Lock synchronously, before dispatching IO, so two taps cannot race.
                                saving = true
                                saveFailed = false
                                scope.launch {
                                    try {
                                        when (currentSave(change)) {
                                            CalendarSettingSave.Saved -> savedRevision++
                                            CalendarSettingSave.Invalid, CalendarSettingSave.Unavailable -> saveFailed = true
                                        }
                                    } finally { saving = false }
                                }
                            }
                        }, onClose = onClose, onChooseDefaultCalendar = onChooseDefaultCalendar,
                        onOpenReminders = onOpenReminders, saving = saving,
                        saveError = if (saveFailed) stringResource(R.string.navigation_preferences_unavailable) else null,
                        savedChangeRevision = savedRevision, defaultCalendarSummary = defaultCalendarSummary)
                }
            }
            if (ready == null) Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp)) {
                    if (preferences == CalendarPreferenceState.Loading) {
                        Text(stringResource(R.string.navigation_preferences_loading))
                        CircularProgressIndicator()
                    } else {
                        Text(stringResource(R.string.navigation_preferences_load_failed))
                        TextButton(onClick = onRetryPreferences) { Text(stringResource(R.string.events_retry)) }
                    }
                    TextButton(onClick = onClose) { Text(stringResource(R.string.events_close)) }
                }
            }
        }
        // Registered after the composed editor's handler: masked Back must not cancel raw input.
        BackHandler(enabled = ready == null) { }
    }
}
