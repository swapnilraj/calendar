package dev.swapnilraj.calendar

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.swapnilraj.calendar.ui.settings.*
import dev.swapnilraj.calendar.core.InvitationFilter
import dev.swapnilraj.calendar.ui.calendars.CalendarViewOption
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
import java.time.*
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsSheetTest {
    @get:Rule val compose = createComposeRule()
    private val views = CalendarViewOption.entries
    private fun show(options: CalendarSettingsOptions = CalendarSettingsOptions(), available: List<CalendarViewOption> = views,
        saving: Boolean = false, error: String? = null, font: Float = 1f, rtl: Boolean = false,
        onSave: (CalendarSettingsOptions) -> Unit = {}, onDismiss: () -> Unit = {}, onCalendar: () -> Unit = {}, onReminders: () -> Unit = {}) {
        compose.setContent {
            DaylightTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, font),
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    SettingsSheet(options, available, onSave, onDismiss, onCalendar, onReminders, saving = saving, saveError = error)
                }
            }
        }
        compose.waitForIdle()
    }
    private fun field(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("settings_fields").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }
    private fun text(value: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("settings_fields").performScrollToNode(hasText(value))
        return compose.onNodeWithText(value)
    }
    private fun switch(label: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("settings_fields").performScrollToNode(hasContentDescription(label))
        return compose.onNodeWithContentDescription(label)
    }
    @Test fun defaultsMatchApprovedWorkAndPersonalPreferences() {
        val options = CalendarSettingsOptions()
        assertEquals(60, options.defaultDurationMinutes)
        assertEquals(10, options.timedReminderMinutes)
        assertEquals(900, options.allDayReminder.minutesBefore)
        assertEquals(DisplayZoneOption.Device, options.displayZone)
        assertTrue(options.showWeekends && options.showDeclined && options.weatherVisible)
        assertEquals(SettingsTheme.SYSTEM, options.theme)
        assertEquals(WeatherUnits.CELSIUS, options.weatherUnits)
    }
    @Test fun reminderPresetsUseNonnegativeWallClockMinuteOffsets() {
        assertEquals(listOf(null, 900, 2340, 9540), AllDayReminderDefault.entries.map { it.minutesBefore })
        val midnight = LocalDate.of(2026, 11, 18).atStartOfDay()
        assertEquals(LocalDateTime.of(2026, 11, 17, 9, 0), midnight.minusMinutes(AllDayReminderDefault.PREVIOUS_DAY_0900.minutesBefore!!.toLong()))
    }
    @Test fun localeWeekStartResolvesWithoutChangingExplicitChoices() {
        assertEquals(DayOfWeek.SUNDAY, FirstWeekdayOption.LOCALE.resolve(Locale.US))
        assertEquals(DayOfWeek.MONDAY, FirstWeekdayOption.LOCALE.resolve(Locale.UK))
        assertEquals(DayOfWeek.SATURDAY, FirstWeekdayOption.SATURDAY.resolve(Locale.UK))
    }
    @Test fun fixedZonesRequireAvailableIanaIdentity() {
        assertEquals(ZoneId.of("Europe/London"), fixedSettingsZone(" Europe/London "))
        assertEquals(ZoneId.of("UTC"), fixedSettingsZone("UTC"))
        assertNull(fixedSettingsZone("+05:30"))
        assertNull(fixedSettingsZone("not/a/zone"))
    }
    @Test fun validationRejectsOverflowNegativeAndOutOfRangeRawValues() {
        val draft = SettingsDraft.from(CalendarSettingsOptions())
        for (raw in listOf("", "0", "1441", "999999999999", "1.5")) assertNull(draft.copy(duration = raw).validated(views))
        for (raw in listOf("-1", "40321", "999999999999", "ten")) assertNull(draft.copy(timedReminder = raw).validated(views))
        assertNotNull(draft.copy(duration = "1", timedReminder = "0").validated(views))
        assertNotNull(draft.copy(duration = "1440", timedReminder = "40320").validated(views))
        assertNull(draft.validated(emptyList()))
    }
    @Test fun noCallbacksFireUntilAnExplicitActionAndSaveDoesNotDismiss() {
        var saved: CalendarSettingsOptions? = null
        var dismissed = 0
        show(onSave = { saved = it }, onDismiss = { dismissed++ })
        assertNull(saved)
        compose.onNodeWithTag("settings_save").performClick()
        assertEquals(CalendarSettingsOptions(), saved)
        assertEquals(0, dismissed)
    }
    @Test fun invalidDurationRemainsEditableAndNeverCallsSave() {
        var saves = 0
        show(onSave = { saves++ })
        field("settings_duration").performTextReplacement("abc")
        compose.onNodeWithTag("settings_save").performClick()
        compose.onNodeWithText("Check the highlighted fields before saving.").assertIsDisplayed()
        field("settings_duration").assertTextContains("abc")
        assertEquals(0, saves)
        field("settings_duration").performTextReplacement("45")
        compose.onNodeWithTag("settings_save").performClick()
        assertEquals(1, saves)
    }
    @Test fun invalidTimedReminderAndZoneNeverReachSaveCallback() {
        var saves = 0
        show(onSave = { saves++ })
        field("settings_timed_reminder").performTextReplacement("-1")
        switch("Use a fixed display time zone").performClick()
        field("settings_zone").performTextReplacement("+05:30")
        compose.onNodeWithTag("settings_save").performClick()
        assertEquals(0, saves)
        field("settings_zone").assertTextContains("+05:30")
    }
    @Test fun zoneSearchSelectsValidatedIanaZoneWithoutIo() {
        var saved: CalendarSettingsOptions? = null
        show(onSave = { saved = it })
        switch("Use a fixed display time zone").performClick()
        field("settings_zone").performTextReplacement("London")
        text("Europe/London").performClick()
        compose.onNodeWithTag("settings_save").performClick()
        assertEquals(DisplayZoneOption.Fixed(ZoneId.of("Europe/London")), saved?.displayZone)
    }
    @Test fun calendarAndReminderActionsOnlyDelegate() {
        var calendar = 0
        var reminders = 0
        var saves = 0
        show(onCalendar = { calendar++ }, onReminders = { reminders++ }, onSave = { saves++ })
        text("Choose default calendar").performClick()
        text("Reminder setup and calendars").performClick()
        assertEquals(1, calendar)
        assertEquals(1, reminders)
        assertEquals(0, saves)
    }
    @Test fun savingDisablesSubmissionCancelAndFieldsWhileErrorPreservesDraft() {
        show(saving = true, error = "Could not save. Try again.")
        compose.onNodeWithTag("settings_save").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").assertIsNotEnabled()
        field("settings_duration").assertIsNotEnabled()
        compose.onNodeWithText("Could not save. Try again.").assertIsDisplayed()
    }
    @Test fun onlyImplementedViewsAreOffered() {
        show(available = listOf(CalendarViewOption.DAY, CalendarViewOption.MONTH))
        compose.onNodeWithContentDescription("Default view, Day").performClick()
        compose.onNodeWithText("Month").assertIsDisplayed()
        compose.onNodeWithText("Week").assertDoesNotExist()
    }
    @Test fun changedPreferencesAreEmittedAsOneTypedValueOnlyOnSave() {
        var saved: CalendarSettingsOptions? = null
        show(onSave = { saved = it })
        fun choose(label: String, current: String, next: String) {
            val description = "$label, $current"
            compose.onNodeWithTag("settings_fields").performScrollToNode(hasContentDescription(description))
            compose.onNodeWithContentDescription(description).performClick()
            compose.onNodeWithText(next).performClick()
        }
        choose("Default view", "Day", "Month")
        field("settings_duration").performTextReplacement("90")
        switch("Default reminder for timed events").performClick()
        choose("Default reminder for all-day events", "Previous day at 9:00 AM", "One week before at 9:00 AM")
        choose("First day of the week", "Use locale default", "Monday")
        switch("Show weekends").performClick()
        switch("Show declined events").performClick()
        choose("Theme", "Use device theme", "Dark")
        switch("Show weather").performClick()
        choose("Temperature units", "Celsius (°C)", "Fahrenheit (°F)")
        assertNull(saved)
        compose.onNodeWithTag("settings_save").performClick()
        assertEquals(CalendarSettingsOptions(CalendarViewOption.MONTH, 90, null, AllDayReminderDefault.WEEK_BEFORE_0900,
            FirstWeekdayOption.MONDAY, false, false, SettingsTheme.DARK, DisplayZoneOption.Device, false, WeatherUnits.FAHRENHEIT), saved)
    }
    @Test fun rawInvalidDraftSurvivesStateRestoration() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { DaylightTheme { SettingsSheet(CalendarSettingsOptions(), views, {}, {}, {}, {}) } }
        field("settings_duration").performTextReplacement("unfinished")
        restoration.emulateSavedInstanceStateRestore()
        field("settings_duration").assertTextContains("unfinished")
    }
    @Test @Config(qualifiers = "+notnight") fun settingsLight() {
        show()
        compose.onNodeWithTag("settings_sheet").captureRoboImage("src/test/screenshots/settings_light.png")
    }
    @Test @Config(qualifiers = "+night") fun settingsDarkZoneAndWeather() {
        show(options = CalendarSettingsOptions(displayZone = DisplayZoneOption.Fixed(ZoneId.of("Europe/London"))))
        text("Celsius (°C)").assertIsDisplayed()
        compose.onNodeWithTag("settings_sheet").captureRoboImage("src/test/screenshots/settings_dark.png")
    }
    @Test @Config(qualifiers = "+notnight") fun settingsLargeRtlScrollsWithoutLosingActions() {
        show(font = 2f, rtl = true)
        compose.onNodeWithTag("settings_sheet").captureRoboImage("src/test/screenshots/settings_large_rtl.png")
        switch("Show weather").assertIsDisplayed().performClick()
        compose.onNodeWithTag("settings_save").assertIsDisplayed()
    }

    @Test fun pendingFilterSurvivesRecreationAndUnrelatedLegacySave() {
        val restoration = StateRestorationTester(compose)
        var saved: CalendarSettingsOptions? = null
        val original = CalendarSettingsOptions(invitationFilter = InvitationFilter.PENDING_ONLY)
        restoration.setContent { DaylightTheme { SettingsSheet(original, views, { saved = it }, {}, {}, {}) } }
        field("settings_duration").performTextReplacement("135")
        restoration.emulateSavedInstanceStateRestore()
        assertNull(saved)
        field("settings_duration").assertTextContains("135")
        compose.onNodeWithTag("settings_save").performClick()
        assertEquals(original.copy(defaultDurationMinutes = 135), saved)
    }
}
