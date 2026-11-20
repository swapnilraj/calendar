package dev.swapnilraj.calendar.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import dev.swapnilraj.calendar.R
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
import java.time.Duration

fun eventTextColor(color: Color): Color {
    // Choose the larger contrast ratio; the best of black/white is always >= 4.5:1.
    val luminance = color.luminance()
    return if ((luminance + 0.05) / 0.05 >= 1.05 / (luminance + 0.05)) Color.Black else Color.White
}

@Composable
fun EventBlock(event: CalEvent, heightDp: Float, zone: ZoneId, use24Hour: Boolean,
    modifier: Modifier = Modifier, onClick: (CalEvent) -> Unit, timedSegment: TimedEvent? = null,
    onOpenEditor: (() -> Unit)? = null, resizeHandle: Boolean = false) {
    val color = Color(event.color or 0xff000000.toInt())
    val foreground = if (event.declined) MaterialTheme.colorScheme.onSurface else eventTextColor(color)
    val locale = LocalLocale.current.platformLocale
    val untitled = stringResource(R.string.calendar_no_title)
    val title = event.title.ifBlank { untitled }
    val range = eventTimeLabel(event, zone, use24Hour, locale)
    val time = if (event.allDay) "${stringResource(R.string.calendar_all_day)}, $range" else range
    val affected = timedSegment?.clockChangeAffected == true && timedSegment.segment != null
    val segmentTime = if (affected) eventSegmentTimeLabel(timedSegment, use24Hour, locale) else null
    val part = if (timedSegment != null && timedSegment.partCount > 1)
        stringResource(R.string.dst_part, timedSegment.partIndex + 1, timedSegment.partCount) else null
    val duration = if (affected) stringResource(R.string.dst_duration,
        realDurationLabel(timedSegment.segment.eventDuration)) else null
    val previousDay = if (timedSegment?.partIndex == 0 && timedSegment.segment != null && timedSegment.segment.start > event.start)
        stringResource(R.string.dst_previous_day) else null
    val nextDay = if (timedSegment != null && timedSegment.partIndex == timedSegment.partCount - 1 &&
        timedSegment.segment != null && timedSegment.segment.end < event.end) stringResource(R.string.dst_next_day) else null
    val presentation = listOfNotNull(time, segmentTime, duration, part, previousDay, nextDay).joinToString(", ")
    val declined = stringResource(R.string.calendar_declined)
    val open = stringResource(R.string.calendar_open_event)
    val titleHeight = minimumEventHeight().value
    val fontScale = LocalDensity.current.fontScale
    val timeFits = heightDp >= titleHeight + (if (affected) 14f else 29f) * fontScale
    val displayedTitle = if (affected && !timeFits)
        stringResource(R.string.dst_offset_title, timedSegment.segment.offsetLabel, title) else title
    val editorLabel = if (onOpenEditor != null) stringResource(R.string.timed_grid_edit_editor) else ""
    Surface(modifier.semantics {
        contentDescription = "$title, $presentation${if (event.declined) ", $declined" else ""}"
        if (onOpenEditor != null) {
            customActions = listOf(CustomAccessibilityAction(editorLabel) { onOpenEditor(); true })
            onLongClick(editorLabel) { onOpenEditor(); true }
        }
    }
        .clickable(onClickLabel = open) { onClick(event) },
        shape = RoundedCornerShape(6.dp),
        color = if (event.declined) Color.Transparent else color,
        border = if (event.declined) BorderStroke(1.dp, color) else null,
        contentColor = foreground) {
        Box(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
            Text(displayedTitle, fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium,
                maxLines = if (heightDp >= titleHeight * 2 - 8f && !(affected && timeFits)) 2 else 1,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (event.declined) TextDecoration.LineThrough else null)
            if (timeFits && !event.allDay) {
                val visibleTime = if (!affected) time else if (part == null) segmentTime!!
                    else stringResource(R.string.dst_offset_part, timedSegment.segment.offsetLabel, part)
                Text(visibleTime, fontSize = 10.sp,
                    lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (resizeHandle) Canvas(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp)) {
            val half = minOf(8.dp.toPx(), size.width / 3)
            drawLine(foreground, Offset(size.width / 2 - half, size.height / 2), Offset(size.width / 2 + half, size.height / 2), 2.dp.toPx(), StrokeCap.Round)
        }
        }
    }
}

@Composable
private fun realDurationLabel(duration: Duration): String {
    if (duration.seconds == 0L && !duration.isZero) return stringResource(R.string.dst_less_than_second)
    val hours = duration.seconds / 3600
    val minutes = duration.seconds / 60 % 60
    val seconds = duration.seconds % 60
    val parts = mutableListOf<String>()
    if (hours > 0) parts += pluralStringResource(R.plurals.dst_hours, hours.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), hours)
    if (minutes > 0) parts += pluralStringResource(R.plurals.dst_minutes, minutes.toInt(), minutes)
    if (seconds > 0 || parts.isEmpty()) parts += pluralStringResource(R.plurals.dst_seconds, seconds.toInt(), seconds)
    return parts.joinToString(", ")
}
