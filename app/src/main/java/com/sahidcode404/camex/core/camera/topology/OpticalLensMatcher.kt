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

data class OpticalLensComparison(
    val match: OpticalLensMatch,
    val score: Int,
    val evidenceCount: Int,
    val reasons: List<String>,
)

/**
 * Pure metadata matcher used only for optical identity. Camera IDs, route kinds and discovery
 * sources are intentionally absent from the decision: they describe transport/profile endpoints,
 * not physical glass. Strong sensor/optical evidence may therefore group two completely different
 * vendor route IDs into one CanonicalLens.
 *
 * Vendor aliases can expose cropped/binned variants, so exact geometry is strong evidence while
 * small numeric tolerances and common integer binning relationships remain conservative. Any
 * authoritative conflict (facing, focal length, sensor geometry, CFA, orientation or FOV) prevents
 * grouping regardless of how similar the transport routes look.
 */
object OpticalLensMatcher {
    private const val FOCAL_STRONG_RELATIVE_TOLERANCE = 0.015
    private const val FOCAL_PROBABLE_RELATIVE_TOLERANCE = 0.03
    private const val FOCAL_CONFLICT_RELATIVE_DELTA = 0.07
    private const val PHYSICAL_STRONG_RELATIVE_TOLERANCE = 0.02
    private const val PHYSICAL_PROBABLE_RELATIVE_TOLERANCE = 0.04
    private const val PHYSICAL_CONFLICT_RELATIVE_DELTA = 0.08
    private const val FOV_STRONG_DEGREES = 2.0
    private const val FOV_PROBABLE_DEGREES = 4.0
    private const val FOV_CONFLICT_DEGREES = 10.0
    private const val APERTURE_STRONG_RELATIVE_TOLERANCE = 0.05
    private const val APERTURE_CONFLICT_RELATIVE_DELTA = 0.20
    private const val STRONG_SCORE = 75
    private const val PROBABLE_SCORE = 55
    private const val MINIMUM_EVIDENCE_COUNT = 4

    fun signature(route: CameraRoute): OpticalLensSignature =
        signature(route.minimalMetadata, route.fullCapabilities?.capabilities)

    fun signature(profile: CameraProfile): OpticalLensSignature =
        signature(profile.metadata, profile.fullCapabilities?.capabilities)

    private fun signature(
        metadata: MinimalCameraMetadata,
        capabilities: LensCapabilities?,
    ): OpticalLensSignature = OpticalLensSignature(
        facing = metadata.facing,
        focalLengthMm = metadata.focalLengthsMm
            .filter { it.isFinite() && it > 0.0 }
            .minOrNull(),
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

    /** Transport/profile identity is deliberately not consulted here. */
    fun compare(left: CameraRoute, right: CameraRoute): OpticalLensComparison =
        compare(signature(left), signature(right))

    /** Profile IDs are deliberately not consulted here. */
    fun compare(left: CameraProfile, right: CameraProfile): OpticalLensComparison =
        compare(signature(left), signature(right))

    fun compare(left: OpticalLensSignature, right: OpticalLensSignature): OpticalLensComparison {
        val reasons = mutableListOf<String>()
        var score = 0
        var evidence = 0

        if (left.facing != LensFacing.UNKNOWN && right.facing != LensFacing.UNKNOWN) {
            if (left.facing != right.facing) return conflict("different facing")
            score += 5
            evidence++
            reasons += "facing agrees"
        }

        compareRelative(
            left.focalLengthMm,
            right.focalLengthMm,
            FOCAL_STRONG_RELATIVE_TOLERANCE,
            FOCAL_PROBABLE_RELATIVE_TOLERANCE,
            FOCAL_CONFLICT_RELATIVE_DELTA,
        )?.let { relation ->
            if (relation.conflict) return conflict("meaningfully different focal length")
            score += if (relation.strong) 30 else 18
            evidence++
            reasons += if (relation.strong) {
                "focal length strongly agrees"
            } else {
                "focal length probably agrees"
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
                    score += 25
                    evidence++
                    reasons += "physical sensor size strongly agrees"
                }
                delta <= PHYSICAL_PROBABLE_RELATIVE_TOLERANCE -> {
                    score += 14
                    evidence++
                    reasons += "physical sensor size probably agrees"
                }
            }
        }

        geometryEvidence(left.pixelArraySize, right.pixelArraySize)?.let { relation ->
            if (relation.conflict && leftPhysical == null && rightPhysical == null) {
                return conflict("different full pixel-array geometry")
            }
            if (!relation.conflict) {
                score += if (relation.strong) 15 else 7
                evidence++
                reasons += if (relation.strong) {
                    "pixel array agrees"
                } else {
                    "pixel array is binning-compatible"
                }
            }
        }

        geometryEvidence(left.activeArraySize, right.activeArraySize)?.let { relation ->
            if (relation.conflict && leftPhysical == null && rightPhysical == null) {
                return conflict("different active-array geometry")
            }
            if (!relation.conflict) {
                score += if (relation.strong) 14 else 6
                evidence++
                reasons += if (relation.strong) {
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
                    score += 20
                    evidence++
                    reasons += "RAW dimensions agree"
                }
                compatible -> {
                    score += 8
                    evidence++
                    reasons += "RAW dimensions are binning-compatible"
                }
                leftPhysical == null && rightPhysical == null ->
                    return conflict("different RAW sensor geometry")
                else -> reasons += "RAW dimensions differ; stronger physical evidence retained"
            }
        }

        val leftCfa = knownCfa(left.colorFilterArrangement)
        val rightCfa = knownCfa(right.colorFilterArrangement)
        if (leftCfa != null && rightCfa != null) {
            if (leftCfa != rightCfa) return conflict("different authoritative CFA")
            score += 15
            evidence++
            reasons += "CFA agrees"
        }

        if (left.sensorOrientationDegrees != null && right.sensorOrientationDegrees != null) {
            if (left.sensorOrientationDegrees != right.sensorOrientationDegrees) {
                return conflict("different sensor orientation")
            }
            score += 6
            evidence++
            reasons += "sensor orientation agrees"
        }

        compareRelative(
            left.aperture,
            right.aperture,
            APERTURE_STRONG_RELATIVE_TOLERANCE,
            APERTURE_STRONG_RELATIVE_TOLERANCE * 2,
            APERTURE_CONFLICT_RELATIVE_DELTA,
        )?.let { relation ->
            if (relation.conflict) return conflict("meaningfully different aperture")
            score += if (relation.strong) 7 else 3
            evidence++
            reasons += "aperture agrees"
        }

        if (left.diagonalFieldOfViewDegrees != null && right.diagonalFieldOfViewDegrees != null) {
            val delta = abs(left.diagonalFieldOfViewDegrees - right.diagonalFieldOfViewDegrees)
            when {
                delta > FOV_CONFLICT_DEGREES -> return conflict("clearly different field of view")
                delta <= FOV_STRONG_DEGREES -> {
                    score += 10
                    evidence++
                    reasons += "field of view strongly agrees"
                }
                delta <= FOV_PROBABLE_DEGREES -> {
                    score += 5
                    evidence++
                    reasons += "field of view probably agrees"
                }
            }
        }

        val match = when {
            evidence >= MINIMUM_EVIDENCE_COUNT && score >= STRONG_SCORE ->
                OpticalLensMatch.STRONG_MATCH
            evidence >= MINIMUM_EVIDENCE_COUNT && score >= PROBABLE_SCORE ->
                OpticalLensMatch.PROBABLE_MATCH
            else -> OpticalLensMatch.INSUFFICIENT_EVIDENCE
        }
        return OpticalLensComparison(match, score, evidence, reasons)
    }

    fun shouldGroup(left: CameraRoute, right: CameraRoute): Boolean =
        compare(left, right).match == OpticalLensMatch.STRONG_MATCH

    private fun conflict(reason: String) = OpticalLensComparison(
        OpticalLensMatch.CONFLICT,
        score = Int.MIN_VALUE,
        evidenceCount = 0,
        reasons = listOf(reason),
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
        if (left == null || right == null || !left.isFinite() || !right.isFinite() ||
            left <= 0 || right <= 0
        ) return null
        val delta = relativeDelta(left, right)
        return when {
            delta > conflictDelta -> RelativeRelation(strong = false, conflict = true)
            delta <= strongTolerance -> RelativeRelation(strong = true, conflict = false)
            delta <= probableTolerance -> RelativeRelation(strong = false, conflict = false)
            else -> null
        }
    }

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

    private data class RelativeRelation(val strong: Boolean, val conflict: Boolean)
}
