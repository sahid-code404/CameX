package com.sahidcode404.camex.core.model

import kotlinx.serialization.Serializable

/**
 * Sanitized, engineering-only report. These DTOs intentionally have no account, location,
 * hardware serial, media, token, or arbitrary extras fields.
 */
@Serializable
data class CompatibilityReport(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val generatedAtUtc: String,
    val device: DeviceReport = DeviceReport(),
    val android: AndroidReport = AndroidReport(),
    val graphics: GraphicsReport = GraphicsReport(),
    val environment: CameraEnvironmentReport? = null,
    val cache: CameraCacheReport = CameraCacheReport(),
    val startupTrace: CameraStartupTraceReport = CameraStartupTraceReport(),
    val javaDiscovery: DiscoveryBackendReport = DiscoveryBackendReport(),
    val ndkDiscovery: DiscoveryBackendReport = DiscoveryBackendReport(),
    val deepDiscovery: DiscoveryBackendReport = DiscoveryBackendReport(),
    val canonicalTopology: CanonicalTopologyReport = CanonicalTopologyReport(),
    /** Phase 1B authoritative physical-lens → transport-profile representation. */
    val canonicalLenses: List<CanonicalLensCompatibilityReport> = emptyList(),
    /** Pairwise optical-identity reasoning, including comparisons that deliberately did not merge. */
    val opticalGrouping: List<OpticalGroupingComparisonReport> = emptyList(),
    /** Exact verified session/canonical bridge used by the Camera UI. */
    val activeSelection: ActiveCameraSelectionReport? = null,
    /** Camera selector projection at export time. */
    val cameraUi: CameraUiSelectionReport = CameraUiSelectionReport(),
    /** Legacy flat canonical-lens projection retained for report-reader compatibility. */
    val cameras: List<CameraCompatibilityEntry> = emptyList(),
    val discoveryFailures: List<DiscoveryFailureReport> = emptyList(),
    val logicalRelationships: List<LogicalRelationshipReport> = emptyList(),
    val hiddenRoutes: List<String> = emptyList(),
    val userVisibleRoutes: List<String> = emptyList(),
    val trustState: List<RouteTrustReport> = emptyList(),
    val failureReasons: List<FailureReasonSummaryReport> = emptyList(),
    val probeResults: List<ProbeReportEntry> = emptyList(),
    val quirks: List<AppliedQuirkReport> = emptyList(),
    val app: AppReport = AppReport(),
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 4
    }
}

@Serializable
data class ActiveCameraSelectionReport(
    val activeProfileRoutingKey: String,
    val activeProfileFingerprint: String? = null,
    val canonicalLensFingerprint: LensFingerprint? = null,
    val canonicalLensId: String? = null,
    val activeProfileFacing: LensFacing = LensFacing.UNKNOWN,
    val canonicalFacing: LensFacing = LensFacing.UNKNOWN,
    val selectionGeneration: Long,
    val sessionState: String,
    val verified: Boolean,
)

@Serializable
data class CameraUiSelectionReport(
    val normalVisibleFrontCount: Int = 0,
    val normalVisibleRearCount: Int = 0,
    val cameraUiFacing: LensFacing = LensFacing.UNKNOWN,
    val cameraUiLensCount: Int = 0,
    val selectedCanonicalFingerprint: String? = null,
    val switchFacingTarget: LensFacing? = null,
    val switchFacingEnabled: Boolean = false,
)

@Serializable
data class CameraEnvironmentReport(
    val stableKey: String,
    val cacheSchemaVersion: Int,
    val topologySchemaVersion: Int,
    val discoverySchemaVersion: Int,
    val apiLevel: Int,
    val advertisedTopologySignature: String? = null,
)

@Serializable
data class CameraCacheReport(
    val ready: Boolean = false,
    val hit: Boolean = false,
    val miss: Boolean = false,
    val missReason: String? = null,
    val generatedAtEpochMs: Long? = null,
    val deepScanRequired: Boolean = false,
)

/** Every offset is monotonic nanoseconds from APP_START, never a wall-clock timestamp. */
@Serializable
data class CameraStartupTraceReport(
    val offsetsNs: Map<String, Long> = emptyMap(),
    val cacheToLensesMs: Double? = null,
    val appToCameraRequestMs: Double? = null,
    val appToFirstPreviewFrameMs: Double? = null,
    val advertisedScanMs: Double? = null,
    val deepAuxScanMs: Double? = null,
)

@Serializable
data class DiscoveryBackendReport(
    val status: String = "NOT_STARTED",
    val candidateCount: Int = 0,
    val durationMs: Long? = null,
    val cameraIds: List<String> = emptyList(),
    val failureCount: Int = 0,
    val failuresByReason: List<FailureCountReport> = emptyList(),
    val failures: List<DiscoveryBackendFailureReport> = emptyList(),
)

@Serializable
data class FailureCountReport(
    val reason: String,
    val count: Int,
)

@Serializable
data class DiscoveryBackendFailureReport(
    val cameraId: String? = null,
    val publicCameraId: String? = null,
    val physicalCameraId: String? = null,
    val stage: String? = null,
    val reason: String,
    val statusCode: Int? = null,
    val detail: String? = null,
)

@Serializable
data class CanonicalTopologyReport(
    val schemaVersion: Int? = null,
    /** Number of physical/canonical optical lenses, not number of route/profile IDs. */
    val routeCount: Int = 0,
    val canonicalRouteIds: List<String> = emptyList(),
    val profileCount: Int = 0,
)

/** Authoritative Phase 1B report entry: one real/credible optical lens. */
@Serializable
data class CanonicalLensCompatibilityReport(
    val canonicalLensId: String,
    val opticalFingerprint: LensFingerprint? = null,
    val facing: LensFacing = LensFacing.UNKNOWN,
    val role: String? = null,
    val roleConfidence: String? = null,
    val focalLengthsMm: List<Double> = emptyList(),
    val fieldOfView: FieldOfView? = null,
    val sensorPhysicalSize: PhysicalSize? = null,
    val pixelArraySize: Size2D? = null,
    val canonicalTrust: CanonicalLensTrustReport = CanonicalLensTrustReport(),
    val preferredProfileId: String? = null,
    val profileCount: Int = 0,
    /** STRONG_MATCH for multi-profile lenses, SINGLE_PROFILE, or MIXED if invariant is violated. */
    val groupingConfidence: String = "SINGLE_PROFILE",
    val profiles: List<CameraProfileCompatibilityReport> = emptyList(),
)

@Serializable
data class CanonicalLensTrustReport(
    val metadataTrust: String = "UNKNOWN",
    val sessionTrust: String = "UNKNOWN",
    val rawTrust: String = "UNKNOWN",
    val lastAttemptEpochMs: Long? = null,
    val failure: CameraRouteFailureReport? = null,
)

/** Pairwise decision explaining whether two transport profiles may share optical identity. */
@Serializable
data class OpticalGroupingComparisonReport(
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

/** Exact Camera2/vendor transport endpoint underneath one CanonicalLens. */
@Serializable
data class CameraProfileCompatibilityReport(
    val profileId: String,
    val profileFingerprint: String,
    val preferred: Boolean = false,
    val ranking: Int,
    val profileScore: Int,
    val discoveredCameraId: String,
    val openCameraId: String,
    val physicalCameraId: String? = null,
    val logicalParentCameraId: String? = null,
    val routeKind: String,
    val discoverySources: List<String> = emptyList(),
    val metadataTrust: String,
    val sessionTrust: String,
    val rawTrust: String,
    val lastAttemptEpochMs: Long? = null,
    val failure: CameraRouteFailureReport? = null,
    val failureDurability: String? = null,
    val previewVerified: Boolean = false,
    val rawAdvertised: String = "UNKNOWN",
    val rawStreamActuallyDeclared: String = "UNKNOWN",
    /** Canonical assignment and exact per-profile optical/sensor metadata for hardware diagnosis. */
    val assignedCanonicalLensId: String? = null,
    val focalLengthsMm: List<Double> = emptyList(),
    val fieldOfView: FieldOfView? = null,
    val sensorPhysicalSize: PhysicalSize? = null,
    val pixelArraySize: Size2D? = null,
    val activeArray: SensorRect? = null,
    val rawDimensions: List<Size2D> = emptyList(),
    val colorFilterArrangement: String? = null,
    val sensorOrientationDegrees: Int? = null,
    val apertures: List<Double> = emptyList(),
    val groupingComparisons: List<OpticalGroupingComparisonReport> = emptyList(),
)

@Serializable
data class DiscoveryFailureReport(
    val publicCameraId: String? = null,
    val physicalCameraId: String? = null,
    val kind: String,
    val detail: String,
)

@Serializable
data class DeviceReport(
    val manufacturer: String? = null,
    val brand: String? = null,
    val model: String? = null,
    val deviceCodename: String? = null,
    val product: String? = null,
    /** Android build fingerprint; not a unique hardware serial. */
    val buildFingerprint: String? = null,
)

@Serializable
data class AndroidReport(
    val sdkInt: Int? = null,
    val release: String? = null,
    val securityPatch: String? = null,
)

@Serializable
data class GraphicsReport(
    val vulkanApiVersion: String? = null,
    val vulkanHardwareLevel: Int? = null,
    val vulkanHardwareVersion: Int? = null,
    val openGlEsVersion: String? = null,
    val renderer: String? = null,
    val vendor: String? = null,
)

/** Legacy flat entry: still one canonical lens, never one entry per CameraProfile. */
@Serializable
data class CameraCompatibilityEntry(
    val identity: LensIdentity,
    val canonicalRouteId: String? = null,
    val discoveredCameraId: String? = null,
    val openCameraId: String? = null,
    val streamPhysicalCameraId: String? = null,
    val logicalParentCameraId: String? = null,
    val routeKind: String? = null,
    val sources: List<String> = emptyList(),
    val role: String? = null,
    val roleConfidence: String? = null,
    val metadataTrust: String? = null,
    val sessionTrust: String? = null,
    val rawTrust: String? = null,
    val routeFailure: CameraRouteFailureReport? = null,
    val aliases: List<CameraRouteAliasReport> = emptyList(),
    val metadataEvidence: CameraMetadataEvidenceReport? = null,
    val cacheStatus: String? = null,
    val fingerprint: LensFingerprint? = null,
    val facing: LensFacing = LensFacing.UNKNOWN,
    val usability: LensUsability = LensUsability.UNKNOWN,
    val category: LensCategory = LensCategory.PHOTOGRAPHIC_UNKNOWN,
    val fieldOfView: FieldOfView? = null,
    val capabilities: LensCapabilities = LensCapabilities(),
    val maximumRawSize: Size2D? = null,
    val estimatedMaximumRawFps: Double? = null,
)

@Serializable
data class CameraRouteFailureReport(
    val kind: String,
    val durability: String,
    val detail: String? = null,
)

@Serializable
data class CameraRouteAliasReport(
    val discoveredCameraId: String,
    val openCameraId: String,
    val streamPhysicalCameraId: String? = null,
    val logicalParentCameraId: String? = null,
    val routeKind: String,
    val sources: List<String> = emptyList(),
)

/** Keeps advertised-vs-actual stream evidence distinct when a vendor capability bit is wrong. */
@Serializable
data class CameraMetadataEvidenceReport(
    val rawCapabilityAdvertised: String,
    val rawStreamActuallyDeclared: String,
    val rawFormats: List<String> = emptyList(),
    val rawSizes: List<Size2D> = emptyList(),
    val previewStreamActuallyDeclared: String,
    val privatePreviewSizes: List<Size2D> = emptyList(),
    val yuvPreviewSizes: List<Size2D> = emptyList(),
)

@Serializable
data class RouteTrustReport(
    val canonicalRouteId: String,
    val metadataTrust: String,
    val sessionTrust: String,
    val rawTrust: String,
    val failure: CameraRouteFailureReport? = null,
)

@Serializable
data class FailureReasonSummaryReport(
    val scope: String,
    val reason: String,
    val count: Int,
)

@Serializable
data class LogicalRelationshipReport(
    val logicalCameraId: String,
    val physicalCameraIds: List<String> = emptyList(),
)

@Serializable
data class ProbeReportEntry(
    val routingKey: String,
    val fingerprint: LensFingerprint? = null,
    val result: LensProbeResult,
)

@Serializable
data class AppliedQuirkReport(
    val quirkId: String,
    val cameraFingerprint: String? = null,
    val description: String? = null,
)

@Serializable
data class AppReport(
    val applicationId: String = "com.sahidcode404.camex",
    val versionName: String? = null,
    val versionCode: Long? = null,
    val buildType: String? = null,
    val buildTimestampUtc: String? = null,
    val gitSha: String? = null,
    val nativeLoaded: Boolean? = null,
    val nativeVersion: String? = null,
    val nativeSelfTestPassed: Boolean? = null,
)
