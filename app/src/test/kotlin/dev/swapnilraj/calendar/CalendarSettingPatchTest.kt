package dev.swapnilraj.calendar

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import dev.swapnilraj.calendar.data.Settings
import dev.swapnilraj.calendar.data.CalendarPreferenceState
import dev.swapnilraj.calendar.core.InvitationFilter
import dev.swapnilraj.calendar.data.settings.*
import dev.swapnilraj.calendar.ui.settings.*
import java.io.IOException
import java.time.ZoneId
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CalendarSettingPatchTest {
    private class Store(initial: Preferences) : DataStore<Preferences> {
        var stored = initial
        var updates = 0
        var failure: Exception? = null
        private val mutex = Mutex()
        override val data: Flow<Preferences> get() = flowOf(stored)
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = mutex.withLock {
            updates++
            failure?.let { throw it }
            yield()
            transform(stored).also { stored = it }
        }
    }
    private fun settings(store: Store) = Settings(RuntimeEnvironment.getApplication(), store)
    private fun raw(p: Preferences) = p.asMap().mapKeys { it.key.name }

    @Test fun concurrentIndependentChoicesPreserveUnknownMalformedAndNonDisplayKeys() = runBlocking {
        val initial = preferencesOf(
            stringPreferencesKey("calendar_view") to "WEEK",
            stringPreferencesKey("display_default_duration_minutes") to "future-format",
            stringPreferencesKey("display_zone_mode") to "FUTURE_ZONE",
            stringPreferencesKey("future_preference") to "opaque",
            stringSetPreferencesKey("selected_calendar_ids") to emptySet(),
            longPreferencesKey("default_calendar_id") to 9L,
            stringPreferencesKey("default_calendar_identity_v1") to "opaque-identity",
            floatPreferencesKey("hour_height") to 88f,
            stringPreferencesKey("reminder_policy_v1") to "opaque-policy")
        val store = Store(initial); val settings = settings(store)
        val results = listOf(async { settings.saveCalendarSetting(CalendarSettingChange.Theme(SettingsTheme.DARK)) },
            async { settings.saveCalendarSetting(CalendarSettingChange.WeatherVisible(false)) }).awaitAll()
        assertEquals(listOf(CalendarSettingSave.Saved, CalendarSettingSave.Saved), results)
        assertEquals(raw(initial) + mapOf("display_theme" to "DARK", "display_weather_visible" to false), raw(store.stored))
    }

    @Test fun compoundChoicesOnlyReplaceTheirOwnKeysAndLaterExplicitChoiceWins() = runBlocking {
        val store = Store(preferencesOf(stringPreferencesKey("unrelated") to "keep")); val settings = settings(store)
        assertEquals(CalendarSettingSave.Saved, settings.saveCalendarSetting(CalendarSettingChange.DisplayZone(DisplayZoneOption.Fixed(ZoneId.of("Asia/Kolkata")))))
        assertEquals(CalendarSettingSave.Saved, settings.saveCalendarSetting(CalendarSettingChange.TimedReminder(30)))
        assertEquals(CalendarSettingSave.Saved, settings.saveCalendarSetting(CalendarSettingChange.DisplayZone(DisplayZoneOption.Device)))
        assertEquals(CalendarSettingSave.Saved, settings.saveCalendarSetting(CalendarSettingChange.TimedReminder(null)))
        assertEquals(mapOf("unrelated" to "keep", "display_zone_mode" to "DEVICE", "display_timed_reminder_enabled" to false), raw(store.stored))
    }

    @Test fun invalidChoiceNeverEntersEditOrChangesStorage() = runBlocking {
        val initial = preferencesOf(stringPreferencesKey("calendar_view") to "MONTH")
        val store = Store(initial); val settings = settings(store)
        listOf(CalendarSettingChange.Duration(0), CalendarSettingChange.Duration(1441),
            CalendarSettingChange.TimedReminder(-1), CalendarSettingChange.TimedReminder(40321)).forEach {
            assertEquals(CalendarSettingSave.Invalid, settings.saveCalendarSetting(it))
        }
        assertEquals(0, store.updates); assertEquals(initial, store.stored)
    }

    @Test fun unavailableSaveRequiresExplicitRetryAndCancellationPropagates() = runBlocking {
        val initial = emptyPreferences(); val store = Store(initial); val settings = settings(store)
        val change = CalendarSettingChange.Theme(SettingsTheme.DARK)
        store.failure = IOException("Synthetic storage failure")
        assertEquals(CalendarSettingSave.Unavailable, settings.saveCalendarSetting(change))
        assertEquals(initial, store.stored); assertEquals(1, store.updates)
        store.failure = null
        assertEquals(CalendarSettingSave.Saved, settings.saveCalendarSetting(change))
        assertEquals("DARK", store.stored[stringPreferencesKey("display_theme")])
        store.failure = CancellationException("Synthetic cancellation")
        try { settings.saveCalendarSetting(CalendarSettingChange.Theme(SettingsTheme.LIGHT)); fail("Cancellation must propagate") }
        catch (_: CancellationException) { }
        assertEquals("DARK", store.stored[stringPreferencesKey("display_theme")])
        assertEquals(3, store.updates)
    }

    @Test fun concurrentInvitationAndUnrelatedChangesPersistWithoutLostUpdates() = runBlocking {
        val initial = preferencesOf(stringPreferencesKey("display_zone_mode") to "FUTURE_ZONE",
            stringPreferencesKey("reminder_policy_v1") to "opaque-policy", longPreferencesKey("default_calendar_id") to 9L)
        val store = Store(initial)
        val writer = settings(store)
        val results = listOf(async { writer.saveCalendarSetting(CalendarSettingChange.InvitationFilter(InvitationFilter.PENDING_ONLY)) },
            async { writer.saveCalendarSetting(CalendarSettingChange.Duration(135)) }).awaitAll()
        assertEquals(listOf(CalendarSettingSave.Saved, CalendarSettingSave.Saved), results)
        assertEquals(raw(initial) + mapOf("display_invitation_filter" to "PENDING_ONLY", "display_default_duration_minutes" to 135), raw(store.stored))
        // A new Settings owner reads the same persisted store, without a remembered UI value.
        val reopened = settings(store)
        assertEquals(CalendarSettingSave.Saved, reopened.saveCalendarSetting(CalendarSettingChange.Theme(SettingsTheme.DARK)))
        assertEquals(InvitationFilter.PENDING_ONLY, CalendarSettingsCodec.read(store.stored, "DAY").invitationFilter)
        assertEquals(CalendarSettingSave.Saved, reopened.saveCalendarSetting(CalendarSettingChange.InvitationFilter(InvitationFilter.ALL)))
        assertEquals("ALL", store.stored[stringPreferencesKey("display_invitation_filter")])
        assertEquals(135, store.stored[intPreferencesKey("display_default_duration_minutes")])
        assertEquals("FUTURE_ZONE", store.stored[stringPreferencesKey("display_zone_mode")])
    }

    @Test fun unrelatedPatchesPreserveUnknownInvitationBytesUntilExplicitChoice() = runBlocking {
        for (initial in listOf(preferencesOf(stringPreferencesKey("display_invitation_filter") to "FUTURE_FILTER"),
            preferencesOf(intPreferencesKey("display_invitation_filter") to 8))) {
            val store = Store(initial)
            val writer = settings(store)
            assertEquals(InvitationFilter.ALL, CalendarSettingsCodec.read(store.stored, "DAY").invitationFilter)
            assertEquals(CalendarSettingSave.Saved, writer.saveCalendarSetting(CalendarSettingChange.ShowWeekends(false)))
            assertEquals(raw(initial) + ("display_show_weekends" to false), raw(store.stored))
            assertEquals(CalendarSettingSave.Saved, writer.saveCalendarSetting(CalendarSettingChange.InvitationFilter(InvitationFilter.PENDING_ONLY)))
            assertEquals(mapOf("display_invitation_filter" to "PENDING_ONLY", "display_show_weekends" to false), raw(store.stored))
        }
    }

    @Test fun invitationChoicePersistsThroughRealDataStoreCloseAndReopen() = runBlocking {
        val directory = Files.createTempDirectory("invitation-preferences").toFile()
        val file = java.io.File(directory, "settings.preferences_pb")
        suspend fun <T> opened(block: suspend (Settings) -> T): T {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val disk = PreferenceDataStoreFactory.create(scope = scope) { file }
            try { return block(Settings(RuntimeEnvironment.getApplication(), disk)) }
            finally { scope.cancel(); scope.coroutineContext[Job]!!.join() }
        }
        try {
            opened { settings ->
                assertEquals(CalendarSettingSave.Saved, settings.saveCalendarSetting(CalendarSettingChange.InvitationFilter(InvitationFilter.PENDING_ONLY)))
                assertEquals(CalendarSettingSave.Saved, settings.saveCalendarSetting(CalendarSettingChange.ShowDeclined(false)))
            }
            opened { settings ->
                val snapshot = settings.calendarPreferences.filterIsInstance<CalendarPreferenceState.Ready>().first().value
                assertEquals(InvitationFilter.PENDING_ONLY, snapshot.options.invitationFilter)
                assertFalse(snapshot.options.showDeclined)
                val legacyEdit = SettingsDraft.from(snapshot.options).copy(duration = "135").validated(dev.swapnilraj.calendar.ui.calendars.CalendarViewOption.entries)!!
                assertTrue(settings.setDisplaySettings(legacyEdit))
            }
            opened { settings ->
                val snapshot = settings.calendarPreferences.filterIsInstance<CalendarPreferenceState.Ready>().first().value
                assertEquals(InvitationFilter.PENDING_ONLY, snapshot.options.invitationFilter)
                assertEquals(135, snapshot.options.defaultDurationMinutes)
                assertFalse(snapshot.options.showDeclined)
            }
        } finally { directory.deleteRecursively() }
    }
}
