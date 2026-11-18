package dev.swapnilraj.calendar.ui.calendars

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import dev.swapnilraj.calendar.R

/** UI navigation vocabulary, independent of persisted settings or repository contracts. */
enum class CalendarViewOption { DAY, MONTH, THREE_DAY, WEEK, AGENDA }

/** Only [availableViews] are rendered. The caller supplies views with real implementations. */
@Composable
fun CalendarViewChooser(availableViews: List<CalendarViewOption>, selectedView: CalendarViewOption,
    onViewSelected: (CalendarViewOption) -> Unit, modifier: Modifier = Modifier) {
    val available = availableViews.distinct()
    var expanded by remember { mutableStateOf(false) }
    val current = selectedView.takeIf { it in available }
    val label = when {
        available.isEmpty() -> stringResource(R.string.calendars_no_views)
        current == null -> stringResource(R.string.calendars_choose_view)
        else -> viewLabel(current)
    }
    val description = stringResource(R.string.calendars_change_view, label)
    LaunchedEffect(available) { if (available.isEmpty()) expanded = false }
    Box(modifier) {
        TextButton(enabled = available.isNotEmpty(), onClick = { expanded = true }, modifier = Modifier.semantics { contentDescription = description }) {
            Text(label)
        }
        DropdownMenu(expanded = expanded && available.isNotEmpty(), onDismissRequest = { expanded = false }) {
            available.forEach { view ->
                DropdownMenuItem(text = { Text(viewLabel(view)) }, modifier = Modifier.semantics { selected = view == current },
                    onClick = { expanded = false; onViewSelected(view) })
            }
        }
    }
}

@Composable
private fun viewLabel(view: CalendarViewOption): String = stringResource(when (view) {
    CalendarViewOption.DAY -> R.string.calendars_view_day
    CalendarViewOption.MONTH -> R.string.calendars_view_month
    CalendarViewOption.THREE_DAY -> R.string.calendars_view_three_day
    CalendarViewOption.WEEK -> R.string.calendars_view_week
    CalendarViewOption.AGENDA -> R.string.calendars_view_agenda
})
