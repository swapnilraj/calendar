package dev.swapnilraj.calendar.android.navigation

import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.calendar.CalendarReader
import dev.swapnilraj.calendar.data.calendar.EventNavigationInfo

/** The shared bounded query engine, never a second provider/range implementation. */
interface IncomingCalendarSource {
    suspend fun readEventInfo(eventId: Long): CalendarReadState<EventNavigationInfo?>
    suspend fun readSnapshot(request: CalendarRequest): CalendarReadState<CalendarSnapshot>
    suspend fun readDetails(ref: EventRef): CalendarReadState<EventDetails>
}

class ReaderIncomingCalendarSource(private val reader: CalendarReader) : IncomingCalendarSource {
    override suspend fun readEventInfo(eventId: Long) = reader.readEventNavigationInfo(eventId)
    override suspend fun readSnapshot(request: CalendarRequest) = reader.readSnapshot(request)
    override suspend fun readDetails(ref: EventRef) = reader.readDetails(ref)
}
