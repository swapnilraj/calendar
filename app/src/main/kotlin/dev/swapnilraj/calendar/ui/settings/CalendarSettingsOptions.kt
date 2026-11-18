package dev.swapnilraj.calendar.ui.settings

import dev.swapnilraj.calendar.ui.calendars.CalendarViewOption
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale

/** Feature-owned values only. Persistence, provider capabilities and applying settings belong to the caller. */
data class CalendarSettingsOptions(
    val defaultView: CalendarViewOption = CalendarViewOption.DAY,
    val defaultDurationMinutes: Int = 60,
    val timedReminderMinutes: Int? = 10,
    val allDayReminder: AllDayReminderDefault = AllDayReminderDefault.PREVIOUS_DAY_0900,
    val firstWeekday: FirstWeekdayOption = FirstWeekdayOption.LOCALE,
    val showWeekends: Boolean = true,
    val showDeclined: Boolean = true,
    val theme: SettingsTheme = SettingsTheme.SYSTEM,
    val displayZone: DisplayZoneOption = DisplayZoneOption.Device,
    val weatherVisible: Boolean = true,
    val weatherUnits: WeatherUnits = WeatherUnits.CELSIUS,
) {
    init {
        require(defaultDurationMinutes in 1..1440)
        require(timedReminderMinutes == null || timedReminderMinutes in 0..40320)
    }
}

enum class SettingsTheme { SYSTEM, LIGHT, DARK }
enum class WeatherUnits { CELSIUS, FAHRENHEIT }
enum class AllDayReminderDefault(val minutesBefore: Int?) {
    NONE(null), PREVIOUS_DAY_0900(900), TWO_DAYS_BEFORE_0900(2340), WEEK_BEFORE_0900(9540),
}
enum class FirstWeekdayOption {
    LOCALE, SUNDAY, MONDAY, SATURDAY;
    fun resolve(locale: Locale): DayOfWeek = when (this) {
        LOCALE -> WeekFields.of(locale).firstDayOfWeek
        SUNDAY -> DayOfWeek.SUNDAY
        MONDAY -> DayOfWeek.MONDAY
        SATURDAY -> DayOfWeek.SATURDAY
    }
}
sealed interface DisplayZoneOption {
    data object Device : DisplayZoneOption
    data class Fixed(val zone: ZoneId) : DisplayZoneOption {
        init { require(zone.id in ZoneId.getAvailableZoneIds()) { "A fixed display zone must be an available IANA zone" } }
    }
}

internal fun fixedSettingsZone(raw: String): ZoneId? = raw.trim().takeIf { it in ZoneId.getAvailableZoneIds() }?.let(ZoneId::of)

internal enum class SettingsField { VIEW, DURATION, TIMED_REMINDER, ZONE }
internal data class SettingsDraft(
    val view: CalendarViewOption,
    val duration: String,
    val timedReminder: String,
    val timedReminderEnabled: Boolean,
    val allDayReminder: AllDayReminderDefault,
    val firstWeekday: FirstWeekdayOption,
    val showWeekends: Boolean,
    val showDeclined: Boolean,
    val theme: SettingsTheme,
    val fixedZone: Boolean,
    val zone: String,
    val weatherVisible: Boolean,
    val units: WeatherUnits,
) {
    fun errors(availableViews: List<CalendarViewOption>): Set<SettingsField> = buildSet {
        if (view !in availableViews) add(SettingsField.VIEW)
        if (duration.trim().toIntOrNull()?.let { it in 1..1440 } != true) add(SettingsField.DURATION)
        if (timedReminderEnabled && timedReminder.trim().toIntOrNull()?.let { it in 0..40320 } != true) add(SettingsField.TIMED_REMINDER)
        if (fixedZone && fixedSettingsZone(zone) == null) add(SettingsField.ZONE)
    }
    fun validated(availableViews: List<CalendarViewOption>): CalendarSettingsOptions? {
        if (errors(availableViews).isNotEmpty()) return null
        return CalendarSettingsOptions(view, duration.trim().toInt(), if (timedReminderEnabled) timedReminder.trim().toInt() else null,
            allDayReminder, firstWeekday, showWeekends, showDeclined, theme,
            if (fixedZone) DisplayZoneOption.Fixed(requireNotNull(fixedSettingsZone(zone))) else DisplayZoneOption.Device, weatherVisible, units)
    }
    companion object {
        fun from(options: CalendarSettingsOptions) = SettingsDraft(options.defaultView, options.defaultDurationMinutes.toString(),
            (options.timedReminderMinutes ?: 10).toString(), options.timedReminderMinutes != null, options.allDayReminder, options.firstWeekday,
            options.showWeekends, options.showDeclined, options.theme, options.displayZone is DisplayZoneOption.Fixed,
            (options.displayZone as? DisplayZoneOption.Fixed)?.zone?.id ?: "", options.weatherVisible, options.weatherUnits)
    }
}
