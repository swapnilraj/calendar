package dev.swapnilraj.calendar.ui.events

import dev.swapnilraj.calendar.core.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Presentation policy only. The durable controller and provider remain the authority for every action. */
internal class PhoneEventEditorPresentation(val controller: EventEditorController, val calendars: List<CalendarInfo>) {
    val state get() = controller.state
    val fields get() = state.fields
    val blocked get() = state.loading || state.saving || state.draftReadFailed || state.legacy || state.reviewRequired ||
        state.recoveryRequired || state.retryReady || controller.hasFrozenSubmission || controller.recoveryOnly ||
        state.problem in setOf(EditorProblem.UNSUPPORTED, EditorProblem.DRAFT_VERSION, EditorProblem.DRAFT_RETIRED) ||
        state.result is MutationResult.LocalSaved || state.result is MutationResult.LocalDeleted
    fun canEdit(field: EditableField) = !blocked && (!controller.isEditing || field in controller.existing?.allowedFields.orEmpty())
    val needsAttention get() = state.legacy || state.reviewRequired || state.recoveryRequired || controller.hasFrozenSubmission || state.draftReadFailed || state.localOutcome != null
    val canChooseCalendar get() = !blocked && !controller.isEditing
    val requiresProviderZoneChoice: Boolean get() {
        if (fields.allDay) return false
        val zone = runCatching { ZoneId.of(fields.zone) }.getOrNull() ?: return false
        if (ProviderTimeZoneCompatibility.isCompatible(zone)) return false
        if (!controller.isEditing) return true
        val source = controller.existing ?: return false
        val baseline = EventEditorController.fieldsFor(EventDraft(source.details.occurrence.ref.calendarId,
            source.details.occurrence.time, source.details.eventZone, ""))
        val timeChanged = EditableField.TIME in source.allowedFields &&
            listOf(fields.startDate, fields.endDate, fields.startTime, fields.endTime, fields.allDay, fields.zone, fields.startOffset, fields.endOffset) !=
                listOf(baseline.startDate, baseline.endDate, baseline.startTime, baseline.endTime, baseline.allDay, baseline.zone, baseline.startOffset, baseline.endOffset)
        val zoneChanged = EditableField.ZONE in source.allowedFields && (fields.zone != baseline.zone || fields.allDay != baseline.allDay)
        return timeChanged || zoneChanged
    }
    val canSave get() = !blocked && !requiresProviderZoneChoice && !controller.calendarSelectionRequired && !controller.visibilityChoiceRequired &&
        (controller.isEditing || calendars.any { it.identity == fields.targetIdentity && it.id.value.toString() == fields.calendarId && it.capabilities.createEvents == AccessDecision.ALLOWED })
    val calendar: CalendarInfo? get() = if (controller.isEditing) controller.existing?.details?.calendar?.takeIf { it.identity == controller.boundCalendarIdentity }
        else calendars.firstOrNull { it.identity == fields.targetIdentity && it.id.value.toString() == fields.calendarId }
}

internal object PhoneEventEditorText {
    fun date(raw: String, locale: Locale): String? = runCatching {
        LocalDate.parse(raw).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale))
    }.getOrNull()
    fun time(raw: String, use24Hour: Boolean, locale: Locale): String? = runCatching {
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, if (use24Hour) "Hm" else "hm")
        LocalTime.parse(raw).format(DateTimeFormatter.ofPattern(pattern, locale))
    }.getOrNull()
    fun zone(raw: String): String? = runCatching {
        val zone = ZoneId.of(raw)
        when { zone == ZoneOffset.UTC || zone.id in setOf("UTC", "Etc/UTC", "GMT") -> "UTC"
            zone is ZoneOffset -> "UTC${zone.id}"
            else -> zone.id.replace('_', ' ') }
    }.getOrNull()
    fun offsets(date: String, time: String, zone: String): List<ZoneOffset> = runCatching {
        ZoneId.of(zone).rules.getValidOffsets(LocalDate.parse(date).atTime(LocalTime.parse(time)))
    }.getOrDefault(emptyList())
    fun alerts(raw: String): List<Int>? = if (raw.isBlank()) emptyList() else runCatching {
        raw.split(',').map { it.trim().toInt() }.distinct()
    }.getOrNull()
}
