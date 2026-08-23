package com.sahidcode404.camex.core.logic

import com.sahidcode404.camex.core.model.CameraCapability
import com.sahidcode404.camex.core.model.CapabilityFlags
import com.sahidcode404.camex.core.model.CapabilitySupport
import com.sahidcode404.camex.core.model.HighSpeedConfiguration
import com.sahidcode404.camex.core.model.StreamConfiguration
import com.sahidcode404.camex.core.model.isPortableRaw

/** Normalized evidence supplied by the Android-facing metadata adapter. */
data class CapabilityEvidence(
    val reportedCapabilities: Set<CameraCapability>? = null,
    val streamConfigurations: List<StreamConfiguration>? = null,
    val minimumFocusDistanceDiopters: Double? = null,
    val autofocusOffAvailable: Boolean? = null,
    val opticalStabilizationAvailable: Boolean? = null,
    val videoStabilizationAvailable: Boolean? = null,
    val highSpeedConfigurations: List<HighSpeedConfiguration>? = null,
)

/** Maps nullable normalized metadata without depending on CameraCharacteristics constants. */
object CapabilityMapper {
    fun map(evidence: CapabilityEvidence): CapabilityFlags = CapabilityFlags(
        backwardCompatible = evidence.membership(CameraCapability.BACKWARD_COMPATIBLE),
        raw = rawSupport(evidence),
        manualSensor = evidence.membership(CameraCapability.MANUAL_SENSOR),
        manualPostProcessing = evidence.membership(CameraCapability.MANUAL_POST_PROCESSING),
        manualFocus = manualFocusSupport(evidence),
        burst = evidence.membership(CameraCapability.BURST_CAPTURE),
        logicalMultiCamera = evidence.membership(CameraCapability.LOGICAL_MULTI_CAMERA),
        highSpeedVideo = supportFromListOrCapability(
            evidence.highSpeedConfigurations,
            evidence,
            CameraCapability.CONSTRAINED_HIGH_SPEED_VIDEO,
        ),
        ultraHighResolution = evidence.membership(CameraCapability.ULTRA_HIGH_RESOLUTION_SENSOR),
        remosaic = evidence.membership(CameraCapability.REMOSAIC_REPROCESSING),
        opticalStabilization = evidence.opticalStabilizationAvailable.asSupport(),
        videoStabilization = evidence.videoStabilizationAvailable.asSupport(),
        depthOutput = evidence.membership(CameraCapability.DEPTH_OUTPUT),
        systemCamera = evidence.membership(CameraCapability.SYSTEM_CAMERA),
    )

    private fun rawSupport(evidence: CapabilityEvidence): CapabilitySupport {
        val streams = evidence.streamConfigurations
        if (streams?.any { it.format.isPortableRaw && it.size.isValid } == true) {
            return CapabilitySupport.SUPPORTED
        }
        if (streams != null) {
            // RAW_PRIVATE alone is intentionally not treated as portable RAW support.
            return if (evidence.reportedCapabilities?.contains(CameraCapability.RAW) == true) {
                CapabilitySupport.UNKNOWN // contradictory advertisement; probe before exposing RAW
            } else {
                CapabilitySupport.UNSUPPORTED
            }
        }
        return evidence.membership(CameraCapability.RAW)
    }

    private fun manualFocusSupport(evidence: CapabilityEvidence): CapabilitySupport {
        val distance = evidence.minimumFocusDistanceDiopters
        if (distance != null && (!distance.isFinite() || distance <= 0.0)) {
            return CapabilitySupport.UNSUPPORTED
        }
        return when {
            distance != null && evidence.autofocusOffAvailable == true -> CapabilitySupport.SUPPORTED
            evidence.autofocusOffAvailable == false -> CapabilitySupport.UNSUPPORTED
            else -> CapabilitySupport.UNKNOWN
        }
    }

    private fun <T> supportFromListOrCapability(
        values: List<T>?,
        evidence: CapabilityEvidence,
        capability: CameraCapability,
    ): CapabilitySupport = when {
        values?.isNotEmpty() == true -> CapabilitySupport.SUPPORTED
        values != null -> evidence.membership(capability)
        else -> evidence.membership(capability)
    }

    private fun CapabilityEvidence.membership(capability: CameraCapability): CapabilitySupport =
        reportedCapabilities?.contains(capability)?.asSupport() ?: CapabilitySupport.UNKNOWN

    private fun Boolean?.asSupport(): CapabilitySupport = when (this) {
        true -> CapabilitySupport.SUPPORTED
        false -> CapabilitySupport.UNSUPPORTED
        null -> CapabilitySupport.UNKNOWN
    }
}
