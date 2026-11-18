package dev.swapnilraj.calendar.android.reminders

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import dev.swapnilraj.calendar.MainActivity
import dev.swapnilraj.calendar.R

/** ACCEPTED means NotificationManager returned normally, not that sound/device delivery was observed. */
enum class ReminderTestNotificationResult { ACCEPTED, PERMISSION_REQUIRED, UNAVAILABLE }

/** Call only from the user's test action. No calendar permission, event ref, ledger or scheduler. */
class ReminderTestNotification(private val context: Context) {
    fun post(): ReminderTestNotificationResult = try {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!canPost(manager)) ReminderTestNotificationResult.PERMISSION_REQUIRED else {
            val activity = ComponentName(context, MainActivity::class.java)
            ReminderNotificationFactory(context, ReminderIntentTargets(activity,
                ComponentName(context, ReminderActionReceiver::class.java))).createChannel()
            val open = PendingIntent.getActivity(context, REQUEST_CODE, Intent.makeMainActivity(activity),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val public = Notification.Builder(context, ReminderNotificationFactory.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(context.getString(R.string.reminder_test_public_title)).build()
            val notification = Notification.Builder(context, ReminderNotificationFactory.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(context.getString(R.string.reminder_test_title))
                .setContentText(context.getString(R.string.reminder_test_body))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setContentIntent(open).setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(public)
                .setAutoCancel(true).setOnlyAlertOnce(false).setShowWhen(false).build()
            if (!canPost(manager)) ReminderTestNotificationResult.PERMISSION_REQUIRED
            else if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) ReminderTestNotificationResult.PERMISSION_REQUIRED
            else {
                manager.notify(TAG, ID, notification)
                ReminderTestNotificationResult.ACCEPTED
            }
        }
    } catch (_: RuntimeException) {
        // Binder failure may occur after acceptance. Do not claim success or replay automatically.
        ReminderTestNotificationResult.UNAVAILABLE
    }

    private fun canPost(manager: NotificationManager): Boolean =
        (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            manager.areNotificationsEnabled() && manager.getNotificationChannel(ReminderNotificationFactory.CHANNEL_ID)
                ?.importance != NotificationManager.IMPORTANCE_NONE

    companion object {
        const val TAG = "daylight://reminder-test/v1"
        const val ID = 0x445454
        private const val REQUEST_CODE = 0x445454
    }
}
