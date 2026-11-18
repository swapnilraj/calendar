package dev.swapnilraj.calendar.data.settings

import androidx.datastore.preferences.core.*
import dev.swapnilraj.calendar.ui.calendars.CalendarViewOption
import dev.swapnilraj.calendar.ui.settings.*
import java.time.ZoneId

/** Pure conversion inside the caller's atomic preference read/edit; never performs IO or repairs storage. */
object CalendarSettingsCodec {
    private val view = stringPreferencesKey("calendar_view")
    private val duration = intPreferencesKey("display_default_duration_minutes")
    private val timedEnabled = booleanPreferencesKey("display_timed_reminder_enabled")
    private val timedMinutes = intPreferencesKey("display_timed_reminder_minutes")
    private val allDay = stringPreferencesKey("display_all_day_reminder")
    private val weekday = stringPreferencesKey("display_first_weekday")
    private val weekends = booleanPreferencesKey("display_show_weekends")
    private val declined = booleanPreferencesKey("display_show_declined")
    private val theme = stringPreferencesKey("display_theme")
    private val zoneMode = stringPreferencesKey("display_zone_mode")
    private val zoneId = stringPreferencesKey("display_zone_id")
    private val weather = booleanPreferencesKey("display_weather_visible")
    private val units = stringPreferencesKey("display_weather_units")

    /** [currentView] is the caller's view from this same snapshot, never a second preference read. */
    fun read(preferences: Preferences, currentView: String): CalendarSettingsOptions {
        val defaults = CalendarSettingsOptions()
        // Inspect raw values by name so a key with the wrong stored type also falls back safely.
        val raw = preferences.asMap().entries.associate { it.key.name to it.value }
        val fixedZone = (raw[zoneId.name] as? String)?.takeIf { it in ZoneId.getAvailableZoneIds() }?.let(ZoneId::of)
        return CalendarSettingsOptions(
            defaultView = enumOrDefault(currentView, defaults.defaultView),
            defaultDurationMinutes = (raw[duration.name] as? Int)?.takeIf { it in 1..1440 } ?: defaults.defaultDurationMinutes,
            timedReminderMinutes = if ((raw[timedEnabled.name] as? Boolean) == false) null
                else (raw[timedMinutes.name] as? Int)?.takeIf { it in 0..40320 } ?: defaults.timedReminderMinutes,
            allDayReminder = enumOrDefault(raw[allDay.name], defaults.allDayReminder),
            firstWeekday = enumOrDefault(raw[weekday.name], defaults.firstWeekday),
            showWeekends = raw[weekends.name] as? Boolean ?: defaults.showWeekends,
            showDeclined = raw[declined.name] as? Boolean ?: defaults.showDeclined,
            theme = enumOrDefault(raw[theme.name], defaults.theme),
            displayZone = if (raw[zoneMode.name] == "FIXED" && fixedZone != null) DisplayZoneOption.Fixed(fixedZone) else DisplayZoneOption.Device,
            weatherVisible = raw[weather.name] as? Boolean ?: defaults.weatherVisible,
            weatherUnits = enumOrDefault(raw[units.name], defaults.weatherUnits),
        )
    }

    /** Mutate only owned display keys and the existing shared view key in the caller's one edit. */
    fun write(preferences: MutablePreferences, options: CalendarSettingsOptions) {
        preferences[view] = options.defaultView.name
        preferences[duration] = options.defaultDurationMinutes
        preferences[timedEnabled] = options.timedReminderMinutes != null
        options.timedReminderMinutes?.let { preferences[timedMinutes] = it } ?: preferences.remove(timedMinutes)
        preferences[allDay] = options.allDayReminder.name
        preferences[weekday] = options.firstWeekday.name
        preferences[weekends] = options.showWeekends
        preferences[declined] = options.showDeclined
        preferences[theme] = options.theme.name
        when (val zone = options.displayZone) {
            DisplayZoneOption.Device -> { preferences[zoneMode] = "DEVICE"; preferences.remove(zoneId) }
            is DisplayZoneOption.Fixed -> { preferences[zoneMode] = "FIXED"; preferences[zoneId] = zone.zone.id }
        }
        preferences[weather] = options.weatherVisible
        preferences[units] = options.weatherUnits.name
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(raw: Any?, default: T): T =
        enumValues<T>().firstOrNull { it.name == raw } ?: default
}
