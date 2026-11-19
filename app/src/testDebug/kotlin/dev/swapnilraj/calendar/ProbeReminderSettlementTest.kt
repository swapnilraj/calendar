package dev.swapnilraj.calendar

import dev.swapnilraj.calendar.android.reminders.ReminderRuntimeStatus
import dev.swapnilraj.calendar.probe.*
import org.junit.Assert.*
import org.junit.Test

class ProbeReminderSettlementTest {
    private fun valid() = ProbeReminderStartup(3, 1, 1, 1, 0, 0, ReminderRuntimeStatus.DEGRADED, 0,
        ProbeReminderCaller.entries.associateWith { when (it) { ProbeReminderCaller.NATIVE -> 2; ProbeReminderCaller.RECOVERY -> 1; else -> 0 } }, 1, 1, 0)

    @Test fun mixed_retry_zero_refusal_one_without_inflight_must_settle_before_two_valid_samples() {
        val good = valid()
        val mixed = good.copy(retryAttempts = 0)
        assertEquals(ProbeReminderIdentityIssue.COUNTER_INCONSISTENT, mixed.identityIssue())
        val sampler = ProbeReminderSettlement(100)
        assertEquals(ProbeReminderSettlementStatus.WAIT, sampler.observe(mixed, 100).status)
        assertEquals(ProbeReminderSettlementStatus.WAIT, sampler.observe(good, 105).status)
        assertEquals(ProbeReminderSettlementStatus.ACCEPT, sampler.observe(good, 110).status)
    }
    @Test fun attributed_unknown_health_before_publication_can_settle_without_faking_health() {
        val good = valid(); val beforePublication = good.copy(health = ReminderRuntimeStatus.UNKNOWN, retryAttempts = 0, retryRefusals = 0)
        assertEquals(ProbeReminderIdentityIssue.HEALTH, beforePublication.identityIssue())
        val sampler = ProbeReminderSettlement(0)
        assertEquals(ProbeReminderSettlementStatus.WAIT, sampler.observe(beforePublication, 0).status)
        assertEquals(ReminderRuntimeStatus.UNKNOWN, beforePublication.health)
        assertEquals(ProbeReminderSettlementStatus.WAIT, sampler.observe(good, 5).status)
        assertEquals(ProbeReminderSettlementStatus.ACCEPT, sampler.observe(good, 10).status)
    }
    @Test fun permanent_counter_inconsistency_times_out_without_later_admission() {
        val mixed = valid().copy(retryAttempts = 0); val sampler = ProbeReminderSettlement(0)
        assertEquals(ProbeReminderSettlementStatus.WAIT, sampler.observe(mixed, 0).status)
        assertEquals(ProbeReminderSettlementStatus.WAIT, sampler.observe(mixed, 4999).status)
        assertEquals(ProbeReminderSettlementStatus.TIMEOUT, sampler.observe(mixed, 5000).status)
        assertEquals(ProbeReminderSettlementStatus.TIMEOUT, sampler.observe(valid(), 5001).status)
    }
    @Test fun permanent_attributed_unknown_health_also_has_the_same_five_second_bound() {
        val pending = valid().copy(health = ReminderRuntimeStatus.UNKNOWN, retryAttempts = 0, retryRefusals = 0)
        val sampler = ProbeReminderSettlement(20)
        assertEquals(ProbeReminderSettlementStatus.WAIT, sampler.observe(pending, 20).status)
        assertEquals(ProbeReminderSettlementStatus.TIMEOUT, sampler.observe(pending, 5020).status)
    }
    @Test fun ready_disabled_permission_required_are_categorical_even_with_inflight_work() {
        listOf(ReminderRuntimeStatus.READY, ReminderRuntimeStatus.DISABLED, ReminderRuntimeStatus.PERMISSION_REQUIRED).forEach {
            val sampler = ProbeReminderSettlement(0)
            assertEquals(ProbeReminderSettlementStatus.REFUSE, sampler.observe(valid().copy(health = it, inFlight = 1), 0).status)
            assertEquals(ProbeReminderSettlementStatus.REFUSE, sampler.observe(valid(), 1).status)
        }
    }
    @Test fun unknown_origin_and_unexpected_ports_cannot_hide_in_a_mixed_snapshot() {
        val base = valid().copy(retryAttempts = 0)
        val bad = listOf(base.copy(callers = base.callers + (ProbeReminderCaller.UNKNOWN to 1)),
            base.copy(callers = base.callers + (ProbeReminderCaller.ALARM to 1)),
            base.copy(callers = base.callers + (ProbeReminderCaller.ACTION to 1)),
            base.copy(callers = base.callers + (ProbeReminderCaller.JOB to 1)),
            base.copy(registrationAttempts = 1), base.copy(cancellationAttempts = 1), base.copy(unexpectedDeliveryAttempts = 1))
        bad.forEach { assertEquals(ProbeReminderSettlementStatus.REFUSE, ProbeReminderSettlement(0).observe(it, 0).status) }
    }
    @Test fun transient_mixed_sample_resets_the_two_equal_valid_observation_requirement() {
        val sampler = ProbeReminderSettlement(0); val good = valid()
        assertEquals(ProbeReminderSettlementStatus.WAIT, sampler.observe(good, 0).status)
        assertEquals(ProbeReminderSettlementStatus.WAIT, sampler.observe(good.copy(retryAttempts = 0), 5).status)
        assertEquals(ProbeReminderSettlementStatus.WAIT, sampler.observe(good, 10).status)
        assertEquals(ProbeReminderSettlementStatus.ACCEPT, sampler.observe(good, 15).status)
    }
}
