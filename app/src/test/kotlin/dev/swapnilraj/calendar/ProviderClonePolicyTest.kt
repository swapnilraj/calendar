package dev.swapnilraj.calendar.data.calendar

import dev.swapnilraj.calendar.SyntheticCloneProviderPolicy
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class ProviderClonePolicyTest {
    private val operation = ProviderCloneOperation.GUEST_FREE_EXCEPTION
    @Test fun noProductionProfileIsImplicitlyAdmittedAndSyntheticProfileIsExplicit() {
        ProviderCloneOperation.entries.forEach { assertFalse(ProviderClonePolicy.Unverified.allows(it)); assertTrue(SyntheticCloneProviderPolicy.allows(it)) }
    }
    @Test fun missingOperationAndIncompleteImplementationEvidenceCannotAdmitCloning() {
        val fixture = SyntheticCloneProviderPolicy.currentProfile(operation)
        val incomplete = listOf(fixture.copy(id = ""), fixture.copy(evidenceRevision = ""), fixture.copy(evidenceReference = ""), fixture.copy(operations = emptySet()),
            fixture.copy(implementation = fixture.implementation.copy(packageName = "")),
            fixture.copy(implementation = fixture.implementation.copy(versionCode = -1)),
            fixture.copy(implementation = fixture.implementation.copy(apkSha256 = emptySet())),
            fixture.copy(implementation = fixture.implementation.copy(apkSha256 = setOf("not-a-binary-digest"))),
            fixture.copy(implementation = fixture.implementation.copy(signingLineageSha256 = emptyList())),
            fixture.copy(implementation = fixture.implementation.copy(frameworkBuildIdentity = "")))
        incomplete.forEach { profile -> assertFalse(ProviderClonePolicy { profile }.allows(operation)) }
        assertFalse(ProviderClonePolicy { error("Synthetic unavailable attestor") }.allows(operation))
    }
    @Test(expected = CancellationException::class) fun cancelledAttestationIsNotConvertedIntoAdmission() {
        ProviderClonePolicy { throw CancellationException("Synthetic cancellation") }.allows(operation)
    }
}
