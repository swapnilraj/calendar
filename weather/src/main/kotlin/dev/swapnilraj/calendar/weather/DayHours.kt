package dev.swapnilraj.calendar.weather

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs

/** Fixed wall-clock rows; next midnight can be 23 or 25 elapsed hours away. */
fun hourInstants(date: LocalDate, zone: ZoneId): List<Instant> = (0..24).map { row ->
    if (row == 24) date.plusDays(1).atStartOfDay(zone).toInstant()
    else date.atTime(LocalTime.of(row, 0)).atZone(zone).withLaterOffsetAtOverlap().toInstant()
}

fun hoursForDay(forecast: List<ForecastHour>, date: LocalDate, zone: ZoneId): List<DayHour>? {
    return hourInstants(date, zone).map { instant ->
        val sample = forecast.minWithOrNull(compareBy<ForecastHour> { abs(it.time.epochSecond - instant.epochSecond) }
            .thenByDescending { it.time }) ?: return null
        if (abs(sample.time.epochSecond - instant.epochSecond) > 1800) return null
        sample.toDayHour()
    }
}
