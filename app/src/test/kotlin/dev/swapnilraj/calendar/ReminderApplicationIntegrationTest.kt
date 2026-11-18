package dev.swapnilraj.calendar

import android.Manifest
import android.app.job.JobScheduler
import android.content.ComponentName
import dev.swapnilraj.calendar.android.reminders.*
import dev.swapnilraj.calendar.data.reminders.ReminderPreferenceSave
import dev.swapnilraj.calendar.data.reminders.SavedReminderSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** Actual application composition and durable settings; no events, accounts or device delivery. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReminderApplicationIntegrationTest {
    @Test fun persistedConfigurationDrivesRealJobRegistrationAndPermissionRecovery() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as CalendarApp
        val initial = app.settings.readReminderSettings()
        val scheduler = app.getSystemService(JobScheduler::class.java)
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.POST_NOTIFICATIONS)
        try {
            assertTrue(app.settings.saveReminderSettings(initial, SavedReminderSettings(true)) is ReminderPreferenceSave.Saved)
            app.reminderRuntime.onResume().join()
            assertEquals(ReminderRuntimeStatus.READY, app.reminderRuntime.health.value.status)
            assertNotNull(scheduler.getPendingJob(AndroidReminderJobs.PERIODIC_ID))
            assertNotNull(scheduler.getPendingJob(AndroidReminderJobs.CONTENT_ID))
            Shadows.shadowOf(app).denyPermissions(Manifest.permission.READ_CALENDAR)
            app.reminderRuntime.onResume().join()
            assertEquals(ReminderRuntimeStatus.PERMISSION_REQUIRED, app.reminderRuntime.health.value.status)
            assertNull(scheduler.getPendingJob(AndroidReminderJobs.CONTENT_ID))
            assertNotNull(scheduler.getPendingJob(AndroidReminderJobs.PERIODIC_ID))
            assertTrue(app.settings.saveReminderSettings(app.settings.readReminderSettings(), SavedReminderSettings()) is ReminderPreferenceSave.Saved)
            app.reminderRuntime.onResume().join()
            assertEquals(ReminderRuntimeStatus.DISABLED, app.reminderRuntime.health.value.status)
            assertNull(scheduler.getPendingJob(AndroidReminderJobs.PERIODIC_ID))
            assertNull(scheduler.getPendingJob(AndroidReminderJobs.CONTENT_ID))
            val service = app.packageManager.getServiceInfo(ComponentName(app, ReminderJobService::class.java), 0)
            assertEquals("android.permission.BIND_JOB_SERVICE", service.permission)
            for (receiver in listOf(ReminderActionReceiver::class.java, ReminderAlarmReceiver::class.java, ReminderRecoveryReceiver::class.java))
                assertFalse(app.packageManager.getReceiverInfo(ComponentName(app, receiver), 0).exported)
        } finally {
            app.settings.saveReminderSettings(app.settings.readReminderSettings(), initial)
            app.reminderRuntime.onResume().join()
        }
    }
}
