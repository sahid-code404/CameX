package com.sahidcode404.camex.core.logic

import com.sahidcode404.camex.core.model.CameraCapability
import com.sahidcode404.camex.core.model.CapabilitySupport
import com.sahidcode404.camex.core.model.ColorFilterArrangement
import com.sahidcode404.camex.core.model.FieldOfView
import com.sahidcode404.camex.core.model.LensCategory
import com.sahidcode404.camex.core.model.LensDescriptor
import com.sahidcode404.camex.core.model.LensFacing
import com.sahidcode404.camex.core.model.LensUsability

object LensClassifier {
    /**
     * Broad diagonal-FOV bands intentionally avoid advertised zoom labels. Boundaries are only a
     * UI description: >=90° ultrawide, >=55° wide, >=25° tele and <25° super-tele.
     */
    fun classify(
        lens: LensDescriptor,
        fieldOfView: FieldOfView? = LensMath.fieldOfView(lens.capabilities),
    ): LensCategory {
        if (lens.usability == LensUsability.DEPTH_AUXILIARY ||
            lens.capabilities.reportedCapabilities?.contains(CameraCapability.DEPTH_OUTPUT) == true &&
            lens.capabilities.flags.backwardCompatible == CapabilitySupport.UNSUPPORTED ||
            lens.capabilities.colorFilterArrangement == ColorFilterArrangement.NIR ||
            lens.capabilities.reportedCapabilities?.contains(CameraCapability.MONOCHROME) == true &&
            lens.capabilities.flags.backwardCompatible != CapabilitySupport.SUPPORTED
        ) return LensCategory.AUXILIARY
        if (lens.facing == LensFacing.FRONT) return LensCategory.FRONT
        if (lens.facing == LensFacing.EXTERNAL) return LensCategory.EXTERNAL

        val diagonal = fieldOfView?.diagonalDegrees
            ?.takeIf { it.isFinite() && it > 0.0 && it < 180.0 }
            ?: return LensCategory.UNKNOWN
        return when {
            diagonal >= 90.0 -> LensCategory.ULTRAWIDE
            diagonal >= 55.0 -> LensCategory.WIDE
            diagonal >= 25.0 -> LensCategory.TELEPHOTO
            else -> LensCategory.SUPER_TELEPHOTO
        }
    }
}
