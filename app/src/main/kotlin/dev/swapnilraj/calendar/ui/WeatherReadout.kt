package dev.swapnilraj.calendar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import dev.swapnilraj.calendar.R
import dev.swapnilraj.calendar.ui.settings.WeatherUnits
import dev.swapnilraj.calendar.weather.*
import java.text.NumberFormat
import kotlin.math.roundToInt

/** Only numeric presentation changes. Input samples, thresholds, gradients and precipitation stay Celsius/mm. */
internal fun displayedWeatherTemperature(tempC: Double, units: WeatherUnits): Int = when (units) {
    WeatherUnits.CELSIUS -> tempC.toInt() // Retain the original Celsius header's truncation.
    WeatherUnits.FAHRENHEIT -> (tempC * 9.0 / 5.0 + 32.0).roundToInt()
}

@Composable
internal fun weatherTemperature(tempC: Double, units: WeatherUnits, readout: Boolean = false): Pair<String, String> {
    val number = NumberFormat.getIntegerInstance(LocalLocale.current.platformLocale).format(displayedWeatherTemperature(tempC, units))
    val visual = stringResource(if (units == WeatherUnits.FAHRENHEIT) R.string.weather_temperature_fahrenheit
        else if (readout) R.string.weather_readout_celsius else R.string.weather_temperature_celsius, number)
    val spoken = stringResource(if (units == WeatherUnits.CELSIUS) R.string.weather_degrees_celsius else R.string.weather_degrees_fahrenheit, number)
    return visual to spoken
}

/**
 * Ready samples only. Caller owns tap selection, timestamp/zone label, coverage and the surface.
 * Temperature/sky use [hour]; precipitation uses [precipitationHour]. For grid block k, callers pass
 * the sample stamped at k+1 for precipitation, which describes the preceding hour, as the background does.
 */
@Composable
fun WeatherReadout(hour: DayHour, units: WeatherUnits = WeatherUnits.CELSIUS, modifier: Modifier = Modifier,
    precipitationHour: DayHour = hour) {
    val (temperature, spokenTemperature) = weatherTemperature(hour.tempC, units, readout = true)
    val band = stringResource(when (tempBand(hour.tempC)) {
        TempBand.FREEZING -> R.string.weather_freezing; TempBand.COLD -> R.string.weather_cold
        TempBand.COOL -> R.string.weather_cool; TempBand.MILD -> R.string.weather_mild
        TempBand.WARM -> R.string.weather_warm; TempBand.HOT -> R.string.weather_hot
    })
    val sky = stringResource(when (skyBand(hour)) {
        SkyBand.BRIGHT -> R.string.weather_bright; SkyBand.MIXED -> R.string.weather_mixed
        SkyBand.OVERCAST -> R.string.weather_overcast; SkyBand.DARK -> R.string.weather_dark
    })
    val condition = weatherConditionLabel(hour.condition)
    val spoken = stringResource(R.string.weather_band_temperature, band, spokenTemperature)
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.weather_band_temperature, band, temperature),
            style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
            modifier = Modifier.clearAndSetSemantics { contentDescription = spoken })
        Text(stringResource(R.string.weather_band_temperature, sky, condition), style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content))
        WeatherPrecipitation(precipitationHour)
        if (precipitationHour.condition.wet && precipitationHour.precipProbability != null) {
            WeatherProbability(precipitationHour.precipProbability!!)
        }
    }
}

@Composable
internal fun WeatherProbability(probability: Double, modifier: Modifier = Modifier) {
    val value = NumberFormat.getIntegerInstance(LocalLocale.current.platformLocale).format(probability.toInt())
    val spoken = stringResource(R.string.weather_probability_accessible, value)
    Text(stringResource(R.string.weather_probability, value), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier.clearAndSetSemantics { contentDescription = spoken })
}

/** Localize the existing pure module's label result, so threshold decisions stay in one place. */
@Composable
private fun WeatherPrecipitation(hour: DayHour) {
    val pureLabel = precipLabel(hour) ?: return
    val labels = listOf("Light rain" to R.string.weather_light_rain, "Heavy rain" to R.string.weather_heavy_rain,
        "Light snow" to R.string.weather_light_snow, "Heavy snow" to R.string.weather_heavy_snow,
        "Thunderstorm" to R.string.weather_thunderstorm, "Drizzle" to R.string.weather_drizzle, "Rain" to R.string.weather_rain)
    val match = labels.firstOrNull { pureLabel == it.first || pureLabel.startsWith("${it.first} ") }
    val kind = if (match == null) weatherConditionLabel(hour.condition) else stringResource(match.second)
    val hasAmount = match != null && pureLabel != match.first && hour.precipMm != null
    val amount = if (hasAmount) NumberFormat.getNumberInstance(LocalLocale.current.platformLocale).apply {
        minimumFractionDigits = 1; maximumFractionDigits = 1
    }.format(hour.precipMm) else null
    val visual = if (amount != null) stringResource(R.string.weather_precip_amount, kind, amount) else kind
    val spoken = if (amount != null) stringResource(R.string.weather_precip_amount_accessible, kind, amount) else kind
    Text(visual, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
        modifier = Modifier.clearAndSetSemantics { contentDescription = spoken })
}

@Composable
internal fun weatherConditionLabel(condition: Condition): String = stringResource(when (condition) {
    Condition.UNKNOWN -> R.string.weather_unknown; Condition.CLEAR -> R.string.weather_clear
    Condition.PARTLY_CLOUDY -> R.string.weather_partly_cloudy; Condition.CLOUDY -> R.string.weather_cloudy
    Condition.FOG -> R.string.weather_fog; Condition.DRIZZLE -> R.string.weather_drizzle
    Condition.RAIN -> R.string.weather_rain; Condition.SNOW -> R.string.weather_snow
    Condition.THUNDERSTORM -> R.string.weather_thunderstorm
})
