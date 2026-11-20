package dev.swapnilraj.calendar

import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.search.*
import dev.swapnilraj.calendar.ui.searchStateForContext
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class SearchWorkflowContextTest {
    @Test fun changedSearchContextCannotRenderPreviousPrivateRowsBeforeEffectsRun() {
        val range = CalendarDateRange.day(LocalDate.parse("2026-11-17"))
        val selected = CalendarSelection.Explicit(setOf(CalendarId(3)))
        val calendar = CalendarRequest(range, ZoneOffset.UTC, selected)
        val start = Instant.parse("2026-11-17T09:00:00Z")
        val hit = CalendarSearchHit(EventOccurrence(EventRef(CalendarId(3), 7, start, start.plusSeconds(3600)),
            EventTime.Timed(start, start.plusSeconds(3600)), "Sensitive fixture", 1), "Private fixture location", "Private fixture notes")
        val request = CalendarSearchRequest("fixture", calendar)
        val old = SearchUiState("fixture", calendar, status = SearchStatus.RESULTS,
            snapshot = CalendarSearchSnapshot(request, emptyList(), listOf(hit), false))
        val desired = listOf(
            old.copy(query = "different"),
            old.copy(calendar = calendar.copy(selection = CalendarSelection.Explicit(emptySet()))),
            old.copy(calendar = calendar.copy(displayZone = ZoneId.of("America/New_York"))),
            old.copy(calendar = calendar.copy(range = CalendarDateRange.day(range.start.plusDays(1)))),
            old.copy(includeDeclined = false),
            old.copy(calendar = calendar.copy(invitationFilter = InvitationFilter.PENDING_ONLY)))
        desired.forEach { target ->
            val shown = searchStateForContext(old, target.query, target.calendar.range,
                target.calendar.selection, target.calendar.displayZone, target.includeDeclined, target.calendar.invitationFilter)
            assertTrue(shown.hits.isEmpty())
            assertNull(shown.snapshot)
            assertEquals(target.query, shown.query)
            assertEquals(target.calendar, shown.calendar)
        }
        assertEquals(old.hits, searchStateForContext(old, old.query, range, selected, ZoneOffset.UTC, true).hits)
    }
}
