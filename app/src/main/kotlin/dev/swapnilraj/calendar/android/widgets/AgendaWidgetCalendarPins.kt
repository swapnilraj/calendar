package dev.swapnilraj.calendar.android.widgets

import dev.swapnilraj.calendar.core.*

internal fun validWidgetPins(selection: WidgetCalendarSelection, pins: Map<CalendarId, CalendarIdentity>): Boolean =
    when (selection) {
        WidgetCalendarSelection.FollowApp -> pins.isEmpty()
        is WidgetCalendarSelection.Explicit -> pins.keys == selection.ids && pins.all { (id, pin) -> id == pin.calendarId && pin.account.name.isNotBlank() && pin.account.type.isNotBlank() }
    }

/** A known identity field cannot be downgraded to unavailable metadata. Unknown provider UIDs cannot
 * distinguish a same-account reset reusing all numeric IDs; this is not an OS identity guarantee. */
internal fun widgetPinsAuthorize(configuration: AgendaWidgetConfiguration, calendars: List<CalendarInfo>): Boolean {
    if (!validWidgetPins(configuration.selection, configuration.expectedCalendarIdentities)) return false
    if (configuration.selection == WidgetCalendarSelection.FollowApp) return true
    val byId = calendars.groupBy { it.id }
    return configuration.expectedCalendarIdentities.all { (id, expected) ->
        val current = byId[id]?.singleOrNull()?.identity
        current != null && current.calendarId == expected.calendarId && current.account == expected.account &&
            (expected.owner == null || current.owner == expected.owner) &&
            (expected.providerId == null || current.providerId == expected.providerId)
    }
}

/** Binds immutable row clicks to the rendered namespace without putting account names in the URI. */
internal fun widgetCalendarFingerprint(identity: CalendarIdentity): String? {
    if (identity.account.name.isBlank() || identity.account.type.isBlank()) return null
    val fields = listOf(identity.calendarId.value.toString(), identity.account.name, identity.account.type, identity.owner, identity.providerId)
    val canonical = fields.joinToString("") { value -> value?.let { "${it.toByteArray(Charsets.UTF_8).size}:$it" } ?: "-1:" }
    return java.security.MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
