package dev.swapnilraj.calendar

import android.Manifest
import android.provider.CalendarContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.calendar.CalendarLocalStore
import dev.swapnilraj.calendar.data.calendar.CalendarSeriesReader
import dev.swapnilraj.calendar.data.calendar.CalendarSeriesWriter
import dev.swapnilraj.calendar.data.reminders.SavedReminderSettings
import dev.swapnilraj.calendar.ui.calendars.CalendarViewOption
import dev.swapnilraj.calendar.ui.settings.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.UUID
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

/** Actual MainActivity → admitted native scope/editor → real series writer/journal/SQLite batch.
 * Dedicated plain owned source has no exceptions, date sets or opaque clone payload. Synthetic
 * DAILY/WEEKLY expansion is explicitly bounded; SQL reopen is not OS process-kill or Google proof. */
@RunWith(RobolectricTestRunner::class)
@Config(application = SyntheticCloneCalendarApp::class, sdk = [35], qualifiers = "en-rUS-w393dp-h851dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeFollowingJourneyTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val title = "Synthetic following appointment"
    private val countRule = "COUNT=8;FREQ=DAILY;INTERVAL=1"

    @Test fun metadataFollowingEditRequiresReviewThenPreservesPrefixAndCreatesIndependentCountTail() = fixture { f ->
        openFollowing()
        replace("Title", "Synthetic independent tail")
        review(f)
        compose.onNode(hasText("Cancel") and hasAnyAncestor(hasAnyDescendant(hasText("Review this and following events")))).performClick()
        assertUnsubmitted(f)
        review(f); confirm()
        val selected = requireNotNull(saved(f).selected)
        assertTailIdentity(f, selected)
        val rows = f.provider.snapshot("events")
        val prefix = rows.first(); val tail = rows.last()
        assertEquals(countRule.replace("COUNT=8", "COUNT=2"), prefix["rrule"])
        assertEquals(countRule.replace("COUNT=8", "COUNT=6"), tail["rrule"])
        assertEquals(title, prefix["title"]); assertEquals("Synthetic independent tail", tail["title"])
        assertEquals(f.selectedStart.toEpochMilli().toString(), tail["dtstart"])
        assertEquals("PT3600S", tail["duration"]); assertNull(tail["dtend"])
        assertPrefixRetained(f)
        val reminders = f.provider.snapshot("reminders")
        assertEquals(f.reminders.single(), reminders.first())
        assertEquals(selected.eventId.toString(), reminders.last()["event_id"])
        assertNotEquals(reminders.first()["_id"], reminders.last()["_id"])
        assertEquals(reminders.first().filterKeys { it !in setOf("_id", "event_id") }, reminders.last().filterKeys { it !in setOf("_id", "event_id") })
        assertTrue(f.provider.snapshot("attendees").isEmpty())
    }

    @Test fun temporalFollowingEditKeepsExactImportedUntilOnTailAndOriginalPrefixDetails() = fixture(until = true) { f ->
        openFollowing()
        replace("Start time (HH:MM)", clock(f.hour + 1))
        compose.onNodeWithText("Set elapsed duration").performScrollTo().performClick()
        replace("Elapsed hours", "1"); replace("Minutes (0–59)", "30")
        review(f); confirm()
        val selected = requireNotNull(saved(f).selected)
        assertTailIdentity(f, selected)
        assertEquals(f.selectedStart.plusSeconds(3600), selected.instanceStart)
        assertEquals(Duration.ofMinutes(90), Duration.between(selected.instanceStart, selected.instanceEnd))
        val prefix = f.provider.snapshot("events").first(); val tail = f.provider.snapshot("events").last()
        assertEquals(f.rawRule, tail["rrule"])
        val prefixUntil = f.selectedStart.minusSeconds(86400).atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'"))
        assertEquals("WKST=SU;FREQ=DAILY;UNTIL=$prefixUntil", prefix["rrule"])
        assertEquals("PT3600S", prefix["duration"]); assertEquals("PT5400S", tail["duration"])
        assertPrefixRetained(f)
    }

    @Test fun frozenWeeklyCountFollowingCommitRecreatesAndRecoversKnownTailAfterSqlReopenWithoutReplayOrWritePermission() = fixture { f ->
        openFollowing()
        compose.onNodeWithText("Weekly").performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Weekly") and isSelected()).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("After a number of occurrences").performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("After a number of occurrences") and isSelected()).fetchSemanticsNodes().size == 1 }
        replace("Occurrences (1–10000)", "3")
        review(f)
        f.provider.failReadsAfterCommit = true
        confirm(); shown(f.app.getString(R.string.events_editor_uncertain))
        assertEquals("COMMITTED", operation(f).second)
        val original = runBlocking { f.app.calendarLocalStore.list() }.single()
        val frozen = JSONObject(original.payload).getJSONObject("frozen").toString()
        assertTrue(JSONObject(original.payload).getBoolean("submitted"))
        assertEquals(1, f.provider.batchCount)
        compose.runOnUiThread { f.activity.recreate() }
        shown(f.app.getString(R.string.events_editor_recover))
        runBlocking { f.app.settings.setDisplaySettings(CalendarSettingsOptions(weatherVisible = false, displayZone = DisplayZoneOption.Fixed(ZoneId.of("America/New_York")))) }
        compose.waitForIdle()
        assertEquals(frozen, JSONObject(runBlocking { f.app.calendarLocalStore.load(original.id) }!!.payload).getJSONObject("frozen").toString())
        compose.onNodeWithText("Save").performScrollTo().assertIsNotEnabled()
        assertEquals(1, f.provider.batchCount)
        f.provider.failReadsAfterCommit = false
        shadowOf(f.app).denyPermissions(Manifest.permission.WRITE_CALENDAR)
        // Reopen the actual SQL journal and construct the real reader/writer against that connection.
        val reopened = CalendarLocalStore(f.app, f.app.calendarLocalStore.databaseName)
        try {
            val permission: (String) -> Boolean = { f.app.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
            val source = CalendarSeriesReader(f.app.contentResolver, f.app.calendarReader, reopened, permission)
            val writer = CalendarSeriesWriter(f.app.contentResolver, source, reopened, permission)
            val result = runBlocking { writer.recover(operation(f).first) } as SeriesMutationResult.LocalSaved
            assertTailIdentity(f, requireNotNull(result.selected))
            assertEquals("VERIFIED", reopened.operation(operation(f).first)!!.phase)
        } finally { reopened.close() }
        compose.onNodeWithText(f.app.getString(R.string.events_editor_recover)).performScrollTo().performClick()
        val selected = requireNotNull(saved(f).selected)
        val tail = f.provider.snapshot("events").last()
        assertEquals(selected.eventId.toString(), tail["_id"])
        assertTrue(tail["rrule"]!!.contains("FREQ=WEEKLY")); assertTrue(tail["rrule"]!!.contains("COUNT=3"))
        assertEquals(countRule.replace("COUNT=8", "COUNT=2"), f.provider.snapshot("events").first()["rrule"])
        assertPrefixRetained(f)
        assertEquals(1, f.provider.batchCount)
        assertNull(shadowOf(f.activity.get()).lastRequestedPermission)
    }

    private fun openFollowing() {
        shown(title); compose.onNodeWithText(title).performClick()
        val edit = hasContentDescription("Edit event") and hasClickAction() and isEnabled()
        compose.waitUntil(15_000) { compose.onAllNodes(edit).fetchSemanticsNodes().size == 1 }
        compose.onNode(edit).performClick() // Fixed phone details toolbar; no inline scrolling action.
        shown("This and following occurrences")
        compose.onNodeWithText("This and following occurrences").assertIsEnabled().performClick()
        shown("Edit recurring event"); shown("Selected and following event times")
    }
    private fun replace(label: String, value: String) {
        val field = hasSetTextAction() and hasText(label)
        compose.waitUntil(15_000) { compose.onAllNodes(field and isEnabled()).fetchSemanticsNodes().size == 1 }
        compose.onNode(field).performScrollTo()
        compose.waitForIdle()
        // Focus can bring a conditional COUNT field into view and replace its semantics node.
        // Complete that real focus transition before resolving it again for keyboard input.
        compose.onNode(field).performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(field and isFocused()).fetchSemanticsNodes().size == 1 }
        compose.waitForIdle()
        compose.onNode(field and isFocused()).performTextReplacement(value)
        compose.waitUntil(15_000) { compose.onAllNodes(field and hasText(value)).fetchSemanticsNodes().size == 1 }
    }
    private fun review(f: Fixture) {
        compose.onNodeWithText("Save").performScrollTo().performClick()
        shown("Review this and following events"); assertUnsubmitted(f)
    }
    private fun confirm() = compose.onNodeWithText("Confirm following changes").performClick()
    private fun shown(text: String) = compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun journalCount(f: Fixture) = f.app.calendarLocalStore.readableDatabase.rawQuery("SELECT COUNT(*) FROM mutation_journal", null).use { it.moveToFirst(); it.getInt(0) }
    private fun operation(f: Fixture) = f.app.calendarLocalStore.readableDatabase.rawQuery("SELECT id,phase FROM mutation_journal", null).use {
        assertTrue(it.moveToFirst()); val result = it.getString(0) to it.getString(1); assertFalse(it.moveToNext()); result
    }
    private fun assertUnsubmitted(f: Fixture) {
        assertEquals(0, f.provider.batchCount); assertEquals(0, journalCount(f))
        assertEquals(listOf(f.before), f.provider.snapshot("events")); assertEquals(f.reminders, f.provider.snapshot("reminders"))
    }
    private fun saved(f: Fixture): SeriesMutationResult.LocalSaved {
        compose.waitUntil(20_000) { compose.waitForIdle(); journalCount(f) == 1 && operation(f).second == "VERIFIED" }
        compose.waitUntil(15_000) {
            compose.waitForIdle()
            runBlocking { f.app.calendarLocalStore.list() }.isEmpty() &&
                compose.onAllNodesWithText("Edit recurring event").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText(f.app.getString(R.string.events_editor_recover)).fetchSemanticsNodes().isEmpty()
        }
        assertEquals(1, f.provider.batchCount)
        return runBlocking { f.app.calendarSeriesWriter.recover(operation(f).first) } as SeriesMutationResult.LocalSaved
    }
    private fun assertPrefixRetained(f: Fixture) {
        assertEquals(f.before.filterKeys { it != "rrule" }, f.provider.snapshot("events").first().filterKeys { it != "rrule" })
    }
    private fun assertTailIdentity(f: Fixture, selected: EventRef) {
        val rows = f.provider.snapshot("events"); assertEquals(2, rows.size)
        val prefix = rows.first(); val tail = rows.last()
        assertNotEquals(prefix["_id"], tail["_id"]); assertEquals(tail["_id"]!!.toLong(), selected.eventId)
        assertEquals(selected.eventId, selected.recurrence!!.masterEventId)
        assertEquals(selected.instanceStart, selected.recurrence!!.originalStart)
        assertEquals("prefix-public-uid", prefix["uid2445"]); assertEquals("prefix-sync", prefix["_sync_id"])
        assertNotEquals(prefix["uid2445"], tail["uid2445"])
        assertEquals(tail["uid2445"], UUID.fromString(tail["uid2445"]).toString()); assertNull(tail["_sync_id"])
        assertEquals(tail["_id"]!!.toLong(), JSONObject(f.app.calendarLocalStore.operation(operation(f).first)!!.resultRef!!).getLong("result"))
        val details = runBlocking { f.app.calendarReader.readDetails(selected) } as CalendarReadState.Ready
        assertEquals(selected, details.value.occurrence.ref)
    }
    private fun clock(hour: Int) = "%02d:00".format(java.util.Locale.ROOT, hour)
    private class Fixture(val app: CalendarApp, val provider: NativeFollowingJourneyProvider, val activity: ActivityController<MainActivity>,
        val hour: Int, val master: Instant, val rawRule: String) {
        val selectedStart = master.plusSeconds(2 * 86400)
        val before = provider.snapshot("events").single(); val reminders = provider.snapshot("reminders")
    }
    private fun fixture(until: Boolean = false, block: (Fixture) -> Unit) {
        val app = RuntimeEnvironment.getApplication() as CalendarApp
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        val today = LocalDate.now(ZoneOffset.UTC); val hour = LocalTime.now(ZoneOffset.UTC).hour.coerceIn(2, 20)
        val master = today.minusDays(2).atTime(hour, 0).toInstant(ZoneOffset.UTC)
        val raw = if (until) "WKST=SU;FREQ=DAILY;UNTIL=" + today.plusDays(10).atTime(hour, 0, 17).toInstant(ZoneOffset.UTC)
            .atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'")) else countRule
        val provider = NativeFollowingJourneyProvider(); provider.seed(master, title, raw)
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        runBlocking {
            app.settings.saveReminderSettings(app.settings.readReminderSettings(), SavedReminderSettings()); app.reminderRuntime.onResume().join()
            app.settings.setDisplaySettings(CalendarSettingsOptions(weatherVisible = false, displayZone = DisplayZoneOption.Fixed(ZoneId.of("UTC"))))
            assertTrue(app.settings.setCalendarView(CalendarViewOption.DAY.name))
            val metadata = app.calendarReader.readCalendars() as CalendarReadState.Ready
            assertTrue(app.settings.setCalendarSelection(CalendarSelection.Explicit(setOf(CalendarId(2))), metadata.value))
        }
        lateinit var activity: ActivityController<MainActivity>
        compose.runOnUiThread { activity = Robolectric.buildActivity(MainActivity::class.java).setup().visible() }
        try { block(Fixture(app, provider, activity, hour, master, raw)) }
        finally { compose.runOnUiThread { activity.pause().stop().destroy() }; provider.db.close() }
    }
}
