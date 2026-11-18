package dev.swapnilraj.calendar.ui.grid

import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.CalendarPreferences
import dev.swapnilraj.calendar.ui.editorSeed
import dev.swapnilraj.calendar.ui.verifiedCreationCalendar
import dev.swapnilraj.calendar.ui.eventCreationDraft
import dev.swapnilraj.calendar.ui.events.EditorDraftLaunch
import dev.swapnilraj.calendar.ui.events.EventEditorController
import dev.swapnilraj.calendar.ui.events.prepareEditorFieldsDraft
import dev.swapnilraj.calendar.ui.events.DraftPreparationResult
import dev.swapnilraj.calendar.ui.settings.CalendarSettingsOptions
import kotlinx.coroutines.CancellationException
import java.time.*
import java.util.UUID

/** Observed UI context only; never a provider token or namespace claim inferred from an ID. */
internal data class GridEditorContext(
    val owners: List<Any?>,
    val request: CalendarRequest,
    val snapshot: CalendarSnapshot?,
    val calendars: List<CalendarInfo>?,
    val preferences: CalendarPreferences,
    val view: String,
    val date: LocalDate,
    val blocked: Boolean,
) {
    fun occurrence(ref: EventRef): EventOccurrence? = snapshot?.takeIf { it.request == request }?.occurrences?.singleOrNull { it.ref == ref }
    fun calendar(ref: EventRef): CalendarInfo? {
        val observed = snapshot?.calendars?.singleOrNull { it.id == ref.calendarId } ?: return null
        return calendars?.singleOrNull { it.identity == observed.identity && it.account.name.isNotBlank() && it.account.type.isNotBlank() }
    }
    fun visibleGridDate(date: LocalDate): Boolean = date in request.range &&
        !(view == "WEEK" && !preferences.options.showWeekends && date.dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))
    val available: Boolean get() = !blocked && view in setOf("DAY", "THREE_DAY", "WEEK") && snapshot?.request == request && calendars != null
}
internal sealed interface GridEditorIntent {
    data class Create(val proposal: TimedGridCreateProposal) : GridEditorIntent
    data class Edit(val proposal: TimedGridEditProposal) : GridEditorIntent
}
internal sealed interface GridEditorPreparation {
    data class Ready(val launch: EditorDraftLaunch) : GridEditorPreparation
    data class Review(val intent: GridEditorIntent, val preview: GridPreviewResult.NeedsReview) : GridEditorPreparation
    data object Stale : GridEditorPreparation
    data object Unavailable : GridEditorPreparation
}

/** Validate column proof and segment membership; core still owns the resulting time plan. */
internal fun validGridPointerPath(context: GridEditorContext, proposal: TimedGridEditProposal): Boolean {
    val path = proposal.pointerPath ?: return false
    fun point(target: GridTarget) = context.visibleGridDate(target.date) && target.zone == context.request.displayZone &&
        target.minute.isFinite() && target.minute in 0.0..1440.0 && target.offsetChoice == GridOffsetChoice.Unspecified && target.boundary == null
    return point(path.origin) && point(path.hover) &&
        DayGeometry(path.origin.date, context.request.displayZone).segments(proposal.source.ref).any { it.key == proposal.selectedSegment }
}

/** Rechecks the current snapshot AND the repository-issued source after suspension. */
internal fun gridSourceMatches(context: GridEditorContext, ref: EventRef, source: EditableEvent, requireKnownZone: Boolean = true): Boolean {
    val occurrence = context.occurrence(ref) ?: return false
    val calendar = context.calendar(ref) ?: return false
    return context.available && ref.recurrence == null && occurrence.time is EventTime.Timed &&
        calendar.capabilities.modifyEvents == AccessDecision.ALLOWED &&
        source.details.occurrence.ref == ref && source.details.occurrence.time == occurrence.time &&
        source.details.calendar?.identity == calendar.identity && (!requireKnownZone || source.details.eventZone != null) && EditableField.TIME in source.allowedFields
}
internal suspend fun gridEditPreflight(context: GridEditorContext, ref: EventRef, mutations: CalendarMutations,
    current: () -> GridEditorContext): Boolean {
    if (current() != context || !context.available || context.occurrence(ref) == null || ref.recurrence != null) return false
    val read = mutations.loadForEdit(ref)
    return current() == context && read is EditReadResult.Ready && gridSourceMatches(current(), ref, read.event)
}

/** No writes or permission prompts; the target is recomputed from the core's snapped wall position. */
internal suspend fun prepareGridEditor(
    context: GridEditorContext, intent: GridEditorIntent, mutations: CalendarMutations, store: DraftStore,
    now: ZonedDateTime, current: () -> GridEditorContext, clock: Clock,
    offset: ZoneOffset? = null, editorAlternative: Boolean = false,
): GridEditorPreparation {
    fun valid() = current() == context && context.available
    if (!valid()) return GridEditorPreparation.Stale
    val zone = context.request.displayZone
    val source: EditableEvent?
    val target: GridTarget
    val plan: GridPreviewResult
    val options: CalendarSettingsOptions = context.preferences.options
    when (intent) {
        is GridEditorIntent.Create -> {
            if (intent.proposal.contextKey != context) return GridEditorPreparation.Stale
            source = null
            target = intent.proposal.target.copy(offsetChoice = offset?.let(GridOffsetChoice::Explicit) ?: GridOffsetChoice.Unspecified, boundary = null)
            plan = TimedGridPlanning.create(target, Duration.ofMinutes(options.defaultDurationMinutes.toLong()))
        }
        is GridEditorIntent.Edit -> {
            val proposal = intent.proposal
            if (proposal.contextKey != context || proposal.source.ref.recurrence != null || context.occurrence(proposal.source.ref)?.time != proposal.source.time)
                return GridEditorPreparation.Stale
            val path = proposal.pointerPath
            val logical = if (context.view != "DAY" && proposal.kind == TimedGridEditKind.MOVE && !editorAlternative) {
                if (path == null || !validGridPointerPath(context, proposal)) return GridEditorPreparation.Unavailable
                translatedGridTarget(proposal.source, proposal.selectedSegment, proposal.kind,
                    (path.hover.minute - path.origin.minute) + java.time.temporal.ChronoUnit.DAYS.between(path.origin.date, path.hover.date) * 1440.0, zone)
            } else proposal.target
            val read = mutations.loadForEdit(proposal.source.ref)
            if (!valid()) return GridEditorPreparation.Stale
            if (read !is EditReadResult.Ready || !gridSourceMatches(current(), proposal.source.ref, read.event, requireKnownZone = !editorAlternative)) return GridEditorPreparation.Unavailable
            source = read.event
            target = logical.copy(offsetChoice = offset?.let(GridOffsetChoice::Explicit) ?: GridOffsetChoice.Unspecified)
            plan = planGridEdit(proposal.source, proposal.selectedSegment, proposal.kind, target)
        }
    }
    if (target.zone != zone || target.date.year !in 1..9999) return GridEditorPreparation.Unavailable
    val plannedTarget = snappedGridTarget(plan, target)
    val resize = intent is GridEditorIntent.Edit && intent.proposal.kind == TimedGridEditKind.RESIZE_END
    val move = intent is GridEditorIntent.Edit && intent.proposal.kind == TimedGridEditKind.MOVE
    if (!editorAlternative && context.view != "DAY" && !move &&
        !(context.visibleGridDate(plannedTarget.date) || resize && plannedTarget.date == context.request.range.endExclusive && plannedTarget.minute == 0.0))
        return GridEditorPreparation.Unavailable
    if (!editorAlternative && plan is GridPreviewResult.NeedsReview) return GridEditorPreparation.Review(intent, plan)
    if (!editorAlternative && plan !is GridPreviewResult.Ready) return GridEditorPreparation.Unavailable
    val calendar = verifiedCreationCalendar(context.preferences, context.calendars.orEmpty())
    if (source == null && context.calendars.orEmpty().none { it.capabilities.createEvents == AccessDecision.ALLOWED }) return GridEditorPreparation.Unavailable
    val original = source?.details?.editorSeed() ?: eventCreationDraft(target.date, false, now.withZoneSameInstant(zone), options, calendar?.id, calendar?.identity)
        ?: return GridEditorPreparation.Unavailable
    val proposedTime = if (editorAlternative) original.time else (plan as GridPreviewResult.Ready).time
    if (proposedTime !is EventTime.Timed || proposedTime.start.atZone(zone).year !in 1..9999 || proposedTime.end.atZone(zone).year !in 1..9999)
        return GridEditorPreparation.Unavailable
    // For edits the source baseline must describe the ORIGINAL canonical event. Only raw form fields
    // carry the proposed time; restoration then compares fresh source values with the real baseline.
    val initial = if (source == null) original.copy(time = proposedTime) else original
    val id = "grid:${UUID.randomUUID()}"
    val selected = source != null || calendar != null
    var fields = EventEditorController.fieldsFor(initial).let {
        if (!selected) it.copy(calendarId = "", targetIdentity = null) else it
    }
    if (source != null && !editorAlternative) {
        val proposed = EventEditorController.fieldsFor(original.copy(time = proposedTime))
        fields = fields.copy(startDate = proposed.startDate, endDate = proposed.endDate,
            startTime = proposed.startTime, endTime = proposed.endTime,
            startOffset = proposed.startOffset, endOffset = proposed.endOffset)
    }
    val atomic = store as? AtomicDraftStore ?: return GridEditorPreparation.Unavailable
    return try {
        if (!valid()) return GridEditorPreparation.Stale
        val prepared = prepareEditorFieldsDraft(initial, source, id, selected, fields, atomic, clock)
        if (!valid()) return GridEditorPreparation.Stale
        val launch = (prepared as? DraftPreparationResult.Ready)?.launch
        if (launch != null && launch.generation != null && launch.ref == source?.details?.occurrence?.ref)
            GridEditorPreparation.Ready(launch) else GridEditorPreparation.Unavailable
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { GridEditorPreparation.Unavailable }

}

/** Subminute durations remain positive even at nanosecond precision; no zero-minute claim. */
internal data class GridElapsedAmount(val value: String, val seconds: Boolean)
internal fun gridElapsedAmount(duration: Duration, locale: java.util.Locale): GridElapsedAmount {
    val seconds = duration < Duration.ofMinutes(1)
    val format = java.text.NumberFormat.getNumberInstance(locale).apply { if (seconds) maximumFractionDigits = 9 }
    val value = duration.seconds.toDouble() + duration.nano / 1_000_000_000.0
    return GridElapsedAmount(format.format(if (seconds) value else value / 60), seconds)
}
