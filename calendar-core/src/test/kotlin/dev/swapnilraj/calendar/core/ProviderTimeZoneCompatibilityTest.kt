package dev.swapnilraj.calendar.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class ProviderTimeZoneCompatibilityTest {
    @Test fun bareNonzeroOffsetsAreNotSilentlyAcceptedAsGmt() {
        for (id in listOf("+05:30", "-03:30", "+14:00", "UTC+05:30", "UT-03:30")) {
            val zone = ZoneId.of(id)
            assertEquals("GMT", TimeZone.getTimeZone(zone.id).id)
            assertFalse(id, ProviderTimeZoneCompatibility.isCompatible(zone))
        }
        // The adapter overload masks the difference, which is why testing that overload is unsafe.
        val bare = ZoneId.of("+05:30")
        assertEquals(bare.rules, TimeZone.getTimeZone(bare).toZoneId().rules)
        assertNotEquals(bare.rules, TimeZone.getTimeZone(bare.id).toZoneId().rules)
    }

    @Test fun utcAndTzdbAliasesUseEquivalentRulesWithoutRenamingInput() {
        for (id in listOf("Z", "UTC", "UT", "GMT", "Etc/UTC", "Etc/GMT-5", "Etc/GMT+3",
            "Europe/London", "Europe/Belfast", "Asia/Kathmandu", "US/Eastern", "America/New_York")) {
            val zone = ZoneId.of(id)
            assertTrue(id, ProviderTimeZoneCompatibility.isCompatible(zone))
            assertEquals(id, zone.id)
        }
    }

    @Test fun customGmtOffsetsAreNotPortableEvenWhenNewerStringParserAcceptsThem() {
        for (id in listOf("GMT+05:30", "GMT-03:30", "GMT+14:00")) {
            val zone = ZoneId.of(id)
            assertEquals(zone.rules, TimeZone.getTimeZone(zone.id).toZoneId().rules)
            assertFalse(ProviderTimeZoneCompatibility.isCompatible(zone))
            val start = RecurrenceStart.Timed(LocalDateTime.parse("2026-11-17T00:15:00"), zone)
            val time = EventTime.Timed(start.instant, start.instant.plusSeconds(3600))
            assertEquals(RecurrenceCreationResult.Invalid(RecurrenceCreationProblem.PROVIDER_TIME_ZONE),
                RecurrenceCreation.compile(start, time, RecurrenceSpec(RecurrencePattern.Daily)))
            assertEquals(RecurrenceMasterTemplateResult.NeedsReview(RecurrenceMasterTemplateProblem.PROVIDER_TIME_ZONE),
                RecurrenceMasterTemplates.resolve(start, "PT1H"))
        }
    }

    @Test fun weeklyCreationNearLocalMidnightRejectsWrongStringDialectBeforePlan() {
        val start = RecurrenceStart.Timed(LocalDateTime.parse("2026-11-17T00:15:00"), ZoneId.of("+05:30"))
        val time = EventTime.Timed(start.instant, start.instant.plusSeconds(3600))
        val spec = RecurrenceSpec(RecurrencePattern.Weekly(setOf(java.time.DayOfWeek.TUESDAY)))
        assertEquals(RecurrenceCreationResult.Invalid(RecurrenceCreationProblem.PROVIDER_TIME_ZONE),
            RecurrenceCreation.compile(start, time, spec))
        assertEquals(RecurrenceMasterTemplateResult.NeedsReview(RecurrenceMasterTemplateProblem.PROVIDER_TIME_ZONE),
            RecurrenceMasterTemplates.resolve(start, "PT3600S"))
    }

    @Test fun supportedTzdbFixedZoneAndRegionKeepExactMasterAndAuthoredZone() {
        for (id in listOf("Z", "UTC", "Etc/GMT-5", "Asia/Kolkata", "Europe/Belfast")) {
            val zone = ZoneId.of(id)
            val start = RecurrenceStart.Timed(LocalDateTime.parse("2026-11-17T00:15:00"), zone)
            val time = EventTime.Timed(start.instant, start.instant.plusSeconds(3600))
            val result = RecurrenceCreation.compile(start, time, RecurrenceSpec(RecurrencePattern.Daily)) as RecurrenceCreationResult.Ready
            assertEquals(zone, result.plan.eventZone)
            assertEquals(start.instant, result.plan.dtstart)
            val template = (RecurrenceMasterTemplates.resolve(start, "PT1H") as RecurrenceMasterTemplateResult.Ready).template
            assertEquals(zone, template.eventZone)
            assertEquals(time, template.time)
        }
    }

    @Test fun allDayRemainsUtcDatesWithoutAnyDisplayZoneInterpretation() {
        val start = RecurrenceStart.AllDay(LocalDate.parse("2026-11-17"))
        val time = EventTime.AllDay(CalendarDateRange.day(start.date))
        val result = RecurrenceCreation.compile(start, time, RecurrenceSpec(RecurrencePattern.Daily)) as RecurrenceCreationResult.Ready
        assertEquals(java.time.ZoneOffset.UTC, result.plan.eventZone)
        assertEquals("P1D", result.plan.durationRaw)
        assertEquals(time, (RecurrenceMasterTemplates.resolve(start, "P1D") as RecurrenceMasterTemplateResult.Ready).template.time)
    }
}
