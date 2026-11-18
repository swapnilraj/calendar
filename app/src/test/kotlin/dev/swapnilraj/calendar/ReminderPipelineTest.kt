package dev.swapnilraj.calendar

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.CalendarContract
import dev.swapnilraj.calendar.android.reminders.*
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.calendar.CalendarLocalStore
import dev.swapnilraj.calendar.data.calendar.CalendarReminderSource
import dev.swapnilraj.calendar.data.calendar.SqliteReminderLedger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowContentResolver

/** Interoperability checks use real source/SQL ledger/coordinator/Android adapters, not mock ports.
 * No receivers/jobs are registered here and no physical delivery or Google sync is asserted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReminderPipelineTest {
    private val origin = Instant.parse("2026-11-18T09:00:00Z")
    private val calendarId = CalendarId(2)
    private val ready = ReminderPermissionState(true, true, true, true, true)

    /** Executes the production adapter's selections/projections against SQLite instead of mirroring
     * its query parser. Only Instances URI bounds are translated into a virtual-table predicate.
     */
    private class Provider(start: Instant, end: Instant, allDay: Boolean, minutes: Int) : ContentProvider() {
        private val db = SQLiteDatabase.create(null)
        val instanceRanges = mutableListOf<Pair<Long, Long>>()
        init {
            table("calendars", mapOf("_id" to 2L, "account_name" to "synthetic@example.invalid", "account_type" to "com.google",
                "ownerAccount" to null, "calendar_access_level" to 700, "visible" to 1, "sync_events" to 1, "calendar_color" to 123))
            val event = mapOf<String, Any?>("_id" to 10L, "calendar_id" to 2L, "dtstart" to start.toEpochMilli(),
                "dtend" to end.toEpochMilli(), "allDay" to if (allDay) 1 else 0, "eventStatus" to 1, "deleted" to 0,
                "selfAttendeeStatus" to 0, "original_id" to null, "originalInstanceTime" to null, "rrule" to null,
                "rdate" to null, "uid2445" to "synthetic-event-uid", "title" to "Synthetic original title", "displayColor" to 456)
            table("events", event)
            table("instances", event + mapOf("event_id" to 10L, "begin" to start.toEpochMilli(), "end" to end.toEpochMilli()))
            table("reminders", mapOf("_id" to 30L, "event_id" to 10L, "minutes" to minutes,
                "method" to CalendarContract.Reminders.METHOD_ALERT))
        }
        private fun table(name: String, row: Map<String, Any?>) {
            val numeric = setOf("original_id", "originalInstanceTime")
            db.execSQL("CREATE TABLE $name (" + row.entries.joinToString(",") { (key, value) ->
                "\"$key\" " + if (value is Number || key in numeric) "INTEGER" else "TEXT"
            } + ")")
            db.insertOrThrow(name, null, ContentValues().apply { row.forEach { (key, value) ->
                when (value) { null -> putNull(key); is Number -> put(key, value.toLong()); else -> put(key, value.toString()) }
            } })
        }
        fun title(value: String) {
            listOf("events", "instances").forEach { db.update(it, ContentValues().apply { put("title", value) }, "_id = 10", null) }
        }
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
            val table = uri.pathSegments.first()
            require(table in setOf("calendars", "events", "reminders", "instances")) { "Unexpected child/detail read" }
            val where = if (table == "instances") {
                val from = uri.pathSegments[2].toLong(); val until = uri.pathSegments[3].toLong()
                instanceRanges += from to until
                "($selection) AND begin < $until AND (end > $from OR (begin = end AND begin >= $from))"
            } else selection
            return db.query(table, projection, where, args, null, null, sort)
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read-only provider fixture")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = error("Read-only provider fixture")
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = error("Read-only provider fixture")
        fun close() = db.close()
    }

    private class Time(var value: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(value, zone)
        override fun instant(): Instant = value
    }

    private inner class Pipeline(val provider: Provider, val time: Time, horizon: Duration = Duration.ofHours(1)) {
        val context: Application = RuntimeEnvironment.getApplication()
        val name = "reminder-pipeline-${UUID.randomUUID()}.db"
        private val receiver = ComponentName(context.packageName, "SyntheticReminderReceiver")
        val source = CalendarReminderSource(context.contentResolver, { it == Manifest.permission.READ_CALENDAR })
        private val info = CalendarInfo(calendarId, "Synthetic", 123, CalendarAccount("synthetic@example.invalid", "com.google"),
            null, null, 700, true, true)
        var configuration = ReminderConfiguration(true, setOf(calendarId), ZoneOffset.UTC,
            expectedCalendarIdentities = mapOf(calendarId to CalendarReminderSource.calendarIdentity(info)!!))
        var store = CalendarLocalStore(context, name)
        var ledger = SqliteReminderLedger(store)
        val notifications = AndroidReminderNotifications(context,
            ReminderIntentTargets(ComponentName(context.packageName, "MainActivity"), receiver), { configuration.deviceZone }, { ready })
        private val alarms = AndroidReminderAlarms(context, receiver)
        private val planningHorizon = horizon
        var coordinator = coordinator()
        val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)
        init {
            shadowOf(context).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.POST_NOTIFICATIONS)
            ShadowAlarmManager.setCanScheduleExactAlarms(true)
            ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        }
        private fun coordinator() = ReminderCoordinator(source, ledger, alarms, notifications, { configuration }, { ready },
            time, horizon = planningHorizon, catchUp = Duration.ofHours(1))
        fun reopen() {
            store.close()
            store = CalendarLocalStore(context, name)
            ledger = SqliteReminderLedger(store)
            coordinator = coordinator()
        }
        fun nextAlarm(): ReminderAlarmRequest {
            val scheduled = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.single()
            return ReminderAlarmIntents.request(shadowOf(scheduled.operation).savedIntent, receiver)!!
        }
        fun postIdentity(notification: Notification): ReminderPostIdentity =
            ReminderIntents.identity(shadowOf(notification.actions.first().actionIntent).savedIntent)!!.postIdentityOrNull()!!
        fun close() {
            alarms.replaceNext(null); notifications.cancelAllOwn(); store.close(); context.deleteDatabase(name); provider.close()
        }
    }

    @Test fun unpinClearsActualPostedAlertWhileSqlHistorySurvivesReopenAndReenable() = runBlocking {
        val pipe = Pipeline(Provider(origin.plusSeconds(600), origin.plusSeconds(4200), false, 10), Time(origin))
        try {
            assertEquals(1, pipe.coordinator.reconcile().posted)
            val original = pipe.postIdentity(pipe.manager.activeNotifications.single().notification)
            val posted = pipe.ledger.findByAlias(original.alias)!!
            val enabled = pipe.configuration
            pipe.configuration = enabled.copy(enabledCalendars = emptySet(), expectedCalendarIdentities = emptyMap())
            assertEquals(0, pipe.coordinator.reconcile().posted)
            assertTrue(pipe.manager.activeNotifications.isEmpty())
            assertEquals(ReminderDeliveryState.POSTED, pipe.ledger.findByAlias(original.alias)!!.state)
            pipe.reopen()
            assertEquals(posted, pipe.ledger.findByAlias(original.alias))
            pipe.configuration = enabled
            assertEquals(0, pipe.coordinator.reconcile().posted)
            assertTrue(pipe.manager.activeNotifications.isEmpty())
            val retained = pipe.ledger.findByAlias(original.alias)!!
            assertEquals(ReminderDeliveryState.POSTED, retained.state)
            assertEquals(original, retained.postIdentity)
            assertEquals(posted.generation, retained.generation)
        } finally { pipe.close() }
    }

    @Test fun alarmFreshReadRestartAndSnoozeReplayUseOneDurablePipeline() = runBlocking {
        val due = origin.plusSeconds(600)
        val provider = Provider(due.plusSeconds(600), due.plusSeconds(4200), false, 10)
        val pipe = Pipeline(provider, Time(origin))
        try {
            val firstCycle = pipe.coordinator.reconcile()
            assertEquals(ReminderCycleStatus.READY, firstCycle.status)
            assertEquals(AlarmSchedulingResult.EXACT, firstCycle.alarm)
            assertEquals(due, pipe.nextAlarm().at)
            assertTrue(pipe.manager.activeNotifications.isEmpty())
            provider.title("Synthetic title freshly changed before alarm")
            val alarm = pipe.nextAlarm()
            pipe.time.value = due
            assertEquals(1, pipe.coordinator.onAlarm(alarm.version).posted)
            val first = pipe.manager.activeNotifications.single()
            assertEquals("Synthetic title freshly changed before alarm", first.notification.extras.getString(Notification.EXTRA_TITLE))
            val oldPost = pipe.postIdentity(first.notification)
            assertEquals(oldPost.token, first.notification.extras.getString(ReminderNotificationFactory.EXTRA_POST_TOKEN))
            assertEquals(oldPost, pipe.ledger.findByAlias(oldPost.alias)!!.postIdentity)
            pipe.reopen()
            assertEquals(0, pipe.coordinator.reconcile().posted)
            assertEquals(oldPost, pipe.ledger.findByAlias(oldPost.alias)!!.postIdentity)
            assertEquals(ReminderActionResult.APPLIED, pipe.coordinator.snooze(oldPost, 5))
            assertEquals(due.plusSeconds(300), pipe.nextAlarm().at)
            assertEquals(ReminderDeliveryState.SNOOZED, pipe.ledger.findByAlias(oldPost.alias)!!.state)
            assertTrue(pipe.manager.activeNotifications.isEmpty())
            pipe.reopen()
            pipe.time.value = due.plusSeconds(300)
            assertEquals(1, pipe.coordinator.onAlarm(pipe.nextAlarm().version).posted)
            val current = pipe.manager.activeNotifications.single()
            val newPost = pipe.postIdentity(current.notification)
            assertEquals(first.tag, current.tag)
            assertEquals(oldPost.alias, newPost.alias)
            assertNotEquals(oldPost.token, newPost.token)
            val before = pipe.ledger.findByAlias(newPost.alias)!!
            assertEquals(ReminderActionResult.IGNORED, pipe.coordinator.dismiss(oldPost))
            assertEquals(ReminderActionResult.IGNORED, pipe.coordinator.snooze(oldPost, 30))
            assertEquals(before, pipe.ledger.findByAlias(newPost.alias))
            assertTrue(pipe.notifications.isActive(newPost))
            assertEquals(ReminderActionResult.APPLIED, pipe.coordinator.dismiss(newPost))
            assertEquals(ReminderDeliveryState.DISMISSED, pipe.ledger.findByAlias(newPost.alias)!!.state)
            assertTrue(pipe.manager.activeNotifications.isEmpty())
            assertEquals(0, pipe.coordinator.reconcile().posted)
            pipe.store.readableDatabase.rawQuery("SELECT payload FROM reminder_ledger", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertFalse(cursor.getString(0).contains("Synthetic title"))
                assertFalse(cursor.getString(0).contains("synthetic@example.invalid"))
            }
        } finally { pipe.close() }
    }

    @Test fun allDayZoneChangeOutsideDiscoveryWindowPreservesPostedHistoryAfterRestart() = runBlocking {
        val start = Instant.parse("2026-11-18T00:00:00Z")
        val pipe = Pipeline(Provider(start, start.plusSeconds(86_400), true, -540), Time(origin))
        try {
            assertEquals(1, pipe.coordinator.reconcile().posted)
            val original = pipe.postIdentity(pipe.manager.activeNotifications.single().notification)
            pipe.reopen()
            pipe.configuration = pipe.configuration.copy(deviceZone = ZoneId.of("America/Los_Angeles"))
            assertEquals(0, pipe.coordinator.reconcile().posted)
            val recomputed = pipe.ledger.findByAlias(original.alias)!!
            assertEquals(Instant.parse("2026-11-18T17:00:00Z"), recomputed.plannedDue)
            assertEquals(ReminderDeliveryState.POSTED, recomputed.state)
            assertEquals(original, recomputed.postIdentity)
            pipe.time.value = recomputed.plannedDue
            assertEquals(0, pipe.coordinator.reconcile().posted)
            assertEquals(original, pipe.ledger.findByAlias(original.alias)!!.postIdentity)
            assertEquals(ReminderActionResult.APPLIED, pipe.coordinator.dismiss(original))
        } finally { pipe.close() }
    }

    @Test fun restartAfterNotifyBeforeSqlAcknowledgementUsesExactActivePostProof() = runBlocking {
        val pipe = Pipeline(Provider(origin.plusSeconds(600), origin.plusSeconds(4200), false, 10), Time(origin))
        try {
            val loaded = pipe.source.loadDueWindow(InstantBounds(origin.minusSeconds(3600), origin.plusSeconds(3600)), pipe.configuration)
                as ReminderSourceResult.Ready
            val candidate = loaded.snapshot.candidates.single()
            pipe.ledger.reconcile(loaded.snapshot, setOf(calendarId), emptyMap(), origin)
            val claim = pipe.ledger.claimDue(origin, 1).single()
            val interruptedPost = claim.record.postIdentity!!
            assertEquals(ReminderPostResult.POSTED, pipe.notifications.post(candidate, interruptedPost))
            // Simulate process loss at the real cross-boundary uncertainty point: the notification
            // exists, while SQL still durably says POSTING. Never call acknowledgement here.
            assertEquals(ReminderDeliveryState.POSTING, pipe.ledger.findByAlias(interruptedPost.alias)!!.state)
            pipe.reopen()
            pipe.coordinator.reconcile()
            val recovered = pipe.ledger.findByAlias(interruptedPost.alias)!!
            assertEquals(ReminderDeliveryState.POSTED, recovered.state)
            assertEquals(interruptedPost, recovered.postIdentity)
            assertTrue(pipe.ledger.interruptedDeliveries().isEmpty())
            assertEquals(interruptedPost.token, pipe.manager.activeNotifications.single().notification.extras
                .getString(ReminderNotificationFactory.EXTRA_POST_TOKEN))
            assertEquals(0, pipe.coordinator.reconcile().posted)
        } finally { pipe.close() }
    }

    @Test fun yearAheadEventWithReminderDueSoonSchedulesAndPostsThroughActualSource() = runBlocking {
        val due = origin.plusSeconds(600)
        val start = due.plus(Duration.ofDays(365))
        val minutes = 365 * 24 * 60
        val pipe = Pipeline(Provider(start, start.plusSeconds(3600), false, minutes), Time(origin))
        try {
            assertEquals(0, pipe.coordinator.reconcile().posted)
            assertEquals(due, pipe.nextAlarm().at)
            assertTrue(pipe.provider.instanceRanges.any { (from, _) -> from > origin.plus(Duration.ofDays(364)).toEpochMilli() })
            pipe.time.value = due
            assertEquals(1, pipe.coordinator.onAlarm(pipe.nextAlarm().version).posted)
            val post = pipe.postIdentity(pipe.manager.activeNotifications.single().notification)
            assertEquals(minutes, pipe.ledger.findByAlias(post.alias)!!.key.resolvedMinutesBefore)
            assertEquals(0, pipe.coordinator.reconcile().posted)
        } finally { pipe.close() }
    }
}
