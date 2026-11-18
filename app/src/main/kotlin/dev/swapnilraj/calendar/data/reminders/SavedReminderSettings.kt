package dev.swapnilraj.calendar.data.reminders

import dev.swapnilraj.calendar.core.*
import java.time.ZoneId

/** App-owned reminder settings only. Editor defaults and display filters never enter this snapshot. */
data class SavedReminderSettings(
    val enabled: Boolean = false,
    val expectedCalendarIdentities: Map<CalendarId, ReminderCalendarIdentity> = emptyMap(),
    val explicitDefaults: Map<CalendarId, ReminderDefaults> = emptyMap(),
) {
    fun toConfiguration(deviceZone: ZoneId): ReminderConfiguration {
        require(explicitDefaults.keys.all { it in expectedCalendarIdentities })
        require(explicitDefaults.values.all { it.timedMinutesBefore != -1 && it.allDayMinutesBefore != -1 })
        val pins = expectedCalendarIdentities.toMap()
        return ReminderConfiguration(enabled, pins.keys.toSet(), deviceZone, explicitDefaults.toMap(), pins)
    }

    /** Only strengthens a previously selected namespace. Missing/new calendars never change selection;
     * a known different UID/fingerprint is a conflict, never permission to adopt a replacement account.
     */
    fun strengthen(observed: Map<CalendarId, ReminderCalendarIdentity>): ReminderPinStrengthening {
        val pins = expectedCalendarIdentities.toMutableMap()
        val strengthened = mutableSetOf<CalendarId>()
        val conflicts = mutableSetOf<CalendarId>()
        expectedCalendarIdentities.forEach { (id, expected) ->
            val actual = observed[id] ?: return@forEach
            if (expected.isKnownDifferentFrom(actual)) conflicts += id
            else if (expected.fingerprint == actual.fingerprint && expected.providerUidFingerprint == null &&
                actual.providerUidFingerprint != null) {
                pins[id] = actual
                strengthened += id
            }
        }
        return ReminderPinStrengthening(copy(expectedCalendarIdentities = pins.toMap(), explicitDefaults = explicitDefaults.toMap()),
            strengthened.toSet(), conflicts.toSet())
    }
}

data class ReminderPinStrengthening(
    val settings: SavedReminderSettings,
    val strengthened: Set<CalendarId>,
    val conflicts: Set<CalendarId>,
)

enum class ReminderSettingsProblem { MALFORMED, UNSUPPORTED_VERSION, INVALID_SNAPSHOT }
sealed interface ReminderSettingsRead {
    data class Ready(val settings: SavedReminderSettings) : ReminderSettingsRead
    data class Unavailable(val problem: ReminderSettingsProblem) : ReminderSettingsRead
}
sealed interface ReminderSettingsSave {
    data class Saved(val settings: SavedReminderSettings) : ReminderSettingsSave
    data class Invalid(val problem: ReminderSettingsProblem) : ReminderSettingsSave
}
