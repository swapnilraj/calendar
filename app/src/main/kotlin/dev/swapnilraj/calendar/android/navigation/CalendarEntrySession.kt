package dev.swapnilraj.calendar.android.navigation

import dev.swapnilraj.calendar.core.CalendarIdentity
import dev.swapnilraj.calendar.data.calendar.EventNavigationInfo
import java.util.UUID

/** Source identity only; no event title, notes, attendees, or details payload is retained. */
data class CalendarEntryBinding(val eventId: Long, val calendar: CalendarIdentity, val providerUid: String?) {
    init {
        require(eventId >= 0 && calendar.account.name.isNotBlank() && calendar.account.type.isNotBlank())
        require(providerUid == null || providerUid.isNotBlank())
    }
}

/** Exactly one incoming intent owns a session. Cancellation never discards an already observed pin.
 * A new external intent creates a new session; lifecycle retry must reuse this one.
 */
class CalendarEntrySession internal constructor(
    val route: CalendarIntentRoute,
    val id: String,
    initialBinding: CalendarEntryBinding?,
) {
    private val lock = Any()
    private var pin = initialBinding
    val binding: CalendarEntryBinding? get() = synchronized(lock) { pin }

    internal fun bind(info: EventNavigationInfo): Boolean = synchronized(lock) {
        val observed = try { CalendarEntryBinding(info.eventId, info.calendar, info.providerUid) }
            catch (_: IllegalArgumentException) { return@synchronized false }
        val previous = pin
        if (previous == null) { pin = observed; true }
        else if (!bindingMatches(previous, observed)) false
        else {
            pin = previous.copy(calendar = previous.calendar.copy(
                owner = previous.calendar.owner ?: observed.calendar.owner,
                providerId = previous.calendar.providerId ?: observed.calendar.providerId),
                providerUid = previous.providerUid ?: observed.providerUid)
            true
        }
    }

    companion object {
        fun create(route: CalendarIntentRoute) = CalendarEntrySession(route, UUID.randomUUID().toString(), null)
    }
}

internal fun identityMatches(expected: CalendarIdentity, current: CalendarIdentity): Boolean =
    expected.calendarId == current.calendarId && expected.account == current.account &&
        (expected.owner == null || expected.owner == current.owner) &&
        (expected.providerId == null || expected.providerId == current.providerId)

internal fun bindingMatches(expected: CalendarEntryBinding, observed: CalendarEntryBinding): Boolean =
    expected.eventId == observed.eventId && identityMatches(expected.calendar, observed.calendar) &&
        (expected.providerUid == null || expected.providerUid == observed.providerUid)
