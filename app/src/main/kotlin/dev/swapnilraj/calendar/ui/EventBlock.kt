package dev.swapnilraj.calendar.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.swapnilraj.calendar.data.CalEvent
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun eventTextColor(color: Color): Color {
    // Choose the larger contrast ratio; the best of black/white is always >= 4.5:1.
    val luminance = color.luminance()
    return if ((luminance + 0.05) / 0.05 >= 1.05 / (luminance + 0.05)) Color.Black else Color.White
}

@Composable
fun EventBlock(event: CalEvent, heightDp: Float, zone: ZoneId, use24Hour: Boolean,
    modifier: Modifier = Modifier, onClick: (CalEvent) -> Unit) {
    val color = Color(event.color or 0xff000000.toInt())
    val foreground = if (event.declined) MaterialTheme.colorScheme.onSurface else eventTextColor(color)
    val formatter = DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "h:mm a", Locale.getDefault())
    val title = event.title.ifBlank { "(No title)" }
    val time = "${event.start.atZone(zone).format(formatter)} – ${event.end.atZone(zone).format(formatter)}"
    Surface(modifier.semantics { contentDescription = "$title, $time${if (event.declined) ", declined" else ""}" }
        .clickable(onClickLabel = "Open event") { onClick(event) },
        shape = RoundedCornerShape(6.dp),
        color = if (event.declined) Color.Transparent else color,
        border = if (event.declined) BorderStroke(1.dp, color) else null,
        contentColor = foreground) {
        Column(Modifier.padding(horizontal = 6.dp, vertical = if (heightDp >= 32) 4.dp else 1.dp)) {
            Text(title, fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium,
                maxLines = if (heightDp >= 64) 2 else 1, overflow = TextOverflow.Ellipsis,
                textDecoration = if (event.declined) TextDecoration.LineThrough else null)
            if (heightDp >= 46 && !event.allDay) {
                Text(time, fontSize = 10.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
