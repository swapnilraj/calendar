package dev.swapnilraj.calendar

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.swapnilraj.calendar.android.reminders.*
import dev.swapnilraj.calendar.core.*
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidReminderDeliveryTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val receiver get() = ComponentName(context.packageName, "PrivateReminderAlarmReceiver")
    private val identity = ReminderCalendarIdentity("calendar", "uid")
    private val now = Instant.parse("2026-11-18T09:00:00Z")
    private val ref = EventRef(CalendarId(2), 7, now, now.plusSeconds(3600))
    private val key = ReminderKey.of(ref, 10)
    private val version = ReminderVersion(key, identity, 1, 3)
    private val ready = ReminderPermissionState(true, true, true, true, true)
    private fun candidate() = ReminderCandidate(key, identity,
        EventOccurrence(ref, EventTime.Timed(ref.instanceStart, ref.instanceEnd), "Synthetic title", 0xff112233.toInt()), now.minusSeconds(600))

    @Test fun exactAlarmReplacesOneImmutablePendingIntentAndRoundTripsVersion() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val alarms = AndroidReminderAlarms(context, receiver)
        assertEquals(AlarmSchedulingResult.EXACT, alarms.replaceNext(ReminderAlarmRequest(now.plusNanos(1), version)))
        val shadow = shadowOf(context.getSystemService(AlarmManager::class.java))
        assertEquals(now.toEpochMilli() + 1, shadow.scheduledAlarms.single().triggerAtTime)
        assertTrue(shadow.scheduledAlarms.single().allowWhileIdle)
        val later = ReminderAlarmRequest(now.plusSeconds(600), version.copy(revision = 4))
        alarms.replaceNext(later)
        val pending = shadow.scheduledAlarms.single().operation
        assertTrue(shadowOf(pending).flags and PendingIntent.FLAG_IMMUTABLE != 0)
        assertEquals(later, ReminderAlarmIntents.request(shadowOf(pending).savedIntent, receiver))
        assertEquals(AlarmSchedulingResult.CANCELLED, alarms.replaceNext(null))
        assertTrue(shadow.scheduledAlarms.isEmpty())
    }

    @Test fun deniedExactAlarmAccessUsesExplicitlyInexactFallback() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertEquals(AlarmSchedulingResult.INEXACT,
            AndroidReminderAlarms(context, receiver).replaceNext(ReminderAlarmRequest(now, version)))
        assertTrue(shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.single().allowWhileIdle)
    }

    @Test @Config(sdk = [29]) fun preAndroidTwelveDoesNotRequireExactAlarmSpecialAccess() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertEquals(AlarmSchedulingResult.EXACT,
            AndroidReminderAlarms(context, receiver).replaceNext(ReminderAlarmRequest(now, version)))
    }

    @Test fun alarmDecoderRejectsWrongTypesComponentsAndPaths() {
        val intent = ReminderAlarmIntents.intent(receiver, ReminderAlarmRequest(now, version))
        assertEquals(version, ReminderAlarmIntents.request(intent, receiver)!!.version)
        assertNull(ReminderAlarmIntents.request(Intent(intent).putExtra("reminder_alarm.revision", "3"), receiver))
        assertNull(ReminderAlarmIntents.request(Intent(intent).putExtra("reminder_alarm.generation", -1L), receiver))
        assertNull(ReminderAlarmIntents.request(Intent(intent).setComponent(ComponentName("other.app", "Receiver")), receiver))
        assertNull(ReminderAlarmIntents.request(Intent(intent).setData(Uri.parse("https://example.test/next")), receiver))
        assertNull(ReminderAlarmIntents.request(Intent(), receiver))
    }

    @Test(expected = IllegalArgumentException::class)
    fun alarmAdapterCannotTargetAnotherApplication() {
        AndroidReminderAlarms(context, ComponentName("other.app", "Receiver"))
    }

    @Test fun generationNonceChangesNotificationTagAndImmutableActionsEvenForReusedIdsAndTimes() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val targets = ReminderIntentTargets(ComponentName(context.packageName, "MainActivity"), receiver)
        val notifications = AndroidReminderNotifications(context, targets, { ZoneOffset.UTC }, { ready })
        val one = ReminderNotificationAlias(ref, now, "generation-one")
        val two = one.copy(nonce = "generation-two")
        assertEquals(ReminderPostResult.POSTED, notifications.post(candidate(), one))
        assertEquals(ReminderPostResult.POSTED, notifications.post(candidate(), two))
        val manager = context.getSystemService(NotificationManager::class.java)
        assertEquals(2, manager.activeNotifications.size)
        val encoded = manager.activeNotifications.map {
            ReminderIntents.identity(shadowOf(it.notification.contentIntent).savedIntent)!!
        }
        assertEquals(setOf("generation-one", "generation-two"), encoded.map { it.generation }.toSet())
        assertEquals(2, encoded.map { it.tag }.toSet().size)
        notifications.cancel(one)
        assertFalse(notifications.isActive(one)); assertTrue(notifications.isActive(two))
    }

    @Test fun endOnlyUpdateUsesFreshPresentationWithThePersistedOldAlias() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val targets = ReminderIntentTargets(ComponentName(context.packageName, "MainActivity"), receiver)
        val notifications = AndroidReminderNotifications(context, targets, { ZoneOffset.UTC }, { ready })
        val alias = ReminderNotificationAlias(ref, now, "stable-end-change")
        val newEnd = ref.instanceEnd.plusSeconds(600)
        val changed = candidate().let { it.copy(occurrence = it.occurrence.copy(ref = ref.copy(instanceEnd = newEnd),
            time = EventTime.Timed(ref.instanceStart, newEnd), title = "Fresh title")) }
        notifications.post(changed, alias)
        val posted = context.getSystemService(NotificationManager::class.java).activeNotifications.single()
        assertEquals("Fresh title", posted.notification.extras.getCharSequence(Notification.EXTRA_TITLE))
        val action = ReminderIntents.identity(shadowOf(posted.notification.contentIntent).savedIntent)!!
        assertEquals(ref, action.event)
        assertEquals(alias.nonce, action.generation)
    }

    @Test fun permissionLossClearsOnlyTheOwnedReminderNamespace() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val targets = ReminderIntentTargets(ComponentName(context.packageName, "MainActivity"), receiver)
        val notifications = AndroidReminderNotifications(context, targets, { ZoneOffset.UTC }, { ready })
        notifications.post(candidate(), ReminderNotificationAlias(ref, now, "old-private"))
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify("unrelated-status", 99, Notification.Builder(context, ReminderNotificationFactory.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle("Other app status").build())
        notifications.cancelAllOwn()
        assertEquals(listOf("unrelated-status"), manager.activeNotifications.map { it.tag })
    }

    @Test fun notificationPermissionGuardDoesNotPostAndLegacyIdentityStillRoundTrips() {
        val targets = ReminderIntentTargets(ComponentName(context.packageName, "MainActivity"), receiver)
        val notifications = AndroidReminderNotifications(context, targets, { ZoneOffset.UTC }, { ready.copy(notificationPermission = false) })
        assertEquals(ReminderPostResult.PERMISSION_REQUIRED, notifications.post(candidate(), ReminderNotificationAlias(ref, now, "denied")))
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
        val legacy = ReminderIdentity(ref, now)
        assertNull(legacy.generation)
        assertEquals(legacy, ReminderIntents.identity(ReminderIntents.intent(context, targets, legacy, ReminderIntents.ACTION_OPEN)))
    }

    @Test fun revokedPlatformPermissionCannotBeBypassedByAStaleReadyHealthSnapshot() {
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val targets = ReminderIntentTargets(ComponentName(context.packageName, "MainActivity"), receiver)
        val notifications = AndroidReminderNotifications(context, targets, { ZoneOffset.UTC }, { ready })
        assertEquals(ReminderPostResult.PERMISSION_REQUIRED,
            notifications.post(candidate(), ReminderNotificationAlias(ref, now, "revoked-before-post")))
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
    }
}
