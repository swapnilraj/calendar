package dev.swapnilraj.calendar.ui.calendars

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.swapnilraj.calendar.R
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import kotlinx.coroutines.flow.distinctUntilChanged

private const val PhoneMonthCount = 9999 * 12
private fun YearMonth.page() = (year - 1) * 12 + monthValue - 1
private fun pageMonth(page: Int) = YearMonth.of(page / 12 + 1, page % 12 + 1)

/**
 * Pure date navigation for years1..9999; no events, queries or implicit selection on month paging.
 * The host controls [displayedMonth], closes on date selection, and supplies its date/year picker.
 * Normal scale is compact; narrow or large-text rows pan rather than shrink their touch targets.
 */
@Composable
fun CalendarMonthNavigator(
    displayedMonth: YearMonth,
    selectedDate: LocalDate,
    today: LocalDate,
    firstDayOfWeek: DayOfWeek,
    onNavigateMonth: (YearMonth) -> Unit,
    onSelectDate: (LocalDate) -> Unit,
    onChooseDate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    require(displayedMonth.year in 1..9999)
    require(selectedDate.year in 1..9999 && today.year in 1..9999)
    val locale = LocalLocale.current.platformLocale
    val pager = rememberPagerState(initialPage = displayedMonth.page(), pageCount = { PhoneMonthCount })
    val latestMonth by rememberUpdatedState(displayedMonth)
    val latestNavigate by rememberUpdatedState(onNavigateMonth)
    LaunchedEffect(displayedMonth) {
        if (pager.currentPage != displayedMonth.page()) pager.scrollToPage(displayedMonth.page())
    }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { page ->
            val month = pageMonth(page)
            if (month != latestMonth) latestNavigate(month)
        }
    }
    val previousLabel = stringResource(R.string.calendar_phone_previous_month)
    val nextLabel = stringResource(R.string.calendar_phone_next_month)
    val title = displayedMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy", locale))
    Surface(modifier.testTag("calendar_month_navigator")) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = displayedMonth.page() > 0,
                    onClick = { onNavigateMonth(displayedMonth.minusMonths(1)) },
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = previousLabel }) {
                    MonthNavigationArrow(false)
                }
                Text(title, style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.Content), textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).semantics { heading(); liveRegion = LiveRegionMode.Polite })
                IconButton(enabled = displayedMonth.page() < PhoneMonthCount - 1,
                    onClick = { onNavigateMonth(displayedMonth.plusMonths(1)) },
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = nextLabel }) {
                    MonthNavigationArrow(true)
                }
            }
            HorizontalPager(pager, modifier = Modifier.fillMaxWidth().testTag("calendar_month_pager"),
                key = { it }, beyondViewportPageCount = 0) { page ->
                MonthNavigationGrid(pageMonth(page), selectedDate, today, firstDayOfWeek, onSelectDate)
            }
            TextButton(onClick = onChooseDate, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.calendar_phone_choose_date))
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun MonthNavigationGrid(month: YearMonth, selectedDate: LocalDate, today: LocalDate,
    firstDayOfWeek: DayOfWeek, onSelectDate: (LocalDate) -> Unit) {
    val locale = LocalLocale.current.platformLocale
    val first = month.atDay(1).let { it.minusDays(Math.floorMod(it.dayOfWeek.value - firstDayOfWeek.value, 7).toLong()) }
    val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cellWidth = maxOf(maxWidth / 7, maxOf(48.dp, (32 * fontScale).dp))
        val rowHeight = maxOf(48.dp, (28 * fontScale).dp)
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Column(Modifier.width(cellWidth * 7).semantics { collectionInfo = CollectionInfo(7, 7) }) {
                Row {
                    repeat(7) { column ->
                        val day = firstDayOfWeek.plus(column.toLong())
                        Text(day.getDisplayName(TextStyle.NARROW, locale), style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.width(cellWidth).heightIn(min = 32.dp).wrapContentHeight()
                                .semantics { contentDescription = day.getDisplayName(TextStyle.FULL, locale); heading() },
                            textAlign = TextAlign.Center)
                    }
                }
                repeat(6) { row ->
                    Row {
                        repeat(7) { column ->
                            val date = first.plusDays((row * 7 + column).toLong())
                            val isSelected = date == selectedDate
                            val isToday = date == today
                            val fullDate = date.format(dateFormat)
                            val label = if (isToday) stringResource(R.string.calendar_phone_date_today, fullDate) else fullDate
                            val inMonth = YearMonth.from(date) == month
                            Box(Modifier.width(cellWidth).height(rowHeight)
                                .semantics {
                                    contentDescription = label
                                    selected = isSelected
                                    collectionItemInfo = CollectionItemInfo(row + 1, 1, column, 1)
                                }
                                .clickable(enabled = date.year in 1..9999, role = Role.Button) { onSelectDate(date) }
                                .testTag("calendar_month_date_${month}_$date"), contentAlignment = Alignment.Center) {
                                val fill = when { isSelected -> MaterialTheme.colorScheme.primary
                                    isToday -> MaterialTheme.colorScheme.secondaryContainer
                                    else -> MaterialTheme.colorScheme.surface }
                                val textColor = when { isSelected -> MaterialTheme.colorScheme.onPrimary
                                    isToday -> MaterialTheme.colorScheme.onSecondaryContainer
                                    !inMonth -> MaterialTheme.colorScheme.onSurfaceVariant
                                    else -> MaterialTheme.colorScheme.onSurface }
                                Box(Modifier.size(maxOf(36.dp, (24 * fontScale).dp)).background(fill, CircleShape), contentAlignment = Alignment.Center) {
                                    Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.bodyMedium, color = textColor,
                                        modifier = Modifier.clearAndSetSemantics {})
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthNavigationArrow(next: Boolean) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(20.dp)) {
        val tip = if (next != rtl) 0.65f else 0.35f
        val edge = 1 - tip
        drawLine(color, Offset(size.width * edge, size.height * 0.25f), Offset(size.width * tip, size.height * 0.5f), 1.5.dp.toPx())
        drawLine(color, Offset(size.width * tip, size.height * 0.5f), Offset(size.width * edge, size.height * 0.75f), 1.5.dp.toPx())
    }
}
