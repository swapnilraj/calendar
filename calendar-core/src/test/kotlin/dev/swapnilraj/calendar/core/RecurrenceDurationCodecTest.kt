package dev.swapnilraj.calendar.core

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class RecurrenceDurationCodecTest {
    private val london = ZoneId.of("Europe/London")
    private fun parsed(raw: String) = RecurrenceDurationCodec.parse(raw) as ParsedRecurrenceDuration.Supported
    private fun timed(raw: String, local: String, zone: ZoneId = london, offset: ZoneOffset? = null): EventTime.Timed =
        (RecurrenceDurationCodec.resolve(parsed(raw), RecurrenceStart.Timed(LocalDateTime.parse(local), zone, offset))
            as ResolvedRecurrenceDuration.Resolved).time as EventTime.Timed
    private fun review(raw: String, start: RecurrenceStart) =
        assertTrue(RecurrenceDurationCodec.resolve(parsed(raw), start) is ResolvedRecurrenceDuration.NeedsReview)

    @Test fun nominalDayAcrossSpringIsTwentyThreeElapsedHours() {
        assertEquals(Duration.ofHours(23), timed("P1D", "2026-03-28T12:00:00").duration)
        assertEquals(Duration.ofHours(24), timed("PT24H", "2026-03-28T12:00:00").duration)
    }

    @Test fun nominalDayAcrossFallIsTwentyFiveElapsedHours() {
        assertEquals(Duration.ofHours(25), timed("P1D", "2026-10-24T12:00:00").duration)
        assertEquals(Duration.ofHours(24), timed("PT24H", "2026-10-24T12:00:00").duration)
    }

    @Test fun halfHourTransitionsRetainNominalDays() {
        val zone = ZoneId.of("Australia/Lord_Howe")
        assertEquals(Duration.ofMinutes(23 * 60 + 30), timed("P1D", "2026-10-03T12:00:00", zone).duration)
        assertEquals(Duration.ofMinutes(24 * 60 + 30), timed("P1D", "2026-04-04T12:00:00", zone).duration)
    }

    @Test fun weeksAreNominalAndMixedUnitsApplyDaysBeforeElapsedTime() {
        assertEquals(Duration.ofHours(167), timed("P1W", "2026-03-23T12:00:00").duration)
        val event = timed("P1DT2H3M4S", "2026-03-28T00:30:00")
        assertEquals(LocalDateTime.parse("2026-03-29T03:33:04"), event.end.atZone(london).toLocalDateTime())
        assertEquals(Duration.ofHours(26).plusMinutes(3).plusSeconds(4), event.duration)
    }

    @Test fun nominalEndpointGapAndOverlapRequireReview() {
        for (local in listOf("2026-03-28T01:30:00", "2026-10-24T01:30:00")) {
            val start = RecurrenceStart.Timed(LocalDateTime.parse(local), london)
            review("P1D", start)
            // Later elapsed units cannot silently resolve an unprovable nominal intermediate end.
            review("P1DT5H", start)
        }
        val zone = ZoneId.of("Australia/Lord_Howe")
        review("P1D", RecurrenceStart.Timed(LocalDateTime.parse("2026-10-03T02:15:00"), zone))
        review("P1D", RecurrenceStart.Timed(LocalDateTime.parse("2026-04-04T01:45:00"), zone))
    }

    @Test fun elapsedDurationPreservesBothStartingOverlapOffsets() {
        for (offset in listOf(ZoneOffset.UTC, ZoneOffset.ofHours(1))) {
            val event = timed("PT30M", "2026-10-25T01:15:00", offset = offset)
            assertEquals(LocalDateTime.parse("2026-10-25T01:15:00").toInstant(offset), event.start)
            assertEquals(Duration.ofMinutes(30), event.duration)
            assertEquals(event, timed("P0DT30M", "2026-10-25T01:15:00", offset = offset))
        }
    }

    @Test fun allDayWholeDatesIgnoreZonesAndCrossLeapDay() {
        val start = RecurrenceStart.AllDay(LocalDate.parse("2028-02-28"))
        for ((raw, end) in listOf("P2D" to "2028-03-01", "P1W" to "2028-03-06")) {
            val event = (RecurrenceDurationCodec.resolve(parsed(raw), start) as ResolvedRecurrenceDuration.Resolved).time
            assertEquals(EventTime.AllDay(CalendarDateRange(start.date, LocalDate.parse(end))), event)
        }
    }

    @Test fun allDayRejectsTimeUnitsEvenWhenEquivalentOrZero() {
        val start = RecurrenceStart.AllDay(LocalDate.parse("2026-10-25"))
        for (raw in listOf("PT24H", "PT86400S", "P1DT0S", "P1DT0H")) review(raw, start)
    }

    @Test fun signedAndZeroGrammarIsRetainedButEventResolutionRequiresPositive() {
        val start = RecurrenceStart.AllDay(LocalDate.parse("2026-01-01"))
        for (raw in listOf("-P1D", "P0D", "+P0W", "-PT0S")) {
            assertEquals(raw, parsed(raw).raw)
            review(raw, start)
        }
        assertEquals(1, parsed("+P1D").parts.sign)
        assertEquals(-1, parsed("-P1D").parts.sign)
        assertTrue(RecurrenceDurationCodec.resolve(parsed("+P1D"), start) is ResolvedRecurrenceDuration.Resolved)
    }

    @Test fun exactRawAndUnnormalizedComponentsAreRetained() {
        val raw = "+P0001DT025H090M061S"
        val value = parsed(raw)
        assertEquals(raw, value.raw)
        assertEquals(RfcDurationParts(days = 1, hours = 25, minutes = 90, seconds = 61, hasTime = true), value.parts)
        assertEquals(1L, parsed("P1W").parts.weeks)
        assertTrue(parsed("PT0H0M1S").parts.hasTime)
    }

    @Test fun malformedDurationsDoNotPartiallyParse() {
        for (raw in listOf("", "P", "PT", "P1DT", "P1W1D", "P1WT1H", "PT1S2M", "PT1H2H", "PT1H2S", "P-1D",
            "P1Dgarbage", " P1D", "P1D\n", "p1d", "PT+1S", "PT1.5.2S")) {
            assertTrue(raw, RecurrenceDurationCodec.parse(raw) is ParsedRecurrenceDuration.Invalid)
        }
    }

    @Test fun isoExtensionsRemainUnsupportedWithoutTruncation() {
        for (raw in listOf("P1Y", "P1M", "P1Y2M3D", "PT0.5S", "PT1,5H", "P1.5W")) {
            val value = RecurrenceDurationCodec.parse(raw)
            assertTrue(raw, value is ParsedRecurrenceDuration.Unsupported)
            assertEquals(raw, value.raw)
        }
    }

    @Test fun componentAndInputBudgetsFailWithoutLosingRaw() {
        val huge = "P9223372036854775808D"
        assertTrue(RecurrenceDurationCodec.parse(huge) is ParsedRecurrenceDuration.Invalid)
        val value = RecurrenceDurationCodec.parse("P001D", maxChars = 4)
        assertTrue(value is ParsedRecurrenceDuration.Unsupported)
        assertEquals("P001D", value.raw)
        assertTrue(RecurrenceDurationCodec.parse("P1D", maxChars = 3) is ParsedRecurrenceDuration.Supported)
    }

    @Test fun checkedArithmeticAndDateBoundsRefuseOverflow() {
        val start = RecurrenceStart.Timed(LocalDateTime.parse("2026-01-01T00:00:00"), london)
        for (raw in listOf("P9223372036854775807W", "P9223372036854775807D", "PT9223372036854775807H",
            "PT2562047788015215H31M", "PT9223372036854775807S")) review(raw, start)
        review("P1D", RecurrenceStart.AllDay(LocalDate.parse("9999-12-31")))
        review("PT1S", RecurrenceStart.Timed(LocalDateTime.parse("9999-12-31T23:59:59"), ZoneOffset.UTC))
    }

    @Test fun explicitDurationChangesEncodeWithoutRewritingKeptSource() {
        assertNull(RecurrenceDurationCodec.encode(RecurrenceDurationChange.KeepRaw))
        assertEquals("PT86400S", RecurrenceDurationCodec.encode(RecurrenceDurationChange.TimedElapsed(Duration.ofHours(24))))
        assertEquals("P2D", RecurrenceDurationCodec.encode(RecurrenceDurationChange.AllDayDays(2)))
    }
}
