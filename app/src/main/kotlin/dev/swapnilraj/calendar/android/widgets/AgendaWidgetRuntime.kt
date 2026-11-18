package dev.swapnilraj.calendar.android.widgets

import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import dev.swapnilraj.calendar.core.*
import java.time.Clock
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*

/** No provider engine, settings defaults, manifest registration, or autonomous application scope.
 * Root wires a bounded shared CalendarReader snapshot adapter and an independent widget job queue.
 */
class AgendaWidgetRuntime(
    private val source: AgendaWidgetSnapshotReader,
    private val settings: AgendaWidgetSettings,
    private val scheduler: AgendaWidgetRefreshScheduler,
    private val host: AgendaWidgetHost,
    private val renderer: AgendaWidgetRenderer,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
    private val timeoutMillis: Long = 15_000,
) {
    private val revisions = ConcurrentHashMap<Int, Long>()
    private data class Removal(val token: Any = Any(), val display: AgendaWidgetDisplay = AgendaWidgetDisplay.SetupRequired)
    private val removals = ConcurrentHashMap<Int, Removal>()
    private val publication = Any()
    init { require(timeoutMillis > 0) }

    /** Called synchronously by widget callbacks/settings changes, before reading possibly slow data. */
    fun invalidate(widgetIds: Set<Int> = host.widgetIds()): Boolean {
        val ids = widgetIds.intersect(host.widgetIds()).filter { it > 0 }.toSet()
        synchronized(publication) {
            ids.forEach { id -> revisions.merge(id, 1L, Long::plus); publish(id, AgendaWidgetDisplay.Loading) }
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

    /** Immediate invalidation precedes IO; a failed cleanup cannot reveal inherited titles. */
    fun removed(widgetIds: Set<Int>, finished: () -> Unit = {}): Job {
        val marker = Removal()
        synchronized(publication) {
            widgetIds.forEach { id ->
                revisions.merge(id, 1L, Long::plus)
                removals[id] = marker
                publish(id, AgendaWidgetDisplay.SetupRequired)
            }
        }
        fun failed() { widgetIds.forEach { id -> removals.computeIfPresent(id) { _, current ->
            if (current.token === marker.token) marker.copy(display = AgendaWidgetDisplay.Unavailable) else current
        } } }
        val once = AtomicBoolean()
        fun finishOnce() { if (once.compareAndSet(false, true)) finished() }
        val job = scope.launch {
            try {
                settings.removed(widgetIds)
                currentCoroutineContext().ensureActive()
                widgetIds.forEach { removals.remove(it, marker) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed() }
        }
        val handler = Handler(Looper.getMainLooper())
        val deadline = Runnable {
            job.cancel()
            failed()
            finishOnce()
        }
        handler.postDelayed(deadline, 8_000)
        job.invokeOnCompletion { failure ->
            handler.removeCallbacks(deadline)
            if (failure != null) failed()
            invalidate(widgetIds)
            finishOnce()
        }
        return job
    }

    /** Root calls only after successfully saving a fresh configuration/generation. */
    fun configurationSaved(widgetId: Int): Boolean {
        removals.remove(widgetId)
        return invalidate(setOf(widgetId))
    }

    private suspend fun load(id: Int): AgendaWidgetDisplay {
        removals[id]?.let { return it.display }
        val state = settings.configuration(id)
        if (state == AgendaWidgetConfigurationState.SetupRequired) return AgendaWidgetDisplay.SetupRequired
        val config = (state as? AgendaWidgetConfigurationState.Ready)?.value ?: return AgendaWidgetDisplay.Unavailable
        val environment = (settings.environment() as? CalendarReadState.Ready)?.value ?: return AgendaWidgetDisplay.Unavailable
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
                    }) }.sortedWith(compareBy<AgendaWidgetRow> { it.date }
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
        if (clock.instant().atZone(environment.displayZone).toLocalDate() != today ||
            settings.configuration(id) != state ||
            (settings.environment() as? CalendarReadState.Ready)?.value != environment) {
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
