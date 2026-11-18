package dev.swapnilraj.calendar.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.swapnilraj.calendar.MainActivity
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Read-only date hint; display-zone conversion belongs to the live calendar at consumption. */
data class CalendarDateNavigation(val id: String, val instant: Instant)

internal object CalendarDateIntents {
    private const val ACTION = "dev.swapnilraj.calendar.OPEN_DATE"
    fun intent(context: Context, value: CalendarDateNavigation): Intent {
        require(UUID.fromString(value.id).toString() == value.id)
        require(value.instant.atOffset(ZoneOffset.UTC).year in 1..9999)
        val uri = Uri.Builder().scheme("daylight").authority("calendar-date")
            .appendPath(value.instant.toEpochMilli().toString()).appendQueryParameter("session", value.id).build()
        return Intent(context, MainActivity::class.java).setAction(ACTION).setData(uri)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
    fun decode(context: Context, intent: Intent?): CalendarDateNavigation? = try {
        if (intent?.action != ACTION || intent.component != ComponentName(context, MainActivity::class.java)) null
        else intent.data?.takeIf { it.toString().length <= 512 && it.scheme == "daylight" && it.authority == "calendar-date" &&
            it.fragment == null && it.pathSegments.size == 1 && it.queryParameterNames == setOf("session") }?.let { uri ->
            val id = requireNotNull(uri.getQueryParameter("session"))
            require(UUID.fromString(id).toString() == id)
            val instant = Instant.ofEpochMilli(uri.pathSegments.single().toLong())
            require(instant.atOffset(ZoneOffset.UTC).year in 1..9999)
            CalendarDateNavigation(id, instant)
        }
    } catch (_: Exception) { null }
}
