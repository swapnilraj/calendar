package dev.swapnilraj.calendar.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.swapnilraj.calendar.core.CalendarId
import dev.swapnilraj.calendar.core.CalendarSelection
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

    suspend fun setCalendarSelection(selection: CalendarSelection): Boolean = try {
        store.edit { values ->
            when (selection) {
                CalendarSelection.ProviderVisible -> values.remove(selectedCalendarsKey)
                is CalendarSelection.Explicit -> values[selectedCalendarsKey] = selection.ids.map { it.value.toString() }.toSet()
            }
        }
        true
    } catch (_: IOException) { false }

    suspend fun setDefaultCalendar(id: CalendarId?): Boolean = try {
        store.edit { values ->
            if (id == null) values.remove(defaultCalendarKey) else values[defaultCalendarKey] = id.value
        }
        true
    } catch (_: IOException) { false }

    suspend fun setHourHeight(height: Float) {
        if (!height.isFinite()) return
        try { store.edit { it[key] = height.coerceIn(32f, 160f) } } catch (_: IOException) { /* Keep the session's zoom. */ }
    }
}
