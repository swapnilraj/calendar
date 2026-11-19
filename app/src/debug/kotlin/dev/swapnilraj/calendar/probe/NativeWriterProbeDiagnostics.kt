package dev.swapnilraj.calendar.probe

import dev.swapnilraj.calendar.core.InvalidEventField
import dev.swapnilraj.calendar.core.MutationDenial
import dev.swapnilraj.calendar.core.MutationResult
import java.io.IOException
import java.util.concurrent.CancellationException

/** Debug/test-only policy shared by the opt-in instrumentation and host JVM regression. */
internal enum class NativeProbeQueryScope {
    OWNED_CALENDAR_ITEM, GUARDED_EVENTS, REMINDER_MEMBERSHIP, GUARDED_REMINDER_ITEM, BOUNDED_INSTANCE,
}

internal fun nativeProbeSortAllowed(scope: NativeProbeQueryScope, sort: String?): Boolean =
    when (scope) {
        NativeProbeQueryScope.OWNED_CALENDAR_ITEM -> sort == "_id ASC"
        else -> sort == null
    }

internal enum class NativeProbeMutationKind {
    LOCAL_SAVED, LOCAL_DELETED, PERMISSION_REQUIRED, DENIED, VALIDATION_FAILED, CONFLICT,
    UNAVAILABLE, SAFE_TO_RETRY, RETIRED, UNCERTAIN,
}

/** No ref, latest details, operation ID, exception text or caller payload can enter these facts. */
internal data class NativeProbeMutationFacts(
    val kind: NativeProbeMutationKind,
    val fields: Set<InvalidEventField> = emptySet(),
    val denial: MutationDenial? = null,
) {
    fun safeFields(): Map<String, Any> = buildMap {
        put("kind", kind.name)
        if (kind == NativeProbeMutationKind.VALIDATION_FAILED) put("fields", fields.map { it.name }.sorted())
        denial?.let { put("denial", it.name) }
    }
}

internal fun nativeProbeMutationFacts(result: MutationResult): NativeProbeMutationFacts = when (result) {
    is MutationResult.LocalSaved -> NativeProbeMutationFacts(NativeProbeMutationKind.LOCAL_SAVED)
    is MutationResult.LocalDeleted -> NativeProbeMutationFacts(NativeProbeMutationKind.LOCAL_DELETED)
    MutationResult.PermissionRequired -> NativeProbeMutationFacts(NativeProbeMutationKind.PERMISSION_REQUIRED)
    is MutationResult.Denied -> NativeProbeMutationFacts(NativeProbeMutationKind.DENIED, denial = result.reason)
    is MutationResult.ValidationFailed -> NativeProbeMutationFacts(NativeProbeMutationKind.VALIDATION_FAILED, fields = result.fields)
    is MutationResult.Conflict -> NativeProbeMutationFacts(NativeProbeMutationKind.CONFLICT)
    MutationResult.Unavailable -> NativeProbeMutationFacts(NativeProbeMutationKind.UNAVAILABLE)
    is MutationResult.SafeToRetry -> NativeProbeMutationFacts(NativeProbeMutationKind.SAFE_TO_RETRY)
    is MutationResult.Retired -> NativeProbeMutationFacts(NativeProbeMutationKind.RETIRED)
    is MutationResult.Uncertain -> NativeProbeMutationFacts(NativeProbeMutationKind.UNCERTAIN)
}

/** Keep the real returned result observable even when a later journal/ACK postcondition fails. */
internal inline fun <T> withNativeProbeMutationFacts(
    result: MutationResult, publish: (NativeProbeMutationFacts) -> Unit, inspect: () -> T,
): T {
    publish(nativeProbeMutationFacts(result))
    return inspect()
}

internal enum class NativeProbeQueryFailureKind {
    HARNESS_PROJECTION_REJECTED, HARNESS_SCOPE_REJECTED, HARNESS_SORT_REJECTED,
    INJECTED_POST_ACK_READ, NATIVE_NULL_CURSOR, NATIVE_CURSOR_LIMIT,
    SDK_PERMISSION, SDK_CANCELLATION, SDK_ARGUMENT, SDK_IO, SDK_OTHER,
}
internal enum class NativeProbeQueryStage { REQUEST_GUARD, DELEGATE_QUERY }
internal data class NativeProbeQueryFacts(
    val kind: NativeProbeQueryFailureKind, val stage: NativeProbeQueryStage,
    val scope: NativeProbeQueryScope? = null,
) {
    fun safeFields(): Map<String, Any> = buildMap {
        put("kind", kind.name); put("stage", stage.name); scope?.let { put("scope", it.name) }
    }
}
internal fun nativeProbeQueryFailure(error: Throwable): NativeProbeQueryFailureKind = when (error) {
    is SecurityException -> NativeProbeQueryFailureKind.SDK_PERMISSION
    is CancellationException -> NativeProbeQueryFailureKind.SDK_CANCELLATION
    is IllegalArgumentException -> NativeProbeQueryFailureKind.SDK_ARGUMENT
    is IOException -> NativeProbeQueryFailureKind.SDK_IO
    else -> NativeProbeQueryFailureKind.SDK_OTHER
}
