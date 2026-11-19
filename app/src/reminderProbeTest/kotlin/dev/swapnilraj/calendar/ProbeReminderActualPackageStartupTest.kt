package dev.swapnilraj.calendar

import android.app.job.JobScheduler
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import dev.swapnilraj.calendar.android.reminders.AndroidReminderJobs
import dev.swapnilraj.calendar.android.reminders.ReminderRecoveryReceiver
import dev.swapnilraj.calendar.probe.ProbeReminderApplication
import dev.swapnilraj.calendar.probe.ProbeReminderBindings
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBroadcastPendingResult
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** Run with -Pdaylight.reminderProbe=true: actual merged probe package/Application and production
 * receiver/job adapter under Robolectric. This is not native broadcast or alarm-delivery evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProbeReminderActualPackageStartupTest {
    @Test fun prebindRecoveryCallbackDoesNotScheduleFrameworkJobs() {
        val app = RuntimeEnvironment.getApplication() as ProbeReminderApplication
        assertEquals("dev.swapnilraj.calendar.reminderprobe", app.packageName)
        val destination = ComponentName(app, ReminderRecoveryReceiver::class.java)
        @Suppress("DEPRECATION")
        val manifestReceiver = app.packageManager.getReceiverInfo(destination, 0)
        assertTrue(manifestReceiver.enabled)
        assertEquals(app.packageName, manifestReceiver.packageName)
        val scheduler = app.getSystemService(JobScheduler::class.java)
        assertEquals(0, scheduler.allPendingJobs.size)
        val receiver = ReminderRecoveryReceiver()
        val pending = ReflectionHelpers.callStaticMethod<BroadcastReceiver.PendingResult>(
            ShadowBroadcastPendingResult::class.java, "create",
            ClassParameter.from(Int::class.javaPrimitiveType!!, 0),
            ClassParameter.from(String::class.java, null),
            ClassParameter.from(Bundle::class.java, null),
            ClassParameter.from(Boolean::class.javaPrimitiveType!!, false))
        ReflectionHelpers.setField(receiver, "mPendingResult", pending)
        receiver.onReceive(app, Intent(Intent.ACTION_TIMEZONE_CHANGED).setComponent(destination))
        shadowOf(pending).future.get(5, TimeUnit.SECONDS)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (ProbeReminderBindings.snapshot(app).inFlight != 0 && System.nanoTime() < deadline) Thread.yield()
        val settled = ProbeReminderBindings.snapshot(app)
        assertEquals(0, settled.inFlight)
        assertEquals(1, settled.configurationAttempts)
        assertEquals(1, settled.retryAttempts)
        val jobs = scheduler.allPendingJobs
        if (jobs.isNotEmpty()) assertEquals(listOf(AndroidReminderJobs.RETRY_ID), jobs.map { it.id })
        assertEquals(0, jobs.size)
    }
}
