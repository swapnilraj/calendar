package dev.swapnilraj.calendar.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.swapnilraj.calendar.CalendarApp
import dev.swapnilraj.calendar.R
import dev.swapnilraj.calendar.android.widgets.*
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.CalendarPreferenceState
import dev.swapnilraj.calendar.ui.events.EventDetailsStateSheet
import dev.swapnilraj.calendar.ui.settings.DisplayZoneOption
import java.time.ZoneId
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine

@Composable
internal fun WidgetOpenContent(app: CalendarApp, route: AgendaWidgetRoute.OpenEvent?, onDismiss: () -> Unit) {
    val preferences by app.settings.calendarPreferences.collectAsStateWithLifecycle(initialValue = CalendarPreferenceState.Loading)
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var presentationEpoch by remember { mutableIntStateOf(0) }
    var started by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> started = true
                Lifecycle.Event.ON_STOP -> { started = false; presentationEpoch++ }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    CalendarPreferenceTheme(preferences) {
        Surface(Modifier.fillMaxSize()) {
            if (route == null) EventDetailsStateSheet(CalendarReadState.Unavailable, onDismiss, {}, {})
            else PermissionGate {
                val options = (preferences as? CalendarPreferenceState.Ready)?.value?.options
                val zone = (options?.displayZone as? DisplayZoneOption.Fixed)?.zone ?: ZoneId.systemDefault()
                val state by key(route, preferences, zone, lifecycle, presentationEpoch) {
                    produceState<CalendarReadState<EventDetails>>(CalendarReadState.Loading, app) {
                        if (preferences == CalendarPreferenceState.Loading) return@produceState
                        if (preferences !is CalendarPreferenceState.Ready) {
                            value = CalendarReadState.Unavailable
                            return@produceState
                        }
                        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                            try {
                                combine(app.calendarReader.observeCalendars(), app.agendaWidgetFramework.health,
                                    app.agendaWidgetRuntime.changes) { metadata, _, _ -> metadata }
                                    .collectLatest { metadata ->
                                        value = CalendarReadState.Loading
                                        value = when (metadata) {
                                            CalendarReadState.Loading -> CalendarReadState.Loading
                                            CalendarReadState.PermissionRequired -> CalendarReadState.PermissionRequired
                                            CalendarReadState.LimitExceeded -> CalendarReadState.LimitExceeded
                                            CalendarReadState.Unavailable -> CalendarReadState.Unavailable
                                            is CalendarReadState.Ready -> app.readWidgetDetails(route)
                                        }
                                    }
                            } finally { value = CalendarReadState.Loading }
                        }
                    }
                }
                EventDetailsStateSheet(if (started) state else CalendarReadState.Loading, onDismiss,
                    onRetry = { app.calendarReader.refresh(); app.settings.refreshPreferences(); app.agendaWidgetFramework.onResume() },
                    onOpenLink = { link ->
                        val uri = Uri.parse(link)
                        if (uri.scheme?.lowercase() in setOf("http", "https", "geo", "mailto", "tel")) {
                            try { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                            catch (_: ActivityNotFoundException) { Toast.makeText(context, R.string.navigation_no_link_handler, Toast.LENGTH_SHORT).show() }
                            catch (_: SecurityException) { Toast.makeText(context, R.string.navigation_link_unavailable, Toast.LENGTH_SHORT).show() }
                        }
                    }, displayZone = zone, use24Hour = DateFormat.is24HourFormat(context))
            }
        }
    }
}

/** Fresh proof both sides of the details read, plus the rendered namespace on its actual result. */
internal suspend fun CalendarApp.readWidgetDetails(route: AgendaWidgetRoute.OpenEvent): CalendarReadState<EventDetails> {
    when (agendaWidgetServices.validateRoute(route)) {
        AgendaWidgetRouteResult.PermissionRequired -> return CalendarReadState.PermissionRequired
        is AgendaWidgetRouteResult.Ready -> Unit
        else -> return CalendarReadState.Unavailable
    }
    val read = calendarReader.readDetails(route.ref)
    if (read !is CalendarReadState.Ready) return read
    val identity = read.value.calendar?.identity ?: return CalendarReadState.Unavailable
    if (read.value.occurrence.ref != route.ref || route.calendarFingerprint == null ||
        widgetCalendarFingerprint(identity) != route.calendarFingerprint) return CalendarReadState.Unavailable
    return when (agendaWidgetServices.validateRoute(route)) {
        is AgendaWidgetRouteResult.Ready -> read
        AgendaWidgetRouteResult.PermissionRequired -> CalendarReadState.PermissionRequired
        else -> CalendarReadState.Unavailable
    }
}
