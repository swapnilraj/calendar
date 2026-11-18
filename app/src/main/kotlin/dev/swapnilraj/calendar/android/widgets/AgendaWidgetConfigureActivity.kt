package dev.swapnilraj.calendar.android.widgets

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.swapnilraj.calendar.R
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
import dev.swapnilraj.calendar.ui.widgets.WidgetSetupChoice
import dev.swapnilraj.calendar.ui.widgets.WidgetSetupSheet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Exported only for the launcher's configuration action. Membership, identities and durable
 * lifecycle proof authorize Save; launch extras alone never authorize a configuration write.
 */
class AgendaWidgetConfigureActivity : ComponentActivity() {
    private var metadata by mutableStateOf<CalendarReadState<List<CalendarInfo>>>(CalendarReadState.Loading)
    private var environment by mutableStateOf<CalendarReadState<AgendaWidgetEnvironment>>(CalendarReadState.Loading)
    private var initial by mutableStateOf<WidgetSetupChoice?>(null)
    private val sessionIdentities = mutableMapOf<CalendarId, CalendarIdentity>()
    private var formStale = false
    private val uiSession = java.util.UUID.randomUUID().toString()
    private var setupRevision by mutableIntStateOf(0)
    private var saving by mutableStateOf(false)
    private var saveError by mutableStateOf<String?>(null)
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var metadataJob: kotlinx.coroutines.Job? = null
    private val services get() = (application as? AgendaWidgetApplicationOwner)?.agendaWidgetServices
    private val calendarPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { reload() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        widgetId = decodeWidgetId(intent) ?: run { finish(); return }
        if (services?.isOwnWidget(widgetId) != true) { finish(); return }
        enableEdgeToEdge()
        reload()
        setContent {
            DaylightTheme {
                Surface(Modifier.fillMaxSize()) {
                    val ready = (environment as? CalendarReadState.Ready)?.value
                    val choice = initial
                    val displayedMetadata = metadata
                    if (ready != null && choice != null) key(uiSession, setupRevision) { WidgetSetupSheet(widgetId, choice, displayedMetadata, ready.appSelection,
                        onSave = { save(it, (displayedMetadata as? CalendarReadState.Ready)?.value?.toList()) }, onDismiss = ::finish, onRetryMetadata = ::reload,
                        saving = saving, saveError = saveError,
                        onRequestPermission = { calendarPermission.launch(Manifest.permission.READ_CALENDAR) }) }
                    else Column(Modifier.fillMaxSize().systemBarsPadding()) {
                        Text(stringResource(when (environment) {
                            CalendarReadState.Loading -> R.string.widget_setup_loading
                            CalendarReadState.PermissionRequired -> R.string.widget_setup_permission
                            else -> R.string.widget_unavailable
                        }))
                        if (environment == CalendarReadState.PermissionRequired) TextButton(onClick = {
                            calendarPermission.launch(Manifest.permission.READ_CALENDAR)
                        }) { Text(stringResource(R.string.widget_setup_allow)) }
                        Text(stringResource(R.string.widget_reconfigure_reboot_note))
                        TextButton(onClick = ::reload) { Text(stringResource(R.string.widget_setup_retry)) }
                        TextButton(onClick = ::finish) { Text(stringResource(R.string.widget_setup_cancel)) }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        metadata = if (hasCalendarPermission()) CalendarReadState.Loading else CalendarReadState.PermissionRequired
        reload()
    }
    override fun onStop() {
        metadata = CalendarReadState.Loading
        super.onStop()
    }
    private fun hasCalendarPermission() = checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private fun reload() {
        if (saving) return
        if (formStale) { initial = WidgetSetupChoice(); setupRevision++; sessionIdentities.clear(); formStale = false }
        metadataJob?.cancel()
        metadata = if (hasCalendarPermission()) CalendarReadState.Loading else CalendarReadState.PermissionRequired
        environment = CalendarReadState.Loading
        val owner = services ?: return
        metadataJob = lifecycleScope.launch {
            try {
                environment = owner.environment()
                if (initial == null) {
                    val state = owner.configuration(widgetId)
                    initial = (state as? AgendaWidgetConfigurationState.Ready)?.value
                        ?.takeIf { validWidgetPins(it.selection, it.expectedCalendarIdentities) }?.let {
                        sessionIdentities.putAll(it.expectedCalendarIdentities)
                        WidgetSetupChoice(it.selection, it.privacy)
                    } ?: WidgetSetupChoice() // An unconfigured widget always begins Busy only.
                }
                repeatOnLifecycle(Lifecycle.State.STARTED) { owner.observeCalendars().collect {
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
                        metadata = if (!hasCalendarPermission()) CalendarReadState.PermissionRequired else {
                            if (it is CalendarReadState.Ready && knownIdentityDrift(it.value)) {
                                formStale = true; saveError = getString(R.string.widget_config_stale)
                            }
                            if (formStale) CalendarReadState.Unavailable else it
                        }
                } }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { environment = CalendarReadState.Unavailable; metadata = CalendarReadState.Unavailable }
        }
    }

    private fun knownIdentityDrift(calendars: List<CalendarInfo>): Boolean {
        var changed = false
        calendars.forEach { calendar ->
            val current = calendar.identity
            val prior = sessionIdentities[calendar.id]
            if (prior != null && (prior.account != current.account ||
                (prior.owner != null && current.owner != null && prior.owner != current.owner) ||
                (prior.providerId != null && current.providerId != null && prior.providerId != current.providerId))) changed = true
            else if (prior == null) sessionIdentities[calendar.id] = current
        }
        return changed
    }

    private fun save(choice: WidgetSetupChoice, authorized: List<CalendarInfo>?) {
        if (saving) return
        if (formStale) { saveError = getString(R.string.widget_config_stale); return }
        val owner = services ?: return
        saving = true; saveError = null
        lifecycleScope.launch {
            try {
                val result = owner.configure(widgetId, choice, authorized)
                if (result is WidgetConfigurationSaveResult.Saved && owner.isOwnWidget(widgetId)) {
                    setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                    finish()
                } else saveError = getString(if (result == WidgetConfigurationSaveResult.Stale) R.string.widget_config_stale else R.string.widget_config_save_failed)
            } finally { saving = false }
        }
    }

    companion object {
        const val ACTION_RECONFIGURE = "dev.swapnilraj.calendar.widgets.CONFIGURE"
        @Suppress("DEPRECATION")
        fun decodeWidgetId(intent: Intent?): Int? = try {
            requireNotNull(intent)
            require(intent.action == AppWidgetManager.ACTION_APPWIDGET_CONFIGURE || intent.action == ACTION_RECONFIGURE)
            (intent.extras?.get(AppWidgetManager.EXTRA_APPWIDGET_ID) as? Int)?.takeIf { it > 0 }
        } catch (_: RuntimeException) { null }
    }
}
