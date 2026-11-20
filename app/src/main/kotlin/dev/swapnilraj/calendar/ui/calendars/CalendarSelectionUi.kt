package dev.swapnilraj.calendar.ui.calendars

import android.provider.CalendarContract
import dev.swapnilraj.calendar.core.CalendarId
import dev.swapnilraj.calendar.core.CalendarInfo
import dev.swapnilraj.calendar.core.CalendarSelection

/** App-local filtering only. No provider visibility or synchronization values are ever written. */
internal fun canDisplay(calendar: CalendarInfo): Boolean = calendar.accessLevel != CalendarContract.Calendars.CAL_ACCESS_NONE

internal fun selectedCalendarIds(calendars: List<CalendarInfo>, selection: CalendarSelection): Set<CalendarId> = when (selection) {
    CalendarSelection.ProviderVisible -> calendars.filter { it.providerVisible && canDisplay(it) }.mapTo(linkedSetOf()) { it.id }
    is CalendarSelection.Explicit -> selection.ids
}

internal fun toggledCalendarSelection(calendars: List<CalendarInfo>, selection: CalendarSelection,
    id: CalendarId, checked: Boolean): CalendarSelection.Explicit {
    val ids = selectedCalendarIds(calendars, selection)
    return CalendarSelection.Explicit(if (checked) ids + id else ids - id)
}
