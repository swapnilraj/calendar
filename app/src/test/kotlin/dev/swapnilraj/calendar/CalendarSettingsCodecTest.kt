package dev.swapnilraj.calendar

import androidx.datastore.preferences.core.*
import dev.swapnilraj.calendar.data.settings.CalendarSettingsCodec
import dev.swapnilraj.calendar.core.InvitationFilter
import dev.swapnilraj.calendar.ui.calendars.CalendarViewOption
import dev.swapnilraj.calendar.ui.settings.*
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class CalendarSettingsCodecTest {
    @Test fun emptySnapshotUsesC6DefaultsAndSuppliedCurrentView() {
        assertEquals(CalendarSettingsOptions(), CalendarSettingsCodec.read(emptyPreferences(), "DAY"))
        val preferences = preferencesOf(stringPreferencesKey("calendar_view") to "WEEK")
        assertEquals(CalendarViewOption.MONTH, CalendarSettingsCodec.read(preferences, "MONTH").defaultView)
        assertEquals(CalendarViewOption.DAY, CalendarSettingsCodec.read(preferences, "future-view").defaultView)
    }
    @Test fun everyFieldRoundTripsThroughStableKeys() {
        val options = CalendarSettingsOptions(CalendarViewOption.WEEK, 135, 25, AllDayReminderDefault.TWO_DAYS_BEFORE_0900,
            FirstWeekdayOption.SATURDAY, false, false, SettingsTheme.DARK, DisplayZoneOption.Fixed(ZoneId.of("Asia/Kolkata")), false, WeatherUnits.FAHRENHEIT)
        val preferences = mutablePreferencesOf()
        CalendarSettingsCodec.write(preferences, options)
        assertEquals("WEEK", preferences[stringPreferencesKey("calendar_view")])
        assertEquals(135, preferences[intPreferencesKey("display_default_duration_minutes")])
        assertEquals("Asia/Kolkata", preferences[stringPreferencesKey("display_zone_id")])
        assertEquals(options, CalendarSettingsCodec.read(preferences.toPreferences(), "WEEK"))
    }
    @Test fun nullTimedAndNoneAllDayRoundTripDistinctFromAbsentDefaults() {
        val preferences = mutablePreferencesOf()
        val none = CalendarSettingsOptions(timedReminderMinutes = null, allDayReminder = AllDayReminderDefault.NONE)
        CalendarSettingsCodec.write(preferences, CalendarSettingsOptions(timedReminderMinutes = 30))
        CalendarSettingsCodec.write(preferences, none)
        assertEquals(false, preferences[booleanPreferencesKey("display_timed_reminder_enabled")])
        assertFalse(preferences.contains(intPreferencesKey("display_timed_reminder_minutes")))
        assertEquals(none, CalendarSettingsCodec.read(preferences, "DAY"))
        assertEquals(10, CalendarSettingsCodec.read(emptyPreferences(), "DAY").timedReminderMinutes)
    }
    @Test fun allEnumsAndViewsRoundTripWithoutOrdinalCoupling() {
        for (view in CalendarViewOption.entries) for (weekday in FirstWeekdayOption.entries) for (allDay in AllDayReminderDefault.entries) {
            val options = CalendarSettingsOptions(defaultView = view, firstWeekday = weekday, allDayReminder = allDay)
            val preferences = mutablePreferencesOf()
            CalendarSettingsCodec.write(preferences, options)
            assertEquals(options, CalendarSettingsCodec.read(preferences, view.name))
        }
        for (theme in SettingsTheme.entries) for (units in WeatherUnits.entries) {
            val options = CalendarSettingsOptions(theme = theme, weatherUnits = units)
            val preferences = mutablePreferencesOf()
            CalendarSettingsCodec.write(preferences, options)
            assertEquals(options, CalendarSettingsCodec.read(preferences, "DAY"))
        }
    }
    @Test fun unknownEnumsAndWrongTypesFallBackWithoutMutatingSnapshot() {
        val preferences = mutablePreferencesOf(
            stringPreferencesKey("display_first_weekday") to "TUESDAY", stringPreferencesKey("display_theme") to "AUTO_FUTURE",
            stringPreferencesKey("display_weather_units") to "KELVIN", stringPreferencesKey("display_all_day_reminder") to "ON_DAY_0900",
            stringPreferencesKey("display_default_duration_minutes") to "90", stringPreferencesKey("display_show_weekends") to "false",
            intPreferencesKey("display_show_declined") to 0, stringPreferencesKey("display_timed_reminder_enabled") to "false",
            stringPreferencesKey("display_timed_reminder_minutes") to "900", stringPreferencesKey("display_weather_visible") to "false")
        val before = preferences.asMap().toMap()
        assertEquals(CalendarSettingsOptions(), CalendarSettingsCodec.read(preferences, "not-a-view"))
        assertEquals(before, preferences.asMap())
    }
    @Test fun invalidDurationsAndOffsetsUseDefaultsRatherThanClamping() {
        for (invalid in listOf(Int.MIN_VALUE, -1, 0, 1441, Int.MAX_VALUE)) {
            val preferences = preferencesOf(intPreferencesKey("display_default_duration_minutes") to invalid)
            assertEquals(60, CalendarSettingsCodec.read(preferences, "DAY").defaultDurationMinutes)
        }
        for (invalid in listOf(Int.MIN_VALUE, -1, 40321, Int.MAX_VALUE)) {
            val preferences = preferencesOf(intPreferencesKey("display_timed_reminder_minutes") to invalid)
            assertEquals(10, CalendarSettingsCodec.read(preferences, "DAY").timedReminderMinutes)
        }
        for (minutes in listOf(0, 40320)) {
            assertEquals(minutes, CalendarSettingsCodec.read(preferencesOf(intPreferencesKey("display_timed_reminder_minutes") to minutes), "DAY").timedReminderMinutes)
        }
    }
    @Test fun invalidFixedZoneAndUnknownModesUseDeviceZoneWithoutRepairs() {
        for (zone in listOf("Missing/Zone", "+05:30", "", "utc")) {
            val preferences = preferencesOf(stringPreferencesKey("display_zone_mode") to "FIXED", stringPreferencesKey("display_zone_id") to zone)
            val before = preferences.asMap().toMap()
            assertEquals(DisplayZoneOption.Device, CalendarSettingsCodec.read(preferences, "DAY").displayZone)
            assertEquals(before, preferences.asMap())
        }
        val future = preferencesOf(stringPreferencesKey("display_zone_mode") to "AUTO", stringPreferencesKey("display_zone_id") to "UTC")
        assertEquals(DisplayZoneOption.Device, CalendarSettingsCodec.read(future, "DAY").displayZone)
        val utc = preferencesOf(stringPreferencesKey("display_zone_mode") to "FIXED", stringPreferencesKey("display_zone_id") to "UTC")
        assertEquals(DisplayZoneOption.Fixed(ZoneId.of("UTC")), CalendarSettingsCodec.read(utc, "DAY").displayZone)
    }
    @Test fun deviceZoneWriteRemovesPreviousFixedZoneAndPreservesOtherPreferences() {
        val preferences = mutablePreferencesOf(
            stringSetPreferencesKey("selected_calendar_ids") to setOf("2", "9"), longPreferencesKey("default_calendar_id") to 9L,
            stringSetPreferencesKey("known_calendar_accounts") to setOf("account-pin"), floatPreferencesKey("hour_height") to 88f,
            booleanPreferencesKey("reminders_enabled") to true, stringSetPreferencesKey("reminder_calendar_ids") to setOf("7"),
            stringSetPreferencesKey("reminder_enabled_calendar_pins") to setOf("pin"), stringPreferencesKey("future_setting") to "untouched")
        val before = preferences.asMap().toMap()
        CalendarSettingsCodec.write(preferences, CalendarSettingsOptions(displayZone = DisplayZoneOption.Fixed(ZoneId.of("Europe/London"))))
        CalendarSettingsCodec.write(preferences, CalendarSettingsOptions())
        assertFalse(preferences.contains(stringPreferencesKey("display_zone_id")))
        for ((key, value) in before) assertEquals("Unrelated ${key.name}", value, preferences.asMap()[key])
        assertTrue(preferences.asMap().keys.filter { it !in before.keys }.all { it.name.startsWith("display_") || it.name == "calendar_view" })
        assertEquals(CalendarSettingsOptions(), CalendarSettingsCodec.read(preferences, "DAY"))
    }

    @Test fun invitationFilterRoundTripsByNameWithoutChangingOtherOptions() {
        for (filter in InvitationFilter.entries) {
            val options = CalendarSettingsOptions(defaultDurationMinutes = 75, showDeclined = false, invitationFilter = filter)
            val preferences = mutablePreferencesOf(stringPreferencesKey("opaque") to "retained")
            CalendarSettingsCodec.write(preferences, options)
            assertEquals(filter.name, preferences[stringPreferencesKey("display_invitation_filter")])
            assertEquals(options, CalendarSettingsCodec.read(preferences, "DAY"))
            assertEquals("retained", preferences[stringPreferencesKey("opaque")])
        }
    }

    @Test fun missingFutureAndMalformedInvitationFiltersDefaultWithoutRepair() {
        val snapshots = listOf(emptyPreferences(),
            preferencesOf(stringPreferencesKey("display_invitation_filter") to "FUTURE_FILTER"),
            preferencesOf(stringPreferencesKey("display_invitation_filter") to "pending_only"),
            preferencesOf(stringPreferencesKey("display_invitation_filter") to " PENDING_ONLY"),
            preferencesOf(intPreferencesKey("display_invitation_filter") to 1))
        for (snapshot in snapshots) {
            val before = snapshot.asMap().toMap()
            assertEquals(InvitationFilter.ALL, CalendarSettingsCodec.read(snapshot, "DAY").invitationFilter)
            assertEquals(before, snapshot.asMap())
        }
    }

    @Test fun unrelatedLegacyDraftEditRetainsPendingInvitationFilter() {
        val original = CalendarSettingsOptions(invitationFilter = InvitationFilter.PENDING_ONLY)
        val saved = SettingsDraft.from(original).copy(duration = "135", showWeekends = false).validated(CalendarViewOption.entries)
        assertEquals(original.copy(defaultDurationMinutes = 135, showWeekends = false), saved)
        assertEquals(InvitationFilter.ALL, SettingsDraft.from(CalendarSettingsOptions()).validated(CalendarViewOption.entries)!!.invitationFilter)
    }
}
