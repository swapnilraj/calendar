package dev.swapnilraj.calendar.probe

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import dev.swapnilraj.calendar.android.reminders.*
import dev.swapnilraj.calendar.core.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

/** Only fixed code-origin categories escape this classifier; no trace, method, message or payload. */
enum class ProbeReminderCaller { NATIVE, RECOVERY, ALARM, ACTION, JOB, UNKNOWN }

object ProbeReminderCallers {
    private val known = mapOf(
        "dev.swapnilraj.calendar.probe.NativeReminderProbe" to ProbeReminderCaller.NATIVE,
        "dev.swapnilraj.calendar.android.reminders.ReminderRecoveryReceiver" to ProbeReminderCaller.RECOVERY,
        "dev.swapnilraj.calendar.android.reminders.ReminderAlarmReceiver" to ProbeReminderCaller.ALARM,
        "dev.swapnilraj.calendar.android.reminders.ReminderActionReceiver" to ProbeReminderCaller.ACTION,
        "dev.swapnilraj.calendar.android.reminders.ReminderJobService" to ProbeReminderCaller.JOB,
    )
    fun classify(classesNearestFirst: Iterable<String>): ProbeReminderCaller =
        classesNearestFirst.firstNotNullOfOrNull(known::get) ?: ProbeReminderCaller.UNKNOWN
    internal fun capture(): ProbeReminderCaller = classify(Thread.currentThread().stackTrace.map { it.className })
}

/** No binding API or real job/store/provider/notification adapter exists before future ownership. */
object ProbeReminderBindings {
    const val PACKAGE = "dev.swapnilraj.calendar.reminderprobe"
    private var owner: Application? = null
    private var session: UnboundReminderProbeSession? = null

    @Synchronized
    fun runtime(application: Application): ReminderRuntime = session(application).let {
        it.recordCaller(ProbeReminderCallers.capture())
        it.runtime
    }

    @Synchronized
    fun snapshot(application: Application): ProbeReminderStartup = session(application).snapshot()

    @Synchronized
    private fun session(application: Application): UnboundReminderProbeSession {
        session?.let { return if (owner === application) it else refusedSession }
        owner = application
        return UnboundReminderProbeSession(CoroutineScope(SupervisorJob() + Dispatchers.IO),
            canReadCalendar = { application.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED })
            .also { session = it }
    }

    private val refusedSession by lazy {
        UnboundReminderProbeSession(CoroutineScope(SupervisorJob() + Dispatchers.IO), canReadCalendar = { true })
    }
}

enum class ProbeReminderIdentityIssue {
    ORIGIN, COUNTER_LIMIT, COUNTER_INCONSISTENT, PORT_ATTEMPT, SETTLEMENT_PENDING, HEALTH,
}

data class ProbeReminderStartup(
    val runtimeGetterCalls: Int,
    val configurationAttempts: Int,
    val operationAttempts: Int,
    val retryAttempts: Int,
    val registrationAttempts: Int,
    val cancellationAttempts: Int,
    val health: ReminderRuntimeStatus,
    val inFlight: Int,
    val callers: Map<ProbeReminderCaller, Int>,
    val retryRefusals: Int,
    val suppressionRefusals: Int,
    val unexpectedDeliveryAttempts: Int,
) {
    /** Identity admits only settled, attributed recovery hints, never a delivery-capable runtime. */
    fun identityIssue(): ProbeReminderIdentityIssue? {
        if (callers.keys != ProbeReminderCaller.entries.toSet() || callers[ProbeReminderCaller.NATIVE] != 2 ||
            listOf(ProbeReminderCaller.ALARM, ProbeReminderCaller.ACTION, ProbeReminderCaller.JOB, ProbeReminderCaller.UNKNOWN).any { callers[it] != 0 })
            return ProbeReminderIdentityIssue.ORIGIN
        val recovery = callers.getValue(ProbeReminderCaller.RECOVERY)
        val values = listOf(configurationAttempts, operationAttempts, retryAttempts, registrationAttempts, cancellationAttempts,
            inFlight, retryRefusals, suppressionRefusals, unexpectedDeliveryAttempts)
        if (recovery !in 0..8 || values.any { it !in 0..8 } || runtimeGetterCalls !in 2..10)
            return ProbeReminderIdentityIssue.COUNTER_LIMIT
        if (runtimeGetterCalls != 2 + recovery || retryAttempts != retryRefusals ||
            operationAttempts != suppressionRefusals + unexpectedDeliveryAttempts || configurationAttempts > recovery ||
            retryAttempts > configurationAttempts || suppressionRefusals > configurationAttempts)
            return ProbeReminderIdentityIssue.COUNTER_INCONSISTENT
        if (registrationAttempts != 0 || cancellationAttempts != 0 || unexpectedDeliveryAttempts != 0)
            return ProbeReminderIdentityIssue.PORT_ATTEMPT
        if (inFlight != 0 || configurationAttempts < recovery) return ProbeReminderIdentityIssue.SETTLEMENT_PENDING
        if (recovery == 0) {
            if (health != ReminderRuntimeStatus.UNKNOWN || values.any { it != 0 }) return ProbeReminderIdentityIssue.HEALTH
        } else if (health !in setOf(ReminderRuntimeStatus.UNAVAILABLE, ReminderRuntimeStatus.DEGRADED) ||
            (health == ReminderRuntimeStatus.DEGRADED && retryRefusals == 0)) return ProbeReminderIdentityIssue.HEALTH
        return null
    }
}

/** A genuine production runtime sees unavailable configuration and a truthful refused retry. */
internal class UnboundReminderProbeSession(
    private val scope: CoroutineScope,
    canReadCalendar: () -> Boolean,
) {
    private val runtimeGetterCalls = AtomicInteger()
    private val callers = ProbeReminderCaller.entries.associateWith { AtomicInteger() }
    private val configurationAttempts = AtomicInteger()
    private val operationAttempts = AtomicInteger()
    private val retryAttempts = AtomicInteger()
    private val retryRefusals = AtomicInteger()
    private val suppressionRefusals = AtomicInteger()
    private val unexpectedDeliveryAttempts = AtomicInteger()
    private val registrationAttempts = AtomicInteger()
    private val cancellationAttempts = AtomicInteger()
    fun recordCaller(caller: ProbeReminderCaller) { runtimeGetterCalls.incrementAndGet(); callers.getValue(caller).incrementAndGet() }
    private val operations = object : ReminderOperations {
        override fun suppressDelivery(): ReminderSuppressionResult {
            operationAttempts.incrementAndGet(); suppressionRefusals.incrementAndGet()
            return ReminderSuppressionResult(false, AlarmSchedulingResult.UNAVAILABLE)
        }
        override suspend fun reconcile(): ReminderCycleResult = unavailableCycle()
        override suspend fun onAlarm(version: ReminderVersion): ReminderCycleResult = unavailableCycle()
        override suspend fun snooze(identity: ReminderPostIdentity, minutes: Int): ReminderActionResult = unavailableAction()
        override suspend fun dismiss(identity: ReminderPostIdentity): ReminderActionResult = unavailableAction()
        private fun unavailableCycle(): ReminderCycleResult {
            operationAttempts.incrementAndGet(); unexpectedDeliveryAttempts.incrementAndGet()
            return ReminderCycleResult(ReminderCycleStatus.UNAVAILABLE, AlarmSchedulingResult.UNAVAILABLE)
        }
        private fun unavailableAction(): ReminderActionResult {
            operationAttempts.incrementAndGet(); unexpectedDeliveryAttempts.incrementAndGet()
            return ReminderActionResult.SOURCE_UNAVAILABLE
        }
    }
    private val jobs = object : ReminderJobController {
        override fun ensureRegistered(canObserveCalendar: Boolean): Boolean { registrationAttempts.incrementAndGet(); return false }
        override fun rearmContent(canObserveCalendar: Boolean): Boolean { registrationAttempts.incrementAndGet(); return false }
        override fun cancelAll() { cancellationAttempts.incrementAndGet() }
        override fun scheduleRetry(): Boolean { retryAttempts.incrementAndGet(); retryRefusals.incrementAndGet(); return false }
    }
    val runtime = ReminderRuntime(operations, jobs, scope,
        configuration = { configurationAttempts.incrementAndGet(); throw IllegalStateException("Probe configuration is unbound") },
        canObserveCalendar = canReadCalendar)

    fun snapshot() = ProbeReminderStartup(runtimeGetterCalls.get(), configurationAttempts.get(), operationAttempts.get(),
        retryAttempts.get(), registrationAttempts.get(), cancellationAttempts.get(), runtime.health.value.status,
        scope.coroutineContext[Job]?.children?.count { !it.isCompleted } ?: 0,
        callers.mapValues { it.value.get() }, retryRefusals.get(), suppressionRefusals.get(), unexpectedDeliveryAttempts.get())
}
