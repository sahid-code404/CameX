package com.sahidcode404.camex.core.camera.topology

import com.sahidcode404.camex.core.model.CapabilitySupport
import com.sahidcode404.camex.core.model.ColorFilterArrangement
import com.sahidcode404.camex.core.model.FieldOfView
import com.sahidcode404.camex.core.model.HardwareLevel
import com.sahidcode404.camex.core.model.LensCapabilities
import com.sahidcode404.camex.core.model.LensFacing
import com.sahidcode404.camex.core.model.LensFingerprint
import com.sahidcode404.camex.core.model.PhysicalSize
import com.sahidcode404.camex.core.model.SensorRect
import com.sahidcode404.camex.core.model.Size2D
import com.sahidcode404.camex.core.model.StreamFormat
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class CameraRouteKind {
    PUBLIC_DIRECT,
    LOGICAL_PARENT,
    LOGICAL_PHYSICAL_MEMBER,
    NDK_DIRECT,
    DEEP_NDK_DIRECT,
    EXTERNAL,
}

@Serializable
enum class CameraDiscoverySource {
    CACHE,
    JAVA_PUBLIC,
    JAVA_PHYSICAL,
    NDK_ADVERTISED,
    NDK_DEEP,
}

@Serializable
enum class CameraMetadataTrust {
    UNKNOWN,
    DISCOVERED,
    METADATA_VALID,
    METADATA_REJECTED,
    ACCESS_DENIED,
    SYSTEM_ONLY,
    BROKEN,
}

@Serializable
enum class CameraSessionTrust {
    UNKNOWN,
    SESSION_VERIFIED,
    SESSION_REJECTED,
    TRANSIENT_FAILURE,
}

@Serializable
enum class CameraRawTrust {
    UNKNOWN,
    NOT_ADVERTISED,
    RAW_VERIFIED,
    RAW_REJECTED,
    TRANSIENT_FAILURE,
}

@Serializable
enum class CameraFailureDurability {
    TRANSIENT,
    STRUCTURAL,
}

@Serializable
enum class CameraRouteFailureKind {
    ACCESS_DENIED,
    SYSTEM_ONLY,
    INVALID_METADATA,
    SESSION_CONFIGURATION_UNSUPPORTED,
    RAW_CONFIGURATION_UNSUPPORTED,
    CAMERA_IN_USE,
    MAX_CAMERAS_IN_USE,
    DISCONNECTED,
    APP_BACKGROUNDED,
    TIMEOUT,
    SERVICE_ERROR,
    MALFORMED_VENDOR_METADATA,
    UNKNOWN,
}

@Serializable
data class CameraRouteFailure(
    val kind: CameraRouteFailureKind,
    val durability: CameraFailureDurability,
    /** Bounded diagnostic text. It must not contain a stack trace or user data. */
    val detail: String? = null,
) {
    fun normalized(): CameraRouteFailure = copy(
        detail = detail?.trim()?.take(MAX_FAILURE_DETAIL_LENGTH)?.takeIf(String::isNotEmpty),
    )

    companion object {
        const val MAX_FAILURE_DETAIL_LENGTH = 240
    }
}

@Serializable
data class CameraRouteTrust(
    val metadata: CameraMetadataTrust = CameraMetadataTrust.DISCOVERED,
    val session: CameraSessionTrust = CameraSessionTrust.UNKNOWN,
    val raw: CameraRawTrust = CameraRawTrust.UNKNOWN,
    val failure: CameraRouteFailure? = null,
    /** Wall-clock diagnostic metadata only; never used for latency calculations. */
    val lastAttemptEpochMs: Long? = null,
)

@Serializable
enum class PhotographicRole {
    PHOTOGRAPHIC_ULTRAWIDE,
    PHOTOGRAPHIC_WIDE,
    PHOTOGRAPHIC_TELEPHOTO,
    PHOTOGRAPHIC_SUPER_TELEPHOTO,
    PHOTOGRAPHIC_MACRO,
    PHOTOGRAPHIC_MONO,
    PHOTOGRAPHIC_UNKNOWN,
    NON_PHOTO_DEPTH,
    NON_PHOTO_TOF,
    NON_PHOTO_IR,
    SYSTEM_ONLY,
    INACCESSIBLE,
    BROKEN,
}

@Serializable
enum class RoleConfidence {
    UNKNOWN,
    WEAK,
    MODERATE,
    STRONG,
    CONFIRMED,
}

/**
 * Small metadata projection used on the startup path. Backends should populate only fields they
 * obtained cheaply; UNKNOWN and null are meaningful and must not be turned into negative claims.
 */
@Serializable
data class MinimalCameraMetadata(
    val facing: LensFacing = LensFacing.UNKNOWN,
    val focalLengthsMm: List<Double> = emptyList(),
    val sensorPhysicalSize: PhysicalSize? = null,
    val activeArray: SensorRect? = null,
    val pixelArraySize: Size2D? = null,
    val sensorOrientationDegrees: Int? = null,
    val approximateFieldOfView: FieldOfView? = null,
    val hardwareLevel: HardwareLevel = HardwareLevel.UNKNOWN,
    val backwardCompatibleAdvertised: CapabilitySupport = CapabilitySupport.UNKNOWN,
    val rawCapabilityAdvertised: CapabilitySupport = CapabilitySupport.UNKNOWN,
    val rawStreamActuallyDeclared: CapabilitySupport = CapabilitySupport.UNKNOWN,
    val rawFormats: Set<StreamFormat> = emptySet(),
    val rawSizes: List<Size2D> = emptyList(),
    val previewStreamActuallyDeclared: CapabilitySupport = CapabilitySupport.UNKNOWN,
    val privatePreviewSizes: List<Size2D> = emptyList(),
    val yuvPreviewSizes: List<Size2D> = emptyList(),
    val depthEvidence: CapabilitySupport = CapabilitySupport.UNKNOWN,
    val tofEvidence: CapabilitySupport = CapabilitySupport.UNKNOWN,
    val infraredEvidence: CapabilitySupport = CapabilitySupport.UNKNOWN,
    val monochromeEvidence: CapabilitySupport = CapabilitySupport.UNKNOWN,
    val systemCameraAdvertised: CapabilitySupport = CapabilitySupport.UNKNOWN,
) {
    val hasCrediblePhotographicEvidence: Boolean
        get() = rawStreamActuallyDeclared == CapabilitySupport.SUPPORTED ||
            previewStreamActuallyDeclared == CapabilitySupport.SUPPORTED &&
            (focalLengthsMm.any { it.isFinite() && it > 0.0 } ||
                sensorPhysicalSize?.isValid == true ||
                activeArray?.isValid == true ||
                pixelArraySize?.isValid == true ||
                backwardCompatibleAdvertised == CapabilitySupport.SUPPORTED)
}

@Serializable
data class FullCameraCapabilities(
    val capabilities: LensCapabilities = LensCapabilities(),
    val complete: Boolean = false,
)

/** One exact transport/profile route underneath a physical optical lens. */
@Serializable
data class CameraProfile(
    val profileId: String,
    val profileFingerprint: String,
    val discoveredCameraId: String,
    val openCameraId: String,
    val streamPhysicalCameraId: String? = null,
    val logicalParentCameraId: String? = null,
    val routeKind: CameraRouteKind,
    val discoverySources: Set<CameraDiscoverySource>,
    val metadata: MinimalCameraMetadata = MinimalCameraMetadata(),
    val fullCapabilities: FullCameraCapabilities? = null,
    val sessionTrust: CameraSessionTrust = CameraSessionTrust.UNKNOWN,
    val rawTrust: CameraRawTrust = CameraRawTrust.UNKNOWN,
    val metadataTrust: CameraMetadataTrust = CameraMetadataTrust.DISCOVERED,
    val failure: CameraRouteFailure? = null,
    val lastAttemptEpochMs: Long? = null,
    val priority: Int = 0,
) {
    val routingKey: String
        get() = buildString {
            append(openCameraId)
            streamPhysicalCameraId?.let { append("/").append(it) }
        }

    val trust: CameraRouteTrust
        get() = CameraRouteTrust(
            metadataTrust,
            sessionTrust,
            rawTrust,
            failure,
            lastAttemptEpochMs,
        )

    val structurallyRejected: Boolean
        get() = sessionTrust == CameraSessionTrust.SESSION_REJECTED &&
            failure?.durability == CameraFailureDurability.STRUCTURAL
}

@Serializable
data class OpticalLensSignature(
    val facing: LensFacing,
    val focalLengthMm: Double? = null,
    val sensorPhysicalSize: PhysicalSize? = null,
    val activeArraySize: Size2D? = null,
    val pixelArraySize: Size2D? = null,
    val rawSizes: List<Size2D> = emptyList(),
    val colorFilterArrangement: ColorFilterArrangement? = null,
    val sensorOrientationDegrees: Int? = null,
    val aperture: Double? = null,
    val diagonalFieldOfViewDegrees: Double? = null,
)

/** Persisted pairwise reasoning so hardware reports can explain both merges and non-merges. */
@Serializable
data class OpticalGroupingComparisonRecord(
    val leftProfileId: String,
    val rightProfileId: String,
    val leftProfileFingerprint: String,
    val rightProfileFingerprint: String,
    val match: String,
    val score: Int,
    val evidenceFamilies: List<String> = emptyList(),
    val positiveReasons: List<String> = emptyList(),
    val negativeReasons: List<String> = emptyList(),
)

@Serializable
data class CanonicalLens(
    val canonicalLensId: String,
    val lensFingerprint: LensFingerprint,
    val facing: LensFacing,
    val minimalMetadata: MinimalCameraMetadata,
    val fullCapabilities: FullCameraCapabilities? = null,
    val role: PhotographicRole = PhotographicRole.PHOTOGRAPHIC_UNKNOWN,
    val roleConfidence: RoleConfidence = RoleConfidence.UNKNOWN,
    val profiles: List<CameraProfile>,
    val preferredProfileId: String?,
    val sessionTrust: CameraSessionTrust,
    val rawTrust: CameraRawTrust,
) {
    val preferredProfile: CameraProfile?
        get() = preferredProfileId?.let { id -> profiles.firstOrNull { it.profileId == id } }
            ?: profiles.firstOrNull()
}

@Serializable
data class CameraRouteAlias(
    val discoveredCameraId: String,
    val openCameraId: String,
    val streamPhysicalCameraId: String? = null,
    val logicalParentCameraId: String? = null,
    val routeKind: CameraRouteKind,
    val sources: Set<CameraDiscoverySource>,
    val minimalMetadata: MinimalCameraMetadata = MinimalCameraMetadata(),
    val fullCapabilities: FullCameraCapabilities? = null,
    val trust: CameraRouteTrust = CameraRouteTrust(),
) {
    val profileId: String
        get() = canonicalRouteId(openCameraId, streamPhysicalCameraId)

    val profileFingerprint: String
        get() = cameraProfileFingerprint(
            discoveredCameraId,
            openCameraId,
            streamPhysicalCameraId,
            routeKind,
        )
}

/**
 * Compatibility-shaped canonical optical lens. Legacy route fields mirror the preferred profile so
 * existing session/UI code can address it, while exact profile metadata is persisted separately.
 */
@Serializable
data class CameraRoute(
    val canonicalRouteId: String,
    val discoveredCameraId: String,
    val openCameraId: String,
    val streamPhysicalCameraId: String? = null,
    val logicalParentCameraId: String? = null,
    val routeKind: CameraRouteKind,
    val sources: Set<CameraDiscoverySource>,
    /** Canonical/representative optical metadata, not a transport profile snapshot. */
    val minimalMetadata: MinimalCameraMetadata,
    val fullCapabilities: FullCameraCapabilities? = null,
    val lensFingerprint: LensFingerprint? = null,
    val role: PhotographicRole = PhotographicRole.PHOTOGRAPHIC_UNKNOWN,
    val roleConfidence: RoleConfidence = RoleConfidence.UNKNOWN,
    /** Aggregate lens trust: one profile failure cannot poison the physical lens. */
    val trust: CameraRouteTrust = CameraRouteTrust(),
    val aliases: List<CameraRouteAlias> = emptyList(),
    @SerialName("profiles")
    val storedProfiles: List<CameraProfile> = emptyList(),
    val preferredProfileId: String? = null,
) {
    val hasCrediblePhotographicEvidence: Boolean
        get() = minimalMetadata.hasCrediblePhotographicEvidence ||
            trust.session == CameraSessionTrust.SESSION_VERIFIED

    val isNormalPhotographicRoute: Boolean
        get() = role.name.startsWith("PHOTOGRAPHIC_") &&
            role != PhotographicRole.PHOTOGRAPHIC_UNKNOWN &&
            trust.metadata != CameraMetadataTrust.ACCESS_DENIED &&
            trust.metadata != CameraMetadataTrust.SYSTEM_ONLY &&
            trust.metadata != CameraMetadataTrust.BROKEN &&
            trust.metadata != CameraMetadataTrust.METADATA_REJECTED &&
            trust.session != CameraSessionTrust.SESSION_REJECTED

    val isAdvancedPhotographicCandidate: Boolean
        get() = role == PhotographicRole.PHOTOGRAPHIC_UNKNOWN &&
            hasCrediblePhotographicEvidence &&
            trust.metadata != CameraMetadataTrust.ACCESS_DENIED &&
            trust.metadata != CameraMetadataTrust.SYSTEM_ONLY &&
            trust.metadata != CameraMetadataTrust.BROKEN &&
            trust.metadata != CameraMetadataTrust.METADATA_REJECTED &&
            trust.session != CameraSessionTrust.SESSION_REJECTED

    /** Exact transport snapshots; old fixtures/routes fall back to the compatibility route fields. */
    val profiles: List<CameraProfile>
        get() = storedProfiles.takeIf(List<CameraProfile>::isNotEmpty)
            ?: (listOf(primaryProfile()) + aliases.map(CameraRouteAlias::toProfile))
                .distinctBy(CameraProfile::profileId)

    fun toCanonicalLens(): CanonicalLens {
        val fingerprint = requireNotNull(lensFingerprint) { "Canonical lens requires a fingerprint" }
        return CanonicalLens(
            canonicalLensId = "cl3_${fingerprint.value}",
            lensFingerprint = fingerprint,
            facing = minimalMetadata.facing,
            minimalMetadata = minimalMetadata,
            fullCapabilities = fullCapabilities,
            role = role,
            roleConfidence = roleConfidence,
            profiles = profiles,
            preferredProfileId = preferredProfileId ?: primaryProfile().profileId,
            sessionTrust = trust.session,
            rawTrust = trust.raw,
        )
    }

    private fun primaryProfile(): CameraProfile = CameraProfile(
        profileId = canonicalRouteId(openCameraId, streamPhysicalCameraId),
        profileFingerprint = cameraProfileFingerprint(
            discoveredCameraId,
            openCameraId,
            streamPhysicalCameraId,
            routeKind,
        ),
        discoveredCameraId = discoveredCameraId,
        openCameraId = openCameraId,
        streamPhysicalCameraId = streamPhysicalCameraId,
        logicalParentCameraId = logicalParentCameraId,
        routeKind = routeKind,
        discoverySources = sources,
        metadata = minimalMetadata,
        fullCapabilities = fullCapabilities,
        sessionTrust = trust.session,
        rawTrust = trust.raw,
        metadataTrust = trust.metadata,
        failure = trust.failure,
        lastAttemptEpochMs = trust.lastAttemptEpochMs,
    )
}

private fun CameraRouteAlias.toProfile(): CameraProfile = CameraProfile(
    profileId = profileId,
    profileFingerprint = profileFingerprint,
    discoveredCameraId = discoveredCameraId,
    openCameraId = openCameraId,
    streamPhysicalCameraId = streamPhysicalCameraId,
    logicalParentCameraId = logicalParentCameraId,
    routeKind = routeKind,
    discoverySources = sources,
    metadata = minimalMetadata,
    fullCapabilities = fullCapabilities,
    sessionTrust = trust.session,
    rawTrust = trust.raw,
    metadataTrust = trust.metadata,
    failure = trust.failure,
    lastAttemptEpochMs = trust.lastAttemptEpochMs,
)

@Serializable
data class LogicalCameraRelationship(
    val logicalCameraId: String,
    val physicalCameraIds: List<String>,
)

@Serializable
data class CameraTopology(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val environmentFingerprint: CameraEnvironmentFingerprint,
    val routes: List<CameraRoute> = emptyList(),
    val logicalRelationships: List<LogicalCameraRelationship> = emptyList(),
    val groupingComparisons: List<OpticalGroupingComparisonRecord> = emptyList(),
) {
    val canonicalLenses: List<CanonicalLens>
        get() = routes.mapNotNull { route ->
            runCatching { route.toCanonicalLens() }.getOrNull()
        }

    companion object {
        // v3 invalidates the over-merged Phase 1B topology/fingerprint cache. A corrected build must
        // rediscover rather than boot a stale single-Wide canonical topology.
        const val CURRENT_SCHEMA_VERSION = 3
        const val CACHE_SCHEMA_VERSION = 3
        const val DISCOVERY_SCHEMA_VERSION = 3
    }
}

@Serializable
data class CameraRouteEvidence(
    val source: CameraDiscoverySource,
    val discoveredCameraId: String,
    val openCameraId: String = discoveredCameraId,
    val streamPhysicalCameraId: String? = null,
    val logicalParentCameraId: String? = null,
    val routeKind: CameraRouteKind,
    val minimalMetadata: MinimalCameraMetadata = MinimalCameraMetadata(),
    val fullCapabilities: FullCameraCapabilities? = null,
    val lensFingerprint: LensFingerprint? = null,
    val trust: CameraRouteTrust = CameraRouteTrust(),
)

@Serializable
enum class TopologyReconciliationMode {
    CACHE_BOOTSTRAP,
    INCREMENTAL,
    FULLY_RECONCILED,
}

internal fun canonicalRouteId(openCameraId: String, physicalCameraId: String?): String = buildString {
    append("cr1_")
    append(openCameraId.length).append(':').append(openCameraId)
    append('|')
    val physical = physicalCameraId.orEmpty()
    append(physical.length).append(':').append(physical)
}

internal fun cameraProfileFingerprint(
    discoveredCameraId: String,
    openCameraId: String,
    physicalCameraId: String?,
    routeKind: CameraRouteKind,
): String = buildString {
    append("cp2_")
    append(routeKind.ordinal).append('|')
    append(discoveredCameraId.length).append(':').append(discoveredCameraId).append('|')
    append(openCameraId.length).append(':').append(openCameraId).append('|')
    val physical = physicalCameraId.orEmpty()
    append(physical.length).append(':').append(physical)
}
