package dev.swapnilraj.calendar.ui.events

/** Temporal presentation only. This snapshot carries neither event text nor mutation authority.
 * Hosts supply calendar-selection namespaces, planning zone and permission separately.
 */
data class PhoneEditorConflictSnapshot(
    val binding: EditorConflictBinding?,
    val visibleTime: EditorTemporalInput?,
    val pendingRaw: Boolean,
    val zoneAuthored: Boolean,
    val firstOccurrenceOnly: Boolean,
)
