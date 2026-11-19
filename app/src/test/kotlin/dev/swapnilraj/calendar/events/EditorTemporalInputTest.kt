package dev.swapnilraj.calendar.events

import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.*
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class EditorTemporalInputTest {
    private val raw = EditorTemporalInput("2026-06-01", "2026-06-01", "10:00", "11:00", false, "UTC")
    private fun problem(expected: EditorProblem, value: EditorTemporalInput) {
        try { value.normalizedTime(); fail("Invalid temporal input accepted") }
        catch (invalid: InvalidInput) { assertEquals(expected, invalid.problem) }
    }
    @Test fun allDayEditorEndIsInclusiveWithoutDisplayZoneInstantConversion() {
        val value = raw.copy(allDay=true, endDate="2026-06-03", startTime="unfinished", endTime="", zone="Pacific/Apia")
        assertEquals(EventTime.AllDay(CalendarDateRange(LocalDate.parse("2026-06-01"),LocalDate.parse("2026-06-04"))), value.normalizedTime())
    }
    @Test fun missingClockTimeInGapIsNeverGuessed() {
        problem(EditorProblem.NONEXISTENT_TIME,raw.copy(startDate="2026-03-29",endDate="2026-03-29",startTime="01:30",endTime="03:00",zone="Europe/London"))
    }
    @Test fun repeatedClockTimeRequiresExplicitOffset() {
        problem(EditorProblem.AMBIGUOUS_TIME,raw.copy(startDate="2026-10-25",endDate="2026-10-25",startTime="01:45",endTime="01:15",zone="Europe/London"))
    }
    @Test fun explicitFoldOffsetsRetainActualElapsedInterval() {
        val value=raw.copy(startDate="2026-10-25",endDate="2026-10-25",startTime="01:45",endTime="01:15",zone="Europe/London",startOffset="+01:00",endOffset="Z")
        assertEquals(EventTime.Timed(Instant.parse("2026-10-25T00:45:00Z"),Instant.parse("2026-10-25T01:15:00Z")),value.normalizedTime())
    }
    @Test fun unfinishedOrNonpositiveTimeRemainsInvalid() {
        for (value in listOf(raw.copy(startDate="2026-"),raw.copy(zone="unfinished"),raw.copy(endTime="10:00"),raw.copy(endTime="09:00"),raw.copy(allDay=true,endDate="2026-05-31")))
            problem(EditorProblem.INVALID_INPUT,value)
    }
    @Test fun temporalProjectionDoesNotValidateUnrelatedPrivateFields() {
        val fields=EventEditorFields("not-a-calendar","",raw.startDate,raw.endDate,raw.startTime,raw.endTime,false,raw.zone,"","","unfinished",
            recurrence=RecurrenceEditorFields(frequency="FUTURE"),privacy="unknown")
        assertEquals(raw.normalizedTime(),EditorTemporalInput.from(fields).normalizedTime())
        assertTrue(runCatching {EventEditorController.normalizedDraft(fields)}.isFailure)
    }
    @Test fun saveNormalizationDelegatesWithoutChangingTemporalResults() {
        val fields=EventEditorFields("2","",raw.startDate,raw.endDate,raw.startTime,raw.endTime,false,raw.zone,"","","")
        for (allDay in listOf(false,true)) {
            val value=fields.copy(allDay=allDay)
            assertEquals(EditorTemporalInput.from(value).normalizedTime(),EventEditorController.normalizedDraft(value).time)
        }
    }
    @Test fun unchangedImportedMillisAndPointEventBypassAuthoredPositiveNormalizationOnlyWhenExact() {
        val at=Instant.parse("2026-06-01T10:00:00.123Z")
        for (end in listOf(at,at.plusMillis(789))) {
            val exact=EventTime.Timed(at,end)
            val binding=EditorConflictBinding("generation","revision",raw,null,null,raw,exact)
            assertEquals(exact,binding.normalizedTime())
            assertEquals(raw.copy(startTime="09:00").normalizedTime(),binding.normalizedTime(raw.copy(startTime="09:00")))
        }
    }
}
