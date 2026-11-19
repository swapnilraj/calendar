package dev.swapnilraj.calendar.ui

import dev.swapnilraj.calendar.android.widgets.AgendaWidgetEnvironment
import dev.swapnilraj.calendar.android.widgets.AgendaWidgetRoute

/** A validated, once-per-intent navigation action; it contains no event payload or write command. */
data class WidgetCalendarNavigation(val id: String, val route: AgendaWidgetRoute, val environment: AgendaWidgetEnvironment)
