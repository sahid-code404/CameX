package com.sahidcode404.camex.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class ProbeStage {
    DISCOVERED,
    METADATA_VALID,
    SESSION_CONFIGURATION_SUPPORTED,
    OPEN_SUCCESS,
    PREVIEW_SUCCESS,
    RAW_CONFIGURATION_VALID,
    RAW_TEST_SUCCESS,
    USABLE,
}

@Serializable
enum class ProbeOutcome {
    SUCCESS,
    FAILURE,
    UNSUPPORTED,
    TIMEOUT,
    EXCEPTION,
    SKIPPED,
}

@Serializable
enum class ProbeFailureKind {
    PERMISSION_DENIED,
    SYSTEM_RESTRICTED,
    ACCESS_DENIED,
    DISCONNECTED,
    SERVICE_ERROR,
    INVALID_METADATA,
    SESSION_CONFIGURATION,
    DEVICE_ERROR,
    TIMEOUT,
    UNKNOWN,
}

@Serializable
data class ProbeStageResult(
    val stage: ProbeStage,
    val outcome: ProbeOutcome,
    val durationMs: Long? = null,
    /** Sanitized, bounded explanation; never place a stack trace or user data here. */
    val detail: String? = null,
    val failureKind: ProbeFailureKind? = null,
)

@Serializable
data class LensProbeResult(
    val stages: List<ProbeStageResult> = emptyList(),
    val attempt: Int = 1,
) {
    val lastResult: ProbeStageResult? get() = stages.lastOrNull()
    val previewVerified: Boolean
        get() = stages.any { it.stage == ProbeStage.PREVIEW_SUCCESS && it.outcome == ProbeOutcome.SUCCESS }
    val rawConfigurationVerified: Boolean
        get() = stages.any { it.stage == ProbeStage.RAW_CONFIGURATION_VALID && it.outcome == ProbeOutcome.SUCCESS }
    val rawFrameTested: Boolean
        get() = stages.any { it.stage == ProbeStage.RAW_TEST_SUCCESS && it.outcome == ProbeOutcome.SUCCESS }
    val usable: Boolean
        get() = stages.any { it.stage == ProbeStage.USABLE && it.outcome == ProbeOutcome.SUCCESS }
}

@Serializable
enum class LensUsability {
    RAW_NATIVE,
    RAW_PHYSICAL_STREAM,
    RAW_MAX_RESOLUTION,
    PROCESSED_ONLY,
    PREVIEW_ONLY,
    /** Credible photographic metadata; session support is deliberately still unverified. */
    PHOTOGRAPHIC_CANDIDATE,
    DEPTH_AUXILIARY,
    SYSTEM_ONLY,
    INACCESSIBLE,
    BROKEN,
    UNKNOWN,
    DISABLED_BY_USER,
    ;

    val isPhotographic: Boolean
        get() = this == RAW_NATIVE || this == RAW_PHYSICAL_STREAM ||
            this == RAW_MAX_RESOLUTION || this == PROCESSED_ONLY || this == PREVIEW_ONLY ||
            this == PHOTOGRAPHIC_CANDIDATE

    val isSelectable: Boolean get() = isPhotographic && this != DISABLED_BY_USER
}
