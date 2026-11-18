package dev.swapnilraj.calendar.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

val HourGutter = 56.dp
internal val LocalCalendarGutter = staticCompositionLocalOf { HourGutter }

@Composable
internal fun calendarGutter(date: LocalDate, use24Hour: Boolean): Dp {
    val locale = LocalLocale.current.platformLocale
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(date, use24Hour, locale, density.density, density.fontScale, measurer) {
        val formatter = DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "h a", locale)
        val labelWidth = (0..23).maxOf { hour ->
            measurer.measure(LocalTime.of(hour, 0).format(formatter), TextStyle(fontSize = 10.sp)).size.width
        }
        val dateWidth = measurer.measure(date.dayOfMonth.toString(), TextStyle(fontSize = 22.sp)).size.width
        val weekdayWidth = measurer.measure(date.format(DateTimeFormatter.ofPattern("EEE", locale)), TextStyle(fontSize = 11.sp)).size.width
        maxOf(HourGutter, (34f * density.fontScale).dp + 8.dp,
            with(density) { maxOf(labelWidth, dateWidth, weekdayWidth).toDp() + 16.dp })
    }
}

@Composable
internal fun minimumEventHeight(): Dp {
    val measured = rememberTextMeasurer().measure("Ag", TextStyle(fontSize = 12.sp, lineHeight = 15.sp))
    return with(LocalDensity.current) { measured.size.height.toDp() + 8.dp }.coerceAtLeast(20.dp)
}
