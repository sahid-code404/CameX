package com.sahidcode404.camex.core.camera.topology

import com.sahidcode404.camex.core.model.CapabilitySupport

/** Evidence-based transport selection for one canonical optical lens. */
object CameraProfileSelector {
    fun select(profiles: Collection<CameraProfile>): CameraProfile? = profiles
        .filter(::credible)
        .maxWithOrNull(compareBy<CameraProfile> { score(it) }
            .thenBy { it.profileFingerprint })

    fun ordered(profiles: Collection<CameraProfile>): List<CameraProfile> = profiles
        .filter(::credible)
        .sortedWith(compareByDescending<CameraProfile> { score(it) }
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

/** Whole-lens trust is an aggregate over profiles, never a copy of one arbitrary route. */
object CanonicalLensTrustAggregator {
    fun aggregate(
        profiles: Collection<CameraProfile>,
        canonicalMetadata: MinimalCameraMetadata,
    ): CameraRouteTrust {
        val values = profiles.toList()
        if (values.isEmpty()) return CameraRouteTrust()
        val metadata = when {
            canonicalMetadata.systemCameraAdvertised == CapabilitySupport.SUPPORTED ->
                CameraMetadataTrust.SYSTEM_ONLY
            values.any { it.metadataTrust == CameraMetadataTrust.METADATA_VALID } ||
                canonicalMetadata.hasCrediblePhotographicEvidence -> CameraMetadataTrust.METADATA_VALID
            values.all { it.metadataTrust == CameraMetadataTrust.ACCESS_DENIED } ->
                CameraMetadataTrust.ACCESS_DENIED
            values.all { it.metadataTrust == CameraMetadataTrust.SYSTEM_ONLY } ->
                CameraMetadataTrust.SYSTEM_ONLY
            values.all { it.metadataTrust == CameraMetadataTrust.BROKEN } ->
                CameraMetadataTrust.BROKEN
            values.any { it.metadataTrust == CameraMetadataTrust.DISCOVERED } ->
                CameraMetadataTrust.DISCOVERED
            else -> CameraMetadataTrust.UNKNOWN
        }
        val session = when {
            values.any { it.sessionTrust == CameraSessionTrust.SESSION_VERIFIED } ->
                CameraSessionTrust.SESSION_VERIFIED
            values.all(CameraProfile::structurallyRejected) -> CameraSessionTrust.SESSION_REJECTED
            values.any { it.sessionTrust == CameraSessionTrust.TRANSIENT_FAILURE } ->
                CameraSessionTrust.TRANSIENT_FAILURE
            else -> CameraSessionTrust.UNKNOWN
        }
        val raw = when {
            values.any { it.rawTrust == CameraRawTrust.RAW_VERIFIED } -> CameraRawTrust.RAW_VERIFIED
            values.all { it.rawTrust == CameraRawTrust.NOT_ADVERTISED } -> CameraRawTrust.NOT_ADVERTISED
            values.all {
                it.rawTrust == CameraRawTrust.RAW_REJECTED ||
                    it.rawTrust == CameraRawTrust.NOT_ADVERTISED
            } -> CameraRawTrust.RAW_REJECTED
            values.any { it.rawTrust == CameraRawTrust.TRANSIENT_FAILURE } ->
                CameraRawTrust.TRANSIENT_FAILURE
            else -> CameraRawTrust.UNKNOWN
        }
        val failure = if (session == CameraSessionTrust.SESSION_REJECTED) {
            values.mapNotNull(CameraProfile::failure)
                .filter { it.durability == CameraFailureDurability.STRUCTURAL }
                .sortedWith(compareBy<CameraRouteFailure>({ it.kind.ordinal }, { it.detail.orEmpty() }))
                .firstOrNull()
        } else {
            null
        }
        return CameraRouteTrust(
            metadata = metadata,
            session = session,
            raw = raw,
            failure = failure,
            lastAttemptEpochMs = values.mapNotNull(CameraProfile::lastAttemptEpochMs).maxOrNull(),
        )
    }
}

fun CameraRoute.profile(profileId: String): CameraProfile? =
    profiles.firstOrNull { it.profileId == profileId }

fun CameraRoute.profileForRoutingKey(routingKey: String): CameraProfile? =
    profiles.firstOrNull { it.routingKey == routingKey }

/** Change preferred transport only; optical metadata/fingerprint and aggregate lens trust remain. */
fun CameraRoute.promoteProfile(profileId: String): CameraRoute {
    val selected = profile(profileId) ?: return this
    val exactProfiles = profiles
    return copy(
        canonicalRouteId = selected.profileId,
        discoveredCameraId = selected.discoveredCameraId,
        openCameraId = selected.openCameraId,
        streamPhysicalCameraId = selected.streamPhysicalCameraId,
        logicalParentCameraId = selected.logicalParentCameraId,
        routeKind = selected.routeKind,
        sources = selected.discoverySources,
        aliases = exactProfiles.filterNot { it.profileId == selected.profileId }.map(CameraProfile::toAlias),
        storedProfiles = exactProfiles,
        preferredProfileId = selected.profileId,
    )
}

fun CameraRoute.promoteBestProfile(): CameraRoute =
    CameraProfileSelector.select(profiles)?.let { promoteProfile(it.profileId) } ?: this

/** Replace one profile's trust, aggregate whole-lens trust, and promote the best surviving profile. */
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
            lastAttemptEpochMs = trust.lastAttemptEpochMs,
        ) else profile
    }
    if (updated.none { it.profileId == profileId }) return this
    val preferred = CameraProfileSelector.select(updated)
        ?: preferredProfileId?.let { id -> updated.firstOrNull { it.profileId == id } }
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
        trust = CanonicalLensTrustAggregator.aggregate(updated, minimalMetadata),
        aliases = updated.filterNot { it.profileId == preferred.profileId }.map(CameraProfile::toAlias),
        storedProfiles = updated,
        preferredProfileId = preferred.profileId,
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
