package dev.swapnilraj.calendar

import android.app.Application
import dev.swapnilraj.calendar.data.Settings
import dev.swapnilraj.calendar.data.LocationSource
import dev.swapnilraj.calendar.data.WeatherRepository
import dev.swapnilraj.calendar.data.fetchWeather
import dev.swapnilraj.calendar.weather.OpenMeteo
import dev.swapnilraj.calendar.data.calendar.CalendarReader
import dev.swapnilraj.calendar.data.calendar.CalendarLocalStore
import dev.swapnilraj.calendar.data.calendar.CalendarWriter
import dev.swapnilraj.calendar.data.calendar.CalendarSeriesReader
import dev.swapnilraj.calendar.data.calendar.CalendarSeriesWriter
import dev.swapnilraj.calendar.data.calendar.CalendarSearchReader
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class CalendarApp : Application() {
    private val calendarScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    val calendarReader by lazy { CalendarReader(contentResolver, calendarScope) }
    val calendarLocalStore by lazy { CalendarLocalStore(this) }
    val calendarWriter by lazy { CalendarWriter(contentResolver, calendarReader, calendarLocalStore,
        { permission -> ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED }) }
    val calendarSeriesReader by lazy { CalendarSeriesReader(contentResolver, calendarReader, calendarLocalStore,
        { permission -> ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED }) }
    val calendarSeriesWriter by lazy { CalendarSeriesWriter(contentResolver, calendarSeriesReader, calendarLocalStore,
        { permission -> ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED }) }
    val calendarSearch by lazy { CalendarSearchReader(contentResolver, calendarReader,
        { permission -> ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED }) }
    val settings by lazy { Settings(this) }
    val weather by lazy {
        val client = OpenMeteo(::fetchWeather)
        val location = LocationSource(this, client)
        WeatherRepository(location::resolve, client::forecast)
    }
}
