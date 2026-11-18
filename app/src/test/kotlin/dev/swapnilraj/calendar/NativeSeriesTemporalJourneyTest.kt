package dev.swapnilraj.calendar

import android.Manifest
import android.provider.CalendarContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.reminders.SavedReminderSettings
import dev.swapnilraj.calendar.ui.calendars.CalendarViewOption
import dev.swapnilraj.calendar.ui.settings.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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

/** Real MainActivity/native ALL editor → CalendarSeriesWriter → SQLite provider and journal.
 * DAILY/WEEKLY Instances are a bounded synthetic projection, not Android or Google expansion.
 * No visual snapshots depend on the application wall clock. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w393dp-h851dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeSeriesTemporalJourneyTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val title = "Synthetic entire-series appointment"
    private val countRaw = "COUNT=8;FREQ=DAILY;INTERVAL=1"

    @Test fun masterTimeReviewChangesTheRealMasterAndKeepsExactRawCountRuleAndOpaquePayload() = fixture { f ->
        openAll()
        replace("Start time (HH:MM)", clock(f.hour + 1))
        review(f)
        compose.onNode(hasText("Cancel") and hasAnyAncestor(hasAnyDescendant(hasText("Review entire-series changes")))).performClick()
        assertUnsubmitted(f)
        review(f); confirm()
        val result = saved(f)
        assertEquals(f.master.plusSeconds(3600).toEpochMilli().toString(), f.provider.value("dtstart"))
        assertEquals(countRaw, f.provider.value("rrule")); assertEquals("PT3600S", f.provider.value("duration")); assertNull(f.provider.value("dtend"))
        val selected = requireNotNull(result.selected)
        assertEquals(f.master.plusSeconds(2 * 86400 + 3600), selected.instanceStart)
        assertEquals(selected.instanceStart.plusSeconds(3600), selected.instanceEnd)
        assertNativeSelected(f, selected)
        preserve(f, setOf("dtstart"))
    }

    @Test fun explicitWeeklyAndCountChangeCanRemoveTheSelectedOrdinalWithoutReturningItsOldRef() = fixture { f ->
        openAll()
        compose.onNodeWithText("Weekly").performScrollTo().performClick()
        compose.onNodeWithText("After a number of occurrences").performScrollTo().performClick()
        replace("Occurrences (1–10000)", "1")
        review(f); confirm()
        val result = saved(f)
        assertNull(result.selected)
        val rule = f.provider.value("rrule")!!
        assertTrue(rule.contains("FREQ=WEEKLY")); assertTrue(rule.contains("COUNT=1"))
        assertEquals(f.master.toEpochMilli().toString(), f.provider.value("dtstart"))
        val request = CalendarRequest(CalendarDateRange(f.today.minusDays(2), f.today.plusDays(40)), ZoneId.of("UTC"), CalendarSelection.Explicit(setOf(CalendarId(2))))
        val snapshot = runBlocking { f.app.calendarReader.readSnapshot(request) } as CalendarReadState.Ready
        assertEquals(listOf(f.master), snapshot.value.occurrences.map { it.ref.instanceStart })
        preserve(f, setOf("rrule"))
    }

    @Test fun importedExactUntilSurvivesDurationEditAndRealReaderKeepsOverlappingOccurrencesDistinct() = fixture(until = true) { f ->
        openAll()
        compose.onNodeWithText("Set elapsed duration").performScrollTo().performClick()
        replace("Elapsed hours", "26")
        review(f); confirm()
        val selected = requireNotNull(saved(f).selected)
        assertEquals(f.rawRule, f.provider.value("rrule")); assertEquals("PT93600S", f.provider.value("duration")); assertNull(f.provider.value("dtend"))
        assertEquals(Duration.ofHours(26), Duration.between(selected.instanceStart, selected.instanceEnd))
        val request = CalendarRequest(CalendarDateRange(f.today, f.today.plusDays(2)), ZoneId.of("UTC"), CalendarSelection.Explicit(setOf(CalendarId(2))))
        val occurrences = (runBlocking { f.app.calendarReader.readSnapshot(request) } as CalendarReadState.Ready).value.occurrences
        val pair = occurrences.filter { it.ref.instanceStart in listOf(f.master.plusSeconds(86400), f.master.plusSeconds(2 * 86400)) }.sortedBy { it.ref.instanceStart }
        assertEquals(2, pair.size); assertEquals(1, pair.map { it.ref.eventId }.distinct().size)
        assertNotEquals(pair[0].ref, pair[1].ref)
        assertTrue(pair[0].ref.instanceEnd > pair[1].ref.instanceStart)
        pair.forEach { assertNativeSelected(f, it.ref) }
        preserve(f, setOf("duration"))
    }

    @Test fun namespaceReplacementAfterDurableTemporalDraftCannotDiscardReloadIntoTheNewCalendar() = fixture { f ->
        openAll(); replace("Start time (HH:MM)", clock(f.hour + 1))
        review(f)
        compose.onNode(hasText("Cancel") and hasAnyAncestor(hasAnyDescendant(hasText("Review entire-series changes")))).performClick()
        val original = draft(f)
        f.provider.setCalendar("_sync_id", "calendar-B")
        compose.runOnUiThread { f.activity.recreate() }
        shown("Discard draft and load latest")
        compose.onNodeWithText("Save").performScrollTo().assertIsNotEnabled()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val once = AtomicBoolean()
        f.provider.afterQuery = { path -> if (path == "events" && once.compareAndSet(false, true)) {
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) { "Synthetic fresh review read was not released" }
        } }
        try {
            compose.onNodeWithText("Discard draft and load latest").performScrollTo().performClick()
            compose.onNodeWithText("Discard draft").performClick()
            compose.waitUntil(5_000) { compose.waitForIdle(); entered.count == 0L }
            compose.onNodeWithText("Discard draft and load latest").assertIsNotEnabled()
        } finally { release.countDown(); f.provider.afterQuery = null }
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasText("Discard draft and load latest") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(original.payload, runBlocking { f.app.calendarLocalStore.load(original.id) }!!.payload)
        assertEquals(0, f.provider.batchCount); assertEquals(0, journalCount(f))
        compose.onNodeWithText("Save").performScrollTo().assertIsNotEnabled()
        assertEquals(f.before, f.provider.snapshot("events").single())
    }

    @Test fun knownCommittedWholeCommandStaysFrozenAcrossRecreationAndSettingsThenRecoversWithoutWriteOrReplay() = fixture { f ->
        openAll(); compose.onNodeWithText("Set elapsed duration").performScrollTo().performClick(); replace("Elapsed hours", "2")
        review(f); f.provider.failReadsAfterCommit = true; confirm()
        shown(f.app.getString(R.string.events_editor_uncertain))
        val original = draft(f); val raw = JSONObject(original.payload)
        assertTrue(raw.getBoolean("submitted")); val frozen = raw.getJSONObject("frozen").toString()
        assertEquals(1, f.provider.batchCount); assertEquals("COMMITTED", operation(f).second)
        compose.runOnUiThread { f.activity.recreate() }
        shown(f.app.getString(R.string.events_editor_recover))
        runBlocking { f.app.settings.setDisplaySettings(CalendarSettingsOptions(weatherVisible = false, displayZone = DisplayZoneOption.Fixed(ZoneId.of("America/New_York")))) }
        compose.waitForIdle()
        assertEquals(frozen, JSONObject(draft(f).payload).getJSONObject("frozen").toString())
        compose.onNodeWithText("Save").performScrollTo().assertIsNotEnabled()
        assertEquals(1, f.provider.batchCount)
        f.provider.failReadsAfterCommit = false
        shadowOf(f.app).denyPermissions(Manifest.permission.WRITE_CALENDAR)
        compose.onNodeWithText(f.app.getString(R.string.events_editor_recover)).performScrollTo().performClick()
        val selected = requireNotNull(saved(f).selected)
        assertEquals(Duration.ofHours(2), Duration.between(selected.instanceStart, selected.instanceEnd))
        assertEquals(1, f.provider.batchCount); assertNull(shadowOf(f.activity.get()).lastRequestedPermission)
    }

    private fun openAll() {
        shown(title); compose.onNodeWithText(title).performClick()
        shown("Edit event"); compose.onNodeWithText("Edit event").performScrollTo().performClick()
        shown("Entire series"); compose.onNodeWithText("Entire series").performClick()
        shown("Edit recurring event"); shown("Master start and duration")
    }
    private fun replace(label: String, value: String) {
        val field = hasSetTextAction() and hasText(label)
        compose.waitUntil(15_000) { compose.onAllNodes(field and isEnabled()).fetchSemanticsNodes().size == 1 }
        compose.onNode(field).performScrollTo()
        compose.waitForIdle()
        // Conditional repeat fields can be recomposed during bring-into-view. Resolve them again.
        compose.onNode(field).performTextReplacement(value)
    }
    private fun review(f: Fixture) {
        compose.onNodeWithText("Save").performScrollTo().performClick(); shown("Review entire-series changes")
        assertUnsubmitted(f)
    }
    private fun confirm() = compose.onNodeWithText("Save entire-series changes").performClick()
    private fun shown(text: String) = compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun draft(f: Fixture) = runBlocking { f.app.calendarLocalStore.list() }.single()
    private fun assertUnsubmitted(f: Fixture) {
        assertEquals(0, f.provider.batchCount); assertEquals(0, journalCount(f))
        assertEquals(f.before, f.provider.snapshot("events").single())
    }
    private fun journalCount(f: Fixture) = f.app.calendarLocalStore.readableDatabase.rawQuery("SELECT COUNT(*) FROM mutation_journal", null).use { it.moveToFirst(); it.getInt(0) }
    private fun operation(f: Fixture) = f.app.calendarLocalStore.readableDatabase.rawQuery("SELECT id,phase FROM mutation_journal", null).use {
        assertTrue(it.moveToFirst()); val value = it.getString(0) to it.getString(1); assertFalse(it.moveToNext()); value
    }
    private fun saved(f: Fixture): SeriesMutationResult.LocalSaved {
        compose.waitUntil(20_000) { compose.waitForIdle(); journalCount(f) == 1 && operation(f).second == "VERIFIED" }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Edit recurring event").fetchSemanticsNodes().isEmpty() }
        assertEquals(1, f.provider.batchCount)
        val result = runBlocking { f.app.calendarSeriesWriter.recover(operation(f).first) }
        assertTrue(result is SeriesMutationResult.LocalSaved); assertEquals(1, f.provider.batchCount)
        return result as SeriesMutationResult.LocalSaved
    }
    private fun assertNativeSelected(f: Fixture, ref: EventRef) {
        assertEquals(ref.instanceStart, ref.recurrence!!.originalStart)
        val details = runBlocking { f.app.calendarReader.readDetails(ref) } as CalendarReadState.Ready
        assertEquals(ref, details.value.occurrence.ref)
    }
    private fun preserve(f: Fixture, changed: Set<String>) {
        assertEquals(f.before.filterKeys { it !in changed }, f.provider.snapshot("events").single().filterKeys { it !in changed })
        assertEquals(f.reminders, f.provider.snapshot("reminders")); assertTrue(f.provider.snapshot("attendees").isEmpty())
    }
    private fun clock(hour: Int) = "%02d:00".format(java.util.Locale.ROOT, hour)
    private class Fixture(val app: CalendarApp, val provider: NativeSeriesTemporalJourneyProvider, val activity: ActivityController<MainActivity>,
        val today: LocalDate, val hour: Int, val master: Instant, val rawRule: String) {
        val before = provider.snapshot("events").single(); val reminders = provider.snapshot("reminders")
    }
    private fun fixture(until: Boolean = false, block: (Fixture) -> Unit) {
        val app = RuntimeEnvironment.getApplication() as CalendarApp
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        val today = LocalDate.now(ZoneOffset.UTC); val hour = LocalTime.now(ZoneOffset.UTC).hour.coerceIn(2, 20)
        val master = today.minusDays(2).atTime(hour, 0).toInstant(ZoneOffset.UTC)
        val raw = if (until) "FREQ=DAILY;UNTIL=" + today.plusDays(10).atTime(hour, 0, 17).toInstant(ZoneOffset.UTC)
            .atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'")) + ";WKST=SU" else countRaw
        val provider = NativeSeriesTemporalJourneyProvider(); provider.seed(master, title, raw)
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
        try { block(Fixture(app, provider, activity, today, hour, master, raw)) }
        finally { compose.runOnUiThread { activity.pause().stop().destroy() }; provider.db.close() }
    }
}
