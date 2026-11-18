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
import dev.swapnilraj.calendar.data.calendar.CalendarOperationRetirement
import dev.swapnilraj.calendar.data.calendar.CalendarSeriesReader
import dev.swapnilraj.calendar.data.calendar.CalendarSeriesWriter
import dev.swapnilraj.calendar.data.calendar.CalendarMeetingWriter
import dev.swapnilraj.calendar.data.calendar.CalendarSearchReader
import dev.swapnilraj.calendar.data.calendar.CalendarReminderSource
import dev.swapnilraj.calendar.data.calendar.SqliteReminderLedger
import dev.swapnilraj.calendar.android.navigation.*
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import dev.swapnilraj.calendar.android.reminders.ReminderRuntimeOwner
import dev.swapnilraj.calendar.android.reminders.ReminderRuntime
import dev.swapnilraj.calendar.android.widgets.*

open class CalendarApp : Application(), ReminderRuntimeOwner, AgendaWidgetApplicationOwner, CalendarEntryOwner, CalendarSeedReviewOwner {
    /** Imported-row cloning requires independently verified provider implementation evidence. */
    protected open val providerClonePolicy: dev.swapnilraj.calendar.data.calendar.ProviderClonePolicy
        get() = dev.swapnilraj.calendar.data.calendar.ProviderClonePolicy.Unverified

    override val calendarEntryServices by lazy { createCalendarEntryServices() }
    override val calendarEntryContent by lazy { calendarEntryRenderer() }
    override val calendarEntryReviewHandler by lazy { calendarEntryReviewer() }
    override val calendarSeedReviewServices by lazy { createCalendarSeedReviewServices() }
    @androidx.compose.runtime.Composable
    override fun CalendarSeedReviewTheme(content: @androidx.compose.runtime.Composable () -> Unit) {
        calendarSeedReviewRendererTheme(content)
    }
    private val calendarScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    private val reminderScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    override val reminderRuntime: ReminderRuntime by lazy { createReminderRuntime(reminderScope) }
    private val widgetScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    override val agendaWidgetServices by lazy { createWidgetServices(widgetScope) }
    override val agendaWidgetRuntime get() = agendaWidgetServices.runtime
    override val agendaWidgetFramework get() = agendaWidgetServices.framework
    internal val reminderSource by lazy { CalendarReminderSource(contentResolver,
        { permission -> checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED }) }
    internal val reminderLedger by lazy { SqliteReminderLedger(calendarLocalStore) }
    internal val reminderOpenResolver by lazy { ReminderOpenResolver(reminderSource, reminderLedger,
        { settings.readReminderSettings().toConfiguration(java.time.ZoneId.systemDefault()) }, calendarReader::readDetails) }

    override fun onCreate() {
        super.onCreate()
        reminderRuntime.onProcessStarted()
        agendaWidgetServices
    }
    val calendarReader by lazy { CalendarReader(contentResolver, calendarScope) }
    val calendarLocalStore by lazy { CalendarLocalStore(this) }
    val operationRetirement by lazy { CalendarOperationRetirement(calendarLocalStore) }
    val calendarWriter by lazy { CalendarWriter(contentResolver, calendarReader, calendarLocalStore,
        { permission -> ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED }) }
    val calendarSeriesReader by lazy { CalendarSeriesReader(contentResolver, calendarReader, calendarLocalStore,
        { permission -> ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED }) }
    val calendarSeriesWriter by lazy { CalendarSeriesWriter(contentResolver, calendarSeriesReader, calendarLocalStore,
        { permission -> ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED },
        providerClonePolicy = providerClonePolicy) }
    val calendarMeetings by lazy { CalendarMeetingWriter(contentResolver, calendarReader, calendarLocalStore,
        { permission -> ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED },
        providerClonePolicy = providerClonePolicy) }
    val calendarSearch by lazy { CalendarSearchReader(contentResolver, calendarReader,
        { permission -> ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED }) }
    val settings by lazy { Settings(this) }
    val weather by lazy {
        val client = OpenMeteo(::fetchWeather)
        val location = LocationSource(this, client)
        WeatherRepository(location::resolve, client::forecast)
    }
}
