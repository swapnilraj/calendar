package dev.swapnilraj.calendar.events

import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.*
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.MonthDay
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecurrenceEditorFieldsTest {
    @Test fun partialInvalidAndInactiveInputsRoundTripWithoutNormalization() {
        val raw = RecurrenceEditorFields(frequency = "MONTHLY", interval = "", weekdays = listOf("MONDAY", "future-day"),
            monthDay = "3", ordinal = "-", weekday = "", endMode = "DATE", count = "not a number", throughDate = "2028-")
        assertEquals(raw, RecurrenceEditorFieldsJson.decode(RecurrenceEditorFieldsJson.encode(raw)))
        assertTrue(runCatching { raw.normalized() }.isFailure)
    }
    @Test fun supportedAuthoredSpecsRoundTripIntoTypedValues() {
        val patterns = listOf(RecurrencePattern.Daily, RecurrencePattern.Weekly(setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)),
            RecurrencePattern.MonthlyDate(31), RecurrencePattern.MonthlyWeekday(-1, DayOfWeek.TUESDAY), RecurrencePattern.Yearly(MonthDay.of(2, 29)))
        val ends = listOf(RecurrenceEnd.Never, RecurrenceEnd.Count(7), RecurrenceEnd.ThroughDate(LocalDate.of(2030, 6, 4)))
        for (pattern in patterns) for (end in ends) {
            val spec = RecurrenceSpec(pattern, 2, end, DayOfWeek.SUNDAY)
            assertEquals(spec, RecurrenceEditorFields.from(spec).normalized())
            assertEquals(spec, AuthoredRecurrenceJson.decode(AuthoredRecurrenceJson.encode(spec)))
        }
    }
    @Test fun unknownSchemaAndNonStringRawInputsAreRejectedWithoutFallback() {
        val raw = RecurrenceEditorFieldsJson.encode(RecurrenceEditorFields())
        assertTrue(runCatching { RecurrenceEditorFieldsJson.decode(JSONObject(raw.toString()).put("schema", 99)) }.isFailure)
        assertTrue(runCatching { RecurrenceEditorFieldsJson.decode(JSONObject(raw.toString()).put("interval", 2)) }.isFailure)
        assertTrue(runCatching { RecurrenceEditorFieldsJson.decode(JSONObject(raw.toString()).put("unknown", "future")) }.isFailure)
        assertTrue(runCatching { RecurrenceEditorFields(frequency = "FUTURE").normalized() }.isFailure)
    }
    @Test fun typedFrozenRecurrenceRejectsUnknownFieldsCoercionAndAuthorBounds() {
        val encoded = AuthoredRecurrenceJson.encode(RecurrenceSpec(RecurrencePattern.Daily))
        for (bad in listOf(JSONObject(encoded.toString()).put("schema", 99),
            JSONObject(encoded.toString()).put("interval", "1"),
            JSONObject(encoded.toString()).put("interval", 1.5),
            JSONObject(encoded.toString()).put("interval", RecurrenceCreation.MAX_INTERVAL + 1),
            JSONObject(encoded.toString()).put("unknown", "future"))) {
            assertTrue(runCatching { AuthoredRecurrenceJson.decode(bad) }.isFailure)
        }
        val imported = RecurrenceSpec(RecurrencePattern.Daily, end = RecurrenceEnd.ExactUntil(java.time.Instant.parse("2030-01-01T12:00:00Z")))
        assertTrue(runCatching { AuthoredRecurrenceJson.encode(imported) }.isFailure)
    }

    @Test fun onlyExplicitPatternChoiceUsesProvidedStartDateAndPreservesOtherRawInputs() {
        val raw = RecurrenceEditorFields(count = "", throughDate = "partial")
        assertEquals(emptyList<String>(), raw.weekdays)
        val weekly = raw.chooseFrequency("WEEKLY", "2028-06-15")
        assertEquals(listOf("THURSDAY"), weekly.weekdays); assertEquals("partial", weekly.throughDate)
        val invalid = raw.chooseFrequency("WEEKLY", "partial-date")
        assertTrue(invalid.weekdays.isEmpty()); assertTrue(runCatching { invalid.normalized() }.isFailure)
    }
}
