package dev.swapnilraj.calendar.android.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.swapnilraj.calendar.R
import dev.swapnilraj.calendar.core.CalendarReadState
import dev.swapnilraj.calendar.ui.events.PhoneEventDetails

/** Neutral fallback; Root may replace seed/edit review with its guarded native editor surface. */
@Composable
fun BasicCalendarEntryScreen(state: CalendarEntryState, actions: CalendarEntryActions) {
    if (state is CalendarEntryState.Details && state.resolved.proposedEdit == null) {
        PhoneEventDetails(CalendarReadState.Ready(state.details), actions.dismiss, actions.retry, actions.openLink,
            displayZone = state.displayZone)
        return
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val result = (state as? CalendarEntryState.Result)?.resolution
            val label = when (result) {
                null -> if (state is CalendarEntryState.Details) R.string.calendar_entry_edit_review else R.string.calendar_entry_loading
                is CalendarEntryResolution.Date -> R.string.calendar_entry_date_review
                is CalendarEntryResolution.CreateSeed -> R.string.calendar_entry_create_review
                is CalendarEntryResolution.NeedsOccurrence -> R.string.calendar_entry_occurrence
                CalendarEntryResolution.PermissionRequired -> R.string.events_permission_required
                CalendarEntryResolution.LimitExceeded -> R.string.calendar_entry_limit
                CalendarEntryResolution.Stale -> R.string.calendar_entry_stale
                CalendarEntryResolution.Unsupported -> R.string.calendar_entry_unsupported
                is CalendarEntryResolution.Invalid -> R.string.calendar_entry_invalid
                else -> R.string.navigation_unavailable
            }
            Text(stringResource(label), style = MaterialTheme.typography.titleLarge)
            if (result is CalendarEntryResolution.Date) Text(result.date.toString())
            val seed = (result as? CalendarEntryResolution.CreateSeed)?.seed ?: (state as? CalendarEntryState.Details)?.resolved?.proposedEdit
            if (state is CalendarEntryState.Details) Text(state.details.occurrence.title)
            if (seed != null) {
                Text(stringResource(R.string.calendar_entry_inputs_note))
                listOfNotNull(seed.title, seed.location, seed.description, seed.occurrence.begin?.toString(),
                    seed.occurrence.end?.toString(), seed.timeZone?.id, seed.recurrenceRule,
                    seed.availability?.toString(), seed.accessLevel?.toString()).forEach { Text(it) }
                seed.guests.forEach { Text(it) }
                if (seed.occurrence.begin == null) Text(stringResource(R.string.calendar_entry_missing_date))
            }
            if (result == CalendarEntryResolution.PermissionRequired) Button(onClick = actions.requestCalendarRead) {
                Text(stringResource(R.string.widget_setup_allow))
            }
            actions.review?.let { review -> Button(onClick = review) { Text(stringResource(R.string.calendar_entry_continue)) } }
            TextButton(onClick = actions.retry) { Text(stringResource(R.string.widget_setup_retry)) }
            TextButton(onClick = actions.dismiss) { Text(stringResource(R.string.widget_setup_cancel)) }
        }
    }
}
