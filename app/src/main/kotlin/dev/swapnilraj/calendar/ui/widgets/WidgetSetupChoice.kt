package dev.swapnilraj.calendar.ui.widgets

import dev.swapnilraj.calendar.android.widgets.WidgetCalendarSelection
import dev.swapnilraj.calendar.android.widgets.WidgetPrivacy
import dev.swapnilraj.calendar.core.CalendarId
import dev.swapnilraj.calendar.core.CalendarInfo
import android.provider.CalendarContract

/** A user choice only; no widget generation, namespace pin, preference mutation or event identity. */
data class WidgetSetupChoice(
    val selection: WidgetCalendarSelection = WidgetCalendarSelection.FollowApp,
    val privacy: WidgetPrivacy = WidgetPrivacy.BUSY_ONLY,
)

internal fun widgetCalendarSelectable(calendar: CalendarInfo): Boolean =
    calendar.accessLevel?.let { it >= CalendarContract.Calendars.CAL_ACCESS_FREEBUSY } == true

internal data class WidgetSetupDraft(val followApp: Boolean, val ids: Set<CalendarId>, val privacy: WidgetPrivacy) {
    fun choice() = WidgetSetupChoice(if (followApp) WidgetCalendarSelection.FollowApp else WidgetCalendarSelection.Explicit(ids.toSet()), privacy)
    fun toggle(id: CalendarId, checked: Boolean) = copy(ids = if (checked) ids + id else ids - id)
    companion object {
        fun from(choice: WidgetSetupChoice) = WidgetSetupDraft(choice.selection == WidgetCalendarSelection.FollowApp,
            (choice.selection as? WidgetCalendarSelection.Explicit)?.ids?.toSet().orEmpty(), choice.privacy)
    }
}
