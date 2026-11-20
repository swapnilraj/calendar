package dev.swapnilraj.calendar.ui.reminders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.swapnilraj.calendar.R
import dev.swapnilraj.calendar.android.reminders.ReminderIssue
import dev.swapnilraj.calendar.android.reminders.ReminderPermissionState

/** Pure presentation. Permission prompts/settings open only after an explicit button press. */
@Composable
fun ReminderSetupScreen(
    state: ReminderPermissionState,
    onCalendarPermission: () -> Unit,
    onNotificationPermission: () -> Unit,
    onNotificationSettings: () -> Unit,
    onExactAlarmSettings: () -> Unit,
    onTestNotification: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxSize()) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.reminders_setup_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(if (state.ready) R.string.reminders_permissions_ready else R.string.reminders_setup_needed),
                style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.reminders_setup_explanation))
            for (issue in state.issues) {
                val message = when (issue) {
                    ReminderIssue.CALENDAR_ACCESS -> R.string.reminders_calendar_explanation
                    ReminderIssue.NOTIFICATION_PERMISSION -> R.string.reminders_permission_explanation
                    ReminderIssue.NOTIFICATIONS_DISABLED -> R.string.reminders_disabled_explanation
                    ReminderIssue.CHANNEL_DISABLED -> R.string.reminders_channel_explanation
                    ReminderIssue.EXACT_ALARM_ACCESS -> R.string.reminders_exact_explanation
                }
                val label = when (issue) {
                    ReminderIssue.CALENDAR_ACCESS -> R.string.reminders_allow_calendar
                    ReminderIssue.NOTIFICATION_PERMISSION -> R.string.reminders_allow_notifications
                    ReminderIssue.NOTIFICATIONS_DISABLED, ReminderIssue.CHANNEL_DISABLED -> R.string.reminders_open_notification_settings
                    ReminderIssue.EXACT_ALARM_ACCESS -> R.string.reminders_allow_exact
                }
                val callback = when (issue) {
                    ReminderIssue.CALENDAR_ACCESS -> onCalendarPermission
                    ReminderIssue.NOTIFICATION_PERMISSION -> onNotificationPermission
                    ReminderIssue.NOTIFICATIONS_DISABLED, ReminderIssue.CHANNEL_DISABLED -> onNotificationSettings
                    ReminderIssue.EXACT_ALARM_ACCESS -> onExactAlarmSettings
                }
                Column {
                    Text(stringResource(message))
                    TextButton(onClick = callback) { Text(stringResource(label)) }
                }
            }
            Text(stringResource(R.string.reminders_duplicate_guidance))
            Button(onClick = onTestNotification, enabled = state.canPostNotifications, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.reminders_test))
            }
            TextButton(onClick = onNotificationSettings) {
                Text(stringResource(R.string.reminders_manage_notifications))
            }
        }
    }
}
