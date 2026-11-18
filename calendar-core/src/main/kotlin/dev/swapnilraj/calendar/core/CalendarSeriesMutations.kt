package dev.swapnilraj.calendar.core

/** Master fields are a template, not a fabricated provider-expanded first occurrence. */
data class SeriesMasterFields(
    val title: String,
    val location: String = "",
    val description: String = "",
    val reminders: List<EventReminder> = emptyList(),
    val availability: Int? = null,
    val privacy: Int? = null,
)

enum class SeriesActionLimitation { UNKNOWN, UNSUPPORTED, READ_ONLY, REVIEW_REQUIRED }

/** Issued by the mutation adapter, not inferred from a readable graph or guest-policy flags. */
data class SeriesScopeCapabilities(
    val edit: AccessDecision = AccessDecision.UNKNOWN,
    val fields: Set<EditableField> = emptySet(),
    val delete: AccessDecision = AccessDecision.UNKNOWN,
    val limitation: SeriesActionLimitation = SeriesActionLimitation.UNKNOWN,
    /** Explicit rule-edit right; readable recurrence text is not authoring authority. */
    val recurrence: AccessDecision = AccessDecision.UNKNOWN,
)

/** The opaque token binds selected identity, the complete master/exception graph and child sets. */
data class EditableSeries(
    val selectedDetails: EventDetails,
    val masterFields: SeriesMasterFields,
    val snapshot: RecurrenceSeriesSnapshot,
    val token: EditToken,
    val allowedFields: Set<EditableField>,
    val scopes: Map<RecurrenceScope, SeriesScopeCapabilities> = emptyMap(),
)

sealed interface SeriesReadResult {
    data class Ready(val series: EditableSeries) : SeriesReadResult
    data object PermissionRequired : SeriesReadResult
    data class Denied(val reason: MutationDenial) : SeriesReadResult
    data class NeedsReview(val reason: String) : SeriesReadResult
    data object Unavailable : SeriesReadResult
}

/** Intent only. The backend recomputes a plan from freshly validated token-bound source data. */
sealed interface SeriesCommand {
    data class OccurrenceEdit(val patch: EventPatch) : SeriesCommand
    data class WholeEdit(val intent: RecurrenceEditIntent.WholeSeriesEdit) : SeriesCommand
    data class FollowingEdit(val intent: RecurrenceEditIntent.FollowingSeriesEdit) : SeriesCommand
    data class Delete(val scope: RecurrenceScope) : SeriesCommand
}

sealed interface SeriesMutationResult {
    /** [identity] is the resulting master/tail; the selected ordinal may no longer exist. */
    data class LocalSaved(val identity: SeriesIdentity, val selected: EventRef?) : SeriesMutationResult
    data class LocalDeleted(val identity: SeriesIdentity, val scope: RecurrenceScope, val selected: EventRef) : SeriesMutationResult
    /** Permission/conflict/uncertain/retry outcomes retain their ordinary local-only semantics. */
    data class Common(val outcome: MutationResult) : SeriesMutationResult {
        init { require(outcome !is MutationResult.LocalSaved && outcome !is MutationResult.LocalDeleted) }
    }
    data class PlanningReview(val reason: String) : SeriesMutationResult
    data class Unsupported(val reason: String) : SeriesMutationResult
    data class Invalid(val reason: String) : SeriesMutationResult
}

interface CalendarSeriesReadPort {
    suspend fun loadForEdit(selected: EventRef): SeriesReadResult
}

interface CalendarSeriesMutations : CalendarSeriesReadPort {
    suspend fun mutate(operationId: String, selected: EventRef, token: EditToken, command: SeriesCommand): SeriesMutationResult
    /** Read-only journal/read-back recovery. Unknown tail inserts must never be blindly retried. */
    suspend fun recover(operationId: String): SeriesMutationResult
}
