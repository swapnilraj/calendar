package dev.swapnilraj.calendar.ui.events

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.swapnilraj.calendar.R

/** Optional injected host only. No existing phone screen or app route calls this yet. */
@Composable
fun EditorConflictAdvisory(controller: EditorConflictAdvisoryController, input: EditorConflictAdvisoryInput,
    modifier: Modifier = Modifier) {
    val observation by controller.state.collectAsState()
    // Read the current input mask in this frame, before the effect can cancel old collection.
    val shown=controller.presentation(input,observation)
    LaunchedEffect(controller,input) { controller.update(input) }
    DisposableEffect(controller) { onDispose { controller.close() } }
    PhoneConflictAdvisory(shown,modifier)
}

/** Passive counts/coverage presentation. There is deliberately no save or permission callback. */
@Composable
fun PhoneConflictAdvisory(state: EditorConflictAdvisoryState, modifier: Modifier = Modifier) {
    if (state.status in setOf(EditorConflictAdvisoryStatus.HIDDEN,EditorConflictAdvisoryStatus.INACTIVE_DRAFT)) return
    Column(modifier.fillMaxWidth().semantics { liveRegion=LiveRegionMode.Polite }.padding(vertical=8.dp),
        verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.conflict_advisory_title),style=MaterialTheme.typography.titleSmall)
        when(state.status) {
            EditorConflictAdvisoryStatus.ASSESSED -> {
                if(state.knownConflicts>0) Text(pluralStringResource(R.plurals.conflict_advisory_known,state.knownConflicts,state.knownConflicts),style=MaterialTheme.typography.bodyMedium)
                if(state.possibleConflicts>0) Text(pluralStringResource(R.plurals.conflict_advisory_possible,state.possibleConflicts,state.possibleConflicts),style=MaterialTheme.typography.bodyMedium)
                if(state.canReportNoConflict) Text(stringResource(R.string.conflict_advisory_clear),style=MaterialTheme.typography.bodyMedium)
                if(!state.complete) Text(stringResource(R.string.conflict_advisory_partial),style=MaterialTheme.typography.bodyMedium)
            }
            else -> Text(stringResource(when(state.status) {
                EditorConflictAdvisoryStatus.NO_CALENDARS_SELECTED -> R.string.conflict_advisory_no_selection
                EditorConflictAdvisoryStatus.SELECTION_UNAVAILABLE -> R.string.conflict_advisory_selection_unavailable
                EditorConflictAdvisoryStatus.UNFINISHED_TIME -> R.string.conflict_advisory_unfinished
                EditorConflictAdvisoryStatus.PERMISSION_REQUIRED -> R.string.conflict_advisory_permission
                EditorConflictAdvisoryStatus.LIMIT_EXCEEDED -> R.string.conflict_advisory_limit
                EditorConflictAdvisoryStatus.UNSUPPORTED_TIME -> R.string.conflict_advisory_unsupported
                EditorConflictAdvisoryStatus.UNAVAILABLE -> R.string.conflict_advisory_unavailable
                else -> R.string.conflict_advisory_loading
            }),style=MaterialTheme.typography.bodyMedium)
        }
        if(state.firstOccurrenceOnly) Text(stringResource(R.string.conflict_advisory_first_only),style=MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.conflict_advisory_local_only),style=MaterialTheme.typography.bodySmall,
            color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
