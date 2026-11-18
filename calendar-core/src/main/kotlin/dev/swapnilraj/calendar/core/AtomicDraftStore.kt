package dev.swapnilraj.calendar.core

sealed interface DraftChangeResult {
    /** The complete conditional change committed durably. */
    data object Applied : DraftChangeResult
    /** Proven mismatch; no row changed. Preserve pending input for review. */
    data object Changed : DraftChangeResult
    /** Storage/acknowledgement is unavailable. Read back before assuming whether a change committed. */
    data object Unavailable : DraftChangeResult
}

/** An atomic storage capability, never implemented by composing load/upsert/delete calls.
 * Compare all fields of the expected record within the same transaction as the change.
 * A null expected record creates only if absent; null replacement deletes only an exact match.
 * At least one record must be present and every present record must have [id].
 */
interface AtomicDraftStore : DraftStore {
    suspend fun compareAndSet(id: String, expected: DraftRecord?, replacement: DraftRecord?): DraftChangeResult
}
