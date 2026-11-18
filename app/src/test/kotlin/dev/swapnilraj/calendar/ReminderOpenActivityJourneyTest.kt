package dev.swapnilraj.calendar

import android.Manifest
import android.app.NotificationManager
import android.content.*
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import dev.swapnilraj.calendar.android.reminders.*
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.CalendarPreferenceState
import dev.swapnilraj.calendar.data.calendar.*
import dev.swapnilraj.calendar.data.reminders.*
import java.time.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowContentResolver

/** Actual Application -> source -> SQL -> coordinator -> Android notification PendingIntent ->
 * private Activity -> real source/reader -> native details. SQL runs production predicates.
 * Instances is an explicit single nonrecurring projection, not Google sync or recurrence expansion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w393dp-h851dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReminderOpenActivityJourneyTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val currentLink = "https://meet.google.com/aaa-bbbb-ccc"
    private val title = "Synthetic private reminder appointment"

    @Test fun realNotificationTapReopensSqlAndFreshNativeDetailsWithoutChangingDisplayNone() = fixture { f ->
        val notification = f.post()
        val intent = f.tap(notification.contentIntent)
        val post = requireNotNull(ReminderIntents.identity(intent)?.postIdentityOrNull())
        assertEquals(ComponentName(f.app, ReminderOpenActivity::class.java), intent.component)
        assertFalse(f.app.packageManager.getActivityInfo(intent.component!!, 0).exported)
        // Close/reopen the actual app SQL helper, not a fake in-memory ledger. This is database
        // restart evidence only; Application/process lifetime is deliberately not simulated.
        f.app.calendarLocalStore.close()
        CalendarLocalStore(f.app, "daylight-local.db").use { reopened ->
            assertEquals(post, runBlocking { SqliteReminderLedger(reopened).findByAlias(post.alias) }!!.postIdentity)
        }
        f.provider.setEvent("description", "Fresh synthetic meeting: $currentLink")
        f.open(intent)
        shown(title)
        // Capture only the stable title region of the real Activity; dates use its actual clock.
        compose.onNodeWithText(title).captureToImage().asAndroidBitmap()
            .captureRoboImage("src/test/screenshots/integration_reminder_notification_open.png")
        compose.onNodeWithText("Join meeting").performScrollTo().performClick()
        val opened = shadowOf(f.activity!!.get()).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, opened.action)
        assertEquals(currentLink, opened.dataString)
        assertEquals(post, runBlocking { f.app.reminderLedger.findByAlias(post.alias) }!!.postIdentity)
        assertTrue(f.provider.detailReads > 0)
        f.assertNone()
    }

    @Test fun stalePostTokenAndReplacementNamespaceDoNotReadPrivateEventDetails() = fixture { f ->
        val original = f.tap(f.post().contentIntent)
        val identity = requireNotNull(ReminderIntents.identity(original))
        val stale = ReminderIntents.intent(f.app, ReminderIntentTargets(ComponentName(f.app, ReminderOpenActivity::class.java),
            ComponentName(f.app, ReminderActionReceiver::class.java)), identity.copy(postToken = "stale-fixture-token"), ReminderIntents.ACTION_OPEN)
        f.provider.detailReads = 0
        f.open(stale)
        unavailable()
        compose.onNodeWithText(title).assertDoesNotExist()
        assertEquals(0, f.provider.detailReads)
        f.closeActivity()
        // Same numeric IDs, different known calendar UID and account namespace.
        f.provider.replaceNamespace()
        f.provider.detailReads = 0
        f.open(original)
        unavailable()
        compose.onNodeWithText(title).assertDoesNotExist()
        assertEquals(0, f.provider.detailReads)
        f.assertNone()
    }

    @Test fun newIntentAndStoppedNamespaceChangeRemovePreviouslyVisiblePrivateDetails() = fixture { f ->
        val original = f.tap(f.post().contentIntent)
        f.open(original)
        shown(title)
        val identity = requireNotNull(ReminderIntents.identity(original))
        val invalid = ReminderIntents.intent(f.app, ReminderIntentTargets(ComponentName(f.app, ReminderOpenActivity::class.java),
            ComponentName(f.app, ReminderActionReceiver::class.java)), identity.copy(postToken = "retired-fixture-token"), ReminderIntents.ACTION_OPEN)
        compose.runOnUiThread { f.activity!!.newIntent(invalid) }
        unavailable()
        compose.onNodeWithText(title).assertDoesNotExist()
        compose.runOnUiThread { f.activity!!.newIntent(original) }
        shown(title)
        compose.runOnUiThread { f.activity!!.pause().stop() }
        f.provider.replaceNamespace()
        f.provider.detailReads = 0
        compose.runOnUiThread { f.activity!!.restart().start().resume() }
        unavailable()
        compose.onNodeWithText(title).assertDoesNotExist()
        assertEquals(0, f.provider.detailReads)
        f.assertNone()
    }

    private fun shown(value: String) = compose.waitUntil(15_000) { compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }
    private fun unavailable() = shown(RuntimeEnvironment.getApplication().getString(R.string.events_unavailable))

    private inner class Fixture(val app: CalendarApp, val provider: Provider) {
        var activity: ActivityController<ReminderOpenActivity>? = null
        fun post(): android.app.Notification {
            runBlocking { app.reminderRuntime.onResume().join() }
            val current = app.getSystemService(NotificationManager::class.java).activeNotifications
            assertEquals("Actual runtime must post through NotificationManager", 1, current.size)
            return current.single().notification
        }
        fun tap(pending: android.app.PendingIntent): Intent {
            pending.send()
            return requireNotNull(shadowOf(app).nextStartedActivity)
        }
        fun open(intent: Intent) {
            compose.runOnUiThread { activity = Robolectric.buildActivity(ReminderOpenActivity::class.java, intent).setup().visible() }
        }
        fun closeActivity() {
            activity?.let { controller -> compose.runOnUiThread { controller.pause().stop().destroy() } }
            activity = null
        }
        fun assertNone() {
            val prefs = runBlocking { app.settings.calendarPreferences.first { it is CalendarPreferenceState.Ready } } as CalendarPreferenceState.Ready
            assertEquals(CalendarSelection.Explicit(emptySet()), prefs.value.selection)
        }
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val app = RuntimeEnvironment.getApplication() as CalendarApp
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.POST_NOTIFICATIONS)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val provider = Provider(Instant.now().minusSeconds(2).plusSeconds(600), title)
        ShadowContentResolver.registerProviderInternal(android.provider.CalendarContract.AUTHORITY, provider)
        val original = runBlocking { app.settings.readReminderSettings() }
        runBlocking {
            app.reminderRuntime.onResume().join()
            val calendars = (app.calendarReader.observeCalendars().first { it is CalendarReadState.Ready } as CalendarReadState.Ready).value
            val info = calendars.single()
            // Deliberately carry another fixture's known namespace, then model the user's current
            // Select None action with its metadata snapshot before any Activity can reconcile it.
            val prior = info.copy(id = CalendarId(999), account = CalendarAccount("prior-fixture@example.invalid", "com.synthetic.prior"),
                owner = "prior-fixture@example.invalid", providerId = "prior-calendar-uid")
            assertTrue(app.settings.setCalendarSelection(CalendarSelection.Explicit(setOf(prior.id)), listOf(prior)))
            assertTrue(app.settings.setCalendarSelection(CalendarSelection.Explicit(emptySet()), calendars))
            val pin = requireNotNull(CalendarReminderSource.calendarIdentity(info))
            assertTrue(app.settings.saveReminderSettings(original, SavedReminderSettings(true, mapOf(info.id to pin))) is ReminderPreferenceSave.Saved)
        }
        val fixture = Fixture(app, provider)
        try { block(fixture) }
        finally {
            fixture.closeActivity()
            runBlocking {
                app.settings.saveReminderSettings(app.settings.readReminderSettings(), SavedReminderSettings())
                app.reminderRuntime.onResume().join()
            }
            app.getSystemService(NotificationManager::class.java).cancelAll()
            provider.close()
        }
    }

    /** General projection support: actual known columns or SQL NULL for an unknown optional field.
     * _ID URI predicates, production selections, bind args and sorting execute in SQLite.
     */
    private class Provider(start: Instant, title: String) : ContentProvider() {
        private val db = SQLiteDatabase.create(null)
        private val columns = mutableMapOf<String, Set<String>>()
        @Volatile var detailReads = 0
        init {
            table("calendars", mapOf("_id" to 3L, "account_name" to "fixture@example.invalid", "account_type" to "com.google", "ownerAccount" to "fixture@example.invalid",
                "_sync_id" to "fixture-calendar-A", "calendar_access_level" to 700L, "visible" to 1L, "sync_events" to 1L,
                "calendar_displayName" to "Synthetic reminder calendar", "calendar_color" to 123L, "calendar_timezone" to "UTC"))
            table("events", mapOf("_id" to 7L, "calendar_id" to 3L, "dtstart" to start.toEpochMilli(), "dtend" to start.plusSeconds(3600).toEpochMilli(),
                "title" to title, "description" to "Old synthetic notes", "eventLocation" to "Synthetic room", "allDay" to 0L, "eventTimezone" to "UTC",
                "eventEndTimezone" to "UTC", "eventStatus" to 1L, "deleted" to 0L, "selfAttendeeStatus" to 0L, "displayColor" to 456L,
                "rrule" to null, "rdate" to null, "exrule" to null, "exdate" to null, "original_id" to null, "original_sync_id" to null,
                "originalInstanceTime" to null, "uid2445" to "fixture-event-uid", "isOrganizer" to 1L, "organizer" to "fixture@example.invalid",
                "hasAttendeeData" to 0L, "guestsCanSeeGuests" to 1L, "accessLevel" to 2L, "availability" to 0L))
            table("reminders", mapOf("_id" to 30L, "event_id" to 7L, "minutes" to 10L, "method" to 1L))
            table("attendees", mapOf("_id" to null, "event_id" to null, "attendeeEmail" to null)); db.delete("attendees", null, null)
        }
        private fun table(name: String, values: Map<String, Any?>) {
            columns[name] = values.keys
            val numeric = setOf("original_id", "originalInstanceTime", "event_id", "_id")
            db.execSQL("CREATE TABLE $name (" + values.entries.joinToString(",") { (key, value) -> "\"$key\" " + if (value is Number || key in numeric) "INTEGER" else "TEXT" } + ")")
            db.insertOrThrow(name, null, ContentValues().apply { values.forEach { (key, value) -> when (value) {
                null -> putNull(key); is Number -> put(key, value.toLong()); else -> put(key, value.toString())
            } } })
        }
        fun setEvent(key: String, value: String) { db.update("events", ContentValues().apply { put(key, value) }, "_id = 7", null) }
        fun replaceNamespace() { db.update("calendars", ContentValues().apply {
            put("_sync_id", "fixture-calendar-B"); put("account_name", "replacement@example.invalid"); put("ownerAccount", "replacement@example.invalid")
        }, "_id = 3", null) }
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
            val path = uri.pathSegments.first()
            val instance = path == "instances"
            val known = if (instance) columns.getValue("events") + setOf("event_id", "begin", "end") else columns.getValue(path)
            val projected = projection?.map { if (it in known) "\"$it\"" else "NULL AS \"$it\"" }?.toTypedArray()
            val table = if (instance) "(SELECT *, _id AS event_id, dtstart AS begin, dtend AS end FROM events)" else path
            val where = mutableListOf<String>(); val bind = mutableListOf<String>()
            if (instance) {
                where += "begin < ? AND (end > ? OR (begin = end AND begin >= ?)) AND deleted = 0 AND eventStatus != 2"
                bind += listOf(uri.pathSegments[3], uri.pathSegments[2], uri.pathSegments[2])
            } else uri.lastPathSegment?.toLongOrNull()?.let { id ->
                where += "_id = ?"; bind += "$id"
                if (path == "events") detailReads++
            }
            if (selection != null) { where += "($selection)"; bind += args.orEmpty() }
            return db.query(table, projected, where.joinToString(" AND ").takeIf { it.isNotEmpty() }, bind.toTypedArray(), null, null, sort)
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read-only fixture")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = error("Read-only fixture")
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = error("Read-only fixture")
        fun close() = db.close()
    }
}
