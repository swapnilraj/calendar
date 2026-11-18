package dev.swapnilraj.calendar

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import dev.swapnilraj.calendar.android.reminders.*
import dev.swapnilraj.calendar.core.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidReminderTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val start = Instant.parse("2026-11-18T09:00:00Z")
    private val ref = EventRef(CalendarId(2), 1, start, start.plusSeconds(3600), RecurrenceRef(8, start))
    private val due = start.minusSeconds(600)
    private val targets get() = ReminderIntentTargets(ComponentName(context.packageName, "TestActivity"),
        ComponentName(context.packageName, "TestPrivateReceiver"))
    private fun details(event: EventRef = ref) = EventDetails(EventOccurrence(event,
        EventTime.Timed(event.instanceStart, event.instanceEnd), "Synthetic meeting", 0xff123456.toInt()), null)

    @Test fun notificationIdentityUsesCompleteValuesRatherThanCollidingNumericHashes() {
        val first = ReminderIdentity(ref, due)
        // These distinct event IDs have the same Long hashCode.
        val other = ReminderIdentity(ref.copy(eventId = 1L shl 32), due)
        assertEquals(ref.eventId.hashCode(), other.event.eventId.hashCode())
        assertNotEquals(first.tag, other.tag)
        assertNotEquals(first.tag, first.copy(due = due.plusNanos(1)).tag)
        assertNotEquals(first.tag, first.copy(event = ref.copy(calendarId = CalendarId(3))).tag)
        assertEquals(first.tag, ReminderIdentity(ref.copy(), due).tag)
    }

    @Test fun actionIntentsRoundTripOccurrenceAndHaveDistinctImmutableIdentities() {
        val identity = ReminderIdentity(ref, due)
        val open = ReminderIntents.intent(context, targets, identity, ReminderIntents.ACTION_OPEN)
        assertEquals(identity, ReminderIntents.identity(open))
        val five = ReminderIntents.intent(context, targets, identity, ReminderIntents.ACTION_SNOOZE, 5)
        val ten = ReminderIntents.intent(context, targets, identity, ReminderIntents.ACTION_SNOOZE, 10)
        assertNotEquals(five.data, ten.data)
        assertEquals(5, five.getIntExtra(ReminderIntents.EXTRA_SNOOZE_MINUTES, -1))
        assertEquals(targets.actionReceiver, five.component)
        assertEquals(identity, ReminderIntents.identity(five))
        assertEquals(targets.eventActivity, open.component)
        assertNull(ReminderIntents.identity(Intent()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun externalIntentDestinationIsRejected() {
        ReminderIntents.intent(context, targets.copy(eventActivity = ComponentName("other.app", "Other")),
            ReminderIdentity(ref, due), ReminderIntents.ACTION_OPEN)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedSnoozeIsRejected() {
        ReminderIntents.intent(context, targets, ReminderIdentity(ref, due), ReminderIntents.ACTION_SNOOZE, 1)
    }

    @Test fun privateNotificationHasStableTagAndOccurrencePreservingActions() {
        val factory = ReminderNotificationFactory(context, targets)
        val result = factory.build(details(), due, ZoneId.of("Europe/London"))
        val notification = result.notification
        assertEquals(result.tag, factory.build(details(), due, ZoneId.of("Europe/London")).tag)
        assertEquals(0, result.id)
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertEquals(Notification.CATEGORY_EVENT, notification.category)
        assertEquals("Calendar reminder", notification.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertFalse(notification.publicVersion.extras.toString().contains("Synthetic meeting"))
        assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
        val open = shadowOf(notification.contentIntent).savedIntent
        assertEquals(ReminderIdentity(ref, due), ReminderIntents.identity(open))
        assertTrue(shadowOf(notification.contentIntent).flags and PendingIntent.FLAG_IMMUTABLE != 0)
        assertEquals(listOf(5, 10, 30), notification.actions.map {
            val pending = it.actionIntent
            assertTrue(shadowOf(pending).flags and PendingIntent.FLAG_IMMUTABLE != 0)
            val action = shadowOf(pending).savedIntent
            assertEquals(ReminderIdentity(ref, due), ReminderIntents.identity(action))
            assertEquals(targets.actionReceiver, action.component)
            action.getIntExtra(ReminderIntents.EXTRA_SNOOZE_MINUTES, -1)
        })
        assertEquals(ReminderIntents.ACTION_DISMISS, shadowOf(notification.deleteIntent).savedIntent.action)
    }

    @Test fun allDayNotificationKeepsItsIntendedDateInNegativeOffsetZone() {
        val dates = CalendarDateRange.day(LocalDate.of(2026, 11, 18))
        val allDayRef = ref.copy(instanceStart = Instant.parse("2026-11-18T00:00:00Z"),
            instanceEnd = Instant.parse("2026-11-19T00:00:00Z"))
        val event = details(allDayRef).copy(occurrence = EventOccurrence(allDayRef,
            EventTime.AllDay(dates), "", 0xff123456.toInt()))
        val notification = ReminderNotificationFactory(context, targets)
            .build(event, due, ZoneId.of("America/Los_Angeles")).notification
        assertEquals("No title", notification.extras.getCharSequence(Notification.EXTRA_TITLE))
        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue(text.contains("All day"))
        assertTrue(text.contains("18"))
        assertFalse(text.contains("16:00"))
    }

    @Test fun timedNotificationHonoursTheChosenClockFormat() {
        val factory = ReminderNotificationFactory(context, targets)
        val twelve = factory.build(details(), due, ZoneId.of("Europe/London"), false)
            .notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        val twentyFour = factory.build(details(), due, ZoneId.of("Europe/London"), true)
            .notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue(twelve.contains("9:00 AM"))
        assertTrue(twentyFour.contains("09:00"))
        assertFalse(twentyFour.contains("AM"))
    }

    @Test fun channelCreationPreservesUserDisabledImportanceAndDoesNotBypassDnd() {
        val manager = context.getSystemService(NotificationManager::class.java)
        val factory = ReminderNotificationFactory(context, targets)
        manager.createNotificationChannel(NotificationChannel(ReminderNotificationFactory.CHANNEL_ID, "Disabled", NotificationManager.IMPORTANCE_NONE))
        factory.createChannel()
        assertEquals(NotificationManager.IMPORTANCE_NONE, manager.getNotificationChannel(ReminderNotificationFactory.CHANNEL_ID).importance)
        assertFalse(manager.getNotificationChannel(ReminderNotificationFactory.CHANNEL_ID).canBypassDnd())
        assertFalse(ReminderPermissionReader(context).read().channelEnabled)
    }

    @Test fun newChannelIsPrivateAndHonoursDoNotDisturb() {
        ReminderNotificationFactory(context, targets).createChannel()
        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(ReminderNotificationFactory.CHANNEL_ID)
        assertEquals(Notification.VISIBILITY_PRIVATE, channel.lockscreenVisibility)
        assertFalse(channel.canBypassDnd())
    }

    @Test @Config(sdk = [29]) fun oldAndroidDoesNotRequireNewRuntimeOrAlarmAccess() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.READ_CALENDAR)
        val state = ReminderPermissionReader(context).read()
        assertTrue(state.calendarAccess)
        assertTrue(state.notificationPermission)
        assertTrue(state.exactAlarmAccess)
        assertTrue(state.channelEnabled)
    }

    @Test @Config(sdk = [31]) fun exactAlarmAccessIsReportedOnAndroidTwelve() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertFalse(ReminderPermissionReader(context).read().exactAlarmAccess)
        assertTrue(ReminderPermissionReader(context).read().notificationPermission)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        assertTrue(ReminderPermissionReader(context).read().exactAlarmAccess)
    }

    @Test fun notificationRuntimePermissionIsReportedOnAndroidThirteenAndLater() {
        val application = shadowOf(context as Application)
        application.denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(ReminderPermissionReader(context).read().notificationPermission)
        application.grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(ReminderPermissionReader(context).read().notificationPermission)
    }

    @Test fun healthExposesEveryBlockerWithoutEquatingTestPostingWithExactDelivery() {
        val state = ReminderPermissionState(false, false, false, false, false)
        assertEquals(ReminderIssue.entries.toSet(), state.issues)
        assertFalse(state.ready)
        val postingOnly = ReminderPermissionState(false, true, true, true, false)
        assertTrue(postingOnly.canPostNotifications)
        assertFalse(postingOnly.ready)
        assertTrue(postingOnly.issues.contains(ReminderIssue.EXACT_ALARM_ACCESS))
    }
}
