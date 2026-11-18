package dev.swapnilraj.calendar.android.reminders

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract

/** Explicit private alarm destination; the stale payload is only a reconciliation hint. */
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val request = ReminderAlarmIntents.request(intent, ComponentName(context, javaClass)) ?: return
        dispatch(context) { reconcile(request.version) }
    }
}

/** Must be non-exported. Require complete nonce/token and a canonical immutable-intent URI. */
class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val command = ReminderActionDecoder.decode(context, intent, ComponentName(context, javaClass)) ?: return
        dispatch(context) { action(command.identity, command.snoozeMinutes) }
    }
}

data class ReminderPrivateAction(val identity: dev.swapnilraj.calendar.core.ReminderPostIdentity, val snoozeMinutes: Int?)

object ReminderActionDecoder {
    @Suppress("DEPRECATION")
    fun decode(context: Context, intent: Intent, expectedReceiver: ComponentName): ReminderPrivateAction? = try {
        require(expectedReceiver.packageName == context.packageName && intent.component == expectedReceiver)
        require(intent.action in setOf(ReminderIntents.ACTION_SNOOZE, ReminderIntents.ACTION_DISMISS))
        val identity = requireNotNull(ReminderIntents.identity(intent))
        val post = requireNotNull(identity.postIdentityOrNull())
        val minutes = if (intent.action == ReminderIntents.ACTION_SNOOZE) {
            val value = intent.extras?.get(ReminderIntents.EXTRA_SNOOZE_MINUTES)
            require(value is Int && value in listOf(5, 10, 30)); value
        } else { require(!intent.hasExtra(ReminderIntents.EXTRA_SNOOZE_MINUTES)); null }
        val canonical = ReminderIntents.intent(context, ReminderIntentTargets(expectedReceiver, expectedReceiver),
            identity, requireNotNull(intent.action), minutes)
        require(intent.data == canonical.data)
        ReminderPrivateAction(post, minutes)
    } catch (_: RuntimeException) { null }
}

/** System signals are hints only. Root declares the exact action filter/boot permission. */
class ReminderRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS ||
            (intent.component != null && intent.component != ComponentName(context, javaClass))) return
        dispatch(context) { reconcile() }
    }
    companion object {
        val ACTIONS: Set<String> = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)
    }
}

/** Separate provider-facing hint, never an event command. Root must audit exported/sender policy.
 * The Calendar provider sends this across UIDs; it cannot target the private action receiver.
 */
class ReminderProviderHintReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != CalendarContract.ACTION_EVENT_REMINDER ||
            (intent.component != null && intent.component != ComponentName(context, javaClass))) return
        // Provider event IDs/times/URIs are intentionally not decoded or acted upon.
        val runtime = (context.applicationContext as? ReminderRuntimeOwner)?.reminderRuntime ?: return
        runtime.requestRetry()
    }
}

private fun BroadcastReceiver.dispatch(context: Context, work: suspend ReminderRuntime.() -> Unit) {
    val runtime = (context.applicationContext as? ReminderRuntimeOwner)?.reminderRuntime ?: return
    val pending = goAsync()
    runtime.broadcast(pending::finish, work)
}
