package dev.swapnilraj.calendar.ui.events

import android.content.Context
import android.content.res.Configuration
import android.icu.text.ListFormatter
import android.text.BidiFormatter
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import dev.swapnilraj.calendar.R
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.core.RecurrenceRules as CoreRecurrenceRules
import java.text.NumberFormat
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun recurrenceSummary(details: EventDetails, displayZone: ZoneId, locale: Locale): String? =
    recurrenceSummary(LocalContext.current, details, displayZone, locale)

/** Presentation only: unsupported rules remain untouched and never leak raw recurrence syntax. */
fun recurrenceSummary(context: Context, details: EventDetails, displayZone: ZoneId, locale: Locale): String? {
    val configuration = Configuration(context.resources.configuration).apply { setLocale(locale) }
    val resources = context.createConfigurationContext(configuration).resources
    val raw = details.recurrenceRule?.takeIf(String::isNotBlank)
    if (raw == null) return if (details.occurrence.ref.recurrence != null) resources.getString(R.string.recurrence_series) else null
    fun custom() = resources.getString(R.string.recurrence_custom)
    return try {
        val original = details.occurrence.ref.recurrence?.originalStart ?: details.occurrence.ref.instanceStart
        val start = when (details.occurrence.time) {
            is EventTime.AllDay -> RecurrenceStart.AllDay(original.atOffset(ZoneOffset.UTC).toLocalDate())
            is EventTime.Timed -> {
                val zone = details.eventZone ?: return custom()
                val local = original.atZone(zone)
                RecurrenceStart.Timed(local.toLocalDateTime(), zone, local.offset)
            }
        }
        val parsed = CoreRecurrenceRules.parse(raw, start) as? ParsedRecurrence.Supported ?: return custom()
        val spec = parsed.spec
        val bidi = BidiFormatter.getInstance(locale)
        fun safe(value: String): String = bidi.unicodeWrap(value)
        fun number(value: Int) = safe(NumberFormat.getIntegerInstance(locale).format(value))
        fun text(id: Int, vararg args: Any) = resources.getString(id, *args)
        fun frequency(id: Int) = resources.getQuantityString(id, spec.interval, number(spec.interval))
        fun zoneName(instant: Instant, zone: ZoneId): String = if (zone.normalized() == ZoneOffset.UTC) text(R.string.recurrence_utc)
            else DateTimeFormatter.ofPattern("zzzz", locale).withZone(zone).format(instant)
        fun date(value: LocalDate) = safe(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(value))
        val pattern = when (val pattern = spec.pattern) {
            RecurrencePattern.Daily -> frequency(R.plurals.recurrence_daily)
            is RecurrencePattern.Weekly -> {
                val days = pattern.days.sortedBy { (it.value - spec.weekStart.value + 7) % 7 }
                    .map { it.getDisplayName(TextStyle.FULL, locale) }
                text(R.string.recurrence_on, frequency(R.plurals.recurrence_weekly), safe(ListFormatter.getInstance(locale).format(days)))
            }
            is RecurrencePattern.MonthlyDate -> text(R.string.recurrence_month_day,
                frequency(R.plurals.recurrence_monthly), number(pattern.day))
            is RecurrencePattern.MonthlyWeekday -> {
                val ordinal = when (pattern.ordinal) {
                    1 -> R.string.recurrence_first; 2 -> R.string.recurrence_second; 3 -> R.string.recurrence_third
                    4 -> R.string.recurrence_fourth; 5 -> R.string.recurrence_fifth; -1 -> R.string.recurrence_last
                    -2 -> R.string.recurrence_second_last; -3 -> R.string.recurrence_third_last
                    -4 -> R.string.recurrence_fourth_last; -5 -> R.string.recurrence_fifth_last
                    else -> return custom()
                }
                text(R.string.recurrence_ordinal_day, frequency(R.plurals.recurrence_monthly), text(ordinal),
                    safe(pattern.day.getDisplayName(TextStyle.FULL, locale)))
            }
            is RecurrencePattern.Yearly -> {
                // A leap reference year formats MonthDay without inventing a recurrence year.
                val format = android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMMd")
                val monthDay = DateTimeFormatter.ofPattern(format, locale).format(pattern.monthDay.atYear(2000))
                text(R.string.recurrence_on, frequency(R.plurals.recurrence_yearly), safe(monthDay))
            }
        }
        val end = when (val end = spec.end) {
            RecurrenceEnd.Never -> null
            is RecurrenceEnd.Count -> resources.getQuantityString(R.plurals.recurrence_count, end.count, number(end.count))
            is RecurrenceEnd.ThroughDate -> if (start is RecurrenceStart.AllDay) text(R.string.recurrence_through, date(end.date))
                else text(R.string.recurrence_through_zone, date(end.date), safe(zoneName(end.date.atStartOfDay((start as RecurrenceStart.Timed).zone).toInstant(), start.zone)))
            is RecurrenceEnd.ExactUntil -> {
                val instant = safe(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.MEDIUM)
                    .withLocale(locale).withZone(displayZone).format(end.instant))
                text(R.string.recurrence_until, instant, safe(zoneName(end.instant, displayZone)))
            }
        }
        if (end == null) pattern else text(R.string.recurrence_with_end, pattern, end)
    } catch (_: Exception) { custom() }
}
