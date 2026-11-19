package dev.swapnilraj.calendar.data.calendar

import kotlinx.coroutines.CancellationException

/** Clone operations need implementation evidence beyond a public CalendarContract projection. */
enum class ProviderCloneOperation { GUEST_FREE_EXCEPTION, GENERATED_RESPONSE_EXCEPTION, INDEPENDENT_TAIL }

data class ProviderCloneBinaryIdentity(
    val packageName: String,
    val versionCode: Long,
    val apkSha256: Set<String>,
    val signingLineageSha256: List<String>,
    val frameworkBuildIdentity: String,
)

/** Evidence supplied by an explicit implementation attestor, never inferred from an account type.
 * This value is not itself a binary verifier. The policy must check its installed identity afresh. */
data class VerifiedProviderCloneProfile(
    val id: String,
    val evidenceRevision: String,
    val evidenceReference: String,
    val implementation: ProviderCloneBinaryIdentity,
    val operations: Set<ProviderCloneOperation>,
)

/** A cheap, pure current-identity decision. Expensive attestation/package invalidation belongs to
 * its implementation. No production profile is admitted by this module. Unknown/stale evidence
 * must return null; neither provider authority, Google account metadata nor signer alone suffices. */
fun interface ProviderClonePolicy {
    fun currentProfile(operation: ProviderCloneOperation): VerifiedProviderCloneProfile?

    companion object {
        val Unverified = ProviderClonePolicy { null }
    }
}

internal fun ProviderClonePolicy.allows(operation: ProviderCloneOperation): Boolean = try {
    val profile = currentProfile(operation)
    if (profile == null || operation !in profile.operations) false else {
        val binary = profile.implementation
        val digest = Regex("[0-9a-f]{64}")
        profile.id.isNotBlank() && profile.evidenceRevision.isNotBlank() && profile.evidenceReference.isNotBlank() &&
            binary.packageName.isNotBlank() && binary.versionCode >= 0 && binary.frameworkBuildIdentity.isNotBlank() &&
            binary.apkSha256.isNotEmpty() && binary.apkSha256.all(digest::matches) &&
            binary.signingLineageSha256.isNotEmpty() && binary.signingLineageSha256.all(digest::matches)
    }
} catch (cancelled: CancellationException) { throw cancelled }
catch (_: Exception) { false }
