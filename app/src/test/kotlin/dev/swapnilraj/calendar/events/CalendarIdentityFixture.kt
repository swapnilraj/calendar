package dev.swapnilraj.calendar.events

import dev.swapnilraj.calendar.core.*
import java.time.ZoneOffset

internal fun eventTestCalendar(id: Long = 2) = CalendarInfo(CalendarId(id), "Personal", 1,
    CalendarAccount("fixture@example.test", "Fixture"), "fixture@example.test", ZoneOffset.UTC, 700, true, true,
    CalendarCapabilities(createEvents = AccessDecision.ALLOWED))
internal fun eventTestIdentity(id: Long = 2) = eventTestCalendar(id).identity
