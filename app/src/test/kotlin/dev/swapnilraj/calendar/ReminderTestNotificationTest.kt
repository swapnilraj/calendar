package dev.swapnilraj.calendar

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.swapnilraj.calendar.android.reminders.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNotificationManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [FailingTestNotificationManager::class])
class ReminderTestNotificationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    @Before fun setup() {
        FailingTestNotificationManager.failAfterAccept = false
        FailingTestNotificationManager.revokeAtChannelCreation = false
        ShadowNotificationManager.reset()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.READ_CALENDAR)
    }
    @Test fun explicitGenericTestNeedsNoCalendarPermissionAndUsesPrivateSharedChannel() {
        assertEquals(ReminderTestNotificationResult.ACCEPTED, ReminderTestNotification(context).post())
        val status = manager.activeNotifications.single()
        assertEquals(ReminderTestNotification.TAG, status.tag); assertEquals(ReminderTestNotification.ID, status.id)
        assertFalse(status.tag.startsWith("daylight://reminder/v1/"))
        val notification = status.notification
        assertEquals("Daylight test reminder", notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertEquals(ReminderNotificationFactory.CHANNEL_ID, notification.channelId)
        assertEquals("Daylight reminder", notification.publicVersion.extras.getString(Notification.EXTRA_TITLE))
        assertNull(notification.deleteIntent); assertTrue(notification.actions.isNullOrEmpty())
        assertFalse(notification.extras.containsKey(ReminderNotificationFactory.EXTRA_POST_TOKEN))
        assertEquals(Notification.VISIBILITY_PRIVATE, manager.getNotificationChannel(ReminderNotificationFactory.CHANNEL_ID)!!.lockscreenVisibility)
    }
    @Test fun contentIntentIsImmutableCanonicalMainWithoutAnyMeetingIdentity() {
        ReminderTestNotification(context).post()
        val pending = manager.activeNotifications.single().notification.contentIntent
        assertTrue(shadowOf(pending).flags and PendingIntent.FLAG_IMMUTABLE != 0)
        val intent = shadowOf(pending).savedIntent
        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertTrue(intent.categories?.contains(Intent.CATEGORY_LAUNCHER) == true)
        assertEquals(MainActivity::class.java.name, intent.component!!.className)
        assertNull(intent.data); assertTrue(intent.extras == null || intent.extras!!.isEmpty)
    }
    @Test fun missingPostGlobalDisableAndBlockedChannelReturnPermissionRequiredWithoutPosting() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(ReminderTestNotificationResult.PERMISSION_REQUIRED, ReminderTestNotification(context).post())
        assertTrue(manager.activeNotifications.isEmpty())
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(manager).setNotificationsEnabled(false)
        assertEquals(ReminderTestNotificationResult.PERMISSION_REQUIRED, ReminderTestNotification(context).post())
        shadowOf(manager).setNotificationsEnabled(true)
        manager.createNotificationChannel(NotificationChannel(ReminderNotificationFactory.CHANNEL_ID, "User blocked", NotificationManager.IMPORTANCE_NONE))
        assertEquals(ReminderTestNotificationResult.PERMISSION_REQUIRED, ReminderTestNotification(context).post())
        assertTrue(manager.activeNotifications.isEmpty())
    }
    @Test @Config(sdk = [32]) fun preNotificationPermissionApiStillAllowsGenericFixture() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(ReminderTestNotificationResult.ACCEPTED, ReminderTestNotification(context).post())
    }
    @Test fun freshPermissionCheckAfterChannelCreationDetectsRevocation() {
        FailingTestNotificationManager.revokeAtChannelCreation = true
        assertEquals(ReminderTestNotificationResult.PERMISSION_REQUIRED, ReminderTestNotification(context).post())
        assertTrue(manager.activeNotifications.isEmpty())
    }
    @Test fun acceptedBySystemCanBeRepostedWithoutOnlyAlertOnceOrTouchingRealSourceAlerts() {
        manager.notify("daylight://reminder/v1/existing-source-alert", 0,
            Notification.Builder(context, ReminderNotificationFactory.CHANNEL_ID).setSmallIcon(android.R.drawable.ic_popup_reminder).build())
        val test = ReminderTestNotification(context)
        assertEquals(ReminderTestNotificationResult.ACCEPTED, test.post())
        assertEquals(ReminderTestNotificationResult.ACCEPTED, test.post())
        assertEquals(2, manager.activeNotifications.size)
        val notice = manager.activeNotifications.single { it.tag == ReminderTestNotification.TAG }.notification
        assertEquals(0, notice.flags and Notification.FLAG_ONLY_ALERT_ONCE)
    }
    @Test fun uncertainBinderFailureAfterActualAcceptanceReturnsUnavailableWithoutPretendingDelivery() {
        FailingTestNotificationManager.failAfterAccept = true
        assertEquals(ReminderTestNotificationResult.UNAVAILABLE, ReminderTestNotification(context).post())
        assertEquals(1, manager.activeNotifications.size)
    }
}

@Implements(NotificationManager::class)
class FailingTestNotificationManager : ShadowNotificationManager() {
    @Implementation override fun notify(tag: String?, id: Int, notification: Notification) {
        super.notify(tag, id, notification)
        if (failAfterAccept) throw IllegalStateException("Synthetic uncertain binder failure")
    }
    @Implementation override fun createNotificationChannel(channel: NotificationChannel) {
        super.createNotificationChannel(channel)
        if (revokeAtChannelCreation) shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }
    companion object {
        var failAfterAccept = false
        var revokeAtChannelCreation = false
    }
}
