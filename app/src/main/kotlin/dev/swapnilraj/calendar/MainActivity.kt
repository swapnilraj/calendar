package dev.swapnilraj.calendar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.swapnilraj.calendar.ui.DaylightContent
import dev.swapnilraj.calendar.ui.theme.DaylightTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DaylightTheme {
                DaylightContent(application as CalendarApp)
            }
        }
    }
}
