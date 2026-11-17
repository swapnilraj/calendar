package dev.swapnilraj.calendar

import android.app.Application
import dev.swapnilraj.calendar.data.CalendarRepository
import dev.swapnilraj.calendar.data.Settings

class CalendarApp : Application() {
    val calendars by lazy { CalendarRepository(contentResolver) }
    val settings by lazy { Settings(this) }
}
