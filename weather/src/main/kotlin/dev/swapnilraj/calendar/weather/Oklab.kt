package dev.swapnilraj.calendar.weather

import kotlin.math.cbrt
import kotlin.math.pow

data class Lab(val l: Double, val a: Double, val b: Double)

fun rgbToOklab(rgb: Rgba): Lab {
    fun linear(v: Double): Double { val c = v / 255; return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4) }
    val r = linear(rgb.r); val g = linear(rgb.g); val b = linear(rgb.b)
    val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
    val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
    val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
    return Lab(0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
        1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
        0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s)
}

fun oklabToRgb(lab: Lab, alpha: Double): Rgba {
    val l = (lab.l + 0.3963377774 * lab.a + 0.2158037573 * lab.b).pow(3)
    val m = (lab.l - 0.1055613458 * lab.a - 0.0638541728 * lab.b).pow(3)
    val s = (lab.l - 0.0894841775 * lab.a - 1.2914855480 * lab.b).pow(3)
    fun srgb(c: Double) = (255 * if (c <= 0.0031308) c * 12.92 else 1.055 * c.pow(1 / 2.4) - 0.055).coerceIn(0.0, 255.0)
    return Rgba(srgb(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s),
        srgb(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s),
        srgb(-0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s), alpha)
}

/** CSS `in oklab`: premultiply components by alpha, interpolate, then unpremultiply. */
fun lerpOklab(from: Rgba, to: Rgba, fraction: Double): Rgba {
    val t = fraction.coerceIn(0.0, 1.0)
    if (t == 0.0) return from
    if (t == 1.0) return to
    val a = from.a + (to.a - from.a) * t
    if (a == 0.0) return Rgba(0.0, 0.0, 0.0, 0.0)
    val x = rgbToOklab(from); val y = rgbToOklab(to)
    fun mix(v: Double, w: Double) = (v * from.a * (1 - t) + w * to.a * t) / a
    return oklabToRgb(Lab(mix(x.l, y.l), mix(x.a, y.a), mix(x.b, y.b)), a)
}
