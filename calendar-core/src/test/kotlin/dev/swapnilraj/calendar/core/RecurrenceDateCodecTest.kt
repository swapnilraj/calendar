package dev.swapnilraj.calendar.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class RecurrenceDateCodecTest {
    private val allDay = RecurrenceStart.AllDay(LocalDate.of(2026, 11, 17))
    private val timed = RecurrenceStart.Timed(LocalDateTime.parse("2026-11-17T09:00:00"), ZoneId.of("Europe/London"))
    private val compat = AllDayDateTimePolicy.PROVIDER_UTC_MIDNIGHT
    private fun date(value: String) = RecurrenceKey.Date(LocalDate.parse(value))
    private fun instant(value: String) = RecurrenceKey.Timed(Instant.parse(value))
    private fun keys(result: ParsedRecurrenceDates) = (result as ParsedRecurrenceDates.Supported).keys

    @Test fun nullAndEmptyAreSupportedAndPreservedExactly() {
        assertEquals(ParsedRecurrenceDates.Supported(null, emptySet<RecurrenceKey>()), RecurrenceDateCodec.parse(null, timed))
        assertEquals(ParsedRecurrenceDates.Supported("", emptySet<RecurrenceKey>()), RecurrenceDateCodec.parse("", allDay))
    }

    @Test fun bareDateListRetainsLeapDatesAndDeduplicatesWithoutChangingRaw() {
        val raw = "20261117,20280229,20261117"
        val parsed = RecurrenceDateCodec.parse(raw, allDay)
        assertEquals(setOf(date("2026-11-17"), date("2028-02-29")), keys(parsed))
        assertEquals(raw, parsed.raw)
    }

    @Test fun utcDateTimesRetainExactSecondsAndRepeatedHourIdentity() {
        val raw = "20261025T003017Z,20261025T013017Z"
        val parsed = RecurrenceDateCodec.parse(raw, timed)
        assertEquals(setOf(instant("2026-10-25T00:30:17Z"), instant("2026-10-25T01:30:17Z")), keys(parsed))
        assertEquals(raw, parsed.raw)
    }

    @Test fun rfcDatePropertyAndParameterNamesPreserveRawCase() {
        val raw = "exdate;value=date:20261117,20261118\r\n"
        val parsed = RecurrenceDateCodec.parse(raw, allDay)
        assertEquals(setOf(date("2026-11-17"), date("2026-11-18")), keys(parsed))
        assertEquals(raw, parsed.raw)
    }

    @Test fun explicitTimezoneResolvesLocalValuesIndependentlyOfStartZone() {
        val raw = "RDATE;VALUE=DATE-TIME;TZID=America/New_York:20261117T090017,20260717T090017"
        assertEquals(setOf(instant("2026-11-17T14:00:17Z"), instant("2026-07-17T13:00:17Z")), keys(RecurrenceDateCodec.parse(raw, timed)))
    }

    @Test fun quotedTzidAndFractionalHourZonesAreRecognizedWithoutFallback() {
        assertEquals(setOf(instant("2026-11-17T03:15:00Z")),
            keys(RecurrenceDateCodec.parse("TZID=\"Asia/Kathmandu\":20261117T090000", timed)))
        assertEquals(setOf(instant("2026-11-17T03:15:00Z")),
            keys(RecurrenceDateCodec.parse("TZID=\"+05:45\":20261117T090000", timed)))
    }

    @Test fun androidBareZonePrefixUsesExplicitZoneAndZOverridesItPerToken() {
        val raw = "Europe/London;20260717T090000,20260717T090000Z"
        assertEquals(setOf(instant("2026-07-17T08:00:00Z"), instant("2026-07-17T09:00:00Z")), keys(RecurrenceDateCodec.parse(raw, timed)))
        assertEquals(raw, RecurrenceDateCodec.parse(raw, timed).raw)
    }

    @Test fun knownZoneLocalOverlapAndGapStayUnsupportedRatherThanGuessingOffsets() {
        assertEquals(ParsedRecurrenceDates.Unsupported("TZID=Europe/London:20261025T013000", RecurrenceDateProblem.AMBIGUOUS_TIME),
            RecurrenceDateCodec.parse("TZID=Europe/London:20261025T013000", timed))
        assertEquals(ParsedRecurrenceDates.Unsupported("TZID=Europe/London:20260329T013000", RecurrenceDateProblem.NONEXISTENT_TIME),
            RecurrenceDateCodec.parse("TZID=Europe/London:20260329T013000", timed))
    }

    @Test fun floatingTimesAreUnsupportedEvenWhenTheStartZoneWouldResolveThem() {
        for (raw in listOf("20261117T090000", "VALUE=DATE-TIME:20261117T090000", "EXDATE:20261117T090000")) {
            assertEquals(ParsedRecurrenceDates.Unsupported(raw, RecurrenceDateProblem.FLOATING_TIME), RecurrenceDateCodec.parse(raw, timed))
        }
    }

    @Test fun unknownTimezoneNeverFallsBackToGmt() {
        for (raw in listOf("TZID=Mars/Olympus:20261117T090000", "Not/AZone;20261117T090000")) {
            assertEquals(ParsedRecurrenceDates.Unsupported(raw, RecurrenceDateProblem.UNKNOWN_TIME_ZONE), RecurrenceDateCodec.parse(raw, timed))
        }
    }

    @Test fun strictAllDayPolicyRejectsDatetimeStorageWithoutCoercion() {
        val raw = "20261117T000000Z"
        assertEquals(ParsedRecurrenceDates.Invalid(raw, RecurrenceDateProblem.TYPE_MISMATCH), RecurrenceDateCodec.parse(raw, allDay))
    }

    @Test fun providerAllDayCompatibilityRequiresExplicitUtcMidnight() {
        assertEquals(setOf(date("2026-11-17")), keys(RecurrenceDateCodec.parse("20261117T000000Z", allDay, allDayPolicy = compat)))
        assertEquals(setOf(date("2026-11-17")), keys(RecurrenceDateCodec.parse("UTC;20261117T000000", allDay, allDayPolicy = compat)))
        assertEquals(setOf(date("2026-11-17")), keys(RecurrenceDateCodec.parse("TZID=Etc/UTC:20261117T000000", allDay, allDayPolicy = compat)))
    }

    @Test fun compatibilityDoesNotInferLondonWinterOrConvertNonMidnightValues() {
        for (raw in listOf("TZID=Europe/London:20261117T000000", "20261117T010000Z", "TZID=+01:20261117T000000")) {
            assertTrue(RecurrenceDateCodec.parse(raw, allDay, allDayPolicy = compat) is ParsedRecurrenceDates.Invalid)
        }
    }

    @Test fun compatMayCombineDateAndUtcMidnightSerializationsButTimedNeverAcceptsDates() {
        val raw = "20261117,20261117T000000Z,20261118T000000Z"
        assertEquals(setOf(date("2026-11-17"), date("2026-11-18")), keys(RecurrenceDateCodec.parse(raw, allDay, allDayPolicy = compat)))
        assertTrue(RecurrenceDateCodec.parse(raw, timed) is ParsedRecurrenceDates.Invalid)
        assertTrue(RecurrenceDateCodec.parse(raw, allDay) is ParsedRecurrenceDates.Invalid)
    }

    @Test fun homogeneousMultilineGroupsShareOneBudgetAndRetainExactContent() {
        val raw = "EXDATE;TZID=America/New_York:20261117T090000\r\nEXDATE:20261117T140000Z,20261118T140000Z\r\n"
        val parsed = RecurrenceDateCodec.parse(raw, timed)
        assertEquals(setOf(instant("2026-11-17T14:00:00Z"), instant("2026-11-18T14:00:00Z")), keys(parsed))
        assertEquals(raw, parsed.raw)
        assertEquals(ParsedRecurrenceDates.Unsupported(raw, RecurrenceDateProblem.VALUE_LIMIT),
            RecurrenceDateCodec.parse(raw, timed, RecurrenceDateBudget(maxValues = 2)))
    }

    @Test fun multilineMixedDateAndTimedKindsAreRejectedWithoutPartialKeys() {
        val raw = "EXDATE;VALUE=DATE:20261117\nEXDATE:20261118T000000Z"
        assertTrue(RecurrenceDateCodec.parse(raw, timed) is ParsedRecurrenceDates.Invalid)
        assertTrue(RecurrenceDateCodec.parse(raw, allDay) is ParsedRecurrenceDates.Invalid)
    }

    @Test fun inclusionAndExclusionPropertiesCannotBeSilentlyUnioned() {
        val raw = "RDATE;VALUE=DATE:20261117\nEXDATE;VALUE=DATE:20261118"
        assertEquals(ParsedRecurrenceDates.Invalid(raw, RecurrenceDateProblem.TYPE_MISMATCH), RecurrenceDateCodec.parse(raw, allDay))
    }

    @Test fun periodsUnsupportedParametersAndFoldedLinesArePreservedUnsupported() {
        val period = "RDATE;VALUE=PERIOD:20261117T090000Z/PT1H"
        assertEquals(ParsedRecurrenceDates.Unsupported(period, RecurrenceDateProblem.PERIOD_VALUE), RecurrenceDateCodec.parse(period, timed))
        val barePeriod = "20261117T090000Z/20261117T100000Z"
        assertEquals(ParsedRecurrenceDates.Unsupported(barePeriod, RecurrenceDateProblem.PERIOD_VALUE), RecurrenceDateCodec.parse(barePeriod, timed))
        val unknown = "EXDATE;X-UNKNOWN=1:20261117T090000Z"
        assertEquals(ParsedRecurrenceDates.Unsupported(unknown, RecurrenceDateProblem.UNSUPPORTED_PARAMETER), RecurrenceDateCodec.parse(unknown, timed))
        val folded = "EXDATE:20261117T090000Z,\r\n 20261118T090000Z"
        assertEquals(ParsedRecurrenceDates.Unsupported(folded, RecurrenceDateProblem.FOLDED_LINE), RecurrenceDateCodec.parse(folded, timed))
    }

    @Test fun malformedDatesTimesAndEmptyListTokensAreNotNormalized() {
        for (raw in listOf("20260229", "20261301", "00000101", "20261117,", ",20261117", "20261117,,20261118", "20261117\n\n20261118")) {
            val parsed = RecurrenceDateCodec.parse(raw, allDay)
            assertTrue("Invalid DATE: $raw, got $parsed", parsed is ParsedRecurrenceDates.Invalid)
            assertEquals(raw, parsed.raw)
        }
        for (raw in listOf("20261117T240000Z", "20261117T090061Z", "20261317T090000Z", "20261117T0900Z")) {
            val parsed = RecurrenceDateCodec.parse(raw, timed)
            assertTrue("Invalid DATE-TIME: $raw, got $parsed", parsed is ParsedRecurrenceDates.Invalid)
            assertEquals(raw, parsed.raw)
        }
    }

    @Test fun precisionAndLeapSecondAreNeverSilentlyTruncated() {
        val fractional = "20261117T090000.123Z"
        assertEquals(ParsedRecurrenceDates.Unsupported(fractional, RecurrenceDateProblem.UNSUPPORTED_PRECISION), RecurrenceDateCodec.parse(fractional, timed))
        val leap = "20161231T235960Z"
        assertEquals(ParsedRecurrenceDates.Unsupported(leap, RecurrenceDateProblem.LEAP_SECOND), RecurrenceDateCodec.parse(leap, timed))
    }

    @Test fun contradictoryTypesParametersAndZoneDeclarationsAreInvalid() {
        for (raw in listOf("VALUE=DATE:20261117T000000Z", "VALUE=DATE;VALUE=DATE:20261117", "VALUE=DATE;TZID=UTC:20261117")) {
            assertTrue(RecurrenceDateCodec.parse(raw, allDay, allDayPolicy = compat) is ParsedRecurrenceDates.Invalid)
        }
        val raw = "TZID=America/New_York:20261117T090000Z"
        assertEquals(ParsedRecurrenceDates.Invalid(raw, RecurrenceDateProblem.CONTRADICTORY_PARAMETERS), RecurrenceDateCodec.parse(raw, timed))
    }

    @Test fun duplicateValuesConsumeBudgetBeforeDeduplicationAndInputSizeIsBounded() {
        val duplicates = "20261117,20261117,20261117"
        assertEquals(ParsedRecurrenceDates.Unsupported(duplicates, RecurrenceDateProblem.VALUE_LIMIT),
            RecurrenceDateCodec.parse(duplicates, allDay, RecurrenceDateBudget(maxValues = 2)))
        assertEquals(ParsedRecurrenceDates.Unsupported("20261117", RecurrenceDateProblem.INPUT_LIMIT),
            RecurrenceDateCodec.parse("20261117", allDay, RecurrenceDateBudget(maxChars = 7)))
    }
}
