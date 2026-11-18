package dev.swapnilraj.calendar.android.reminders

import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import dev.swapnilraj.calendar.core.ReminderConfiguration
import dev.swapnilraj.calendar.core.ReminderPostIdentity
import dev.swapnilraj.calendar.core.ReminderVersion
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The Application owns exactly one runtime/coordinator. Framework components never create stores. */
interface ReminderRuntimeOwner { val reminderRuntime: ReminderRuntime }

interface ReminderOperations {
    suspend fun reconcile(): ReminderCycleResult
    suspend fun onAlarm(version: ReminderVersion): ReminderCycleResult
    suspend fun snooze(identity: ReminderPostIdentity, minutes: Int): ReminderActionResult
    suspend fun dismiss(identity: ReminderPostIdentity): ReminderActionResult
}

class CoordinatorReminderOperations(private val coordinator: ReminderCoordinator) : ReminderOperations {
    override suspend fun reconcile() = coordinator.reconcile()
    override suspend fun onAlarm(version: ReminderVersion) = coordinator.onAlarm(version)
    override suspend fun snooze(identity: ReminderPostIdentity, minutes: Int) = coordinator.snooze(identity, minutes)
    override suspend fun dismiss(identity: ReminderPostIdentity) = coordinator.dismiss(identity)
}

interface ReminderJobController {
    /** Idempotent registration. Content observation requires current READ_CALENDAR access. */
    fun ensureRegistered(canObserveCalendar: Boolean): Boolean
    fun rearmContent(canObserveCalendar: Boolean): Boolean
    fun scheduleRetry(): Boolean
    fun cancelAll()
}

enum class ReminderRuntimeStatus { UNKNOWN, READY, DEGRADED, DISABLED, PERMISSION_REQUIRED, UNAVAILABLE }
data class ReminderRuntimeHealth(val status: ReminderRuntimeStatus, val cycle: ReminderCycleResult? = null)

/** Configuration failure is not an empty/disabled configuration. No delivery operation runs until
 * configuration has been read successfully. The coordinator's own supplier must also throw on failure.
 * The injected application scope should use SupervisorJob + Dispatchers.IO, never an Activity scope.
 */
class ReminderRuntime(
    private val operations: ReminderOperations,
    private val jobs: ReminderJobController,
    private val scope: CoroutineScope,
    private val configuration: suspend () -> ReminderConfiguration,
    private val canObserveCalendar: () -> Boolean,
    private val broadcastTimeoutMillis: Long = 8_000,
    private val jobTimeoutMillis: Long = 120_000,
) {
    private val mutableHealth = MutableStateFlow(ReminderRuntimeHealth(ReminderRuntimeStatus.UNKNOWN))
    val health: StateFlow<ReminderRuntimeHealth> = mutableHealth.asStateFlow()

    init { require(broadcastTimeoutMillis > 0 && jobTimeoutMillis > 0) }

    /** Startup registers recovery only. Resume also reconciles changes made in system settings. */
    fun onProcessStarted(): Job = scope.launch {
        val registration = register()
        if (registration == null || !registration.healthy) retry()
    }
    fun onResume(): Job = scope.launch { reconcile() }

    private data class Registration(val configuration: ReminderConfiguration, val healthy: Boolean)
    private suspend fun register(): Registration? {
        val config = try { configuration() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { unavailable(); return null }
        currentCoroutineContext().ensureActive()
        val healthy = try {
            if (config.enabled) jobs.ensureRegistered(canObserveCalendar()) else { jobs.cancelAll(); true }
        } catch (_: RuntimeException) { false }
        if (!healthy) degraded()
        return Registration(config, healthy)
    }

    suspend fun reconcile(alarm: ReminderVersion? = null, retryOnFailure: Boolean = true): Boolean {
        val registration = register()
        if (registration == null) {
            if (retryOnFailure) retry()
            return true
        }
        return try {
            val result = if (alarm == null) operations.reconcile() else operations.onAlarm(alarm)
            currentCoroutineContext().ensureActive()
            val registrationFailed = !registration.healthy
            val status = when (result.status) {
                ReminderCycleStatus.READY -> if (registrationFailed) ReminderRuntimeStatus.DEGRADED else ReminderRuntimeStatus.READY
                ReminderCycleStatus.DEGRADED -> ReminderRuntimeStatus.DEGRADED
                ReminderCycleStatus.DISABLED -> ReminderRuntimeStatus.DISABLED
                ReminderCycleStatus.PERMISSION_REQUIRED -> ReminderRuntimeStatus.PERMISSION_REQUIRED
                ReminderCycleStatus.UNAVAILABLE -> ReminderRuntimeStatus.UNAVAILABLE
            }
            mutableHealth.value = ReminderRuntimeHealth(status, result)
            val needsRetry = status in setOf(ReminderRuntimeStatus.UNAVAILABLE, ReminderRuntimeStatus.DEGRADED)
            if (needsRetry && retryOnFailure) retry()
            needsRetry
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { unavailable(); if (retryOnFailure) retry(); true }
    }

    suspend fun action(identity: ReminderPostIdentity, snoozeMinutes: Int? = null) {
        val registration = register()
        if (registration == null) { retry(); return }
        if (!registration.healthy) retry()
        try {
            val result = if (snoozeMinutes == null) operations.dismiss(identity)
                else operations.snooze(identity, snoozeMinutes)
            currentCoroutineContext().ensureActive()
            if (result == ReminderActionResult.SOURCE_UNAVAILABLE) { unavailable(); retry() }
            else if (result == ReminderActionResult.PERMISSION_REQUIRED)
                mutableHealth.value = ReminderRuntimeHealth(ReminderRuntimeStatus.PERMISSION_REQUIRED)
            // An ignored action is not evidence that delivery is healthy.
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { unavailable(); retry() }
    }

    /** Completion registration also finishes a broadcast cancelled before its coroutine body starts. */
    fun broadcast(finished: () -> Unit, work: suspend ReminderRuntime.() -> Unit): Job {
        val once = AtomicBoolean()
        fun finishOnce() { if (once.compareAndSet(false, true)) finished() }
        val handler = Handler(Looper.getMainLooper())
        val job = scope.launch {
            try { work() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { unavailable(); retry() }
        }
        // A provider may ignore coroutine cancellation. Finish the broadcast at the deadline
        // independently; the durable job retries reconciliation, while CAS guards in-flight work.
        val deadline = Runnable { unavailable(); retry(); finishOnce(); job.cancel() }
        handler.postDelayed(deadline, broadcastTimeoutMillis)
        job.invokeOnCompletion {
            handler.removeCallbacks(deadline)
            if (it is CancellationException) retry()
            finishOnce()
        }
        return job
    }

    /** LAZY lets JobService install its cancellation/finish guard before work can complete. */
    fun job(contentTriggered: Boolean, finished: (Boolean) -> Unit): Job = scope.launch(start = CoroutineStart.LAZY) {
        var retry = true
        try { withTimeout(jobTimeoutMillis) { retry = reconcile(retryOnFailure = false) } }
        catch (_: TimeoutCancellationException) { unavailable() }
        catch (cancelled: CancellationException) { throw cancelled }
        finally {
            if (contentTriggered) {
                try {
                    val config = configuration()
                    if (config.enabled && !jobs.rearmContent(canObserveCalendar())) { degraded(); retry = true }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { unavailable(); retry = true }
            }
        }
        finished(retry)
    }

    private fun unavailable() { mutableHealth.value = ReminderRuntimeHealth(ReminderRuntimeStatus.UNAVAILABLE) }
    private fun degraded() { mutableHealth.value = ReminderRuntimeHealth(ReminderRuntimeStatus.DEGRADED) }
    fun requestRetry() = retry()

    private fun retry() { try { if (!jobs.scheduleRetry()) degraded() } catch (_: RuntimeException) { degraded() } }
}
