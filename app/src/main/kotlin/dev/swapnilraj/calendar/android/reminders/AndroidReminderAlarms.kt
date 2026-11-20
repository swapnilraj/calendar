package dev.swapnilraj.calendar.android.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import dev.swapnilraj.calendar.core.*
import java.time.Instant
import java.time.ZoneOffset

/** One replaceable explicit alarm, so large calendars do not consume one kernel alarm per event. */
class AndroidReminderAlarms(private val context: Context, private val receiver: ComponentName) : ReminderAlarmPort {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    init { require(receiver.packageName == context.packageName) }

    override fun replaceNext(alarm: ReminderAlarmRequest?): AlarmSchedulingResult {
        if (alarm == null) {
            PendingIntent.getBroadcast(context, 0, ReminderAlarmIntents.base(receiver),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let(alarms::cancel)
            return AlarmSchedulingResult.CANCELLED
        }
        val pending = PendingIntent.getBroadcast(context, 0, ReminderAlarmIntents.intent(receiver, alarm),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val millis = try {
            // Round up, never fire before a sub-millisecond due instant.
            Math.addExact(alarm.at.toEpochMilli(), if (alarm.at.nano % 1_000_000 == 0) 0L else 1L)
        } catch (_: ArithmeticException) { return AlarmSchedulingResult.UNAVAILABLE }
        if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending)
                return AlarmSchedulingResult.EXACT
            } catch (_: SecurityException) { /* Access can be revoked between check and set. */ }
        }
        return try {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending)
            AlarmSchedulingResult.INEXACT
        } catch (_: RuntimeException) { AlarmSchedulingResult.UNAVAILABLE }
    }
}

/** Receiver registration/export rules are composition-root responsibilities. Never include titles. */
object ReminderAlarmIntents {
    const val ACTION_DELIVER = "dev.swapnilraj.calendar.reminders.DELIVER"
    private const val URI = "daylight://reminder-alarm/next"
    private const val PREFIX = "reminder_alarm."

    internal fun base(receiver: ComponentName): Intent = Intent(ACTION_DELIVER).setComponent(receiver).setData(Uri.parse(URI))

    fun intent(receiver: ComponentName, request: ReminderAlarmRequest): Intent = base(receiver).apply {
        val version = request.version
        putExtra(PREFIX + "calendar", version.key.calendarId.value)
        putExtra(PREFIX + "owner", version.key.masterOrEventId)
        putExtra(PREFIX + "original", version.key.originalOrInstanceStart.toString())
        putExtra(PREFIX + "minutes", version.key.resolvedMinutesBefore)
        putExtra(PREFIX + "fingerprint", version.sourceCalendarIdentity.fingerprint)
        version.sourceCalendarIdentity.providerUidFingerprint?.let { putExtra(PREFIX + "uid", it) }
        putExtra(PREFIX + "generation", version.generation)
        putExtra(PREFIX + "revision", version.revision)
        putExtra(PREFIX + "at", request.at.toString())
    }

    @Suppress("DEPRECATION")
    fun request(intent: Intent, expectedReceiver: ComponentName): ReminderAlarmRequest? = try {
        require(intent.action == ACTION_DELIVER && intent.data?.toString() == URI && intent.component == expectedReceiver)
        val extras = requireNotNull(intent.extras)
        fun value(name: String): Any = requireNotNull(extras.get(PREFIX + name))
        fun long(name: String): Long = value(name) as Long
        fun string(name: String): String = value(name) as String
        fun instant(name: String): Instant = Instant.parse(string(name)).also {
            require(it.atOffset(ZoneOffset.UTC).year in 1..9999)
        }
        val uid = if (extras.containsKey(PREFIX + "uid")) string("uid") else null
        val version = ReminderVersion(ReminderKey(CalendarId(long("calendar")), long("owner"), instant("original"),
            value("minutes") as Int), ReminderCalendarIdentity(string("fingerprint"), uid), long("generation"), long("revision"))
        require(version.generation >= 0 && version.revision >= 0)
        ReminderAlarmRequest(instant("at"), version)
    } catch (_: RuntimeException) { null }
}
