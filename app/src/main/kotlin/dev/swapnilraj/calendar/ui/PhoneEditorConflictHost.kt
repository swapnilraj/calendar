package dev.swapnilraj.calendar.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.*
import java.time.ZoneId

/** Resolve every chosen namespace. A removed calendar must not become an apparently clear subset. */
internal fun conflictCalendarIdentities(
    metadata: CalendarReadState<List<CalendarInfo>>, selection: CalendarSelection,
): Set<CalendarIdentity>? {
    if (selection is CalendarSelection.Explicit && selection.ids.isEmpty()) return emptySet()
    val rows = (metadata as? CalendarReadState.Ready)?.value ?: return null
    if (rows.map { it.id }.distinct().size != rows.size) return null
    val selected = when (selection) {
        CalendarSelection.ProviderVisible -> rows.filter { it.providerVisible }
        is CalendarSelection.Explicit -> {
            if (selection.ids.size > SchedulingConflicts.MAX_CALENDARS) return null
            val byId = rows.associateBy { it.id }
            if (!byId.keys.containsAll(selection.ids)) return null
            selection.ids.map { byId.getValue(it) }
        }
    }
    if (selected.size > SchedulingConflicts.MAX_CALENDARS) return null
    // Visibility and sync flags remain observations in the reader's coverage assessment.
    return selected.map { it.identity }.toSet()
}

/** Mounted inside each editor slot, so leaving a picker or permission mask creates a fresh owner. */
@Composable
internal fun PhoneEditorConflictHost(
    source: CalendarSchedulingConflicts,
    snapshot: PhoneEditorConflictSnapshot,
    selectedCalendars: Set<CalendarIdentity>?,
    planningZone: ZoneId,
    presentationVisible: Boolean = true,
    readPermission: Boolean = true,
    sourceReady: Boolean = true,
    hostRevision: Long = 0,
    calendarScope: ConflictCalendarScope = ConflictCalendarScope.EXACT_IDENTITIES,
) {
    val scope = rememberCoroutineScope()
    val controller = remember(source, scope) { EditorConflictAdvisoryController(source, scope) }
    val input = EditorConflictAdvisoryInput(snapshot.binding, snapshot.visibleTime, selectedCalendars,
        planningZone, presentationVisible, readPermission, snapshot.pendingRaw, sourceReady,
        snapshot.zoneAuthored, snapshot.firstOccurrenceOnly, hostRevision, calendarScope)
    EditorConflictAdvisory(controller, input)
}
