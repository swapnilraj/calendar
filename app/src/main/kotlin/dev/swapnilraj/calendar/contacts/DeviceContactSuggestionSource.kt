package dev.swapnilraj.calendar.contacts

/** Ephemeral local address suggestion, never a validated recipient or calendar authority.
 * Preserve the provider's strings; feature-specific guest validation runs after selection.
 * Contact IDs, account identities, lookup URIs and roles do not cross this boundary. */
data class DeviceContactSuggestion(val email: String, val name: String?)

sealed interface DeviceContactSearchResult {
    data object Disabled : DeviceContactSearchResult
    data object QueryRequired : DeviceContactSearchResult
    data object InvalidQuery : DeviceContactSearchResult
    data object PermissionRequired : DeviceContactSearchResult
    data object Unavailable : DeviceContactSearchResult
    data object LimitExceeded : DeviceContactSearchResult
    data class Ready(val items: List<DeviceContactSuggestion>, val truncated: Boolean) : DeviceContactSearchResult
}

/** The caller must opt in explicitly for this picker session; an OS grant alone does not enable it.
 * Disabled requests perform no query. Implementations search only the OS's local DEFAULT email
 * directory, check READ_CONTACTS, bound input/output/work and propagate cancellation. They never
 * prompt, write, persist history, enumerate accounts/directories, or use an enterprise endpoint.
 * A ready result grants no permission to mutate a calendar. No address/name normalization or
 * syntactic recipient validation belongs here; only byte-identical suggestion tuples may dedupe. */
fun interface DeviceContactSuggestionSource {
    suspend fun search(query: String, enabled: Boolean): DeviceContactSearchResult
}
