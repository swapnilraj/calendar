package dev.swapnilraj.calendar.android.reminders

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import dev.swapnilraj.calendar.R
import dev.swapnilraj.calendar.core.EventDetails
import dev.swapnilraj.calendar.core.EventTime
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Post/cancel with BOTH tag and id. The persistent delivery ledger belongs to the scheduler. */
data class ReminderNotification(val tag: String, val id: Int, val notification: Notification)

class ReminderNotificationFactory(private val context: Context, private val targets: ReminderIntentTargets) {
    fun createChannel() {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.reminders_channel_name), NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description = context.getString(R.string.reminders_channel_description)
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                    setBypassDnd(false)
                },
        )
    }

    fun build(details: EventDetails, due: Instant, displayZone: ZoneId,
        use24Hour: Boolean = DateFormat.is24HourFormat(context)): ReminderNotification {
        createChannel()
        val identity = ReminderIdentity(details.occurrence.ref, due)
        val open = ReminderIntents.intent(context, targets, identity, ReminderIntents.ACTION_OPEN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val content = PendingIntent.getActivity(context, 0, open, flags)
        val dismiss = PendingIntent.getBroadcast(context, 0,
            ReminderIntents.intent(context, targets, identity, ReminderIntents.ACTION_DISMISS), flags)
        val title = details.occurrence.title.ifBlank { context.getString(R.string.reminders_no_title) }
        val locale = context.resources.configuration.locales[0]
        val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
        val summary = when (val time = details.occurrence.time) {
            is EventTime.Timed -> {
                val local = time.start.atZone(displayZone)
                context.getString(R.string.reminders_timed_date_time, dateFormatter.format(local),
                    DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "h:mm a", locale).format(local))
            }
            is EventTime.AllDay -> context.getString(R.string.reminders_all_day_date,
                time.dates.start.format(dateFormatter))
        }
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setCategory(Notification.CATEGORY_EVENT)
            .setContentTitle(title).setContentText(summary)
            .setContentIntent(content).setDeleteIntent(dismiss)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(context.getString(R.string.reminders_private_title)).build())
            .setAutoCancel(true).setOnlyAlertOnce(true).setShowWhen(false)
        for (minutes in listOf(5, 10, 30)) {
            val pending = PendingIntent.getBroadcast(context, 0,
                ReminderIntents.intent(context, targets, identity, ReminderIntents.ACTION_SNOOZE, minutes), flags)
            builder.addAction(Notification.Action.Builder(null,
                context.getString(R.string.reminders_snooze_minutes, minutes), pending).build())
        }
        return ReminderNotification(identity.tag, 0, builder.build())
    }

    companion object { const val CHANNEL_ID = "event_reminders" }
}
