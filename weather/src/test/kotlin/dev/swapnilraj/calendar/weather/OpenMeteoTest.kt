package dev.swapnilraj.calendar.weather

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

// Ported from notion-calendar-weather test/wmo.test.ts and test/openMeteo.test.ts.
class OpenMeteoTest {
    @Test fun representativeWmoCodesAndUnknown() {
        val cases = mapOf(0 to Condition.CLEAR, 2 to Condition.PARTLY_CLOUDY, 63 to Condition.RAIN,
            81 to Condition.RAIN, 73 to Condition.SNOW, 95 to Condition.THUNDERSTORM, 1234 to Condition.UNKNOWN)
        cases.forEach { (code, condition) -> assertEquals(condition, describeWmoCode(code).condition) }
        assertEquals("Mainly clear", describeWmoCode(1).label)
    }

    private val body = """{"hourly":{"time":[0,3600,7200],"temperature_2m":[11.6,5,null],"weather_code":[61,3,0],"precipitation_probability":[80,null,0],"precipitation":[1.4,null,0],"is_day":[1,0,1]}}"""

    @Test fun fieldsRoundTempsAndSkipIncompleteHours() {
        val hours = parseHourly(body)
        assertEquals(2, hours.size)
        assertEquals(ForecastHour(Instant.EPOCH, 61, 12.0, 80.0, 1.4, true), hours[0])
        assertEquals(Condition.RAIN, hours[0].condition)
        assertFalse(hours[1].isDay)
        assertNull(hours[1].precipMm)
    }

    @Test fun optionalArraysCanBeAbsent() {
        val h = parseHourly("""{"hourly":{"time":[0],"temperature_2m":[5],"weather_code":[3]}}""")[0]
        assertNull(h.precipMm)
        assertNull(h.precipProbability)
    }

    @Test fun malformedAndMismatchedArraysAreRejected() {
        listOf("invalid", "{}", "[]", """{"hourly":{"time":[]}}""",
            body.replace("[1.4,null,0]", "[1.4]"), body.replace("[11.6,5,null]", "[5]"))
            .forEach { assertThrows(WeatherResponseException::class.java) { parseHourly(it) } }
    }

    @Test fun requestsRoundedUtcUnixMetricHourlyDataWithFakeFetch() {
        var requested = ""
        val client = OpenMeteo { url -> requested = url; body }
        assertEquals(2, client.forecast(Coordinates(51.5074, -0.1278)).size)
        listOf("latitude=51.51", "longitude=-0.13", "timezone=GMT", "timeformat=unixtime",
            "past_days=1", "forecast_days=16", "temperature_unit=celsius",
            "hourly=temperature_2m,weather_code,precipitation_probability,precipitation,is_day")
            .forEach { assertTrue(requested, requested.contains(it)) }
    }

    @Test fun geocodingAndZoneCity() {
        assertEquals("New York", cityForZone("America/New_York"))
        assertNull(cityForZone("UTC"))
        assertTrue(geocodingUrl("New York").contains("name=New+York"))
        assertEquals(Coordinates(51.5, -0.12), parseGeocoding("""{"results":[{"latitude":51.5,"longitude":-0.12}]}"""))
        assertNull(parseGeocoding("{}"))
        assertNull(parseGeocoding("""{"results":[{"bogus":true}]}"""))
        assertThrows(IllegalArgumentException::class.java) { geocodingUrl("a") }
        assertThrows(java.io.IOException::class.java) { OpenMeteo { throw java.io.IOException() }.forecast(Coordinates(0.0, 0.0)) }
    }
}
