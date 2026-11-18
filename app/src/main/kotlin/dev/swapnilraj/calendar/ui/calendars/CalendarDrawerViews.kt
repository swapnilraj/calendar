package dev.swapnilraj.calendar.ui.calendars

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.swapnilraj.calendar.R

private val drawerViewOrder = listOf(CalendarViewOption.AGENDA, CalendarViewOption.DAY,
    CalendarViewOption.THREE_DAY, CalendarViewOption.WEEK, CalendarViewOption.MONTH)

/** Only implemented, wired views are offered. The host persists selection and dismisses the drawer. */
@Composable
internal fun CalendarDrawerViews(availableViews: List<CalendarViewOption>, currentView: CalendarViewOption?,
    onViewSelected: (CalendarViewOption) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("calendar_drawer_views")) {
        drawerViewOrder.filter { it in availableViews }.forEach { view ->
            NavigationDrawerItem(
                selected = currentView == view,
                onClick = { onViewSelected(view) },
                label = { Text(stringResource(when (view) {
                    CalendarViewOption.AGENDA -> R.string.calendar_navigation_schedule
                    CalendarViewOption.DAY -> R.string.calendars_view_day
                    CalendarViewOption.THREE_DAY -> R.string.calendars_view_three_day
                    CalendarViewOption.WEEK -> R.string.calendars_view_week
                    CalendarViewOption.MONTH -> R.string.calendars_view_month
                })) },
                icon = { CalendarViewIcon(view) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp)
                    .testTag("calendar_drawer_view_${view.name}"),
            )
        }
    }
}

/** Original geometric glyphs; the text label supplies the accessible name. */
@Composable
private fun CalendarViewIcon(view: CalendarViewOption) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(22.dp)) {
        val left = 2.dp.toPx()
        val top = 3.dp.toPx()
        val width = size.width - left * 2
        val height = size.height - top * 2
        val stroke = 1.3.dp.toPx()
        if (view == CalendarViewOption.AGENDA) {
            repeat(3) { row ->
                val y = top + height * (row + 0.5f) / 3
                drawCircle(color, stroke, Offset(left + stroke, y))
                drawLine(color, Offset(left + width * 0.3f, y), Offset(left + width, y), stroke)
            }
        } else {
            drawRoundRect(color, Offset(left, top), Size(width, height), CornerRadius(1.dp.toPx()), style = Stroke(stroke))
            val columns = when (view) { CalendarViewOption.THREE_DAY -> 3; CalendarViewOption.WEEK -> 7
                CalendarViewOption.MONTH -> 3; else -> 1 }
            for (column in 1 until columns) {
                val x = left + width * column / columns
                drawLine(color, Offset(x, top), Offset(x, top + height), stroke)
            }
            if (view == CalendarViewOption.MONTH) for (row in 1..2) {
                val y = top + height * row / 3
                drawLine(color, Offset(left, y), Offset(left + width, y), stroke)
            }
        }
    }
}
