package dev.swapnilraj.calendar

import android.os.Bundle
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.swapnilraj.calendar.ui.DaylightContent
import dev.swapnilraj.calendar.ui.CalendarDateNavigation
import dev.swapnilraj.calendar.ui.CalendarDateIntents
import dev.swapnilraj.calendar.ui.WidgetCalendarNavigation
import dev.swapnilraj.calendar.android.widgets.*
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var calendarDateNavigation by mutableStateOf<CalendarDateNavigation?>(null)
    private var handledCalendarDate: String? = null
    private fun acceptCalendarDate(intent: Intent?) { calendarDateNavigation = CalendarDateIntents.decode(this, intent)?.takeIf { it.id != handledCalendarDate } }
    private fun consumeCalendarDate(id: String) { handledCalendarDate = id; if (calendarDateNavigation?.id == id) calendarDateNavigation = null }

    private var widgetNavigation by mutableStateOf<WidgetCalendarNavigation?>(null)
    private var widgetRoute: AgendaWidgetRoute? = null
    private var widgetNonce: String? = null
    private var widgetHandled = false
    private var widgetValidation: Job? = null

    override fun onResume() {
        super.onResume()
        (application as CalendarApp).reminderRuntime.onResume()
        (application as CalendarApp).agendaWidgetFramework.onResume()
        resolveWidgetRoute()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handledCalendarDate = savedInstanceState?.getString("calendar_date_handled")
        acceptCalendarDate(intent)
        setContent {
            DaylightContent(application as CalendarApp, widgetNavigation, ::consumeWidgetNavigation,
                dateNavigation = calendarDateNavigation, onDateNavigationConsumed = ::consumeCalendarDate)
        }
        acceptWidgetIntent(intent, savedInstanceState?.getString(WIDGET_NONCE), savedInstanceState?.getBoolean(WIDGET_HANDLED) ?: false)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptWidgetIntent(intent)
        acceptCalendarDate(intent)
        resolveWidgetRoute()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("calendar_date_handled", handledCalendarDate)
        outState.putString(WIDGET_NONCE, widgetNonce)
        outState.putBoolean(WIDGET_HANDLED, widgetHandled)
        super.onSaveInstanceState(outState)
    }
    override fun onStop() {
        widgetValidation?.cancel()
        widgetNavigation = null
        super.onStop()
    }
    private fun acceptWidgetIntent(intent: Intent?, restoredNonce: String? = null, handled: Boolean = false) {
        widgetValidation?.cancel()
        widgetNavigation = null
        widgetRoute = AgendaWidgetIntents.decode(this, intent, ComponentName(this, MainActivity::class.java))
        widgetNonce = widgetRoute?.let { restoredNonce ?: UUID.randomUUID().toString() }
        widgetHandled = handled && widgetRoute != null
    }
    private fun consumeWidgetNavigation(id: String) {
        if (widgetNonce == id) { widgetHandled = true; widgetNavigation = null }
    }
    private fun resolveWidgetRoute() {
        val route = widgetRoute ?: return
        val nonce = widgetNonce ?: return
        if (widgetHandled || widgetValidation?.isActive == true || widgetNavigation != null) return
        widgetValidation = lifecycleScope.launch {
            val result = (application as CalendarApp).agendaWidgetServices.validateRoute(route)
            if (widgetNonce != nonce || widgetHandled) return@launch
            when (result) {
                is AgendaWidgetRouteResult.SetupRequired -> {
                    consumeWidgetNavigation(nonce)
                    startActivity(Intent(this@MainActivity, AgendaWidgetConfigureActivity::class.java)
                        .setAction(AgendaWidgetConfigureActivity.ACTION_RECONFIGURE)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, result.widgetId))
                }
                is AgendaWidgetRouteResult.Ready -> when (val ready = result.route) {
                    is AgendaWidgetRoute.OpenEvent -> {
                        consumeWidgetNavigation(nonce)
                        startActivity(AgendaWidgetIntents.intent(this@MainActivity,
                            ComponentName(this@MainActivity, WidgetOpenActivity::class.java), ready))
                    }
                    else -> widgetNavigation = WidgetCalendarNavigation(nonce, ready, result.environment)
                }
                AgendaWidgetRouteResult.PermissionRequired -> {
                    // Retain the read-only request for resume after an explicit calendar grant.
                    Toast.makeText(this@MainActivity, R.string.events_permission_required, Toast.LENGTH_SHORT).show()
                }
                else -> {
                    consumeWidgetNavigation(nonce)
                    Toast.makeText(this@MainActivity, R.string.navigation_unavailable, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    private companion object {
        const val WIDGET_NONCE = "widget_navigation_nonce"
        const val WIDGET_HANDLED = "widget_navigation_handled"
    }
}
