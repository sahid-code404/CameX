package com.sahidcode404.camex.core.camera

data class LogicalCameraRelationship(
    val logicalCameraId: String,
    val physicalCameraIds: List<String>,
)

enum class DiscoveryFailureKind {
    CAMERA_SERVICE,
    ACCESS_DENIED,
    INVALID_METADATA,
    PHYSICAL_METADATA_UNAVAILABLE,
    UNKNOWN,
}

data class CameraDiscoveryFailure(
    val publicCameraId: String? = null,
    val physicalCameraId: String? = null,
    val kind: DiscoveryFailureKind,
    /** Bounded and sanitized: exception messages and stack traces are intentionally excluded. */
    val detail: String,
)
