package dev.swapnilraj.calendar

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import dev.swapnilraj.calendar.data.Settings
import dev.swapnilraj.calendar.data.settings.*
import dev.swapnilraj.calendar.ui.settings.*
import java.io.IOException
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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
}
