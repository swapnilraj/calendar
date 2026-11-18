package dev.swapnilraj.calendar.android.navigation

import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.calendar.CalendarReminderSource
import kotlinx.coroutines.CancellationException

/** A notification is a hint to a persisted post, never authority to open its old event number.
 * Read-only resolution follows the original reminder key to the current expanded occurrence.
 */
class ReminderOpenResolver(
    private val source: ReminderSource,
    private val ledger: ReminderLedger,
    private val configuration: suspend () -> ReminderConfiguration,
    private val readDetails: suspend (EventRef) -> CalendarReadState<EventDetails>,
) {
    suspend fun resolve(post: ReminderPostIdentity): CalendarReadState<EventDetails> = try {
        val config = configuration()
        val record = ledger.findByAlias(post.alias)
        if (record == null || record.postIdentity != post || record.state !in OpenStates ||
            !config.enabled || record.key.calendarId !in config.enabledCalendars ||
            config.expectedCalendarIdentities[record.key.calendarId] != record.sourceCalendarIdentity) {
            CalendarReadState.Unavailable
        } else when (val resolved = source.resolve(setOf(record.key), config)) {
            ReminderResolutionResult.PermissionRequired -> CalendarReadState.PermissionRequired
            ReminderResolutionResult.Unavailable -> CalendarReadState.Unavailable
            is ReminderResolutionResult.Ready -> {
                val candidate = (resolved.results[record.key] as? ReminderResolution.Eligible)?.candidate
                if (candidate == null || candidate.key != record.key ||
                    candidate.sourceCalendarIdentity != record.sourceCalendarIdentity ||
                    (record.sourceEventUidFingerprint != null && record.sourceEventUidFingerprint != candidate.sourceEventUidFingerprint)) {
                    CalendarReadState.Unavailable
                } else {
                    val details = readDetails(candidate.occurrence.ref)
                    if (details is CalendarReadState.Ready) {
                        val currentIdentity = details.value.calendar?.let { CalendarReminderSource.calendarIdentity(it) }
                        if (details.value.occurrence.ref != candidate.occurrence.ref || currentIdentity != record.sourceCalendarIdentity ||
                            configuration() != config || ledger.findByAlias(post.alias) != record) CalendarReadState.Unavailable
                        else details
                    } else details
                }
            }
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: SecurityException) { CalendarReadState.PermissionRequired }
    catch (_: Exception) { CalendarReadState.Unavailable }

    private companion object {
        // A private nonce-bearing tap may arrive after a post accepted by Android lost its ACK.
        // Opening is read-only and does not assert delivery success or change its uncertain state.
        val OpenStates = setOf(ReminderDeliveryState.POSTED, ReminderDeliveryState.POSTING, ReminderDeliveryState.UNCERTAIN)
    }
}
