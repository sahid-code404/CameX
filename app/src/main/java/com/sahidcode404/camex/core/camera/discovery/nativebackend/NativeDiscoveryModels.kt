package com.sahidcode404.camex.core.camera.discovery.nativebackend

/** Independent evidence source. Reconciliation may attach both values to one canonical route. */
enum class NativeDiscoverySource {
    NDK_ADVERTISED,
    NDK_DEEP,
}

enum class NativeLensFacing {
    FRONT,
    BACK,
    EXTERNAL,
    UNKNOWN,
}

enum class NativeHardwareLevel {
    LEGACY,
    LIMITED,
    FULL,
    LEVEL_3,
    EXTERNAL,
    UNKNOWN,
}

enum class NativeCameraCapability {
    BACKWARD_COMPATIBLE,
    RAW,
    LOGICAL_MULTI_CAMERA,
    DEPTH_OUTPUT,
    MONOCHROME,
    SYSTEM_CAMERA,
}

enum class NativeColorFilterArrangement {
    RGGB,
    GRBG,
    GBRG,
    BGGR,
    RGB,
    MONO,
    NIR,
    UNKNOWN,
}

/** RAW_PRIVATE is retained as evidence but is intentionally distinct from portable RAW. */
enum class NativeRawStreamFormat {
    RAW_SENSOR,
    RAW10,
    RAW12,
    RAW14,
    RAW_PRIVATE,
}

data class NativeSize(
    val width: Int,
    val height: Int,
) {
    val isValid: Boolean
        get() = width > 0 && height > 0
}

data class NativeSensorRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val isValid: Boolean
        get() = right > left && bottom > top
}

/**
 * Small, CameX-owned projection of ACameraMetadata. It intentionally excludes frame durations,
 * high-speed tables, and session state so discovery stays cheap and read-only.
 */
data class NativeMinimalCameraMetadata(
    val cameraId: String,
    val sources: Set<NativeDiscoverySource>,
    val facing: NativeLensFacing = NativeLensFacing.UNKNOWN,
    val focalLengthsMm: List<Double> = emptyList(),
    val sensorPhysicalSizeMm: NativeSizeF? = null,
    val activeArray: NativeSensorRect? = null,
    val pixelArraySize: NativeSize? = null,
    val sensorOrientationDegrees: Int? = null,
    val hardwareLevel: NativeHardwareLevel = NativeHardwareLevel.UNKNOWN,
    /** Null means the capabilities entry itself was absent or malformed. */
    val rawCapabilityAdvertised: Boolean? = null,
    val rawStreamActuallyDeclared: Boolean = false,
    val rawFormats: Set<NativeRawStreamFormat> = emptySet(),
    val rawSizes: List<NativeSize> = emptyList(),
    val privatePreviewStreamDeclared: Boolean = false,
    val privatePreviewSizes: List<NativeSize> = emptyList(),
    val yuvPreviewStreamDeclared: Boolean = false,
    val yuvPreviewSizes: List<NativeSize> = emptyList(),
    val reportedCapabilities: Set<NativeCameraCapability>? = null,
    val colorFilterArrangement: NativeColorFilterArrangement? = null,
    val physicalCameraIds: List<String> = emptyList(),
) {
    val previewStreamActuallyDeclared: Boolean
        get() = privatePreviewStreamDeclared || yuvPreviewStreamDeclared

    val hasOpticalEvidence: Boolean
        get() = focalLengthsMm.isNotEmpty() || sensorPhysicalSizeMm?.isValid == true ||
            activeArray?.isValid == true
}

data class NativeSizeF(
    val width: Double,
    val height: Double,
) {
    val isValid: Boolean
        get() = width.isFinite() && height.isFinite() && width > 0.0 && height > 0.0
}

enum class NativeDiscoveryFailureStage {
    LOAD_API,
    CREATE_MANAGER,
    LIST_IDS,
    READ_CHARACTERISTICS,
    INPUT_VALIDATION,
    DECODE_PAYLOAD,
    JNI_CALL,
}

/** Stable, sanitized categories; native exception text and stack traces never cross this API. */
enum class NativeDiscoveryFailureReason {
    NATIVE_API_UNAVAILABLE,
    MANAGER_CREATION_FAILED,
    ID_ENUMERATION_FAILED,
    INVALID_CAMERA_ID,
    ACCESS_DENIED,
    CAMERA_DISCONNECTED,
    NOT_ENOUGH_MEMORY,
    METADATA_UNAVAILABLE,
    CAMERA_DEVICE_ERROR,
    CAMERA_SERVICE_ERROR,
    INVALID_OPERATION,
    CAMERA_IN_USE,
    MAX_CAMERAS_IN_USE,
    CAMERA_DISABLED,
    UNSUPPORTED_OPERATION,
    ADVERTISED_LIST_TRUNCATED,
    CANDIDATE_LIMIT_REACHED,
    MALFORMED_VENDOR_METADATA,
    MALFORMED_NATIVE_RESPONSE,
    JNI_INVOCATION_FAILED,
    UNKNOWN_NATIVE_STATUS,
}

data class NativeDiscoveryFailure(
    val cameraId: String? = null,
    val stage: NativeDiscoveryFailureStage,
    val reason: NativeDiscoveryFailureReason,
    val statusCode: Int? = null,
)

data class NativeDiscoveryCounts(
    val advertisedIdCount: Int = 0,
    val requestedCandidateCount: Int = 0,
    val attemptedMetadataCount: Int = 0,
    val metadataValidCount: Int = 0,
    val failureCount: Int = 0,
    val skippedCandidateCount: Int = 0,
)

data class NativeDiscoveryResult(
    val source: NativeDiscoverySource,
    val advertisedCameraIds: List<String> = emptyList(),
    val requestedCameraIds: List<String> = emptyList(),
    val attemptedCameraIds: List<String> = emptyList(),
    val cameras: List<NativeMinimalCameraMetadata> = emptyList(),
    val failures: List<NativeDiscoveryFailure> = emptyList(),
    val counts: NativeDiscoveryCounts = NativeDiscoveryCounts(),
    /** Native steady-clock time for only this scan; zero is a valid sub-millisecond result. */
    val durationMs: Long = 0L,
)

data class DeepAuxDiscoveryLimits(
    val lowNumericNamespaceMax: Int = DEFAULT_LOW_NUMERIC_NAMESPACE_MAX,
    val neighborRadius: Int = DEFAULT_NEIGHBOR_RADIUS,
    val maximumNumericId: Int = DEFAULT_MAXIMUM_NUMERIC_ID,
    val maximumCandidateCount: Int = DEFAULT_MAXIMUM_CANDIDATE_COUNT,
) {
    companion object {
        const val DEFAULT_LOW_NUMERIC_NAMESPACE_MAX = 31
        const val DEFAULT_NEIGHBOR_RADIUS = 4
        const val DEFAULT_MAXIMUM_NUMERIC_ID = 1024
        const val DEFAULT_MAXIMUM_CANDIDATE_COUNT = 96
    }
}

data class DeepAuxDiscoveryRequest(
    val advertisedCameraIds: Collection<String> = emptyList(),
    val cachedSuccessfulCameraIds: Collection<String> = emptyList(),
    val previouslySuccessfulDeepCameraIds: Collection<String> = emptyList(),
    val limits: DeepAuxDiscoveryLimits = DeepAuxDiscoveryLimits(),
)
