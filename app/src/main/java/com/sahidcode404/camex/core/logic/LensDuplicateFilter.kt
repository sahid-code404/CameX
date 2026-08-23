package com.sahidcode404.camex.core.logic

import com.sahidcode404.camex.core.model.CapabilitySupport
import com.sahidcode404.camex.core.model.FingerprintStrategy
import com.sahidcode404.camex.core.model.LensCategory
import com.sahidcode404.camex.core.model.LensDescriptor
import com.sahidcode404.camex.core.model.LensFacing
import com.sahidcode404.camex.core.model.LensUsability
import kotlin.math.abs
import kotlin.math.max

data class DuplicateLensGroup(
    val representative: LensDescriptor,
    val members: List<LensDescriptor>,
)

/** Keeps all nodes available to diagnostics while selecting one representation for the lens UI. */
object LensDuplicateFilter {
    fun filterForSelector(lenses: List<LensDescriptor>): List<LensDescriptor> =
        group(
            lenses.filter {
                it.usability.isSelectable && it.category != LensCategory.AUXILIARY
            },
        ).map { it.representative }

    fun group(lenses: List<LensDescriptor>): List<DuplicateLensGroup> {
        if (lenses.isEmpty()) return emptyList()
        val parents = IntArray(lenses.size) { it }

        fun root(index: Int): Int {
            var value = index
            while (parents[value] != value) {
                parents[value] = parents[parents[value]]
                value = parents[value]
            }
            return value
        }

        fun union(left: Int, right: Int) {
            val leftRoot = root(left)
            val rightRoot = root(right)
            if (leftRoot != rightRoot) parents[rightRoot] = leftRoot
        }

        for (left in lenses.indices) {
            for (right in left + 1 until lenses.size) {
                if (areDuplicates(lenses[left], lenses[right])) union(left, right)
            }
        }

        return lenses.indices.groupBy(::root).values.map { indices ->
            val members = indices.map(lenses::get)
            DuplicateLensGroup(
                representative = members.maxWithOrNull(representativeComparator)!!,
                members = members.sortedBy { it.discoveryOrder },
            )
        }.sortedBy { it.representative.discoveryOrder }
    }

    fun areDuplicates(left: LensDescriptor, right: LensDescriptor): Boolean {
        if (left === right) return true
        if (left.facing != LensFacing.UNKNOWN && right.facing != LensFacing.UNKNOWN &&
            left.facing != right.facing
        ) return false

        val leftFingerprint = left.fingerprint
        val rightFingerprint = right.fingerprint
        if (leftFingerprint != null && rightFingerprint != null &&
            leftFingerprint.strategy == FingerprintStrategy.STABLE_METADATA &&
            rightFingerprint.strategy == FingerprintStrategy.STABLE_METADATA &&
            leftFingerprint.value == rightFingerprint.value
        ) return true

        val leftPhysical = left.identity.physicalCameraId?.takeIf(String::isNotBlank)
        val rightPhysical = right.identity.physicalCameraId?.takeIf(String::isNotBlank)
        if (leftPhysical != null && leftPhysical == rightPhysical) return true

        var evidence = 0
        var coreOpticalEvidence = false
        representativeFocal(left)?.let { leftFocal ->
            representativeFocal(right)?.let { rightFocal ->
                if (!nearlyEqual(leftFocal, rightFocal, 0.015)) return false
                evidence++
                coreOpticalEvidence = true
            }
        }
        val leftSensor = left.capabilities.sensorPhysicalSize?.takeIf { it.isValid }
        val rightSensor = right.capabilities.sensorPhysicalSize?.takeIf { it.isValid }
        if (leftSensor != null && rightSensor != null) {
            if (!nearlyEqual(leftSensor.widthMm, rightSensor.widthMm, 0.015) ||
                !nearlyEqual(leftSensor.heightMm, rightSensor.heightMm, 0.015)
            ) return false
            evidence++
        }
        val leftPixels = left.capabilities.pixelArraySize?.takeIf { it.isValid }
        val rightPixels = right.capabilities.pixelArraySize?.takeIf { it.isValid }
        if (leftPixels != null && rightPixels != null) {
            if (leftPixels != rightPixels) return false
            evidence++
        }
        val leftActive = left.capabilities.activeArray?.takeIf { it.isValid }
        val rightActive = right.capabilities.activeArray?.takeIf { it.isValid }
        if (leftActive != null && rightActive != null) {
            if (leftActive.size != rightActive.size) return false
            evidence++
        }
        val leftFov = LensMath.fieldOfView(left.capabilities)?.diagonalDegrees
        val rightFov = LensMath.fieldOfView(right.capabilities)?.diagonalDegrees
        if (leftFov != null && rightFov != null) {
            if (abs(leftFov - rightFov) > 1.5) return false
            evidence++
            coreOpticalEvidence = true
        }
        val leftOrientation = left.capabilities.sensorOrientationDegrees
        val rightOrientation = right.capabilities.sensorOrientationDegrees
        if (leftOrientation != null && rightOrientation != null) {
            if (Math.floorMod(leftOrientation, 360) != Math.floorMod(rightOrientation, 360)) return false
            evidence++
        }
        val leftFormats = left.capabilities.streamConfigurations.orEmpty().map { it.format }.toSet()
        val rightFormats = right.capabilities.streamConfigurations.orEmpty().map { it.format }.toSet()
        if (leftFormats.isNotEmpty() && rightFormats.isNotEmpty()) {
            val overlap = leftFormats.intersect(rightFormats).size.toDouble() /
                max(leftFormats.size, rightFormats.size)
            if (overlap >= 0.75) evidence++
        }

        // Multiple independent matches are required; sparse nodes stay visible rather than being
        // incorrectly collapsed.
        return coreOpticalEvidence && evidence >= 3
    }

    private val representativeComparator = compareBy<LensDescriptor> { usabilityRank(it.usability) }
        .thenBy { if (it.probeResult?.usable == true) 1 else 0 }
        .thenBy { if (it.capabilities.flags.raw == CapabilitySupport.SUPPORTED) 1 else 0 }
        .thenBy { metadataCount(it) }
        .thenBy { it.capabilities.pixelArraySize?.area ?: -1L }
        .thenBy { it.fingerprint?.value.orEmpty() }
        .thenBy { -it.discoveryOrder }

    private fun usabilityRank(value: LensUsability): Int = when (value) {
        LensUsability.RAW_NATIVE -> 7
        LensUsability.RAW_PHYSICAL_STREAM -> 6
        LensUsability.RAW_MAX_RESOLUTION -> 5
        LensUsability.PROCESSED_ONLY -> 4
        LensUsability.PREVIEW_ONLY -> 3
        LensUsability.UNKNOWN -> 2
        else -> 0
    }

    private fun metadataCount(lens: LensDescriptor): Int = with(lens.capabilities) {
        listOf(
            focalLengthsMm,
            sensorPhysicalSize,
            pixelArraySize,
            activeArray,
            sensorOrientationDegrees,
            apertures,
            streamConfigurations,
        ).count { it != null }
    }

    private fun representativeFocal(lens: LensDescriptor): Double? = lens.capabilities.focalLengthsMm
        .orEmpty()
        .filter { it.isFinite() && it > 0.0 }
        .minOrNull()

    private fun nearlyEqual(left: Double, right: Double, tolerance: Double): Boolean =
        abs(left - right) <= max(abs(left), abs(right)) * tolerance
}
