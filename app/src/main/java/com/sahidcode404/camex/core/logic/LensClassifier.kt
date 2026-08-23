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
        when (lens.usability) {
            LensUsability.SYSTEM_ONLY -> return LensCategory.SYSTEM_ONLY
            LensUsability.INACCESSIBLE -> return LensCategory.INACCESSIBLE
            LensUsability.BROKEN -> return LensCategory.BROKEN
            LensUsability.DEPTH_AUXILIARY -> return LensCategory.NON_PHOTO_DEPTH
            else -> Unit
        }
        if (
            lens.capabilities.reportedCapabilities?.contains(CameraCapability.DEPTH_OUTPUT) == true &&
            lens.capabilities.flags.backwardCompatible == CapabilitySupport.UNSUPPORTED
        ) return LensCategory.NON_PHOTO_DEPTH
        if (lens.capabilities.colorFilterArrangement == ColorFilterArrangement.NIR) {
            return LensCategory.NON_PHOTO_IR
        }
        if (lens.capabilities.reportedCapabilities?.contains(CameraCapability.MONOCHROME) == true) {
            return LensCategory.PHOTOGRAPHIC_MONO
        }

        val diagonal = fieldOfView?.diagonalDegrees
            ?.takeIf { it.isFinite() && it > 0.0 && it < 180.0 }
            ?: return LensCategory.PHOTOGRAPHIC_UNKNOWN
        return when {
            diagonal >= 90.0 -> LensCategory.PHOTOGRAPHIC_ULTRAWIDE
            diagonal >= 55.0 -> LensCategory.PHOTOGRAPHIC_WIDE
            diagonal >= 25.0 -> LensCategory.PHOTOGRAPHIC_TELEPHOTO
            else -> LensCategory.PHOTOGRAPHIC_SUPER_TELEPHOTO
        }
    }
}
