package dev.swapnilraj.calendar.data.calendar

/** Independent in-place effect contract. It grants no clone admission or installed-provider trust. */
internal data class SeriesGraphDurationNativeBinding(
    val authority: String, val providerComponent: String, val providerPackage: String,
    val providerVersion: Long, val providerCertificateSha256: String, val sdk: Int,
    val callerPackage: String, val callerUid: Int, val callerPackageCount: Int,
    val effectContract: String = EFFECT,
) {
    init {
        require(authority == "com.android.calendar" && effectContract == EFFECT)
        require(providerPackage.length <= 255 && callerPackage.length <= 255 && providerComponent.length <= 512)
        require(providerPackage.matches(PACKAGE) && callerPackage.matches(PACKAGE))
        require(providerComponent.startsWith("$providerPackage/") && providerComponent.substringAfter('/').isNotBlank())
        require(providerVersion >= 0 && sdk > 0 && callerUid >= 0 && callerPackageCount == 1)
        require(providerCertificateSha256.matches(Regex("[0-9a-f]{64}")))
    }
    companion object {
        const val EFFECT = "AOSP_NEVER_DURATION_CACHE_GATE_V1"
        private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    }
}

/** Trusted injection seam only; this packet does not attest any installed APK or provider profile. */
internal fun interface SeriesGraphDurationNativeVerifier {
    suspend fun current(): SeriesGraphDurationNativeBinding?

    data object Unverified : SeriesGraphDurationNativeVerifier {
        override suspend fun current(): SeriesGraphDurationNativeBinding? = null
    }
}
