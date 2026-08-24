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

/**
 * Camera-photo-preview FPS selector.
 *
 * Auto is deliberately 30-fps-first rather than "highest FPS wins". That avoids the 60/90/120-fps
 * preview bias that can increase ISP bandwidth, force smaller streams, shorten exposure and make
 * vendor/AUX preview look jerky. Explicit user overrides are still honored when the active stream
 * can sustain them.
 */
object PreviewFpsSelector {
    private const val DEFAULT_PHOTO_PREVIEW_FPS = 30
    private const val FPS_TOLERANCE = 0.75

    fun preferredTargetFps(
        ranges: Collection<FpsRange>?,
        requested: FpsRange? = null,
    ): Double? {
        val valid = valid(ranges)
        requested?.let { wanted ->
            valid.firstOrNull { it == wanted }?.let { return it.max.toDouble() }
        }
        val selected = selectAutoRange(valid) ?: return null
        return if (selected.min > DEFAULT_PHOTO_PREVIEW_FPS) {
            selected.max.toDouble()
        } else {
            min(selected.max, DEFAULT_PHOTO_PREVIEW_FPS).toDouble()
        }
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

        val pool = if (estimatedMax == null) {
            valid
        } else {
            // Never force an AE range the selected stream cannot sustain. If the HAL metadata
            // proves none are compatible, leave AE unconstrained instead of manufacturing a range.
            valid.filter(::compatible).takeIf(List<FpsRange>::isNotEmpty) ?: return null
        }
        return selectAutoRange(pool)
    }

    private fun selectAutoRange(ranges: Collection<FpsRange>): FpsRange? = ranges.minWithOrNull(
        compareBy<FpsRange> { autoPriority(it) }
            .thenBy { autoDistance(it) }
            .thenByDescending { it.min }
            .thenBy { it.max },
    )

    private fun autoPriority(range: FpsRange): Int = when {
        range.min == DEFAULT_PHOTO_PREVIEW_FPS && range.max == DEFAULT_PHOTO_PREVIEW_FPS -> 0
        range.max == DEFAULT_PHOTO_PREVIEW_FPS -> 1
        range.min <= DEFAULT_PHOTO_PREVIEW_FPS && range.max >= DEFAULT_PHOTO_PREVIEW_FPS -> 2
        range.max < DEFAULT_PHOTO_PREVIEW_FPS -> 3
        else -> 4
    }

    private fun autoDistance(range: FpsRange): Int = when {
        range.max == DEFAULT_PHOTO_PREVIEW_FPS ->
            DEFAULT_PHOTO_PREVIEW_FPS - range.min
        range.min <= DEFAULT_PHOTO_PREVIEW_FPS && range.max >= DEFAULT_PHOTO_PREVIEW_FPS ->
            range.max - DEFAULT_PHOTO_PREVIEW_FPS
        range.max < DEFAULT_PHOTO_PREVIEW_FPS ->
            DEFAULT_PHOTO_PREVIEW_FPS - range.max
        else ->
            abs(range.min - DEFAULT_PHOTO_PREVIEW_FPS) +
                abs(range.max - DEFAULT_PHOTO_PREVIEW_FPS)
    }

    private fun valid(ranges: Collection<FpsRange>?): List<FpsRange> = ranges
        .orEmpty()
        .asSequence()
        .filter { it.isValid && it.max > 0 }
        .distinct()
        .toList()
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
