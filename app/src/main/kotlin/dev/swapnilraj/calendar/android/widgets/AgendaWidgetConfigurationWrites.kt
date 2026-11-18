package dev.swapnilraj.calendar.android.widgets

import kotlinx.coroutines.Deferred

/** A lifecycle/host-binding epoch, distinct from a configuration generation or numeric widget ID. */
data class WidgetEpoch(val widgetId: Int, val token: String) {
    init { require(widgetId > 0 && token.isNotBlank()) }
}
sealed interface WidgetLifecycleProof {
    data class Verified(val epoch: WidgetEpoch) : WidgetLifecycleProof
    data object SetupRequired : WidgetLifecycleProof
    data object Unavailable : WidgetLifecycleProof
}
data class WidgetConfigurationChoice(
    val selection: WidgetCalendarSelection = WidgetCalendarSelection.FollowApp,
    val privacy: WidgetPrivacy = WidgetPrivacy.BUSY_ONLY,
)
class WidgetConfigurationSession internal constructor(val epoch: WidgetEpoch)
sealed interface WidgetConfigurationSessionResult {
    data class Ready(val session: WidgetConfigurationSession) : WidgetConfigurationSessionResult
    data object SetupRequired : WidgetConfigurationSessionResult
    data object Unavailable : WidgetConfigurationSessionResult
}
sealed interface WidgetRemovalReceipt {
    data class Committed(val epoch: WidgetEpoch) : WidgetRemovalReceipt
    data object Unavailable : WidgetRemovalReceipt
    data object Superseded : WidgetRemovalReceipt
}
sealed interface WidgetSaveReceipt {
    data class Committed(val epoch: WidgetEpoch, val generation: String) : WidgetSaveReceipt
    data object Unavailable : WidgetSaveReceipt
    data object Stale : WidgetSaveReceipt
}
sealed interface WidgetConfigurationSaveResult {
    data class Saved(val configuration: AgendaWidgetConfiguration) : WidgetConfigurationSaveResult
    data object Unavailable : WidgetConfigurationSaveResult
    data object Stale : WidgetConfigurationSaveResult
}

/** Root's sole configuration writer. Enqueue synchronously registers FIFO work before returning;
 * throwing means nothing was accepted. The actor owns operations independently of caller cancellation.
 * A receipt settles only after its structured IO has ended; timeout is never evidence of settlement.
 * Removal persists an epoch tombstone conditionally. Replayed/superseded cleanup cannot delete a
 * subsequently configured generation. Save conditionally commits only against the current epoch.
 * No detached IO may continue after a receipt settles. Root serializes all writes through this port.
 *
 * Runtime epochs alone are not crash proof: if removal was not persisted before process death,
 * root bootstrap must validate host/lifecycle proof before returning Verified/Ready. A numeric ID,
 * restored ID, backup row or new process-local UUID is not that proof.
 */
interface AgendaWidgetConfigurationWriter {
    fun enqueueRemoval(epoch: WidgetEpoch): Deferred<WidgetRemovalReceipt>
    fun enqueueSave(epoch: WidgetEpoch, configuration: AgendaWidgetConfiguration): Deferred<WidgetSaveReceipt>
}
