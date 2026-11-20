package dev.swapnilraj.calendar.android.widgets

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.app.job.JobWorkItem
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PersistableBundle
import android.provider.CalendarContract
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

interface AgendaWidgetJobController : AgendaWidgetRefreshScheduler {
    fun ensureRegistered(hasWidgets: Boolean, canObserveCalendar: Boolean): Boolean
    fun rearmContent(canObserveCalendar: Boolean): Boolean
    fun replaceMidnight(at: Instant): Boolean
    fun retryQueuedWork(): Boolean
    fun cancelAll()
}

/** No reminder configuration, network, exact alarm, or calendar commands enter this adapter. */
class AndroidAgendaWidgetJobs(
    context: Context,
    private val service: ComponentName,
    private val clock: Clock = Clock.systemUTC(),
) : AgendaWidgetJobController {
    private val scheduler = context.getSystemService(JobScheduler::class.java)
    init { require(service.packageName == context.packageName) }

    override fun ensureRegistered(hasWidgets: Boolean, canObserveCalendar: Boolean): Boolean = safe {
        if (!hasWidgets) { cancelAll(); return@safe true }
        var success = true
        if (ownedPending(PERIODIC_ID) == null) success = scheduled(builder(PERIODIC_ID).setPersisted(true)
            .setPeriodic(15 * 60_000L).build()) && success
        if (!canObserveCalendar) scheduler.cancel(CONTENT_ID)
        else if (ownedPending(CONTENT_ID) == null) success = rearmContent(true) && success
        success
    }
    override fun rearmContent(canObserveCalendar: Boolean): Boolean = safe {
        if (!canObserveCalendar) { scheduler.cancel(CONTENT_ID); return@safe true }
        scheduled(builder(CONTENT_ID).addTriggerContentUri(JobInfo.TriggerContentUri(CalendarContract.CONTENT_URI,
            JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
            .setTriggerContentUpdateDelay(1_000).setTriggerContentMaxDelay(10_000).build())
    }
    override fun replaceMidnight(at: Instant): Boolean = safe {
        val delay = Duration.between(clock.instant(), at)
        val millis = Math.addExact(delay.toMillis(), if (delay.nano % 1_000_000 == 0) 0 else 1).coerceAtLeast(1)
        scheduled(builder(MIDNIGHT_ID).setMinimumLatency(millis).build())
    }
    override fun requestRefresh(widgetIds: Set<Int>): Boolean = safe {
        if (widgetIds.none { it > 0 }) return@safe true
        // Always enqueue, including while running. Reuse only our validated spec; never skip work.
        val info = ownedPending(QUEUE_ID) ?: builder(QUEUE_ID).setMinimumLatency(1_000).build()
        scheduler.enqueue(info, command()) == JobScheduler.RESULT_SUCCESS
    }
    override fun retryQueuedWork(): Boolean = safe {
        // Changed JobInfo causes a stop/restart while transferring pending AND executing work.
        // Do not use schedule/cancel/jobFinished: each can discard a concurrently enqueued command.
        scheduler.enqueue(builder(QUEUE_ID).setMinimumLatency(30_000).build(), command()) == JobScheduler.RESULT_SUCCESS
    }
    override fun cancelAll() { listOf(CONTENT_ID, PERIODIC_ID, QUEUE_ID, MIDNIGHT_ID).forEach(scheduler::cancel) }

    private fun builder(id: Int): JobInfo.Builder = JobInfo.Builder(id, service).setExtras(PersistableBundle().apply {
        putString(SPEC_NAMESPACE, NAMESPACE); putString(SPEC_GENERATION, UUID.randomUUID().toString())
    })
    private fun ownedPending(id: Int): JobInfo? = scheduler.getPendingJob(id)?.takeIf {
        it.service == service && it.extras.getString(SPEC_NAMESPACE) == NAMESPACE &&
            !it.extras.getString(SPEC_GENERATION).isNullOrBlank() && it.isPeriodic == (id == PERIODIC_ID)
    }
    private fun command() = JobWorkItem(Intent(ACTION_REFRESH).setComponent(service).setData(Uri.parse(COMMAND_URI)))
    private fun scheduled(info: JobInfo) = scheduler.schedule(info) == JobScheduler.RESULT_SUCCESS
    private inline fun safe(block: () -> Boolean): Boolean = try { block() } catch (_: RuntimeException) { false }

    companion object {
        const val CONTENT_ID = 0x445701
        const val PERIODIC_ID = 0x445702
        const val QUEUE_ID = 0x445703
        const val MIDNIGHT_ID = 0x445704
        const val SPEC_NAMESPACE = "agenda_widget.namespace"
        const val SPEC_GENERATION = "agenda_widget.spec_generation"
        const val NAMESPACE = "dev.swapnilraj.calendar.agenda.v1"
        const val ACTION_REFRESH = "dev.swapnilraj.calendar.widgets.REFRESH"
        const val COMMAND_URI = "daylight://agenda-widget-refresh/all"
        fun owns(id: Int) = id in CONTENT_ID..MIDNIGHT_ID
    }
}
