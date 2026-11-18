package dev.swapnilraj.calendar.android.reminders

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.swapnilraj.calendar.core.CalendarId
import dev.swapnilraj.calendar.core.EventRef
import dev.swapnilraj.calendar.core.RecurrenceRef
import java.time.Instant

/** The complete canonical identity is used as a notification tag; no truncated hash can collide. */
data class ReminderIdentity(val event: EventRef, val due: Instant) {
    val tag: String get() = Uri.Builder().scheme("daylight").authority("reminder").appendPath("v1")
        .appendPath(event.calendarId.value.toString()).appendPath(event.eventId.toString())
        .appendPath(event.instanceStart.toString()).appendPath(event.instanceEnd.toString())
        .appendPath(event.recurrence?.masterEventId?.toString() ?: "-")
        .appendPath(event.recurrence?.originalStart?.toString() ?: "-").appendPath(due.toString())
        .build().toString()
}

/** Explicit app destinations. The action receiver must be non-exported; the activity validates refs. */
data class ReminderIntentTargets(val eventActivity: ComponentName, val actionReceiver: ComponentName)

object ReminderIntents {
    const val ACTION_OPEN = "dev.swapnilraj.calendar.reminders.OPEN"
    const val ACTION_SNOOZE = "dev.swapnilraj.calendar.reminders.SNOOZE"
    const val ACTION_DISMISS = "dev.swapnilraj.calendar.reminders.DISMISS"
    private const val CALENDAR = "reminder.calendar"
    private const val EVENT = "reminder.event"
    private const val START = "reminder.start"
    private const val END = "reminder.end"
    private const val MASTER = "reminder.master"
    private const val ORIGINAL = "reminder.original"
    private const val DUE = "reminder.due"
    const val EXTRA_SNOOZE_MINUTES = "reminder.snooze_minutes"

    fun intent(context: Context, targets: ReminderIntentTargets, identity: ReminderIdentity,
        action: String, snoozeMinutes: Int? = null): Intent {
        require(action in setOf(ACTION_OPEN, ACTION_SNOOZE, ACTION_DISMISS))
        require((action == ACTION_SNOOZE && snoozeMinutes in listOf(5, 10, 30)) ||
            (action != ACTION_SNOOZE && snoozeMinutes == null))
        val target = if (action == ACTION_OPEN) targets.eventActivity else targets.actionReceiver
        require(target.packageName == context.packageName) { "Reminder destinations must belong to this app" }
        val data = Uri.parse(identity.tag).buildUpon().appendPath(action.substringAfterLast('.'))
            .apply { snoozeMinutes?.let { appendPath(it.toString()) } }.build()
        return Intent(action).setComponent(target).setData(data).apply {
            putExtra(CALENDAR, identity.event.calendarId.value)
            putExtra(EVENT, identity.event.eventId)
            putExtra(START, identity.event.instanceStart.toString())
            putExtra(END, identity.event.instanceEnd.toString())
            putExtra(DUE, identity.due.toString())
            identity.event.recurrence?.let { putExtra(MASTER, it.masterEventId); putExtra(ORIGINAL, it.originalStart.toString()) }
            snoozeMinutes?.let { putExtra(EXTRA_SNOOZE_MINUTES, it) }
        }
    }

    /** Treat all incoming extras as untrusted; absent or invalid values never default to event zero. */
    fun identity(intent: Intent): ReminderIdentity? = runCatching {
        require(intent.hasExtra(CALENDAR) && intent.hasExtra(EVENT) && intent.hasExtra(START) && intent.hasExtra(END) && intent.hasExtra(DUE))
        val hasMaster = intent.hasExtra(MASTER)
        require(hasMaster == intent.hasExtra(ORIGINAL))
        ReminderIdentity(EventRef(
            CalendarId(intent.getLongExtra(CALENDAR, -1)), intent.getLongExtra(EVENT, -1),
            Instant.parse(intent.getStringExtra(START)), Instant.parse(intent.getStringExtra(END)),
            if (hasMaster) RecurrenceRef(intent.getLongExtra(MASTER, -1), Instant.parse(intent.getStringExtra(ORIGINAL))) else null,
        ), Instant.parse(intent.getStringExtra(DUE)))
    }.getOrNull()
}
