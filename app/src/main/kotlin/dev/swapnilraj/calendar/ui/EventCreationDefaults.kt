package dev.swapnilraj.calendar.ui

import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.settings.CalendarSettingsOptions
import java.time.*

/** Defaults for a new draft only; never reinterpret an existing provider reminder or event. */
internal fun eventCreationDraft(date: LocalDate, allDay: Boolean, now: ZonedDateTime,
    options: CalendarSettingsOptions, calendar: CalendarId?): EventDraft? {
    if (allDay) return EventDraft(calendar ?: CalendarId(0), EventTime.AllDay(CalendarDateRange.day(date)),
        null, "", alertMinutes = listOfNotNull(options.allDayReminder.minutesBefore))
    val time = if (date == now.toLocalDate()) {
        val minute = minOf(23 * 60 + 45, ((now.hour * 60 + now.minute + (if (now.second > 0 || now.nano > 0) 1 else 0) + 14) / 15) * 15)
        LocalTime.of(minute / 60, minute % 60)
    } else LocalTime.of(9, 0)
    val local = date.atTime(time)
    val offsets = now.zone.rules.getValidOffsets(local)
    // Defaults can advance over a skipped clock band, but never leave the browsed date.
    val start = if (offsets.isEmpty()) {
        val transition = now.zone.rules.getTransition(local) ?: return null
        if (transition.dateTimeAfter.toLocalDate() != date) return null
        transition.dateTimeAfter.toInstant(transition.offsetAfter)
    } else {
        val offset = offsets.singleOrNull() ?: offsets.firstOrNull { it == now.offset } ?: return null
        local.toInstant(offset)
    }
    val end = start.plusSeconds(options.defaultDurationMinutes * 60L)
    if (end.atZone(now.zone).year !in 1..9999) return null
    return EventDraft(calendar ?: CalendarId(0), EventTime.Timed(start, end), now.zone, "",
        alertMinutes = listOfNotNull(options.timedReminderMinutes))
}
