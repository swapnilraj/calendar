package dev.swapnilraj.calendar.ui

import androidx.core.text.BidiFormatter
import dev.swapnilraj.calendar.data.CalEvent
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

internal fun eventTimeLabel(event: CalEvent, zone: ZoneId, use24Hour: Boolean, locale: Locale): String {
    val dates = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    val bidi = BidiFormatter.getInstance(locale)
    if (event.allDay) {
        val start = event.start.atOffset(ZoneOffset.UTC).toLocalDate()
        val last = event.end.atOffset(ZoneOffset.UTC).toLocalDate().minusDays(1)
        val range = if (last <= start) start.format(dates) else "${start.format(dates)} – ${last.format(dates)}"
        return bidi.unicodeWrap(range)
    }
    val formatter = DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "h:mm a", locale)
    val start = event.start.atZone(zone)
    val end = event.end.atZone(zone)
    val includeDates = start.toLocalDate() != end.toLocalDate()
    val includeOffsets = start.offset != end.offset || zone.rules.getValidOffsets(start.toLocalDateTime()).size > 1 ||
        zone.rules.getValidOffsets(end.toLocalDateTime()).size > 1
    fun value(time: java.time.ZonedDateTime): String = buildString {
        if (includeDates) append(time.toLocalDate().format(dates)).append(' ')
        append(time.format(formatter))
        if (includeOffsets) append(" UTC").append(if (time.offset == ZoneOffset.UTC) "+00:00" else time.offset.id)
    }
    // Numeric times have no strong direction. Force the range's chronology even when the
    // layout is RTL while the formatting locale is English.
    return "\u202a${value(start)} – ${value(end)}\u202c"
}

/** Each fragment's end uses its own offset, even when that instant changes the local clock. */
internal fun eventSegmentTimeLabel(timed: TimedEvent, use24Hour: Boolean, locale: Locale): String {
    val segment = requireNotNull(timed.segment)
    val formatter = DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "h:mm a", locale)
    val begin = segment.start.atOffset(segment.offset).format(formatter)
    val end = segment.end.atOffset(segment.offset).format(formatter)
    return "\u202a$begin – $end ${segment.offsetLabel}\u202c"
}
