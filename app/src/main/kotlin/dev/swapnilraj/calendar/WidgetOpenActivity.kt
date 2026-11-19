package dev.swapnilraj.calendar

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import dev.swapnilraj.calendar.android.widgets.*
import dev.swapnilraj.calendar.ui.WidgetOpenContent

/** Private, read-only destination independent of the app's calendar filters and open editor. */
class WidgetOpenActivity : ComponentActivity() {
    private var route by mutableStateOf<AgendaWidgetRoute.OpenEvent?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        accept(intent)
        setContent { WidgetOpenContent(application as CalendarApp, route, ::finish) }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        accept(intent)
    }
    private fun accept(intent: Intent?) {
        route = AgendaWidgetIntents.decode(this, intent, ComponentName(this, WidgetOpenActivity::class.java)) as? AgendaWidgetRoute.OpenEvent
    }
}
