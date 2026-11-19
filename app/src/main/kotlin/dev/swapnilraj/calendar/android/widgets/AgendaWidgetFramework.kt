package dev.swapnilraj.calendar.android.widgets

import android.app.job.JobWorkItem
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

interface AgendaWidgetFrameworkOwner : AgendaWidgetRuntimeOwner { val agendaWidgetFramework: AgendaWidgetFramework }
interface AgendaWidgetWork {
    fun invalidate(): Boolean
    suspend fun refresh(): Boolean
}
class RuntimeAgendaWidgetWork(private val runtime: AgendaWidgetRuntime) : AgendaWidgetWork {
    override fun invalidate() = runtime.invalidate()
    override suspend fun refresh() = runtime.refresh()
}
interface AgendaWidgetWorkQueue {
    fun next(): JobWorkItem?
    fun complete(work: JobWorkItem)
}
enum class AgendaWidgetFrameworkHealth { STARTING, REGISTERED, REFRESHED, DEGRADED }
sealed interface AgendaWidgetJobOutcome {
    data object QueueDrained : AgendaWidgetJobOutcome
    data object RetryQueue : AgendaWidgetJobOutcome
    data class Finished(val retry: Boolean) : AgendaWidgetJobOutcome
}

/** Application supplies one framework/runtime. No default zone or empty-host fallback on read failure. */
class AgendaWidgetFramework(
    private val work: AgendaWidgetWork,
    private val host: AgendaWidgetHost,
    private val jobs: AgendaWidgetJobController,
    private val scope: CoroutineScope,
    private val canObserveCalendar: () -> Boolean,
    private val displayZone: suspend () -> ZoneId?,
    private val clock: Clock = Clock.systemUTC(),
    val jobTimeoutMillis: Long = 120_000,
    private val batchSize: Int = 32,
    private val maxBatches: Int = 4,
) {
    private val mutableHealth = MutableStateFlow(AgendaWidgetFrameworkHealth.STARTING)
    val health = mutableHealth.asStateFlow()
    init { require(jobTimeoutMillis > 0 && batchSize > 0 && maxBatches > 0) }

    /** Synchronous scheduling/presentation only: no settings/provider read or permission prompt. */
    fun recover(): Boolean = try {
        val ids = widgetIds()
        val registered = jobs.ensureRegistered(ids.isNotEmpty(), canObserveCalendar())
        val requested = ids.isEmpty() || work.invalidate()
        mutableHealth.value = if (registered && requested) AgendaWidgetFrameworkHealth.REGISTERED else AgendaWidgetFrameworkHealth.DEGRADED
        registered && requested
    } catch (_: Exception) { degraded(); false }
    fun onProcessStarted() = recover()
    fun onResume() = recover()

    /** Called before revoking a worker lease/cancelling its coroutine, invalidating E6 revisions. */
    fun invalidateBeforeStop() { try { work.invalidate() } catch (_: Exception) { degraded() } }
    fun retryQueue(): Boolean {
        degraded()
        return try { jobs.retryQueuedWork() } catch (_: Exception) { false }
    }
    fun worker(finished: (AgendaWidgetJobOutcome) -> Unit, jobId: Int, queue: AgendaWidgetWorkQueue?): Job =
        scope.launch(start = CoroutineStart.LAZY) { finished(run(jobId, queue)) }

    suspend fun run(jobId: Int, queue: AgendaWidgetWorkQueue? = null): AgendaWidgetJobOutcome {
        try {
            require(AndroidAgendaWidgetJobs.owns(jobId))
            val ids = widgetIds()
            if (ids.isEmpty()) {
                jobs.cancelAll()
                return if (jobId == AndroidAgendaWidgetJobs.QUEUE_ID) AgendaWidgetJobOutcome.QueueDrained else AgendaWidgetJobOutcome.Finished(false)
            }
            if (!jobs.ensureRegistered(true, canObserveCalendar())) return retry(jobId)
            if (jobId == AndroidAgendaWidgetJobs.QUEUE_ID) {
                val commands = requireNotNull(queue)
                repeat(maxBatches) {
                    val batch = mutableListOf<JobWorkItem>()
                    while (batch.size < batchSize) {
                        val command = commands.next() ?: break
                        batch += command
                    }
                    if (batch.isEmpty()) return AgendaWidgetJobOutcome.QueueDrained
                    if (!refresh()) return retry(jobId)
                    currentCoroutineContext().ensureActive()
                    batch.forEach(commands::complete)
                }
                // A final null dequeue after completion atomically lets Android stop the queue.
                // A non-null item is intentionally left unacked for a bounded retry/replacement.
                return if (commands.next() == null) AgendaWidgetJobOutcome.QueueDrained else retry(jobId)
            }
            val success = refresh()
            val rearmed = jobId != AndroidAgendaWidgetJobs.CONTENT_ID || jobs.rearmContent(canObserveCalendar())
            return AgendaWidgetJobOutcome.Finished(!(success && rearmed)).also { if (!success || !rearmed) degraded() }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { return retry(jobId) }
    }

    private suspend fun refresh(): Boolean {
        if (!work.refresh()) { degraded(); return false }
        currentCoroutineContext().ensureActive()
        val zone = displayZone() ?: run { degraded(); return false }
        currentCoroutineContext().ensureActive()
        if (!jobs.replaceMidnight(nextMidnight(clock.instant(), zone))) { degraded(); return false }
        mutableHealth.value = AgendaWidgetFrameworkHealth.REFRESHED
        return true
    }
    private fun retry(id: Int): AgendaWidgetJobOutcome {
        degraded()
        return if (id == AndroidAgendaWidgetJobs.QUEUE_ID) AgendaWidgetJobOutcome.RetryQueue else AgendaWidgetJobOutcome.Finished(true)
    }
    private fun widgetIds(): Set<Int> = host.widgetIds().also { require(it.all { id -> id > 0 }) }
    private fun degraded() { mutableHealth.value = AgendaWidgetFrameworkHealth.DEGRADED }
    companion object {
        fun nextMidnight(now: Instant, zone: ZoneId): Instant = now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
    }
}
