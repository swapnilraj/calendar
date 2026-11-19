package dev.swapnilraj.calendar

import dev.swapnilraj.calendar.android.reminders.ReminderRuntimeStatus
import dev.swapnilraj.calendar.probe.*
import org.junit.Assert.*
import org.junit.Test

class ProbeReminderIdentityPolicyTest {
    private fun state(recovery: Int = 0) = ProbeReminderStartup(
        runtimeGetterCalls = 2 + recovery, configurationAttempts = recovery, operationAttempts = recovery,
        retryAttempts = recovery, registrationAttempts = 0, cancellationAttempts = 0,
        health = if (recovery == 0) ReminderRuntimeStatus.UNKNOWN else ReminderRuntimeStatus.DEGRADED,
        inFlight = 0, callers = ProbeReminderCaller.entries.associateWith { when (it) {
            ProbeReminderCaller.NATIVE -> 2; ProbeReminderCaller.RECOVERY -> recovery; else -> 0 } },
        retryRefusals = recovery, suppressionRefusals = recovery, unexpectedDeliveryAttempts = 0)

    @Test fun exact_known_classes_are_sanitized_and_arbitrary_names_are_unknown() {
        val classes = mapOf("dev.swapnilraj.calendar.probe.NativeReminderProbe" to ProbeReminderCaller.NATIVE,
            "dev.swapnilraj.calendar.android.reminders.ReminderRecoveryReceiver" to ProbeReminderCaller.RECOVERY,
            "dev.swapnilraj.calendar.android.reminders.ReminderAlarmReceiver" to ProbeReminderCaller.ALARM,
            "dev.swapnilraj.calendar.android.reminders.ReminderActionReceiver" to ProbeReminderCaller.ACTION,
            "dev.swapnilraj.calendar.android.reminders.ReminderJobService" to ProbeReminderCaller.JOB)
        classes.forEach { (name, kind) -> assertEquals(kind, ProbeReminderCallers.classify(listOf("unrelated.Frame", name))) }
        assertEquals(ProbeReminderCaller.UNKNOWN, ProbeReminderCallers.classify(classes.keys.map { it + "Spoof" }))
        assertEquals(ProbeReminderCaller.UNKNOWN, ProbeReminderCallers.classify(emptyList()))
    }
    @Test fun innermost_known_boundary_wins_over_outer_native_frame() {
        assertEquals(ProbeReminderCaller.RECOVERY, ProbeReminderCallers.classify(listOf(
            "dev.swapnilraj.calendar.android.reminders.ReminderRecoveryReceiver", "dev.swapnilraj.calendar.probe.NativeReminderProbe")))
    }
    @Test fun genuinely_idle_and_settled_recovery_states_have_no_data_or_delivery_authority() {
        assertNull(state().identityIssue())
        assertNull(state(1).identityIssue())
        assertNull(state(8).identityIssue())
        assertNull(state(1).copy(health = ReminderRuntimeStatus.UNAVAILABLE, retryAttempts = 0, retryRefusals = 0).identityIssue())
    }
    @Test fun unbound_callback_work_must_really_finish_before_observation() {
        assertEquals(ProbeReminderIdentityIssue.SETTLEMENT_PENDING, state(1).copy(inFlight = 1).identityIssue())
        assertEquals(ProbeReminderIdentityIssue.SETTLEMENT_PENDING, state(1).copy(configurationAttempts = 0,
            retryAttempts = 0, retryRefusals = 0, operationAttempts = 0, suppressionRefusals = 0).identityIssue())
    }
    @Test fun alarm_action_job_and_unknown_entries_are_not_admitted_in_identity() {
        listOf(ProbeReminderCaller.ALARM, ProbeReminderCaller.ACTION, ProbeReminderCaller.JOB, ProbeReminderCaller.UNKNOWN).forEach {
            assertEquals(ProbeReminderIdentityIssue.ORIGIN, state().copy(callers = state().callers + (it to 1)).identityIssue())
        }
    }
    @Test fun recovery_never_turns_unavailable_setup_into_ready_disabled_or_fake_unknown() {
        listOf(ReminderRuntimeStatus.READY, ReminderRuntimeStatus.DISABLED, ReminderRuntimeStatus.PERMISSION_REQUIRED, ReminderRuntimeStatus.UNKNOWN).forEach {
            assertEquals(ProbeReminderIdentityIssue.HEALTH, state(1).copy(health = it).identityIssue())
        }
        assertEquals(ProbeReminderIdentityIssue.HEALTH, state(1).copy(retryAttempts = 0, retryRefusals = 0).identityIssue())
    }
    @Test fun any_delivery_or_job_registration_attempt_remains_refused() {
        assertNotNull(state(1).copy(registrationAttempts = 1).identityIssue())
        assertNotNull(state(1).copy(cancellationAttempts = 1).identityIssue())
        assertNotNull(state(1).copy(unexpectedDeliveryAttempts = 1).identityIssue())
    }
    @Test fun counter_bounds_and_refusal_consistency_are_checked() {
        assertNotNull(state(9).identityIssue())
        assertNotNull(state(1).copy(retryRefusals = 0).identityIssue())
        assertNotNull(state(1).copy(runtimeGetterCalls = 2).identityIssue())
        assertNotNull(state(1).copy(suppressionRefusals = 0).identityIssue())
    }
}
