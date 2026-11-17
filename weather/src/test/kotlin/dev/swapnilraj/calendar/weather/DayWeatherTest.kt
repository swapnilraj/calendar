package dev.swapnilraj.calendar.weather

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class DayWeatherTest {
    private val date=LocalDate.of(2026,11,17)
    private val utc=ZoneId.of("UTC")
    private fun forecast(date: LocalDate=this.date, n: Int=48, zone: ZoneId=utc, change: (Int,ForecastHour)->ForecastHour={_,h->h}) =
        (0 until n).map { i -> change(i,ForecastHour(date.atStartOfDay(zone).toInstant().plusSeconds(i*3600L),3,7.0,5.0,0.0,true)) }

    @Test fun mappingIncludesNextMidnightAndRejectsMissingSamples() {
        val f=forecast()
        assertEquals(25,hoursForDay(f,date,utc)!!.size)
        assertNull(hoursForDay(f.filterIndexed { i,_->i!=12 },date,utc))
        assertNull(hoursForDay(emptyList(),date,utc))
        assertNotNull(hoursForDay(f.map { it.copy(time=it.time.plusSeconds(1800)) },date,utc))
        assertNull(hoursForDay(f.map { it.copy(time=it.time.plusSeconds(1801)) },date,utc))
    }

    @Test fun londonDstUsesClockRowsAndActualNextMidnight() {
        val london=ZoneId.of("Europe/London")
        val spring=LocalDate.of(2026,3,29);val autumn=LocalDate.of(2026,10,25)
        for ((d,duration) in listOf(spring to 23L,autumn to 25L)) {
            val instants=hourInstants(d,london)
            assertEquals(25,instants.size)
            assertEquals(duration,Duration.between(instants.first(),instants.last()).toHours())
            val mapped=hoursForDay(forecast(d,27,london) { i,h->h.copy(tempC=i.toDouble()) },d,london)!!
            assertEquals(duration.toDouble(),mapped[24].tempC,0.0)
        }
        assertEquals(hourInstants(spring,london)[1],hourInstants(spring,london)[2])
        assertEquals(Instant.parse("2026-10-25T01:00:00Z"),hourInstants(autumn,london)[1])
    }

    @Test fun wetTintAndTextureAlignOnBlockBeforeStamp() {
        val hours=List(25) { i->DayHour(condition=if(i==13) Condition.RAIN else Condition.CLOUDY,precipMm=if(i==13)3.0 else 0.0) }
        val wet=wetnessAtLines(hours);val texture=textureFor(hours)!!
        val full=precipIntensity(hours[13]).rain
        assertEquals(listOf(12,13),wet.indices.filter { wet[it].rain>0 })
        assertEquals(full/2,wet[12].rain,1e-9);assertEquals(full/2,wet[13].rain,1e-9)
        assertEquals(full,texture.blocks[12].rain,1e-9)
        assertEquals(full,texture.rowWetness(12.5*64,64.0).rain,1e-9)
        assertEquals(0.0,texture.rowWetness(11.5*64,64.0).rain,1e-9)
        assertEquals(full/2,texture.rowWetness(12.0*64,64.0).rain,1e-9)
        assertEquals(texture.blocks.first(),texture.rowWetness(-100.0,64.0))
        assertEquals(texture.blocks.last(),texture.rowWetness(2000.0,64.0))
        assertEquals(97,gradientStops(hours,WeatherTheme.DARK).size)
        assertNull(textureFor(List(25) { DayHour() }))
        assertThrows(IllegalArgumentException::class.java) { textureFor(listOf(DayHour())) }
    }

    @Test fun summaryRespectsLocalDaysAndPartialEdges() {
        val f=forecast()
        assertNotNull(daySummary(f,date,utc));assertNotNull(daySummary(f,date.plusDays(1),utc))
        val singapore=ZoneId.of("Asia/Singapore")
        assertNull(daySummary(f,date,singapore))
        assertNotNull(daySummary(f,date.plusDays(1),singapore))
        assertNull(daySummary(f,date.plusDays(2),singapore))
    }

    @Test fun lastingSeveritySingleStormDaytimeBasisHighLowAndRainMaximum() {
        fun summary(change: (Int,ForecastHour)->ForecastHour)=daySummary(forecast(n=24,change=change),date,utc)!!
        assertEquals(Condition.RAIN,summary { i,h->h.copy(code=if(i in 10..12)63 else 3) }.condition)
        assertEquals(Condition.CLOUDY,summary { i,h->h.copy(code=if(i==11)63 else 3) }.condition)
        assertEquals(Condition.THUNDERSTORM,summary { i,h->h.copy(code=if(i==11)95 else 0) }.condition)
        assertEquals(Condition.CLEAR,summary { i,h->h.copy(code=if(i in 8..16)0 else 63,isDay=i in 8..16) }.condition)
        val s=summary { i,h->h.copy(tempC=when(i){5->2.0;14->13.0;else->7.0},precipProbability=if(i==20)85.0 else 5.0) }
        assertEquals(13.0,s.high,0.0);assertEquals(2.0,s.low,0.0);assertEquals(85.0,s.precipProbability!!,0.0)
        assertNull(summary { _,h->h.copy(precipProbability=null) }.precipProbability)
    }
}
