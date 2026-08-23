package com.sahidcode404.camex.core.camera

import com.sahidcode404.camex.core.model.FpsRange
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * A framework-neutral stream size considered for a live preview.
 *
 * [minimumFrameDurationNanos] is optional because some HALs omit it. Zero is treated as unknown.
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
    /** Optional evidence-backed limits. Generic operation does not impose a device-size cap. */
    val maximumArea: Long = Long.MAX_VALUE,
    val maximumLongEdge: Int = Int.MAX_VALUE,
    /** Preferred live frame rate derived from camera metadata, not a fixed application value. */
    val preferredMinimumFps: Double? = null,
)

/**
 * Selects a preview stream without assuming a device, camera ID, fixed resolution, or fixed FPS.
 * Aspect ratio and frame-rate viability win over pixel count. The smallest good stream that covers
 * the view is preferred, while metadata-reported slow streams are avoided when a faster compatible
 * stream exists.
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
        val preferredFps = request.preferredMinimumFps?.takeIf { it.isFinite() && it > 0.0 }
        val hasFastCandidate = preferredFps != null && pool.any {
            val fps = it.estimatedMaximumFps
            fps == null || fps + FPS_TOLERANCE >= preferredFps
        }

        return pool.minWithOrNull(
            compareBy<PreviewStreamCandidate> {
                score(
                    candidate = it,
                    targetAspect = targetAspect,
                    targetArea = targetArea,
                    targetLong = targetLong,
                    targetShort = targetShort,
                    preferredMinimumFps = preferredFps,
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
        preferredMinimumFps: Double?,
        penalizeSlowStreams: Boolean,
    ): Double {
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
            penalizeSlowStreams &&
            preferredMinimumFps != null &&
            fps != null &&
            fps + FPS_TOLERANCE < preferredMinimumFps
        ) {
            2_000.0 + (preferredMinimumFps - fps) * 25.0
        } else {
            0.0
        }
        return aspectPenalty + coveragePenalty + resolutionPenalty + frameRatePenalty
    }

    private const val FPS_TOLERANCE = 0.75
}

/**
 * Chooses a live AE FPS range only from ranges reported by the active camera profile.
 *
 * Auto mode prefers the range with the highest lower bound, then highest upper bound. This keeps
 * the preview smooth instead of allowing AE to fall to a much lower frame rate when the camera
 * explicitly advertises a faster normal-preview range. If the selected stream reports a minimum
 * frame duration, ranges that exceed that stream are removed first.
 */
object PreviewFpsSelector {
    fun preferredTargetFps(ranges: Collection<FpsRange>?): Double? = valid(ranges)
        .maxWithOrNull(
            compareBy<FpsRange> { it.min }
                .thenBy { it.max }
                .thenByDescending { it.max - it.min },
        )
        ?.max
        ?.toDouble()

    fun selectForStream(
        ranges: Collection<FpsRange>?,
        minimumFrameDurationNanos: Long?,
    ): FpsRange? {
        val valid = valid(ranges)
        if (valid.isEmpty()) return null
        val estimatedMax = minimumFrameDurationNanos
            ?.takeIf { it > 0L }
            ?.let { 1_000_000_000.0 / it.toDouble() }
        val compatible = if (estimatedMax == null) {
            valid
        } else {
            valid.filter { it.max.toDouble() <= estimatedMax + FPS_TOLERANCE }
        }
        val pool = compatible.ifEmpty {
            if (estimatedMax == null) valid else valid.filter {
                it.min.toDouble() <= estimatedMax + FPS_TOLERANCE
            }.ifEmpty { valid }
        }
        return pool.maxWithOrNull(
            compareBy<FpsRange> { it.min }
                .thenBy { it.max }
                .thenByDescending { it.max - it.min },
        )
    }

    private fun valid(ranges: Collection<FpsRange>?): List<FpsRange> = ranges
        .orEmpty()
        .asSequence()
        .filter { it.isValid && it.max > 0 }
        .distinct()
        .toList()

    private const val FPS_TOLERANCE = 0.75
}
