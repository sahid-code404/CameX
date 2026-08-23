package com.sahidcode404.camex.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class LensNodeKind {
    STANDALONE,
    LOGICAL,
    PHYSICAL,
    UNKNOWN,
}

@Serializable
enum class LensFacing {
    FRONT,
    BACK,
    EXTERNAL,
    UNKNOWN,
}

/**
 * Camera2 routing identity, deliberately separate from persistent lens identity.
 *
 * [publicCameraId] is an application-visible ID that CameraManager can open. For a physical-only
 * lens it is normally the logical parent. [physicalCameraId], when present, is the stream target
 * inside that logical device. Camera IDs are diagnostics/routing data only; preferences use a
 * [LensFingerprint].
 */
@Serializable
data class LensIdentity(
    val publicCameraId: String,
    val physicalCameraId: String? = null,
    val logicalParentCameraId: String? = null,
    val nodeKind: LensNodeKind = if (physicalCameraId == null) LensNodeKind.STANDALONE else LensNodeKind.PHYSICAL,
) {
    /** Camera ID to open. A known logical parent always wins for a physical stream. */
    val openCameraId: String
        get() = logicalParentCameraId?.takeIf(String::isNotBlank) ?: publicCameraId

    /** Optional physical output target passed when creating a routed stream. */
    val streamPhysicalCameraId: String?
        get() = physicalCameraId?.takeIf(String::isNotBlank)

    /** Session-local routing key. It must never be used as a persistent preference key. */
    val routingKey: String
        get() = buildString {
            append(openCameraId.length)
            append(':')
            append(openCameraId)
            append('|')
            append(streamPhysicalCameraId?.length ?: 0)
            append(':')
            append(streamPhysicalCameraId.orEmpty())
        }
}
