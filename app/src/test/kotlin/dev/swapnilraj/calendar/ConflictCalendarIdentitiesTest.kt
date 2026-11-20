package dev.swapnilraj.calendar

import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.conflictCalendarIdentities
import org.junit.Assert.*
import org.junit.Test

class ConflictCalendarIdentitiesTest {
    private fun row(id: Long, visible: Boolean = true, sync: Boolean = true, provider: String = "provider-$id") =
        CalendarInfo(CalendarId(id), "Synthetic calendar", 0, CalendarAccount("fixture@example.invalid", "fixture"),
            "fixture@example.invalid", null, null, visible, sync, providerId = provider)

    @Test fun missingSelectedIdNeverDropsToAnApparentlyCompleteSubset() {
        val rows = CalendarReadState.Ready(listOf(row(1)))
        assertNull(conflictCalendarIdentities(rows, CalendarSelection.Explicit(setOf(CalendarId(1), CalendarId(2)))))
    }

    @Test fun chosenEmptySetStaysEmptyEvenWhenMetadataIsUnavailable() {
        for (state in listOf(CalendarReadState.Loading, CalendarReadState.PermissionRequired, CalendarReadState.Unavailable))
            assertEquals(emptySet<CalendarIdentity>(), conflictCalendarIdentities(state, CalendarSelection.Explicit(emptySet())))
    }

    @Test fun unresolvedNonemptyOrProviderChoiceCannotBecomeEmpty() {
        assertNull(conflictCalendarIdentities(CalendarReadState.Loading, CalendarSelection.Explicit(setOf(CalendarId(1)))))
        assertNull(conflictCalendarIdentities(CalendarReadState.Unavailable, CalendarSelection.ProviderVisible))
    }

    @Test fun explicitHiddenOrUnsyncedRowsRetainFullNamespacesForCoverageAssessment() {
        val hidden = row(1, visible = false)
        val offline = row(2, sync = false)
        assertEquals(setOf(hidden.identity, offline.identity), conflictCalendarIdentities(
            CalendarReadState.Ready(listOf(hidden, offline)), CalendarSelection.Explicit(setOf(hidden.id, offline.id))))
    }

    @Test fun providerVisibleDefaultFollowsVisibilityWithoutDroppingSyncDisabledRows() {
        val selected = row(1, sync = false)
        assertEquals(setOf(selected.identity), conflictCalendarIdentities(CalendarReadState.Ready(listOf(selected,
            row(2, visible = false))), CalendarSelection.ProviderVisible))
    }

    @Test fun duplicateNumericIdsWithDifferentNamespacesCannotBeResolved() {
        assertNull(conflictCalendarIdentities(CalendarReadState.Ready(listOf(row(1), row(1, provider = "replacement"))),
            CalendarSelection.Explicit(setOf(CalendarId(1)))))
    }
}
