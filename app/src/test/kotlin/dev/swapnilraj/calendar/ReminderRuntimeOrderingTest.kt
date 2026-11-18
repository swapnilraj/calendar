package dev.swapnilraj.calendar

import dev.swapnilraj.calendar.android.reminders.*
import dev.swapnilraj.calendar.core.*
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Controlled configuration-read suspension reproduces lifecycle ordering without sleeps or events. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReminderRuntimeOrderingTest {
    private class Jobs : ReminderJobController {
        var periodic = false
        var content = false
        val transitions = mutableListOf<String>()
        override fun ensureRegistered(canObserveCalendar: Boolean): Boolean {
            transitions += "register"; periodic = true; content = canObserveCalendar; return true
        }
        override fun rearmContent(canObserveCalendar: Boolean): Boolean {
            transitions += "rearm"; content = canObserveCalendar; return true
        }
        override fun scheduleRetry() = true
        override fun cancelAll() { transitions += "cancel"; periodic = false; content = false }
    }
    private class Operations(private val enabled: () -> Boolean) : ReminderOperations {
        override fun suppressDelivery() = ReminderSuppressionResult(true, AlarmSchedulingResult.CANCELLED)
        override suspend fun reconcile() = if (enabled())
            ReminderCycleResult(ReminderCycleStatus.READY, AlarmSchedulingResult.EXACT)
        else ReminderCycleResult(ReminderCycleStatus.DISABLED, AlarmSchedulingResult.CANCELLED)
        override suspend fun onAlarm(version: ReminderVersion) = reconcile()
        override suspend fun snooze(identity: ReminderPostIdentity, minutes: Int) = ReminderActionResult.IGNORED
        override suspend fun dismiss(identity: ReminderPostIdentity) = ReminderActionResult.IGNORED
    }

    @Test fun delayedDisabledStartupCannotCancelJobsRegisteredByNewerEnabledResume() = runTest {
        var config = ReminderConfiguration(false, emptySet(), ZoneOffset.UTC)
        val firstReadEntered = CompletableDeferred<Unit>()
        val releaseFirstRead = CompletableDeferred<Unit>()
        var reads = 0
        val jobs = Jobs()
        val runtime = ReminderRuntime(Operations { config.enabled }, jobs, this, {
            val captured = config
            if (++reads == 1) { firstReadEntered.complete(Unit); releaseFirstRead.await() }
            captured
        }, { true })
        val startup = runtime.onProcessStarted()
        runCurrent()
        assertTrue(firstReadEntered.isCompleted)
        config = config.copy(enabled = true)
        val resume = runtime.onResume()
        runCurrent() // Old runtime finishes resume here; ordered runtime waits behind startup.
        releaseFirstRead.complete(Unit)
        runCurrent()
        startup.join(); resume.join()
        assertTrue(reads >= 2)
        assertTrue("The final enabled configuration requires periodic recovery", jobs.periodic)
        assertTrue("The final enabled configuration requires provider observation", jobs.content)
        assertEquals("register", jobs.transitions.last())
        assertEquals(ReminderRuntimeStatus.READY, runtime.health.value.status)
    }

    @Test fun delayedEnabledContentRearmCannotResurrectObservationAfterDisable() = runTest {
        var config = ReminderConfiguration(true, emptySet(), ZoneOffset.UTC)
        val rearmReadEntered = CompletableDeferred<Unit>()
        val releaseRearmRead = CompletableDeferred<Unit>()
        var reads = 0
        val jobs = Jobs()
        val runtime = ReminderRuntime(Operations { config.enabled }, jobs, this, {
            val captured = config
            // Content execution reads configuration for registration, then again for final rearm.
            if (++reads == 2) { rearmReadEntered.complete(Unit); releaseRearmRead.await() }
            captured
        }, { true })
        var jobRequestedRetry: Boolean? = null
        val content = runtime.job(contentTriggered = true) { jobRequestedRetry = it }
        content.start()
        runCurrent()
        assertTrue(rearmReadEntered.isCompleted)
        config = config.copy(enabled = false)
        val disable = runtime.onResume()
        runCurrent() // Try disabling while an older enabled rearm snapshot is held.
        releaseRearmRead.complete(Unit)
        runCurrent()
        content.join(); disable.join()
        assertTrue(reads >= 3)
        assertEquals(false, jobRequestedRetry)
        assertFalse(jobs.periodic)
        assertFalse("An older enabled read must not rearm after the disabled registration", jobs.content)
        assertEquals("cancel", jobs.transitions.last())
        assertEquals(ReminderRuntimeStatus.DISABLED, runtime.health.value.status)
    }
}
