package dev.swapnilraj.calendar

import android.app.Application
import android.app.job.*
import android.content.*
import android.os.*
import android.widget.RemoteViews
import dev.swapnilraj.calendar.android.widgets.*
import java.time.*
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowJobScheduler
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = WidgetFrameworkApplication::class, shadows = [WidgetParametersShadow::class, AuditedWidgetScheduler::class])
class AgendaWidgetFrameworkTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val component get() = ComponentName(context, AgendaWidgetJobService::class.java)
    @Before fun reset() { AuditedWidgetScheduler.enqueues.clear(); WidgetParametersShadow.queues.clear() }
    private class Host : AgendaWidgetHost {
        var fail = false; var ids = setOf(1)
        override fun widgetIds(): Set<Int> { if (fail) error("Unavailable host"); return ids }
        override fun size(widgetId: Int) = AgendaWidgetSize(240, 240)
        override fun publish(widgetId: Int, views: RemoteViews) = Unit
    }
    private class Jobs : AgendaWidgetJobController {
        var cancelled = 0; var retries = 0; var rearmed = 0; var registered = 0
        var lastMidnight: Instant? = null; var success = true; var retrySuccess = true
        override fun ensureRegistered(hasWidgets: Boolean, canObserveCalendar: Boolean): Boolean { registered++; return success }
        override fun rearmContent(canObserveCalendar: Boolean): Boolean { rearmed++; return success }
        override fun replaceMidnight(at: Instant): Boolean { lastMidnight = at; return success }
        override fun retryQueuedWork(): Boolean { retries++; return retrySuccess }
        override fun requestRefresh(widgetIds: Set<Int>) = success
        override fun cancelAll() { cancelled++ }
    }
    private class Work : AgendaWidgetWork {
        var invalidated = 0; var refreshed = 0; var success = true
        var action: (suspend () -> Unit)? = null
        override fun invalidate(): Boolean { invalidated++; return true }
        override suspend fun refresh(): Boolean { refreshed++; action?.invoke(); return success }
    }
    class Queue : AgendaWidgetWorkQueue {
        val pending = java.util.ArrayDeque<JobWorkItem>()
        val active = mutableListOf<JobWorkItem>(); val completed = mutableListOf<JobWorkItem>()
        var stopped = false; var restartScheduled = false; var afterStop: (() -> Unit)? = null
        fun enqueue() { pending += JobWorkItem(Intent("untrusted payload ignored")); if (stopped) restartScheduled = true }
        override fun next(): JobWorkItem? {
            val item = pending.pollFirst()
            if (item != null) active += item
            else if (active.isEmpty()) { stopped = true; afterStop?.also { afterStop = null }?.invoke() }
            return item
        }
        override fun complete(work: JobWorkItem) { assertTrue(active.remove(work)); completed += work }
    }
    private inner class Fixture(batch: Int = 32, cycles: Int = 4) {
        val host = Host(); val jobs = Jobs(); val work = Work()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var zone: ZoneId? = ZoneOffset.UTC
        val framework = AgendaWidgetFramework(work, host, jobs, scope, { true }, { zone },
            Clock.fixed(Instant.parse("2026-11-18T09:00:00Z"), ZoneOffset.UTC), 50, batch, cycles)
        init { (context as WidgetFrameworkApplication).agendaWidgetFramework = framework }
        fun close() = scope.cancel()
    }
    private fun params(id: Int, generation: String = "spec-one") = ReflectionHelpers.newInstance(JobParameters::class.java).also {
        ReflectionHelpers.setField(it, "jobId", id)
        ReflectionHelpers.setField(it, "extras", PersistableBundle().apply {
            putString(AndroidAgendaWidgetJobs.SPEC_NAMESPACE, AndroidAgendaWidgetJobs.NAMESPACE)
            putString(AndroidAgendaWidgetJobs.SPEC_GENERATION, generation)
        })
    }

    @Test fun nativeRegistrationSeparatesJobsAndAlwaysEnqueuesEvenWhenAlreadyPending() {
        val scheduler = context.getSystemService(JobScheduler::class.java); scheduler.cancelAll()
        val now = Instant.parse("2026-11-18T09:00:00Z")
        val jobs = AndroidAgendaWidgetJobs(context, component, Clock.fixed(now, ZoneOffset.UTC))
        assertTrue(jobs.ensureRegistered(true, true))
        assertTrue(jobs.requestRefresh(setOf(1))); val before = scheduler.getPendingJob(AndroidAgendaWidgetJobs.QUEUE_ID)!!
        assertTrue(jobs.requestRefresh(setOf(2)))
        assertEquals(2, AuditedWidgetScheduler.enqueues.size)
        assertEquals(before, AuditedWidgetScheduler.enqueues.last().first)
        val periodic = scheduler.getPendingJob(AndroidAgendaWidgetJobs.PERIODIC_ID)!!
        val content = scheduler.getPendingJob(AndroidAgendaWidgetJobs.CONTENT_ID)!!
        assertTrue(periodic.isPersisted); assertTrue(periodic.isPeriodic); assertEquals(900_000L, periodic.intervalMillis)
        assertFalse(content.isPersisted); assertNotNull(content.triggerContentUris)
        assertNull(periodic.requiredNetwork); assertNull(content.requiredNetwork); assertNull(before.requiredNetwork)
        assertTrue(jobs.replaceMidnight(now.plusSeconds(600)))
        val midnight = scheduler.getPendingJob(AndroidAgendaWidgetJobs.MIDNIGHT_ID)!!
        assertEquals(600_000L, midnight.minLatencyMillis)
        assertFalse(midnight.isPersisted); assertFalse(midnight.isPeriodic); assertNull(midnight.requiredNetwork)
        assertTrue(jobs.retryQueuedWork())
        val replacement = AuditedWidgetScheduler.enqueues.last().first
        assertNotEquals(before.extras.getString(AndroidAgendaWidgetJobs.SPEC_GENERATION), replacement.extras.getString(AndroidAgendaWidgetJobs.SPEC_GENERATION))
        assertEquals(30_000L, replacement.minLatencyMillis)
        assertTrue(jobs.ensureRegistered(true, false)); assertNull(scheduler.getPendingJob(AndroidAgendaWidgetJobs.CONTENT_ID))
        scheduler.schedule(JobInfo.Builder(123, component).setMinimumLatency(1).build())
        jobs.cancelAll(); assertEquals(listOf(123), scheduler.allPendingJobs.map { it.id })
    }

    @Test fun nativeEnqueueRejectsSpoofedExistingSpecAndUsesCorrectOwnedComponent() {
        val scheduler = context.getSystemService(JobScheduler::class.java); scheduler.cancelAll()
        scheduler.schedule(JobInfo.Builder(AndroidAgendaWidgetJobs.QUEUE_ID, ComponentName(context.packageName, "OtherService"))
            .setMinimumLatency(1).build())
        val jobs = AndroidAgendaWidgetJobs(context, component)
        assertTrue(jobs.requestRefresh(setOf(1)))
        assertEquals(component, AuditedWidgetScheduler.enqueues.single().first.service)
        assertEquals(AndroidAgendaWidgetJobs.NAMESPACE, AuditedWidgetScheduler.enqueues.single().first.extras.getString(AndroidAgendaWidgetJobs.SPEC_NAMESPACE))
        scheduler.schedule(JobInfo.Builder(AndroidAgendaWidgetJobs.QUEUE_ID, component).setMinimumLatency(1)
            .setExtras(PersistableBundle().apply {
                putString(AndroidAgendaWidgetJobs.SPEC_NAMESPACE, "outside-namespace")
                putString(AndroidAgendaWidgetJobs.SPEC_GENERATION, "outside-spec")
            }).build())
        assertTrue(jobs.requestRefresh(setOf(1)))
        assertEquals(AndroidAgendaWidgetJobs.NAMESPACE, AuditedWidgetScheduler.enqueues.last().first.extras.getString(AndroidAgendaWidgetJobs.SPEC_NAMESPACE))
        assertNotEquals("outside-spec", AuditedWidgetScheduler.enqueues.last().first.extras.getString(AndroidAgendaWidgetJobs.SPEC_GENERATION))
    }

    @Test fun queuedArrivalDuringRefreshIsNotDroppedAndEmptyQueueStopsOnlyThroughDequeue() = runBlocking {
        val f = Fixture(); val queue = Queue(); queue.enqueue()
        f.work.action = { if (f.work.refreshed == 1) queue.enqueue() }
        assertEquals(AgendaWidgetJobOutcome.QueueDrained, f.framework.run(AndroidAgendaWidgetJobs.QUEUE_ID, queue))
        assertEquals(2, f.work.refreshed); assertEquals(2, queue.completed.size)
        assertTrue(queue.stopped); assertTrue(queue.active.isEmpty())
        f.close()
    }

    @Test fun arrivalAfterFinalDrainIsLeftForPlatformRestartAndNoManualFinishIsIssued() = runBlocking {
        val f = Fixture(); val queue = Queue(); queue.enqueue()
        queue.afterStop = { queue.enqueue() }
        val controller = Robolectric.buildService(AgendaWidgetJobService::class.java).create(); val service = controller.get()
        val p = params(AndroidAgendaWidgetJobs.QUEUE_ID); WidgetParametersShadow.queues[p] = queue
        assertTrue(service.onStartJob(p))
        assertTrue(queue.restartScheduled); assertEquals(1, queue.pending.size)
        assertFalse(Shadows.shadowOf(service).isJobFinished)
        controller.destroy(); f.close()
    }

    @Test fun failureAndBoundedBatchYieldNeverAcknowledgeUnrefreshedCommands() = runBlocking {
        val f = Fixture(batch = 2, cycles = 2); val queue = Queue(); repeat(8) { queue.enqueue() }
        assertEquals(AgendaWidgetJobOutcome.RetryQueue, f.framework.run(AndroidAgendaWidgetJobs.QUEUE_ID, queue))
        assertEquals(2, f.work.refreshed); assertEquals(4, queue.completed.size)
        assertEquals(4, queue.active.size + queue.pending.size)
        val failed = Queue(); failed.enqueue(); f.work.success = false
        assertEquals(AgendaWidgetJobOutcome.RetryQueue, f.framework.run(AndroidAgendaWidgetJobs.QUEUE_ID, failed))
        assertTrue(failed.completed.isEmpty()); assertEquals(1, failed.active.size)
        f.close()
    }

    @Test fun unknownZoneOrHostNeverInventsDefaultsOrCancelsJobsAndContentRearms() = runBlocking {
        val f = Fixture(); f.zone = null
        assertEquals(AgendaWidgetJobOutcome.Finished(true), f.framework.run(AndroidAgendaWidgetJobs.CONTENT_ID))
        assertNull(f.jobs.lastMidnight); assertEquals(1, f.jobs.rearmed)
        f.host.fail = true
        assertFalse(f.framework.recover()); assertEquals(0, f.jobs.cancelled)
        f.host.fail = false; f.host.ids = emptySet()
        assertEquals(AgendaWidgetJobOutcome.Finished(false), f.framework.run(AndroidAgendaWidgetJobs.PERIODIC_ID))
        assertEquals(1, f.jobs.cancelled)
        f.close()
    }

    @Test fun midnightFollowsConfiguredZoneAcrossDstAndSkippedDates() {
        val ny = ZoneId.of("America/New_York")
        val spring = Instant.parse("2026-03-08T05:00:00Z"); val fall = Instant.parse("2026-11-01T04:00:00Z")
        assertEquals(23, Duration.between(spring, AgendaWidgetFramework.nextMidnight(spring, ny)).toHours())
        assertEquals(25, Duration.between(fall, AgendaWidgetFramework.nextMidnight(fall, ny)).toHours())
        val apia = ZoneId.of("Pacific/Apia"); val beforeSkip = Instant.parse("2011-12-29T10:00:00Z")
        assertEquals(LocalDate.of(2011, 12, 31), AgendaWidgetFramework.nextMidnight(beforeSkip, apia).atZone(apia).toLocalDate())
    }

    @Test fun queuedTimeoutRevokesBeforeCancelAndLateNoncooperativeWorkNeverFinishesOrAcknowledges() = runBlocking {
        val f = Fixture(); val release = CompletableDeferred<Unit>()
        f.work.action = { withContext(NonCancellable) { release.await() } }
        val queue = Queue(); queue.enqueue()
        val controller = Robolectric.buildService(AgendaWidgetJobService::class.java).create(); val service = controller.get()
        val p = params(AndroidAgendaWidgetJobs.QUEUE_ID); WidgetParametersShadow.queues[p] = queue
        assertTrue(service.onStartJob(p))
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(51, TimeUnit.MILLISECONDS)
        assertTrue(f.work.invalidated > 0); assertEquals(1, f.jobs.retries)
        assertTrue(queue.completed.isEmpty()); assertFalse(Shadows.shadowOf(service).isJobFinished)
        release.complete(Unit); yield()
        assertTrue(queue.completed.isEmpty()); assertFalse(Shadows.shadowOf(service).isJobFinished)
        assertEquals(AgendaWidgetFrameworkHealth.DEGRADED, f.framework.health.value)
        controller.destroy(); f.close()
    }

    @Test fun failedQueueReplacementLeavesCommandsUnacknowledgedAndNeverReportsFinished() {
        val f = Fixture(); f.work.success = false; f.jobs.retrySuccess = false
        val queue = Queue(); queue.enqueue()
        val controller = Robolectric.buildService(AgendaWidgetJobService::class.java).create(); val service = controller.get()
        val p = params(AndroidAgendaWidgetJobs.QUEUE_ID); WidgetParametersShadow.queues[p] = queue
        assertTrue(service.onStartJob(p))
        assertEquals(1, f.jobs.retries); assertEquals(1, queue.active.size)
        assertTrue(queue.completed.isEmpty()); assertFalse(Shadows.shadowOf(service).isJobFinished)
        assertEquals(0, f.jobs.cancelled)
        assertEquals(AgendaWidgetFrameworkHealth.DEGRADED, f.framework.health.value)
        controller.destroy(); f.close()
    }

    @Test fun ordinaryJobWatchdogFinishesWithRetryAndCancellationRunsAfterInvalidation() {
        val f = Fixture(); var invalidatedBeforeCancellation = false
        f.work.action = { try { awaitCancellation() } finally { invalidatedBeforeCancellation = f.work.invalidated > 0 } }
        val controller = Robolectric.buildService(AgendaWidgetJobService::class.java).create(); val service = controller.get()
        assertTrue(service.onStartJob(params(AndroidAgendaWidgetJobs.PERIODIC_ID)))
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(51, TimeUnit.MILLISECONDS)
        assertTrue(invalidatedBeforeCancellation)
        assertTrue(Shadows.shadowOf(service).isJobFinished)
        assertTrue(Shadows.shadowOf(service).isRescheduleNeeded)
        controller.destroy(); f.close()
    }

    @Test fun staleOldGenerationStopCannotCancelReplacementAndFreshParcelStopCan() = runBlocking {
        val f = Fixture(); val releases = mutableListOf<CompletableDeferred<Unit>>()
        f.work.action = { val gate = CompletableDeferred<Unit>(); releases += gate; withContext(NonCancellable) { gate.await() } }
        val controller = Robolectric.buildService(AgendaWidgetJobService::class.java).create(); val service = controller.get()
        val old = params(AndroidAgendaWidgetJobs.PERIODIC_ID, "old"); val newer = params(AndroidAgendaWidgetJobs.PERIODIC_ID, "new")
        service.onStartJob(old); service.onStartJob(newer)
        val invalidations = f.work.invalidated
        assertTrue(service.onStopJob(old)); assertEquals(invalidations, f.work.invalidated)
        releases[0].complete(Unit); yield(); assertFalse(Shadows.shadowOf(service).isJobFinished)
        assertTrue(service.onStopJob(params(AndroidAgendaWidgetJobs.PERIODIC_ID, "new")))
        assertTrue(f.work.invalidated > invalidations)
        releases[1].complete(Unit); yield(); assertFalse(Shadows.shadowOf(service).isJobFinished)
        controller.destroy(); f.close()
    }

    @Test fun knownOlderCapturedParametersWithSameSpecAreRejectedAndRecoveryIgnoresCommands() = runBlocking {
        val f = Fixture(); f.work.action = { awaitCancellation() }
        val controller = Robolectric.buildService(AgendaWidgetJobService::class.java).create(); val service = controller.get()
        val old = params(AndroidAgendaWidgetJobs.PERIODIC_ID); val newer = params(AndroidAgendaWidgetJobs.PERIODIC_ID)
        service.onStartJob(old); service.onStartJob(newer)
        val invalidations = f.work.invalidated; service.onStopJob(old)
        assertEquals(invalidations, f.work.invalidated)
        val receiver = AgendaWidgetRecoveryReceiver()
        receiver.onReceive(context, Intent(Intent.ACTION_VIEW)); assertEquals(invalidations, f.work.invalidated)
        receiver.onReceive(context, Intent(Intent.ACTION_TIMEZONE_CHANGED)); assertTrue(f.work.invalidated > invalidations)
        service.onStopJob(newer); controller.destroy(); f.close()
    }
}

class WidgetFrameworkApplication : Application(), AgendaWidgetFrameworkOwner {
    override val agendaWidgetRuntime: AgendaWidgetRuntime get() = error("No application/runtime construction in framework tests")
    override lateinit var agendaWidgetFramework: AgendaWidgetFramework
}

@Implements(JobParameters::class)
class WidgetParametersShadow {
    @RealObject lateinit var real: JobParameters
    @Implementation fun dequeueWork(): JobWorkItem? = queues[real]?.next()
    @Implementation fun completeWork(work: JobWorkItem) { requireNotNull(queues[real]).complete(work) }
    companion object { val queues = WeakHashMap<JobParameters, AgendaWidgetFrameworkTest.Queue>() }
}

@Implements(className = "android.app.JobSchedulerImpl", isInAndroidSdk = false)
class AuditedWidgetScheduler : ShadowJobScheduler.ShadowJobSchedulerImpl() {
    @Implementation override fun enqueue(job: JobInfo, work: JobWorkItem): Int {
        enqueues += job to work
        return super.enqueue(job, work)
    }
    companion object { val enqueues = mutableListOf<Pair<JobInfo, JobWorkItem>>() }
}
