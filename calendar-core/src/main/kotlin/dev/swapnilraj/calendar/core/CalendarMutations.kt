package dev.swapnilraj.calendar.core

import java.time.Instant
import java.time.ZoneId

/** Complete, normalized input; incomplete editor fields belong in [DraftRecord] instead. */
data class EventDraft(
    val calendarId: CalendarId,
    val time: EventTime,
    val eventZone: ZoneId?,
    val title: String,
    val location: String = "",
    val description: String = "",
    val alertMinutes: List<Int> = emptyList(),
    /** Optional provider metadata. Null omits a new-event override; unknown existing values survive Keep. */
    val availability: Int? = null,
    val privacy: Int? = null,
    /** Null is an unproven legacy/newly unselected destination, not permission to adopt its current ID. */
    val targetIdentity: CalendarIdentity? = null,
)

sealed interface FieldChange<out T> {
    data object Keep : FieldChange<Nothing>
    data class Set<T>(val value: T) : FieldChange<T>
}

/** Keep is different from deliberately clearing a field. Unrepresented provider fields survive. */
data class EventPatch(
    val title: FieldChange<String> = FieldChange.Keep,
    val time: FieldChange<EventTime> = FieldChange.Keep,
    val eventZone: FieldChange<ZoneId?> = FieldChange.Keep,
    val location: FieldChange<String> = FieldChange.Keep,
    val description: FieldChange<String> = FieldChange.Keep,
    val alertMinutes: FieldChange<List<Int>> = FieldChange.Keep,
    val availability: FieldChange<Int?> = FieldChange.Keep,
    val privacy: FieldChange<Int?> = FieldChange.Keep,
)

enum class EditableField { TITLE, TIME, ZONE, LOCATION, DESCRIPTION, ALERTS, AVAILABILITY, PRIVACY }

/** Opaque repository-issued token bound to a private persisted source snapshot, not a provider revision. */
data class EditToken(val value: String)
data class EditableEvent(val details: EventDetails, val token: EditToken, val allowedFields: Set<EditableField>)
enum class MutationDenial { CALENDAR_READ_ONLY, OWNERSHIP_UNKNOWN, INVITATION, RECURRENCE, UNSUPPORTED_EVENT }
enum class InvalidEventField { CALENDAR, TIME, ZONE, ALERTS, AVAILABILITY, PRIVACY }

sealed interface EditReadResult {
    data class Ready(val event: EditableEvent) : EditReadResult
    data object PermissionRequired : EditReadResult
    data class Denied(val reason: MutationDenial) : EditReadResult
    data object Unavailable : EditReadResult
}

sealed interface MutationResult {
    data class LocalSaved(val ref: EventRef) : MutationResult
    data class LocalDeleted(val ref: EventRef) : MutationResult
    data object PermissionRequired : MutationResult
    data class Denied(val reason: MutationDenial) : MutationResult
    data class ValidationFailed(val fields: Set<InvalidEventField>) : MutationResult
    data class Conflict(val latest: EventDetails?) : MutationResult
    data object Unavailable : MutationResult
    /** Recovery-only proof that this journaled command was never dispatched or atomically rolled back.
     * Retry requires an explicit user action with the same operation ID and exact original command.
     * Missing journals, unknown outcomes and unreadable records never establish this proof.
     */
    data class SafeToRetry(val operationId: String) : MutationResult
    /** A possibly committed operation must be reconciled, never blindly re-submitted. */
    data class Uncertain(val operationId: String, val knownEventId: Long?) : MutationResult
}

/** Local provider outcomes only. Guest/series operations require separate verified implementations. */
interface CalendarMutations {
    suspend fun loadForEdit(ref: EventRef): EditReadResult
    suspend fun create(operationId: String, draft: EventDraft): MutationResult
    suspend fun update(operationId: String, ref: EventRef, token: EditToken, patch: EventPatch): MutationResult
    suspend fun delete(operationId: String, ref: EventRef, token: EditToken): MutationResult
    /** Inspect the durable journal/read back a known commit; never submits a new provider write. */
    suspend fun recover(operationId: String): MutationResult
}

/** Versioned UI payload retains invalid/partial input without accidentally saving an event. */
data class DraftRecord(val id: String, val version: Int, val payload: String, val updatedAt: Instant)
interface DraftStore {
    suspend fun load(id: String): DraftRecord?
    suspend fun list(): List<DraftRecord>
    suspend fun upsert(record: DraftRecord)
    suspend fun delete(id: String)
}
