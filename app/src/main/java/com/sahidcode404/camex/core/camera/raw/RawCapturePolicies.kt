package com.sahidcode404.camex.core.camera.raw

import com.sahidcode404.camex.core.camera.topology.CameraProfile
import com.sahidcode404.camex.core.camera.topology.CameraProfileSelector
import com.sahidcode404.camex.core.model.LensFingerprint

/** Failover is restricted to transport profiles that belong to the same canonical optical lens. */
object RawProfileFailoverPolicy {
    fun candidates(
        profiles: List<CameraProfile>,
        canonicalFingerprint: LensFingerprint?,
        attemptedProfileFingerprints: Set<String>,
    ): List<CameraProfile> {
        if (canonicalFingerprint == null) return emptyList()
        return CameraProfileSelector.ordered(profiles)
            .filterNot { it.profileFingerprint in attemptedProfileFingerprints }
            .filter { profile ->
                val metadata = profile.metadata
                metadata.rawSizes.any { it.isValid } ||
                    profile.fullCapabilities?.capabilities
                        ?.configurations(com.sahidcode404.camex.core.model.StreamFormat.RAW_SENSOR)
                        ?.isNotEmpty() == true
            }
    }
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
