package dev.swapnilraj.calendar.weather

import kotlin.math.floor

data class Rgba(val r: Double, val g: Double, val b: Double, val a: Double)
enum class WeatherTheme { LIGHT, DARK }

/** Constants and rounding match src/ui/tint.ts in the desktop prototype. RGB is 0..255. */
fun tintFor(hour: DayHour, theme: WeatherTheme, wet: Wetness = precipIntensity(hour)): Rgba {
    val band = skyBand(hour)
    val base = when {
        hour.condition == Condition.THUNDERSTORM -> Rgba(72.0, 52.0, 132.0, 0.20)
        hour.condition == Condition.FOG && band != SkyBand.DARK -> Rgba(198.0, 204.0, 212.0, 0.15)
        else -> when (band) {
            SkyBand.BRIGHT -> Rgba(255.0, 222.0, 140.0, 0.13)
            SkyBand.MIXED -> Rgba(226.0, 212.0, 184.0, 0.08)
            SkyBand.OVERCAST -> Rgba(148.0, 156.0, 168.0, 0.12)
            SkyBand.DARK -> Rgba(38.0, 58.0, 120.0, 0.17)
        }
    }
    val cast = when (tempBand(hour.tempC)) {
        TempBand.FREEZING -> Rgba(120.0, 215.0, 255.0, 0.55)
        TempBand.COLD -> Rgba(79.0, 190.0, 240.0, 0.38)
        TempBand.COOL -> Rgba(95.0, 180.0, 200.0, 0.15)
        TempBand.MILD -> Rgba(0.0, 0.0, 0.0, 0.0)
        TempBand.WARM -> Rgba(255.0, 165.0, 80.0, 0.25)
        TempBand.HOT -> Rgba(255.0, 105.0, 70.0, 0.42)
    }
    val m = cast.a * if (band == SkyBand.DARK) 0.6 else 1.0
    fun mix(a: Double, b: Double, t: Double) = a + (b - a) * t
    var r = mix(base.r, cast.r, m)
    var g = mix(base.g, cast.g, m)
    var b = mix(base.b, cast.b, m)
    var a = base.a + 0.05 * m
    if (wet.rain > 0) {
        r = mix(r, 78.0, 0.55 * wet.rain); g = mix(g, 104.0, 0.55 * wet.rain); b = mix(b, 150.0, 0.55 * wet.rain)
        a += 0.05 * wet.rain
    }
    if (wet.snow > 0) {
        r = mix(r, 215.0, 0.55 * wet.snow); g = mix(g, 232.0, 0.55 * wet.snow); b = mix(b, 252.0, 0.55 * wet.snow)
        a += 0.04 * wet.snow
    }
    if (theme == WeatherTheme.LIGHT) {
        r *= 0.82; g *= 0.82; b *= 0.90; a = minOf(0.30, a * 1.7)
    }
    return Rgba(floor(r + 0.5), floor(g + 0.5), floor(b + 0.5), floor(a * 1000 + 0.5) / 1000)
}
