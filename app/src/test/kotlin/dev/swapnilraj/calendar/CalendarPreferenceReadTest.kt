package dev.swapnilraj.calendar

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import dev.swapnilraj.calendar.core.CalendarId
import dev.swapnilraj.calendar.core.CalendarSelection
import dev.swapnilraj.calendar.data.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
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
class CalendarPreferenceReadTest {
    private class Store(initial: Preferences, failing: Boolean = false) : DataStore<Preferences> {
        private sealed interface Read {
            data class Value(val preferences: Preferences) : Read
            data object Failure : Read
        }
        var stored = initial
            private set
        var updates = 0
            private set
        var reads = 0
            private set
        private val mutex = Mutex()
        private val state = MutableStateFlow<Read>(if (failing) Read.Failure else Read.Value(initial))
        override val data: Flow<Preferences> = flow {
            reads++
            emitAll(state.map {
                when (it) {
                    is Read.Value -> it.preferences
                    Read.Failure -> throw IOException("Synthetic private preference read failure")
                }
            })
        }
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = mutex.withLock {
            updates++
            if (state.value == Read.Failure) throw IOException("Synthetic unavailable store")
            transform(stored).also { stored = it; state.value = Read.Value(it) }
        }
        fun failReads() { state.value = Read.Failure }
        fun restoreReads() { state.value = Read.Value(stored) }
    }

    private fun stored(ids: Set<String>) = preferencesOf(stringSetPreferencesKey("selected_calendar_ids") to ids,
        longPreferencesKey("default_calendar_id") to 9L, stringPreferencesKey("calendar_view") to "WEEK",
        floatPreferencesKey("hour_height") to 88f)
    private fun settings(store: Store) = Settings(RuntimeEnvironment.getApplication(), store)
    private suspend fun Channel<CalendarPreferenceState>.next(): CalendarPreferenceState = withTimeout(10_000) { receive() }

    @Test fun defaultCreationDestinationRequiresTheSameNamespaceEvenBeforeReconciliation() = runBlocking {
        val calendar = dev.swapnilraj.calendar.core.CalendarInfo(CalendarId(9), "Calendar A", 1,
            dev.swapnilraj.calendar.core.CalendarAccount("a@example.invalid", "com.google"), "a@example.invalid", null, 700, true, true,
            dev.swapnilraj.calendar.core.CalendarCapabilities(createEvents = dev.swapnilraj.calendar.core.AccessDecision.ALLOWED), "calendar-a")
        val replacement = calendar.copy(displayName = "Calendar B", account = dev.swapnilraj.calendar.core.CalendarAccount("b@example.invalid", "com.google"),
            owner = "b@example.invalid", providerId = "calendar-b")
        val store = Store(stored(setOf("9")))
        val settings = settings(store)
        val legacy = settings.calendarPreferences.filterIsInstance<CalendarPreferenceState.Ready>().first().value
        assertNull(dev.swapnilraj.calendar.ui.verifiedCreationCalendar(legacy, listOf(calendar)))
        assertTrue(settings.setDefaultCalendar(calendar.id, listOf(calendar)))
        val pinned = settings.calendarPreferences.filterIsInstance<CalendarPreferenceState.Ready>().first().value
        assertEquals(calendar.identity, pinned.defaultCalendarIdentity)
        assertEquals(calendar, dev.swapnilraj.calendar.ui.verifiedCreationCalendar(pinned, listOf(calendar)))
        val missingUid = calendar.copy(providerId = null)
        assertTrue(settings.reconcileCalendars(listOf(missingUid)))
        val unavailable = settings.calendarPreferences.filterIsInstance<CalendarPreferenceState.Ready>().first().value
        assertEquals(calendar.identity, unavailable.defaultCalendarIdentity)
        assertNull(dev.swapnilraj.calendar.ui.verifiedCreationCalendar(unavailable, listOf(missingUid)))
        // A provider ID can be reused before asynchronous preference reconciliation runs.
        assertNull(dev.swapnilraj.calendar.ui.verifiedCreationCalendar(pinned, listOf(replacement)))
        assertEquals(calendar.copy(displayName = "Renamed", color = 2),
            dev.swapnilraj.calendar.ui.verifiedCreationCalendar(pinned, listOf(calendar.copy(displayName = "Renamed", color = 2))))
        assertTrue(settings.reconcileCalendars(listOf(replacement)))
        val removed = settings.calendarPreferences.filterIsInstance<CalendarPreferenceState.Ready>().first().value
        assertNull(removed.defaultCalendar)
        assertNull(removed.defaultCalendarIdentity)
        assertTrue(settings.setDefaultCalendar(replacement.id, listOf(replacement)))
        val reselected = settings.calendarPreferences.filterIsInstance<CalendarPreferenceState.Ready>().first().value
        assertEquals(replacement, dev.swapnilraj.calendar.ui.verifiedCreationCalendar(reselected, listOf(replacement)))
        assertEquals(CalendarSelection.Explicit(setOf(CalendarId(9))), reselected.selection)
    }

    @Test fun displaySaveIsAtomicWithFiltersAndFailureCannotPartiallyApplyOptions() = runBlocking {
        val original = stored(setOf("9")).toMutablePreferences().apply {
            this[stringPreferencesKey("reminder_private_fixture")] = "retain"
        }.toPreferences()
        val store = Store(original)
        val settings = settings(store)
        val options = dev.swapnilraj.calendar.ui.settings.CalendarSettingsOptions(
            defaultView = dev.swapnilraj.calendar.ui.calendars.CalendarViewOption.MONTH,
            defaultDurationMinutes = 75, timedReminderMinutes = 30, showDeclined = false,
            showWeekends = false, theme = dev.swapnilraj.calendar.ui.settings.SettingsTheme.DARK,
            displayZone = dev.swapnilraj.calendar.ui.settings.DisplayZoneOption.Fixed(java.time.ZoneId.of("America/Los_Angeles")),
            weatherVisible = false)
        assertTrue(settings.setDisplaySettings(options))
        val ready = settings.calendarPreferences.filterIsInstance<CalendarPreferenceState.Ready>().first().value
        assertEquals(options, ready.options)
        assertEquals("MONTH", ready.view)
        assertEquals(CalendarSelection.Explicit(setOf(CalendarId(9))), ready.selection)
        assertEquals(CalendarId(9), ready.defaultCalendar)
        assertEquals(88f, ready.hourHeight)
        assertEquals("retain", store.stored[stringPreferencesKey("reminder_private_fixture")])
        val saved = store.stored
        store.failReads()
        assertFalse(settings.setDisplaySettings(options.copy(defaultDurationMinutes = 90)))
        assertEquals(saved, store.stored)
    }

    @Test fun firstReadFailureDoesNotExposeDefaultsOrOverwriteExplicitNoneOrWorkSelection() = runBlocking {
        for (ids in listOf(emptySet(), setOf("9"))) {
            val store = Store(stored(ids), failing = true)
            val settings = settings(store)
            assertEquals(listOf(CalendarPreferenceState.Loading, CalendarPreferenceState.Unavailable), settings.calendarPreferences.take(2).toList())
            val legacyValues = mutableListOf<Any?>()
            val collectors = listOf(settings.calendarSelection, settings.defaultCalendar, settings.calendarView, settings.hourHeight)
                .map { flow -> launch { flow.collect { legacyValues += it } } }
            try {
                withTimeout(10_000) { while (store.reads < 5) yield() }
                yield()
                assertTrue(legacyValues.isEmpty())
            } finally { collectors.forEach { it.cancelAndJoin() } }
            assertEquals(stored(ids), store.stored)
            assertEquals(0, store.updates)
        }
    }

    @Test fun failureAfterValidSnapshotKeepsLegacySelectionAndNeverEmitsProviderVisible() = runBlocking {
        val store = Store(stored(setOf("9")))
        val settings = settings(store)
        val states = Channel<CalendarPreferenceState>(Channel.UNLIMITED)
        val selections = mutableListOf<CalendarSelection>()
        val stateJob = launch { settings.calendarPreferences.collect { states.send(it) } }
        val selectionJob = launch { settings.calendarSelection.collect { selections += it } }
        try {
            assertEquals(CalendarPreferenceState.Loading, states.next())
            val ready = states.next() as CalendarPreferenceState.Ready
            assertEquals(CalendarSelection.Explicit(setOf(CalendarId(9))), ready.value.selection)
            withTimeout(10_000) { while (selections.isEmpty()) yield() }
            store.failReads()
            assertEquals(CalendarPreferenceState.Unavailable, states.next())
            // Wait for both cold reads to observe their failure; neither emits a replacement value.
            yield()
            assertTrue(selectionJob.isActive)
            assertEquals(listOf(CalendarSelection.Explicit(setOf(CalendarId(9)))), selections)
            assertEquals(0, store.updates)
            assertEquals(stored(setOf("9")), store.stored)
        } finally { stateJob.cancelAndJoin(); selectionJob.cancelAndJoin() }
    }

    @Test fun explicitRetryRestartsFailedReadAndRecoversAllFieldsInOneSnapshot() = runBlocking {
        val store = Store(stored(emptySet()), failing = true)
        val settings = settings(store)
        val states = Channel<CalendarPreferenceState>(Channel.UNLIMITED)
        val collector = launch { settings.calendarPreferences.collect { states.send(it) } }
        try {
            assertEquals(CalendarPreferenceState.Loading, states.next())
            assertEquals(CalendarPreferenceState.Unavailable, states.next())
            store.restoreReads()
            settings.refreshPreferences()
            assertEquals(CalendarPreferenceState.Loading, states.next())
            assertEquals(CalendarPreferenceState.Ready(CalendarPreferences(CalendarSelection.Explicit(emptySet()), CalendarId(9), "WEEK", 88f)), states.next())
            assertEquals(2, store.reads)
            assertEquals(0, store.updates)
        } finally { collector.cancelAndJoin() }
    }

    @Test fun normalPreferenceWritesEmitCompleteReadySnapshotsWithoutLoadingOrResettingOtherFields() = runBlocking {
        val store = Store(stored(setOf("9")))
        val settings = settings(store)
        val states = Channel<CalendarPreferenceState>(Channel.UNLIMITED)
        val collector = launch { settings.calendarPreferences.collect { states.send(it) } }
        try {
            assertEquals(CalendarPreferenceState.Loading, states.next())
            val first = (states.next() as CalendarPreferenceState.Ready).value
            assertTrue(settings.setCalendarView("MONTH"))
            assertEquals(CalendarPreferenceState.Ready(first.copy(view = "MONTH", options = first.options.copy(defaultView = dev.swapnilraj.calendar.ui.calendars.CalendarViewOption.MONTH))), states.next())
            assertTrue(settings.setCalendarSelection(CalendarSelection.Explicit(emptySet())))
            val hidden = first.copy(view = "MONTH", selection = CalendarSelection.Explicit(emptySet()), options = first.options.copy(defaultView = dev.swapnilraj.calendar.ui.calendars.CalendarViewOption.MONTH))
            assertEquals(CalendarPreferenceState.Ready(hidden), states.next())
            settings.setHourHeight(120f)
            assertEquals(CalendarPreferenceState.Ready(hidden.copy(hourHeight = 120f)), states.next())
            assertTrue(settings.setDefaultCalendar(null))
            assertEquals(CalendarPreferenceState.Ready(hidden.copy(hourHeight = 120f, defaultCalendar = null)), states.next())
        } finally { collector.cancelAndJoin() }
    }
}
