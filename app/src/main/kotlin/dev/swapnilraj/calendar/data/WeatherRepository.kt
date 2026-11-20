package dev.swapnilraj.calendar.data

import dev.swapnilraj.calendar.weather.*
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class WeatherState(
    val forecast: List<ForecastHour> = emptyList(),
    val locationLabel: String? = null,
    val stale: Boolean = false,
    val zone: ZoneId? = null,
)

class WeatherRepository(
    private val resolve: (ZoneId) -> WeatherLocation?,
    private val fetch: (Coordinates) -> List<ForecastHour>,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private data class Cache(val zone: ZoneId, val request: String, val state: WeatherState, val at: Long)
    private var cache: Cache? = null
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(WeatherState())
    val state = mutableState.asStateFlow()

    suspend fun refresh(zone: ZoneId, force: Boolean = false) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val previous = cache
            if (!force && previous != null && previous.zone == zone && clock() - previous.at < 30 * 60_000) return@withLock
            var location: WeatherLocation? = null
            try {
                location = resolve(zone)
                if (location == null) { mutableState.value = WeatherState(); return@withLock }
                val forecast = fetch(location.coordinates)
                if (forecast.isEmpty()) throw WeatherResponseException()
                val current = WeatherState(forecast, location.label, zone = zone)
                cache = Cache(zone, forecastUrl(location.coordinates), current, clock())
                mutableState.value = current
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A stale forecast is useful only for the same place. Never attach an old city's data to a new one.
                mutableState.value = if (previous != null && previous.zone == zone &&
                    (location == null || previous.request == forecastUrl(location.coordinates))) previous.state.copy(stale = true)
                    else WeatherState()
            }
        }
    }
}
