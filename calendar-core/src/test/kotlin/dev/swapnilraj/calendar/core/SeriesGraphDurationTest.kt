package dev.swapnilraj.calendar.core

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class SeriesGraphDurationTest {
    private val start = Instant.parse("2026-11-17T09:00:00Z")
    private val ref = EventRef(CalendarId(2), 10, start, start.plusSeconds(3600), RecurrenceRef(10, start))
    private fun rejected(block: () -> Unit) { try { block(); fail("Expected invalid command") } catch (_: IllegalArgumentException) { } }

    @Test fun explicitDurationHasNoKeepOrPatchAndPreservesExactIntentKind() {
        val timed = RecurrenceStart.Timed(start.atOffset(java.time.ZoneOffset.UTC).toLocalDateTime(), java.time.ZoneOffset.UTC)
        val intent = SeriesGraphDurationCommand(ref, EditToken("a".repeat(64)), SeriesGraphDuration.TimedSeconds(5400)).intent(timed)
        assertEquals(RecurrenceDurationChange.TimedElapsed(java.time.Duration.ofSeconds(5400)), intent.duration)
        assertEquals(EventPatch(), intent.fields)
        assertEquals(FieldChange.Keep, intent.newMasterStart)
        assertEquals(RecurrenceRuleChanges(), intent.rule)
        val date = RecurrenceStart.AllDay(LocalDate.of(2026, 11, 17))
        assertEquals(RecurrenceDurationChange.AllDayDays(2), SeriesGraphDurationCommand(ref, EditToken("a".repeat(64)), SeriesGraphDuration.AllDayDays(2)).intent(date).duration)
        rejected { SeriesGraphDurationCommand(ref, EditToken("a".repeat(64)), SeriesGraphDuration.TimedSeconds(60)).intent(date) }
        rejected { SeriesGraphDurationCommand(ref, EditToken("a".repeat(64)), SeriesGraphDuration.AllDayDays(2)).intent(timed) }
    }

    @Test fun nonPositiveOrUnboundOrNonNativeIdentityCannotBecomeCommand() {
        for (n in listOf(0L, -1L, Long.MIN_VALUE)) { rejected { SeriesGraphDuration.TimedSeconds(n) }; rejected { SeriesGraphDuration.AllDayDays(n) } }
        rejected { SeriesGraphDurationCommand(ref.copy(recurrence = null), EditToken("a".repeat(64)), SeriesGraphDuration.TimedSeconds(60)) }
        rejected { SeriesGraphDurationCommand(ref, EditToken("future-token"), SeriesGraphDuration.TimedSeconds(60)) }
        for (invalid in listOf(ref.copy(instanceStart = start.plusNanos(1)), ref.copy(instanceEnd = ref.instanceEnd.plusNanos(1)),
            ref.copy(recurrence = RecurrenceRef(10, start.minusNanos(1)))))
            rejected { SeriesGraphDurationCommand(invalid, EditToken("a".repeat(64)), SeriesGraphDuration.TimedSeconds(60)) }
        val negative = Instant.ofEpochMilli(-1)
        val exact = EventRef(CalendarId(2), 10, negative, negative.plusSeconds(60), RecurrenceRef(10, negative))
        assertEquals(exact, SeriesGraphDurationCommand(exact, EditToken("a".repeat(64)), SeriesGraphDuration.TimedSeconds(60)).selected)
    }

    @Test fun familyCannotAliasExistingSeriesOrNumericReceiptFamilies() {
        assertEquals("SERIES_GRAPH_DURATION_V1", SeriesGraphDurationProtocol.FAMILY)
        assertEquals("series-graph-duration-v1:", SeriesGraphDurationProtocol.FINGERPRINT_PREFIX)
        assertEquals(1, SeriesGraphDurationProtocol.RECEIPT_VERSION)
        assertFalse(SeriesGraphDurationProtocol.FAMILY.startsWith("SERIES_V1_"))
    }
    @Test fun readableCompleteSeriesDoesNotIssueDurationAuthorityByDefault() {
        val recurrenceStart = RecurrenceStart.Timed(start.atOffset(java.time.ZoneOffset.UTC).toLocalDateTime(), java.time.ZoneOffset.UTC)
        val snapshot = RecurrenceSeriesSnapshot(SeriesIdentity(CalendarId(2), 10), recurrenceStart, "FREQ=DAILY;COUNT=6",
            durationRaw = "PT1H", coverage = SeriesCoverage.COMPLETE, validatedSelection = ref)
        val details = EventDetails(EventOccurrence(ref, EventTime.Timed(start, start.plusSeconds(3600)), "Synthetic", 1), null)
        val series = EditableSeries(details, SeriesMasterFields("Synthetic"), snapshot, EditToken("a".repeat(64)), setOf(EditableField.TIME))
        assertEquals(AccessDecision.UNKNOWN, SeriesGraphDurationAccess(series).duration)
        assertEquals(AccessDecision.ALLOWED, SeriesGraphDurationAccess(series, AccessDecision.ALLOWED).duration)
    }

}
