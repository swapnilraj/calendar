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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.swapnilraj.calendar.CalendarApp
import dev.swapnilraj.calendar.R
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.CalendarPreferenceState
import dev.swapnilraj.calendar.data.reminders.ReminderPreferenceState
import dev.swapnilraj.calendar.ui.events.PhoneEventDetails
import dev.swapnilraj.calendar.ui.settings.DisplayZoneOption
import java.time.ZoneId
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine

/** Independent read-only presentation; opening a reminder does not change display filters or drafts. */
@Composable
internal fun ReminderOpenContent(app: CalendarApp, post: ReminderPostIdentity?, onDismiss: () -> Unit) {
    val preferences by app.settings.calendarPreferences.collectAsStateWithLifecycle(initialValue = CalendarPreferenceState.Loading)
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    CalendarPreferenceTheme(preferences) {
        Surface(Modifier.fillMaxSize()) {
            if (post == null) PhoneEventDetails(CalendarReadState.Unavailable, onDismiss, {}, {})
            else PermissionGate {
                val options = (preferences as? CalendarPreferenceState.Ready)?.value?.options
                val deviceZone = ZoneId.systemDefault()
                val zone = when (val selected = options?.displayZone) {
                    is DisplayZoneOption.Fixed -> selected.zone
                    else -> deviceZone
                }
                val state by key(post, preferences, zone, lifecycle) { produceState<CalendarReadState<EventDetails>>(CalendarReadState.Loading, app) {
                    value = CalendarReadState.Loading
                    if (preferences == CalendarPreferenceState.Loading) return@produceState
                    if (preferences !is CalendarPreferenceState.Ready) {
                        value = CalendarReadState.Unavailable
                        return@produceState
                    }
                    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        try {
                            combine(app.calendarReader.observeCalendars(), app.reminderRuntime.health,
                                app.settings.reminderPreferences) { metadata, _, reminders -> metadata to reminders }
                                .collectLatest { (metadata, reminders) ->
                                    value = CalendarReadState.Loading
                                    value = when {
                                        metadata == CalendarReadState.PermissionRequired -> CalendarReadState.PermissionRequired
                                        metadata == CalendarReadState.LimitExceeded -> CalendarReadState.LimitExceeded
                                        metadata == CalendarReadState.Loading || reminders == ReminderPreferenceState.Loading -> CalendarReadState.Loading
                                        metadata !is CalendarReadState.Ready || reminders !is ReminderPreferenceState.Ready -> CalendarReadState.Unavailable
                                        else -> app.reminderOpenResolver.resolve(post)
                                    }
                                }
                        } finally {
                            value = CalendarReadState.Loading
                        }
                    }
                } }
                PhoneEventDetails(state, onDismiss,
                    onRetry = { app.settings.refreshPreferences(); app.calendarReader.refresh(); app.reminderRuntime.onResume() },
                    onOpenLink = { value ->
                        val uri = Uri.parse(value)
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
