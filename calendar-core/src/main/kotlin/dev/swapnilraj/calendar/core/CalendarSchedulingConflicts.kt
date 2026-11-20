package dev.swapnilraj.calendar.core

import java.time.ZoneId
import kotlinx.coroutines.flow.Flow

/** Immutable request dimensions. A source revision is issued by the observing adapter, never supplied here. */
class ConflictReadRequest(
    draftRevision: ConflictDraftRevision,
    proposedTime: EventTime,
    planningZone: ZoneId,
    calendars: Set<CalendarIdentity>,
    exclude: EventRef? = null,
    calendarScope: ConflictCalendarScope = ConflictCalendarScope.EXACT_IDENTITIES,
) {
    private val template = ConflictContext(draftRevision, ConflictSourceRevision("request-validation", 0),
        proposedTime, planningZone, calendars, exclude, calendarScope)
    init {
        require(SchedulingConflicts.assess(template, ConflictSnapshot(template, emptyList(), ConflictCoverage.Complete))
            !is ConflictAssessment.Rejected) { "Invalid conflict request" }
    }
    val draftRevision get() = template.draftRevision
    val proposedTime get() = template.proposedTime
    val planningZone get() = template.planningZone
    val calendars get() = template.calendars
    val exclude get() = template.exclude
    val calendarScope get() = template.calendarScope

    /** Correlation only. Calling this does not issue provider freshness or mutation authority. */
    fun context(source: ConflictSourceRevision): ConflictContext = ConflictContext(draftRevision, source,
        proposedTime, planningZone, calendars, exclude, calendarScope)
    fun matches(context: ConflictContext): Boolean = context.draftRevision == draftRevision && context.proposedTime == proposedTime &&
        context.planningZone == planningZone && context.calendars == calendars && context.exclude == exclude &&
        context.calendarScope == calendarScope
}

/** Selected-calendar, device-local advisory occupancy. Loading masks any prior observation. */
interface CalendarSchedulingConflicts {
    fun observe(request: ConflictReadRequest): Flow<CalendarReadState<ConflictSnapshot>>
}
