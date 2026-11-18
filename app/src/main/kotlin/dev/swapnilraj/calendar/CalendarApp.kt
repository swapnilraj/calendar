package dev.swapnilraj.calendar

import android.app.Application
import dev.swapnilraj.calendar.data.Settings
import dev.swapnilraj.calendar.data.LocationSource
import dev.swapnilraj.calendar.data.WeatherRepository
import dev.swapnilraj.calendar.data.fetchWeather
import dev.swapnilraj.calendar.weather.OpenMeteo
import dev.swapnilraj.calendar.data.calendar.CalendarReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class CalendarApp : Application() {
    private val calendarScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    val calendarReader by lazy { CalendarReader(contentResolver, calendarScope) }
    val settings by lazy { Settings(this) }
    val weather by lazy {
        val client = OpenMeteo(::fetchWeather)
        val location = LocationSource(this, client)
        WeatherRepository(location::resolve, client::forecast)
    }
}
