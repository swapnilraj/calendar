package dev.swapnilraj.calendar.ui.events

import dev.swapnilraj.calendar.core.*
import java.time.*

/** Raw temporal fields only: no event text, guest identities or write authority. */
data class EditorTemporalInput(
    val startDate: String, val endDate: String, val startTime: String, val endTime: String,
    val allDay: Boolean, val zone: String, val startOffset: String = "", val endOffset: String = "",
) {
    companion object {
        fun from(fields: EventEditorFields) = EditorTemporalInput(fields.startDate, fields.endDate,
            fields.startTime, fields.endTime, fields.allDay, fields.zone, fields.startOffset, fields.endOffset)
    }

    /** Same temporal validation used by save; an all-day editor end date is inclusive. */
    fun normalizedTime(): EventTime = try {
        val start = LocalDate.parse(startDate); val end = LocalDate.parse(endDate)
        val eventZone = ZoneId.of(zone)
        if (allDay) {
            if (end < start) throw InvalidInput(EditorProblem.INVALID_INPUT)
            EventTime.AllDay(CalendarDateRange(start, end.plusDays(1)))
        } else {
            fun instant(date: LocalDate, raw: String, choice: String): Instant {
                val local = date.atTime(LocalTime.parse(raw))
                val offsets = eventZone.rules.getValidOffsets(local)
                if (offsets.isEmpty()) throw InvalidInput(EditorProblem.NONEXISTENT_TIME)
                val offset = if (offsets.size == 1) offsets.single() else offsets.firstOrNull { it.id == choice }
                    ?: throw InvalidInput(EditorProblem.AMBIGUOUS_TIME)
                return local.toInstant(offset)
            }
            val begin = instant(start, startTime, startOffset)
            val finish = instant(end, endTime, endOffset)
            if (finish <= begin) throw InvalidInput(EditorProblem.INVALID_INPUT)
            EventTime.Timed(begin, finish)
        }
    } catch (invalid: InvalidInput) { throw invalid }
    catch (_: Exception) { throw InvalidInput(EditorProblem.INVALID_INPUT) }
}

/** Observable local draft correlation only. This value cannot authorize native mutations. */
data class EditorConflictBinding(
    val generation: String, val revision: String,
    val input: EditorTemporalInput, val calendarIdentity: CalendarIdentity?, val editedRef: EventRef?,
    val baselineInput: EditorTemporalInput? = null, val preservedTime: EventTime? = null,
) {
    /** Unchanged imported time keeps exact precision, including a source point event. */
    fun normalizedTime(visibleInput: EditorTemporalInput = input): EventTime =
        if (baselineInput != null && visibleInput == baselineInput && preservedTime != null) preservedTime
        else visibleInput.normalizedTime()
}
