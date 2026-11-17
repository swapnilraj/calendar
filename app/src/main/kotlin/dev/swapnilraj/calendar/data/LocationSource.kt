package dev.swapnilraj.calendar.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import dev.swapnilraj.calendar.weather.Coordinates
import dev.swapnilraj.calendar.weather.OpenMeteo
import dev.swapnilraj.calendar.weather.cityForZone
import java.time.ZoneId

data class WeatherLocation(val coordinates: Coordinates, val label: String)

class LocationSource(private val context: Context, private val client: OpenMeteo) {
    fun resolve(zone: ZoneId): WeatherLocation? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val manager = context.getSystemService(LocationManager::class.java)
            val location = manager?.getProviders(true)?.mapNotNull { provider ->
                try { manager.getLastKnownLocation(provider) } catch (_: SecurityException) { null }
            }?.maxByOrNull { it.elapsedRealtimeNanos }
            if (location != null) return WeatherLocation(Coordinates(location.latitude, location.longitude), "Approximate location")
        }
        val city = cityForZone(zone.id) ?: return null
        return client.geocode(city)?.let { WeatherLocation(it, city) }
    }
}
