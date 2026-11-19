package dev.swapnilraj.calendar.probe

import dev.swapnilraj.calendar.android.reminders.ReminderRuntimeStatus

enum class ProbeReminderSettlementStatus { WAIT, ACCEPT, REFUSE, TIMEOUT }
data class ProbeReminderSettlementResult(val status: ProbeReminderSettlementStatus, val issue: ProbeReminderIdentityIssue? = null)

/** Metadata observation only. Timestamps come from the native monotonic clock; no runtime clock,
 * counter or health value is modified. Invalid observations never count as an accepted sample. */
class ProbeReminderSettlement(private val beganMillis: Long) {
    private var previousValid: ProbeReminderStartup? = null
    private var terminalFailure: ProbeReminderSettlementResult? = null

    fun observe(state: ProbeReminderStartup, nowMillis: Long): ProbeReminderSettlementResult {
        terminalFailure?.let { return it }
        if (nowMillis < beganMillis || nowMillis - beganMillis >= BUDGET_MILLIS)
            return fail(ProbeReminderSettlementStatus.TIMEOUT)
        // These facts are categorical even when other reads overlap an in-flight callback.
        if (state.health in setOf(ReminderRuntimeStatus.READY, ReminderRuntimeStatus.DISABLED, ReminderRuntimeStatus.PERMISSION_REQUIRED))
            return fail(ProbeReminderSettlementStatus.REFUSE, ProbeReminderIdentityIssue.HEALTH)
        if (state.registrationAttempts != 0 || state.cancellationAttempts != 0 || state.unexpectedDeliveryAttempts != 0)
            return fail(ProbeReminderSettlementStatus.REFUSE, ProbeReminderIdentityIssue.PORT_ATTEMPT)
        val issue = state.identityIssue()
        if (issue != null) {
            previousValid = null
            val maySettle = issue in setOf(ProbeReminderIdentityIssue.SETTLEMENT_PENDING, ProbeReminderIdentityIssue.COUNTER_INCONSISTENT) ||
                (issue == ProbeReminderIdentityIssue.HEALTH && state.health == ReminderRuntimeStatus.UNKNOWN &&
                    (state.callers[ProbeReminderCaller.RECOVERY] ?: 0) > 0)
            return if (maySettle) ProbeReminderSettlementResult(ProbeReminderSettlementStatus.WAIT)
                else fail(ProbeReminderSettlementStatus.REFUSE, issue)
        }
        if (previousValid == state) return ProbeReminderSettlementResult(ProbeReminderSettlementStatus.ACCEPT)
        previousValid = state
        return ProbeReminderSettlementResult(ProbeReminderSettlementStatus.WAIT)
    }

    private fun fail(status: ProbeReminderSettlementStatus, issue: ProbeReminderIdentityIssue? = null): ProbeReminderSettlementResult =
        ProbeReminderSettlementResult(status, issue).also { terminalFailure = it }

    companion object { const val BUDGET_MILLIS = 5_000L }
}
