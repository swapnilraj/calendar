package dev.swapnilraj.calendar.ui.grid

import java.time.Duration

/** Subminute durations remain positive even at nanosecond precision; no zero-minute claim. */
internal data class GridElapsedAmount(val value: String, val seconds: Boolean)
internal fun gridElapsedAmount(duration: Duration, locale: java.util.Locale): GridElapsedAmount {
    val seconds = duration < Duration.ofMinutes(1)
    val format = java.text.NumberFormat.getNumberInstance(locale).apply { if (seconds) maximumFractionDigits = 9 }
    val value = duration.seconds.toDouble() + duration.nano / 1_000_000_000.0
    return GridElapsedAmount(format.format(if (seconds) value else value / 60), seconds)
}
