package dev.swapnilraj.calendar

import dev.swapnilraj.calendar.data.*
import dev.swapnilraj.calendar.weather.*
import java.io.IOException
import java.time.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class WeatherRepositoryTest {
    @Test fun cachesForThirtyMinutesAndRetainsStaleOnFailure() = runBlocking {
        var clock=0L;var calls=0;var offline=false
        val hour=ForecastHour(Instant.EPOCH,3,10.0,0.0,0.0,true)
        val repository=WeatherRepository({WeatherLocation(Coordinates(51.5,-0.12),"London")}, {
            calls++;if(offline) throw IOException();listOf(hour)
        },{clock})
        val zone=ZoneId.of("Europe/London")
        repository.refresh(zone)
        assertEquals(zone,repository.state.value.zone)
        assertEquals(listOf(hour),repository.state.value.forecast)
        clock=30*60_000L-1;repository.refresh(zone);assertEquals(1,calls)
        clock++;offline=true;repository.refresh(zone)
        assertEquals(2,calls);assertTrue(repository.state.value.stale)
        assertEquals(listOf(hour),repository.state.value.forecast)
    }
    @Test fun missingLocationOfflineAndNewLocationNeverUseWrongForecast() = runBlocking {
        val zone=ZoneId.of("Europe/London")
        val noLocation=WeatherRepository({null},{error("Must not fetch")})
        noLocation.refresh(zone);assertTrue(noLocation.state.value.forecast.isEmpty())
        var location=Coordinates(51.5,-0.12);var offline=false
        val repository=WeatherRepository({WeatherLocation(location,"Fixture")},{
            if(offline) throw IOException()
            listOf(ForecastHour(Instant.EPOCH,0,12.0,null,null,true))
        })
        repository.refresh(zone);assertFalse(repository.state.value.forecast.isEmpty())
        location=Coordinates(40.0,-74.0);offline=true
        repository.refresh(zone,force=true);assertTrue(repository.state.value.forecast.isEmpty())
        repository.refresh(ZoneId.of("America/New_York"),force=true);assertTrue(repository.state.value.forecast.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { fetchWeather("https://example.com/") }
        Unit
    }
}
