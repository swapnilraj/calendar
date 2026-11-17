package dev.swapnilraj.calendar.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/** The app's only HTTP entry point. Redirects are disabled so requests stay on Open-Meteo. */
fun fetchWeather(url: String): String {
    val uri = URI(url)
    require(uri.scheme == "https" && uri.host in setOf("api.open-meteo.com", "geocoding-api.open-meteo.com"))
    val connection = uri.toURL().openConnection() as HttpURLConnection
    return try {
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.requestMethod = "GET"
        if (connection.responseCode !in 200..299) throw IOException("Weather unavailable")
        connection.inputStream.bufferedReader().use { it.readText() }
    } finally { connection.disconnect() }
}
