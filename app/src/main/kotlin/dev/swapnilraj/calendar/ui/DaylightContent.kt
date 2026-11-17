package dev.swapnilraj.calendar.ui

import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Intent
import android.provider.CalendarContract
import android.text.format.DateFormat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.swapnilraj.calendar.CalendarApp
import java.time.ZonedDateTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DaylightContent(app: CalendarApp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val savedHeight by app.settings.hourHeight.collectAsStateWithLifecycle(initialValue = null)
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) { now = ZonedDateTime.now(); delay(60_000 - System.currentTimeMillis() % 60_000) }
    }
    val snackbar = remember { SnackbarHostState() }
    Surface(Modifier.fillMaxSize()) {
        PermissionGate {
            savedHeight?.let { initialHeight ->
                Scaffold(snackbarHost = { SnackbarHost(snackbar) }, contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0)) { _ ->
                    DayScreen(now, DateFormat.is24HourFormat(context), initialHeight, app.calendars::events,
                        onSaveHourHeight = { height -> scope.launch { app.settings.setHourHeight(height) } },
                        onEventClick = { event ->
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.id))
                                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.start.toEpochMilli())
                                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.end.toEpochMilli()))
                            } catch (_: ActivityNotFoundException) {
                                scope.launch { snackbar.showSnackbar("No calendar app is available to open this event") }
                            } catch (_: SecurityException) {
                                scope.launch { snackbar.showSnackbar("This event couldn’t be opened") }
                            }
                        })
                }
            }
        }
    }
}
