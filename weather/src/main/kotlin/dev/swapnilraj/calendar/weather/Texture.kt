package dev.swapnilraj.calendar.weather

import kotlin.math.floor

data class Texture(val blocks: List<Wetness>) {
    /** The same mask used by the SVG prototype, evaluated at a physical row. */
    fun rowWetness(y: Double, hourPx: Double): Wetness {
        require(hourPx > 0)
        val position = (y / hourPx - 0.5).coerceIn(0.0, 23.0)
        val left = floor(position).toInt()
        val right = minOf(left + 1, 23)
        val t = position - left
        return Wetness(blocks[left].rain + (blocks[right].rain - blocks[left].rain) * t,
            blocks[left].snow + (blocks[right].snow - blocks[left].snow) * t)
    }
}

fun textureFor(hours: List<DayHour>): Texture? {
    require(hours.size == 25)
    val blocks = (0..23).map { k -> precipIntensity(hours[k + 1]) }
    return if (blocks.all { it.rain == 0.0 && it.snow == 0.0 }) null else Texture(blocks)
}

/** 97 OKLab samples: four sub-stops per hour, including the next midnight. */
fun gradientStops(hours: List<DayHour>, theme: WeatherTheme): List<Rgba> {
    require(hours.size == 25)
    val wetness = wetnessAtLines(hours)
    val tints = hours.mapIndexed { i, hour -> tintFor(hour, theme, wetness[i]) }
    return (0..96).map { step ->
        if (step == 96) tints[24] else lerpOklab(tints[step / 4], tints[step / 4 + 1], step % 4 / 4.0)
    }
}
