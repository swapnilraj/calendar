package dev.swapnilraj.calendar.core

import java.time.ZoneId

data class CalendarAccount(val name: String, val type: String)

/** A creation destination is a namespace, never a reusable provider-local number alone. */
data class CalendarIdentity(
    val calendarId: CalendarId,
    val account: CalendarAccount,
    val owner: String?,
    val providerId: String? = null,
)

/** Provider flags are observations, not app-local preferences or proof of cloud coverage. */
data class CalendarInfo(
    val id: CalendarId,
    val displayName: String,
    val color: Int,
    val account: CalendarAccount,
    val owner: String?,
    val zone: ZoneId?,
    val accessLevel: Int?,
    val providerVisible: Boolean,
    val syncEnabled: Boolean,
    val capabilities: CalendarCapabilities = CalendarCapabilities(),
    val providerId: String? = null,
) {
    val identity: CalendarIdentity get() = CalendarIdentity(id, account, owner?.takeIf(String::isNotBlank),
        providerId?.takeIf(String::isNotBlank))
}

/** One provider-expanded occurrence; rendering segments are deliberately separate. */
data class EventOccurrence(
    val ref: EventRef,
    val time: EventTime,
    val title: String,
    val color: Int,
    val declined: Boolean = false,
)

data class CalendarRequest(
    val range: CalendarDateRange,
    val displayZone: ZoneId,
    val selection: CalendarSelection = CalendarSelection.ProviderVisible,
)

enum class CoverageReason { PROVIDER_HIDDEN, SYNC_DISABLED, MISSING_CALENDAR, INSUFFICIENT_ACCESS }
data class CalendarCoverage(val calendarId: CalendarId, val reason: CoverageReason)

data class CalendarSnapshot(
    val request: CalendarRequest,
    val calendars: List<CalendarInfo>,
    val occurrences: List<EventOccurrence>,
    val coverage: List<CalendarCoverage> = emptyList(),
)

sealed interface CalendarReadState<out T> {
    data object Loading : CalendarReadState<Nothing>
    data class Ready<T>(val value: T) : CalendarReadState<T>
    data object PermissionRequired : CalendarReadState<Nothing>
    data object Unavailable : CalendarReadState<Nothing>
}
