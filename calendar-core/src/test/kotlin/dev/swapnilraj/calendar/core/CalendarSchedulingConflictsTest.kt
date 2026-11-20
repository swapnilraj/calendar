package dev.swapnilraj.calendar.core

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class CalendarSchedulingConflictsTest {
    private val identity = CalendarIdentity(CalendarId(1), CalendarAccount("synthetic", "local"), null)
    private val time = EventTime.Timed(Instant.parse("2026-06-01T10:00:00Z"), Instant.parse("2026-06-01T11:00:00Z"))
    private fun request(selection: Set<CalendarIdentity> = setOf(identity), draft: ConflictDraftRevision = ConflictDraftRevision("draft", 1)) =
        ConflictReadRequest(draft, time, ZoneId.of("UTC"), selection)
    @Test fun requestOwnsSelectionAndCannotBeMutatedThroughItsGetter() {
        val mutable = mutableSetOf(identity)
        val request = request(mutable)
        mutable.clear()
        assertEquals(setOf(identity), request.calendars)
        try { (request.calendars as MutableSet<CalendarIdentity>).clear(); fail("Mutable request") }
        catch (_: UnsupportedOperationException) { }
    }
    @Test fun noCallerSourceRevisionIsNeededAndIssuedRevisionsDoNotAlterRequestMatch() {
        val request = request()
        for (source in listOf(ConflictSourceRevision("adapter-one", 1), ConflictSourceRevision("adapter-two", 2))) {
            val context = request.context(source)
            assertEquals(source, context.sourceRevision)
            assertTrue(request.matches(context))
        }
    }
    @Test fun matchingChecksEveryRequestDimension() {
        val request = request()
        val source = ConflictSourceRevision("adapter", 1)
        val context = request.context(source)
        for (changed in listOf(
            ConflictContext(ConflictDraftRevision("draft", 2), source, time, context.planningZone, context.calendars),
            ConflictContext(context.draftRevision, source, EventTime.Timed(time.start.plusSeconds(1), time.end), context.planningZone, context.calendars),
            ConflictContext(context.draftRevision, source, time, ZoneId.of("Europe/London"), context.calendars),
            ConflictContext(context.draftRevision, source, time, context.planningZone, setOf(identity.copy(owner = "other"))),
            ConflictContext(context.draftRevision, source, time, context.planningZone, context.calendars,
                EventRef(identity.calendarId, 1, time.start, time.end))
        )) assertFalse(request.matches(changed))
    }
    @Test fun invalidDescriptorAndOversizedSelectionFailAtConstruction() {
        for (action in listOf<() -> Unit>(
            { request(draft = ConflictDraftRevision("", 0)) },
            { request((1L..257L).map { identity.copy(calendarId = CalendarId(it)) }.toSet()) },
            { request(setOf(identity, identity.copy(owner = "other"))) },
            { ConflictReadRequest(ConflictDraftRevision("draft", 1), EventTime.Timed(time.start, time.start), ZoneId.of("UTC"), setOf(identity)) }
        )) try { action(); fail("Invalid descriptor accepted") } catch (_: IllegalArgumentException) { }
    }
    @Test fun explicitEmptySelectionStaysEmpty() {
        val request = request(emptySet())
        val context = request.context(ConflictSourceRevision("adapter", 1))
        assertTrue(SchedulingConflicts.assess(context, ConflictSnapshot(context, emptyList(), ConflictCoverage.Complete))
            is ConflictAssessment.NoCalendarsSelected)
    }

    @Test fun scopeIsPartOfRequestAndPureContextCorrelation() {
        for (selection in listOf(setOf(identity), emptySet())) {
            val exact = request(selection)
            val dynamic = ConflictReadRequest(exact.draftRevision, exact.proposedTime, exact.planningZone,
                selection, calendarScope = ConflictCalendarScope.PROVIDER_VISIBLE)
            val source = ConflictSourceRevision("adapter", 1)
            val exactContext = exact.context(source)
            val dynamicContext = dynamic.context(source)
            assertEquals(ConflictCalendarScope.EXACT_IDENTITIES, exactContext.calendarScope)
            assertEquals(ConflictCalendarScope.PROVIDER_VISIBLE, dynamicContext.calendarScope)
            assertTrue(exact.matches(exactContext)); assertTrue(dynamic.matches(dynamicContext))
            assertFalse(exact.matches(dynamicContext)); assertFalse(dynamic.matches(exactContext))
            for ((current, other) in listOf(exactContext to dynamicContext, dynamicContext to exactContext)) {
                val result = SchedulingConflicts.assess(current, ConflictSnapshot(other, emptyList(), ConflictCoverage.Complete))
                assertEquals(ConflictProblem.STALE_CONTEXT, (result as ConflictAssessment.Rejected).problem)
            }
        }
    }
}
