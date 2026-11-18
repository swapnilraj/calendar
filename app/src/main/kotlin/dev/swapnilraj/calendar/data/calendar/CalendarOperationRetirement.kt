package dev.swapnilraj.calendar.data.calendar

import dev.swapnilraj.calendar.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Local journal fencing only: no resolver, source rereads, permission prompts, or draft deletion. */
class CalendarOperationRetirement(
    private val store: CalendarLocalStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val readBudgets: ProviderReadBudgets = ProviderReadBudgets(),
) : OperationRetirement {
    override suspend fun retire(request: RetirableOperation) = access(request, true)
    override suspend fun inspect(request: RetirableOperation) = access(request, false)

    private suspend fun access(request: RetirableOperation, write: Boolean): OperationRetirementResult =
        withContext(io + readBudgetContext(readBudgets.mutation)) {
            try {
                currentCoroutineContext().ensureActive()
                // Only shipped ordinary encoders can bind a permanent tombstone. The remote
                // draft encoder deliberately does not serialize recurrence; never hash it away.
                when (request) {
                    is RetirableOperation.Create -> if (request.draft.recurrence != null) return@withContext OperationRetirementResult.Unsupported
                    is RetirableOperation.Update -> if (request.ref.recurrence != null) return@withContext OperationRetirementResult.Unsupported
                    is RetirableOperation.Delete -> if (request.ref.recurrence != null) return@withContext OperationRetirementResult.Unsupported
                    else -> return@withContext OperationRetirementResult.Unsupported
                }
                if (request.operationId.isBlank() || request.operationId.length > readBudgets.mutation.maxCellUnits)
                    return@withContext OperationRetirementResult.Unavailable
                val token = when (request) {
                    is RetirableOperation.Create -> null
                    is RetirableOperation.Update -> request.token
                    is RetirableOperation.Delete -> request.token
                    is RetirableOperation.Series -> request.token
                    is RetirableOperation.Meeting -> request.token
                    is RetirableOperation.MeetingCreate -> request.token
                }
                if (token != null && token.value.length > readBudgets.mutation.maxCellUnits)
                    return@withContext OperationRetirementResult.Unavailable
                val fingerprint = when (request) {
                    is RetirableOperation.Create -> CalendarWriter.createFingerprint(request.draft, readBudgets.maxCanonicalBytes)
                    is RetirableOperation.Update -> CalendarWriter.updateFingerprint(request.ref, request.token, request.patch, readBudgets.maxCanonicalBytes)
                    is RetirableOperation.Delete -> CalendarWriter.deleteFingerprint(request.ref, request.token)
                    else -> null // This publication packet contains no series or meeting backend.
                } ?: return@withContext OperationRetirementResult.Unsupported
                val result = store.retirement(request.operationId, fingerprint.first, fingerprint.second, write)
                currentCoroutineContext().ensureActive()
                result
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { OperationRetirementResult.Unavailable }
        }
}

/** Only a small header is read on the normal path. Encode exact typed input only for a tombstone. */
internal fun CalendarLocalStore.abandoned(id: String, fingerprint: () -> Pair<String, String>): MutationResult? {
    val header = operationHeader(id) ?: return null
    if (header.phase != "ABANDONED") return null
    val expected = fingerprint()
    return if (header.kind == expected.first && header.signature == expected.second) MutationResult.Retired(id)
        else MutationResult.Conflict(null)
}
