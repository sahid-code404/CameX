package com.sahidcode404.camex.core.camera.topology

import com.sahidcode404.camex.core.model.CapabilitySupport
import com.sahidcode404.camex.core.model.FieldOfView
import com.sahidcode404.camex.core.model.HardwareLevel
import com.sahidcode404.camex.core.model.LensCapabilities
import com.sahidcode404.camex.core.model.LensFacing
import com.sahidcode404.camex.core.model.LensFingerprint
import com.sahidcode404.camex.core.model.PhysicalSize
import com.sahidcode404.camex.core.model.SensorRect
import com.sahidcode404.camex.core.model.Size2D
import com.sahidcode404.camex.core.model.StreamFormat
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
    /**
     * Evidence strong enough to offer lazy user validation. Merely having an ID or a redundant RAW
     * capability bit is not enough; an actual RAW declaration or preview-plus-optics is required.
     */
    val hasCrediblePhotographicEvidence: Boolean
        get() = rawStreamActuallyDeclared == CapabilitySupport.SUPPORTED ||
            previewStreamActuallyDeclared == CapabilitySupport.SUPPORTED &&
            (focalLengthsMm.any { it.isFinite() && it > 0.0 } ||
                sensorPhysicalSize?.isValid == true ||
                activeArray?.isValid == true ||
                pixelArraySize?.isValid == true ||
                backwardCompatibleAdvertised == CapabilitySupport.SUPPORTED)
}

/** Stage-B metadata; deliberately CameX-owned rather than a retained CameraCharacteristics. */
@Serializable
data class FullCameraCapabilities(
    val capabilities: LensCapabilities = LensCapabilities(),
    val complete: Boolean = false,
)

@Serializable
data class CameraRouteAlias(
    val discoveredCameraId: String,
    val openCameraId: String,
    val streamPhysicalCameraId: String? = null,
    val logicalParentCameraId: String? = null,
    val routeKind: CameraRouteKind,
    val sources: Set<CameraDiscoverySource>,
)

/** One immutable canonical route consumed by the runtime and selector. */
@Serializable
data class CameraRoute(
    val canonicalRouteId: String,
    val discoveredCameraId: String,
    val openCameraId: String,
    val streamPhysicalCameraId: String? = null,
    val logicalParentCameraId: String? = null,
    val routeKind: CameraRouteKind,
    val sources: Set<CameraDiscoverySource>,
    val minimalMetadata: MinimalCameraMetadata,
    val fullCapabilities: FullCameraCapabilities? = null,
    val lensFingerprint: LensFingerprint? = null,
    val role: PhotographicRole = PhotographicRole.PHOTOGRAPHIC_UNKNOWN,
    val roleConfidence: RoleConfidence = RoleConfidence.UNKNOWN,
    val trust: CameraRouteTrust = CameraRouteTrust(),
    val aliases: List<CameraRouteAlias> = emptyList(),
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
}

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
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val CACHE_SCHEMA_VERSION = 1
        const val DISCOVERY_SCHEMA_VERSION = 2
    }
}

/** One backend observation before canonical reconciliation. */
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
    /** Cache is the only backend; no live absence may be inferred. */
    CACHE_BOOTSTRAP,

    /** One or more live backends are still running; retain cache-only routes. */
    INCREMENTAL,

    /** Every requested backend, including any required deep scan, has completed. */
    FULLY_RECONCILED,
}

internal fun canonicalRouteId(openCameraId: String, physicalCameraId: String?): String = buildString {
    append("cr1_")
    append(openCameraId.length).append(':').append(openCameraId)
    append('|')
    val physical = physicalCameraId.orEmpty()
    append(physical.length).append(':').append(physical)
}
