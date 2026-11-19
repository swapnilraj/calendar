package dev.swapnilraj.calendar.probe

import android.app.Application
import dev.swapnilraj.calendar.android.reminders.ReminderRuntime
import dev.swapnilraj.calendar.android.reminders.ReminderRuntimeOwner

/** Explicit isolated debug target only; never starts CalendarApp, widgets or weather. */
class ProbeReminderApplication : Application(), ReminderRuntimeOwner {
    override val reminderRuntime: ReminderRuntime
        get() = ProbeReminderBindings.runtime(this)
}
