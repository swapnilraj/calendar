package dev.swapnilraj.calendar

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.content.BroadcastReceiver
import android.os.Bundle
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import dev.swapnilraj.calendar.android.reminders.*
import dev.swapnilraj.calendar.core.*
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import org.robolectric.shadows.ShadowBroadcastPendingResult

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ReminderFrameworkApplication::class)
class AndroidReminderFrameworkTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val scheduler get() = context.getSystemService(JobScheduler::class.java)
    private val service get() = ComponentName(context, ReminderJobService::class.java)
    private val receiver get() = ComponentName(context, ReminderActionReceiver::class.java)
    private val now = Instant.parse("2026-11-18T09:00:00Z")
    private val identity get() = ReminderIdentity(EventRef(CalendarId(1), 2, now, now.plusSeconds(3600)),
        now.minusSeconds(600), "generation", "post-token")
    private val targets get() = ReminderIntentTargets(ComponentName(context.packageName, "EventActivity"), receiver)
    private var calls = 0
    private var actions = 0
    private var block = false
    private lateinit var scope: CoroutineScope
    private lateinit var runtime: ReminderRuntime
    private lateinit var jobs: AndroidReminderJobs

    @Before fun setup() {
        scheduler.cancelAll()
        jobs = AndroidReminderJobs(context, service)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        runtime = ReminderRuntime(object : ReminderOperations {
            override fun suppressDelivery() = ReminderSuppressionResult(true, AlarmSchedulingResult.CANCELLED)
            override suspend fun reconcile(): ReminderCycleResult {
                calls++
                if (block) awaitCancellation()
                return ReminderCycleResult(ReminderCycleStatus.READY, AlarmSchedulingResult.EXACT)
            }
            override suspend fun onAlarm(version: ReminderVersion) = reconcile()
            override suspend fun snooze(identity: ReminderPostIdentity, minutes: Int): ReminderActionResult { actions++; return ReminderActionResult.APPLIED }
            override suspend fun dismiss(identity: ReminderPostIdentity): ReminderActionResult { actions++; return ReminderActionResult.APPLIED }
        }, jobs, scope, { ReminderConfiguration(true, emptySet(), ZoneOffset.UTC) }, { true })
        (context as ReminderFrameworkApplication).reminderRuntime = runtime
    }

    @Test fun jobRegistrationSeparatesPersistedPeriodicFromNonPersistedContentWithoutNetwork() {
        assertTrue(jobs.ensureRegistered(true))
        val periodic = scheduler.getPendingJob(AndroidReminderJobs.PERIODIC_ID)!!
        val content = scheduler.getPendingJob(AndroidReminderJobs.CONTENT_ID)!!
        assertTrue(periodic.isPersisted)
        assertTrue(periodic.isPeriodic)
        assertEquals(15 * 60_000L, periodic.intervalMillis)
        assertNull(periodic.triggerContentUris)
        assertFalse(content.isPersisted)
        assertFalse(content.isPeriodic)
        assertEquals(CalendarContract.CONTENT_URI, content.triggerContentUris!!.single().uri)
        assertEquals(JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS, content.triggerContentUris!!.single().flags)
        assertNull(periodic.requiredNetwork)
        assertNull(content.requiredNetwork)
        assertEquals(service, periodic.service)
        assertEquals(service, content.service)
        assertTrue(jobs.ensureRegistered(true))
        assertEquals(2, scheduler.allPendingJobs.size)
        scope.cancel()
    }

    @Test fun lostReadAccessCancelsOnlyObservationAndRetryRequestsCoalesce() {
        jobs.ensureRegistered(true)
        jobs.ensureRegistered(false)
        assertNull(scheduler.getPendingJob(AndroidReminderJobs.CONTENT_ID))
        assertNotNull(scheduler.getPendingJob(AndroidReminderJobs.PERIODIC_ID))
        assertTrue(jobs.scheduleRetry()); val retry = scheduler.getPendingJob(AndroidReminderJobs.RETRY_ID)
        assertTrue(jobs.scheduleRetry())
        assertSame(retry, scheduler.getPendingJob(AndroidReminderJobs.RETRY_ID))
        assertEquals(30_000L, retry!!.minLatencyMillis)
        assertFalse(retry.isPersisted)
        val unrelated = JobInfo.Builder(123, service).setMinimumLatency(1).build()
        scheduler.schedule(unrelated)
        jobs.cancelAll()
        assertEquals(listOf(123), scheduler.allPendingJobs.map { it.id })
        scope.cancel()
    }

    @Test fun schedulerQuotaFailureIsVisibleAndContentCanRearmAfterConsumption() {
        shadowOf(scheduler).failOnJob(AndroidReminderJobs.PERIODIC_ID)
        assertFalse(jobs.ensureRegistered(true))
        scheduler.cancel(AndroidReminderJobs.CONTENT_ID)
        assertTrue(jobs.rearmContent(true))
        assertNotNull(scheduler.getPendingJob(AndroidReminderJobs.CONTENT_ID))
        assertTrue(jobs.rearmContent(false))
        assertNull(scheduler.getPendingJob(AndroidReminderJobs.CONTENT_ID))
        scope.cancel()
    }

    @Test fun actionDecoderRequiresCanonicalComponentActionTokenUriAndTypedMinutes() {
        val valid = ReminderIntents.intent(context, targets, identity, ReminderIntents.ACTION_SNOOZE, 10)
        assertEquals(10, ReminderActionDecoder.decode(context, valid, receiver)!!.snoozeMinutes)
        assertNull(ReminderActionDecoder.decode(context, Intent(valid).setComponent(service), receiver))
        assertNull(ReminderActionDecoder.decode(context, Intent(valid).setAction(ReminderIntents.ACTION_OPEN), receiver))
        assertNull(ReminderActionDecoder.decode(context, Intent(valid).setData(android.net.Uri.parse("https://example.com")), receiver))
        assertNull(ReminderActionDecoder.decode(context, Intent(valid).putExtra(ReminderIntents.EXTRA_SNOOZE_MINUTES, "10"), receiver))
        val legacy = ReminderIntents.intent(context, targets, identity.copy(postToken = null), ReminderIntents.ACTION_DISMISS)
        assertNull(ReminderActionDecoder.decode(context, legacy, receiver))
        val dismiss = ReminderIntents.intent(context, targets, identity, ReminderIntents.ACTION_DISMISS)
        assertNotNull(ReminderActionDecoder.decode(context, dismiss, receiver))
        assertNull(ReminderActionDecoder.decode(context, Intent(dismiss).putExtra(ReminderIntents.EXTRA_SNOOZE_MINUTES, 5), receiver))
        scope.cancel()
    }

    @Test fun receiversIgnoreMalformedAlarmsActionsAndProviderEventCommands() {
        ReminderAlarmReceiver().onReceive(context, Intent(ReminderAlarmIntents.ACTION_DELIVER))
        ReminderActionReceiver().onReceive(context, Intent(ReminderIntents.ACTION_DISMISS))
        ReminderProviderHintReceiver().onReceive(context, Intent(ReminderIntents.ACTION_SNOOZE))
        ReminderRecoveryReceiver().onReceive(context, Intent(Intent.ACTION_VIEW))
        assertEquals(0, calls); assertEquals(0, actions)
        assertTrue(scheduler.allPendingJobs.isEmpty())
        // Valid provider action schedules only a coalesced job; extras never become event commands.
        ReminderProviderHintReceiver().onReceive(context, Intent(CalendarContract.ACTION_EVENT_REMINDER)
            .putExtra(ReminderIntents.EXTRA_SNOOZE_MINUTES, 30).setData(android.net.Uri.parse("https://untrusted.example")))
        assertEquals(0, calls); assertEquals(0, actions)
        assertNotNull(scheduler.getPendingJob(AndroidReminderJobs.RETRY_ID))
        scope.cancel()
    }

    private fun pending(receiver: BroadcastReceiver): BroadcastReceiver.PendingResult {
        val result = ReflectionHelpers.callStaticMethod<BroadcastReceiver.PendingResult>(
            ShadowBroadcastPendingResult::class.java, "create",
            ClassParameter.from(Int::class.javaPrimitiveType!!, 0),
            ClassParameter.from(String::class.java, null),
            ClassParameter.from(Bundle::class.java, null),
            ClassParameter.from(Boolean::class.javaPrimitiveType!!, false))
        ReflectionHelpers.setField(receiver, "mPendingResult", result)
        return result
    }

    @Test fun validPrivateActionFinishesRealPendingResultAndOnlyInvokesMatchingOperation() {
        val actionReceiver = ReminderActionReceiver()
        val pending = pending(actionReceiver)
        actionReceiver.onReceive(context, ReminderIntents.intent(context, targets, identity, ReminderIntents.ACTION_SNOOZE, 5))
        assertTrue(shadowOf(actionReceiver).wentAsync())
        assertTrue(shadowOf(pending).future.isDone)
        assertEquals(1, actions)
        assertEquals(0, calls)
        scope.cancel()
    }

    @Test fun validAlarmAndRecoverySignalsReconcileAndFinishEvenWithCancelledApplicationScope() {
        val alarmReceiver = ReminderAlarmReceiver()
        val pending = pending(alarmReceiver)
        val version = ReminderVersion(ReminderKey.of(identity.event, 10), ReminderCalendarIdentity("calendar"), 1, 1)
        alarmReceiver.onReceive(context, ReminderAlarmIntents.intent(ComponentName(context, ReminderAlarmReceiver::class.java),
            ReminderAlarmRequest(now, version)))
        assertEquals(1, calls)
        assertTrue(shadowOf(pending).future.isDone)
        val recovery = ReminderRecoveryReceiver()
        val recoveryPending = pending(recovery)
        recovery.onReceive(context, Intent(Intent.ACTION_TIMEZONE_CHANGED))
        assertEquals(2, calls)
        assertTrue(shadowOf(recoveryPending).future.isDone)
        scope.cancel()
        val cancelledReceiver = ReminderRecoveryReceiver()
        val cancelledPending = pending(cancelledReceiver)
        cancelledReceiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(2, calls)
        assertTrue(shadowOf(cancelledPending).future.isDone)
        assertNotNull(scheduler.getPendingJob(AndroidReminderJobs.RETRY_ID))
    }

    private fun parameters(id: Int): JobParameters = ReflectionHelpers.newInstance(JobParameters::class.java).also {
        ReflectionHelpers.setField(it, "jobId", id)
    }

    @Test fun jobServiceCompletesAndIgnoresUnknownJobIds() {
        val controller = Robolectric.buildService(ReminderJobService::class.java).create()
        val service = controller.get()
        assertFalse(service.onStartJob(parameters(5)))
        assertTrue(service.onStartJob(parameters(AndroidReminderJobs.RETRY_ID)))
        assertEquals(1, calls)
        assertTrue(shadowOf(service).isJobFinished)
        assertFalse(shadowOf(service).isRescheduleNeeded)
        controller.destroy(); scope.cancel()
    }

    @Test fun stoppedJobCancelsWorkerAndNeverCallsJobFinished() {
        block = true
        val controller = Robolectric.buildService(ReminderJobService::class.java).create()
        val service = controller.get(); val params = parameters(AndroidReminderJobs.CONTENT_ID)
        assertTrue(service.onStartJob(params))
        assertEquals(1, calls)
        assertTrue(service.onStopJob(parameters(AndroidReminderJobs.CONTENT_ID)))
        assertFalse(shadowOf(service).isJobFinished)
        assertFalse(service.onStopJob(parameters(5)))
        controller.destroy(); scope.cancel()
    }
}
