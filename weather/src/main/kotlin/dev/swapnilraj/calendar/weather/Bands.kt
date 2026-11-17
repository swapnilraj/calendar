package dev.swapnilraj.calendar.weather

import java.util.Locale
import kotlin.math.sqrt

data class DayHour(
    val condition: Condition = Condition.CLOUDY,
    val precipProbability: Double? = 0.0,
    val precipMm: Double? = null,
    val isDay: Boolean = true,
    val tempC: Double = 17.0,
)

fun ForecastHour.toDayHour() = DayHour(condition, precipProbability, precipMm, isDay, tempC)

enum class SkyBand(val label: String) { BRIGHT("Bright"), MIXED("Mixed"), OVERCAST("Overcast"), DARK("Dark") }
enum class TempBand(val label: String) { FREEZING("Freezing"), COLD("Cold"), COOL("Cool"), MILD("Mild"), WARM("Warm"), HOT("Hot") }

fun skyBand(hour: DayHour): SkyBand = when {
    !hour.isDay || hour.condition == Condition.THUNDERSTORM -> SkyBand.DARK
    hour.condition == Condition.CLEAR -> SkyBand.BRIGHT
    hour.condition == Condition.PARTLY_CLOUDY -> SkyBand.MIXED
    else -> SkyBand.OVERCAST
}

fun tempBand(tempC: Double): TempBand = when {
    tempC <= 0 -> TempBand.FREEZING
    tempC <= 7 -> TempBand.COLD
    tempC <= 14 -> TempBand.COOL
    tempC <= 20 -> TempBand.MILD
    tempC <= 27 -> TempBand.WARM
    else -> TempBand.HOT
}

data class Wetness(val rain: Double = 0.0, val snow: Double = 0.0)

fun precipIntensity(hour: DayHour): Wetness {
    if (!hour.condition.wet) return Wetness()
    val snowy = hour.condition == Condition.SNOW
    val mm = hour.precipMm
    val p = hour.precipProbability ?: 50.0
    val intensity = if (mm != null) {
        if (mm < 0.05) 0.0 else sqrt(mm / if (snowy) 3 else 6).coerceIn(0.12, 1.0)
    } else {
        (if (p < 10) 0.0 else 0.15 + 0.85 * p / 100) * if (hour.condition == Condition.DRIZZLE) 0.6 else 1.0
    }
    return if (snowy) Wetness(snow = intensity) else Wetness(rain = intensity)
}

fun precipLabel(hour: DayHour): String? {
    val wet = precipIntensity(hour)
    if (wet.rain == 0.0 && wet.snow == 0.0) return null
    val mm = hour.precipMm
    val amount = if (mm != null && mm >= 0.1) String.format(Locale.ROOT, " %.1f mm", mm) else ""
    val kind = when {
        wet.snow > 0 -> if (mm == null || mm < 2) "Light snow" else "Heavy snow"
        hour.condition == Condition.THUNDERSTORM -> "Thunderstorm"
        hour.condition == Condition.DRIZZLE -> "Drizzle"
        mm == null -> "Rain"
        mm < 2.5 -> "Light rain"
        mm < 7.6 -> "Rain"
        else -> "Heavy rain"
    }
    return kind + amount
}

fun wetnessAtLines(hours: List<DayHour>): List<Wetness> {
    require(hours.size == 25)
    return hours.indices.map { i ->
        val a = precipIntensity(hours[i])
        val b = precipIntensity(hours[minOf(i + 1, 24)])
        Wetness((a.rain + b.rain) / 2, (a.snow + b.snow) / 2)
    }
}
