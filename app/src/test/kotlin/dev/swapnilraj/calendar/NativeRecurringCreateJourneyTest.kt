package dev.swapnilraj.calendar

import android.Manifest
import android.provider.CalendarContract
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.calendar.CalendarLocalStore
import dev.swapnilraj.calendar.data.calendar.CalendarWriter
import dev.swapnilraj.calendar.data.reminders.SavedReminderSettings
import dev.swapnilraj.calendar.ui.settings.CalendarSettingsOptions
import dev.swapnilraj.calendar.ui.settings.DisplayZoneOption
import java.time.ZoneId
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
import java.time.Instant

/** Real MainActivity/editor/CalendarWriter/local SQL journal/provider transaction. The native SQL
 * fixture projects the first instance only; reopening SQL is not a process-kill or Google sync test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w393dp-h851dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeRecurringCreateJourneyTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val title = "Synthetic daily authored appointment"

    @Test fun dailyCountReviewIsNonMutatingUntilConfirmAndSavedMasterSurvivesRecreateAndReadOnlyRecovery() = fixture { f ->
        enterDaily(f)
        review()
        assertUnsubmitted(f)
        compose.onNode(hasText("Cancel") and hasAnyAncestor(hasAnyDescendant(hasText("Review recurring event")))).performClick()
        assertUnsubmitted(f)
        review()
        assertUnsubmitted(f)
        confirm()
        val (operationId, ref) = awaitSaved(f)
        assertEquals("FREQ=DAILY;INTERVAL=1;WKST=MO;COUNT=4", f.provider.value("rrule"))
        assertEquals("PT3600S", f.provider.value("duration"))
        assertNull(f.provider.value("dtend"))
        assertEquals("0", f.provider.value("allDay"))
        assertEquals(title, f.provider.value("title"))
        assertEquals(ref.instanceStart.toEpochMilli().toString(), f.provider.value("dtstart"))
        assertEquals(ref.instanceStart.plusSeconds(3600), ref.instanceEnd)
        assertEquals(RecurrenceRef(ref.eventId, ref.instanceStart), ref.recurrence)
        val details = runBlocking { f.app.calendarReader.readDetails(ref) } as CalendarReadState.Ready
        assertEquals(ref, details.value.occurrence.ref)
        assertEquals(title, details.value.occurrence.title)
        assertEquals(1, f.provider.count("reminders"))
        compose.runOnUiThread { f.activity.recreate() }
        compose.waitForIdle()
        assertEquals(1, f.provider.batchCount)
        assertEquals(1, f.provider.count("events"))
        shadowOf(f.app).denyPermissions(Manifest.permission.WRITE_CALENDAR)
        val reopened = CalendarLocalStore(f.app, f.app.calendarLocalStore.databaseName)
        try {
            val writer = CalendarWriter(f.app.contentResolver, f.app.calendarReader, reopened,
                { f.app.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED })
            assertEquals(MutationResult.LocalSaved(ref), runBlocking { writer.recover(operationId) })
            assertEquals("VERIFIED", reopened.operation(operationId)!!.phase)
        } finally { reopened.close() }
        assertEquals(1, f.provider.batchCount)
    }

    @Test fun permissionGrantAndRecreationRetainRepeatChoicesButRequireFreshSaveAndReviewConfirmation() = fixture(writeAllowed = false) { f ->
        enterDaily(f)
        review()
        assertUnsubmitted(f)
        confirm()
        try { shown(f.app.getString(R.string.phone_event_permission)) } catch (failure: Throwable) {
            val categories = listOf(R.string.phone_event_pending_permission, R.string.phone_event_invalid,
                R.string.phone_event_unavailable, R.string.events_calendar_reselection, R.string.phone_event_uncertain)
                .associateWith { compose.onAllNodesWithText(f.app.getString(it)).fetchSemanticsNodes().size }
            val submitted = runBlocking { f.app.calendarLocalStore.list() }.map { JSONObject(it.payload).optBoolean("submitted") }
            throw AssertionError("Expected unfrozen permission state; visible category counts=$categories submitted=$submitted", failure)
        }
        assertUnsubmitted(f)
        shadowOf(f.app).grantPermissions(Manifest.permission.WRITE_CALENDAR)
        compose.runOnUiThread { f.activity.pause().stop().restart().start().resume() }
        compose.waitForIdle()
        assertUnsubmitted(f)
        compose.runOnUiThread { f.activity.recreate() }
        shown("Save")
        assertUnsubmitted(f)
        compose.onAllNodesWithText("Create recurring event").assertCountEquals(0)
        compose.onNodeWithText("Daily").performScrollTo().performClick()
        compose.onNode(hasText("Daily") and isSelectable()).assertIsSelected()
        compose.onNode(hasSetTextAction() and hasText("Occurrences (1–10000)")).performScrollTo().assertTextContains("4")
        compose.onNodeWithText("Done").performClick()
        review()
        assertUnsubmitted(f)
        confirm()
        awaitSaved(f)
        assertEquals(1, f.provider.batchCount)
        assertEquals("FREQ=DAILY;INTERVAL=1;WKST=MO;COUNT=4", f.provider.value("rrule"))
    }

    private fun enterDaily(f: Fixture) {
        shown("Create event")
        compose.onNodeWithText("Create event").performClick()
        shown("Save")
        compose.onNode(hasSetTextAction() and hasContentDescription("Title")).performScrollTo().performTextReplacement(title)
        compose.onNodeWithText(f.app.getString(R.string.phone_event_choose_calendar)).performScrollTo().performClick()
        compose.onNode(hasText("Synthetic calendar") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).performClick()
        compose.onNodeWithText("Does not repeat").performScrollTo().performClick()
        compose.onNodeWithText("Daily").performScrollTo().performClick()
        compose.onNodeWithText("After a number of occurrences").performScrollTo().performClick()
        val countField = hasSetTextAction() and hasText("Occurrences (1–10000)")
        compose.onNode(countField).performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(countField and isFocused()).fetchSemanticsNodes().size == 1 }
        compose.onNode(countField).performTextReplacement("4")
        compose.waitUntil(15_000) { compose.onAllNodes(countField and SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("4"))).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("Done").performClick()
        assertUnsubmitted(f)
    }
    private fun review() {
        compose.onNodeWithText("Save").performClick()
        shown("Review recurring event")
        compose.onNodeWithText("After 4 occurrences").assertExists()
    }
    private fun confirm() { compose.onNodeWithText("Create recurring event").performClick() }
    private fun shown(text: String) = compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun assertUnsubmitted(f: Fixture) {
        assertEquals(0, f.provider.batchCount)
        assertEquals(0, f.provider.count("events"))
        assertEquals(0, journalCount(f))
    }
    private fun journalCount(f: Fixture) = f.app.calendarLocalStore.readableDatabase.rawQuery("SELECT COUNT(*) FROM mutation_journal", null).use {
        it.moveToFirst(); it.getInt(0)
    }
    private fun awaitSaved(f: Fixture): Pair<String, EventRef> {
        compose.waitUntil(15_000) {
            compose.waitForIdle()
            f.app.calendarLocalStore.readableDatabase.rawQuery("SELECT COUNT(*) FROM mutation_journal WHERE phase='VERIFIED'", null).use {
                it.moveToFirst(); it.getInt(0) == 1
            }
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Save").fetchSemanticsNodes().isEmpty() }
        assertEquals(1, f.provider.batchCount)
        assertEquals(1, journalCount(f))
        return f.app.calendarLocalStore.readableDatabase.rawQuery("SELECT id,result_ref FROM mutation_journal", null).use {
            it.moveToFirst()
            val proof = JSONObject(it.getString(1)); assertEquals(3, proof.getInt("version"))
            val first = proof.getJSONObject("firstRef")
            it.getString(0) to EventRef(CalendarId(first.getLong("calendar")), first.getLong("event"),
                Instant.parse(first.getString("start")), Instant.parse(first.getString("end")),
                RecurrenceRef(first.getLong("master"), Instant.parse(first.getString("original"))))
        }
    }
    private class Fixture(val app: CalendarApp, val provider: NativeRecurringCreateJourneyProvider, val activity: ActivityController<MainActivity>)
    private fun fixture(writeAllowed: Boolean = true, block: (Fixture) -> Unit) {
        val app = RuntimeEnvironment.getApplication() as CalendarApp
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)
        if (writeAllowed) shadowOf(app).grantPermissions(Manifest.permission.WRITE_CALENDAR) else shadowOf(app).denyPermissions(Manifest.permission.WRITE_CALENDAR)
        val provider = NativeRecurringCreateJourneyProvider()
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        runBlocking {
            app.settings.saveReminderSettings(app.settings.readReminderSettings(), SavedReminderSettings())
            app.reminderRuntime.onResume().join()
            app.settings.setDisplaySettings(CalendarSettingsOptions(weatherVisible = false, defaultDurationMinutes = 60, displayZone = DisplayZoneOption.Fixed(ZoneId.of("UTC"))))
            assertTrue(app.settings.setCalendarView("DAY"))
            val metadata = app.calendarReader.readCalendars() as CalendarReadState.Ready
            assertTrue(app.settings.setDefaultCalendar(null, metadata.value))
            app.calendarReader.refresh()
            assertTrue(app.settings.setCalendarSelection(CalendarSelection.Explicit(setOf(CalendarId(2))), metadata.value))
        }
        lateinit var activity: ActivityController<MainActivity>
        compose.runOnUiThread { activity = Robolectric.buildActivity(MainActivity::class.java).setup().visible() }
        try { block(Fixture(app, provider, activity)) }
        finally { compose.runOnUiThread { activity.pause().stop().destroy() }; provider.db.close() }
    }
}
