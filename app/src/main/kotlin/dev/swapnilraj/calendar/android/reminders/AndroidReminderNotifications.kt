package dev.swapnilraj.calendar.android.reminders

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import dev.swapnilraj.calendar.core.CalendarId
import dev.swapnilraj.calendar.core.ReminderCalendarIdentity
import dev.swapnilraj.calendar.core.EventDetails
import dev.swapnilraj.calendar.core.ReminderCandidate
import dev.swapnilraj.calendar.core.ReminderNotificationAlias
import dev.swapnilraj.calendar.core.ReminderNotificationPort
import dev.swapnilraj.calendar.core.ReminderPostResult
import dev.swapnilraj.calendar.core.ReminderPostIdentity
import java.time.ZoneId
import kotlinx.coroutines.CancellationException

/** Presentation only; persistent claims/recovery belong to the coordinator and ledger. */
class AndroidReminderNotifications(
    private val context: Context,
    targets: ReminderIntentTargets,
    private val displayZone: () -> ZoneId = { ZoneId.systemDefault() },
    private val permissions: () -> ReminderPermissionState = { ReminderPermissionReader(context).read() },
) : ReminderNotificationPort {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val factory = ReminderNotificationFactory(context, targets)

    override fun post(candidate: ReminderCandidate, identity: ReminderPostIdentity): ReminderPostResult {
        val permission = permissions()
        if (!permission.calendarAccess || !permission.canPostNotifications) return ReminderPostResult.PERMISSION_REQUIRED
        return try {
            val notification = factory.build(EventDetails(candidate.occurrence, null), identity.alias.anchorDue,
                displayZone(), notificationIdentity = identity.identity())
            notification.notification.extras.apply {
                putInt(SCOPE_VERSION, 1)
                putLong(SCOPE_CALENDAR, candidate.key.calendarId.value)
                putString(SCOPE_FINGERPRINT, candidate.sourceCalendarIdentity.fingerprint)
                candidate.sourceCalendarIdentity.providerUidFingerprint?.let { putString(SCOPE_UID, it) }
            }
            val current = permissions()
            if (!current.calendarAccess || !current.canPostNotifications) return ReminderPostResult.PERMISSION_REQUIRED
            // Keep the platform POST check visible to both the runtime and static permission audit;
            // the injected health reader additionally guards calendar/global/channel settings.
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return ReminderPostResult.PERMISSION_REQUIRED
            // A snooze may commit before the process dies while cancelling the previous alert.
            // Fresh durable posts must replace that old status, not silently update it under
            // ONLY_ALERT_ONCE. Same-token metadata updates keep the existing status and stay quiet.
            if (manager.activeNotifications.any { it.tag == notification.tag && it.id == notification.id &&
                    it.notification.extras.getString(ReminderNotificationFactory.EXTRA_POST_TOKEN) != identity.token }) {
                manager.cancel(notification.tag, notification.id)
            }
            manager.notify(notification.tag, notification.id, notification.notification)
            // Accepted by NotificationManager, not a claim that sound/device delivery was observed.
            ReminderPostResult.POSTED
        } catch (_: RuntimeException) {
            // Binder can fail after accepting work. A thrown call is not safe evidence for replay.
            ReminderPostResult.UNCERTAIN
        }
    }

    override fun isActive(identity: ReminderPostIdentity): Boolean = manager.activeNotifications.any {
        it.tag == identity.alias.identity().tag && it.id == 0 &&
            it.notification.extras.getString(ReminderNotificationFactory.EXTRA_POST_TOKEN) == identity.token
    }

    override fun cancel(alias: ReminderNotificationAlias) { manager.cancel(alias.identity().tag, 0) }

    override fun cancelIneligibleScopes(
        allowedCalendars: Map<CalendarId, ReminderCalendarIdentity>,
        observedCalendars: Map<CalendarId, ReminderCalendarIdentity>,
        removedCalendars: Set<CalendarId>,
    ) {
        // Android bounds this enumeration to currently posted app alerts, not persisted history.
        // Missing/malformed legacy metadata is not proof of a safe scope: clear, never re-post.
        manager.activeNotifications.filter { it.tag?.startsWith(OWN_PREFIX) == true }.forEach { active ->
            val scope = scope(active.tag, active.notification)
            val allowed = scope?.let { allowedCalendars[it.first] }
            if (scope == null || allowed == null || scope.first in removedCalendars ||
                scope.second.isKnownDifferentFrom(allowed) ||
                observedCalendars[scope.first]?.isKnownDifferentFrom(scope.second) == true) {
                manager.cancel(active.tag, active.id)
            }
        }
    }

    @Suppress("DEPRECATION") // Raw Bundle values are intentionally checked, never coerced/defaulted.
    private fun scope(tag: String?, notification: android.app.Notification): Pair<CalendarId, ReminderCalendarIdentity>? = try {
        val extras = notification.extras
        require(extras.get(SCOPE_VERSION) == 1)
        val calendar = CalendarId(requireNotNull(extras.get(SCOPE_CALENDAR) as? Long))
        val fingerprint = requireNotNull(extras.get(SCOPE_FINGERPRINT) as? String)
        val uid = if (extras.containsKey(SCOPE_UID)) requireNotNull(extras.get(SCOPE_UID) as? String) else null
        val uri = Uri.parse(requireNotNull(tag))
        require(uri.scheme == "daylight" && uri.authority == "reminder" && uri.pathSegments.firstOrNull() == "v1")
        require(uri.pathSegments.getOrNull(1) == calendar.value.toString())
        calendar to ReminderCalendarIdentity(fingerprint, uid)
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: RuntimeException) { null }

    internal companion object {
        const val OWN_PREFIX = "daylight://reminder/v1/"
        const val SCOPE_VERSION = "dev.swapnilraj.calendar.reminders.SCOPE_VERSION"
        const val SCOPE_CALENDAR = "dev.swapnilraj.calendar.reminders.SCOPE_CALENDAR"
        const val SCOPE_FINGERPRINT = "dev.swapnilraj.calendar.reminders.SCOPE_FINGERPRINT"
        const val SCOPE_UID = "dev.swapnilraj.calendar.reminders.SCOPE_UID"
    }

    override fun cancelAllOwn() {
        manager.activeNotifications.filter { it.tag?.startsWith(OWN_PREFIX) == true }
            .forEach { manager.cancel(it.tag, it.id) }
    }
}

internal fun ReminderNotificationAlias.identity(): ReminderIdentity = ReminderIdentity(ref, anchorDue, nonce)
internal fun ReminderPostIdentity.identity(): ReminderIdentity = ReminderIdentity(alias.ref, alias.anchorDue, alias.nonce, token)

/** Missing legacy tokens must never be replaced with the ledger's newest token. */
fun ReminderIdentity.postIdentityOrNull(): ReminderPostIdentity? = generation?.let { nonce ->
    postToken?.let { token -> ReminderPostIdentity(ReminderNotificationAlias(event, due, nonce), token) }
}
