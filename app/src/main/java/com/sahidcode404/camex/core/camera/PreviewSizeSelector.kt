package com.sahidcode404.camex.core.camera

import com.sahidcode404.camex.core.model.FpsRange
import com.sahidcode404.camex.core.model.PreviewPreference
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** A framework-neutral stream size considered for a live preview. */
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
    /** Optional evidence-backed limits. Generic operation imposes no model/device resolution cap. */
    val maximumArea: Long = Long.MAX_VALUE,
    val maximumLongEdge: Int = Int.MAX_VALUE,
    /** Preferred live frame rate derived from this camera profile's metadata. */
    val preferredMinimumFps: Double? = null,
)

/**
 * Auto preview selector. Aspect match and sustainable frame rate win over raw pixel count; among
 * equivalent choices the smallest stream that covers the view is preferred to reduce ISP/GPU load.
 */
object PreviewSizeSelector {
    fun select(
        candidates: Collection<PreviewStreamCandidate>,
        request: PreviewSelectionRequest,
    ): PreviewStreamCandidate? {
        val unique = validUnique(candidates)
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

    internal fun validUnique(candidates: Collection<PreviewStreamCandidate>): List<PreviewStreamCandidate> =
        candidates.asSequence()
            .filter(PreviewStreamCandidate::isValid)
            .distinctBy { it.width to it.height }
            .toList()

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
        val resolutionPenalty = if (coversTarget) {
            abs(ln(areaRatio.coerceAtLeast(1e-9))) * 30.0
        } else {
            abs(ln(areaRatio.coerceAtLeast(1e-9))) * 60.0
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

/** Selects a live AE FPS range only from ranges reported by the active camera profile. */
object PreviewFpsSelector {
    fun preferredTargetFps(
        ranges: Collection<FpsRange>?,
        requested: FpsRange? = null,
    ): Double? {
        val valid = valid(ranges)
        requested?.let { wanted ->
            valid.firstOrNull { it == wanted }?.let { return it.max.toDouble() }
        }
        return valid.maxWithOrNull(autoComparator())?.max?.toDouble()
    }

    fun selectForStream(
        ranges: Collection<FpsRange>?,
        minimumFrameDurationNanos: Long?,
        requested: FpsRange? = null,
    ): FpsRange? {
        val valid = valid(ranges)
        if (valid.isEmpty()) return null
        val estimatedMax = minimumFrameDurationNanos
            ?.takeIf { it > 0L }
            ?.let { 1_000_000_000.0 / it.toDouble() }

        fun compatible(range: FpsRange): Boolean =
            estimatedMax == null || range.max.toDouble() <= estimatedMax + FPS_TOLERANCE

        requested?.let { wanted ->
            valid.firstOrNull { it == wanted && compatible(it) }?.let { return it }
        }

        val compatible = valid.filter(::compatible)
        val pool = compatible.ifEmpty {
            if (estimatedMax == null) {
                valid
            } else {
                valid.filter { it.min.toDouble() <= estimatedMax + FPS_TOLERANCE }.ifEmpty { valid }
            }
        }
        return pool.maxWithOrNull(autoComparator())
    }

    private fun autoComparator(): Comparator<FpsRange> =
        compareBy<FpsRange> { it.min }
            .thenBy { it.max }
            .thenByDescending { it.max - it.min }

    private fun valid(ranges: Collection<FpsRange>?): List<FpsRange> = ranges
        .orEmpty()
        .asSequence()
        .filter { it.isValid && it.max > 0 }
        .distinct()
        .toList()

    private const val FPS_TOLERANCE = 0.75
}

/**
 * Resolves user preview choices without making them structural requirements. A stale preference
 * caused by a ROM/firmware/profile change automatically falls back to the capability-driven path.
 */
object PreviewPreferenceResolver {
    fun selectStream(
        candidates: Collection<PreviewStreamCandidate>,
        request: PreviewSelectionRequest,
        preference: PreviewPreference,
    ): PreviewStreamCandidate? {
        val unique = PreviewSizeSelector.validUnique(candidates)
        preference.size?.takeIf { it.isValid }?.let { requested ->
            unique.firstOrNull {
                it.width == requested.width && it.height == requested.height
            }?.let { return it }
        }
        return PreviewSizeSelector.select(unique, request)
    }

    fun didFallbackSize(
        candidates: Collection<PreviewStreamCandidate>,
        preference: PreviewPreference,
    ): Boolean {
        val requested = preference.size ?: return false
        return PreviewSizeSelector.validUnique(candidates).none {
            it.width == requested.width && it.height == requested.height
        }
    }

    fun selectFps(
        ranges: Collection<FpsRange>?,
        minimumFrameDurationNanos: Long?,
        preference: PreviewPreference,
    ): FpsRange? = PreviewFpsSelector.selectForStream(
        ranges = ranges,
        minimumFrameDurationNanos = minimumFrameDurationNanos,
        requested = preference.fpsRange,
    )
}
