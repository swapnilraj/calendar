package dev.swapnilraj.calendar

import android.Manifest
import android.provider.CalendarContract
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.reminders.SavedReminderSettings
import dev.swapnilraj.calendar.ui.settings.*
import dev.swapnilraj.calendar.ui.calendars.CalendarViewOption
import java.time.*
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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
import org.robolectric.shadows.ShadowContentResolver

/** Actual MainActivity → physical multi-day pointer → durable native editor → CalendarWriter
 * transaction. The dedicated SQLite provider models native predicates and one-off Instances,
 * not account synchronization or device gesture/launcher behavior. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w393dp-h851dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeMultiDayEditJourneyTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val title = "Synthetic multi-day appointment"

    @Test fun threeDayCrossColumnMoveWaitsForSaveThenChangesOnlyTrueStartAndEnd() = fixture(CalendarViewOption.THREE_DAY) { f ->
        gesture(f, resize = false)
        assertDraft(f, f.date.plusDays(1), "09:00", f.date.plusDays(1), "10:00")
        save(f)
        val start = f.start.plus(Duration.ofDays(1))
        assertSaved(f, start, start.plusSeconds(3600))
        compose.runOnUiThread { f.activity.recreate() }
        compose.waitForIdle()
        assertEquals(1, f.provider.batchCount)
    }

    @Test fun weekCrossColumnLowerHandleRetainsFullStartAndExtendsTrueElapsedDuration() = fixture(CalendarViewOption.WEEK) { f ->
        gesture(f, resize = true)
        assertDraft(f, f.date, "09:00", f.date.plusDays(1), "10:30")
        save(f)
        val end = f.date.plusDays(1).atTime(10, 30).toInstant(ZoneOffset.UTC)
        assertSaved(f, f.start, end)
        assertEquals(Duration.ofHours(25).plusMinutes(30), Duration.between(f.start, end))
    }

    @Test fun durableMovedDraftRestoredAfterCalendarNamespaceReplacementCannotWriteOrRebase() = fixture(CalendarViewOption.THREE_DAY) { f ->
        gesture(f, resize = false)
        assertDraft(f, f.date.plusDays(1), "09:00", f.date.plusDays(1), "10:00")
        val retained = runBlocking { f.app.calendarLocalStore.list() }.single()
        f.provider.setCalendar("_sync_id", "replacement-calendar-B")
        compose.runOnUiThread { f.activity.recreate() }
        shown("Start a new draft")
        compose.onNodeWithText("Edit event").assertExists(); compose.onNodeWithText("New event").assertDoesNotExist()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Delete event").assertDoesNotExist()
        assertEquals(retained.payload, runBlocking { f.app.calendarLocalStore.load(retained.id) }!!.payload)
        assertEquals(f.beforeEvents, f.provider.snapshot("events"))
        assertEquals(f.beforeReminders, f.provider.snapshot("reminders"))
        assertEquals(0, f.provider.batchCount)
        assertEquals(0, journalCount(f))
    }

    private fun gesture(f: Fixture, resize: Boolean) {
        shown(title)
        val columns = compose.onNodeWithTag("multiday_columns")
        columns.performSemanticsAction(SemanticsActions.ScrollBy) { it(-10000f, 0f) }
        val node = compose.onNodeWithText(title)
        val viewport = compose.onNodeWithTag("multiday_time_scroll")
        node.performScrollTo()
        node.assertIsDisplayed()
        val body = viewport.fetchSemanticsNode().boundsInRoot
        val bounds = node.fetchSemanticsNode().boundsInRoot
        assertTrue("The entire event must be visible before choosing move versus resize", bounds.top >= body.top && bounds.bottom <= body.bottom && bounds.height > 48f)
        val target = compose.onNodeWithTag("multiday_grid_${f.date.plusDays(1)}").fetchSemanticsNode().boundsInRoot
        // Fixture pins 64dp/hour and mdpi; semantics day bounds are viewport-clipped.
        val hourPixels = 64f
        compose.mainClock.autoAdvance = false
        node.performTouchInput { down(if (resize) Offset(bounds.width / 2, bounds.height - 3) else Offset(bounds.width / 2, bounds.height / 4)) }
        compose.mainClock.advanceTimeBy(700)
        compose.waitUntil(15_000) {
            compose.mainClock.advanceTimeBy(16)
            compose.onAllNodesWithText(f.app.getString(R.string.timed_grid_original)).fetchSemanticsNodes().isNotEmpty()
        }
        node.performTouchInput { moveBy(Offset(target.center.x - bounds.center.x, if (resize) hourPixels / 2f else 0f)) }
        compose.mainClock.advanceTimeBy(48); compose.waitForIdle()
        node.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(64)
        compose.mainClock.autoAdvance = true
        shown("Edit event")
        assertEquals(0, f.provider.batchCount)
        assertEquals(0, journalCount(f))
        assertEquals(f.beforeEvents, f.provider.snapshot("events"))
    }
    private fun assertDraft(f: Fixture, startDate: LocalDate, startTime: String, endDate: LocalDate, endTime: String) {
        compose.waitUntil(15_000) { runBlocking { f.app.calendarLocalStore.list() }.size == 1 }
        val raw = JSONObject(runBlocking { f.app.calendarLocalStore.list() }.single().payload)
        assertEquals(startDate.toString(), raw.getString("startDate")); assertEquals(startTime, raw.getString("startTime"))
        assertEquals(endDate.toString(), raw.getString("endDate")); assertEquals(endTime, raw.getString("endTime"))
        assertEquals("Synthetic opaque notes", raw.getString("description"))
        assertEquals(0, f.provider.batchCount)
    }
    private fun save(f: Fixture) {
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(15_000) {
            compose.waitForIdle()
            f.app.calendarLocalStore.readableDatabase.rawQuery("SELECT COUNT(*) FROM mutation_journal WHERE phase='VERIFIED'", null).use {
                it.moveToFirst(); it.getInt(0) == 1
            }
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Save").fetchSemanticsNodes().isEmpty() }
    }
    private fun assertSaved(f: Fixture, start: Instant, end: Instant) {
        assertEquals(1, f.provider.batchCount); assertEquals(1, journalCount(f))
        val after = f.provider.snapshot("events").single()
        assertEquals(start.toEpochMilli().toString(), after["dtstart"])
        assertEquals(end.toEpochMilli().toString(), after["dtend"])
        assertEquals(f.beforeEvents.single().filterKeys { it !in setOf("dtstart", "dtend") }, after.filterKeys { it !in setOf("dtstart", "dtend") })
        assertEquals(f.beforeReminders, f.provider.snapshot("reminders"))
        assertTrue(f.provider.snapshot("attendees").isEmpty())
        val ref = EventRef(CalendarId(2), 7, start, end)
        val detail = runBlocking { f.app.calendarReader.readDetails(ref) } as CalendarReadState.Ready
        assertEquals(ref, detail.value.occurrence.ref)
    }
    private fun journalCount(f: Fixture) = f.app.calendarLocalStore.readableDatabase.rawQuery("SELECT COUNT(*) FROM mutation_journal", null).use {
        it.moveToFirst(); it.getInt(0)
    }
    private fun shown(text: String) = compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private class Fixture(val app: CalendarApp, val provider: NativeRecurringCreateJourneyProvider,
        val activity: ActivityController<MainActivity>, val date: LocalDate, val start: Instant) {
        val beforeEvents = provider.snapshot("events")
        val beforeReminders = provider.snapshot("reminders")
    }
    private fun fixture(view: CalendarViewOption, block: (Fixture) -> Unit) {
        val app = RuntimeEnvironment.getApplication() as CalendarApp
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        val today = LocalDate.now(ZoneOffset.UTC)
        val date = if (view == CalendarViewOption.WEEK) today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) else today
        val start = date.atTime(9, 0).toInstant(ZoneOffset.UTC)
        val provider = NativeRecurringCreateJourneyProvider(allowUpdates = true)
        provider.seedOneOff(start, start.plusSeconds(3600), title)
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        runBlocking {
            app.settings.saveReminderSettings(app.settings.readReminderSettings(), SavedReminderSettings())
            app.reminderRuntime.onResume().join()
            app.settings.setHourHeight(64f)
            app.settings.setDisplaySettings(CalendarSettingsOptions(defaultView = view, weatherVisible = false,
                firstWeekday = FirstWeekdayOption.MONDAY, displayZone = DisplayZoneOption.Fixed(ZoneId.of("UTC"))))
            assertTrue(app.settings.setCalendarView(view.name))
            val metadata = app.calendarReader.readCalendars() as CalendarReadState.Ready
            assertTrue(app.settings.setCalendarSelection(CalendarSelection.Explicit(setOf(CalendarId(2))), metadata.value))
        }
        lateinit var activity: ActivityController<MainActivity>
        compose.runOnUiThread { activity = Robolectric.buildActivity(MainActivity::class.java).setup().visible() }
        try { block(Fixture(app, provider, activity, date, start)) }
        finally { compose.mainClock.autoAdvance = true; compose.runOnUiThread { activity.pause().stop().destroy() }; provider.db.close() }
    }
}
