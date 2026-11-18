package dev.swapnilraj.calendar.ui.grid

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.swapnilraj.calendar.R
import dev.swapnilraj.calendar.core.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Fixed height while enabled, so feedback never shifts the pointer's viewport during a drag. */
@Composable
internal fun TimedGridFeedback(date: LocalDate, zone: ZoneId, use24Hour: Boolean, interactions: TimedGridInteractions,
    preview: TimedGridPreview?, onCancel: () -> Unit) {
    val locale = LocalLocale.current.platformLocale
    val dates = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    val clock = DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "h:mm a", locale)
    fun time(value: Instant): String = value.atZone(zone).let { "${it.toLocalDate().format(dates)} ${it.format(clock)} UTC${if (it.offset == ZoneOffset.UTC) "+00:00" else it.offset.id}" }
    val current = preview?.takeIf { it.contextKey == interactions.contextKey }
    val result = current?.result
    val message = when {
        current == null -> stringResource(if (interactions.creationEnabled && interactions.editingEnabled) R.string.timed_grid_hint
            else if (interactions.creationEnabled) R.string.timed_grid_create_hint else R.string.timed_grid_edit_hint)
        current.phase == TimedGridGesturePhase.CHECKING -> stringResource(R.string.timed_grid_checking)
        current.phase == TimedGridGesturePhase.REJECTED -> stringResource(R.string.timed_grid_unavailable)
        current.phase == TimedGridGesturePhase.ORIGINAL -> stringResource(R.string.timed_grid_original)
        result is GridPreviewResult.Ready -> {
            val elapsed = gridElapsedAmount(result.time.duration, locale)
            stringResource(if (elapsed.seconds) R.string.timed_grid_ready_seconds else R.string.timed_grid_ready,
                "\u202a${time(result.time.start)} – ${time(result.time.end)}\u202c", elapsed.value)
        }
        result is GridPreviewResult.NeedsReview -> {
            val target = result.snappedTarget
            val wall = LocalTime.MIDNIGHT.plusSeconds((target.minute * 60).toLong()).format(clock)
            stringResource(if (result.reason == GridReviewReason.CLOCK_GAP) R.string.timed_grid_gap else R.string.timed_grid_overlap,
                "\u202a${target.date.format(dates)} $wall\u202c")
        }
        result is GridPreviewResult.Invalid -> stringResource(R.string.timed_grid_invalid)
        else -> stringResource(R.string.timed_grid_original)
    }
    // Keep the moving preview readable in the fixed strip; accessibility retains the complete
    // date/offset/elapsed-duration review below, including both sides of a clock transition.
    fun compactTime(value: Instant): String = value.atZone(zone).let {
        if (it.toLocalDate() == date) it.format(clock) else "${it.toLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale))} ${it.format(clock)}"
    }
    fun compactRange(value: EventTime.Timed): String {
        val start = value.start.atZone(zone)
        val end = value.end.atZone(zone)
        // A shared multi-day strip names a same-date target once, leaving room for the actual time
        // and duration at large fonts. Complete dates/offsets remain in the accessibility message.
        return if (start.toLocalDate() == end.toLocalDate() && start.toLocalDate() != date)
            "${start.toLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale))} ${start.format(clock)} – ${end.format(clock)}"
        else "${compactTime(value.start)} – ${compactTime(value.end)}"
    }
    val visualMessage = when (result) {
        is GridPreviewResult.Ready -> {
            val elapsed = gridElapsedAmount(result.time.duration, locale)
            stringResource(if (elapsed.seconds) R.string.timed_grid_ready_compact_seconds else R.string.timed_grid_ready_compact,
                "\u202a${compactRange(result.time)}\u202c", elapsed.value)
        }
        is GridPreviewResult.NeedsReview -> {
            val target = result.snappedTarget
            val wall = LocalTime.MIDNIGHT.plusSeconds((target.minute * 60).toLong()).format(clock)
            val label = if (target.date == date) wall else "${target.date.format(dates)} $wall"
            stringResource(if (result.reason == GridReviewReason.CLOCK_GAP) R.string.timed_grid_gap_compact else R.string.timed_grid_overlap_compact, "\u202a$label\u202c")
        }
        else -> message
    }
    Row(Modifier.fillMaxWidth().height((32f * LocalDensity.current.fontScale + 16f).dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(visualMessage, modifier = Modifier.weight(1f).semantics { contentDescription = message; liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp, textDirection = TextDirection.Content), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (current != null) TextButton(onClick = onCancel) { Text(stringResource(R.string.timed_grid_cancel)) }
        else if (interactions.creationEnabled) TextButton(onClick = { interactions.onCreateInEditor?.invoke(date) }) { Text(stringResource(R.string.timed_grid_create_editor)) }
    }
}
