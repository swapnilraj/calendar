package dev.swapnilraj.calendar.ui

import dev.swapnilraj.calendar.ui.theme.LocalDaylightDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.dp
import dev.swapnilraj.calendar.weather.*

@Composable
fun BoxScope.WeatherBackground(hours: List<DayHour>) {
    val gutterWidth = LocalCalendarGutter.current
    val theme = if (LocalDaylightDarkTheme.current) WeatherTheme.DARK else WeatherTheme.LIGHT
    val stops = remember(hours, theme) { gradientStops(hours, theme) }
    val texture = remember(hours) { textureFor(hours) }
    Box(Modifier.matchParentSize().padding(start = gutterWidth).drawWithCache {
        val gradient = Brush.verticalGradient(*stops.mapIndexed { i, color ->
            i / 96f to Color((color.r / 255).toFloat(), (color.g / 255).toFloat(), (color.b / 255).toFloat(), color.a.toFloat())
        }.toTypedArray(), startY = 0f, endY = size.height)
        val hourPx = size.height.toDouble() / 24
        val rain = if (theme == WeatherTheme.DARK) Color(170, 205, 255).copy(alpha = 0.32f) else Color(40, 90, 190).copy(alpha = 0.30f)
        val snow = if (theme == WeatherTheme.DARK) Color(235, 245, 255).copy(alpha = 0.45f) else Color(70, 110, 170).copy(alpha = 0.40f)
        onDrawBehind {
            drawRect(gradient)
            if (texture != null) {
                var y = 0f
                while (y < size.height) {
                    val alpha = texture.rowWetness((y + 9.dp.toPx()).toDouble(), hourPx).rain.toFloat()
                    if (alpha > 0) {
                        var x = 0f
                        val color = rain.copy(alpha = rain.alpha * alpha)
                        while (x < size.width) {
                            drawLine(color, Offset(x + 9.dp.toPx(), y + 1.5.dp.toPx()), Offset(x + 6.dp.toPx(), y + 9.5.dp.toPx()), 1.dp.toPx(), StrokeCap.Round)
                            drawLine(color, Offset(x + 3.5.dp.toPx(), y + 10.5.dp.toPx()), Offset(x + 1.dp.toPx(), y + 16.5.dp.toPx()), 1.dp.toPx(), StrokeCap.Round)
                            x += 12.dp.toPx()
                        }
                    }
                    y += 18.dp.toPx()
                }
                y = 0f
                while (y < size.height) {
                    val a = texture.rowWetness((y + 3.dp.toPx()).toDouble(), hourPx).snow.toFloat()
                    val b = texture.rowWetness((y + 9.dp.toPx()).toDouble(), hourPx).snow.toFloat()
                    if (a > 0 || b > 0) {
                        var x = 0f
                        while (x < size.width) {
                            drawCircle(snow.copy(alpha = snow.alpha * a), 1.1.dp.toPx(), Offset(x + 3.dp.toPx(), y + 3.dp.toPx()))
                            drawCircle(snow.copy(alpha = snow.alpha * b), 0.9.dp.toPx(), Offset(x + 9.5.dp.toPx(), y + 9.dp.toPx()))
                            x += 12.dp.toPx()
                        }
                    }
                    y += 12.dp.toPx()
                }
            }
        }
    })
}
