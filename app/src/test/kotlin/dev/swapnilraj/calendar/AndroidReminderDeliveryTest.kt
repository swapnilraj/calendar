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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowNotificationManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidReminderDeliveryTest {
    @Before fun resetNotificationAudit() { AuditedNotificationManager.resetAudit() }
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val receiver get() = ComponentName(context.packageName, "PrivateReminderAlarmReceiver")
    private val identity = ReminderCalendarIdentity("calendar", "uid")
    private val now = Instant.parse("2026-11-18T09:00:00Z")
    private val ref = EventRef(CalendarId(2), 7, now, now.plusSeconds(3600))
    private val key = ReminderKey.of(ref, 10)
    private val version = ReminderVersion(key, identity, 1, 3)
    private val ready = ReminderPermissionState(true, true, true, true, true)
    private fun post(alias: ReminderNotificationAlias) = ReminderPostIdentity(alias, "post-${alias.nonce}")
    private fun candidate() = ReminderCandidate(key, identity,
        EventOccurrence(ref, EventTime.Timed(ref.instanceStart, ref.instanceEnd), "Synthetic title", 0xff112233.toInt()), now.minusSeconds(600))

    @Test fun activeScopeCleanupRetainsOnlyConfiguredNamespacesAndLeavesUnrelatedNotice() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val targets = ReminderIntentTargets(ComponentName(context.packageName, "MainActivity"), receiver)
        val notifications = AndroidReminderNotifications(context, targets, { ZoneOffset.UTC }, { ready })
        val one = post(ReminderNotificationAlias(ref, now, "one"))
        val otherRef = ref.copy(calendarId = CalendarId(3), eventId = 8)
        val otherIdentity = ReminderCalendarIdentity("other", "other-uid")
        val otherCandidate = candidate().copy(key = ReminderKey.of(otherRef, 10), sourceCalendarIdentity = otherIdentity,
            occurrence = candidate().occurrence.copy(ref = otherRef))
        val other = post(ReminderNotificationAlias(otherRef, now, "other"))
        notifications.post(candidate(), one); notifications.post(otherCandidate, other)
        val manager = context.getSystemService(NotificationManager::class.java)
        // An unrelated notice is independent of the delivery components in this packet.
        manager.notify("fixture-independent-notice", 9, android.app.Notification.Builder(context,
            ReminderNotificationFactory.CHANNEL_ID).setSmallIcon(android.R.drawable.ic_popup_reminder).build())
        notifications.cancelIneligibleScopes(mapOf(CalendarId(3) to otherIdentity), emptyMap(), emptySet())
        assertFalse(notifications.isActive(one)); assertTrue(notifications.isActive(other))
        assertEquals(2, manager.activeNotifications.size) // Independent test notice survives.
        notifications.cancelIneligibleScopes(mapOf(CalendarId(3) to otherIdentity),
            mapOf(CalendarId(3) to ReminderCalendarIdentity("replacement")), emptySet())
        assertFalse(notifications.isActive(other)); assertEquals(1, manager.activeNotifications.size)
    }

    @Test fun onlyKnownNamespaceChangesOrRemovalCancelUidEnrichmentAndMissingUidPreserve() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notifications = AndroidReminderNotifications(context,
            ReminderIntentTargets(ComponentName(context.packageName, "MainActivity"), receiver), { ZoneOffset.UTC }, { ready })
        val post = post(ReminderNotificationAlias(ref, now, "uid-cases"))
        val weak = identity.copy(providerUidFingerprint = null)
        notifications.post(candidate().copy(sourceCalendarIdentity = weak), post)
        notifications.cancelIneligibleScopes(mapOf(ref.calendarId to identity), emptyMap(), emptySet())
        assertTrue(notifications.isActive(post)) // Known UID enrichment is not replacement proof.
        notifications.post(candidate(), post)
        notifications.cancelIneligibleScopes(mapOf(ref.calendarId to identity), mapOf(ref.calendarId to weak), emptySet())
        assertTrue(notifications.isActive(post))
        notifications.cancelIneligibleScopes(mapOf(ref.calendarId to identity),
            mapOf(ref.calendarId to identity.copy(providerUidFingerprint = "changed")), emptySet())
        assertFalse(notifications.isActive(post))
        notifications.post(candidate(), post)
        notifications.cancelIneligibleScopes(mapOf(ref.calendarId to identity), emptyMap(), setOf(ref.calendarId))
        assertFalse(notifications.isActive(post))
    }

    @Test fun malformedLegacyAndCalendarTagMismatchScopeClearWithoutReposting() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val targets = ReminderIntentTargets(ComponentName(context.packageName, "MainActivity"), receiver)
        val notifications = AndroidReminderNotifications(context, targets, { ZoneOffset.UTC }, { ready })
        val manager = context.getSystemService(NotificationManager::class.java)
        for (mode in 0..4) {
            val post = post(ReminderNotificationAlias(ref, now, "invalid-$mode"))
            notifications.post(candidate(), post)
            val active = manager.activeNotifications.single()
            when (mode) {
                0 -> active.notification.extras.remove(AndroidReminderNotifications.SCOPE_VERSION)
                1 -> active.notification.extras.putString(AndroidReminderNotifications.SCOPE_VERSION, "1")
                2 -> active.notification.extras.putLong(AndroidReminderNotifications.SCOPE_CALENDAR, 99L)
                3 -> active.notification.extras.putString(AndroidReminderNotifications.SCOPE_FINGERPRINT, "")
                else -> active.notification.extras.putInt(AndroidReminderNotifications.SCOPE_UID, 1)
            }
            manager.notify(active.tag, active.id, active.notification)
            notifications.cancelIneligibleScopes(mapOf(ref.calendarId to identity), emptyMap(), emptySet())
            assertTrue(manager.activeNotifications.isEmpty())
        }
    }

    @Test @Config(shadows = [AuditedNotificationManager::class])
    fun activeScopeCancellationFailureIsExposedInsteadOfPretendingSuccess() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notifications = AndroidReminderNotifications(context,
            ReminderIntentTargets(ComponentName(context.packageName, "MainActivity"), receiver), { ZoneOffset.UTC }, { ready })
        notifications.post(candidate(), post(ReminderNotificationAlias(ref, now, "cancel-failure")))
        AuditedNotificationManager.failNextCancel = true
        try { notifications.cancelIneligibleScopes(emptyMap(), emptyMap(), emptySet()); fail("Unavailable cleanup must propagate") }
        catch (_: IllegalStateException) { }
        AuditedNotificationManager.failNextEnumeration = true
        try { notifications.cancelIneligibleScopes(emptyMap(), emptyMap(), emptySet()); fail("Unavailable enumeration must propagate") }
        catch (_: IllegalStateException) { }
    }

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
        assertEquals(ReminderPostResult.POSTED, notifications.post(candidate(), post(one)))
        assertEquals(ReminderPostResult.POSTED, notifications.post(candidate(), post(two)))
        val manager = context.getSystemService(NotificationManager::class.java)
        assertEquals(2, manager.activeNotifications.size)
        val encoded = manager.activeNotifications.map {
            ReminderIntents.identity(shadowOf(it.notification.contentIntent).savedIntent)!!
        }
        assertEquals(setOf("generation-one", "generation-two"), encoded.map { it.generation }.toSet())
        assertEquals(2, encoded.map { it.tag }.toSet().size)
        notifications.cancel(one)
        assertFalse(notifications.isActive(post(one))); assertTrue(notifications.isActive(post(two)))
    }

    @Test fun endOnlyUpdateUsesFreshPresentationWithThePersistedOldAlias() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val targets = ReminderIntentTargets(ComponentName(context.packageName, "MainActivity"), receiver)
        val notifications = AndroidReminderNotifications(context, targets, { ZoneOffset.UTC }, { ready })
        val alias = ReminderNotificationAlias(ref, now, "stable-end-change")
        val newEnd = ref.instanceEnd.plusSeconds(600)
        val changed = candidate().let { it.copy(occurrence = it.occurrence.copy(ref = ref.copy(instanceEnd = newEnd),
            time = EventTime.Timed(ref.instanceStart, newEnd), title = "Fresh title")) }
        notifications.post(changed, post(alias))
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
        notifications.post(candidate(), post(ReminderNotificationAlias(ref, now, "old-private")))
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify("unrelated-status", 99, Notification.Builder(context, ReminderNotificationFactory.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle("Other app status").build())
        notifications.cancelAllOwn()
        assertEquals(listOf("unrelated-status"), manager.activeNotifications.map { it.tag })
    }

    @Test fun notificationPermissionGuardDoesNotPostAndLegacyIdentityStillRoundTrips() {
        val targets = ReminderIntentTargets(ComponentName(context.packageName, "MainActivity"), receiver)
        val notifications = AndroidReminderNotifications(context, targets, { ZoneOffset.UTC }, { ready.copy(notificationPermission = false) })
        assertEquals(ReminderPostResult.PERMISSION_REQUIRED, notifications.post(candidate(), post(ReminderNotificationAlias(ref, now, "denied"))))
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
            notifications.post(candidate(), post(ReminderNotificationAlias(ref, now, "revoked-before-post"))))
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
    }

    @Test fun repostKeepsStableTagButOldImmutableActionsKeepTheirOriginalPostToken() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val targets = ReminderIntentTargets(ComponentName(context.packageName, "MainActivity"), receiver)
        val notifications = AndroidReminderNotifications(context, targets, { ZoneOffset.UTC }, { ready })
        val alias = ReminderNotificationAlias(ref, now, "stable-generation")
        val first = ReminderPostIdentity(alias, "post-one")
        val second = ReminderPostIdentity(alias, "post-two")
        notifications.post(candidate(), first)
        val manager = context.getSystemService(NotificationManager::class.java)
        val firstNotification = manager.activeNotifications.single()
        val retainedOpen = firstNotification.notification.contentIntent
        val retainedSnooze = firstNotification.notification.actions.first().actionIntent
        notifications.post(candidate(), second)
        val current = manager.activeNotifications.single()
        assertEquals(firstNotification.tag, current.tag)
        assertNotEquals(retainedOpen, current.notification.contentIntent)
        assertNotEquals(retainedSnooze, current.notification.actions.first().actionIntent)
        val oldAction = ReminderIntents.identity(shadowOf(retainedSnooze).savedIntent)!!
        val newAction = ReminderIntents.identity(shadowOf(current.notification.actions.first().actionIntent).savedIntent)!!
        assertEquals(first, oldAction.postIdentityOrNull())
        assertEquals(second, newAction.postIdentityOrNull())
        assertNotEquals(shadowOf(retainedSnooze).savedIntent.data,
            shadowOf(current.notification.actions.first().actionIntent).savedIntent.data)
        assertEquals("post-two", current.notification.extras.getString(ReminderNotificationFactory.EXTRA_POST_TOKEN))
        assertNull(current.notification.publicVersion.extras.getString(ReminderNotificationFactory.EXTRA_POST_TOKEN))
        assertFalse(notifications.isActive(first))
        assertTrue(notifications.isActive(second))
    }

    @Test fun legacyIdentityIsNeverUpgradedToTheCurrentLedgerToken() {
        val legacy = ReminderIdentity(ref, now, "generation")
        assertNull(legacy.postIdentityOrNull())
        val targets = ReminderIntentTargets(ComponentName(context.packageName, "MainActivity"), receiver)
        val decoded = ReminderIntents.identity(ReminderIntents.intent(context, targets, legacy, ReminderIntents.ACTION_DISMISS))!!
        assertNull(decoded.postToken)
        assertNull(decoded.postIdentityOrNull())
    }

    @Test @Config(shadows = [AuditedNotificationManager::class])
    fun freshSnoozePostCancelsOldActiveStatusBeforeNotifyButSameTokenUpdateDoesNot() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val targets = ReminderIntentTargets(ComponentName(context.packageName, "MainActivity"), receiver)
        val notifications = AndroidReminderNotifications(context, targets, { ZoneOffset.UTC }, { ready })
        val alias = ReminderNotificationAlias(ref, now, "snooze-cancel-crash")
        val first = ReminderPostIdentity(alias, "old-post")
        val fresh = ReminderPostIdentity(alias, "fresh-post")
        notifications.post(candidate(), first)
        notifications.post(candidate(), fresh)
        assertEquals(listOf("notify", "cancel", "notify"), AuditedNotificationManager.operations.map { it.kind })
        assertEquals("old-post", AuditedNotificationManager.operations[1].previousToken)
        assertNull(AuditedNotificationManager.operations[2].previousToken)
        assertTrue(notifications.isActive(fresh))
        val latest = candidate().let { it.copy(occurrence = it.occurrence.copy(title = "Updated visible title")) }
        notifications.post(latest, fresh)
        assertEquals(listOf("notify", "cancel", "notify", "notify"), AuditedNotificationManager.operations.map { it.kind })
        assertEquals("fresh-post", AuditedNotificationManager.operations.last().previousToken)
        val active = context.getSystemService(NotificationManager::class.java).activeNotifications.single()
        assertTrue(active.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
    }

    @Test @Config(shadows = [AuditedNotificationManager::class])
    fun freshDurablePostAlsoRemovesAnOldSameTagStatusWithNoPrivatePostToken() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val targets = ReminderIntentTargets(ComponentName(context.packageName, "MainActivity"), receiver)
        val alias = ReminderNotificationAlias(ref, now, "legacy-status")
        val legacy = ReminderNotificationFactory(context, targets).build(EventDetails(candidate().occurrence, null), now,
            ZoneOffset.UTC, notificationIdentity = ReminderIdentity(ref, now, alias.nonce))
        context.getSystemService(NotificationManager::class.java).notify(legacy.tag, legacy.id, legacy.notification)
        val notifications = AndroidReminderNotifications(context, targets, { ZoneOffset.UTC }, { ready })
        val fresh = ReminderPostIdentity(alias, "new-token")
        assertEquals(ReminderPostResult.POSTED, notifications.post(candidate(), fresh))
        assertEquals(listOf("notify", "cancel", "notify"), AuditedNotificationManager.operations.map { it.kind })
        assertTrue(notifications.isActive(fresh))
    }

    @Test @Config(shadows = [AuditedNotificationManager::class])
    fun failureBetweenCancellationAndFreshPostIsUncertainAndNeverRetriedByAdapter() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val targets = ReminderIntentTargets(ComponentName(context.packageName, "MainActivity"), receiver)
        val notifications = AndroidReminderNotifications(context, targets, { ZoneOffset.UTC }, { ready })
        val alias = ReminderNotificationAlias(ref, now, "non-atomic-gap")
        notifications.post(candidate(), ReminderPostIdentity(alias, "old-post"))
        AuditedNotificationManager.failNextNotify = true
        assertEquals(ReminderPostResult.UNCERTAIN, notifications.post(candidate(), ReminderPostIdentity(alias, "fresh-post")))
        assertEquals(listOf("notify", "cancel", "notify"), AuditedNotificationManager.operations.map { it.kind })
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
    }
}

/** Keep real ShadowNotificationManager storage while auditing cancel/notify order and a Binder failure. */
@Implements(NotificationManager::class)
class AuditedNotificationManager : ShadowNotificationManager() {
    data class Operation(val kind: String, val previousToken: String?)

    @Implementation
    override fun getActiveNotifications(): Array<android.service.notification.StatusBarNotification> {
        if (failNextEnumeration) { failNextEnumeration = false; throw IllegalStateException("Synthetic enumeration failure") }
        return super.getActiveNotifications()
    }

    @Implementation
    override fun notify(tag: String?, id: Int, notification: Notification) {
        operations.add(Operation("notify", getNotification(tag, id)?.extras?.getString(ReminderNotificationFactory.EXTRA_POST_TOKEN)))
        if (failNextNotify) { failNextNotify = false; throw IllegalStateException("Synthetic post failure") }
        super.notify(tag, id, notification)
    }

    @Implementation
    override fun cancel(tag: String?, id: Int) {
        if (failNextCancel) { failNextCancel = false; throw IllegalStateException("Synthetic cancellation failure") }
        operations.add(Operation("cancel", getNotification(tag, id)?.extras?.getString(ReminderNotificationFactory.EXTRA_POST_TOKEN)))
        super.cancel(tag, id)
    }

    companion object {
        val operations = mutableListOf<Operation>()
        var failNextNotify = false
        var failNextCancel = false
        var failNextEnumeration = false
        fun resetAudit() { operations.clear(); failNextNotify = false; failNextCancel = false; failNextEnumeration = false; ShadowNotificationManager.reset() }
    }
}
