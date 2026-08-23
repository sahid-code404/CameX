package com.sahidcode404.camex.core.camera

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * A framework-neutral stream size considered for a live preview.
 *
 * [minimumFrameDurationNanos] is optional because a surprising number of HALs omit it. A value of
 * zero is treated as unknown, not as an infinitely fast stream.
 */
data class PreviewStreamCandidate(
    val width: Int,
    val height: Int,
    val minimumFrameDurationNanos: Long? = null,
) {
    val area: Long get() = width.toLong() * height.toLong()
    val longEdge: Int get() = max(width, height)
    val shortEdge: Int get() = min(width, height)
    val aspectRatio: Double get() = longEdge.toDouble() / shortEdge.toDouble()
    val estimatedMaximumFps: Double?
        get() = minimumFrameDurationNanos
            ?.takeIf { it > 0L }
            ?.let { 1_000_000_000.0 / it.toDouble() }

    fun isValid(): Boolean = width > 0 && height > 0
}

data class PreviewSelectionRequest(
    /** View dimensions expressed in the camera buffer's orientation. */
    val targetWidth: Int,
    val targetHeight: Int,
    /** A live preview larger than this normally wastes bandwidth without improving the UI. */
    val maximumArea: Long = 4_194_304L,
    val maximumLongEdge: Int = 2_560,
    val preferredMinimumFps: Double = 24.0,
)

/**
 * Selects a preview stream without assuming a device, camera ID, or fixed output aspect ratio.
 *
 * The score deliberately makes aspect ratio and frame-rate viability more important than pixel
 * count. It then prefers the smallest stream that covers the view, avoiding sensor-resolution
 * preview streams on high-resolution cameras. If the HAL reports only slow or oversized choices,
 * those remain eligible as a fallback.
 */
object PreviewSizeSelector {
    fun select(
        candidates: Collection<PreviewStreamCandidate>,
        request: PreviewSelectionRequest,
    ): PreviewStreamCandidate? {
        val unique = candidates
            .asSequence()
            .filter(PreviewStreamCandidate::isValid)
            .distinctBy { it.width to it.height }
            .toList()
        if (unique.isEmpty()) return null

        val targetLong = max(request.targetWidth, request.targetHeight).coerceAtLeast(1)
        val targetShort = min(request.targetWidth, request.targetHeight).coerceAtLeast(1)
        val targetAspect = targetLong.toDouble() / targetShort.toDouble()
        val targetArea = targetLong.toLong() * targetShort.toLong()

        val withinBudget = unique.filter {
            it.area <= request.maximumArea && it.longEdge <= request.maximumLongEdge
        }
        val pool = withinBudget.ifEmpty { unique }
        val hasFastCandidate = pool.any {
            val fps = it.estimatedMaximumFps
            fps == null || fps >= request.preferredMinimumFps
        }

        return pool.minWithOrNull(
            compareBy<PreviewStreamCandidate> {
                score(
                    candidate = it,
                    targetAspect = targetAspect,
                    targetArea = targetArea,
                    targetLong = targetLong,
                    targetShort = targetShort,
                    preferredMinimumFps = request.preferredMinimumFps,
                    penalizeSlowStreams = hasFastCandidate,
                )
            }.thenBy { it.area }
                .thenBy { it.width }
                .thenBy { it.height },
        )
    }

    private fun score(
        candidate: PreviewStreamCandidate,
        targetAspect: Double,
        targetArea: Long,
        targetLong: Int,
        targetShort: Int,
        preferredMinimumFps: Double,
        penalizeSlowStreams: Boolean,
    ): Double {
        // Log-space treats 4:3 vs 16:9 in the same way regardless of which is the target.
        val aspectPenalty = abs(ln(candidate.aspectRatio / targetAspect)) * 10_000.0
        val coversTarget = candidate.longEdge >= targetLong && candidate.shortEdge >= targetShort
        val coveragePenalty = if (coversTarget) 0.0 else 800.0
        val areaRatio = candidate.area.toDouble() / targetArea.coerceAtLeast(1L).toDouble()
        val resolutionPenalty = when {
            coversTarget -> abs(ln(areaRatio.coerceAtLeast(1e-9))) * 30.0
            else -> abs(ln(areaRatio.coerceAtLeast(1e-9))) * 60.0
        }
        val fps = candidate.estimatedMaximumFps
        val frameRatePenalty = if (
            penalizeSlowStreams && fps != null && fps < preferredMinimumFps
        ) {
            2_000.0 + (preferredMinimumFps - fps) * 25.0
        } else {
            0.0
        }
        return aspectPenalty + coveragePenalty + resolutionPenalty + frameRatePenalty
    }
}
