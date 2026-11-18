package dev.swapnilraj.calendar.data

import android.content.ContentResolver
import android.content.ContentUris
import android.database.ContentObserver
import android.provider.CalendarContract
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*

sealed interface CalendarState {
    data object Loading : CalendarState
    data class Ready(val events: List<CalEvent>) : CalendarState
    data object PermissionRequired : CalendarState
    data object Unavailable : CalendarState
}

class CalendarRepository(private val resolver: ContentResolver) {
    fun events(date: LocalDate, zone: ZoneId): Flow<CalendarState> = callbackFlow {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) { trySend(Unit) }
        }
        // Observe the provider root: edits to Events/Calendars also invalidate Instances.
        resolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
        trySend(Unit)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.conflate().map {
        try { CalendarState.Ready(query(date, zone)) }
        catch (_: SecurityException) { CalendarState.PermissionRequired }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { CalendarState.Unavailable }
    }.flowOn(Dispatchers.IO)

    internal fun query(date: LocalDate, zone: ZoneId): List<CalEvent> {
        val localStart = date.atStartOfDay(zone).toInstant()
        val localEnd = date.plusDays(1).atStartOfDay(zone).toInstant()
        val utcStart = date.atStartOfDay(ZoneOffset.UTC).toInstant()
        val utcEnd = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
            ContentUris.appendId(this, minOf(localStart, utcStart).toEpochMilli())
            ContentUris.appendId(this, maxOf(localEnd, utcEnd).toEpochMilli())
        }.build()
        val fields = arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END, CalendarContract.Instances.TITLE, CalendarContract.Instances.DISPLAY_COLOR,
            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.SELF_ATTENDEE_STATUS)
        return resolver.query(uri, fields, "${CalendarContract.Calendars.VISIBLE} = ?", arrayOf("1"),
            "${CalendarContract.Instances.BEGIN} ASC")?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val event = CalEvent(cursor.getLong(0), java.time.Instant.ofEpochMilli(cursor.getLong(1)),
                        java.time.Instant.ofEpochMilli(cursor.getLong(2)), cursor.getString(3).orEmpty(), cursor.getInt(4),
                        cursor.getInt(5) != 0, cursor.getInt(6) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED)
                    if (event.occursOn(date, zone)) add(event)
                }
            }
        } ?: throw IllegalStateException("Calendar provider unavailable")
    }
}
