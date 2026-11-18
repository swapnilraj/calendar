package dev.swapnilraj.calendar

import android.Manifest
import android.content.ContentValues
import android.provider.CalendarContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.calendar.CalendarLocalStore
import dev.swapnilraj.calendar.data.calendar.CalendarMeetingWriter
import dev.swapnilraj.calendar.data.reminders.SavedReminderSettings
import dev.swapnilraj.calendar.ui.events.meetings.MeetingResponseDraftCodec
import dev.swapnilraj.calendar.ui.settings.CalendarSettingsOptions
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.first
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

/** Feature integration against a declared synthetic clone profile, not Google/provider certification.
 * Real MainActivity, durable drafts, permission callbacks, native exception batch and SQL recovery. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w393dp-h851dp-mdpi", application = SyntheticCloneCalendarApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeGeneratedRsvpJourneyTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val title = "Synthetic invitation 10"
    private val kind = "MEETING_SERIES_V1_THIS_RESPOND_GENERATED"

    @Test fun mainGeneratedThisResponseCreatesOneExceptionAndRefreshClosesVerifiedDraft() = fixture { f ->
        openResponse(f)
        choose("No"); save(); awaitSaved(f)
        val ref = assertOneException(f, "2")
        assertEquals("VERIFIED", operation(f).second)
        val refreshed = runBlocking { f.app.calendarMeetings.loadForEdit(ref) }
        assertTrue(refreshed.toString(), refreshed is MeetingReadResult.Ready)
        assertEquals(ref, (refreshed as MeetingReadResult.Ready).value.details.occurrence.ref)
        assertEquals(2, refreshed.value.details.selfAttendeeStatus)
        assertEquals(1, f.provider.batchCount)
    }

    @Test fun writeGrantAndResumeRetainChoiceButRequireExplicitSaveForGeneratedThis() = fixture(writeAllowed = false) { f ->
        openResponse(f)
        choose("Yes"); save()
        shown(f.app.getString(R.string.meeting_response_permission))
        val raw = JSONObject(responseRecord(f).payload)
        assertEquals("THIS", raw.getString("scope")); assertEquals("YES", raw.getString("response")); assertFalse(raw.has("frozen"))
        assertEquals(0, f.provider.batchCount); assertEquals(f.before.getValue("events"), f.provider.snapshot("events"))
        shadowOf(f.app).grantPermissions(Manifest.permission.WRITE_CALENDAR)
        compose.runOnUiThread { f.activity.pause().stop().restart().start().resume() }
        compose.waitForIdle()
        assertEquals(0, f.provider.batchCount)
        compose.onNodeWithText("Yes").assertIsSelected()
        save(); awaitSaved(f)
        assertOneException(f, "1"); assertEquals("VERIFIED", operation(f).second)
    }

    @Test fun committedReadLossRecreatesThenRecoversSourceFreeFromReopenedSqlWithoutSecondBatch() = fixture { f ->
        openResponse(f)
        choose("Maybe")
        f.provider.revokeReadAfterCommit = true
        save()
        compose.waitUntil(15_000) { f.provider.batchCount == 1 && responseRecordOrNull(f)?.let { JSONObject(it.payload).has("frozen") } == true }
        val draft = responseRecord(f); val launch = requireNotNull(MeetingResponseDraftCodec.describe(draft))
        assertTrue(launch.needsRecovery)
        val id = JSONObject(draft.payload).getString("operationId")
        compose.waitUntil(15_000) { journalPhase(f, id) == "COMMITTED" }
        assertOneException(f, "4")
        // Process-style UI restoration cannot borrow the old controller's editable source.
        compose.runOnUiThread { f.activity.recreate() }
        assertEquals(1, f.provider.batchCount)
        f.app.calendarLocalStore.writableDatabase.execSQL("DELETE FROM edit_snapshots")
        shadowOf(f.app).denyPermissions(Manifest.permission.WRITE_CALENDAR)
        f.provider.revokeReadAfterCommit = false
        shadowOf(f.app).grantPermissions(Manifest.permission.READ_CALENDAR)
        compose.runOnUiThread { f.activity.pause().stop().restart().start().resume() }
        // Independently reopen the actual journal and verify without an editable snapshot or WRITE.
        val reopened = CalendarLocalStore(f.app, f.app.calendarLocalStore.databaseName)
        try {
            val writer = CalendarMeetingWriter(f.app.contentResolver, f.app.calendarReader, reopened,
                { permission -> f.app.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED },
                providerClonePolicy = SyntheticCloneProviderPolicy)
            val recovered = runBlocking { writer.recover(id) }
            assertTrue(recovered.toString(), recovered is MeetingMutationResult.LocalSaved)
            val ref = (recovered as MeetingMutationResult.LocalSaved).selected
            assertEquals(f.ref.copy(eventId = ref.eventId), ref)
        } finally { reopened.close() }
        compose.onNodeWithContentDescription("Open navigation menu").performClick()
        compose.onNodeWithText("Drafts").performClick()
        shown("Invitation response draft")
        compose.onNodeWithText("Recover operation").performClick()
        shown("Respond to invitation")
        compose.onNodeWithText("Save response on this device").assertIsNotEnabled()
        compose.onNodeWithText("Review original operation").performScrollTo().performClick()
        awaitSaved(f)
        assertEquals("VERIFIED", journalPhase(f, id)); assertEquals(1, f.provider.batchCount)
        assertOneException(f, "4")
        assertEquals(0, f.app.calendarLocalStore.readableDatabase.rawQuery("SELECT COUNT(*) FROM edit_snapshots", null).use { it.moveToFirst(); it.getInt(0) })
    }

    private fun openResponse(f: Fixture) {
        shown(title)
        compose.onNodeWithText(title).performScrollTo().performClick()
        shown("Respond to invitation")
        compose.onNodeWithText("Respond to invitation").performScrollTo().performClick()
        shown("This occurrence")
        compose.onNodeWithText("This occurrence").assertIsEnabled()
        val source = runBlocking { f.app.calendarMeetings.loadForEdit(f.ref) }
        assertTrue(source.toString(), source is MeetingReadResult.Ready)
        assertEquals(f.ref, (source as MeetingReadResult.Ready).value.details.occurrence.ref)
        assertEquals(AccessDecision.ALLOWED, source.value.scopes.getValue(RecurrenceScope.THIS).respond)
        assertEquals(0, f.provider.batchCount)
    }
    private fun choose(value: String) {
        compose.onNodeWithText("This occurrence").performScrollTo().performClick()
        compose.onNodeWithText(value).performScrollTo().performClick()
    }
    private fun save() = compose.onNodeWithText("Save response on this device").performScrollTo().performClick()
    private fun shown(value: String) = compose.waitUntil(15_000) { compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }
    private fun responseRecordOrNull(f: Fixture) = runBlocking { f.app.calendarLocalStore.list() }.singleOrNull { MeetingResponseDraftCodec.describe(it) != null }
    private fun responseRecord(f: Fixture) = requireNotNull(responseRecordOrNull(f))
    private fun awaitSaved(f: Fixture) {
        compose.waitUntil(15_000) { responseRecordOrNull(f) == null }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Review original operation").fetchSemanticsNodes().isEmpty() && compose.onAllNodesWithText("Save response on this device").fetchSemanticsNodes().isEmpty() }
    }
    private fun operation(f: Fixture) = f.app.calendarLocalStore.readableDatabase.rawQuery("SELECT id,phase FROM mutation_journal WHERE kind=?", arrayOf(kind)).use {
        assertEquals(1, it.count); assertTrue(it.moveToFirst()); it.getString(0) to it.getString(1)
    }
    private fun journalPhase(f: Fixture, id: String) = f.app.calendarLocalStore.readableDatabase.rawQuery("SELECT phase FROM mutation_journal WHERE id=?", arrayOf(id)).use {
        assertTrue(it.moveToFirst()); it.getString(0)
    }
    private fun assertOneException(f: Fixture, status: String): EventRef {
        val oldIds = f.before.getValue("events").map { it.getValue("_id") }.toSet()
        val events = f.provider.snapshot("events")
        assertEquals(f.before.getValue("events"), events.filter { it.getValue("_id") in oldIds })
        val new = events.single { it.getValue("_id") !in oldIds }
        val id = new.getValue("_id")!!.toLong(); assertTrue(id > 0 && id !in oldIds.mapNotNull { it?.toLong() })
        assertEquals("10", new["original_id"]); assertEquals("master-A", new["original_sync_id"])
        assertEquals(f.ref.recurrence!!.originalStart.toEpochMilli().toString(), new["originalInstanceTime"])
        assertEquals(f.ref.instanceStart.toEpochMilli().toString(), new["dtstart"]); assertEquals(f.ref.instanceEnd.toEpochMilli().toString(), new["dtend"])
        assertEquals("0", new["originalAllDay"]); assertEquals(status, new["selfAttendeeStatus"]); assertEquals("1", new["eventStatus"])
        assertNull(new["rrule"]); assertNull(new["_sync_id"])
        for (table in listOf("attendees", "reminders", "extendedproperties")) {
            val old = f.before.getValue(table); val ids = old.map { it["_id"] }.toSet()
            assertEquals(old, f.provider.snapshot(table).filter { it["_id"] in ids })
        }
        val copied = f.provider.snapshot("attendees").filter { it["event_id"] == id.toString() }
        assertEquals(status, copied.single { it["attendeeEmail"] == "actor@example.invalid" }["attendeeStatus"])
        assertEquals("1", copied.single { it["attendeeEmail"] == "other@example.invalid" }["attendeeStatus"])
        assertEquals(f.before.getValue("instances"), f.provider.snapshot("instances").filter { it["event_id"] != id.toString() })
        assertEquals(1, f.provider.native.correctionValues.size)
        return f.ref.copy(eventId = id)
    }
    private class Fixture(val app: CalendarApp, val provider: NativeGeneratedRsvpJourneyProvider,
        val activity: ActivityController<MainActivity>, val ref: EventRef) {
        val before = listOf("events", "attendees", "reminders", "extendedproperties", "instances").associateWith(provider::snapshot)
    }
    private fun fixture(writeAllowed: Boolean = true, block: (Fixture) -> Unit) {
        val app = RuntimeEnvironment.getApplication() as CalendarApp
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)
        if (writeAllowed) shadowOf(app).grantPermissions(Manifest.permission.WRITE_CALENDAR) else shadowOf(app).denyPermissions(Manifest.permission.WRITE_CALENDAR)
        val selected = Instant.now().truncatedTo(ChronoUnit.MINUTES)
        val provider = NativeGeneratedRsvpJourneyProvider(selected.minusSeconds(86400))
        // Two additional actual instances prove the chosen original identity is the only new row.
        for (delta in listOf(-86400L, 86400L)) provider.db.insertOrThrow("instances", null, ContentValues().apply {
            put("event_id", 10); put("begin", selected.plusSeconds(delta).toEpochMilli()); put("end", selected.plusSeconds(delta + 3600).toEpochMilli())
        })
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        runBlocking {
            app.settings.saveReminderSettings(app.settings.readReminderSettings(), SavedReminderSettings()); app.reminderRuntime.onResume().join()
            app.settings.setDisplaySettings(CalendarSettingsOptions(weatherVisible = false)); app.settings.setCalendarView("DAY")
            val metadata = app.calendarReader.observeCalendars().first { it is CalendarReadState.Ready } as CalendarReadState.Ready
            assertTrue(app.settings.setCalendarSelection(CalendarSelection.Explicit(setOf(CalendarId(2))), metadata.value))
        }
        lateinit var activity: ActivityController<MainActivity>
        compose.runOnUiThread { activity = Robolectric.buildActivity(MainActivity::class.java).setup().visible() }
        val f = Fixture(app, provider, activity, EventRef(CalendarId(2), 10, selected, selected.plusSeconds(3600), RecurrenceRef(10, selected)))
        try { block(f) } finally {
            compose.runOnUiThread { activity.pause().stop().destroy() }
            provider.db.close()
        }
    }
}
