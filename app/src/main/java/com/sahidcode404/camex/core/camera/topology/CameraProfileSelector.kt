package com.sahidcode404.camex.core.camera.topology

import com.sahidcode404.camex.core.model.CapabilitySupport

/**
 * Chooses a transport profile for one canonical optical lens. The policy is evidence based and
 * never compares numeric camera IDs. SESSION_VERIFIED profiles are sticky winners; structural
 * rejections fall to the bottom and are never tried ahead of an untested credible profile.
 */
object CameraProfileSelector {
    fun select(profiles: Collection<CameraProfile>): CameraProfile? = profiles
        .filter(::credible)
        .maxWithOrNull(compareBy<CameraProfile>(::score)
            .thenBy { it.profileFingerprint })

    fun ordered(profiles: Collection<CameraProfile>): List<CameraProfile> = profiles
        .filter(::credible)
        .sortedWith(compareByDescending<CameraProfile>(::score)
            .thenBy(CameraProfile::profileFingerprint))

    fun score(profile: CameraProfile): Int {
        var score = 0
        score += when (profile.sessionTrust) {
            CameraSessionTrust.SESSION_VERIFIED -> 1_000
            CameraSessionTrust.UNKNOWN -> 100
            CameraSessionTrust.TRANSIENT_FAILURE -> 60
            CameraSessionTrust.SESSION_REJECTED -> if (profile.structurallyRejected) -2_000 else -200
        }
        score += when (profile.routeKind) {
            CameraRouteKind.PUBLIC_DIRECT -> 180
            CameraRouteKind.LOGICAL_PHYSICAL_MEMBER -> 170
            CameraRouteKind.LOGICAL_PARENT -> 145
            CameraRouteKind.NDK_DIRECT -> 120
            CameraRouteKind.DEEP_NDK_DIRECT -> 95
            CameraRouteKind.EXTERNAL -> 130
        }
        if (CameraDiscoverySource.JAVA_PUBLIC in profile.discoverySources) score += 35
        if (CameraDiscoverySource.JAVA_PHYSICAL in profile.discoverySources) score += 30
        if (CameraDiscoverySource.NDK_ADVERTISED in profile.discoverySources) score += 20
        if (CameraDiscoverySource.NDK_DEEP in profile.discoverySources) score += 5

        val metadata = profile.metadata
        if (metadata.privatePreviewSizes.isNotEmpty()) score += 80
        if (metadata.previewStreamActuallyDeclared == CapabilitySupport.SUPPORTED) score += 40
        if (metadata.rawStreamActuallyDeclared == CapabilitySupport.SUPPORTED) score += 25
        if (metadata.rawSizes.isNotEmpty()) score += 15
        if (metadata.focalLengthsMm.isNotEmpty()) score += 12
        if (metadata.sensorPhysicalSize != null) score += 10
        if (metadata.pixelArraySize != null) score += 8
        if (metadata.activeArray != null) score += 8
        if (profile.fullCapabilities?.complete == true) score += 15
        if (profile.failure?.durability == CameraFailureDurability.TRANSIENT) score -= 20
        return score
    }

    private fun credible(profile: CameraProfile): Boolean {
        if (profile.metadataTrust == CameraMetadataTrust.SYSTEM_ONLY ||
            profile.metadataTrust == CameraMetadataTrust.ACCESS_DENIED ||
            profile.metadataTrust == CameraMetadataTrust.BROKEN ||
            profile.metadataTrust == CameraMetadataTrust.METADATA_REJECTED
        ) return false
        if (profile.structurallyRejected) return false
        return profile.metadata.hasCrediblePhotographicEvidence ||
            profile.sessionTrust == CameraSessionTrust.SESSION_VERIFIED ||
            profile.metadataTrust == CameraMetadataTrust.METADATA_VALID
    }
}

/** Lookup a profile by its stable transport/profile ID. */
fun CameraRoute.profile(profileId: String): CameraProfile? =
    profiles.firstOrNull { it.profileId == profileId }

/** Lookup a profile by Camera2 routing key. */
fun CameraRoute.profileForRoutingKey(routingKey: String): CameraProfile? =
    profiles.firstOrNull { it.routingKey == routingKey }

/**
 * Rotate the preferred transport without changing optical fingerprint or canonical role. Every
 * previous route remains in aliases for diagnostics and later engineering analysis.
 */
fun CameraRoute.promoteProfile(profileId: String): CameraRoute {
    val selected = profile(profileId) ?: return this
    val remaining = profiles.filterNot { it.profileId == selected.profileId }
    return copy(
        canonicalRouteId = selected.profileId,
        discoveredCameraId = selected.discoveredCameraId,
        openCameraId = selected.openCameraId,
        streamPhysicalCameraId = selected.streamPhysicalCameraId,
        logicalParentCameraId = selected.logicalParentCameraId,
        routeKind = selected.routeKind,
        sources = selected.discoverySources,
        minimalMetadata = selected.metadata,
        fullCapabilities = selected.fullCapabilities,
        trust = selected.trust,
        aliases = remaining.map(CameraProfile::toAlias),
    )
}

/** Promote the highest-ranked known-good/credible profile. */
fun CameraRoute.promoteBestProfile(): CameraRoute =
    CameraProfileSelector.select(profiles)?.let { promoteProfile(it.profileId) } ?: this

/** Replace only one profile's trust, then recalculate the preferred profile. */
fun CameraRoute.withProfileTrust(
    profileId: String,
    trust: CameraRouteTrust,
): CameraRoute {
    val updated = profiles.map { profile ->
        if (profile.profileId == profileId) profile.copy(
            metadataTrust = trust.metadata,
            sessionTrust = trust.session,
            rawTrust = trust.raw,
            failure = trust.failure,
        ) else profile
    }
    if (updated.none { it.profileId == profileId }) return this
    val preferred = CameraProfileSelector.select(updated)
        ?: updated.firstOrNull { it.profileId == canonicalRouteId }
        ?: updated.first()
    return copy(
        canonicalRouteId = preferred.profileId,
        discoveredCameraId = preferred.discoveredCameraId,
        openCameraId = preferred.openCameraId,
        streamPhysicalCameraId = preferred.streamPhysicalCameraId,
        logicalParentCameraId = preferred.logicalParentCameraId,
        routeKind = preferred.routeKind,
        sources = preferred.discoverySources,
        minimalMetadata = preferred.metadata,
        fullCapabilities = preferred.fullCapabilities,
        trust = preferred.trust,
        aliases = updated.filterNot { it.profileId == preferred.profileId }
            .map(CameraProfile::toAlias),
    )
}

internal fun CameraProfile.toAlias(): CameraRouteAlias = CameraRouteAlias(
    discoveredCameraId = discoveredCameraId,
    openCameraId = openCameraId,
    streamPhysicalCameraId = streamPhysicalCameraId,
    logicalParentCameraId = logicalParentCameraId,
    routeKind = routeKind,
    sources = discoverySources,
    minimalMetadata = metadata,
    fullCapabilities = fullCapabilities,
    trust = trust,
)
