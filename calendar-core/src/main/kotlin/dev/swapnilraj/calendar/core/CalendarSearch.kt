package dev.swapnilraj.calendar.core

import kotlinx.coroutines.flow.Flow

/** Literal case-insensitive text search; provider metacharacters have no special meaning. */
data class CalendarSearchRequest(
    val query: String,
    val calendar: CalendarRequest,
    val limit: Int = 200,
    val includeDeclined: Boolean = true,
) {
    init {
        require(query.trim().isNotEmpty() && query.length <= 256)
        require(limit in 1..1000)
        require(calendar.range.endExclusive <= calendar.range.start.plusYears(10))
    }
}

/** Provider-authorized plain text only; each hit retains its exact expanded occurrence identity. */
data class CalendarSearchHit(
    val occurrence: EventOccurrence,
    val location: String? = null,
    val snippet: String? = null,
)

data class CalendarSearchSnapshot(
    val request: CalendarSearchRequest,
    val calendars: List<CalendarInfo>,
    val hits: List<CalendarSearchHit>,
    val truncated: Boolean,
    val coverage: List<CalendarCoverage> = emptyList(),
)

interface CalendarSearch {
    fun observe(request: CalendarSearchRequest): Flow<CalendarReadState<CalendarSearchSnapshot>>
}
