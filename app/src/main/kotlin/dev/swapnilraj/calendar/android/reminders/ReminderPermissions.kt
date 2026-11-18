package dev.swapnilraj.calendar.android.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

enum class ReminderIssue {
    CALENDAR_ACCESS, NOTIFICATION_PERMISSION, NOTIFICATIONS_DISABLED, CHANNEL_DISABLED, EXACT_ALARM_ACCESS,
}

data class ReminderPermissionState(
    val calendarAccess: Boolean,
    val notificationPermission: Boolean,
    val notificationsEnabled: Boolean,
    val channelEnabled: Boolean,
    val exactAlarmAccess: Boolean,
) {
    val canPostNotifications: Boolean get() = notificationPermission && notificationsEnabled && channelEnabled
    val issues: Set<ReminderIssue> get() = buildSet {
        if (!calendarAccess) add(ReminderIssue.CALENDAR_ACCESS)
        if (!notificationPermission) add(ReminderIssue.NOTIFICATION_PERMISSION)
        if (!notificationsEnabled) add(ReminderIssue.NOTIFICATIONS_DISABLED)
        if (!channelEnabled) add(ReminderIssue.CHANNEL_DISABLED)
        if (!exactAlarmAccess) add(ReminderIssue.EXACT_ALARM_ACCESS)
    }
    val ready: Boolean get() = issues.isEmpty()
}

/** Read-only checks; call again on resume because system settings can change outside the app. */
class ReminderPermissionReader(private val context: Context) {
    fun read(): ReminderPermissionState {
        val notifications = context.getSystemService(NotificationManager::class.java)
        return ReminderPermissionState(
            calendarAccess = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED,
            notificationPermission = Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            notificationsEnabled = notifications.areNotificationsEnabled(),
            // An uncreated channel is available: creation is performed by the notification factory.
            channelEnabled = notifications.getNotificationChannel(ReminderNotificationFactory.CHANNEL_ID)
                ?.importance != NotificationManager.IMPORTANCE_NONE,
            exactAlarmAccess = Build.VERSION.SDK_INT < 31 ||
                context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(),
        )
    }
}
