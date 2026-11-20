package dev.swapnilraj.calendar

import dev.swapnilraj.calendar.ui.planningMultiDayRange
import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class PlanningRangesTest {
    @Test fun localeWeekAndThreeDayWindowsContainTheChosenDate() {
        val chosen = LocalDate.parse("2026-11-17")
        assertEquals(LocalDate.parse("2026-11-15"), planningMultiDayRange(chosen, 7, DayOfWeek.SUNDAY).start)
        assertEquals(LocalDate.parse("2026-11-16"), planningMultiDayRange(chosen, 7, DayOfWeek.MONDAY).start)
        val three = planningMultiDayRange(chosen, 3, DayOfWeek.SUNDAY)
        assertEquals(chosen, three.start)
        assertEquals(chosen.plusDays(3), three.endExclusive)
    }
    @Test fun supportedYearEdgesKeepACompleteWindowWithoutLosingTheChosenDate() {
        for (date in listOf(LocalDate.of(1, 1, 1), LocalDate.of(9999, 12, 31))) {
            for (days in listOf(3, 7)) {
                val range = planningMultiDayRange(date, days, DayOfWeek.SUNDAY)
                assertTrue(date in range)
                assertTrue(range.start >= LocalDate.of(1, 1, 1))
                assertTrue(range.endExclusive <= LocalDate.of(10000, 1, 1))
                assertEquals(days.toLong(), java.time.temporal.ChronoUnit.DAYS.between(range.start, range.endExclusive))
            }
        }
    }
}
