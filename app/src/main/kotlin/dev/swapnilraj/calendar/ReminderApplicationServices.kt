package dev.swapnilraj.calendar

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import dev.swapnilraj.calendar.android.reminders.*
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope

/** Application lifetime only. Every supplier reads reminder settings independently of display state. */
internal fun CalendarApp.createReminderRuntime(scope: CoroutineScope): ReminderRuntime {
    val configuration = suspend { settings.readReminderSettings().toConfiguration(ZoneId.systemDefault()) }
    val permissions = { ReminderPermissionReader(this).read() }
    val coordinator = ReminderCoordinator(reminderSource, reminderLedger,
        AndroidReminderAlarms(this, ComponentName(this, ReminderAlarmReceiver::class.java)),
        AndroidReminderNotifications(this, ReminderIntentTargets(ComponentName(this, ReminderOpenActivity::class.java),
            ComponentName(this, ReminderActionReceiver::class.java))), configuration, permissions)
    return ReminderRuntime(CoordinatorReminderOperations(coordinator),
        AndroidReminderJobs(this, ComponentName(this, ReminderJobService::class.java)), scope, configuration,
        { checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED },
        deliveryPermissions = permissions)
}
