package dev.swapnilraj.calendar.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.swapnilraj.calendar.CalendarApp
import dev.swapnilraj.calendar.R
import dev.swapnilraj.calendar.android.reminders.*
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.reminders.*
import dev.swapnilraj.calendar.ui.reminders.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Actual settings IO and explicit permission actions; no display filter enters reminder selection. */
@Composable
internal fun ReminderSettingsWorkflow(app: CalendarApp, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val metadata by app.calendarReader.observeCalendars().collectAsStateWithLifecycle(initialValue = CalendarReadState.Loading)
    val health by app.reminderRuntime.health.collectAsStateWithLifecycle()
    var epoch by rememberSaveable { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ReminderPreferenceSave?>(null) }
    var permissionsOpen by rememberSaveable { mutableStateOf(false) }
    var finishAfterSetup by rememberSaveable { mutableStateOf(false) }
    var permissions by remember { mutableStateOf(ReminderPermissionReader(context).read()) }
    fun refreshPermissions() {
        permissions = ReminderPermissionReader(context).read()
        app.reminderRuntime.onResume()
    }
    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refreshPermissions(); app.calendarReader.refresh() }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refreshPermissions() }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refreshPermissions() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    fun openSystemSettings(intent: Intent) {
        try { context.startActivity(intent) }
        catch (_: ActivityNotFoundException) { Toast.makeText(context, R.string.reminder_workflow_settings_unavailable, Toast.LENGTH_SHORT).show() }
        catch (_: SecurityException) { Toast.makeText(context, R.string.reminder_workflow_settings_unavailable, Toast.LENGTH_SHORT).show() }
    }
    key(epoch) {
        // Rekeying starts a fresh uncached collector at Loading as well as a new draft. A reload can
        // never seed its new expected value from the previous collector's remembered Ready snapshot.
        val preferences by app.settings.reminderPreferences.collectAsStateWithLifecycle(initialValue = ReminderPreferenceState.Loading)
        ReminderCalendarSettingsSheet(preferences,
            if (permissions.calendarAccess) metadata else CalendarReadState.PermissionRequired,
            onSave = { expected, updated ->
                if (!saving) {
                    saving = true
                    scope.launch {
                        try {
                            var saved = app.settings.saveReminderSettings(expected, updated)
                            if (saved == ReminderPreferenceSave.Unavailable) {
                                // A lost storage ACK does not prove failure. Preferences are idempotent;
                                // readback can confirm the exact requested current settings safely.
                                val current = try { app.settings.readReminderSettings() }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { null }
                                if (current == updated) saved = ReminderPreferenceSave.Saved(updated)
                            }
                            result = saved
                            if (saved is ReminderPreferenceSave.Saved) {
                                refreshPermissions()
                                if (saved.settings.enabled && !permissions.ready) {
                                    finishAfterSetup = true
                                    permissionsOpen = true
                                } else onDismiss()
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { result = ReminderPreferenceSave.Unavailable }
                        finally { saving = false }
                    }
                }
            }, onDismiss = { if (!saving) onDismiss() },
            onPermissions = { if (!saving) { finishAfterSetup = false; refreshPermissions(); permissionsOpen = true } },
            onReloadSettings = { if (!saving) { result = null; app.settings.refreshPreferences(); epoch++ } },
            onRetryCalendars = app.calendarReader::refresh, saving = saving, saveResult = result)
    }
    if (permissionsOpen) {
        fun close() { permissionsOpen = false; if (finishAfterSetup) onDismiss() }
        Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    TextButton(onClick = ::close) { Text(stringResource(if (finishAfterSetup) R.string.reminder_workflow_done else R.string.reminder_workflow_back)) }
                    Text(stringResource(when (health.status) {
                        ReminderRuntimeStatus.UNKNOWN -> R.string.reminder_workflow_checking
                        ReminderRuntimeStatus.READY -> R.string.reminder_workflow_ready
                        ReminderRuntimeStatus.DISABLED -> R.string.reminder_workflow_disabled
                        ReminderRuntimeStatus.PERMISSION_REQUIRED -> R.string.reminder_workflow_permission
                        ReminderRuntimeStatus.DEGRADED -> R.string.reminder_workflow_degraded
                        ReminderRuntimeStatus.UNAVAILABLE -> R.string.reminder_workflow_unavailable
                    }), Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                    ReminderSetupScreen(permissions,
                        onCalendarPermission = { calendarPermission.launch(Manifest.permission.READ_CALENDAR) },
                        onNotificationPermission = { if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) },
                        onNotificationSettings = { openSystemSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) },
                        onExactAlarmSettings = { if (Build.VERSION.SDK_INT >= 31) openSystemSettings(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) },
                        onTestNotification = {
                            val message = when (ReminderTestNotification(context).post()) {
                                ReminderTestNotificationResult.ACCEPTED -> R.string.reminder_workflow_test_accepted
                                ReminderTestNotificationResult.PERMISSION_REQUIRED -> R.string.reminder_workflow_test_permission
                                ReminderTestNotificationResult.UNAVAILABLE -> R.string.reminder_workflow_test_unavailable
                            }
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                            refreshPermissions()
                        }, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
