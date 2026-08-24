package com.sahidcode404.camex.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class LensCategory {
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
    ;

    val isNormalSelectorCandidate: Boolean
        get() = when (this) {
            PHOTOGRAPHIC_ULTRAWIDE,
            PHOTOGRAPHIC_WIDE,
            PHOTOGRAPHIC_TELEPHOTO,
            PHOTOGRAPHIC_SUPER_TELEPHOTO,
            PHOTOGRAPHIC_MACRO,
            PHOTOGRAPHIC_MONO,
            PHOTOGRAPHIC_UNKNOWN,
            -> true

            NON_PHOTO_DEPTH,
            NON_PHOTO_TOF,
            NON_PHOTO_IR,
            SYSTEM_ONLY,
            INACCESSIBLE,
            BROKEN,
            -> false
        }
}

@Serializable
enum class FingerprintStrategy {
    STABLE_METADATA,
    DEVICE_SCOPED_FALLBACK,
}

@Serializable
data class LensFingerprint(
    val value: String,
    val strategy: FingerprintStrategy,
)

@Serializable
data class LensDescriptor(
    val identity: LensIdentity,
    val facing: LensFacing = LensFacing.UNKNOWN,
    val capabilities: LensCapabilities = LensCapabilities(),
    val fingerprint: LensFingerprint? = null,
    val usability: LensUsability = LensUsability.UNKNOWN,
    val category: LensCategory = LensCategory.PHOTOGRAPHIC_UNKNOWN,
    val probeResult: LensProbeResult? = null,
    /** Stable within a discovery pass and used only as a final UI tie-break, never as lens identity. */
    val discoveryOrder: Int = 0,
    /**
     * Session-only requested live stream. Discovery/topology descriptors leave this null; the
     * PreviewPreferenceRegistry projection sets it only after validating it against this profile.
     */
    val previewStreamFormat: StreamFormat? = null,
)
