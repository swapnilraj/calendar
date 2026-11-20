package dev.swapnilraj.calendar

import dev.swapnilraj.calendar.android.reminders.*
import dev.swapnilraj.calendar.core.*
import java.time.ZoneOffset
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class ReminderConfigurationOrderingTest {
    private class Jobs : ReminderJobController {
        var retries = 0
        override fun ensureRegistered(canObserveCalendar: Boolean) = true
        override fun rearmContent(canObserveCalendar: Boolean) = true
        override fun scheduleRetry(): Boolean { retries++; return true }
        override fun cancelAll() = Unit
    }
    private class Operations : ReminderOperations {
        var cycles = 0; var suppressions = 0
        var duringCycle: (suspend () -> Unit)? = null
        override fun suppressDelivery(): ReminderSuppressionResult { suppressions++; return ReminderSuppressionResult(true, AlarmSchedulingResult.CANCELLED) }
        override suspend fun reconcile(): ReminderCycleResult { cycles++; duringCycle?.invoke(); return ReminderCycleResult(ReminderCycleStatus.READY, AlarmSchedulingResult.EXACT) }
        override suspend fun onAlarm(version: ReminderVersion) = reconcile()
        override suspend fun snooze(identity: ReminderPostIdentity, minutes: Int) = ReminderActionResult.IGNORED
        override suspend fun dismiss(identity: ReminderPostIdentity) = ReminderActionResult.IGNORED
    }
    private val config = ReminderConfiguration(true, emptySet(), ZoneOffset.UTC)
    private val ready = ReminderPermissionState(true, true, true, true, true)

    @Test fun acknowledgedSaveCannotOvertakeOldCycleAndQueuedCycleReadsNewState() = runTest {
        val operations = Operations(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var value = config; val observations = mutableListOf<Boolean>()
        operations.duringCycle = {
            observations += value.enabled
            if (operations.cycles == 1) { entered.complete(Unit); release.await() }
        }
        val runtime = ReminderRuntime(operations, Jobs(), this, { value }, { true })
        val old = runtime.onResume(); runCurrent(); assertTrue(entered.isCompleted)
        var written = false
        val saving = async { runtime.updateConfiguration { value = config.copy(enabled = false); written = true; "ACK" } }
        runCurrent(); assertFalse(written); assertFalse(saving.isCompleted)
        release.complete(Unit); runCurrent(); old.join()
        assertEquals("ACK", saving.await()); runCurrent()
        assertEquals(listOf(true, false), observations)
    }

    @Test fun cancelledOrUncertainWriteStillQueuesAppOwnedReconcileAfterReleasingBoundary() = runTest {
        for (cancelled in listOf(true, false)) {
            val operations = Operations(); val runtime = ReminderRuntime(operations, Jobs(), this, { config }, { true })
            val entered = CompletableDeferred<Unit>()
            val saving = launch {
                try {
                    runtime.updateConfiguration {
                        entered.complete(Unit)
                        if (cancelled) awaitCancellation() else throw java.io.IOException("Unknown ACK")
                    }
                } catch (_: java.io.IOException) { }
            }
            runCurrent(); assertTrue(entered.isCompleted)
            if (cancelled) saving.cancelAndJoin() else saving.join()
            runCurrent()
            assertEquals(1, operations.cycles)
        }
    }

    @Test fun updateMarksCheckingUntilLatestPostWriteCycleCompletes() = runTest {
        val operations = Operations(); val runtime = ReminderRuntime(operations, Jobs(), this, { config }, { true })
        runtime.onResume(); runCurrent(); assertEquals(ReminderRuntimeStatus.READY, runtime.health.value.status)
        val release = CompletableDeferred<Unit>(); operations.duringCycle = { release.await() }
        runtime.updateConfiguration { assertEquals(ReminderRuntimeStatus.UNKNOWN, runtime.health.value.status) }
        runCurrent(); assertEquals(ReminderRuntimeStatus.UNKNOWN, runtime.health.value.status)
        release.complete(Unit); runCurrent(); assertEquals(ReminderRuntimeStatus.READY, runtime.health.value.status)
    }

    @Test fun unreadableSettingsUsesOnlyNarrowSuppressionForProvenLossNeverFullReconcile() = runTest {
        for (mode in 0..4) {
            val operations = Operations(); val jobs = Jobs()
            val callback: (() -> ReminderPermissionState)? = when (mode) {
                1 -> ({ ready.copy(notificationPermission = false) })
                2 -> ({ error("Unknown permission service") })
                3 -> ({ ready.copy(exactAlarmAccess = false) })
                else -> null
            }
            val runtime = ReminderRuntime(operations, jobs, this, { error("Unreadable settings") }, { mode != 4 }, deliveryPermissions = callback)
            runtime.onResume(); runCurrent()
            assertEquals(0, operations.cycles)
            assertEquals(if (mode == 1 || mode == 4) 1 else 0, operations.suppressions)
            assertEquals(ReminderRuntimeStatus.UNAVAILABLE, runtime.health.value.status)
            assertTrue(jobs.retries > 0)
        }
    }
}
