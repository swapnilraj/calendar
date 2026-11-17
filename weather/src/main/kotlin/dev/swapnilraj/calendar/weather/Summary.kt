package dev.swapnilraj.calendar.weather

import java.time.LocalDate
import java.time.ZoneId

data class DaySummary(
    val condition: Condition,
    val high: Double,
    val low: Double,
    val precipProbability: Double?,
)

fun daySummary(forecast: List<ForecastHour>, date: LocalDate, zone: ZoneId): DaySummary? {
    val hours = forecast.filter { it.time.atZone(zone).toLocalDate() == date }
    if (hours.size < 20) return null
    val daytime = hours.filter { it.isDay }
    val basis = if (daytime.size >= 3) daytime else hours
    val counts = basis.groupingBy { it.condition }.eachCount()
    val notable = counts.filter { (c, n) -> n >= 2 || c == Condition.THUNDERSTORM && n >= 1 }
    val condition = if (notable.isNotEmpty()) notable.keys.maxBy { it.severity }
        else counts.maxBy { it.value }.key
    return DaySummary(condition, hours.maxOf { it.tempC }, hours.minOf { it.tempC }, hours.mapNotNull { it.precipProbability }.maxOrNull())
}
