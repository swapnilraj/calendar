package dev.swapnilraj.calendar.core

/** Exact backend inputs only. UI payloads and caller-computed fingerprints are not authority. */
sealed interface RetirableOperation {
    val operationId: String
    data class Create(override val operationId: String, val draft: EventDraft) : RetirableOperation
    data class Update(override val operationId: String, val ref: EventRef, val token: EditToken,
        val patch: EventPatch) : RetirableOperation
    data class Delete(override val operationId: String, val ref: EventRef, val token: EditToken) : RetirableOperation
    data class Series(override val operationId: String, val selected: EventRef, val token: EditToken,
        val command: SeriesCommand) : RetirableOperation
    data class MeetingCreate(override val operationId: String, val token: EditToken, val draft: EventDraft,
        val guests: List<MeetingGuestInput>) : RetirableOperation
    data class Meeting(override val operationId: String, val selected: EventRef, val token: EditToken,
        val command: MeetingCommand) : RetirableOperation
}

enum class RetirementBlockReason { MAY_HAVE_DISPATCHED, RECORDED_COMMIT }

sealed interface OperationRetirementResult {
    data class Retired(val operationId: String) : OperationRetirementResult
    /** Inspection only: absence of a tombstone does not prove that a command never dispatched. */
    data class NotRetired(val operationId: String) : OperationRetirementResult
    data class Blocked(val operationId: String, val reason: RetirementBlockReason) : OperationRetirementResult
    data class Conflict(val operationId: String) : OperationRetirementResult
    data object Unsupported : OperationRetirementResult
    data object Unavailable : OperationRetirementResult
}

/** App-private durable fencing, with no provider reads/writes or permission prompts.
 * A matching permanent retirement must precede deleting/replacing a frozen draft. An unavailable
 * acknowledgement requires inspection of the same typed request, never inferred success.
 */
interface OperationRetirement {
    suspend fun retire(request: RetirableOperation): OperationRetirementResult
    suspend fun inspect(request: RetirableOperation): OperationRetirementResult
}
