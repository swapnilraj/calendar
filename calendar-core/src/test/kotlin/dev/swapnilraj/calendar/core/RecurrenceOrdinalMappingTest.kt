package dev.swapnilraj.calendar.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class RecurrenceOrdinalMappingTest {
    private fun date(raw: String) = RecurrenceStart.AllDay(LocalDate.parse(raw))
    private fun key(raw: String) = RecurrenceKey.Date(LocalDate.parse(raw))
    private fun timed(raw: String, offset: ZoneOffset? = null) = RecurrenceStart.Timed(LocalDateTime.parse(raw), ZoneId.of("Europe/London"), offset)

    @Test fun dailyMappingBeyondPublicThousandPreviewRetainsNominalOrdinal() {
        val start = date("2020-01-01")
        val changed = date("2030-01-01")
        val original = RecurrenceKey.Date(start.date.plusDays(5000))
        assertEquals(RecurrenceOrdinalResult.Ready(5000, RecurrenceOccurrence.AllDay(changed.date.plusDays(5000))),
            RecurrenceOrdinalMapping.map("FREQ=DAILY", start, original, "FREQ=DAILY", changed))
    }

    @Test fun candidateBudgetIsExactAndNeverReturnsNullForAnUnprovenOldOrdinal() {
        val start = date("2020-01-01")
        val original = RecurrenceKey.Date(start.date.plusDays(9999))
        assertTrue(RecurrenceOrdinalMapping.map("FREQ=DAILY", start, original, "FREQ=DAILY", start) is RecurrenceOrdinalResult.Ready)
        assertEquals(RecurrenceOrdinalResult.NeedsReview(RecurrenceOrdinalProblem.CANDIDATE_BUDGET),
            RecurrenceOrdinalMapping.map("FREQ=DAILY", start, RecurrenceKey.Date(start.date.plusDays(10000)), "FREQ=DAILY", start))
        assertEquals(RecurrenceOrdinalResult.NeedsReview(RecurrenceOrdinalProblem.INPUT_BUDGET),
            RecurrenceOrdinalMapping.map("FREQ=DAILY", start, original, "FREQ=DAILY", start, 10001))
    }

    @Test fun nullOnlyMeansNewCountOrUntilActuallyExhausted() {
        val start = date("2026-11-18")
        for (rule in listOf("FREQ=DAILY;COUNT=2", "FREQ=DAILY;UNTIL=20261119"))
            assertEquals(RecurrenceOrdinalResult.Ready(2, null), RecurrenceOrdinalMapping.map("FREQ=DAILY;COUNT=3", start,
                key("2026-11-20"), rule, start))
        assertEquals(RecurrenceOrdinalResult.NeedsReview(RecurrenceOrdinalProblem.ORIGINAL_NOT_FOUND),
            RecurrenceOrdinalMapping.map("FREQ=DAILY;COUNT=2", start, key("2026-11-20"), "FREQ=DAILY", start))
    }

    @Test fun monthlyMissingDatesAreSkippedWithoutChangingOrdinalCount() {
        val start = date("2026-01-31")
        val rule = "FREQ=MONTHLY;BYMONTHDAY=31;COUNT=3"
        assertEquals(RecurrenceOrdinalResult.Ready(2, RecurrenceOccurrence.AllDay(LocalDate.parse("2026-03-31"))),
            RecurrenceOrdinalMapping.map(rule, start, key("2026-05-31"), "FREQ=MONTHLY;BYMONTHDAY=31;COUNT=3", date("2025-12-31")))
        assertEquals(RecurrenceOrdinalResult.NeedsReview(RecurrenceOrdinalProblem.ORIGINAL_NOT_FOUND),
            RecurrenceOrdinalMapping.map(rule, start, key("2026-04-30"), rule, start))
    }

    @Test fun explicitFoldSeedKeepsItsInstantButFutureFoldOrGapNeedsReview() {
        for (offset in listOf(ZoneOffset.UTC, ZoneOffset.ofHours(1))) {
            val start = timed("2026-10-25T01:15:00", offset)
            val expected = RecurrenceOccurrence.Timed(start.localDateTime, start.instant)
            assertEquals(RecurrenceOrdinalResult.Ready(0, expected), RecurrenceOrdinalMapping.map("FREQ=DAILY;COUNT=1", start,
                RecurrenceKey.Timed(start.instant), "FREQ=DAILY;COUNT=1", start))
        }
        for ((local, original) in listOf("2026-03-28T01:15:00" to "2026-03-30T00:15:00Z", "2026-10-24T01:15:00" to "2026-10-26T01:15:00Z")) {
            val start = timed(local)
            assertEquals(RecurrenceOrdinalResult.NeedsReview(RecurrenceOrdinalProblem.CLOCK_UNCERTAINTY),
                RecurrenceOrdinalMapping.map("FREQ=DAILY;COUNT=3", start, RecurrenceKey.Timed(Instant.parse(original)), "FREQ=DAILY", start))
        }
    }

    @Test fun newSideGapCannotBeReportedAsExhaustion() {
        val old = timed("2026-03-20T01:15:00")
        val changed = timed("2026-03-28T01:15:00")
        assertEquals(RecurrenceOrdinalResult.NeedsReview(RecurrenceOrdinalProblem.CLOCK_UNCERTAINTY),
            RecurrenceOrdinalMapping.map("FREQ=DAILY;COUNT=3", old, RecurrenceKey.Timed(old.instant.plusSeconds(86400)),
                "FREQ=DAILY;COUNT=3", changed))
    }

    @Test fun excludedStartAndUnsupportedRulesNeverFabricateAFirstOccurrence() {
        val start = date("2026-11-18")
        for (rule in listOf("FREQ=WEEKLY;BYDAY=TU", "FREQ=DAILY;BYHOUR=9", "nonsense"))
            assertEquals(RecurrenceOrdinalResult.NeedsReview(RecurrenceOrdinalProblem.RULE),
                RecurrenceOrdinalMapping.map(rule, start, key("2026-11-18"), "FREQ=DAILY", start))
    }

    @Test fun mismatchedKindAndDateRangeCannotYieldCandidateIdentity() {
        val start = date("2026-11-18")
        assertEquals(RecurrenceOrdinalResult.NeedsReview(RecurrenceOrdinalProblem.KIND),
            RecurrenceOrdinalMapping.map("FREQ=DAILY", start, key("2026-11-18"), "FREQ=DAILY", timed("2026-11-18T09:00:00")))
        val last = date("9999-12-31")
        assertEquals(RecurrenceOrdinalResult.NeedsReview(RecurrenceOrdinalProblem.DATE_RANGE),
            RecurrenceOrdinalMapping.map("FREQ=DAILY", date("9999-12-30"), key("9999-12-31"), "FREQ=DAILY", last))
    }
}
