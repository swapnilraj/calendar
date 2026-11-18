package dev.swapnilraj.calendar

import android.app.Application
import android.os.Looper
import dev.swapnilraj.calendar.android.reminders.*
import dev.swapnilraj.calendar.core.*
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReminderRuntimeTest {
    private class Jobs : ReminderJobController {
        var registrations = 0; var retries = 0; var cancellations = 0; var rearmed = 0
        var observe = false; var success = true
        override fun ensureRegistered(canObserveCalendar: Boolean): Boolean { registrations++; observe = canObserveCalendar; return success }
        override fun rearmContent(canObserveCalendar: Boolean): Boolean { rearmed++; observe = canObserveCalendar; return success }
        override fun scheduleRetry(): Boolean { retries++; return success }
        override fun cancelAll() { cancellations++ }
    }
    private class Operations : ReminderOperations {
        override fun suppressDelivery() = ReminderSuppressionResult(true, AlarmSchedulingResult.CANCELLED)
        var reconciles = 0; var actions = 0
        var block: (suspend () -> Unit)? = null
        var result = ReminderCycleResult(ReminderCycleStatus.READY, AlarmSchedulingResult.EXACT)
        override suspend fun reconcile(): ReminderCycleResult { reconciles++; block?.invoke(); return result }
        override suspend fun onAlarm(version: ReminderVersion) = reconcile()
        override suspend fun snooze(identity: ReminderPostIdentity, minutes: Int): ReminderActionResult { actions++; return ReminderActionResult.IGNORED }
        override suspend fun dismiss(identity: ReminderPostIdentity): ReminderActionResult { actions++; return ReminderActionResult.IGNORED }
    }
    private class Fixture {
        val jobs = Jobs(); val operations = Operations()
        val owner = SupervisorJob(); val scope = CoroutineScope(owner + Dispatchers.Unconfined)
        var config = ReminderConfiguration(true, emptySet(), ZoneOffset.UTC)
        var configFails = false; var observe = true
        val runtime = ReminderRuntime(operations, jobs, scope,
            { if (configFails) error("Unreadable configuration") else config }, { observe }, 50, 50)
    }

    @Test fun startupRegistersWithoutClaimingReadyOrQueryingEventsAndResumeReconciles() = runBlocking {
        val f = Fixture()
        assertEquals(ReminderRuntimeStatus.UNKNOWN, f.runtime.health.value.status)
        f.runtime.onProcessStarted().join()
        assertEquals(0, f.operations.reconciles)
        assertEquals(ReminderRuntimeStatus.UNKNOWN, f.runtime.health.value.status)
        f.runtime.onResume().join()
        assertEquals(1, f.operations.reconciles)
        assertEquals(ReminderRuntimeStatus.READY, f.runtime.health.value.status)
        f.owner.cancel()
    }

    @Test fun unreadableConfigurationPreservesOperationsAndRetriesNeverTreatingItAsDisabled() = runBlocking {
        val f = Fixture(); f.configFails = true
        assertTrue(f.runtime.reconcile())
        assertEquals(0, f.operations.reconciles)
        assertEquals(0, f.jobs.cancellations)
        assertEquals(0, f.jobs.registrations)
        assertEquals(1, f.jobs.retries)
        assertEquals(ReminderRuntimeStatus.UNAVAILABLE, f.runtime.health.value.status)
        f.owner.cancel()
    }

    @Test fun startupFailureQueuesRecoveryAndSuccessfulReadCanRecoverHealth() = runBlocking {
        val f = Fixture(); f.configFails = true
        f.runtime.onProcessStarted().join()
        assertEquals(1, f.jobs.retries)
        f.configFails = false
        assertFalse(f.runtime.reconcile())
        assertEquals(ReminderRuntimeStatus.READY, f.runtime.health.value.status)
        f.owner.cancel()
    }

    @Test fun failedStartupRegistrationSchedulesRetryWithoutQueryingEvents() = runBlocking {
        val f = Fixture(); f.jobs.success = false
        f.runtime.onProcessStarted().join()
        assertEquals(1, f.jobs.retries)
        assertEquals(0, f.operations.reconciles)
        assertEquals(ReminderRuntimeStatus.DEGRADED, f.runtime.health.value.status)
        f.owner.cancel()
    }

    @Test fun disabledConfigurationCancelsOwnJobsButStillRunsCoordinatorCleanup() = runBlocking {
        val f = Fixture(); f.config = f.config.copy(enabled = false)
        f.operations.result = ReminderCycleResult(ReminderCycleStatus.DISABLED, AlarmSchedulingResult.CANCELLED)
        f.runtime.reconcile()
        assertEquals(1, f.jobs.cancellations)
        assertEquals(1, f.operations.reconciles)
        assertEquals(ReminderRuntimeStatus.DISABLED, f.runtime.health.value.status)
        assertEquals(0, f.jobs.retries)
        f.owner.cancel()
    }

    @Test fun readPermissionOnlyControlsObservationAndJobFailureCannotReportReady() = runBlocking {
        val f = Fixture(); f.observe = false; f.jobs.success = false
        assertTrue(f.runtime.reconcile())
        assertFalse(f.jobs.observe)
        assertEquals(ReminderRuntimeStatus.DEGRADED, f.runtime.health.value.status)
        f.jobs.success = true
        assertFalse(f.runtime.reconcile())
        assertEquals(ReminderRuntimeStatus.READY, f.runtime.health.value.status)
        f.owner.cancel()
    }

    @Test fun contentJobRearmsAndReturnsRetryWithoutReplacingItsOwnJob() = runBlocking {
        val f = Fixture(); f.operations.result = ReminderCycleResult(ReminderCycleStatus.UNAVAILABLE, AlarmSchedulingResult.CANCELLED)
        var retry: Boolean? = null
        val job = f.runtime.job(true) { retry = it }
        assertEquals(0, f.operations.reconciles)
        job.start(); job.join()
        assertEquals(true, retry)
        assertEquals(1, f.jobs.rearmed)
        assertEquals(0, f.jobs.retries)
        f.owner.cancel()
    }

    @Test fun timedOutJobRequestsRescheduleAndDoesNotClaimReady() = runBlocking {
        val f = Fixture(); f.operations.block = { awaitCancellation() }
        var retry: Boolean? = null
        val job = f.runtime.job(false) { retry = it }; job.start(); job.join()
        assertEquals(true, retry)
        assertEquals(ReminderRuntimeStatus.UNAVAILABLE, f.runtime.health.value.status)
        f.owner.cancel()
    }

    @Test fun broadcastFinishesOnceOnSuccessAndBeforeStartCancellation() = runBlocking {
        val f = Fixture(); var finishes = 0
        f.runtime.broadcast({ finishes++ }) { reconcile() }.join()
        assertEquals(1, finishes)
        f.owner.cancel()
        f.runtime.broadcast({ finishes++ }) { error("Must not execute") }.join()
        assertEquals(2, finishes)
        assertTrue(f.jobs.retries > 0)
    }

    @Test fun broadcastDeadlineFinishesBeforeCancellationResistantWorkEndsAndQueuesRecovery() = runBlocking {
        val f = Fixture(); var finishes = 0
        val release = CompletableDeferred<Unit>()
        f.operations.block = { withContext(NonCancellable) { release.await() } }
        val job = f.runtime.broadcast({ finishes++ }) { reconcile() }
        shadowOf(Looper.getMainLooper()).idleFor(51, TimeUnit.MILLISECONDS)
        assertEquals(1, finishes)
        assertFalse(job.isCompleted)
        assertEquals(1, f.jobs.retries)
        release.complete(Unit); job.join()
        assertEquals(ReminderRuntimeStatus.UNAVAILABLE, f.runtime.health.value.status)
        assertEquals(1, finishes)
        f.owner.cancel()
    }
}

/** Framework test application deliberately has no real provider, database, or composition root. */
class ReminderFrameworkApplication : Application(), ReminderRuntimeOwner {
    override lateinit var reminderRuntime: ReminderRuntime
}
