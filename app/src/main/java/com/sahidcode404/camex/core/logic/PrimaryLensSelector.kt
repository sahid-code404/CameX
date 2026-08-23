package com.sahidcode404.camex.core.logic

import com.sahidcode404.camex.core.model.CapabilitySupport
import com.sahidcode404.camex.core.model.HardwareLevel
import com.sahidcode404.camex.core.model.LensCategory
import com.sahidcode404.camex.core.model.LensDescriptor
import com.sahidcode404.camex.core.model.LensFacing
import com.sahidcode404.camex.core.model.LensFingerprint
import com.sahidcode404.camex.core.model.LensUsability
import kotlin.math.abs
import kotlin.math.ln

data class PrimaryLensScore(
    val usability: Int,
    val fovSuitability: Int,
    val capabilityConfidence: Int,
    val sensorEvidence: Int,
)

/** Capability/FOV heuristic with no manufacturer, model, or Camera2 ID rules. */
object PrimaryLensSelector {
    fun select(
        lenses: List<LensDescriptor>,
        userReference: LensFingerprint? = null,
    ): LensDescriptor? {
        val eligible = lenses.filter {
            it.facing == LensFacing.BACK && it.usability.isSelectable &&
                it.category != LensCategory.AUXILIARY
        }
        userReference?.let { preferred ->
            eligible.firstOrNull { it.fingerprint?.value == preferred.value }?.let { return it }
        }
        return eligible.maxWithOrNull(
            compareBy<LensDescriptor> { score(it).fovSuitability }
                .thenBy { score(it).usability }
                .thenBy { score(it).capabilityConfidence }
                .thenBy { score(it).sensorEvidence }
                // Fingerprint/discovery order only make otherwise-equal input deterministic.
                .thenBy { it.fingerprint?.value.orEmpty() }
                .thenBy { -it.discoveryOrder },
        )
    }

    fun score(lens: LensDescriptor): PrimaryLensScore {
        val usability = when (lens.usability) {
            LensUsability.RAW_NATIVE -> 60
            LensUsability.RAW_PHYSICAL_STREAM -> 58
            LensUsability.RAW_MAX_RESOLUTION -> 56
            LensUsability.PROCESSED_ONLY -> 50
            LensUsability.PREVIEW_ONLY -> 25
            else -> 0
        }
        val fov = LensMath.fieldOfView(lens.capabilities)?.diagonalDegrees
        // A conventional wide lens is normally near 70° diagonal; use a broad curve, not a label.
        val fovSuitability = fov?.let { (100.0 - abs(it - 70.0) * 2.0).toInt().coerceIn(0, 100) }
            ?: when (lens.category) {
                LensCategory.WIDE -> 70
                LensCategory.ULTRAWIDE, LensCategory.TELEPHOTO -> 30
                LensCategory.SUPER_TELEPHOTO -> 10
                else -> 0
            }
        val flags = lens.capabilities.flags
        val capabilityConfidence = listOf(
            flags.backwardCompatible,
            flags.raw,
            flags.manualSensor,
            flags.burst,
        ).sumOf { if (it == CapabilitySupport.SUPPORTED) 10 else 0 } +
            when (lens.capabilities.hardwareLevel) {
                HardwareLevel.LEVEL_3 -> 12
                HardwareLevel.FULL -> 10
                HardwareLevel.LIMITED -> 6
                HardwareLevel.LEGACY -> 2
                HardwareLevel.EXTERNAL, HardwareLevel.UNKNOWN -> 0
            }
        val pixelArea = lens.capabilities.activeArray?.takeIf { it.isValid }?.size?.area
            ?: lens.capabilities.pixelArraySize?.area
        val sensorEvidence = pixelArea?.let {
            // Log scaling prevents a huge advertised array from dominating verified usability/FOV.
            (ln(it.coerceAtLeast(1L).toDouble()) * 2.0).toInt().coerceAtMost(40)
        } ?: 0
        return PrimaryLensScore(usability, fovSuitability, capabilityConfidence, sensorEvidence)
    }
}
