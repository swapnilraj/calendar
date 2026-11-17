package dev.swapnilraj.calendar.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import androidx.datastore.preferences.core.emptyPreferences

private val Context.daylightSettings by preferencesDataStore("daylight")

class Settings(context: Context) {
    private val store = context.applicationContext.daylightSettings
    private val key = floatPreferencesKey("hour_height")
    val hourHeight = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map { (it[key] ?: 64f).takeIf(Float::isFinite)?.coerceIn(32f, 160f) ?: 64f }

    suspend fun setHourHeight(height: Float) {
        if (!height.isFinite()) return
        try { store.edit { it[key] = height.coerceIn(32f, 160f) } } catch (_: IOException) { /* Keep the session's zoom. */ }
    }
}
