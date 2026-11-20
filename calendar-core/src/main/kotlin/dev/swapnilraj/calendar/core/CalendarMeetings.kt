package dev.swapnilraj.calendar.core

import java.time.ZoneId

enum class MeetingResponse { YES, MAYBE, NO }
enum class MeetingGuestType { UNSPECIFIED, REQUIRED, OPTIONAL }
/** Opaque persisted-source/scope-bound selector. A caller-created value grants no authority. */
data class MeetingGuestRef(val value: String)
data class MeetingGuestInput(val email: String, val type: MeetingGuestType, val name: String? = null)
enum class MeetingGuestAction { CHANGE_TYPE, REMOVE }
data class MeetingGuestRow(val ref: MeetingGuestRef, val attendee: EventAttendee,
    val allowed: Set<MeetingGuestAction> = emptySet())
sealed interface MeetingGuestChange {
    data class Add(val guest: MeetingGuestInput) : MeetingGuestChange
    data class ChangeType(val ref: MeetingGuestRef, val type: MeetingGuestType) : MeetingGuestChange
    data class Remove(val ref: MeetingGuestRef) : MeetingGuestChange
}
/** Advisory UI rights issued only after complete backend feasibility checks; the token remains required. */
data class MeetingScopeAccess(
    val event: SeriesScopeCapabilities = SeriesScopeCapabilities(),
    val respond: AccessDecision = AccessDecision.UNKNOWN,
    val addGuests: AccessDecision = AccessDecision.UNKNOWN,
    val allowedGuestTypes: Set<MeetingGuestType> = emptySet(),
    val guests: List<MeetingGuestRow> = emptyList(),
)
/** Source eligibility only; the backend validates the actual draft/guests before preparation. */
data class MeetingCreation(val calendar: CalendarInfo, val token: EditToken,
    val allowedGuestTypes: Set<MeetingGuestType>, val recurringCreation: AccessDecision = AccessDecision.UNKNOWN)
data class MeetingMasterTemplate(val start: RecurrenceStart, val time: EventTime?, val eventZone: ZoneId?,
    val fields: SeriesMasterFields)
data class EditableMeeting(val details: EventDetails, val token: EditToken,
    val scopes: Map<RecurrenceScope, MeetingScopeAccess>, val master: MeetingMasterTemplate? = null)
sealed interface MeetingReadResult<out T> {
    data class Ready<T>(val value: T) : MeetingReadResult<T>
    data object PermissionRequired : MeetingReadResult<Nothing>
    data class Denied(val reason: MutationDenial) : MeetingReadResult<Nothing>
    data class NeedsReview(val reason: String) : MeetingReadResult<Nothing>
    data object LimitExceeded : MeetingReadResult<Nothing>
    data object Unavailable : MeetingReadResult<Nothing>
}
sealed interface MeetingCommand {
    data class Respond(val scope: RecurrenceScope, val response: MeetingResponse) : MeetingCommand
    data class EditOwned(val scope: RecurrenceScope, val patch: EventPatch,
        val guests: List<MeetingGuestChange> = emptyList(), val expectedMasterStart: RecurrenceStart? = null) : MeetingCommand
    /** Organizer cancellation is distinct from an attendee's NO response. */
    data class CancelOwned(val scope: RecurrenceScope) : MeetingCommand
}
sealed interface MeetingMutationResult {
    data class LocalSaved(val selected: EventRef, val scope: RecurrenceScope) : MeetingMutationResult
    data class LocalCancelled(val selected: EventRef, val scope: RecurrenceScope) : MeetingMutationResult
    data class Common(val outcome: MutationResult) : MeetingMutationResult {
        init { require(outcome !is MutationResult.LocalSaved && outcome !is MutationResult.LocalDeleted) }
    }
    data class NeedsReview(val reason: String) : MeetingMutationResult
    data class Unsupported(val reason: String) : MeetingMutationResult
    data class Invalid(val reason: String) : MeetingMutationResult
}
/** Dedicated feature-tagged tokens/journal kinds in the same private store. No ordinary-writer fallback.
 * THIS acts on the selected occurrence. ALL response touches self rows on master/active exceptions;
 * guest changes require complete unambiguous mapping and preserve unrelated responses/overrides.
 * Unimplemented scopes have no issued rights and refuse before dispatch. Recovery is read-only.
 */
interface CalendarMeetings {
    suspend fun loadForCreate(target: CalendarIdentity): MeetingReadResult<MeetingCreation>
    suspend fun loadForEdit(selected: EventRef): MeetingReadResult<EditableMeeting>
    suspend fun create(operationId: String, token: EditToken, draft: EventDraft,
        guests: List<MeetingGuestInput>): MeetingMutationResult
    suspend fun mutate(operationId: String, selected: EventRef, token: EditToken,
        command: MeetingCommand): MeetingMutationResult
    suspend fun recover(operationId: String): MeetingMutationResult
}
