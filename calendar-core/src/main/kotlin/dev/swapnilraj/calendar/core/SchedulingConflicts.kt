package dev.swapnilraj.calendar.core

import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Collections

/** Correlation only: neither token conveys edit rights or persisted CAS authority. */
data class ConflictDraftRevision(val session: String, val revision: Long)
data class ConflictSourceRevision(val namespace: String, val generation: Long)

/** Dynamic provider visibility requires the adapter to attest the entire fresh visible namespace. */
enum class ConflictCalendarScope { EXACT_IDENTITIES, PROVIDER_VISIBLE }

/** Owns its selection snapshot. There is deliberately no data-class copy that can replace it. */
class ConflictContext(
    val draftRevision: ConflictDraftRevision,
    val sourceRevision: ConflictSourceRevision,
    val proposedTime: EventTime,
    val planningZone: ZoneId,
    calendars: Set<CalendarIdentity>,
    val exclude: EventRef? = null,
    val calendarScope: ConflictCalendarScope = ConflictCalendarScope.EXACT_IDENTITIES,
) {
    private val capturedSelection = boundedCopy(calendars, SchedulingConflicts.MAX_CALENDARS)
    internal val selection = capturedSelection?.takeIf { rows -> rows.all { boundedIdentity(it) } }
    val calendars: Set<CalendarIdentity> = Collections.unmodifiableSet(LinkedHashSet(selection ?: emptyList()))
    internal fun same(other: ConflictContext): Boolean = draftRevision == other.draftRevision &&
        sourceRevision == other.sourceRevision && proposedTime == other.proposedTime &&
        planningZone == other.planningZone && calendars == other.calendars && exclude == other.exclude &&
        calendarScope == other.calendarScope
}

enum class ConflictAvailability { BUSY, TENTATIVE, FREE, UNKNOWN }
enum class ConflictEventStatus { ACTIVE, CANCELLED, UNKNOWN }
enum class ConflictParticipation { PERSONAL, ACCEPTED, TENTATIVE, INVITED, DECLINED, UNKNOWN }

/** No event text or guest list is needed to assess occupancy. */
data class ConflictCandidate(
    val calendar: CalendarIdentity,
    val ref: EventRef,
    val time: EventTime,
    val availability: ConflictAvailability,
    val status: ConflictEventStatus,
    val participation: ConflictParticipation,
)
enum class ConflictCoverageProblem {
    MISSING_CALENDAR, HIDDEN_CALENDAR, SYNC_DISABLED, ACCESS_LIMITED, TRUNCATED, READ_FAILED, UNVERIFIED_COVERAGE,
}
enum class ConflictUnavailableReason { PERMISSION_REQUIRED, INVALIDATED, READ_FAILED, UNSUPPORTED_TIME_RANGE }
sealed interface ConflictCoverage {
    data object Complete : ConflictCoverage
    class Partial(reasons: Set<ConflictCoverageProblem>) : ConflictCoverage {
        internal val captured = boundedCopy(reasons, 16)
        val reasons: Set<ConflictCoverageProblem> = Collections.unmodifiableSet(LinkedHashSet(captured ?: emptyList()))
    }
    data class Unavailable(val reason: ConflictUnavailableReason) : ConflictCoverage
}

/** Complete means an adapter observed the entire selected local window, never cloud free/busy. */
class ConflictSnapshot(val context: ConflictContext, candidates: List<ConflictCandidate>, val coverage: ConflictCoverage) {
    internal val captured = boundedCopy(candidates, SchedulingConflicts.MAX_CANDIDATES)
    val candidates: List<ConflictCandidate> = captured ?: emptyList()
}

enum class ConflictProblem { STALE_CONTEXT, INVALID_CONTEXT, INVALID_CANDIDATE, LIMIT_EXCEEDED }
sealed interface ConflictAssessment {
    data class Rejected(val problem: ConflictProblem) : ConflictAssessment
    data class NoCalendarsSelected(val context: ConflictContext) : ConflictAssessment
    data class Unavailable(val context: ConflictContext, val reason: ConflictUnavailableReason) : ConflictAssessment
    class Assessed internal constructor(
        val context: ConflictContext,
        val coverage: ConflictCoverage,
        conflicts: List<EventRef>,
        possibleConflicts: List<EventRef>,
    ) : ConflictAssessment {
        val conflicts: List<EventRef> = Collections.unmodifiableList(ArrayList(conflicts))
        val possibleConflicts: List<EventRef> = Collections.unmodifiableList(ArrayList(possibleConflicts))
        val canReportNoConflict: Boolean get() = coverage == ConflictCoverage.Complete &&
            conflicts.isEmpty() && possibleConflicts.isEmpty()
    }
}

/** Advisory semantic calculation only. Callers must mask results on permission/source invalidation. */
object SchedulingConflicts {
    const val MAX_CALENDARS = 256
    const val MAX_CANDIDATES = 10_000

    fun assess(current: ConflictContext, snapshot: ConflictSnapshot): ConflictAssessment {
        fun reject(problem: ConflictProblem) = ConflictAssessment.Rejected(problem)
        if (current.selection == null || snapshot.context.selection == null) return reject(ConflictProblem.LIMIT_EXCEEDED)
        if (!validContext(current) || !validContext(snapshot.context)) return reject(ConflictProblem.INVALID_CONTEXT)
        if (!current.same(snapshot.context)) return reject(ConflictProblem.STALE_CONTEXT)
        if (snapshot.captured == null || (snapshot.coverage as? ConflictCoverage.Partial)?.captured == null &&
            snapshot.coverage is ConflictCoverage.Partial) return reject(ConflictProblem.LIMIT_EXCEEDED)
        if (snapshot.coverage is ConflictCoverage.Partial && snapshot.coverage.reasons.isEmpty())
            return reject(ConflictProblem.INVALID_CONTEXT)
        if (current.calendars.isEmpty()) return ConflictAssessment.NoCalendarsSelected(current)
        if (snapshot.coverage is ConflictCoverage.Unavailable)
            return ConflictAssessment.Unavailable(current, snapshot.coverage.reason)
        val proposed = bounds(current.proposedTime, current.planningZone)
            ?: return ConflictAssessment.Unavailable(current, ConflictUnavailableReason.UNSUPPORTED_TIME_RANGE)
        if (proposed.start == proposed.endExclusive)
            return ConflictAssessment.Unavailable(current, ConflictUnavailableReason.UNSUPPORTED_TIME_RANGE)
        val known = ArrayList<EventRef>()
        val possible = ArrayList<EventRef>()
        val seen = HashSet<EventRef>()
        for (candidate in snapshot.candidates) {
            if (!boundedIdentity(candidate.calendar) || candidate.calendar !in current.calendars || candidate.ref.calendarId != candidate.calendar.calendarId ||
                !coherent(candidate) || !seen.add(candidate.ref)) return reject(ConflictProblem.INVALID_CANDIDATE)
            val candidateBounds = bounds(candidate.time, current.planningZone)
                ?: return ConflictAssessment.Unavailable(current, ConflictUnavailableReason.UNSUPPORTED_TIME_RANGE)
            if (candidate.ref == current.exclude) continue
            val overlaps = if (current.proposedTime is EventTime.AllDay && candidate.time is EventTime.AllDay)
                current.proposedTime.dates.intersects(candidate.time.dates)
            else proposed.start < candidateBounds.endExclusive && candidateBounds.start < proposed.endExclusive &&
                candidateBounds.start < candidateBounds.endExclusive
            if (!overlaps) continue
            if (candidate.availability == ConflictAvailability.FREE || candidate.status == ConflictEventStatus.CANCELLED ||
                candidate.participation == ConflictParticipation.DECLINED) continue
            if (candidate.availability == ConflictAvailability.UNKNOWN || candidate.status == ConflictEventStatus.UNKNOWN ||
                candidate.participation == ConflictParticipation.UNKNOWN) possible.add(candidate.ref)
            else known.add(candidate.ref)
        }
        return ConflictAssessment.Assessed(current, snapshot.coverage, known, possible)
    }

    private fun validContext(context: ConflictContext): Boolean = context.draftRevision.session.length in 1..128 &&
        context.draftRevision.session.isNotBlank() && context.draftRevision.revision >= 0 &&
        context.sourceRevision.namespace.length in 1..128 && context.sourceRevision.namespace.isNotBlank() &&
        context.sourceRevision.generation >= 0 &&
        context.calendars.map { it.calendarId }.toSet().size == context.calendars.size &&
        when (val time = context.proposedTime) {
            is EventTime.Timed -> time.start < time.end
            is EventTime.AllDay -> validDates(time.dates)
        }

    private fun validDates(dates: CalendarDateRange) = dates.start.year in 1..9999 && dates.endExclusive.year in 1..9999

    private fun coherent(candidate: ConflictCandidate): Boolean = when (val time = candidate.time) {
        is EventTime.Timed -> time.start == candidate.ref.instanceStart && time.end == candidate.ref.instanceEnd
        is EventTime.AllDay -> validDates(time.dates) &&
            time.dates.start.atStartOfDay(ZoneOffset.UTC).toInstant() == candidate.ref.instanceStart &&
            time.dates.endExclusive.atStartOfDay(ZoneOffset.UTC).toInstant() == candidate.ref.instanceEnd
    }

    private fun bounds(time: EventTime, zone: ZoneId): InstantBounds? = try {
        when (time) {
            is EventTime.Timed -> InstantBounds(time.start, time.end)
            is EventTime.AllDay -> time.dates.instantBounds(zone)
        }
    } catch (_: java.time.DateTimeException) { null }
}

/** Scalar bounds keep hashing/equality work bounded without interpreting identity strings. */
private fun boundedIdentity(identity: CalendarIdentity): Boolean =
    identity.account.name.length <= 4096 && identity.account.type.length <= 4096 &&
        (identity.owner?.length ?: 0) <= 4096 && (identity.providerId?.length ?: 0) <= 4096

/** Count before traversal/allocation; also bound iteration if an unusual collection lies about size. */
private fun <T> boundedCopy(source: Collection<T>, limit: Int): List<T>? {
    if (source.size !in 0..limit) return null
    val copy = ArrayList<T>(source.size)
    val iterator = source.iterator()
    while (iterator.hasNext()) {
        if (copy.size == limit) return null
        copy.add(iterator.next())
    }
    return Collections.unmodifiableList(copy)
}
