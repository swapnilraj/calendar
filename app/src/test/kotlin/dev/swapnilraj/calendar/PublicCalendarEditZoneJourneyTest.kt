package dev.swapnilraj.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.provider.CalendarContract
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import dev.swapnilraj.calendar.android.navigation.*
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.reminders.SavedReminderSettings
import dev.swapnilraj.calendar.ui.events.*
import dev.swapnilraj.calendar.ui.settings.*
import java.time.*
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
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

/** Actual exported EDIT → private review → ordinary editor → native SQLite writer/receipt4. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w393dp-h851dp-notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PublicCalendarEditZoneJourneyTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var start = Instant.parse("2028-06-15T09:00:00Z")
    private var zone = ZoneId.of("Asia/Tokyo")
    private val title = "Synthetic imported missing-zone event"
    private var durationSeconds = 3600L

    @Test fun sameRendererZoneNeedsExplicitApplyAndAnotherSaveAfterWriteGrantThenWritesZoneOnly() = fixture(write = false) { f ->
        val before = f.provider.snapshot("events").single(); val reminders = f.provider.snapshot("reminders")
        val editor = enter(f, moved = false)
        assertNoWrite(f); assertTrue(records(f).none { it.id.startsWith("incoming-edit:") })
        apply(f)
        val native = records(f).single { it.id.startsWith("incoming-edit:") }; val doc = OrdinaryDraftDocument.decode(native)
        assertTrue(doc.zoneAuthored); assertFalse(doc.hasRequest)
        assertEquals("", JSONObject(doc.sourceBaseline).getString("zone")); assertEquals("Asia/Tokyo", JSONObject(doc.sourceBaseline).getString(OrdinaryEditDisplayProof.KEY))
        assertNoWrite(f)
        save(); shown(f.app.getString(R.string.phone_event_permission)); assertNoWrite(f)
        assertFalse(OrdinaryDraftDocument.decode(requireNotNull(runBlocking { f.app.calendarLocalStore.load(native.id) })).hasRequest)
        shadowOf(f.app).grantPermissions(Manifest.permission.WRITE_CALENDAR)
        compose.runOnUiThread { editor.pause().stop().restart().start().resume() }
        shown("Save"); compose.waitForIdle(); assertNoWrite(f)
        save(); finished(editor)
        val after = f.provider.snapshot("events").single()
        assertEquals(zone.id, after["eventTimezone"]); assertEquals(zone.id, after["eventEndTimezone"])
        val changed = setOf("eventTimezone", "eventEndTimezone")
        assertEquals(before.filterKeys { it !in changed }, after.filterKeys { it !in changed })
        assertEquals(reminders, f.provider.snapshot("reminders")); assertReceipt4(f, doc.operationId, time = false)
        assertEquals(1, f.provider.batchCount); assertNull(runBlocking { f.app.calendarLocalStore.load(native.id) })
        assertTrue(isCalendarEditHandoffReceipt(records(f).single())); assertFilters(f)
    }

    @Test fun proposedTimeAndSameRendererZoneSurviveRecreationAndDeviceTravelThenWriteBothDeltas() = fixture { f ->
        val before = f.provider.snapshot("events").single(); val editor = enter(f, moved = true); apply(f)
        val native = records(f).single { it.id.startsWith("incoming-edit:") }; val doc = OrdinaryDraftDocument.decode(native)
        assertTrue(doc.zoneAuthored); assertEquals("19:00", doc.fields.startTime); assertNoWrite(f)
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        compose.runOnUiThread { editor.recreate() }
        shown(f.app.getString(R.string.events_editor_edit)); assertNoWrite(f)
        val reopened = OrdinaryDraftDocument.decode(requireNotNull(runBlocking { f.app.calendarLocalStore.load(native.id) }))
        assertEquals(doc.fields, reopened.fields); assertTrue(reopened.zoneAuthored); assertEquals(doc.generation, reopened.generation)
        save(); finished(editor)
        val after = f.provider.snapshot("events").single()
        assertEquals(start.plusSeconds(3600).toEpochMilli().toString(), after["dtstart"])
        assertEquals(start.plusSeconds(7200).toEpochMilli().toString(), after["dtend"])
        assertEquals(zone.id, after["eventTimezone"]); assertEquals(zone.id, after["eventEndTimezone"])
        val changed = setOf("dtstart", "dtend", "eventTimezone", "eventEndTimezone")
        assertEquals(before.filterKeys { it !in changed }, after.filterKeys { it !in changed })
        assertReceipt4(f, doc.operationId, time = true); assertEquals(1, f.provider.batchCount); assertFilters(f)
    }

    @Test fun committedAuthoredZoneRecoversAfterReadLossAndSnapshotPurgeWithoutReauthoringOrSecondBatch() = fixture { f ->
        val editor = enter(f, moved = false); apply(f)
        f.provider.failReadsAfterCommit = true; save(); shown(f.app.getString(R.string.phone_event_uncertain))
        val frozen = records(f).single { it.id.startsWith("incoming-edit:") }; val doc = OrdinaryDraftDocument.decode(frozen)
        assertTrue(doc.hasRequest); assertTrue(doc.zoneAuthored); assertReceipt4(f, doc.operationId, time = false)
        assertEquals(1, f.provider.batchCount)
        f.app.calendarLocalStore.writableDatabase.delete("edit_snapshots", null, null)
        shadowOf(f.app).denyPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        compose.runOnUiThread { editor.recreate() }
        shown(f.app.getString(R.string.phone_event_check_save)); compose.onNodeWithText("Save").assertIsNotEnabled()
        assertEquals(frozen, runBlocking { f.app.calendarLocalStore.load(frozen.id) }); assertEquals(1, f.provider.batchCount)
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
        f.provider.failReadsAfterCommit = false; shadowOf(f.app).grantPermissions(Manifest.permission.READ_CALENDAR)
        compose.runOnUiThread { editor.pause().stop().restart().start().resume() }
        val check = hasText(f.app.getString(R.string.phone_event_check_save)) and hasClickAction() and isEnabled()
        shown(f.app.getString(R.string.phone_event_check_save)); compose.onNode(check).performScrollTo().performClick(); finished(editor)
        assertNull(runBlocking { f.app.calendarLocalStore.load(frozen.id) }); assertEquals(1, f.provider.batchCount)
        assertEquals(zone.id, f.provider.value("eventTimezone")); assertTrue(isCalendarEditHandoffReceipt(records(f).single())); assertFilters(f)
    }

    @Test fun absentZoneFoldProposalWithSameWallClocksButDifferentOffsetsStaysInReview() {
        start = Instant.parse("2028-11-05T05:15:00Z"); durationSeconds = 1800; zone = ZoneId.of("America/New_York")
        fixture { f ->
            enter(f, moved = true, includeZone = false, applyAvailable = false)
            val review = CalendarEditReviewDocument.decode(records(f).single())
            val original = requireNotNull(review.original); val suggested = CalendarEditSuggestions.overlay(original, review.request)
            assertEquals(original.startTime, suggested.startTime); assertEquals(original.endTime, suggested.endTime)
            assertEquals("-04:00", original.startOffset); assertEquals("-05:00", suggested.startOffset)
            compose.onAllNodesWithTag("incoming_edit_apply").assertCountEquals(0)
            assertTrue(records(f).none { it.id.startsWith("incoming-edit:") }); assertNoWrite(f); assertFilters(f)
        }
    }

    private fun enter(f: Fixture, moved: Boolean, includeZone: Boolean = true, applyAvailable: Boolean = true): ActivityController<CalendarEditReviewActivity> {
        val intent = Intent(Intent.ACTION_EDIT, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, 7))
        if (includeZone) intent.putExtra(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
        if (moved) intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start.plusSeconds(3600).toEpochMilli())
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start.plusSeconds(durationSeconds + 3600).toEpochMilli())
        val public = host(f, CalendarEntryActivity::class.java, intent)
        shown(f.app.getString(R.string.calendar_entry_continue)); assertTrue(records(f).isEmpty()); assertNoWrite(f)
        compose.onNodeWithText(f.app.getString(R.string.calendar_entry_continue)).performClick()
        var next: Intent? = null
        compose.waitUntil(15_000) { compose.waitForIdle(); if (next == null) next = shadowOf(public.get()).nextStartedActivity; next != null }
        assertEquals(CalendarEditReviewActivity::class.java.name, next!!.component!!.className)
        stop(public)
        return host(f, CalendarEditReviewActivity::class.java, requireNotNull(next)).also {
            shown(f.app.getString(if (applyAvailable) R.string.incoming_edit_review_apply else R.string.incoming_edit_review_zone_authoring))
        }
    }
    private fun apply(f: Fixture) {
        compose.onNodeWithTag("incoming_edit_apply").performScrollTo().performClick()
        shown(f.app.getString(R.string.events_editor_edit))
        compose.waitUntil(15_000) { compose.onAllNodes(hasContentDescription(f.app.getString(R.string.phone_event_title)) and hasSetTextAction() and isEnabled()).fetchSemanticsNodes().size == 1 }
    }
    private fun save() = compose.onNode(hasText("Save") and hasClickAction() and isEnabled()).performClick()
    private fun shown(value: String) = compose.waitUntil(15_000) { compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }
    private fun finished(activity: ActivityController<out ComponentActivity>) = compose.waitUntil(15_000) { compose.waitForIdle(); activity.get().isFinishing }
    private fun records(f: Fixture) = runBlocking { f.app.calendarLocalStore.list() }
    private fun assertReceipt4(f: Fixture, operationId: String, time: Boolean) {
        val receipt = JSONObject(requireNotNull(f.app.calendarLocalStore.operation(operationId)?.resultRef))
        assertEquals(4, receipt.getInt("version")); assertEquals("ORDINARY_EDIT_RECEIPT_V4", receipt.getString("feature"))
        val command = receipt.getJSONObject("command")
        assertEquals(zone.id, command.getString("zone")); assertEquals(time, command.has("time")); assertFalse(command.has("zoneAuthored"))
    }
    private fun assertNoWrite(f: Fixture) {
        assertEquals(0, f.provider.batchCount)
        f.app.calendarLocalStore.readableDatabase.rawQuery("SELECT COUNT(*) FROM mutation_journal", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
    }
    private fun assertFilters(f: Fixture) {
        assertEquals(CalendarSelection.Explicit(emptySet()), runBlocking { f.app.settings.calendarSelection.first() })
    }
    private fun <A: ComponentActivity> host(f: Fixture, type: Class<A>, intent: Intent) = Robolectric.buildActivity(type, intent).also {
        compose.runOnUiThread { it.setup().visible() }; f.activities += it
    }
    private fun stop(activity: ActivityController<out ComponentActivity>) { compose.runOnUiThread { activity.pause().stop().destroy() } }
    private class Fixture(val app: CalendarApp, val provider: NativeRecurringCreateJourneyProvider) {
        val activities = mutableListOf<ActivityController<out ComponentActivity>>()
    }
    private fun fixture(write: Boolean = true, block: (Fixture) -> Unit) {
        val oldZone = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
        val app = RuntimeEnvironment.getApplication() as CalendarApp
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)
        if (write) shadowOf(app).grantPermissions(Manifest.permission.WRITE_CALENDAR) else shadowOf(app).denyPermissions(Manifest.permission.WRITE_CALENDAR)
        val provider = NativeRecurringCreateJourneyProvider(allowUpdates = true)
        provider.seedOneOff(start, start.plusSeconds(durationSeconds), title)
        provider.db.update("events", ContentValues().apply { putNull("eventTimezone"); putNull("eventEndTimezone") }, "_id=7", null)
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        runBlocking {
            app.settings.saveReminderSettings(app.settings.readReminderSettings(), SavedReminderSettings()); app.reminderRuntime.onResume().join()
            app.settings.setDisplaySettings(CalendarSettingsOptions(weatherVisible = false, theme = SettingsTheme.LIGHT, displayZone = DisplayZoneOption.Fixed(zone)))
            val calendars = app.calendarReader.readCalendars() as CalendarReadState.Ready
            assertTrue(app.settings.setDefaultCalendar(null, calendars.value)); assertTrue(app.settings.setCalendarSelection(CalendarSelection.Explicit(emptySet()), calendars.value))
        }
        val f = Fixture(app, provider)
        try { block(f) } finally {
            f.activities.asReversed().forEach { if (!it.get().isDestroyed) stop(it) }
            provider.db.close(); TimeZone.setDefault(oldZone)
        }
    }
}
