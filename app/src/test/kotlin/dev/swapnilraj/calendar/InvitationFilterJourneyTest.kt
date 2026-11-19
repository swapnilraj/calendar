package dev.swapnilraj.calendar

import android.Manifest
import android.content.ContentValues
import android.provider.CalendarContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.CalendarPreferenceState
import dev.swapnilraj.calendar.data.settings.CalendarSettingChange
import dev.swapnilraj.calendar.data.settings.CalendarSettingSave
import dev.swapnilraj.calendar.data.reminders.SavedReminderSettings
import dev.swapnilraj.calendar.ui.calendars.CalendarViewOption
import dev.swapnilraj.calendar.ui.settings.CalendarSettingsOptions
import dev.swapnilraj.calendar.ui.settings.DisplayZoneOption
import java.time.*
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
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

/** Actual application settings -> shared SQL-backed reader/search -> main views. Read-only
 * synthetic fixtures; no Google invitation, synchronization or Android task-stack evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w393dp-h851dp-notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InvitationFilterJourneyTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val pending = "Synthetic invitation pending"
    private val accepted = "Synthetic invitation accepted"
    private val uncertain = "Synthetic invitation uncertain"

    @Test fun actualSettingsChoiceFiltersMainPersistsOnRecreationAndKeepsProviderAndSelectionUnchanged() = fixture { f ->
        shown(pending); shown(accepted); shown(uncertain)
        compose.onNodeWithContentDescription("Open navigation menu").performClick()
        compose.onNodeWithText("Settings").performClick()
        shownTag("phone_settings_open_general")
        compose.onNodeWithTag("phone_settings_open_general").performClick()
        compose.onNodeWithTag("phone_settings_general_list").performScrollToNode(hasTestTag("phone_settings_row_invitations"))
        compose.onNodeWithTag("phone_settings_row_invitations").performClick()
        compose.onNodeWithText(f.app.getString(R.string.invitation_filter_pending)).performClick()
        shownTag("phone_settings_general_list")
        compose.onNodeWithTag("phone_settings_row_invitations")
            .assertTextContains(f.app.getString(R.string.invitation_filter_pending))
        compose.onNodeWithContentDescription("Back").performClick()
        shownTag("phone_settings_home_list")
        compose.onNodeWithContentDescription("Back").performClick()
        assertPendingMain(f)
        compose.runOnUiThread { f.activity.recreate() }
        assertPendingMain(f)
        // Default-ALL readers remain independent of the app's new display preference.
        io {
            val request = CalendarRequest(CalendarDateRange.day(f.today), ZoneOffset.UTC,
                CalendarSelection.Explicit(setOf(CalendarId(2))))
            val snapshot = f.app.calendarReader.readSnapshot(request) as CalendarReadState.Ready
            assertEquals(3, snapshot.value.occurrences.size)
        }
        assertLocalOnly(f)
    }

    @Test fun changingFilterWhileRealSearchIsOpenDropsAcceptedHitsAndRetainsUnknownCoverage() = fixture { f ->
        shown(pending)
        compose.onNodeWithContentDescription("Search events").performClick()
        shownTag("search_query")
        compose.onNodeWithTag("search_query").performTextInput("Synthetic invitation")
        shown(f.app.resources.getQuantityString(R.plurals.search_result_count, 3, 3))
        compose.onNodeWithTag("search_results").performScrollToNode(hasTestTag("search_hit_2_8"))
        compose.onNodeWithTag("search_hit_2_8").assertExists()
        io { assertEquals(CalendarSettingSave.Saved,
            f.app.settings.saveCalendarSetting(CalendarSettingChange.InvitationFilter(InvitationFilter.PENDING_ONLY))) }
        compose.onNodeWithTag("search_results").performScrollToIndex(0)
        shown(f.app.resources.getQuantityString(R.plurals.search_result_count, 1, 1))
        compose.onNode(hasText(f.app.getString(R.string.invitation_filter_pending)) and
            hasAnyAncestor(hasTestTag("search_sheet"))).assertExists()
        compose.onNode(hasText("could not be classified as awaiting a response", substring = true)).assertExists()
        compose.onNodeWithTag("search_results").performScrollToNode(hasTestTag("search_hit_2_7"))
        compose.onNodeWithTag("search_hit_2_7").assertExists()
        compose.onAllNodesWithTag("search_hit_2_8").assertCountEquals(0)
        compose.onAllNodesWithTag("search_hit_2_9").assertCountEquals(0)
        assertLocalOnly(f)
    }

    private fun assertPendingMain(f: Fixture) {
        shown(pending)
        shown(f.app.getString(R.string.invitation_filter_unknown_generic))
        compose.onAllNodesWithText(accepted).assertCountEquals(0)
        compose.onAllNodesWithText(uncertain).assertCountEquals(0)
    }

    private fun assertLocalOnly(f: Fixture) = io {
        val state = f.app.settings.calendarPreferences.first { it is CalendarPreferenceState.Ready } as CalendarPreferenceState.Ready
        assertEquals(InvitationFilter.PENDING_ONLY, state.value.options.invitationFilter)
        assertEquals(CalendarSelection.Explicit(setOf(CalendarId(2))), state.value.selection)
        assertEquals(SavedReminderSettings(), f.app.settings.readReminderSettings())
        assertEquals(f.events, f.provider.snapshot("events"))
        assertEquals(f.calendars, f.provider.snapshot("calendars"))
        assertEquals(0, f.provider.batchCount)
        assertTrue(f.app.calendarLocalStore.list().isEmpty())
        f.app.calendarLocalStore.readableDatabase.rawQuery("SELECT COUNT(*) FROM mutation_journal", null).use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
    }

    private fun shown(text: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
    private fun shownTag(tag: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }
    private fun <T> io(block: suspend () -> T): T {
        val result = AtomicReference<Result<T>?>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch { result.set(runCatching { block() }) }
        try {
            compose.waitUntil(15_000) { result.get() != null }
            return requireNotNull(result.get()).getOrThrow()
        } finally { scope.cancel() }
    }
    private class Fixture(val app: CalendarApp, val provider: NativeRecurringCreateJourneyProvider,
        val activity: ActivityController<MainActivity>, val today: LocalDate) {
        val events = provider.snapshot("events")
        val calendars = provider.snapshot("calendars")
    }
    private fun fixture(block: (Fixture) -> Unit) {
        val app = RuntimeEnvironment.getApplication() as CalendarApp
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)
        shadowOf(app).denyPermissions(Manifest.permission.WRITE_CALENDAR)
        val provider = NativeRecurringCreateJourneyProvider()
        val today = LocalDate.now(ZoneOffset.UTC)
        val start = today.atTime(12, 0).toInstant(ZoneOffset.UTC)
        listOf(Triple(7, pending, 3), Triple(8, accepted, 1), Triple(9, uncertain, 0)).forEach { (id, title, response) ->
            provider.db.insertOrThrow("events", null, ContentValues().apply {
                put("_id", id); put("calendar_id", 2); put("dtstart", start.toEpochMilli()); put("dtend", start.plusSeconds(3600).toEpochMilli())
                put("allDay", 0); put("eventTimezone", "UTC"); put("eventEndTimezone", "UTC"); put("title", title)
                put("isOrganizer", 0); put("organizer", "organizer@example.invalid"); put("selfAttendeeStatus", response)
                put("_sync_id", "synthetic-event-$id"); put("uid2445", "synthetic-uid-$id"); put("fixture_opaque", "opaque sentinel")
            })
        }
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        io {
            app.settings.saveReminderSettings(app.settings.readReminderSettings(), SavedReminderSettings())
            assertTrue(app.settings.setDisplaySettings(CalendarSettingsOptions(weatherVisible = false,
                displayZone = DisplayZoneOption.Fixed(ZoneId.of("UTC")))))
            assertTrue(app.settings.setCalendarView(CalendarViewOption.DAY.name))
            val metadata = app.calendarReader.readCalendars() as CalendarReadState.Ready
            assertTrue(app.settings.setCalendarSelection(CalendarSelection.Explicit(setOf(CalendarId(2))), metadata.value))
        }
        lateinit var activity: ActivityController<MainActivity>
        compose.runOnUiThread { activity = Robolectric.buildActivity(MainActivity::class.java).setup().visible() }
        try { block(Fixture(app, provider, activity, today)) }
        finally { compose.runOnUiThread { activity.pause().stop().destroy() }; provider.db.close() }
    }
}
