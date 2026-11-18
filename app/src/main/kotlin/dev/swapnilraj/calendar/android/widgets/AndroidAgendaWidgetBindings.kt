package dev.swapnilraj.calendar.android.widgets

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import dev.swapnilraj.calendar.data.widgets.WidgetConfigurationActor
import dev.swapnilraj.calendar.core.CalendarReadState
import kotlinx.coroutines.*

/** Raw public options/membership access, not a host-incarnation witness. */
internal interface WidgetMarkerAccess {
    fun isOwnWidget(id: Int): Boolean
    fun marker(id: Int): String?
    fun marker(id: Int, value: String)
}
internal class AndroidWidgetMarkerAccess(context: Context, private val host: AgendaWidgetHost) : WidgetMarkerAccess {
    private val manager = AppWidgetManager.getInstance(context)
    private val provider = ComponentName(context, AgendaWidgetProvider::class.java)
    override fun isOwnWidget(id: Int) = id in host.widgetIds() && manager.getAppWidgetInfo(id)?.provider == provider
    override fun marker(id: Int): String? {
        @Suppress("DEPRECATION") val value = manager.getAppWidgetOptions(id).get(MARKER_KEY)
        require(value == null || value is String)
        return value as String?
    }
    override fun marker(id: Int, value: String) {
        manager.updateAppWidgetOptions(id, Bundle().apply { putString(MARKER_KEY, value) })
    }
    companion object { const val MARKER_KEY = "dev.swapnilraj.calendar.agenda.binding.v1" }
}
internal sealed interface WidgetBindingObservation {
    data class Matched(val epoch: WidgetEpoch) : WidgetBindingObservation
    data object SetupRequired : WidgetBindingObservation
    data object Unavailable : WidgetBindingObservation
}
internal sealed interface WidgetBindingSetupResult {
    data class Prepared(val epoch: WidgetEpoch) : WidgetBindingSetupResult
    data object Stale : WidgetBindingSetupResult
    data object Unavailable : WidgetBindingSetupResult
}

/** Trusted-framework/host scoped handshake only. Options are public to the host and are not durable
 * across system reboot on pinned AOSP; missing options require manual setup, never rehydration.
 * Same-ID malicious host replay cannot be universally distinguished through public Android APIs.
 * Root must wire quarantineBatch as Runtime.removalEpochs before enabling any provider/config UI.
 */
internal class AndroidAgendaWidgetBindings(
    private val ledger: WidgetBindingLedger,
    private val actor: WidgetConfigurationActor,
    private val markers: WidgetMarkerAccess,
    private val scope: CoroutineScope,
    private val setupTimeoutMillis: Long = 15_000,
) : AgendaWidgetSettings, AgendaWidgetConfigurationWriter {
    private val lock = Any()
    private var poisoned = false
    private val confirmed = mutableMapOf<Int, WidgetEpoch>()
    private val resets = mutableMapOf<Int, Pair<WidgetEpoch, Deferred<WidgetRemovalReceipt>>>()
    private val setupCapture = ThreadLocal<MutableMap<Int, WidgetEpoch>>()

    /** Whole-batch synchronous AtomicFile fence BEFORE any actor enqueue. Runtime uses this map. */
    fun quarantineBatch(ids: Set<Int>): Map<Int, WidgetEpoch> = synchronized(lock) {
        check(!poisoned)
        ids.forEach { actor.revokeValidation(it); confirmed.remove(it) }
        try { ledger.quarantine(ids).also { setupCapture.get()?.putAll(it) } }
        catch (failure: Exception) { poison(); throw failure }
    }
    override fun enqueueRemoval(epoch: WidgetEpoch): Deferred<WidgetRemovalReceipt> = synchronized(lock) {
        val result = try {
            check(!poisoned)
            if (ledger.snapshot().records[epoch.widgetId] != WidgetBindingRecord(epoch, WidgetBindingPhase.QUARANTINED))
                CompletableDeferred<WidgetRemovalReceipt>(WidgetRemovalReceipt.Superseded)
            else {
                actor.supplyValidatedEpoch(epoch, false)
                actor.enqueueRemoval(epoch)
            }
        } catch (_: Exception) { poison(); CompletableDeferred<WidgetRemovalReceipt>(WidgetRemovalReceipt.Unavailable) }
        resets[epoch.widgetId] = epoch to result
        result
    }
    override fun enqueueSave(epoch: WidgetEpoch, configuration: AgendaWidgetConfiguration): Deferred<WidgetSaveReceipt> {
        val accepted = synchronized(lock) {
            if (observeNow(epoch.widgetId) != WidgetBindingObservation.Matched(epoch)) return CompletableDeferred(WidgetSaveReceipt.Stale)
            actor.enqueueSave(epoch, configuration)
        }
        val result = CompletableDeferred<WidgetSaveReceipt>()
        // The actor's accepted IO and receipt are not children of the caller/broadcast.
        val checking = scope.launch {
            val receipt = try { accepted.await() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { WidgetSaveReceipt.Unavailable }
            result.complete(synchronized(lock) {
                if (observeNow(epoch.widgetId) == WidgetBindingObservation.Matched(epoch)) receipt else WidgetSaveReceipt.Stale
            })
        }
        checking.invokeOnCompletion { if (!result.isCompleted) result.complete(WidgetSaveReceipt.Unavailable) }
        return result
    }

    /** Only explicit setup mints a fresh runtime/ledger epoch. Candidate phases never auto-resume. */
    suspend fun prepareForSetup(id: Int, runtime: AgendaWidgetRuntime): WidgetBindingSetupResult = try {
        withContext(Dispatchers.IO) {
            withTimeout(setupTimeoutMillis) {
                if (!markers.isOwnWidget(id)) return@withTimeout WidgetBindingSetupResult.Stale
                // Runtime invokes the fence synchronously on this call stack; capture exactly
                // this reset, never a newer concurrent setup's epoch. No lock inversion with runtime.
                val capture = mutableMapOf<Int, WidgetEpoch>()
                val previous = setupCapture.get()
                setupCapture.set(capture)
                try { runtime.removed(setOf(id)) } finally { setupCapture.set(previous) }
                val requested = capture[id] ?: return@withTimeout WidgetBindingSetupResult.Unavailable
                val (epoch, receipt) = synchronized(lock) {
                    check(!poisoned)
                    val current = requireNotNull(resets[id])
                    if (current.first != requested) return@synchronized null
                    check(ledger.snapshot().records[id] == WidgetBindingRecord(current.first, WidgetBindingPhase.QUARANTINED))
                    current
                } ?: return@withTimeout WidgetBindingSetupResult.Stale
                if (receipt.await() != WidgetRemovalReceipt.Committed(epoch)) return@withTimeout WidgetBindingSetupResult.Unavailable
                currentCoroutineContext().ensureActive()
                synchronized(lock) {
                    check(!poisoned)
                    ledger.transition(epoch, WidgetBindingPhase.QUARANTINED, WidgetBindingPhase.CANDIDATE)
                    if (!markers.isOwnWidget(id)) return@synchronized WidgetBindingSetupResult.Stale
                    val namespace = ledger.snapshot().namespace
                    markers.marker(id, marker(namespace, epoch))
                    if (!markers.isOwnWidget(id) || markers.marker(id) != marker(namespace, epoch))
                        return@synchronized WidgetBindingSetupResult.Stale
                    ledger.transition(epoch, WidgetBindingPhase.CANDIDATE, WidgetBindingPhase.ACTIVE)
                    if (observeNow(id) != WidgetBindingObservation.Matched(epoch)) WidgetBindingSetupResult.Stale
                    else WidgetBindingSetupResult.Prepared(epoch)
                }
            }
        }
    } catch (_: TimeoutCancellationException) { WidgetBindingSetupResult.Unavailable }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { WidgetBindingSetupResult.Unavailable }

    fun observe(id: Int): WidgetBindingObservation = synchronized(lock) { observeNow(id) }
    private fun observeNow(id: Int): WidgetBindingObservation = try {
        check(!poisoned)
        val snapshot = ledger.snapshot()
        val record = snapshot.records[id]
        if (record?.phase != WidgetBindingPhase.ACTIVE || !markers.isOwnWidget(id) ||
            markers.marker(id) != marker(snapshot.namespace, record.epoch)) {
            if (confirmed.remove(id) != null) actor.revokeValidation(id)
            WidgetBindingObservation.SetupRequired
        } else {
            if (confirmed[id] != record.epoch) {
                actor.supplyValidatedEpoch(record.epoch, true); confirmed[id] = record.epoch
            }
            WidgetBindingObservation.Matched(record.epoch)
        }
    } catch (_: Exception) {
        if (confirmed.remove(id) != null) actor.revokeValidation(id)
        WidgetBindingObservation.Unavailable
    }
    override suspend fun lifecycle(widgetId: Int): WidgetLifecycleProof {
        val first = observe(widgetId)
        if (first == WidgetBindingObservation.Unavailable) return WidgetLifecycleProof.Unavailable
        if (first !is WidgetBindingObservation.Matched) return WidgetLifecycleProof.SetupRequired
        val result = actor.lifecycle(widgetId)
        return if (observe(widgetId) == first) result else WidgetLifecycleProof.SetupRequired
    }
    override suspend fun configuration(widgetId: Int): AgendaWidgetConfigurationState {
        val first = observe(widgetId)
        if (first == WidgetBindingObservation.Unavailable) return AgendaWidgetConfigurationState.Unavailable
        if (first !is WidgetBindingObservation.Matched) return AgendaWidgetConfigurationState.SetupRequired
        val result = actor.configuration(widgetId)
        return if (observe(widgetId) == first) result else AgendaWidgetConfigurationState.SetupRequired
    }
    override suspend fun environment(): CalendarReadState<AgendaWidgetEnvironment> = actor.environment()
    private fun poison() {
        poisoned = true
        confirmed.keys.toList().forEach(actor::revokeValidation)
        confirmed.clear()
    }
    companion object {
        private fun marker(namespace: String, epoch: WidgetEpoch) = Uri.Builder().scheme("daylight-widget-binding")
            .authority("v1").appendPath(namespace).appendPath(epoch.widgetId.toString()).appendPath(epoch.token).build().toString()
    }
}
