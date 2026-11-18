package dev.swapnilraj.calendar

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.Settings
import dev.swapnilraj.calendar.data.reminders.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ReminderPreferenceStorageTest {
    private class Store(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        val state = MutableStateFlow(initial)
        var broken = false
        override val data = flow { if (broken) throw java.io.IOException("fixture"); emitAll(state) }
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            if (broken) throw java.io.IOException("fixture")
            return transform(state.value).also { state.value = it }
        }
    }
    private fun settings(store: Store) = Settings(RuntimeEnvironment.getApplication(), store)
    private val selected = SavedReminderSettings(true,
        mapOf(CalendarId(3) to ReminderCalendarIdentity("fixture-account", "fixture-uid")))

    @Test fun staleSetupCannotOverwriteNewChoicesAndDisplayChangesCannotSelectReminderCalendars() = runBlocking {
        val store = Store()
        val settings = settings(store)
        val empty = settings.readReminderSettings()
        assertEquals(SavedReminderSettings(), empty)
        assertEquals(ReminderPreferenceSave.Saved(selected), settings.saveReminderSettings(empty, selected))
        val disabled = selected.copy(enabled = false)
        assertEquals(ReminderPreferenceSave.Saved(disabled), settings.saveReminderSettings(selected, disabled))
        assertEquals(ReminderPreferenceSave.Changed, settings.saveReminderSettings(selected, selected))
        settings.setCalendarSelection(CalendarSelection.Explicit(emptySet()))
        settings.setDefaultCalendar(CalendarId(99))
        settings.setDisplaySettings(dev.swapnilraj.calendar.ui.settings.CalendarSettingsOptions(defaultDurationMinutes = 90))
        assertEquals(disabled, settings(store).readReminderSettings())
        val raw = store.state.value
        val invalid = disabled.copy(explicitDefaults = mapOf(CalendarId(99) to ReminderDefaults()))
        assertEquals(ReminderPreferenceSave.Invalid, settings.saveReminderSettings(disabled, invalid))
        assertEquals(raw, store.state.value)
    }

    @Test fun corruptOrFailedReadsNeverDisableOrRepairSavedReminders() = runBlocking {
        val raw = preferencesOf(ReminderSettingsCodec.key to "{broken fixture}")
        val store = Store(raw)
        val settings = settings(store)
        assertTrue(settings.reminderPreferences.first { it !is ReminderPreferenceState.Loading } is ReminderPreferenceState.Unavailable)
        assertEquals(ReminderPreferenceSave.Unavailable, settings.saveReminderSettings(SavedReminderSettings(), selected))
        assertEquals(raw, store.state.value)
        try { settings.readReminderSettings(); fail("Corrupt settings must not produce disabled configuration") }
        catch (_: IllegalStateException) { }
        store.broken = true
        assertEquals(ReminderPreferenceState.Unavailable(), settings.reminderPreferences.first { it !is ReminderPreferenceState.Loading })
        assertEquals(ReminderPreferenceSave.Unavailable, settings.saveReminderSettings(SavedReminderSettings(), selected))
        try { settings.readReminderSettings(); fail("IO failure must propagate to background supplier") }
        catch (_: java.io.IOException) { }
        assertEquals(raw, store.state.value)
    }

    @Test fun lostAcknowledgementIsDetectedByReadbackInsteadOfOverwritingAnotherSnapshot() = runBlocking {
        val store = Store()
        val settings = settings(store)
        val initial = settings.readReminderSettings()
        settings.saveReminderSettings(initial, selected) // Caller loses its in-memory result.
        val reopened = settings(store)
        assertEquals(selected, reopened.readReminderSettings())
        assertEquals(ReminderPreferenceSave.Changed, reopened.saveReminderSettings(initial, selected.copy(enabled = false)))
        assertEquals(selected, reopened.readReminderSettings())
    }
}
