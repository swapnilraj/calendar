package dev.swapnilraj.calendar.android.navigation

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Hints from another app, never proof of an existing occurrence or permission to change it. */
data class CalendarOccurrenceHints(val begin: Instant?, val end: Instant?, val allDay: Boolean?) {
    val allDayStartDate: LocalDate? get() = if (allDay == true) begin?.atOffset(ZoneOffset.UTC)?.toLocalDate() else null
    val allDayEndDateExclusive: LocalDate? get() = if (allDay == true) end?.atOffset(ZoneOffset.UTC)?.toLocalDate() else null
}

/** Plain, untrusted form inputs. Opening/cancelling a seed must never save an event. */
data class CalendarDraftSeed(
    val occurrence: CalendarOccurrenceHints,
    val title: String?,
    val location: String?,
    val description: String?,
    val guests: List<String>,
    val recurrenceRule: String?,
    val timeZone: ZoneId?,
    val availability: Int?,
    val accessLevel: Int?,
)

enum class CalendarIntentError { INVALID_URI, INVALID_EXTRA_TYPE, INVALID_INTERVAL, MISSING_BEGIN, OUT_OF_RANGE_TIME, INVALID_SEED_VALUE, MALFORMED_INPUT }

sealed interface CalendarIntentRoute {
    data class OpenDate(val date: LocalDate, val instant: Instant) : CalendarIntentRoute
    data class OpenEvent(val eventId: Long, val occurrence: CalendarOccurrenceHints) : CalendarIntentRoute
    data class CreateDraftSeed(val seed: CalendarDraftSeed) : CalendarIntentRoute
    /** Overrides must be reviewed against provider details, never silently applied to an existing event. */
    data class EditEvent(val eventId: Long, val seed: CalendarDraftSeed) : CalendarIntentRoute
    data object Unsupported : CalendarIntentRoute
    data class Invalid(val reason: CalendarIntentError) : CalendarIntentRoute
}
