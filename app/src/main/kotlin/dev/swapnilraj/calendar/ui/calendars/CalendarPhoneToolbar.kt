package dev.swapnilraj.calendar.ui.calendars

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import dev.swapnilraj.calendar.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Shared phone chrome only. The host owns navigation, modal state, insets and edit cancellation. */
@Composable
fun CalendarPhoneToolbar(
    selectedDate: LocalDate,
    today: LocalDate,
    monthExpanded: Boolean,
    onOpenDrawer: () -> Unit,
    onToggleMonth: () -> Unit,
    onToday: () -> Unit,
    onSearch: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val locale = LocalLocale.current.platformLocale
    val todaySize = (22 * LocalDensity.current.fontScale).dp.coerceIn(26.dp, 40.dp)
    val month = selectedDate.format(DateTimeFormatter.ofPattern("MMMM yyyy", locale))
    val monthLabel = stringResource(if (monthExpanded) R.string.calendar_phone_collapse_month else R.string.calendar_phone_expand_month, month)
    val menuLabel = stringResource(R.string.calendar_phone_menu)
    val expandedLabel = stringResource(if (monthExpanded) R.string.calendar_phone_expanded else R.string.calendar_phone_collapsed)
    val todayLabel = stringResource(R.string.calendar_phone_today,
        today.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)))
    Surface(modifier) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("calendar_phone_toolbar"), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onOpenDrawer, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .semantics { contentDescription = menuLabel }) { PhoneGlyph(PhoneGlyph.MENU) }
            TextButton(onClick = onToggleMonth, modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                .semantics { contentDescription = monthLabel; stateDescription = expandedLabel }
                .testTag("calendar_phone_month"), contentPadding = PaddingValues(horizontal = 4.dp)) {
                Text(month, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleLarge.copy(textDirection = TextDirection.Content), color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(4.dp))
                PhoneChevron(expanded = monthExpanded)
            }
            onSearch?.let { search ->
                val label = stringResource(R.string.calendar_phone_search)
                IconButton(onClick = search, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .semantics { contentDescription = label }) { PhoneGlyph(PhoneGlyph.SEARCH) }
            }
            IconButton(onClick = onToday, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .semantics { contentDescription = todayLabel }.testTag("calendar_phone_today")) {
                Box(Modifier.size(todaySize), contentAlignment = Alignment.Center) {
                    PhoneGlyph(PhoneGlyph.TODAY, Modifier.matchParentSize())
                    Text(today.dayOfMonth.toString(), style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 5.dp).clearAndSetSemantics {})
                }
            }
        }
    }
}

private enum class PhoneGlyph { MENU, SEARCH, TODAY }

@Composable
private fun PhoneGlyph(glyph: PhoneGlyph, modifier: Modifier = Modifier.size(24.dp)) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier) {
        val stroke = 1.6.dp.toPx()
        when (glyph) {
            PhoneGlyph.MENU -> for (row in 0..2) {
                val y = size.height * (0.25f + row * 0.25f)
                drawLine(color, Offset(size.width * 0.15f, y), Offset(size.width * 0.85f, y), stroke)
            }
            PhoneGlyph.SEARCH -> {
                drawCircle(color, size.width * 0.27f, Offset(size.width * 0.42f, size.height * 0.42f), style = Stroke(stroke))
                drawLine(color, Offset(size.width * 0.62f, size.height * 0.62f), Offset(size.width * 0.88f, size.height * 0.88f), stroke)
            }
            PhoneGlyph.TODAY -> {
                drawRoundRect(color, Offset(size.width * 0.1f, size.height * 0.17f), Size(size.width * 0.8f, size.height * 0.76f),
                    CornerRadius(2.dp.toPx()), style = Stroke(stroke))
                drawLine(color, Offset(size.width * 0.1f, size.height * 0.36f), Offset(size.width * 0.9f, size.height * 0.36f), stroke)
                for (x in listOf(0.3f, 0.7f)) drawLine(color, Offset(size.width * x, size.height * 0.08f), Offset(size.width * x, size.height * 0.24f), stroke)
            }
        }
    }
}

@Composable
private fun PhoneChevron(expanded: Boolean) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(16.dp)) {
        val y = if (expanded) 0.35f else 0.65f
        val edge = 1 - y
        drawLine(color, Offset(size.width * 0.2f, size.height * edge), Offset(size.width * 0.5f, size.height * y), 1.5.dp.toPx())
        drawLine(color, Offset(size.width * 0.5f, size.height * y), Offset(size.width * 0.8f, size.height * edge), 1.5.dp.toPx())
    }
}
