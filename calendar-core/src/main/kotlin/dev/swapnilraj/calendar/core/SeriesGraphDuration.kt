package dev.swapnilraj.calendar.core

import java.time.Duration

/** Separate opt-in protocol. None of these values enable the legacy WholeEdit executor. */
object SeriesGraphDurationProtocol {
    const val FAMILY = "SERIES_GRAPH_DURATION_V1"
    const val FINGERPRINT_PREFIX = "series-graph-duration-v1:"
    const val RECEIPT_VERSION = 1
}

/** No Keep, patches, anchor, rule, zone, conversion, scope or cloning input exists on this path. */
sealed interface SeriesGraphDuration {
    data class TimedSeconds(val seconds: Long) : SeriesGraphDuration {
        init { require(seconds > 0) }
    }
    data class AllDayDays(val days: Long) : SeriesGraphDuration {
        init { require(days > 0) }
    }
}

data class SeriesGraphDurationCommand(val selected: EventRef, val token: EditToken, val duration: SeriesGraphDuration) {
    init {
        val recurrence = requireNotNull(selected.recurrence)
        require(selected.calendarId.value > 0 && selected.eventId > 0 && recurrence.masterEventId > 0)
        require(listOf(selected.instanceStart, selected.instanceEnd, recurrence.originalStart).all { it.nano % 1_000_000 == 0 })
        require(token.value.matches(Regex("[0-9a-f]{64}")))
    }

    /** Pure planner input, never native execution authority. The token must bind this exact source. */
    fun intent(start: RecurrenceStart): RecurrenceEditIntent.WholeSeriesEdit = RecurrenceEditIntent.WholeSeriesEdit(start,
        duration = when (val value = duration) {
            is SeriesGraphDuration.TimedSeconds -> {
                require(start is RecurrenceStart.Timed)
                RecurrenceDurationChange.TimedElapsed(Duration.ofSeconds(value.seconds))
            }
            is SeriesGraphDuration.AllDayDays -> {
                require(start is RecurrenceStart.AllDay)
                RecurrenceDurationChange.AllDayDays(value.days)
            }
        })
}

/** Issued by a future verified adapter for this exact command family; readable graph is insufficient. */
data class SeriesGraphDurationAccess(val series: EditableSeries, val duration: AccessDecision = AccessDecision.UNKNOWN)

sealed interface SeriesGraphDurationReadResult {
    data class Ready(val access: SeriesGraphDurationAccess) : SeriesGraphDurationReadResult
    data object PermissionRequired : SeriesGraphDurationReadResult
    data class Denied(val reason: MutationDenial) : SeriesGraphDurationReadResult
    data class NeedsReview(val reason: String) : SeriesGraphDurationReadResult
    data object Unavailable : SeriesGraphDurationReadResult
}

/** Independent future port. This packet supplies no implementation or composition binding. */
interface CalendarSeriesGraphDuration {
    suspend fun loadForEdit(selected: EventRef): SeriesGraphDurationReadResult
    suspend fun mutate(operationId: String, command: SeriesGraphDurationCommand): SeriesMutationResult
    /** Read-only; an unknown native attempt must never dispatch again. */
    suspend fun recover(operationId: String): SeriesMutationResult
}
