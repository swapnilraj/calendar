package dev.swapnilraj.calendar

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import dev.swapnilraj.calendar.android.reminders.*
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.CalendarPreferenceState
import dev.swapnilraj.calendar.data.calendar.CalendarReminderSource
import dev.swapnilraj.calendar.data.reminders.*
import dev.swapnilraj.calendar.ui.settings.CalendarSettingsOptions
import java.time.Instant
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

/** Actual drawer/settings workflow, DataStore, runtime/source/SQL and Android notifications.
 * All provider data is synthetic; no fake save/resolver callback or device-delivery claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w393dp-h851dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReminderSettingsJourneyTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun explicitReminderSelectionPostsThroughRealRuntimeWhileDisplayStaysNone() = fixture { f ->
        openSettings()
        field("reminder_calendars_enabled").performClick()
        field("reminder_calendar_3").performClick()
        compose.onNodeWithTag("reminder_calendars_save").performClick()
        compose.waitUntil(15_000) { f.manager.activeNotifications.any { it.tag?.startsWith("daylight://reminder/v1/") == true } }
        val saved = runBlocking { f.app.settings.readReminderSettings() }
        assertTrue(saved.enabled)
        assertEquals(mapOf(CalendarId(3) to f.identity), saved.expectedCalendarIdentities)
        val notification = f.manager.activeNotifications.single { it.tag?.startsWith("daylight://reminder/v1/") == true }.notification
        assertEquals("Synthetic settings reminder appointment", notification.extras.getString(Notification.EXTRA_TITLE))
        val post = requireNotNull(ReminderIntents.identity(shadowOf(notification.contentIntent).savedIntent)?.postIdentityOrNull())
        assertEquals(ReminderDeliveryState.POSTED, runBlocking { f.app.reminderLedger.findByAlias(post.alias) }!!.state)
        f.assertDisplayNoneAndNoWrites()
    }

    @Test fun permissionPageAndReadRevokeKeepPendingRawChoicesButScrubCalendarMetadata() = fixture { f ->
        openSettings()
        field("reminder_calendar_3").performClick()
        field("reminder_defaults_3").performClick()
        field("reminder_timed_3").performTextReplacement("unfinished")
        permissions()
        compose.onNodeWithText("Back to reminder calendars").performClick()
        field("reminder_timed_3").assert(hasText("unfinished"))
        permissions()
        f.resumeWithRead(false)
        compose.onNodeWithText("Back to reminder calendars").performClick()
        shownTag("reminder_calendar_sheet")
        compose.onNodeWithText("Synthetic reminder calendar").assertDoesNotExist()
        compose.onAllNodes(hasText("fixture@example.invalid", substring = true)).assertCountEquals(0)
        assertFalse(runBlocking { f.app.settings.readReminderSettings() }.enabled)
        f.resumeWithRead(true)
        field("reminder_timed_3").assert(hasText("unfinished"))
        field("reminder_calendar_3").assertIsOn()
        compose.onNodeWithTag("reminder_calendars_save").performClick()
        assertEquals(SavedReminderSettings(), runBlocking { f.app.settings.readReminderSettings() })
        f.assertDisplayNoneAndNoWrites()
    }

    @Test fun externalCasChangeLocksSaveAndExplicitReloadDiscardsRawAgainstFreshBase() = fixture { f ->
        openSettings()
        field("reminder_calendars_enabled").performClick()
        field("reminder_calendar_3").performClick()
        field("reminder_defaults_3").performClick()
        field("reminder_timed_3").performTextReplacement("17")
        val external = SavedReminderSettings(false, mapOf(CalendarId(3) to f.identity),
            mapOf(CalendarId(3) to ReminderDefaults(timedMinutesBefore = 33)))
        assertTrue(runBlocking { f.app.settings.saveReminderSettings(SavedReminderSettings(), external) } is ReminderPreferenceSave.Saved)
        shownTag("reminder_settings_reload")
        compose.onNodeWithTag("reminder_calendars_save").assertIsNotEnabled()
        field("reminder_timed_3").assert(hasText("17"))
        compose.onNodeWithTag("reminder_settings_reload").performClick()
        shownTag("reminder_calendar_sheet")
        field("reminder_calendars_enabled").assertIsOff()
        field("reminder_defaults_3").performClick()
        field("reminder_timed_3").assert(hasText("33"))
        compose.onNodeWithTag("reminder_calendars_save").assertIsEnabled().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("reminder_calendar_sheet").fetchSemanticsNodes().isEmpty() }
        assertEquals(external, runBlocking { f.app.settings.readReminderSettings() })
        f.assertDisplayNoneAndNoWrites()
    }

    @Test fun explicitGenericTestNotificationWorksWithoutEnablingCalendarReminders() = fixture { f ->
        openSettings()
        permissions()
        compose.onNodeWithText("Send test notification").performScrollTo().performClick()
        compose.waitUntil(15_000) { f.manager.activeNotifications.any { it.tag == ReminderTestNotification.TAG } }
        val notice = f.manager.activeNotifications.single { it.tag == ReminderTestNotification.TAG }.notification
        assertEquals(f.app.getString(R.string.reminder_test_title), notice.extras.getString(Notification.EXTRA_TITLE))
        assertFalse(notice.extras.toString().contains("Synthetic settings reminder appointment"))
        assertEquals(SavedReminderSettings(), runBlocking { f.app.settings.readReminderSettings() })
        assertTrue(f.manager.activeNotifications.none { it.tag?.startsWith("daylight://reminder/v1/") == true })
        f.assertDisplayNoneAndNoWrites()
    }

    private fun openSettings() {
        compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("Open navigation menu").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Open navigation menu").performClick()
        compose.onNodeWithText("Event reminders").performClick()
        shownTag("reminder_calendars_save")
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("reminder_calendar_3").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun permissions() {
        compose.onNodeWithTag("reminder_calendar_fields").performScrollToNode(hasText("Permissions and delivery setup"))
        compose.onNodeWithText("Permissions and delivery setup").performClick()
        shownText("Back to reminder calendars")
    }
    private fun field(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("reminder_calendar_fields").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }
    private fun shownTag(tag: String) = compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun shownText(text: String) = compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private inner class Fixture(val app: CalendarApp, val provider: ReminderSettingsJourneyProvider,
        val activity: ActivityController<MainActivity>, val identity: ReminderCalendarIdentity) {
        val manager = app.getSystemService(NotificationManager::class.java)
        fun resumeWithRead(granted: Boolean) {
            compose.runOnUiThread { activity.pause().stop() }
            if (granted) shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)
            else shadowOf(app).denyPermissions(Manifest.permission.READ_CALENDAR)
            compose.runOnUiThread { activity.restart().start().resume() }
            compose.waitForIdle()
        }
        fun assertDisplayNoneAndNoWrites() {
            val prefs = runBlocking { app.settings.calendarPreferences.first { it is CalendarPreferenceState.Ready } } as CalendarPreferenceState.Ready
            assertEquals(CalendarSelection.Explicit(emptySet()), prefs.value.selection)
            assertEquals(0, provider.writeAttempts)
        }
    }
    private fun fixture(block: (Fixture) -> Unit) {
        val app = RuntimeEnvironment.getApplication() as CalendarApp
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.POST_NOTIFICATIONS)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val provider = ReminderSettingsJourneyProvider(Instant.now().plusSeconds(598), "Synthetic settings reminder appointment")
        ShadowContentResolver.registerProviderInternal(android.provider.CalendarContract.AUTHORITY, provider)
        val identity = runBlocking {
            app.settings.saveReminderSettings(app.settings.readReminderSettings(), SavedReminderSettings())
            app.reminderRuntime.onResume().join()
            assertTrue(app.settings.setDisplaySettings(CalendarSettingsOptions(weatherVisible = false)))
            val metadata = app.calendarReader.observeCalendars().first { it is CalendarReadState.Ready } as CalendarReadState.Ready
            val info = metadata.value.single()
            // Seed a foreign prior known snapshot so this remains isolated in a combined suite.
            // Select None records current metadata once before launch, never after UI actions.
            val prior = info.copy(id = CalendarId(999), account = CalendarAccount("prior-fixture@example.invalid", "com.synthetic.prior"),
                owner = "prior-fixture@example.invalid", providerId = "prior-calendar-uid")
            assertTrue(app.settings.setCalendarSelection(CalendarSelection.Explicit(setOf(prior.id)), listOf(prior)))
            assertTrue(app.settings.setCalendarSelection(CalendarSelection.Explicit(emptySet()), metadata.value))
            requireNotNull(CalendarReminderSource.calendarIdentity(info))
        }
        lateinit var activity: ActivityController<MainActivity>
        compose.runOnUiThread { activity = Robolectric.buildActivity(MainActivity::class.java).setup().visible() }
        val f = Fixture(app, provider, activity, identity)
        try { block(f) }
        finally {
            compose.runOnUiThread { activity.pause().stop().destroy() }
            shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)
            runBlocking {
                app.settings.saveReminderSettings(app.settings.readReminderSettings(), SavedReminderSettings())
                app.reminderRuntime.onResume().join()
            }
            f.manager.cancelAll()
            provider.close()
        }
    }
}
