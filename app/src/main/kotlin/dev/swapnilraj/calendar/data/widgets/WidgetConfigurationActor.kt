package dev.swapnilraj.calendar.data.widgets

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.swapnilraj.calendar.android.widgets.*
import dev.swapnilraj.calendar.core.CalendarReadState
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first

/** Create once per application/file. Root must supply a backup-excluded installation namespace.
 * Context.noBackupFilesDir excludes this document from normal Android backup, not manual copying.
 */
internal fun widgetConfigurationDataStore(context: Context, scope: CoroutineScope): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(scope = scope) { File(context.noBackupFilesDir, "agenda_widgets.preferences_pb") }

/** Internal persistence slice, not a host witness implementation. Root alone may supply confirmed
 * epochs after its concrete marker/bootstrap handshake. Numeric IDs, stored rows and random UUIDs
 * never confirm themselves. Missing custom host options after reboot require fresh setup; malicious
 * host replay is not universally preventable with public Android APIs. No device proof is claimed.
 *
 * One actor owns FIFO IO independently of caller/broadcast cancellation. Receipts settle only after
 * DataStore.edit returns (durable commit) or throws (operation ended). closeAndJoin drains accepted
 * commands; it does not cancel them. Do not cancel the DataStore's scope before that drain finishes.
 */
internal class WidgetConfigurationActor(
    private val store: DataStore<Preferences>,
    private val namespace: String,
    private val readEnvironment: suspend () -> CalendarReadState<AgendaWidgetEnvironment> = { CalendarReadState.Unavailable },
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AgendaWidgetConfigurationWriter, AgendaWidgetSettings {
    private data class Binding(val epoch: WidgetEpoch, val active: Boolean)
    private sealed class Command(val sequence: Long, val epoch: WidgetEpoch) {
        class Remove(sequence: Long, epoch: WidgetEpoch, val receipt: CompletableDeferred<WidgetRemovalReceipt>) : Command(sequence, epoch)
        class Save(sequence: Long, epoch: WidgetEpoch, val configuration: AgendaWidgetConfiguration,
            val receipt: CompletableDeferred<WidgetSaveReceipt>) : Command(sequence, epoch)
    }
    private val lock = Any()
    private val bindings = mutableMapOf<Int, Binding>()
    private val pending = mutableMapOf<Int, Long>()
    private var serial = 0L
    private var closed = false
    private val queue = Channel<Command>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val worker: Job
    init {
        require(namespace.isNotBlank())
        worker = scope.launch { for (command in queue) execute(command) }
    }

    /** Trusted internal bootstrap input, not evidence produced/validated by this backend.
     * inactive authorizes deletion cleanup but never Ready/Verified or configuration saves.
     */
    fun supplyValidatedEpoch(epoch: WidgetEpoch, active: Boolean) = synchronized(lock) {
        check(!closed)
        bindings[epoch.widgetId] = Binding(epoch, active)
        serial++
    }
    fun revokeValidation(widgetId: Int) = synchronized(lock) { bindings.remove(widgetId); serial++ }

    override fun enqueueRemoval(epoch: WidgetEpoch): Deferred<WidgetRemovalReceipt> = synchronized(lock) {
        val result = CompletableDeferred<WidgetRemovalReceipt>() // No caller Job parent.
        if (closed) { result.complete(WidgetRemovalReceipt.Unavailable); return@synchronized result }
        val command = Command.Remove(++serial, epoch, result)
        pending[epoch.widgetId] = command.sequence
        check(queue.trySend(command).isSuccess)
        result
    }
    override fun enqueueSave(epoch: WidgetEpoch, configuration: AgendaWidgetConfiguration): Deferred<WidgetSaveReceipt> = synchronized(lock) {
        val result = CompletableDeferred<WidgetSaveReceipt>()
        if (closed) { result.complete(WidgetSaveReceipt.Unavailable); return@synchronized result }
        val selected = when (val selection = configuration.selection) {
            WidgetCalendarSelection.FollowApp -> selection
            is WidgetCalendarSelection.Explicit -> WidgetCalendarSelection.Explicit(selection.ids.toSet())
        }
        val command = Command.Save(++serial, epoch, configuration.copy(selection = selected), result)
        pending[epoch.widgetId] = command.sequence
        check(queue.trySend(command).isSuccess)
        result
    }

    private suspend fun execute(command: Command) {
        var removal: WidgetRemovalReceipt = WidgetRemovalReceipt.Unavailable
        var save: WidgetSaveReceipt = WidgetSaveReceipt.Unavailable
        try {
            store.edit { values ->
                val decoded = WidgetConfigurationDocuments.decode(values[WidgetConfigurationDocuments.key], namespace)
                val document = (decoded as? WidgetDocumentRead.Ready)?.document ?: error("Unavailable widget document")
                val binding = synchronized(lock) { bindings[command.epoch.widgetId] }
                if (binding == null || binding.epoch != command.epoch) {
                    removal = WidgetRemovalReceipt.Superseded; save = WidgetSaveReceipt.Stale
                    return@edit
                }
                val current = document.rows[command.epoch.widgetId]
                val next = when (command) {
                    is Command.Remove -> {
                        removal = WidgetRemovalReceipt.Committed(command.epoch)
                        // Same epoch means this reset already happened. Preserve any later save.
                        if (current?.epoch == command.epoch) requireNotNull(current) else WidgetConfigurationRow(command.epoch, null)
                    }
                    is Command.Save -> {
                        if (!binding.active || current == null || current.epoch != command.epoch ||
                            (current.configuration?.generation == command.configuration.generation && current.configuration != command.configuration)) {
                            save = WidgetSaveReceipt.Stale; return@edit
                        }
                        save = WidgetSaveReceipt.Committed(command.epoch, command.configuration.generation)
                        WidgetConfigurationRow(command.epoch, command.configuration)
                    }
                }
                values[WidgetConfigurationDocuments.key] = WidgetConfigurationDocuments.encode(document.copy(
                    rows = document.rows + (command.epoch.widgetId to next)))
            }
            synchronized(lock) {
                if (bindings[command.epoch.widgetId]?.epoch != command.epoch) {
                    removal = WidgetRemovalReceipt.Superseded; save = WidgetSaveReceipt.Stale
                }
            }
        } catch (_: Exception) {
            removal = WidgetRemovalReceipt.Unavailable; save = WidgetSaveReceipt.Unavailable
        }
        synchronized(lock) {
            if (pending[command.epoch.widgetId] == command.sequence) pending.remove(command.epoch.widgetId)
        }
        when (command) {
            is Command.Remove -> command.receipt.complete(removal)
            is Command.Save -> command.receipt.complete(save)
        }
    }

    private data class Read(val binding: Binding?, val pending: Boolean, val serial: Long)
    private fun readState(id: Int) = synchronized(lock) { Read(bindings[id], closed || pending.containsKey(id), serial) }
    private suspend fun row(id: Int): Pair<Read, WidgetConfigurationRow?>? {
        val before = readState(id)
        if (before.pending) return null
        val decoded = WidgetConfigurationDocuments.decode(store.data.first()[WidgetConfigurationDocuments.key], namespace)
        val document = (decoded as? WidgetDocumentRead.Ready)?.document ?: return null
        if (before != readState(id)) return null
        return before to document.rows[id]
    }
    override suspend fun configuration(widgetId: Int): AgendaWidgetConfigurationState { return try {
        val (state, row) = row(widgetId) ?: return AgendaWidgetConfigurationState.Unavailable
        val binding = state.binding
        if (binding == null || !binding.active || row?.epoch != binding.epoch) AgendaWidgetConfigurationState.SetupRequired
        else row.configuration?.let { AgendaWidgetConfigurationState.Ready(it, row.epoch) } ?: AgendaWidgetConfigurationState.SetupRequired
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { AgendaWidgetConfigurationState.Unavailable } }

    override suspend fun lifecycle(widgetId: Int): WidgetLifecycleProof { return try {
        val (state, row) = row(widgetId) ?: return WidgetLifecycleProof.Unavailable
        val binding = state.binding
        if (binding == null || !binding.active || row?.epoch != binding.epoch) WidgetLifecycleProof.SetupRequired
        else WidgetLifecycleProof.Verified(binding.epoch)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { WidgetLifecycleProof.Unavailable } }
    override suspend fun environment(): CalendarReadState<AgendaWidgetEnvironment> = try { readEnvironment() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { CalendarReadState.Unavailable }

    suspend fun closeAndJoin() {
        synchronized(lock) { closed = true; queue.close() }
        worker.join()
        scope.cancel()
    }
}
