package dev.swapnilraj.calendar

import android.app.Application
import dev.swapnilraj.calendar.android.reminders.ReminderRuntimeStatus
import dev.swapnilraj.calendar.core.*
import java.time.Instant
import dev.swapnilraj.calendar.probe.UnboundReminderProbeSession
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ProbeReminderBindingsTest {
    private class Fixture(val read: Boolean = true) {
        val owner = SupervisorJob()
        val session = UnboundReminderProbeSession(CoroutineScope(owner + Dispatchers.Unconfined),
            canReadCalendar = { read })
        fun close() { owner.cancel() }
    }
    @Test fun constructionAndRuntimeReadsDoNotStartConfigurationOrFrameworkWork() {
        val f = Fixture()
        try {
            assertSame(f.session.runtime, f.session.runtime)
            assertEquals(0, f.session.snapshot().configurationAttempts); assertEquals(0, f.session.snapshot().inFlight)
            assertEquals(0, f.session.snapshot().retryRefusals)
        } finally { f.close() }
    }
    @Test fun unavailableStartupRefusesRecoveryAndKeepsTruthfulDegradedHealth() = runBlocking {
        val f = Fixture()
        try {
            f.session.runtime.onProcessStarted().join()
            val state = f.session.snapshot()
            assertEquals(ReminderRuntimeStatus.DEGRADED, state.health)
            assertEquals(1, state.configurationAttempts)
            assertEquals(1, state.retryAttempts)
            assertEquals(0, state.operationAttempts)
            assertEquals(0, state.registrationAttempts)
            assertEquals(0, state.cancellationAttempts)
            assertEquals(1, state.retryRefusals)
        } finally { f.close() }
    }
    @Test fun refusedRecoveryCannotPublishReadyOrDisabled() = runBlocking {
        val f = Fixture()
        try {
            f.session.runtime.onResume().join()
            assertEquals(ReminderRuntimeStatus.DEGRADED, f.session.snapshot().health)
            assertEquals(1, f.session.snapshot().retryRefusals)
            assertEquals(0, f.session.snapshot().operationAttempts)
        } finally { f.close() }
    }
    @Test fun deniedCalendarReadOnlyAttemptsUnacceptedSuppressionNeverFullDelivery() = runBlocking {
        val f = Fixture(read = false)
        try {
            f.session.runtime.onResume().join()
            val state = f.session.snapshot()
            assertEquals(ReminderRuntimeStatus.DEGRADED, state.health)
            assertEquals(1, state.operationAttempts)
            assertEquals(0, state.registrationAttempts)
            assertEquals(0, state.cancellationAttempts)
        } finally { f.close() }
    }
    @Test fun alarmHintCannotBypassUnavailableConfiguration() = runBlocking {
        val f = Fixture()
        try {
            f.session.runtime.reconcile(ReminderVersion(ReminderKey(CalendarId(1), 1, Instant.EPOCH, 0), ReminderCalendarIdentity("probe-fixture"), 1, 1))
            assertEquals(ReminderRuntimeStatus.DEGRADED, f.session.snapshot().health)
            assertEquals(0, f.session.snapshot().operationAttempts)
            assertEquals(1, f.session.snapshot().retryRefusals)
        } finally { f.close() }
    }
    @Test fun stoppedScopeCannotTurnUnavailableSetupIntoSuccessfulWork() = runBlocking {
        val f = Fixture(); f.close()
        f.session.runtime.onProcessStarted().join()
        assertEquals(0, f.session.snapshot().configurationAttempts); assertEquals(0, f.session.snapshot().inFlight)
        assertEquals(0, f.session.snapshot().retryRefusals)
    }
}
