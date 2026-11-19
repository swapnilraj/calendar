package dev.swapnilraj.calendar.events

import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.*
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class EditorConflictAdvisoryInputTest {
    @Test fun selectionSnapshotCannotFollowCallerMutationOrGetterCast() {
        val rows=mutableSetOf(ConflictAdvisoryFixture.identity)
        val input=ConflictAdvisoryFixture.input(calendars=rows);rows.clear()
        assertEquals(setOf(ConflictAdvisoryFixture.identity),input.selectedCalendars)
        try {(input.selectedCalendars as MutableSet<CalendarIdentity>).clear();fail("Mutable input selection")}
        catch(_:UnsupportedOperationException){}
    }
    @Test fun missingNamespacesAreDistinctFromExplicitEmptyAndOversizedSelectionRefuses() {
        assertNull(ConflictAdvisoryFixture.input(calendars=null).selectedCalendars)
        assertTrue(ConflictAdvisoryFixture.input(calendars=emptySet()).selectedCalendars!!.isEmpty())
        val tooMany=(1L..257L).map {ConflictAdvisoryFixture.identity.copy(calendarId=CalendarId(it))}.toSet()
        assertNull(ConflictAdvisoryFixture.input(calendars=tooMany).selectedCalendars)
    }
    @Test fun lyingCollectionCannotCauseUnboundedSnapshotTraversal() {
        var reads=0
        val rows=object:AbstractSet<CalendarIdentity>() {
            override val size=0
            override fun iterator()=object:Iterator<CalendarIdentity> {
                override fun hasNext()=true
                override fun next():CalendarIdentity {reads++;return ConflictAdvisoryFixture.identity}
            }
        }
        assertNull(ConflictAdvisoryFixture.input(calendars=rows).selectedCalendars)
        assertEquals(SchedulingConflicts.MAX_CALENDARS+1,reads)
    }
    @Test fun nullableBaselineUsesVisibleTimeAndPositiveImportedMillisRemainExact() {
        val raw=ConflictAdvisoryFixture.raw
        val create=ConflictAdvisoryFixture.binding.copy(baselineInput=null,preservedTime=null)
        assertEquals(raw.normalizedTime(),create.normalizedTime())
        val begin=Instant.parse("2026-06-01T10:00:00.123Z");val exact=EventTime.Timed(begin,begin.plusMillis(789))
        val imported=create.copy(baselineInput=raw,preservedTime=exact)
        assertEquals(exact,imported.normalizedTime())
        val changed=raw.copy(startTime="09:00")
        assertEquals(changed.normalizedTime(),imported.normalizedTime(changed))
        assertNull(create.editedRef)
    }
}
