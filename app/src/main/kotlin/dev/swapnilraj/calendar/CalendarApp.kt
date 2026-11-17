package dev.swapnilraj.calendar

import android.app.Application
import dev.swapnilraj.calendar.data.CalendarRepository
import dev.swapnilraj.calendar.data.Settings
import dev.swapnilraj.calendar.data.LocationSource
import dev.swapnilraj.calendar.data.WeatherRepository
import dev.swapnilraj.calendar.data.fetchWeather
import dev.swapnilraj.calendar.weather.OpenMeteo

class CalendarApp : Application() {
    val calendars by lazy { CalendarRepository(contentResolver) }
    val settings by lazy { Settings(this) }
    val weather by lazy {
        val client = OpenMeteo(::fetchWeather)
        val location = LocationSource(this, client)
        WeatherRepository(location::resolve, client::forecast)
    }
}
