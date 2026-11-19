package dev.swapnilraj.calendar

import android.Manifest
import android.content.pm.PackageManager
import android.text.format.DateFormat
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.swapnilraj.calendar.android.navigation.*
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.CalendarPreferenceState
import dev.swapnilraj.calendar.data.CalendarPreferences
import dev.swapnilraj.calendar.ui.*
import dev.swapnilraj.calendar.ui.settings.DisplayZoneOption
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*

private suspend fun CalendarApp.entryPreferences(): CalendarPreferences? =
    (settings.calendarPreferences.first { it != CalendarPreferenceState.Loading } as? CalendarPreferenceState.Ready)?.value
private fun CalendarPreferences.entryZone(): ZoneId = when (val choice = options.displayZone) {
    DisplayZoneOption.Device -> ZoneId.systemDefault()
    is DisplayZoneOption.Fixed -> choice.zone
}
private fun CalendarApp.entryChanges(): Flow<Unit> = merge(
    calendarReader.observeCalendars().map { Unit }, settings.calendarPreferences.map { Unit })

internal fun CalendarApp.createCalendarEntryServices() = CalendarEntryServices(
    ReaderIncomingCalendarSource(calendarReader), readZone = {
        try { entryPreferences()?.let { CalendarReadState.Ready(it.entryZone()) } ?: CalendarReadState.Unavailable }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { CalendarReadState.Unavailable }
    }, changes = entryChanges(),
)

internal fun CalendarApp.createCalendarSeedReviewServices() = CalendarSeedReviewServices(
    calendarLocalStore, calendarWriter, operationRetirement, readEnvironment = {
        try {
            val prefs = entryPreferences()
            if (prefs == null) CalendarReadState.Unavailable else {
                val granted = checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
                val state = if (granted) calendarReader.readCalendars() else CalendarReadState.Ready(emptyList())
                when (state) {
                    is CalendarReadState.Ready -> CalendarReadState.Ready(CalendarSeedReviewEnvironment(
                        calendars = state.value, displayZone = prefs.entryZone(), use24Hour = DateFormat.is24HourFormat(this),
                        defaultCalendar = if (granted) verifiedCreationCalendar(prefs, state.value)?.identity else null,
                        timedAlertMinutes = listOfNotNull(prefs.options.timedReminderMinutes),
                        allDayAlertMinutes = listOfNotNull(prefs.options.allDayReminder.minutesBefore),
                        recurringCreationSupported = true, calendarReadGranted = granted))
                    CalendarReadState.PermissionRequired -> CalendarReadState.PermissionRequired
                    CalendarReadState.LimitExceeded -> CalendarReadState.LimitExceeded
                    else -> CalendarReadState.Unavailable
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { CalendarReadState.Unavailable }
    }, changes = entryChanges(),
)

internal fun CalendarApp.createCalendarEditReviewServices() = CalendarEditReviewServices(
    calendarLocalStore, calendarWriter, operationRetirement, ReaderIncomingCalendarSource(calendarReader),
    readEnvironment = {
        try {
            val prefs = entryPreferences()
            if (prefs == null) CalendarReadState.Unavailable else {
                fun environment(calendars: List<CalendarInfo>, granted: Boolean) = CalendarReadState.Ready(
                    CalendarEditReviewEnvironment(calendars, prefs.entryZone(), DateFormat.is24HourFormat(this), granted))
                if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED)
                    environment(emptyList(), false)
                else when (val state = calendarReader.readCalendars()) {
                    is CalendarReadState.Ready -> environment(state.value, true)
                    CalendarReadState.PermissionRequired -> environment(emptyList(), false)
                    CalendarReadState.LimitExceeded -> CalendarReadState.LimitExceeded
                    else -> CalendarReadState.Unavailable
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { CalendarReadState.Unavailable }
    }, changes = entryChanges(),
)

internal fun CalendarApp.calendarEntryRenderer() = CalendarEntryContent { state, actions ->
    val prefs by settings.calendarPreferences.collectAsStateWithLifecycle(initialValue = CalendarPreferenceState.Loading)
    CalendarPreferenceTheme(prefs) {
        if (prefs is CalendarPreferenceState.Ready) {
            BasicCalendarEntryScreen(state, actions)
        } else BasicCalendarEntryScreen(CalendarEntryState.Loading, actions.copy(review = null))
    }
}

@Composable
internal fun CalendarApp.calendarSeedReviewRendererTheme(content: @Composable () -> Unit) {
    val prefs by settings.calendarPreferences.collectAsStateWithLifecycle(initialValue = CalendarPreferenceState.Loading)
    CalendarPreferenceTheme(prefs, content)
}

internal fun CalendarApp.calendarEntryReviewer() = CalendarEntryReviewHandler { activity, command ->
    when (val resolution = command.resolution) {
        is CalendarEntryResolution.Date -> {
            activity.startActivity(CalendarDateIntents.intent(activity, CalendarDateNavigation(command.sessionId, resolution.instant))); true
        }
        is CalendarEntryResolution.CreateSeed -> {
            activity.startActivity(CalendarSeedReviewActivity.intent(activity, command.sessionId, resolution.seed, command.displayZone)); true
        }
        is CalendarEntryResolution.Event -> resolution.proposedEdit?.let { seed ->
            activity.startActivity(CalendarEditReviewActivity.intent(activity, CalendarEditReviewRequest(
                command.sessionId, resolution.ref, resolution.binding, seed, command.displayZone)))
            true
        } ?: false
        else -> false
    }
}
