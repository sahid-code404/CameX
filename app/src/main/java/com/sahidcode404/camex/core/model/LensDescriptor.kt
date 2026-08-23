package com.sahidcode404.camex.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class LensCategory {
    ULTRAWIDE,
    WIDE,
    TELEPHOTO,
    SUPER_TELEPHOTO,
    FRONT,
    EXTERNAL,
    AUXILIARY,
    UNKNOWN,
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
    val category: LensCategory = LensCategory.UNKNOWN,
    val probeResult: LensProbeResult? = null,
    /** Stable within a discovery pass and used only as a final UI tie-break, never as lens identity. */
    val discoveryOrder: Int = 0,
)
