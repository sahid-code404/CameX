package com.sahidcode404.camex.core.model

import kotlinx.serialization.Serializable

/**
 * Sanitized, engineering-only report. These DTOs intentionally have no account, location,
 * hardware serial, media, token, or arbitrary extras fields.
 */
@Serializable
data class CompatibilityReport(
    val schemaVersion: Int = 1,
    val generatedAtUtc: String,
    val device: DeviceReport = DeviceReport(),
    val android: AndroidReport = AndroidReport(),
    val graphics: GraphicsReport = GraphicsReport(),
    val cameras: List<CameraCompatibilityEntry> = emptyList(),
    val discoveryFailures: List<DiscoveryFailureReport> = emptyList(),
    val logicalRelationships: List<LogicalRelationshipReport> = emptyList(),
    val probeResults: List<ProbeReportEntry> = emptyList(),
    val quirks: List<AppliedQuirkReport> = emptyList(),
    val app: AppReport = AppReport(),
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

@Serializable
data class CameraCompatibilityEntry(
    val identity: LensIdentity,
    val fingerprint: LensFingerprint? = null,
    val facing: LensFacing = LensFacing.UNKNOWN,
    val usability: LensUsability = LensUsability.UNKNOWN,
    val category: LensCategory = LensCategory.UNKNOWN,
    val fieldOfView: FieldOfView? = null,
    val capabilities: LensCapabilities = LensCapabilities(),
    val maximumRawSize: Size2D? = null,
    val estimatedMaximumRawFps: Double? = null,
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
