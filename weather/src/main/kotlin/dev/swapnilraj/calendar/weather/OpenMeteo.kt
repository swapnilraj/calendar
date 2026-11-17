package dev.swapnilraj.calendar.weather

import java.net.URLEncoder
import java.time.Instant
import java.util.Locale
import kotlinx.serialization.json.*
import kotlin.math.floor

data class Coordinates(val latitude: Double, val longitude: Double) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
    }
}

data class ForecastHour(
    val time: Instant,
    val code: Int,
    val tempC: Double,
    val precipProbability: Double?,
    val precipMm: Double?,
    val isDay: Boolean,
) {
    val condition: Condition get() = describeWmoCode(code).condition
}

class WeatherResponseException : IllegalArgumentException("Invalid weather response")

/** Injection keeps network access out of the model and makes failures testable. */
class OpenMeteo(private val fetch: (String) -> String) {
    fun forecast(location: Coordinates): List<ForecastHour> = parseHourly(fetch(forecastUrl(location)))
    fun geocode(city: String): Coordinates? = parseGeocoding(fetch(geocodingUrl(city)))
}

fun forecastUrl(location: Coordinates): String =
    "https://api.open-meteo.com/v1/forecast?latitude=${String.format(Locale.ROOT, "%.2f", location.latitude)}" +
        "&longitude=${String.format(Locale.ROOT, "%.2f", location.longitude)}" +
        "&hourly=temperature_2m,weather_code,precipitation_probability,precipitation,is_day" +
        "&timezone=GMT&timeformat=unixtime&past_days=1&forecast_days=16&temperature_unit=celsius"

fun cityForZone(zoneId: String): String? = zoneId.takeIf { '/' in it }
    ?.substringAfterLast('/')?.replace('_', ' ')?.takeIf { it.length >= 2 }

fun geocodingUrl(city: String): String {
    require(city.trim().length >= 2)
    return "https://geocoding-api.open-meteo.com/v1/search?name=" +
        URLEncoder.encode(city.trim(), "UTF-8") + "&count=1&language=en&format=json"
}

private fun jsonObject(body: String): JsonObject = try {
    Json.parseToJsonElement(body) as? JsonObject ?: throw WeatherResponseException()
} catch (_: kotlinx.serialization.SerializationException) {
    throw WeatherResponseException()
}

private fun JsonElement?.number(): Double? = (this as? JsonPrimitive)
    ?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() }

fun parseHourly(body: String): List<ForecastHour> {
    val hourly = jsonObject(body)["hourly"] as? JsonObject ?: throw WeatherResponseException()
    fun required(name: String) = hourly[name] as? JsonArray ?: throw WeatherResponseException()
    val time = required("time")
    val temperatures = required("temperature_2m")
    val codes = required("weather_code")
    val optional = listOf("precipitation_probability", "precipitation", "is_day").associateWith { name ->
        when (val value = hourly[name]) {
            null -> null
            is JsonArray -> value
            else -> throw WeatherResponseException()
        }
    }
    if ((listOf(temperatures, codes) + optional.values.filterNotNull()).any { it.size != time.size }) {
        throw WeatherResponseException()
    }
    return time.indices.mapNotNull { i ->
        val seconds = time[i].number() ?: return@mapNotNull null
        val temp = temperatures[i].number() ?: return@mapNotNull null
        val code = codes[i].number() ?: return@mapNotNull null
        if (seconds % 1.0 != 0.0 || code % 1.0 != 0.0) return@mapNotNull null
        val instant = try { Instant.ofEpochSecond(seconds.toLong()) } catch (_: Exception) { return@mapNotNull null }
        ForecastHour(
            instant, code.toInt(), floor(temp + 0.5),
            optional["precipitation_probability"]?.get(i).number(),
            optional["precipitation"]?.get(i).number(),
            optional["is_day"]?.get(i).number() == 1.0,
        )
    }
}

fun parseGeocoding(body: String): Coordinates? {
    val root = jsonObject(body)
    val results = root["results"] ?: return null
    if (results !is JsonArray) throw WeatherResponseException()
    return results.firstNotNullOfOrNull { item ->
        val obj = item as? JsonObject ?: return@firstNotNullOfOrNull null
        val lat = obj["latitude"].number() ?: return@firstNotNullOfOrNull null
        val lon = obj["longitude"].number() ?: return@firstNotNullOfOrNull null
        runCatching { Coordinates(lat, lon) }.getOrNull()
    }
}
