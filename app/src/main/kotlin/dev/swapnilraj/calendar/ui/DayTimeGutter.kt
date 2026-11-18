package dev.swapnilraj.calendar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.swapnilraj.calendar.R
import dev.swapnilraj.calendar.core.ClockChangeKind
import dev.swapnilraj.calendar.core.DayGeometry
import java.time.*
import java.time.format.DateTimeFormatter

/** Shared wall-clock labels. Width is supplied by the caller; this component never owns scrolling. */
@Composable
fun DayTimeGutter(hourHeight: Float, use24Hour: Boolean, modifier: Modifier = Modifier) {
    val locale = LocalLocale.current.platformLocale
    val fontScale = LocalDensity.current.fontScale
    val formatter = remember(locale, use24Hour) { DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "h a", locale) }
    Box(modifier.height((hourHeight * 24).dp)) {
        for (hour in 1..23) Text(LocalTime.of(hour, 0).format(formatter), color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp, textAlign = TextAlign.End, maxLines = 1, softWrap = false,
            modifier = Modifier.offset(y = (hourHeight * hour - 8 * fontScale).dp).fillMaxWidth().padding(end = 8.dp))
    }
}

/** Each date owns its offset changes; a common multi-date gutter cannot express them. */
@Composable
internal fun DayClockChangeSummary(date: LocalDate, zone: ZoneId, use24Hour: Boolean) {
    val geometry = remember(date, zone) { DayGeometry(date, zone) }
    val locale = LocalLocale.current.platformLocale
    val formatter = remember(locale, use24Hour) { DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "h:mm a", locale) }
    geometry.clockChanges.forEach { band ->
        fun clock(minute: Double) = if (minute >= 1440) "24:00" else LocalTime.MIDNIGHT.plusSeconds((minute * 60).toLong()).format(formatter)
        val details = "\u202a${clock(band.startMinute)} – ${clock(band.endMinute)} · ${band.offsetBeforeLabel} → ${band.offsetAfterLabel}\u202c"
        val label = stringResource(if (band.kind == ClockChangeKind.GAP) R.string.dst_clock_forward else R.string.dst_clock_back, details)
        Text(details, style = MaterialTheme.typography.labelSmall, modifier = Modifier.fillMaxWidth().padding(4.dp)
            .clearAndSetSemantics { contentDescription = label })
    }
}
