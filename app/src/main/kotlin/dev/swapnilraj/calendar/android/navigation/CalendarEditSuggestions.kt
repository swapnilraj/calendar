package dev.swapnilraj.calendar.android.navigation

import dev.swapnilraj.calendar.core.EditableField
import dev.swapnilraj.calendar.ui.events.EventEditorFields
import java.time.ZoneId
import java.time.ZoneOffset

/** Raw overlay only. The immutable original baseline is never constructed from caller input. */
internal object CalendarEditSuggestions {
    fun overlay(original: EventEditorFields, request: CalendarEditReviewRequest): EventEditorFields {
        val seed = request.proposed
        val allDay = seed.occurrence.allDay ?: original.allDay
        val changedKind = allDay != original.allDay
        val zone = if (allDay) ZoneOffset.UTC else seed.timeZone ?: if (original.allDay) request.reviewedZone else ZoneId.of(original.zone)
        var fields = original.copy(title = seed.title ?: original.title, location = seed.location ?: original.location,
            description = seed.description ?: original.description, availability = seed.availability?.toString() ?: original.availability,
            privacy = seed.accessLevel?.toString() ?: original.privacy, allDay = allDay,
            zone = if (changedKind || seed.timeZone != null) zone.id else original.zone)
        if (changedKind) fields = fields.copy(startDate = "", endDate = "", startTime = "", endTime = "", startOffset = "", endOffset = "")
        // Re-selecting the renderer is zone-only authoring for a missing source zone. Its
        // unchanged raw times/offsets must not become a spurious TIME delta (or lose fold choice).
        else if (seed.timeZone != null && zone.id != original.zone) fields = fields.copy(startOffset = "", endOffset = "")
        seed.occurrence.begin?.atZone(zone)?.let { start ->
            fields = fields.copy(startDate = start.toLocalDate().toString(), startTime = if (allDay) "" else start.toLocalTime().toString(),
                startOffset = if (allDay) "" else start.offset.id)
        }
        seed.occurrence.end?.atZone(zone)?.let { end ->
            fields = fields.copy(endDate = (if (allDay) end.toLocalDate().minusDays(1) else end.toLocalDate()).toString(),
                endTime = if (allDay) "" else end.toLocalTime().toString(), endOffset = if (allDay) "" else end.offset.id)
        }
        // Recurrence and guests cannot be represented by an ordinary one-off update. They are
        // retained in the request and block handoff, rather than being removed by this overlay.
        return fields
    }

    fun issues(original: EventEditorFields, suggested: EventEditorFields, allowed: Set<EditableField>, request: CalendarEditReviewRequest): Set<CalendarEditReviewIssue> = buildSet {
        if (request.proposed.guests.isNotEmpty()) add(CalendarEditReviewIssue.GUESTS)
        if (!request.proposed.recurrenceRule.isNullOrEmpty()) add(CalendarEditReviewIssue.RECURRENCE)
        if (suggested.allDay && request.proposed.timeZone != null && request.proposed.timeZone.normalized() != ZoneOffset.UTC) add(CalendarEditReviewIssue.FIELD_NOT_ALLOWED)
        val changed = buildSet {
            if (original.title != suggested.title) add(EditableField.TITLE)
            if (original.location != suggested.location) add(EditableField.LOCATION)
            if (original.description != suggested.description) add(EditableField.DESCRIPTION)
            if (original.availability != suggested.availability) add(EditableField.AVAILABILITY)
            if (original.privacy != suggested.privacy) add(EditableField.PRIVACY)
            if (original.zone != suggested.zone || original.allDay != suggested.allDay) add(EditableField.ZONE)
            if (listOf(original.startDate, original.endDate, original.startTime, original.endTime, original.allDay, original.zone, original.startOffset, original.endOffset) !=
                listOf(suggested.startDate, suggested.endDate, suggested.startTime, suggested.endTime, suggested.allDay, suggested.zone, suggested.startOffset, suggested.endOffset)) add(EditableField.TIME)
        }
        if (!allowed.containsAll(changed)) add(CalendarEditReviewIssue.FIELD_NOT_ALLOWED)
    }
}
