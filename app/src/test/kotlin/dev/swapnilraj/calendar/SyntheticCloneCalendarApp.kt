package dev.swapnilraj.calendar

import dev.swapnilraj.calendar.data.calendar.ProviderCloneBinaryIdentity
import dev.swapnilraj.calendar.data.calendar.ProviderCloneOperation
import dev.swapnilraj.calendar.data.calendar.ProviderClonePolicy
import dev.swapnilraj.calendar.data.calendar.VerifiedProviderCloneProfile

/** Explicit admission for the synthetic SDK-only SQL fixtures. Never production-provider evidence. */
internal object SyntheticCloneProviderPolicy : ProviderClonePolicy {
    private val profile = VerifiedProviderCloneProfile(
        id = "synthetic-sdk-only-sql-fixture",
        evidenceRevision = "1",
        evidenceReference = "test-only: synthetic SQLite views and explicit SDK clone columns",
        implementation = ProviderCloneBinaryIdentity(
            packageName = "synthetic.fixture.calendar.provider",
            versionCode = 1,
            apkSha256 = setOf("0".repeat(64)),
            signingLineageSha256 = listOf("1".repeat(64)),
            frameworkBuildIdentity = "synthetic-robolectric-api35-fixture",
        ),
        operations = ProviderCloneOperation.entries.toSet(),
    )
    override fun currentProfile(operation: ProviderCloneOperation): VerifiedProviderCloneProfile = profile
}

/** Manifest-default CalendarApp stays unverified; only explicitly named fixture tests use this app. */
class SyntheticCloneCalendarApp : CalendarApp() {
    override val providerClonePolicy: ProviderClonePolicy get() = SyntheticCloneProviderPolicy
}
