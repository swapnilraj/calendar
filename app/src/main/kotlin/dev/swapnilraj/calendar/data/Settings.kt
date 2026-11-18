package dev.swapnilraj.calendar.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.swapnilraj.calendar.core.CalendarId
import dev.swapnilraj.calendar.core.CalendarSelection
import dev.swapnilraj.calendar.core.CalendarInfo
import dev.swapnilraj.calendar.core.AccessDecision
import java.util.Base64
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import androidx.datastore.preferences.core.emptyPreferences

private val Context.daylightSettings by preferencesDataStore("daylight")

class Settings(context: Context) {
    private val store = context.applicationContext.daylightSettings
    private val key = floatPreferencesKey("hour_height")
    private val selectedCalendarsKey = stringSetPreferencesKey("selected_calendar_ids")
    private val defaultCalendarKey = longPreferencesKey("default_calendar_id")
    private val knownCalendarsKey = stringSetPreferencesKey("known_calendar_accounts")
    private val viewKey = stringPreferencesKey("calendar_view")
    private val views = setOf("DAY", "MONTH", "THREE_DAY", "WEEK", "AGENDA")
    private val preferences = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }
    val hourHeight = preferences.map { (it[key] ?: 64f).takeIf(Float::isFinite)?.coerceIn(32f, 160f) ?: 64f }

    /** An absent preference retains the provider default; a stored empty set means no calendars. */
    val calendarSelection = preferences.map { values ->
        values[selectedCalendarsKey]?.let { ids ->
            CalendarSelection.Explicit(ids.mapNotNull { raw ->
                raw.toLongOrNull()?.takeIf { it >= 0 }?.let(::CalendarId)
            }.toSet())
        } ?: CalendarSelection.ProviderVisible
    }

    /** Eligibility is rechecked against fresh metadata before creating an event. */
    val defaultCalendar = preferences.map { values ->
        values[defaultCalendarKey]?.takeIf { it >= 0 }?.let(::CalendarId)
    }

    val calendarView = preferences.map { values -> values[viewKey]?.takeIf { it in views } ?: "DAY" }

    suspend fun setCalendarView(view: String): Boolean {
        if (view !in views) return false
        return try { store.edit { it[viewKey] = view }; true } catch (_: IOException) { false }
    }

    suspend fun setCalendarSelection(selection: CalendarSelection, calendars: List<CalendarInfo>? = null): Boolean = try {
        store.edit { values ->
            if (calendars != null) values[knownCalendarsKey] = calendars.map(::calendarIdentity).toSet()
            when (selection) {
                CalendarSelection.ProviderVisible -> values.remove(selectedCalendarsKey)
                is CalendarSelection.Explicit -> values[selectedCalendarsKey] = selection.ids.map { it.value.toString() }.toSet()
            }
        }
        true
    } catch (_: IOException) { false }

    /** Preserve explicit choices across renames; new visible calendars adopt the initial default. */
    suspend fun reconcileCalendars(calendars: List<CalendarInfo>): Boolean = try {
        val unique = calendars.distinctBy { it.id }
        val identities = unique.associate { it.id.value.toString() to calendarIdentity(it) }
        store.edit { values ->
            val previous = values[knownCalendarsKey]
            val selected = values[selectedCalendarsKey]
            if (selected != null) {
                val retained = selected.filterTo(mutableSetOf()) { id ->
                    identities[id]?.let { previous == null || it in previous } == true
                }
                if (previous != null) unique.filter { calendar ->
                    calendarIdentity(calendar) !in previous && calendar.providerVisible &&
                        calendar.accessLevel?.let { it >= android.provider.CalendarContract.Calendars.CAL_ACCESS_FREEBUSY } != false
                }.forEach { retained += it.id.value.toString() }
                values[selectedCalendarsKey] = retained
            }
            values[defaultCalendarKey]?.let { id ->
                val calendar = unique.firstOrNull { it.id.value == id }
                if (calendar == null || calendar.capabilities.createEvents != AccessDecision.ALLOWED ||
                    (previous != null && calendarIdentity(calendar) !in previous)) values.remove(defaultCalendarKey)
            }
            values[knownCalendarsKey] = identities.values.toSet()
        }
        true
    } catch (_: IOException) { false }

    private fun calendarIdentity(calendar: CalendarInfo): String {
        fun encode(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
        return "${calendar.id.value}:${encode(calendar.account.name)}:${encode(calendar.account.type)}"
    }

    suspend fun setDefaultCalendar(id: CalendarId?, calendars: List<CalendarInfo>? = null): Boolean = try {
        store.edit { values ->
            if (calendars != null) values[knownCalendarsKey] = calendars.map(::calendarIdentity).toSet()
            if (id == null) values.remove(defaultCalendarKey) else values[defaultCalendarKey] = id.value
        }
        true
    } catch (_: IOException) { false }

    suspend fun setHourHeight(height: Float) {
        if (!height.isFinite()) return
        try { store.edit { it[key] = height.coerceIn(32f, 160f) } } catch (_: IOException) { /* Keep the session's zoom. */ }
    }
}
