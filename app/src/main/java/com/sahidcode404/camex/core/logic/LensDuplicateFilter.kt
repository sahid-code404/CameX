package com.sahidcode404.camex.core.logic

import com.sahidcode404.camex.core.model.CapabilitySupport
import com.sahidcode404.camex.core.model.FingerprintStrategy
import com.sahidcode404.camex.core.model.LensCategory
import com.sahidcode404.camex.core.model.LensDescriptor
import com.sahidcode404.camex.core.model.LensFacing
import com.sahidcode404.camex.core.model.LensUsability

data class DuplicateLensGroup(
    val representative: LensDescriptor,
    val members: List<LensDescriptor>,
)

/** Keeps all nodes available to diagnostics while selecting one representation for the lens UI. */
object LensDuplicateFilter {
    fun filterForSelector(lenses: List<LensDescriptor>): List<LensDescriptor> =
        group(
            lenses.filter {
                it.usability.isSelectable && it.category.isNormalSelectorCandidate
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
        if (left.identity.routingKey == right.identity.routingKey) return true

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
        // Canonical topology resolution owns cross-route aliasing. Similar optics alone can still
        // describe two real sensors, so this UI safety net never performs a second heuristic merge.
        return false
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
        LensUsability.PHOTOGRAPHIC_CANDIDATE -> 2
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

}
