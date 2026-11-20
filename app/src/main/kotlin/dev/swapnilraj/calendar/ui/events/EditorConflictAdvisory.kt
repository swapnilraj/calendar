package dev.swapnilraj.calendar.ui.events

import dev.swapnilraj.calendar.core.*
import java.time.ZoneId
import java.util.Collections

/** Captures only temporal/correlation/namespace data, never editor text or guest payloads.
 * Null selection means unresolved namespaces; an explicitly empty selection is a valid choice.
 */
class EditorConflictAdvisoryInput(
    val binding: EditorConflictBinding?,
    val visibleTime: EditorTemporalInput?,
    selectedCalendars: Set<CalendarIdentity>?,
    val planningZone: ZoneId,
    val presentationVisible: Boolean,
    val readPermission: Boolean,
    val pendingRaw: Boolean = false,
    val sourceReady: Boolean = true,
    val zoneAuthored: Boolean = false,
    val firstOccurrenceOnly: Boolean = false,
    val hostRevision: Long = 0,
    val calendarScope: ConflictCalendarScope = ConflictCalendarScope.EXACT_IDENTITIES,
) {
    val selectedCalendars: Set<CalendarIdentity>? = capture(selectedCalendars)

    internal fun same(other: EditorConflictAdvisoryInput): Boolean = binding == other.binding &&
        visibleTime == other.visibleTime && selectedCalendars == other.selectedCalendars && planningZone == other.planningZone &&
        presentationVisible == other.presentationVisible && readPermission == other.readPermission && pendingRaw == other.pendingRaw &&
        sourceReady == other.sourceReady && zoneAuthored == other.zoneAuthored && firstOccurrenceOnly == other.firstOccurrenceOnly &&
        hostRevision == other.hostRevision && calendarScope == other.calendarScope

    private fun capture(source: Set<CalendarIdentity>?): Set<CalendarIdentity>? {
        if (source == null || source.size !in 0..SchedulingConflicts.MAX_CALENDARS) return null
        val copy = LinkedHashSet<CalendarIdentity>()
        var count = 0
        for (identity in source) {
            if (count++ == SchedulingConflicts.MAX_CALENDARS) return null
            copy += identity
        }
        return Collections.unmodifiableSet(copy)
    }
}

enum class EditorConflictAdvisoryStatus {
    HIDDEN, INACTIVE_DRAFT, PENDING_INPUT, UNFINISHED_TIME, NO_CALENDARS_SELECTED, SELECTION_UNAVAILABLE,
    LOADING, PERMISSION_REQUIRED, UNAVAILABLE, LIMIT_EXCEEDED, UNSUPPORTED_TIME, ASSESSED,
}

/** Presentation retains counts only; refs remain inside the injected assessment boundary. */
data class EditorConflictAdvisoryState(
    val status: EditorConflictAdvisoryStatus,
    val knownConflicts: Int = 0,
    val possibleConflicts: Int = 0,
    val complete: Boolean = false,
    val firstOccurrenceOnly: Boolean = false,
) {
    val canReportNoConflict: Boolean get() = status == EditorConflictAdvisoryStatus.ASSESSED && complete &&
        knownConflicts == 0 && possibleConflicts == 0
}

/** Correlation is private to this controller instance; callers cannot mint a current observation. */
class EditorConflictAdvisoryObservation internal constructor(
    internal val owner: Any, internal val input: EditorConflictAdvisoryInput?, internal val epoch: Long,
    val presentation: EditorConflictAdvisoryState,
)
