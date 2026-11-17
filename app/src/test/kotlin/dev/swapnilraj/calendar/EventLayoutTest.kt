package dev.swapnilraj.calendar

import dev.swapnilraj.calendar.data.CalEvent
import dev.swapnilraj.calendar.ui.*
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class EventLayoutTest {
    private val date=LocalDate.of(2026,11,17)
    private val zone=ZoneId.of("Europe/London")
    private fun event(id: Long,start: Double,end: Double) = TimedEvent(CalEvent(id,Instant.EPOCH,Instant.EPOCH,"",0),start,end)
    @Test fun transitiveClustersAndColumnReuse() {
        val result=layoutEvents(listOf(event(1,60.0,180.0),event(2,120.0,240.0),event(3,190.0,250.0),event(4,250.0,300.0)),20.0)
        assertEquals(listOf(0,1,0,0),result.map { it.column })
        assertEquals(listOf(2,2,2,1),result.map { it.columns })
    }
    @Test fun sameStartSortsLongerFirstAndShortEventsStillCollide() {
        val result=layoutEvents(listOf(event(1,60.0,60.0),event(2,60.0,120.0),event(3,70.0,71.0)),20.0)
        assertEquals(2L,result[0].timed.event.id)
        assertTrue(result.all { it.columns==3 })
        assertEquals(emptyList<PlacedEvent>(),layoutEvents(emptyList(),20.0))
    }
    @Test fun allDayDatesDoNotShiftInNegativeTimeZones() {
        val allDay=CalEvent(1,Instant.parse("2026-11-17T00:00:00Z"),Instant.parse("2026-11-18T00:00:00Z"),"",0,allDay=true)
        val la=ZoneId.of("America/Los_Angeles")
        assertTrue(allDay.occursOn(date,la));assertFalse(allDay.occursOn(date.minusDays(1),la))
        assertTrue(timedEvents(listOf(allDay),date,la).isEmpty())
    }
    @Test fun midnightClippingAndExclusiveEnd() {
        val e=CalEvent(1,Instant.parse("2026-11-16T23:00:00Z"),Instant.parse("2026-11-17T02:00:00Z"),"",0)
        val timed=timedEvents(listOf(e),date,zone).single()
        assertEquals(0.0,timed.startMinute,0.0);assertEquals(120.0,timed.endMinute,0.0)
        assertFalse(e.copy(end=date.atStartOfDay(zone).toInstant()).occursOn(date,zone))
        assertTrue(e.copy(start=date.atStartOfDay(zone).toInstant(),end=date.atStartOfDay(zone).toInstant()).occursOn(date,zone))
    }
}
