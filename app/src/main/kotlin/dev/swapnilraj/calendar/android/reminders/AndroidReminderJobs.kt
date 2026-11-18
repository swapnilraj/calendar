package dev.swapnilraj.calendar.android.reminders

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.provider.CalendarContract
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

/** Separate jobs: content triggers cannot be periodic or persisted on Android. No network needed. */
class AndroidReminderJobs(context: Context, private val service: ComponentName) : ReminderJobController {
    private val scheduler = context.getSystemService(JobScheduler::class.java)
    init { require(service.packageName == context.packageName) }

    override fun ensureRegistered(canObserveCalendar: Boolean): Boolean {
        var success = true
        if (scheduler.getPendingJob(PERIODIC_ID) == null) success = schedule(JobInfo.Builder(PERIODIC_ID, service)
            .setPersisted(true).setPeriodic(15 * 60_000L).build()) && success
        if (!canObserveCalendar) scheduler.cancel(CONTENT_ID)
        else if (scheduler.getPendingJob(CONTENT_ID) == null) success = rearmContent(true) && success
        return success
    }

    override fun rearmContent(canObserveCalendar: Boolean): Boolean {
        if (!canObserveCalendar) { scheduler.cancel(CONTENT_ID); return true }
        return schedule(JobInfo.Builder(CONTENT_ID, service)
            .addTriggerContentUri(JobInfo.TriggerContentUri(CalendarContract.CONTENT_URI,
                JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
            .setTriggerContentUpdateDelay(1_000).setTriggerContentMaxDelay(10_000).build())
    }

    override fun scheduleRetry(): Boolean {
        // Do not replace an already pending/running retry: repeated provider broadcasts coalesce.
        if (scheduler.getPendingJob(RETRY_ID) != null) return true
        return schedule(JobInfo.Builder(RETRY_ID, service).setMinimumLatency(30_000)
            .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
    }
    override fun cancelAll() { listOf(CONTENT_ID, PERIODIC_ID, RETRY_ID).forEach(scheduler::cancel) }
    private fun schedule(job: JobInfo): Boolean = scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS

    companion object {
        const val CONTENT_ID = 0x445201
        const val PERIODIC_ID = 0x445202
        const val RETRY_ID = 0x445203
        fun owns(id: Int) = id == CONTENT_ID || id == PERIODIC_ID || id == RETRY_ID
    }
}

/** Manifest must declare BIND_JOB_SERVICE. Application supplies the single shared runtime. */
class ReminderJobService : JobService() {
    private class Running { lateinit var job: Job }
    private val running = ConcurrentHashMap<Int, Running>()

    override fun onStartJob(params: JobParameters): Boolean {
        if (!AndroidReminderJobs.owns(params.jobId)) return false
        val runtime = (applicationContext as? ReminderRuntimeOwner)?.reminderRuntime ?: return false
        val entry = Running()
        entry.job = runtime.job(params.jobId == AndroidReminderJobs.CONTENT_ID) { retry ->
            if (running.remove(params.jobId, entry)) jobFinished(params, retry)
        }
        running.put(params.jobId, entry)?.job?.cancel()
        entry.job.start()
        // Covers an application scope already cancelled before this lazy job starts.
        entry.job.invokeOnCompletion {
            if (running.remove(params.jobId, entry)) jobFinished(params, true)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        // The stop callback may contain a newly unparcelled JobParameters instance.
        running.remove(params.jobId)?.job?.cancel()
        return AndroidReminderJobs.owns(params.jobId)
    }

    override fun onDestroy() {
        val entries = running.values.toList()
        running.clear()
        entries.forEach { it.job.cancel() }
        super.onDestroy()
    }
}
