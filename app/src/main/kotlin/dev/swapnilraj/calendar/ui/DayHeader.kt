package dev.swapnilraj.calendar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.swapnilraj.calendar.data.CalEvent
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val HourGutter = 56.dp

@Composable
fun DayHeader(date: LocalDate, today: LocalDate, allDayEvents: List<CalEvent>, zone: ZoneId,
    use24Hour: Boolean, onEventClick: (CalEvent) -> Unit,
    summary: @Composable RowScope.() -> Unit = {}) {
    val locale = LocalLocale.current.platformLocale
    Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(bottom = 8.dp)) {
        Column(Modifier.width(HourGutter).padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(date.format(DateTimeFormatter.ofPattern("EEE", locale)), fontSize = 11.sp,
                color = if (date == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Box(Modifier.size(34.dp).then(if (date == today) Modifier.background(MaterialTheme.colorScheme.primary, CircleShape) else Modifier),
                contentAlignment = Alignment.Center) {
                Text(date.dayOfMonth.toString(), fontSize = 22.sp, fontWeight = FontWeight.Normal,
                    color = if (date == today) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
            }
        }
        Column(Modifier.weight(1f).padding(end = 12.dp, top = 8.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 22.dp), horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically, content = summary)
            allDayEvents.take(3).forEach { event ->
                EventBlock(event, 24f, zone, use24Hour,
                    Modifier.fillMaxWidth().height(24.dp).padding(bottom = 2.dp), onEventClick)
            }
            if (allDayEvents.size > 3) Text("+${allDayEvents.size - 3} more", fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
        }
    }
}
