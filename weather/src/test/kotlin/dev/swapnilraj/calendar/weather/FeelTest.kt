package dev.swapnilraj.calendar.weather

import org.junit.Assert.*
import org.junit.Test

// Port of desktop bands.test.ts / feel.test.ts. Celsius-only request makes conversion unnecessary.
class FeelTest {
    private fun h(c: Condition = Condition.CLOUDY, temp: Double = 17.0, day: Boolean = true,
        mm: Double? = null, p: Double? = 0.0) = DayHour(c, p, mm, day, temp)

    @Test fun skyAndAbsoluteTemperatureBands() {
        assertEquals(listOf(SkyBand.DARK, SkyBand.BRIGHT, SkyBand.MIXED, SkyBand.OVERCAST, SkyBand.OVERCAST, SkyBand.OVERCAST, SkyBand.DARK),
            listOf(h(Condition.CLEAR, day=false), h(Condition.CLEAR), h(Condition.PARTLY_CLOUDY), h(), h(Condition.RAIN), h(Condition.FOG), h(Condition.THUNDERSTORM)).map(::skyBand))
        assertEquals(listOf(TempBand.FREEZING, TempBand.FREEZING, TempBand.COLD, TempBand.COLD, TempBand.COOL, TempBand.COOL, TempBand.MILD, TempBand.MILD, TempBand.WARM, TempBand.WARM, TempBand.HOT),
            listOf(-3.0,0.0,1.0,7.0,8.0,14.0,15.0,20.0,21.0,27.0,28.0).map(::tempBand))
    }

    @Test fun skyBrightnessAndTemperatureCast() {
        fun t(h: DayHour) = tintFor(h, WeatherTheme.DARK)
        fun lum(h: DayHour) = t(h).let { (it.r + it.g + it.b) * it.a }
        assertTrue(lum(h(Condition.CLEAR)) > lum(h()))
        assertTrue(lum(h()) > lum(h(Condition.CLEAR, day=false)))
        val cold=t(h(Condition.CLEAR,2.0)); val mild=t(h(Condition.CLEAR)); val warm=t(h(Condition.CLEAR,26.0)); val ice=t(h(Condition.CLEAR,-4.0))
        assertTrue(cold.r < mild.r && cold.b > mild.b)
        assertTrue(warm.b < mild.b)
        assertTrue(ice.b - ice.r > cold.b - cold.r)
        val dry=t(h(temp=10.0)); val wet=t(h(Condition.RAIN,10.0,p=90.0))
        assertTrue(wet.b-wet.r > dry.b-dry.r && wet.a > dry.a)
        assertEquals(Rgba(255.0,222.0,140.0,0.13), t(h(Condition.CLEAR)))
        assertTrue(tintFor(h(Condition.THUNDERSTORM), WeatherTheme.LIGHT).a <= 0.30)
    }

    @Test fun intensityOnlyCountsWetConditionsAndUsesProbabilityFallback() {
        assertEquals(Wetness(), precipIntensity(h(p=80.0, mm=2.0)))
        fun r(c: Condition=Condition.RAIN,p: Double?=90.0) = precipIntensity(h(c,p=p)).rain
        assertEquals(0.0,r(p=5.0),0.0)
        assertTrue(r(p=90.0) > r(p=30.0))
        assertTrue(r(Condition.DRIZZLE) < r())
        assertTrue(r(p=null) > 0)
        assertTrue(precipIntensity(h(Condition.SNOW,p=80.0)).snow > 0)
    }

    @Test fun measuredAmountWinsAndUsesSquareRootScale() {
        fun r(mm: Double,p: Double=100.0) = precipIntensity(h(Condition.RAIN,mm=mm,p=p)).rain
        assertEquals(0.0,r(0.02),0.0)
        val values=listOf(0.1,0.5,1.4,4.0,6.0).map { r(it) }
        assertTrue(values.first()>=0.12 && values.zipWithNext().all { (a,b)->a<b })
        assertEquals(1.0,r(40.0),0.0)
        assertTrue(r(5.0,20.0) > r(0.1))
        assertEquals(kotlin.math.sqrt(0.5),precipIntensity(h(Condition.SNOW,mm=1.5)).snow,1e-9)
    }

    @Test fun readoutWording() {
        fun label(c: Condition,mm: Double?) = precipLabel(h(c,mm=mm,p=80.0))
        assertNull(label(Condition.CLOUDY,0.0)); assertNull(label(Condition.RAIN,0.0))
        listOf(Triple(Condition.DRIZZLE,0.2,"Drizzle 0.2 mm"),Triple(Condition.RAIN,1.4,"Light rain 1.4 mm"),Triple(Condition.RAIN,4.0,"Rain 4.0 mm"),Triple(Condition.RAIN,9.0,"Heavy rain 9.0 mm"),Triple(Condition.THUNDERSTORM,3.0,"Thunderstorm 3.0 mm"),Triple(Condition.SNOW,0.8,"Light snow 0.8 mm"))
            .forEach { (c,mm,want)->assertEquals(want,label(c,mm)) }
        assertEquals("Rain",label(Condition.RAIN,null))
    }

    @Test fun oklabRoundTripsEndpointsAndPremultipliedTransparency() {
        listOf(Rgba(255.0,0.0,0.0,0.4),Rgba(0.0,0.0,255.0,1.0),Rgba(148.0,156.0,168.0,0.12)).forEach { rgb ->
            val back=oklabToRgb(rgbToOklab(rgb),rgb.a)
            assertEquals(rgb.r,back.r,0.001);assertEquals(rgb.g,back.g,0.001);assertEquals(rgb.b,back.b,0.001)
            assertEquals(rgb,lerpOklab(rgb,Rgba(0.0,0.0,0.0,0.0),0.0))
        }
        val red=Rgba(255.0,0.0,0.0,1.0); val transparent=Rgba(0.0,0.0,255.0,0.0)
        assertEquals(transparent,lerpOklab(red,transparent,1.0))
        val half=lerpOklab(red,transparent,0.5)
        assertEquals(255.0,half.r,0.001); assertEquals(0.0,half.b,0.001); assertEquals(0.5,half.a,0.0)
    }
}
