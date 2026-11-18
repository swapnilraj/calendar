package dev.swapnilraj.calendar.android.widgets

import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID
import dev.swapnilraj.calendar.core.*
import java.time.Clock
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** No provider engine, settings defaults, manifest registration, or autonomous application scope.
 * Root wires a bounded shared CalendarReader snapshot adapter and an independent widget job queue.
 */
class AgendaWidgetRuntime(
    private val source: AgendaWidgetSnapshotReader,
    private val settings: AgendaWidgetSettings,
    private val writer: AgendaWidgetConfigurationWriter,
    private val scheduler: AgendaWidgetRefreshScheduler,
    private val host: AgendaWidgetHost,
    private val renderer: AgendaWidgetRenderer,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
    private val timeoutMillis: Long = 15_000,
    private val removalEpochs: (Set<Int>) -> Map<Int, WidgetEpoch> = { ids ->
        ids.associateWith { WidgetEpoch(it, UUID.randomUUID().toString()) }
    },
) {
    private val mutableChanges = MutableStateFlow(0L)
    /** A revision trigger, not authority or a successful IO receipt. Readers must revalidate proof. */
    val changes: StateFlow<Long> = mutableChanges.asStateFlow()
    private fun changed() { mutableChanges.update { it + 1 } }
    private val revisions = ConcurrentHashMap<Int, Long>()
    private data class Removal(val epoch: WidgetEpoch, val receipt: Deferred<WidgetRemovalReceipt>,
        val display: AgendaWidgetDisplay = AgendaWidgetDisplay.SetupRequired)
    private val removals = ConcurrentHashMap<Int, Removal>()
    private val epochs = ConcurrentHashMap<Int, WidgetEpoch>()
    private val cleanup = mutableMapOf<Int, MutableList<Deferred<WidgetRemovalReceipt>>>()
    private data class SaveAttempt(val epoch: WidgetEpoch, val serial: Long, val display: AgendaWidgetDisplay = AgendaWidgetDisplay.Loading)
    private val saves = ConcurrentHashMap<Int, SaveAttempt>()
    private var nextSaveSerial = 0L
    private val publication = Any()
    private val blocked = ConcurrentHashMap.newKeySet<Int>()
    private val seenEpochTokens = mutableSetOf<String>()
    init { require(timeoutMillis > 0) }

    /** Called synchronously by widget callbacks/settings changes, before reading possibly slow data. */
    fun invalidate(widgetIds: Set<Int> = host.widgetIds()): Boolean {
        val ids = widgetIds.intersect(host.widgetIds()).filter { it > 0 }.toSet()
        synchronized(publication) {
            ids.forEach { id -> revisions.merge(id, 1L, Long::plus); publish(id, AgendaWidgetDisplay.Loading) }
            if (ids.isNotEmpty()) changed()
        }
        val scheduled = ids.isEmpty() || try { scheduler.requestRefresh(ids) } catch (_: RuntimeException) { false }
        if (!scheduled) synchronized(publication) { ids.forEach { publish(it, AgendaWidgetDisplay.Unavailable) } }
        return scheduled
    }

    /** Called by a bounded job on the application's IO scope. Failure never retains old event text. */
    suspend fun refresh(widgetIds: Set<Int> = host.widgetIds()): Boolean {
        var successful = true
        for (id in widgetIds.intersect(host.widgetIds()).filter { it > 0 }) {
            val revision = synchronized(publication) {
                revisions.merge(id, 1L, Long::plus)!!.also { publish(id, AgendaWidgetDisplay.Loading) }
            }
            val display = try { withTimeout(timeoutMillis) { load(id) } }
            catch (_: TimeoutCancellationException) { AgendaWidgetDisplay.Unavailable }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { AgendaWidgetDisplay.Unavailable }
            currentCoroutineContext().ensureActive()
            synchronized(publication) {
                if (id !in host.widgetIds() || revisions[id] != revision) successful = false
                else if (!publish(id, display)) successful = false
            }
            if (display == AgendaWidgetDisplay.Unavailable) successful = false
        }
        return successful
    }

    /** Registration, quarantine and the actor enqueue happen synchronously before any coroutine.
     * Broadcast timeout ends only the broadcast; it neither cancels nor settles shared cleanup IO.
     */
    fun removed(widgetIds: Set<Int>, finished: () -> Unit = {}): Job {
        val registrations = synchronized(publication) {
            val ids = widgetIds.filter { it > 0 }.toSet()
            epochs.values.forEach { seenEpochTokens += it.token }
            val issued = try {
                removalEpochs(ids.toSet()).toMap().also { supplied ->
                    require(supplied.keys == ids)
                    require(supplied.all { (id, epoch) -> epoch.widgetId == id && epoch.token !in seenEpochTokens })
                    require(supplied.values.map { it.token }.toSet().size == ids.size)
                }
            } catch (_: Exception) {
                ids.forEach { id ->
                    revisions.merge(id, 1L, Long::plus)
                    saves.remove(id); epochs.remove(id); removals.remove(id)
                    blocked += id
                    publish(id, AgendaWidgetDisplay.Unavailable)
                }
                emptyMap()
            }
            issued.map { (id, epoch) ->
                seenEpochTokens += epoch.token
                blocked.remove(id)
                epochs[id] = epoch
                saves.remove(id)
                revisions.merge(id, 1L, Long::plus)
                publish(id, AgendaWidgetDisplay.SetupRequired)
                val receipt = try { writer.enqueueRemoval(epoch) }
                    catch (_: Exception) { CompletableDeferred<WidgetRemovalReceipt>(WidgetRemovalReceipt.Unavailable) }
                val removal = Removal(epoch, receipt)
                removals[id] = removal
                cleanup.getOrPut(id) { mutableListOf() }.add(receipt)
                receipt.invokeOnCompletion { synchronized(publication) { changed() } }
                removal
            }.also { if (ids.isNotEmpty()) changed() }
        }
        val once = AtomicBoolean()
        fun finishOnce() { if (once.compareAndSet(false, true)) finished() }
        val job = scope.launch {
            registrations.forEach { registration ->
                val result = try { registration.receipt.await() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { WidgetRemovalReceipt.Unavailable }
                synchronized(publication) {
                    val id = registration.epoch.widgetId
                    val current = removals[id]
                    if (epochs[id] == registration.epoch && current?.epoch == registration.epoch) {
                        val display = if (result == WidgetRemovalReceipt.Committed(registration.epoch))
                            AgendaWidgetDisplay.SetupRequired else AgendaWidgetDisplay.Unavailable
                        removals[id] = current.copy(display = display)
                        publish(id, display)
                        changed()
                    }
                }
            }
        }
        val handler = Handler(Looper.getMainLooper())
        val deadline = Runnable { finishOnce() }
        handler.postDelayed(deadline, 8_000)
        job.invokeOnCompletion { handler.removeCallbacks(deadline); finishOnce() }
        return job
    }

    suspend fun beginConfiguration(widgetId: Int): WidgetConfigurationSessionResult = (try {
        withTimeout(timeoutMillis) {
            require(widgetId in host.widgetIds())
            if (widgetId in blocked) return@withTimeout WidgetConfigurationSessionResult.Unavailable
            val known = synchronized(publication) { epochs[widgetId] }
            if (known != null && removals.containsKey(widgetId))
                return@withTimeout WidgetConfigurationSessionResult.Ready(WidgetConfigurationSession(known))
            val proof = settings.lifecycle(widgetId)
            val verified = (proof as? WidgetLifecycleProof.Verified)?.epoch
            if (proof == WidgetLifecycleProof.Unavailable) WidgetConfigurationSessionResult.Unavailable
            else if (verified == null || verified.widgetId != widgetId) WidgetConfigurationSessionResult.SetupRequired
            else synchronized(publication) {
                if (widgetId in blocked) return@synchronized WidgetConfigurationSessionResult.Unavailable
                val expected = epochs.putIfAbsent(widgetId, verified) ?: verified
                seenEpochTokens += expected.token
                if (expected != verified) WidgetConfigurationSessionResult.SetupRequired
                else WidgetConfigurationSessionResult.Ready(WidgetConfigurationSession(expected))
            }
        }
    } catch (_: TimeoutCancellationException) { WidgetConfigurationSessionResult.Unavailable }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { WidgetConfigurationSessionResult.Unavailable }).also {
        if (it is WidgetConfigurationSessionResult.Ready) synchronized(publication) { changed() }
    }

    /** All UI writes use this path. No raw save callback can remove quarantine. Awaiting a receipt
     * does not own/cancel the actor operation; a bounded UI timeout leaves shared cleanup pending.
     */
    suspend fun saveFreshConfiguration(session: WidgetConfigurationSession, choice: WidgetConfigurationChoice): WidgetConfigurationSaveResult {
        val epoch = session.epoch
        val attempt = synchronized(publication) {
            if (epochs[epoch.widgetId] != epoch) return WidgetConfigurationSaveResult.Stale
            SaveAttempt(epoch, ++nextSaveSerial).also {
                saves[epoch.widgetId] = it
                revisions.merge(epoch.widgetId, 1L, Long::plus)
                publish(epoch.widgetId, AgendaWidgetDisplay.Loading)
                changed()
            }
        }
        val result = try {
            withTimeout(timeoutMillis) {
                val barriers = synchronized(publication) { cleanup[epoch.widgetId]?.toList().orEmpty() }
                var latest: WidgetRemovalReceipt? = null
                barriers.forEach { latest = it.await() }
                if (barriers.isNotEmpty() && latest != WidgetRemovalReceipt.Committed(epoch))
                    return@withTimeout WidgetConfigurationSaveResult.Unavailable
                if (epoch.widgetId !in host.widgetIds()) return@withTimeout WidgetConfigurationSaveResult.Stale
                if (settings.lifecycle(epoch.widgetId) != WidgetLifecycleProof.Verified(epoch))
                    return@withTimeout WidgetConfigurationSaveResult.Unavailable
                val selection = when (val selected = choice.selection) {
                    WidgetCalendarSelection.FollowApp -> selected
                    is WidgetCalendarSelection.Explicit -> WidgetCalendarSelection.Explicit(selected.ids.toSet())
                }
                if (!validWidgetPins(selection, choice.expectedCalendarIdentities)) return@withTimeout WidgetConfigurationSaveResult.Unavailable
                val configuration = AgendaWidgetConfiguration(UUID.randomUUID().toString(), selection, choice.privacy,
                    choice.expectedCalendarIdentities.toMap())
                val receipt = synchronized(publication) {
                    if (!current(attempt)) return@withTimeout WidgetConfigurationSaveResult.Stale
                    writer.enqueueSave(epoch, configuration).also { receipt ->
                        // Independent of the UI coroutine: even an unknown/cancelled ACK must
                        // invalidate readers once the accepted durable operation actually ends.
                        receipt.invokeOnCompletion { synchronized(publication) { changed() } }
                    }
                }
                val committed = receipt.await()
                currentCoroutineContext().ensureActive()
                if (committed != WidgetSaveReceipt.Committed(epoch, configuration.generation))
                    return@withTimeout if (committed == WidgetSaveReceipt.Stale) WidgetConfigurationSaveResult.Stale else WidgetConfigurationSaveResult.Unavailable
                val stored = settings.configuration(epoch.widgetId)
                val proof = settings.lifecycle(epoch.widgetId)
                synchronized(publication) {
                    if (!current(attempt)) return@withTimeout WidgetConfigurationSaveResult.Stale
                    if (stored != AgendaWidgetConfigurationState.Ready(configuration, epoch) || proof != WidgetLifecycleProof.Verified(epoch))
                        return@withTimeout WidgetConfigurationSaveResult.Unavailable
                    removals.remove(epoch.widgetId)
                    cleanup.remove(epoch.widgetId)
                    saves.remove(epoch.widgetId)
                    invalidate(setOf(epoch.widgetId))
                    WidgetConfigurationSaveResult.Saved(configuration)
                }
            }
        } catch (_: TimeoutCancellationException) { WidgetConfigurationSaveResult.Unavailable }
        catch (cancelled: CancellationException) {
            failSave(attempt)
            throw cancelled
        } catch (_: Exception) { WidgetConfigurationSaveResult.Unavailable }
        if (result !is WidgetConfigurationSaveResult.Saved) failSave(attempt)
        return result
    }

    private fun current(attempt: SaveAttempt) = epochs[attempt.epoch.widgetId] == attempt.epoch && saves[attempt.epoch.widgetId]?.serial == attempt.serial
    private fun failSave(attempt: SaveAttempt) = synchronized(publication) {
        if (current(attempt)) {
            saves[attempt.epoch.widgetId] = attempt.copy(display = AgendaWidgetDisplay.Unavailable)
            publish(attempt.epoch.widgetId, AgendaWidgetDisplay.Unavailable)
            changed()
        }
    }

    private suspend fun load(id: Int): AgendaWidgetDisplay {
        if (id in blocked) return AgendaWidgetDisplay.Unavailable
        removals[id]?.let { return it.display }
        saves[id]?.let { return it.display }
        val state = settings.configuration(id)
        if (state == AgendaWidgetConfigurationState.SetupRequired) return AgendaWidgetDisplay.SetupRequired
        val stored = state as? AgendaWidgetConfigurationState.Ready ?: return AgendaWidgetDisplay.Unavailable
        val proof = settings.lifecycle(id)
        if (proof == WidgetLifecycleProof.Unavailable) return AgendaWidgetDisplay.Unavailable
        val verified = (proof as? WidgetLifecycleProof.Verified)?.epoch ?: return AgendaWidgetDisplay.SetupRequired
        if (stored.epoch != verified || verified.widgetId != id) return AgendaWidgetDisplay.SetupRequired
        val expected = synchronized(publication) {
            if (id in blocked) return AgendaWidgetDisplay.Unavailable
            (epochs.putIfAbsent(id, verified) ?: verified).also { seenEpochTokens += it.token }
        }
        if (expected != verified) return AgendaWidgetDisplay.SetupRequired
        val config = stored.value
        if (!validWidgetPins(config.selection, config.expectedCalendarIdentities)) return AgendaWidgetDisplay.SetupRequired
        val environment = when (val state = settings.environment()) {
            CalendarReadState.PermissionRequired -> return AgendaWidgetDisplay.PermissionRequired
            is CalendarReadState.Ready -> state.value
            else -> return AgendaWidgetDisplay.Unavailable
        }
        val today = clock.instant().atZone(environment.displayZone).toLocalDate()
        require(today.year in 1..9999)
        val selection = when (val choice = config.selection) {
            WidgetCalendarSelection.FollowApp -> environment.appSelection
            is WidgetCalendarSelection.Explicit -> CalendarSelection.Explicit(choice.ids)
        }
        val range = CalendarDateRange(today, minOf(today.plusDays(7), LocalDate.of(10000, 1, 1)))
        val request = CalendarRequest(range, environment.displayZone, selection)
        val result = if (selection is CalendarSelection.Explicit && selection.ids.isEmpty()) {
            CalendarReadState.Ready(CalendarSnapshot(request, emptyList(), emptyList()))
        } else source.read(request)
        val display = when (result) {
            CalendarReadState.PermissionRequired -> AgendaWidgetDisplay.PermissionRequired
            is CalendarReadState.Ready -> {
                require(result.value.request == request)
                val snapshot = result.value
                if (!widgetPinsAuthorize(config, snapshot.calendars)) return AgendaWidgetDisplay.SetupRequired
                val allowed = when (selection) {
                    is CalendarSelection.Explicit -> selection.ids
                    CalendarSelection.ProviderVisible -> snapshot.calendars.filter { it.providerVisible }.map { it.id }.toSet()
                }
                val now = clock.instant()
                val rows = snapshot.occurrences.asSequence()
                    .filter { it.ref.calendarId in allowed && (environment.showDeclined || !it.declined) }
                    .filter { it.time.intersects(range, environment.displayZone) }
                    .filter { event -> when (val time = event.time) {
                        is EventTime.AllDay -> true
                        is EventTime.Timed -> if (time.start == time.end) time.start >= now else time.end > now
                    } }
                    .distinctBy { it.ref }
                    .map { event -> AgendaWidgetRow(event, when (val time = event.time) {
                        is EventTime.AllDay -> maxOf(today, time.dates.start)
                        is EventTime.Timed -> maxOf(today, time.start.atZone(environment.displayZone).toLocalDate())
                    }, snapshot.calendars.singleOrNull { it.id == event.ref.calendarId }?.accessLevel
                        ?.let { it < android.provider.CalendarContract.Calendars.CAL_ACCESS_READ } != false,
                        snapshot.calendars.singleOrNull { it.id == event.ref.calendarId }?.identity?.let(::widgetCalendarFingerprint)) }.sortedWith(compareBy<AgendaWidgetRow> { it.date }
                        .thenBy { it.occurrence.time !is EventTime.AllDay }
                        .thenBy { it.occurrence.ref.instanceStart }
                        .thenBy { it.occurrence.ref.calendarId.value }
                        .thenBy { it.occurrence.ref.eventId }
                        .thenBy { it.occurrence.ref.instanceEnd })
                    .take(renderer.rowCapacity(host.size(id)) + 1).toList()
                AgendaWidgetDisplay.Ready(config, environment, today, rows.take(renderer.rowCapacity(host.size(id))),
                    rows.size > renderer.rowCapacity(host.size(id)), selection is CalendarSelection.Explicit && selection.ids.isEmpty(),
                    snapshot.coverage.isNotEmpty())
            }
            else -> AgendaWidgetDisplay.Unavailable
        }
        // A preference failure/change during the query cannot publish old titles or old filters.
        val latestEnvironment = when (val latest = settings.environment()) {
            CalendarReadState.PermissionRequired -> return AgendaWidgetDisplay.PermissionRequired
            is CalendarReadState.Ready -> latest.value
            else -> return AgendaWidgetDisplay.Unavailable
        }
        if (clock.instant().atZone(environment.displayZone).toLocalDate() != today ||
            settings.configuration(id) != state || settings.lifecycle(id) != WidgetLifecycleProof.Verified(expected) ||
            latestEnvironment != environment) {
            scheduler.requestRefresh(setOf(id))
            return AgendaWidgetDisplay.Unavailable
        }
        return display
    }

    private fun publish(id: Int, display: AgendaWidgetDisplay): Boolean = try {
        if (id in host.widgetIds()) host.publish(id, renderer.render(id, host.size(id), display))
        true
    } catch (_: RuntimeException) { false }
}
