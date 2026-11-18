package dev.swapnilraj.calendar.android.reminders

import android.app.NotificationManager
import android.content.Context
import dev.swapnilraj.calendar.core.EventDetails
import dev.swapnilraj.calendar.core.ReminderCandidate
import dev.swapnilraj.calendar.core.ReminderNotificationAlias
import dev.swapnilraj.calendar.core.ReminderNotificationPort
import dev.swapnilraj.calendar.core.ReminderPostResult
import java.time.ZoneId

/** Presentation only; persistent claims/recovery belong to the coordinator and ledger. */
class AndroidReminderNotifications(
    private val context: Context,
    targets: ReminderIntentTargets,
    private val displayZone: () -> ZoneId = { ZoneId.systemDefault() },
    private val permissions: () -> ReminderPermissionState = { ReminderPermissionReader(context).read() },
) : ReminderNotificationPort {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val factory = ReminderNotificationFactory(context, targets)

    override fun post(candidate: ReminderCandidate, alias: ReminderNotificationAlias): ReminderPostResult {
        val permission = permissions()
        if (!permission.calendarAccess || !permission.canPostNotifications) return ReminderPostResult.PERMISSION_REQUIRED
        return try {
            val notification = factory.build(EventDetails(candidate.occurrence, null), alias.anchorDue,
                displayZone(), notificationIdentity = alias.identity())
            val current = permissions()
            if (!current.calendarAccess || !current.canPostNotifications) return ReminderPostResult.PERMISSION_REQUIRED
            manager.notify(notification.tag, notification.id, notification.notification)
            // Accepted by NotificationManager, not a claim that sound/device delivery was observed.
            ReminderPostResult.POSTED
        } catch (_: RuntimeException) {
            // Binder can fail after accepting work. A thrown call is not safe evidence for replay.
            ReminderPostResult.UNCERTAIN
        }
    }

    override fun isActive(alias: ReminderNotificationAlias): Boolean = manager.activeNotifications.any {
        it.tag == alias.identity().tag && it.id == 0
    }

    override fun cancel(alias: ReminderNotificationAlias) { manager.cancel(alias.identity().tag, 0) }

    override fun cancelAllOwn() {
        manager.activeNotifications.filter { it.tag?.startsWith("daylight://reminder/v1/") == true }
            .forEach { manager.cancel(it.tag, it.id) }
    }
}

internal fun ReminderNotificationAlias.identity(): ReminderIdentity = ReminderIdentity(ref, anchorDue, nonce)
