package com.sahidcode404.camex.core.camera.topology

import com.sahidcode404.camex.core.model.ColorFilterArrangement
import com.sahidcode404.camex.core.model.LensCapabilities
import com.sahidcode404.camex.core.model.LensFacing
import com.sahidcode404.camex.core.model.Size2D
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Confidence result for profile-to-optical-lens grouping. */
enum class OpticalLensMatch {
    STRONG_MATCH,
    PROBABLE_MATCH,
    INSUFFICIENT_EVIDENCE,
    CONFLICT,
}

/** Independent identity families. Correlated observations inside one family count only once. */
enum class OpticalEvidenceFamily {
    OPTICAL,
    SENSOR,
    GEOMETRY,
    TOPOLOGY,
}

data class OpticalLensComparison(
    val match: OpticalLensMatch,
    val score: Int,
    /** Number of independent evidence families, not raw metadata-field matches. */
    val evidenceCount: Int,
    val reasons: List<String>,
    val evidenceFamilies: Set<OpticalEvidenceFamily> = emptySet(),
    val positiveReasons: List<String> = reasons,
    val negativeReasons: List<String> = emptyList(),
)

/**
 * Conservative optical-identity matcher.
 *
 * Camera IDs, route kinds and discovery sources never become ordinary optical evidence. The one
 * exception is an authoritative logical/physical relationship: two profiles explicitly naming the
 * same physical member of the same logical camera are the same hardware member, while two distinct
 * physical members of that same logical camera are different hardware members.
 *
 * Evidence is scored by family. Pixel array, active array and RAW dimensions are deliberately one
 * GEOMETRY family, because vendor HALs frequently clone or derive these values together. A normal
 * cross-route STRONG_MATCH requires a strong optical anchor (focal length or FOV) plus at least one
 * independent corroborating family. Generic dimensions, facing and orientation can never prove
 * optical identity by themselves.
 */
object OpticalLensMatcher {
    // Camera2 reports focal length in millimetres. Keep automatic alias tolerance deliberately
    // narrow: 4.70 vs 4.72 is strong, while ~3% disagreement is ambiguous and >3.5% conflicts.
    private const val FOCAL_STRONG_RELATIVE_TOLERANCE = 0.0125
    private const val FOCAL_PROBABLE_RELATIVE_TOLERANCE = 0.02
    private const val FOCAL_CONFLICT_RELATIVE_DELTA = 0.035

    private const val PHYSICAL_STRONG_RELATIVE_TOLERANCE = 0.015
    private const val PHYSICAL_PROBABLE_RELATIVE_TOLERANCE = 0.03
    private const val PHYSICAL_CONFLICT_RELATIVE_DELTA = 0.06

    private const val FOV_STRONG_DEGREES = 2.0
    private const val FOV_PROBABLE_DEGREES = 4.0
    private const val FOV_CONFLICT_DEGREES = 8.0

    private const val APERTURE_STRONG_RELATIVE_TOLERANCE = 0.05
    private const val APERTURE_CONFLICT_RELATIVE_DELTA = 0.20

    fun signature(route: CameraRoute): OpticalLensSignature =
        signature(route.minimalMetadata, route.fullCapabilities?.capabilities)

    fun signature(profile: CameraProfile): OpticalLensSignature =
        signature(profile.metadata, profile.fullCapabilities?.capabilities)

    private fun signature(
        metadata: MinimalCameraMetadata,
        capabilities: LensCapabilities?,
    ): OpticalLensSignature {
        val focalValues = metadata.focalLengthsMm
            .filter { it.isFinite() && it > 0.0 }
            .distinct()
            .sorted()
        return OpticalLensSignature(
            facing = metadata.facing,
            // A logical/composite route may advertise multiple focal lengths. Picking the minimum
            // would incorrectly pretend the route itself is one physical lens, so only a single
            // unambiguous value becomes a focal identity anchor.
            focalLengthMm = focalValues.singleOrNull(),
            sensorPhysicalSize = metadata.sensorPhysicalSize,
            activeArraySize = metadata.activeArray?.let { rect ->
                Size2D(rect.right - rect.left, rect.bottom - rect.top).takeIf(Size2D::isValid)
            },
            pixelArraySize = metadata.pixelArraySize,
            rawSizes = metadata.rawSizes.filter(Size2D::isValid).distinct(),
            colorFilterArrangement = capabilities?.colorFilterArrangement,
            sensorOrientationDegrees = metadata.sensorOrientationDegrees
                ?: capabilities?.sensorOrientationDegrees,
            aperture = capabilities?.apertures.orEmpty()
                .filter { it.isFinite() && it > 0.0 }
                .minOrNull(),
            diagonalFieldOfViewDegrees = metadata.approximateFieldOfView?.diagonalDegrees
                ?.takeIf { it.isFinite() && it > 0.0 && it < 180.0 },
        )
    }

    /** Transport/profile identity is deliberately not consulted as ordinary optical evidence. */
    fun compare(left: CameraRoute, right: CameraRoute): OpticalLensComparison {
        val optical = compare(signature(left), signature(right))
        return applyAuthoritativeTopology(
            optical,
            left.streamPhysicalCameraId,
            left.logicalParentCameraId,
            right.streamPhysicalCameraId,
            right.logicalParentCameraId,
        )
    }

    /** Profile IDs are deliberately not consulted as ordinary optical evidence. */
    fun compare(left: CameraProfile, right: CameraProfile): OpticalLensComparison {
        val optical = compare(signature(left), signature(right))
        return applyAuthoritativeTopology(
            optical,
            left.streamPhysicalCameraId,
            left.logicalParentCameraId,
            right.streamPhysicalCameraId,
            right.logicalParentCameraId,
        )
    }

    fun compare(left: OpticalLensSignature, right: OpticalLensSignature): OpticalLensComparison {
        val positive = mutableListOf<String>()
        val negative = mutableListOf<String>()
        val familyScores = linkedMapOf<OpticalEvidenceFamily, Int>()
        var strongOpticalAnchor = false
        var probableOpticalAnchor = false
        var focalDisagreementBlocksAutomaticMerge = false

        if (left.facing != LensFacing.UNKNOWN && right.facing != LensFacing.UNKNOWN) {
            if (left.facing != right.facing) return conflict("different facing")
            positive += "facing agrees (context only)"
        }

        val focalRelation = compareRelative(
            left.focalLengthMm,
            right.focalLengthMm,
            FOCAL_STRONG_RELATIVE_TOLERANCE,
            FOCAL_PROBABLE_RELATIVE_TOLERANCE,
            FOCAL_CONFLICT_RELATIVE_DELTA,
        )
        when {
            focalRelation?.conflict == true -> return conflict("meaningfully different focal length")
            focalRelation?.strong == true -> {
                familyScores.raise(OpticalEvidenceFamily.OPTICAL, 50)
                strongOpticalAnchor = true
                positive += "focal length strongly agrees"
            }
            focalRelation != null -> {
                familyScores.raise(OpticalEvidenceFamily.OPTICAL, 30)
                probableOpticalAnchor = true
                positive += "focal length probably agrees"
            }
            validPair(left.focalLengthMm, right.focalLengthMm) -> {
                focalDisagreementBlocksAutomaticMerge = true
                negative += "focal lengths differ outside alias tolerance"
            }
        }

        val leftPhysical = left.sensorPhysicalSize
        val rightPhysical = right.sensorPhysicalSize
        if (leftPhysical?.isValid == true && rightPhysical?.isValid == true) {
            val width = relativeDelta(leftPhysical.widthMm, rightPhysical.widthMm)
            val height = relativeDelta(leftPhysical.heightMm, rightPhysical.heightMm)
            val delta = max(width, height)
            when {
                delta > PHYSICAL_CONFLICT_RELATIVE_DELTA ->
                    return conflict("meaningfully different physical sensor size")
                delta <= PHYSICAL_STRONG_RELATIVE_TOLERANCE -> {
                    familyScores.raise(OpticalEvidenceFamily.SENSOR, 25)
                    positive += "physical sensor size strongly agrees"
                }
                delta <= PHYSICAL_PROBABLE_RELATIVE_TOLERANCE -> {
                    familyScores.raise(OpticalEvidenceFamily.SENSOR, 14)
                    positive += "physical sensor size probably agrees"
                }
                else -> negative += "physical sensor size differs outside corroboration tolerance"
            }
        }

        val leftCfa = knownCfa(left.colorFilterArrangement)
        val rightCfa = knownCfa(right.colorFilterArrangement)
        if (leftCfa != null && rightCfa != null) {
            if (leftCfa != rightCfa) return conflict("different authoritative CFA")
            familyScores.raise(OpticalEvidenceFamily.SENSOR, 20)
            positive += "CFA agrees"
        }

        val geometryReasons = mutableListOf<String>()
        val geometryNegative = mutableListOf<String>()
        var geometryScore = 0

        geometryEvidence(left.pixelArraySize, right.pixelArraySize)?.let { relation ->
            if (relation.conflict) {
                geometryNegative += "pixel arrays differ"
            } else {
                geometryScore = max(geometryScore, if (relation.strong) 18 else 8)
                geometryReasons += if (relation.strong) {
                    "pixel array agrees"
                } else {
                    "pixel array is binning-compatible"
                }
            }
        }

        geometryEvidence(left.activeArraySize, right.activeArraySize)?.let { relation ->
            if (relation.conflict) {
                geometryNegative += "active arrays differ"
            } else {
                geometryScore = max(geometryScore, if (relation.strong) 18 else 8)
                geometryReasons += if (relation.strong) {
                    "active array agrees"
                } else {
                    "active array is crop/binning-compatible"
                }
            }
        }

        if (left.rawSizes.isNotEmpty() && right.rawSizes.isNotEmpty()) {
            val exact = left.rawSizes.any { it in right.rawSizes }
            val compatible = exact || left.rawSizes.any { a ->
                right.rawSizes.any { b -> binningCompatible(a, b) }
            }
            when {
                exact -> {
                    geometryScore = max(geometryScore, 18)
                    geometryReasons += "RAW dimensions agree"
                }
                compatible -> {
                    geometryScore = max(geometryScore, 8)
                    geometryReasons += "RAW dimensions are binning-compatible"
                }
                else -> geometryNegative += "RAW dimensions differ"
            }
        }
        if (geometryScore > 0) {
            familyScores.raise(OpticalEvidenceFamily.GEOMETRY, geometryScore)
            // Preserve field-level evidence for diagnostics, but count only one GEOMETRY family.
            positive += geometryReasons
        }
        negative += geometryNegative

        if (left.sensorOrientationDegrees != null && right.sensorOrientationDegrees != null) {
            if (left.sensorOrientationDegrees != right.sensorOrientationDegrees) {
                negative += "sensor orientation differs (context only)"
            } else {
                positive += "sensor orientation agrees (context only)"
            }
        }

        compareRelative(
            left.aperture,
            right.aperture,
            APERTURE_STRONG_RELATIVE_TOLERANCE,
            APERTURE_STRONG_RELATIVE_TOLERANCE * 2,
            APERTURE_CONFLICT_RELATIVE_DELTA,
        )?.let { relation ->
            if (relation.conflict) return conflict("meaningfully different aperture")
            familyScores.raise(OpticalEvidenceFamily.OPTICAL, if (relation.strong) 12 else 6)
            positive += if (relation.strong) "aperture strongly agrees" else "aperture probably agrees"
        }

        if (left.diagonalFieldOfViewDegrees != null && right.diagonalFieldOfViewDegrees != null) {
            val delta = abs(left.diagonalFieldOfViewDegrees - right.diagonalFieldOfViewDegrees)
            when {
                delta > FOV_CONFLICT_DEGREES -> return conflict("clearly different field of view")
                delta <= FOV_STRONG_DEGREES -> {
                    familyScores.raise(OpticalEvidenceFamily.OPTICAL, 42)
                    strongOpticalAnchor = true
                    positive += "field of view strongly agrees"
                }
                delta <= FOV_PROBABLE_DEGREES -> {
                    familyScores.raise(OpticalEvidenceFamily.OPTICAL, 24)
                    probableOpticalAnchor = true
                    positive += "field of view probably agrees"
                }
                else -> negative += "field of view differs outside alias tolerance"
            }
        }

        val corroboratingFamilies = familyScores.keys - OpticalEvidenceFamily.OPTICAL
        val score = familyScores.values.sum().coerceAtMost(100)
        val match = when {
            !focalDisagreementBlocksAutomaticMerge &&
                strongOpticalAnchor && corroboratingFamilies.isNotEmpty() -> OpticalLensMatch.STRONG_MATCH
            (strongOpticalAnchor || probableOpticalAnchor) && corroboratingFamilies.isNotEmpty() ->
                OpticalLensMatch.PROBABLE_MATCH
            strongOpticalAnchor || probableOpticalAnchor -> OpticalLensMatch.PROBABLE_MATCH
            else -> OpticalLensMatch.INSUFFICIENT_EVIDENCE
        }
        return OpticalLensComparison(
            match = match,
            score = score,
            evidenceCount = familyScores.size,
            reasons = positive + negative,
            evidenceFamilies = familyScores.keys.toSet(),
            positiveReasons = positive,
            negativeReasons = negative,
        )
    }

    fun shouldGroup(left: CameraRoute, right: CameraRoute): Boolean =
        compare(left, right).match == OpticalLensMatch.STRONG_MATCH

    private fun applyAuthoritativeTopology(
        base: OpticalLensComparison,
        leftPhysicalId: String?,
        leftParentId: String?,
        rightPhysicalId: String?,
        rightParentId: String?,
    ): OpticalLensComparison {
        val leftPhysical = leftPhysicalId?.trim()?.takeIf(String::isNotEmpty)
        val rightPhysical = rightPhysicalId?.trim()?.takeIf(String::isNotEmpty)
        val leftParent = leftParentId?.trim()?.takeIf(String::isNotEmpty)
        val rightParent = rightParentId?.trim()?.takeIf(String::isNotEmpty)
        if (leftPhysical == null || rightPhysical == null || leftParent == null || rightParent == null ||
            leftParent != rightParent
        ) return base

        if (leftPhysical != rightPhysical) {
            return conflict("different authoritative physical members of the same logical camera")
        }
        if (base.match == OpticalLensMatch.CONFLICT) return base

        val positives = base.positiveReasons + "authoritative topology names the same physical member"
        val families = base.evidenceFamilies + OpticalEvidenceFamily.TOPOLOGY
        return base.copy(
            match = OpticalLensMatch.STRONG_MATCH,
            score = (base.score + 50).coerceAtMost(100),
            evidenceCount = families.size,
            reasons = positives + base.negativeReasons,
            evidenceFamilies = families,
            positiveReasons = positives,
        )
    }

    private fun conflict(reason: String) = OpticalLensComparison(
        OpticalLensMatch.CONFLICT,
        score = Int.MIN_VALUE,
        evidenceCount = 0,
        reasons = listOf(reason),
        evidenceFamilies = emptySet(),
        positiveReasons = emptyList(),
        negativeReasons = listOf(reason),
    )

    private fun knownCfa(value: ColorFilterArrangement?): ColorFilterArrangement? =
        value?.takeUnless { it == ColorFilterArrangement.UNKNOWN }

    private fun compareRelative(
        left: Double?,
        right: Double?,
        strongTolerance: Double,
        probableTolerance: Double,
        conflictDelta: Double,
    ): RelativeRelation? {
        if (!validPair(left, right)) return null
        val delta = relativeDelta(requireNotNull(left), requireNotNull(right))
        return when {
            delta > conflictDelta -> RelativeRelation(strong = false, conflict = true)
            delta <= strongTolerance -> RelativeRelation(strong = true, conflict = false)
            delta <= probableTolerance -> RelativeRelation(strong = false, conflict = false)
            else -> null
        }
    }

    private fun validPair(left: Double?, right: Double?): Boolean =
        left != null && right != null && left.isFinite() && right.isFinite() && left > 0 && right > 0

    private fun geometryEvidence(left: Size2D?, right: Size2D?): RelativeRelation? {
        if (left?.isValid != true || right?.isValid != true) return null
        return when {
            left == right -> RelativeRelation(strong = true, conflict = false)
            binningCompatible(left, right) -> RelativeRelation(strong = false, conflict = false)
            else -> RelativeRelation(strong = false, conflict = true)
        }
    }

    private fun binningCompatible(left: Size2D, right: Size2D): Boolean {
        if (!left.isValid || !right.isValid) return false
        val largeWidth = max(left.width, right.width).toDouble()
        val smallWidth = min(left.width, right.width).toDouble()
        val largeHeight = max(left.height, right.height).toDouble()
        val smallHeight = min(left.height, right.height).toDouble()
        val widthRatio = largeWidth / smallWidth
        val heightRatio = largeHeight / smallHeight
        if (abs(widthRatio - heightRatio) > 0.03) return false
        return listOf(2.0, 3.0, 4.0).any { factor -> abs(widthRatio - factor) <= 0.04 }
    }

    private fun relativeDelta(left: Double, right: Double): Double =
        abs(left - right) / max(abs(left), abs(right)).coerceAtLeast(1e-9)

    private fun MutableMap<OpticalEvidenceFamily, Int>.raise(
        family: OpticalEvidenceFamily,
        score: Int,
    ) {
        this[family] = max(this[family] ?: 0, score)
    }

    private data class RelativeRelation(val strong: Boolean, val conflict: Boolean)
}
