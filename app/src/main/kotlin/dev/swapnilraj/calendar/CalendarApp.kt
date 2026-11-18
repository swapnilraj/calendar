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
import dev.swapnilraj.calendar.data.calendar.CalendarReminderSource
import dev.swapnilraj.calendar.data.calendar.SqliteReminderLedger
import dev.swapnilraj.calendar.android.navigation.ReminderOpenResolver
import dev.swapnilraj.calendar.android.reminders.ReminderRuntimeOwner
import dev.swapnilraj.calendar.android.reminders.ReminderRuntime
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class CalendarApp : Application(), ReminderRuntimeOwner {
    private val calendarScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    private val reminderScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    override val reminderRuntime: ReminderRuntime by lazy { createReminderRuntime(reminderScope) }
    internal val reminderSource by lazy { CalendarReminderSource(contentResolver,
        { permission -> checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED }) }
    internal val reminderLedger by lazy { SqliteReminderLedger(calendarLocalStore) }
    internal val reminderOpenResolver by lazy { ReminderOpenResolver(reminderSource, reminderLedger,
        { settings.readReminderSettings().toConfiguration(java.time.ZoneId.systemDefault()) }, calendarReader::readDetails) }

    override fun onCreate() {
        super.onCreate()
        reminderRuntime.onProcessStarted()
    }
    val calendarReader by lazy { CalendarReader(contentResolver, calendarScope) }
    val calendarLocalStore by lazy { CalendarLocalStore(this) }
    val calendarWriter by lazy { CalendarWriter(contentResolver, calendarReader, calendarLocalStore,
        { permission -> ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED }) }
    val settings by lazy { Settings(this) }
    val weather by lazy {
        val client = OpenMeteo(::fetchWeather)
        val location = LocationSource(this, client)
        WeatherRepository(location::resolve, client::forecast)
    }
}
