package dev.swapnilraj.calendar.weather

enum class Condition(val label: String, val severity: Int, val wet: Boolean = false) {
    UNKNOWN("Unknown", -1), CLEAR("Clear", 0), PARTLY_CLOUDY("Partly cloudy", 1),
    CLOUDY("Cloudy", 2), FOG("Fog", 3), DRIZZLE("Drizzle", 4, true),
    RAIN("Rain", 5, true), SNOW("Snow", 6, true), THUNDERSTORM("Thunderstorm", 7, true),
}

data class WmoDescription(val condition: Condition, val label: String)

fun describeWmoCode(code: Int): WmoDescription = when (code) {
    0 -> WmoDescription(Condition.CLEAR, "Clear sky")
    1 -> WmoDescription(Condition.CLEAR, "Mainly clear")
    2 -> WmoDescription(Condition.PARTLY_CLOUDY, "Partly cloudy")
    3 -> WmoDescription(Condition.CLOUDY, "Overcast")
    45, 48 -> WmoDescription(Condition.FOG, "Fog")
    51, 53, 55, 56, 57 -> WmoDescription(Condition.DRIZZLE, "Drizzle")
    61, 63, 65, 66, 67 -> WmoDescription(Condition.RAIN, "Rain")
    80, 81, 82 -> WmoDescription(Condition.RAIN, "Rain showers")
    71, 73, 75, 77 -> WmoDescription(Condition.SNOW, "Snow")
    85, 86 -> WmoDescription(Condition.SNOW, "Snow showers")
    95, 96, 99 -> WmoDescription(Condition.THUNDERSTORM, "Thunderstorm")
    else -> WmoDescription(Condition.UNKNOWN, "Unknown")
}
