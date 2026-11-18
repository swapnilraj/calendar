package dev.swapnilraj.calendar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.swapnilraj.calendar.ui.DaylightContent

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        (application as CalendarApp).reminderRuntime.onResume()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DaylightContent(application as CalendarApp)
        }
    }
}
