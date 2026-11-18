package dev.swapnilraj.calendar

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.swapnilraj.calendar.android.navigation.ReminderOpenDecoder
import dev.swapnilraj.calendar.core.ReminderPostIdentity
import dev.swapnilraj.calendar.ui.ReminderOpenContent

/** A private PendingIntent destination keeps notification details independent of an open editor. */
class ReminderOpenActivity : ComponentActivity() {
    private var post by mutableStateOf<ReminderPostIdentity?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        accept(intent)
        setContent { ReminderOpenContent(application as CalendarApp, post, ::finish) }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        accept(intent)
    }
    override fun onResume() {
        super.onResume()
        (application as CalendarApp).reminderRuntime.onResume()
    }
    private fun accept(intent: Intent?) {
        post = ReminderOpenDecoder.decode(this, intent, ComponentName(this, ReminderOpenActivity::class.java))
    }
}
