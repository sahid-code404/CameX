package com.sahidcode404.camex.core.camera

/** Values are descriptive inputs for optional, narrowly scoped device rules. */
data class CameraRuntimeEnvironment(
    val manufacturer: String,
    val model: String,
    val sdkInt: Int,
    val buildFingerprint: String,
)

data class CameraRouteFacts(
    val cameraFingerprint: String?,
    val opensThroughLogicalParent: Boolean,
    val reportsRaw: Boolean,
    val reportsBackwardCompatible: Boolean,
)

/**
 * Conservative operation limits. A rule may only override the fields it has evidence for.
 */
data class CameraOperationPolicy(
    val openTimeoutMillis: Long = 4_000L,
    val sessionTimeoutMillis: Long = 4_000L,
    val firstFrameTimeoutMillis: Long = 3_000L,
    val closeSettleTimeoutMillis: Long = 1_000L,
    // A TextureView preview does not benefit from multi-megapixel sensor streams. Keeping the
    // generic live stream in the 1080p class reduces bandwidth/ISP pressure and avoids low-fps
    // preview choices while RAW still uses the full selected RAW_SENSOR resolution independently.
    val maximumPreviewArea: Long = 2_073_600L,
    val maximumPreviewLongEdge: Int = 1_920,
    val allowPhysicalOutputRouting: Boolean = true,
    val allowRawSessionProbe: Boolean = true,
    val maxAutomaticFailures: Int = 2,
) {
    init {
        require(openTimeoutMillis > 0)
        require(sessionTimeoutMillis > 0)
        require(firstFrameTimeoutMillis > 0)
        require(closeSettleTimeoutMillis > 0)
        require(maximumPreviewArea > 0)
        require(maximumPreviewLongEdge > 0)
        require(maxAutomaticFailures > 0)
    }
}

data class CameraPolicyOverride(
    val openTimeoutMillis: Long? = null,
    val sessionTimeoutMillis: Long? = null,
    val firstFrameTimeoutMillis: Long? = null,
    val closeSettleTimeoutMillis: Long? = null,
    val maximumPreviewArea: Long? = null,
    val maximumPreviewLongEdge: Int? = null,
    val allowPhysicalOutputRouting: Boolean? = null,
    val allowRawSessionProbe: Boolean? = null,
    val maxAutomaticFailures: Int? = null,
)

interface CameraQuirkRule {
    /** Stable diagnostic name; it must not contain camera metadata or user information. */
    val id: String

    fun matches(environment: CameraRuntimeEnvironment, camera: CameraRouteFacts): Boolean

    fun overridePolicy(
        environment: CameraRuntimeEnvironment,
        camera: CameraRouteFacts,
    ): CameraPolicyOverride
}

data class ResolvedCameraPolicy(
    val policy: CameraOperationPolicy,
    val appliedRuleIds: List<String>,
)

/**
 * Resolves the capability-driven generic policy first, then explicit exception rules in order.
 *
 * CameX intentionally ships no manufacturer/camera-ID blacklist here. Evidence-backed rules can be
 * injected later without changing the generic camera path or making quirks the source of truth.
 */
class CameraQuirkRegistry(
    private val genericPolicy: CameraOperationPolicy = CameraOperationPolicy(),
    rules: List<CameraQuirkRule> = emptyList(),
) {
    private val rules = rules.toList()

    fun resolve(
        environment: CameraRuntimeEnvironment,
        camera: CameraRouteFacts,
    ): ResolvedCameraPolicy {
        var result = genericPolicy
        val applied = mutableListOf<String>()
        rules.forEach { rule ->
            if (!runCatching { rule.matches(environment, camera) }.getOrDefault(false)) return@forEach
            val override = runCatching { rule.overridePolicy(environment, camera) }.getOrNull()
                ?: return@forEach
            result = result.with(override)
            applied += rule.id
        }
        return ResolvedCameraPolicy(result, applied.distinct())
    }

    private fun CameraOperationPolicy.with(override: CameraPolicyOverride) = copy(
        openTimeoutMillis = override.openTimeoutMillis?.takeIf { it > 0 } ?: openTimeoutMillis,
        sessionTimeoutMillis = override.sessionTimeoutMillis?.takeIf { it > 0 }
            ?: sessionTimeoutMillis,
        firstFrameTimeoutMillis = override.firstFrameTimeoutMillis?.takeIf { it > 0 }
            ?: firstFrameTimeoutMillis,
        closeSettleTimeoutMillis = override.closeSettleTimeoutMillis?.takeIf { it > 0 }
            ?: closeSettleTimeoutMillis,
        maximumPreviewArea = override.maximumPreviewArea?.takeIf { it > 0 } ?: maximumPreviewArea,
        maximumPreviewLongEdge = override.maximumPreviewLongEdge?.takeIf { it > 0 }
            ?: maximumPreviewLongEdge,
        allowPhysicalOutputRouting = override.allowPhysicalOutputRouting
            ?: allowPhysicalOutputRouting,
        allowRawSessionProbe = override.allowRawSessionProbe ?: allowRawSessionProbe,
        maxAutomaticFailures = override.maxAutomaticFailures?.takeIf { it > 0 }
            ?: maxAutomaticFailures,
    )
}
