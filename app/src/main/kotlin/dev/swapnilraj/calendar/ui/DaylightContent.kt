package dev.swapnilraj.calendar.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.text.format.DateFormat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.swapnilraj.calendar.CalendarApp
import dev.swapnilraj.calendar.data.WeatherState
import dev.swapnilraj.calendar.weather.daySummary
import dev.swapnilraj.calendar.weather.hoursForDay
import java.time.ZonedDateTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DaylightContent(app: CalendarApp) {
    val context = LocalContext.current
    val noLinkHandler = stringResource(dev.swapnilraj.calendar.R.string.navigation_no_link_handler)
    val linkUnavailable = stringResource(dev.swapnilraj.calendar.R.string.navigation_link_unavailable)
    val scope = rememberCoroutineScope()
    val savedHeight by app.settings.hourHeight.collectAsStateWithLifecycle(initialValue = null)
    val calendarSelection by app.settings.calendarSelection.collectAsStateWithLifecycle(initialValue = null)
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) { now = ZonedDateTime.now(); delay(60_000 - System.currentTimeMillis() % 60_000) }
    }
    val snackbar = remember { SnackbarHostState() }
    val weather by app.weather.state.collectAsStateWithLifecycle()
    var locationAllowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) }
    var lastWeatherPermission by remember { mutableStateOf<Boolean?>(null) }
    val locationRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val previouslyAllowed = locationAllowed
        locationAllowed = granted
        if (granted && previouslyAllowed) scope.launch { app.weather.refresh(now.zone, force = true) }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                now = ZonedDateTime.now()
                app.calendarReader.refresh()
                locationAllowed = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Surface(Modifier.fillMaxSize()) {
        PermissionGate {
            LaunchedEffect(now.zone, locationAllowed) {
                var changedPermission = lastWeatherPermission != null && lastWeatherPermission != locationAllowed
                lastWeatherPermission = locationAllowed
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    app.weather.refresh(now.zone, force = changedPermission)
                    changedPermission = false
                    while (true) { delay(30 * 60_000L); app.weather.refresh(now.zone) }
                }
            }
            savedHeight?.let { initialHeight ->
              calendarSelection?.let { selectedCalendars ->
                Box(Modifier.fillMaxSize()) {
                    LiveCalendarDay(app.calendarReader, selectedCalendars, now, DateFormat.is24HourFormat(context), initialHeight,
                        onSaveHourHeight = { height -> scope.launch { app.settings.setHourHeight(height) } },
                        onOpenLink = { value ->
                            val uri = Uri.parse(value)
                            if (uri.scheme?.lowercase() in setOf("http", "https", "geo", "mailto", "tel")) {
                                try { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                                catch (_: ActivityNotFoundException) {
                                    scope.launch { snackbar.showSnackbar(noLinkHandler) }
                                } catch (_: SecurityException) {
                                    scope.launch { snackbar.showSnackbar(linkUnavailable) }
                                }
                            }
                        },
                        actions = { WeatherOptions(weather,
                            onLocation = { locationRequest.launch(Manifest.permission.ACCESS_COARSE_LOCATION) },
                            onRefresh = { scope.launch { app.weather.refresh(now.zone, force = true) } }) },
                        summary = { date ->
                            val summary = remember(weather.forecast, date, now.zone) { daySummary(weather.forecast, date, now.zone) }
                            summary?.let { WeatherSummary(it, weather.stale) }
                        },
                        background = { date ->
                            val hours = remember(weather.forecast, date, now.zone) { hoursForDay(weather.forecast, date, now.zone) }
                            // DayScreen owns the zoom; the layer derives hour height from its measured full-day height.
                            hours?.let { WeatherBackground(it) }
                        })
                    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
                }
              }
            }
        }
    }
}

@Composable
internal fun RowScope.WeatherOptions(weather: WeatherState, onLocation: () -> Unit, onRefresh: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    androidx.compose.foundation.layout.Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(36.dp).semantics { contentDescription = "Weather options" }) {
            Text("⋮", style = MaterialTheme.typography.titleLarge)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            weather.locationLabel?.let { DropdownMenuItem(text = { Text("Weather: $it${if (weather.stale) " (cached)" else ""}") }, onClick = {}, enabled = false) }
            DropdownMenuItem(text = { Text("Use approximate location") }, onClick = { expanded = false; onLocation() })
            DropdownMenuItem(text = { Text("Refresh weather") }, onClick = { expanded = false; onRefresh() })
            DropdownMenuItem(text = { Text("Weather by Open-Meteo · CC BY 4.0") }, onClick = {
                expanded = false
                try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://open-meteo.com/"))) }
                catch (_: ActivityNotFoundException) { /* Attribution remains available in this menu. */ }
            })
        }
    }
}
