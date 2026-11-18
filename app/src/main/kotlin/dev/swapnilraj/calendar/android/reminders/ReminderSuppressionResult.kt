package dev.swapnilraj.calendar.android.reminders

import dev.swapnilraj.calendar.core.AlarmSchedulingResult

/** Cancellation acceptance is not a physical-device observation or a ledger-history proof. */
data class ReminderSuppressionResult(val notificationCancellationAccepted: Boolean, val alarm: AlarmSchedulingResult) {
    val accepted: Boolean get() = notificationCancellationAccepted && alarm == AlarmSchedulingResult.CANCELLED
}

