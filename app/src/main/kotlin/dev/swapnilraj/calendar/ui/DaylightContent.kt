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
import dev.swapnilraj.calendar.data.CalendarPreferenceState
import dev.swapnilraj.calendar.ui.settings.*
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
import androidx.compose.foundation.isSystemInDarkTheme
import dev.swapnilraj.calendar.data.WeatherState
import dev.swapnilraj.calendar.weather.daySummary
import dev.swapnilraj.calendar.weather.hoursForDay
import java.time.ZonedDateTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DaylightContent(app: CalendarApp) {
    val preferences by app.settings.calendarPreferences.collectAsStateWithLifecycle(initialValue = CalendarPreferenceState.Loading)
    CalendarPreferenceTheme(preferences) { DaylightBody(app, preferences) }
}

@Composable
internal fun CalendarPreferenceTheme(preferences: CalendarPreferenceState, content: @Composable () -> Unit) {
    val options = (preferences as? CalendarPreferenceState.Ready)?.value?.options
    val dark = when (options?.theme ?: SettingsTheme.SYSTEM) {
        SettingsTheme.SYSTEM -> isSystemInDarkTheme()
        SettingsTheme.LIGHT -> false
        SettingsTheme.DARK -> true
    }
    DaylightTheme(dark, content)
}

@Composable
private fun DaylightBody(app: CalendarApp, preferences: CalendarPreferenceState) {
    val options = (preferences as? CalendarPreferenceState.Ready)?.value?.options ?: CalendarSettingsOptions()
    val weatherVisible = preferences is CalendarPreferenceState.Ready && options.weatherVisible
    val context = LocalContext.current
    val noLinkHandler = stringResource(dev.swapnilraj.calendar.R.string.navigation_no_link_handler)
    val linkUnavailable = stringResource(dev.swapnilraj.calendar.R.string.navigation_link_unavailable)
    val preferencesUnavailable = stringResource(dev.swapnilraj.calendar.R.string.navigation_preferences_unavailable)
    val localSaved = stringResource(dev.swapnilraj.calendar.R.string.navigation_local_saved)
    val localDeleted = stringResource(dev.swapnilraj.calendar.R.string.navigation_local_deleted)
    val draftUnavailable = stringResource(dev.swapnilraj.calendar.R.string.navigation_draft_unavailable)
    val scope = rememberCoroutineScope()
    var deviceNow by remember { mutableStateOf(ZonedDateTime.now()) }
    val now = when (val zone = options.displayZone) {
        DisplayZoneOption.Device -> deviceNow
        is DisplayZoneOption.Fixed -> deviceNow.withZoneSameInstant(zone.zone)
    }
    LaunchedEffect(Unit) {
        while (true) { deviceNow = ZonedDateTime.now(); delay(60_000 - System.currentTimeMillis() % 60_000) }
    }
    val snackbar = remember { SnackbarHostState() }
    val writeRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Permission grants never retry an event mutation automatically.
        app.calendarReader.refresh()
    }
    val mutations = remember(app, context, writeRequest) { PermissionAwareMutations(app.calendarWriter,
        { ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED },
        { writeRequest.launch(Manifest.permission.WRITE_CALENDAR) }) }
    val seriesMutations = remember(app, context, writeRequest) { PermissionAwareSeriesMutations(app.calendarSeriesWriter,
        { ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED },
        { writeRequest.launch(Manifest.permission.WRITE_CALENDAR) }) }
    val latestWeather by app.weather.state.collectAsStateWithLifecycle()
    val weather = latestWeather.takeIf { it.zone == deviceNow.zone } ?: WeatherState()
    var locationAllowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) }
    var lastWeatherPermission by remember { mutableStateOf<Boolean?>(null) }
    val locationRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val previouslyAllowed = locationAllowed
        locationAllowed = granted
        if (weatherVisible && granted && previouslyAllowed) scope.launch { app.weather.refresh(deviceNow.zone, force = true) }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                deviceNow = ZonedDateTime.now()
                app.calendarReader.refresh()
                locationAllowed = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Surface(Modifier.fillMaxSize()) {
        PermissionGate {
            LaunchedEffect(deviceNow.zone, locationAllowed, weatherVisible) {
                if (!weatherVisible) return@LaunchedEffect
                var changedPermission = lastWeatherPermission != null && lastWeatherPermission != locationAllowed
                lastWeatherPermission = locationAllowed
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    app.weather.refresh(deviceNow.zone, force = changedPermission)
                    changedPermission = false
                    while (true) { delay(30 * 60_000L); app.weather.refresh(deviceNow.zone) }
                }
            }
                Box(Modifier.fillMaxSize()) {
                    LiveCalendarDay(app.calendarReader, dev.swapnilraj.calendar.core.CalendarSelection.Explicit(emptySet()),
                        now, DateFormat.is24HourFormat(context), 64f,
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
                        actions = { if (weatherVisible) WeatherOptions(weather,
                            onLocation = { locationRequest.launch(Manifest.permission.ACCESS_COARSE_LOCATION) },
                            onRefresh = { scope.launch { app.weather.refresh(deviceNow.zone, force = true) } }) },
                        summary = { date ->
                            val summary = remember(weather.forecast, date, now.zone) { daySummary(weather.forecast, date, now.zone) }
                            if (weatherVisible) summary?.let { WeatherSummary(it, weather.stale, options.weatherUnits) }
                        },
                        settings = app.settings,
                        preferenceOverride = preferences,
                        mutations = mutations,
                        seriesMutations = seriesMutations,
                        drafts = app.calendarLocalStore,
                        onLocalSaved = { scope.launch { snackbar.showSnackbar(localSaved) } },
                        onLocalDeleted = { scope.launch { snackbar.showSnackbar(localDeleted) } },
                        onDraftFailure = { scope.launch { snackbar.showSnackbar(draftUnavailable) } },
                        onPreferenceFailure = { scope.launch { snackbar.showSnackbar(preferencesUnavailable) } },
                        background = { date ->
                            val hours = remember(weather.forecast, date, now.zone) { hoursForDay(weather.forecast, date, now.zone) }
                            // DayScreen owns the zoom; the layer derives hour height from its measured full-day height.
                            if (weatherVisible) hours?.let { WeatherBackground(it) }
                        })
                    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
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
