package dev.swapnilraj.calendar.ui.settings

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import dev.swapnilraj.calendar.data.settings.CalendarSettingChange
import java.time.ZoneId

internal enum class PhoneSettingsPage { HOME, GENERAL }
internal enum class PhoneSettingEditor { NONE, WEEKDAY, ZONE, DURATION, THEME, VIEW, TIMED_REMINDER, ALL_DAY_REMINDER, WEATHER_UNITS }

/** Local navigation/raw input only. It never persists or replays a preference change. */
internal class PhoneSettingsState {
    var page by mutableStateOf(PhoneSettingsPage.HOME)
    var editor by mutableStateOf(PhoneSettingEditor.NONE)
    var raw by mutableStateOf("")
    var choice by mutableStateOf("")
    var reminderEnabled by mutableStateOf(true)
    var attemptedApply by mutableStateOf(false)
    var generation by mutableLongStateOf(0)
    var awaitingSave by mutableStateOf(false)
        private set
    var lastChange by mutableStateOf<CalendarSettingChange?>(null)
        private set
    var submittedGeneration by mutableLongStateOf(-1)
        private set
    private var submittedEditor = PhoneSettingEditor.NONE
    private var submittedRevision = 0L

    fun open(editor: PhoneSettingEditor, options: CalendarSettingsOptions, deviceZone: ZoneId) {
        if (awaitingSave) return
        generation++
        this.editor = editor
        attemptedApply = false
        choice = when (editor) {
            PhoneSettingEditor.WEEKDAY -> options.firstWeekday.name
            PhoneSettingEditor.THEME -> options.theme.name
            PhoneSettingEditor.VIEW -> options.defaultView.name
            PhoneSettingEditor.ALL_DAY_REMINDER -> options.allDayReminder.name
            PhoneSettingEditor.WEATHER_UNITS -> options.weatherUnits.name
            else -> ""
        }
        raw = when (editor) {
            PhoneSettingEditor.ZONE -> (options.displayZone as? DisplayZoneOption.Fixed)?.zone?.id ?: deviceZone.id
            PhoneSettingEditor.DURATION -> options.defaultDurationMinutes.toString()
            PhoneSettingEditor.TIMED_REMINDER -> (options.timedReminderMinutes ?: 10).toString()
            else -> ""
        }
        reminderEnabled = options.timedReminderMinutes != null
    }

    fun cancelEditor() {
        if (awaitingSave) return
        generation++
        editor = PhoneSettingEditor.NONE
        attemptedApply = false
    }

    fun submit(change: CalendarSettingChange, revision: Long) {
        awaitingSave = true
        submittedGeneration = generation
        submittedEditor = editor
        submittedRevision = revision
        lastChange = change
    }

    fun observeResult(saving: Boolean, error: String?, revision: Long) {
        if (!awaitingSave) return
        if (revision > submittedRevision) {
            awaitingSave = false
            if (generation == submittedGeneration && editor == submittedEditor && editor != PhoneSettingEditor.NONE) {
                editor = PhoneSettingEditor.NONE
                generation++
                attemptedApply = false
            }
        } else if (!saving && error != null) {
            awaitingSave = false
        }
    }

    fun rawChange(): CalendarSettingChange? = when (editor) {
        PhoneSettingEditor.DURATION -> raw.trim().toIntOrNull()?.takeIf { it in 1..1440 }?.let { CalendarSettingChange.Duration(it) }
        PhoneSettingEditor.ZONE -> fixedSettingsZone(raw)?.let { CalendarSettingChange.DisplayZone(DisplayZoneOption.Fixed(it)) }
        PhoneSettingEditor.TIMED_REMINDER -> if (!reminderEnabled) CalendarSettingChange.TimedReminder(null)
            else raw.trim().toIntOrNull()?.takeIf { it in 0..40320 }?.let { CalendarSettingChange.TimedReminder(it) }
        else -> null
    }

    companion object {
        val Saver = listSaver<PhoneSettingsState, Any>(
            save = { listOf(it.page.name, it.editor.name, it.raw, it.choice, it.reminderEnabled, it.attemptedApply, it.generation, it.submittedGeneration) },
            restore = {
                PhoneSettingsState().apply {
                    page = PhoneSettingsPage.valueOf(it[0] as String)
                    editor = PhoneSettingEditor.valueOf(it[1] as String)
                    raw = it[2] as String
                    choice = it[3] as String
                    reminderEnabled = it[4] as Boolean
                    attemptedApply = it[5] as Boolean
                    generation = it[6] as Long
                    submittedGeneration = it[7] as Long
                    // Pending callbacks are not restored or replayed. Raw input remains explicit.
                }
            },
        )
    }
}
