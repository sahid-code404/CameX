package com.sahidcode404.camex.core.camera.raw

import com.sahidcode404.camex.core.camera.topology.CameraProfile
import com.sahidcode404.camex.core.camera.topology.CameraProfileSelector
import com.sahidcode404.camex.core.camera.topology.CameraRoute
import com.sahidcode404.camex.core.model.LensFingerprint
import com.sahidcode404.camex.core.model.StreamFormat

/** Failover is restricted to transport profiles that belong to the same canonical optical lens. */
object RawProfileFailoverPolicy {
    fun candidates(
        routes: List<CameraRoute>,
        canonicalFingerprint: LensFingerprint?,
        attemptedProfileFingerprints: Set<String>,
    ): List<CameraProfile> {
        val fingerprint = canonicalFingerprint ?: return emptyList()
        val route = routes.firstOrNull { it.lensFingerprint?.value == fingerprint.value }
            ?: return emptyList()
        return CameraProfileSelector.ordered(route.profiles)
            .filterNot { it.profileFingerprint in attemptedProfileFingerprints }
            .filter(::hasRawPotential)
    }

    private fun hasRawPotential(profile: CameraProfile): Boolean =
        profile.metadata.rawSizes.any { it.isValid } ||
            profile.fullCapabilities?.capabilities
                ?.configurations(StreamFormat.RAW_SENSOR)
                ?.any { !it.maximumResolution && it.size.isValid } == true
}

/** Small duplicate-capture gate; reset is explicit so failure paths cannot permanently lock shutter. */
class RawCaptureGate {
    private var active = false

    @Synchronized
    fun tryBegin(): Boolean {
        if (active) return false
        active = true
        return true
    }

    @Synchronized
    fun end() {
        active = false
    }

    @Synchronized
    fun isActive(): Boolean = active
}
