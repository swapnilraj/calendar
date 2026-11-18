package dev.swapnilraj.calendar.android.widgets

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.widgets.WidgetConfigurationActor
import dev.swapnilraj.calendar.data.widgets.widgetConfigurationDataStore
import dev.swapnilraj.calendar.ui.widgets.WidgetSetupChoice
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow

interface AgendaWidgetApplicationOwner : AgendaWidgetFrameworkOwner {
    val agendaWidgetServices: AgendaWidgetApplicationServices
}

sealed interface AgendaWidgetRouteResult {
    data class Ready(val route: AgendaWidgetRoute, val environment: AgendaWidgetEnvironment) : AgendaWidgetRouteResult
    data class SetupRequired(val widgetId: Int) : AgendaWidgetRouteResult
    data object PermissionRequired : AgendaWidgetRouteResult
    data object Stale : AgendaWidgetRouteResult
    data object Unavailable : AgendaWidgetRouteResult
    data object Invalid : AgendaWidgetRouteResult
}

/** Application lifetime glue. The injected one-shot reads must run the shared reader's bounded
 * queries, not merely take a potentially replayed flow value. No widget operation prompts by itself.
 */
class AgendaWidgetApplicationServices internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val snapshotReader: AgendaWidgetSnapshotReader,
    private val observeMetadata: () -> Flow<CalendarReadState<List<CalendarInfo>>>,
    private val readMetadata: suspend () -> CalendarReadState<List<CalendarInfo>>,
    private val readEnvironment: suspend () -> CalendarReadState<AgendaWidgetEnvironment>,
    mainActivity: ComponentName,
    private val host: AgendaWidgetHost = AndroidAgendaWidgetHost(context),
    private val markers: WidgetMarkerAccess = AndroidWidgetMarkerAccess(context, host),
    private val clock: Clock = Clock.systemUTC(),
    bootstrapDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val bindingLock = Any()
    private val bindingLedger by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { WidgetBindingLedger.open(context.noBackupFilesDir) }
    private val pendingRemovals = mutableListOf<Pair<WidgetEpoch, CompletableDeferred<WidgetRemovalReceipt>>>()
    private var bootstrapFailed = false
    @Volatile private var actor: WidgetConfigurationActor? = null
    @Volatile private var bindings: AndroidAgendaWidgetBindings? = null
    private val bootstrap: Deferred<AndroidAgendaWidgetBindings?> = scope.async(bootstrapDispatcher) {
        try {
            // Order matters: creating DataStore first would make a new installation look orphaned.
            val ledger = bindingLedger ?: run { failPendingRemovals(); return@async null }
            val actor = WidgetConfigurationActor(widgetConfigurationDataStore(context, scope), ledger.snapshot().namespace, readEnvironment).also { this@AgendaWidgetApplicationServices.actor = it }
            synchronized(bindingLock) {
                AndroidAgendaWidgetBindings(ledger, actor, markers, scope).also { ready ->
                    // Register all previously accepted removals before any ready writer can accept
                    // newer work. Epoch checks still guard stale receipts; no delayed reset adoption.
                    pendingRemovals.forEach { (epoch, result) -> bridge(ready.enqueueRemoval(epoch), result) }
                    pendingRemovals.clear()
                    bindings = ready
                }
            }
        } catch (cancelled: CancellationException) { failPendingRemovals(); throw cancelled }
        catch (_: Exception) { failPendingRemovals(); null }
    }
    private val settings = object : AgendaWidgetSettings {
        override suspend fun configuration(widgetId: Int) = bootstrap.await()?.configuration(widgetId) ?: AgendaWidgetConfigurationState.Unavailable
        override suspend fun lifecycle(widgetId: Int) = bootstrap.await()?.lifecycle(widgetId) ?: WidgetLifecycleProof.Unavailable
        override suspend fun environment() = readEnvironment()
    }
    private val writer = object : AgendaWidgetConfigurationWriter {
        override fun enqueueRemoval(epoch: WidgetEpoch): Deferred<WidgetRemovalReceipt> = synchronized(bindingLock) {
            bindings?.let { return@synchronized it.enqueueRemoval(epoch) }
            val receipt = CompletableDeferred<WidgetRemovalReceipt>()
            if (bootstrapFailed) receipt.complete(WidgetRemovalReceipt.Unavailable)
            else pendingRemovals += epoch to receipt
            receipt
        }
        override fun enqueueSave(epoch: WidgetEpoch, configuration: AgendaWidgetConfiguration) = bindings?.enqueueSave(epoch, configuration)
            ?: CompletableDeferred<WidgetSaveReceipt>(WidgetSaveReceipt.Unavailable)
    }
    private fun bridge(accepted: Deferred<WidgetRemovalReceipt>, result: CompletableDeferred<WidgetRemovalReceipt>) {
        val forwarding = scope.launch { result.complete(accepted.await()) }
        forwarding.invokeOnCompletion { if (!result.isCompleted) result.complete(WidgetRemovalReceipt.Unavailable) }
    }
    private fun failPendingRemovals() = synchronized(bindingLock) {
        bootstrapFailed = true
        pendingRemovals.forEach { it.second.complete(WidgetRemovalReceipt.Unavailable) }
        pendingRemovals.clear()
    }
    private val jobs = AndroidAgendaWidgetJobs(context, ComponentName(context, AgendaWidgetJobService::class.java), clock)
    val runtime = AgendaWidgetRuntime(snapshotReader, settings, writer, jobs, host,
        AgendaWidgetRenderer(context, mainActivity), scope, clock,
        removalEpochs = { ids -> synchronized(bindingLock) {
            // The tiny synchronous durability fence may wait for the same IO-opened ledger.
            // It must precede actor enqueue even on a cold-start removal/restore callback.
            bindings?.quarantineBatch(ids) ?: checkNotNull(bindingLedger) { "Widget ledger unavailable" }.quarantine(ids)
        } })
    val framework = AgendaWidgetFramework(RuntimeAgendaWidgetWork(runtime), host, jobs, scope,
        { context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED },
        { (readEnvironment() as? CalendarReadState.Ready)?.value?.displayZone }, clock)

    init {
        bootstrap.invokeOnCompletion { if (it != null) failPendingRemovals() }
        scope.launch { bootstrap.await(); framework.onProcessStarted() }
    }
    internal suspend fun closeAndJoin() { bootstrap.await(); actor?.closeAndJoin() }
    fun observeCalendars(): Flow<CalendarReadState<List<CalendarInfo>>> = observeMetadata()
    suspend fun configuration(widgetId: Int) = settings.configuration(widgetId)
    suspend fun environment(): CalendarReadState<AgendaWidgetEnvironment> = readEnvironment()
    fun isOwnWidget(widgetId: Int): Boolean = try { widgetId > 0 && markers.isOwnWidget(widgetId) } catch (_: Exception) { false }

    /** The metadata displayed at Save authorizes identities, not merely reusable numeric IDs. */
    suspend fun configure(widgetId: Int, choice: WidgetSetupChoice,
        authorizedCalendars: List<CalendarInfo>?): WidgetConfigurationSaveResult = try {
        withContext(Dispatchers.IO) { withTimeout(30_000) {
            if (!isOwnWidget(widgetId)) return@withTimeout WidgetConfigurationSaveResult.Stale
            val previousPins = (settings.configuration(widgetId) as? AgendaWidgetConfigurationState.Ready)
                ?.value?.expectedCalendarIdentities.orEmpty()
            val pins = when (val selected = choice.selection) {
                WidgetCalendarSelection.FollowApp -> emptyMap()
                is WidgetCalendarSelection.Explicit -> {
                    val authorized = authorizedCalendars.orEmpty().groupBy { it.id }
                    selected.ids.associateWith { id ->
                        val calendar = authorized[id]?.singleOrNull() ?: return@withTimeout WidgetConfigurationSaveResult.Unavailable
                        if (calendar.accessLevel?.let { it >= android.provider.CalendarContract.Calendars.CAL_ACCESS_FREEBUSY } != true)
                            return@withTimeout WidgetConfigurationSaveResult.Unavailable
                        val observed = calendar.identity
                        val previous = previousPins[id]
                        if (previous?.account == observed.account &&
                            (previous.owner == null || observed.owner == null || previous.owner == observed.owner))
                            observed.copy(owner = observed.owner ?: previous.owner, providerId = observed.providerId ?: previous.providerId)
                        else observed
                    }
                }
            }
            val candidate = AgendaWidgetConfiguration("validation", choice.selection, choice.privacy, pins)
            if (!validWidgetPins(choice.selection, pins)) return@withTimeout WidgetConfigurationSaveResult.Unavailable
            if (pins.isNotEmpty()) {
                val fresh = (readMetadata() as? CalendarReadState.Ready)?.value ?: return@withTimeout WidgetConfigurationSaveResult.Unavailable
                if (!widgetPinsAuthorize(candidate, fresh)) return@withTimeout WidgetConfigurationSaveResult.Stale
            }
            val binding = bootstrap.await() ?: return@withTimeout WidgetConfigurationSaveResult.Unavailable
            when (binding.prepareForSetup(widgetId, runtime)) {
                is WidgetBindingSetupResult.Prepared -> Unit
                WidgetBindingSetupResult.Stale -> return@withTimeout WidgetConfigurationSaveResult.Stale
                WidgetBindingSetupResult.Unavailable -> return@withTimeout WidgetConfigurationSaveResult.Unavailable
            }
            val session = (runtime.beginConfiguration(widgetId) as? WidgetConfigurationSessionResult.Ready)?.session
                ?: return@withTimeout WidgetConfigurationSaveResult.Unavailable
            runtime.saveFreshConfiguration(session, WidgetConfigurationChoice(choice.selection, choice.privacy, pins))
        } }
    } catch (_: TimeoutCancellationException) { WidgetConfigurationSaveResult.Unavailable }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { WidgetConfigurationSaveResult.Unavailable }

    /** Validates lifecycle/generation before reading provider metadata and again after awaits.
     * Returned values contain no cached event details; Root still resolves its normal details UI.
     */
    suspend fun validateRoute(route: AgendaWidgetRoute): AgendaWidgetRouteResult = try {
        withContext(Dispatchers.IO) { withTimeout(15_000) {
            if (!isOwnWidget(route.widgetId)) return@withTimeout AgendaWidgetRouteResult.Invalid
            if (route is AgendaWidgetRoute.Setup) return@withTimeout AgendaWidgetRouteResult.SetupRequired(route.widgetId)
            val state = settings.configuration(route.widgetId)
            if (state == AgendaWidgetConfigurationState.SetupRequired) return@withTimeout AgendaWidgetRouteResult.SetupRequired(route.widgetId)
            val ready = state as? AgendaWidgetConfigurationState.Ready ?: return@withTimeout AgendaWidgetRouteResult.Unavailable
            val generation = when (route) {
                is AgendaWidgetRoute.Today -> route.generation
                is AgendaWidgetRoute.Create -> route.generation
                is AgendaWidgetRoute.More -> route.generation
                is AgendaWidgetRoute.OpenEvent -> route.generation
                is AgendaWidgetRoute.Setup -> error("Handled above")
            }
            if (ready.value.generation != generation) return@withTimeout AgendaWidgetRouteResult.Stale
            if (!validWidgetPins(ready.value.selection, ready.value.expectedCalendarIdentities))
                return@withTimeout AgendaWidgetRouteResult.SetupRequired(route.widgetId)
            val proof = settings.lifecycle(route.widgetId)
            if (proof != WidgetLifecycleProof.Verified(ready.epoch)) return@withTimeout AgendaWidgetRouteResult.SetupRequired(route.widgetId)
            val environment = (readEnvironment() as? CalendarReadState.Ready)?.value ?: return@withTimeout AgendaWidgetRouteResult.Unavailable
            if (route is AgendaWidgetRoute.OpenEvent) {
                if (route.calendarFingerprint == null) return@withTimeout AgendaWidgetRouteResult.Stale
                val selection = when (val selected = ready.value.selection) {
                    WidgetCalendarSelection.FollowApp -> environment.appSelection
                    is WidgetCalendarSelection.Explicit -> CalendarSelection.Explicit(selected.ids)
                }
                val today = clock.instant().atZone(environment.displayZone).toLocalDate()
                if (today.year !in 1..9999 || route.date < today || route.date >= today.plusDays(7)) return@withTimeout AgendaWidgetRouteResult.Stale
                val request = CalendarRequest(CalendarDateRange(today, minOf(today.plusDays(7), LocalDate.of(10000, 1, 1))), environment.displayZone, selection)
                when (val read = snapshotReader.read(request)) {
                    CalendarReadState.PermissionRequired -> return@withTimeout AgendaWidgetRouteResult.PermissionRequired
                    is CalendarReadState.Ready -> {
                        val snapshot = read.value
                        if (snapshot.request != request) return@withTimeout AgendaWidgetRouteResult.Unavailable
                        if (!widgetPinsAuthorize(ready.value, snapshot.calendars)) return@withTimeout AgendaWidgetRouteResult.SetupRequired(route.widgetId)
                        val allowed = when (selection) {
                            is CalendarSelection.Explicit -> selection.ids
                            CalendarSelection.ProviderVisible -> snapshot.calendars.filter { it.providerVisible }.map { it.id }.toSet()
                        }
                        if (snapshot.calendars.singleOrNull { it.id == route.ref.calendarId }?.identity
                                ?.let(::widgetCalendarFingerprint) != route.calendarFingerprint) return@withTimeout AgendaWidgetRouteResult.Stale
                        if (snapshot.calendars.singleOrNull { it.id == route.ref.calendarId }?.accessLevel
                                ?.let { it >= android.provider.CalendarContract.Calendars.CAL_ACCESS_READ } != true)
                            return@withTimeout AgendaWidgetRouteResult.Unavailable
                        val matched = snapshot.occurrences.firstOrNull { it.ref == route.ref && (environment.showDeclined || !it.declined) }
                        if (route.ref.calendarId !in allowed || matched == null) return@withTimeout AgendaWidgetRouteResult.Stale
                        val rowDate = when (val time = matched.time) {
                            is EventTime.AllDay -> maxOf(today, time.dates.start)
                            is EventTime.Timed -> maxOf(today, time.start.atZone(environment.displayZone).toLocalDate())
                        }
                        if (rowDate != route.date || !matched.time.intersects(request.range, environment.displayZone) ||
                            (matched.time as? EventTime.Timed)?.let { if (it.start == it.end) it.start < clock.instant() else it.end <= clock.instant() } == true)
                            return@withTimeout AgendaWidgetRouteResult.Stale
                    }
                    else -> return@withTimeout AgendaWidgetRouteResult.Unavailable
                }
            }
            if (!isOwnWidget(route.widgetId) || settings.configuration(route.widgetId) != state ||
                settings.lifecycle(route.widgetId) != proof || (readEnvironment() as? CalendarReadState.Ready)?.value != environment)
                return@withTimeout AgendaWidgetRouteResult.Stale
            AgendaWidgetRouteResult.Ready(route, environment)
        } }
    } catch (_: TimeoutCancellationException) { AgendaWidgetRouteResult.Unavailable }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { AgendaWidgetRouteResult.Unavailable }
}
