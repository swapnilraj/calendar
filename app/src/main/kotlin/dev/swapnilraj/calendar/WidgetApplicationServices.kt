package dev.swapnilraj.calendar

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.text.format.DateFormat
import dev.swapnilraj.calendar.android.widgets.*
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.CalendarPreferenceState
import dev.swapnilraj.calendar.ui.settings.DisplayZoneOption
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first

internal fun CalendarApp.createWidgetServices(scope: CoroutineScope) = AgendaWidgetApplicationServices(
    this, scope, AgendaWidgetSnapshotReader(calendarReader::readSnapshot),
    calendarReader::observeCalendars, calendarReader::readCalendars,
    readEnvironment = {
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED)
            CalendarReadState.PermissionRequired
        else try {
            when (val current = settings.calendarPreferences.first { it != CalendarPreferenceState.Loading }) {
                is CalendarPreferenceState.Ready -> CalendarReadState.Ready(AgendaWidgetEnvironment(
                    current.value.selection,
                    when (val zone = current.value.options.displayZone) {
                        DisplayZoneOption.Device -> ZoneId.systemDefault()
                        is DisplayZoneOption.Fixed -> zone.zone
                    }, current.value.options.showDeclined, DateFormat.is24HourFormat(this)))
                else -> CalendarReadState.Unavailable
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { CalendarReadState.Unavailable }
    }, mainActivity = ComponentName(this, MainActivity::class.java),
)
