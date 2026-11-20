package dev.swapnilraj.calendar.android.widgets

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job

/**
 * Queued jobs NEVER call jobFinished. After completeWork, a final dequeueWork(null) lets Android
 * atomically stop the job; concurrent enqueue is retained. Retry changes JobInfo via enqueue,
 * transferring both executing and pending work rather than cancelling/dropping the queue.
 * Verified AOSP 1cdfff555f4a21f71ccc978290e2e212e2f8b168:
 * https://github.com/aosp-mirror/platform_frameworks_base/blob/1cdfff555f4a21f71ccc978290e2e212e2f8b168/apex/jobscheduler/framework/java/android/app/job/JobParameters.java#L555-L608
 * https://github.com/aosp-mirror/platform_frameworks_base/blob/1cdfff555f4a21f71ccc978290e2e212e2f8b168/apex/jobscheduler/framework/java/android/app/job/JobScheduler.java#L333-L357
 * https://github.com/aosp-mirror/platform_frameworks_base/blob/1cdfff555f4a21f71ccc978290e2e212e2f8b168/apex/jobscheduler/service/java/com/android/server/job/controllers/JobStatus.java#L1012-L1027
 */
class AgendaWidgetJobService : JobService() {
    private class Lease(val params: JobParameters, val generation: String, val serial: Long, val framework: AgendaWidgetFramework) {
        lateinit var worker: Job
        lateinit var deadline: Runnable
    }
    private val running = ConcurrentHashMap<Int, Lease>()
    private val captured = WeakHashMap<JobParameters, Long>()
    private var serial = 0L
    private val handler = Handler(Looper.getMainLooper())

    override fun onStartJob(params: JobParameters): Boolean {
        val generation = generation(params) ?: return false
        val framework = (applicationContext as? AgendaWidgetFrameworkOwner)?.agendaWidgetFramework ?: return false
        running[params.jobId]?.let { old -> retire(old, retry = false, notifySystem = false) }
        val lease = Lease(params, generation, ++serial, framework)
        synchronized(captured) { captured[params] = lease.serial }
        val queue = if (params.jobId == AndroidAgendaWidgetJobs.QUEUE_ID) object : AgendaWidgetWorkQueue {
            override fun next() = params.dequeueWork()
            override fun complete(work: android.app.job.JobWorkItem) { params.completeWork(work) }
        } else null
        lease.worker = framework.worker({ outcome ->
            when (outcome) {
                AgendaWidgetJobOutcome.QueueDrained -> complete(lease, notifySystem = false)
                AgendaWidgetJobOutcome.RetryQueue -> retire(lease, retry = true, notifySystem = false)
                is AgendaWidgetJobOutcome.Finished -> if (outcome.retry) retire(lease, retry = true, notifySystem = true)
                    else complete(lease, notifySystem = true)
            }
        }, params.jobId, queue)
        lease.deadline = Runnable { retire(lease, retry = true, notifySystem = params.jobId != AndroidAgendaWidgetJobs.QUEUE_ID) }
        running[params.jobId] = lease
        handler.postDelayed(lease.deadline, framework.jobTimeoutMillis)
        lease.worker.invokeOnCompletion { failure ->
            if (failure != null) retire(lease, retry = true, notifySystem = params.jobId != AndroidAgendaWidgetJobs.QUEUE_ID)
        }
        lease.worker.start()
        return true
    }

    private fun complete(lease: Lease, notifySystem: Boolean) {
        if (!running.remove(lease.params.jobId, lease)) return
        handler.removeCallbacks(lease.deadline)
        if (notifySystem) jobFinished(lease.params, false)
    }
    private fun retire(lease: Lease, retry: Boolean, notifySystem: Boolean) {
        if (running[lease.params.jobId] !== lease) return
        lease.framework.invalidateBeforeStop()
        if (!running.remove(lease.params.jobId, lease)) return
        handler.removeCallbacks(lease.deadline)
        lease.worker.cancel()
        if (retry && lease.params.jobId == AndroidAgendaWidgetJobs.QUEUE_ID) lease.framework.retryQueue()
        else if (notifySystem) jobFinished(lease.params, retry)
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val generation = generation(params) ?: return false
        val lease = running[params.jobId] ?: return true
        val previousSerial = synchronized(captured) { captured[params] }
        // Stop can be newly unmarshalled. Match public spec-generation, and reject a known older
        // captured execution. Identical-spec executions rely on Android's ordered lifecycle;
        // the public API exposes no additional per-execution identifier (no hidden Binder access).
        if (generation != lease.generation || (previousSerial != null && previousSerial != lease.serial)) return true
        retire(lease, retry = false, notifySystem = false)
        return true
    }
    override fun onDestroy() {
        running.values.toList().forEach { retire(it, retry = false, notifySystem = false) }
        super.onDestroy()
    }
    private fun generation(params: JobParameters): String? = try {
        if (!AndroidAgendaWidgetJobs.owns(params.jobId) ||
            params.extras.getString(AndroidAgendaWidgetJobs.SPEC_NAMESPACE) != AndroidAgendaWidgetJobs.NAMESPACE) null
        else params.extras.getString(AndroidAgendaWidgetJobs.SPEC_GENERATION)?.takeIf { it.isNotBlank() }
    } catch (_: RuntimeException) { null }
}

/** Only system recovery hints, with no event/widget commands or configuration adoption. */
class AgendaWidgetRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS || (intent.component != null && intent.component != ComponentName(context, javaClass))) return
        (context.applicationContext as? AgendaWidgetFrameworkOwner)?.agendaWidgetFramework?.recover()
    }
    companion object {
        val ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED)
    }
}
