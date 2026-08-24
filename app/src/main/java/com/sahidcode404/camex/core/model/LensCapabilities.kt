package com.sahidcode404.camex.core.model

import kotlinx.serialization.Serializable

/** A tri-state value preserves the difference between false and metadata that was not available. */
@Serializable
enum class CapabilitySupport {
    SUPPORTED,
    UNSUPPORTED,
    UNKNOWN,
}

@Serializable
enum class HardwareLevel {
    LEGACY,
    LIMITED,
    FULL,
    LEVEL_3,
    EXTERNAL,
    UNKNOWN,
}

@Serializable
enum class CameraCapability {
    BACKWARD_COMPATIBLE,
    MANUAL_SENSOR,
    MANUAL_POST_PROCESSING,
    RAW,
    BURST_CAPTURE,
    LOGICAL_MULTI_CAMERA,
    CONSTRAINED_HIGH_SPEED_VIDEO,
    ULTRA_HIGH_RESOLUTION_SENSOR,
    REMOSAIC_REPROCESSING,
    DEPTH_OUTPUT,
    MONOCHROME,
    SYSTEM_CAMERA,
    OFFLINE_PROCESSING,
    UNKNOWN,
}

@Serializable
enum class StreamFormat {
    PRIVATE,
    YUV_420_888,
    JPEG,
    HEIC,
    RAW_SENSOR,
    RAW10,
    RAW12,
    RAW14,
    RAW_PRIVATE,
    DEPTH16,
    DEPTH_POINT_CLOUD,
    DEPTH_JPEG,
    UNKNOWN,
}

val StreamFormat.isPortableRaw: Boolean
    get() = this == StreamFormat.RAW_SENSOR ||
        this == StreamFormat.RAW10 ||
        this == StreamFormat.RAW12 ||
        this == StreamFormat.RAW14

/**
 * Formats that CameX can safely activate as a repeating viewfinder stream today.
 *
 * PRIVATE is the zero-copy SurfaceTexture display transport. YUV_420_888 is an optional live
 * processing stream that is drained on the camera callback thread while PRIVATE continues to own
 * the display transport. Capture/stall formats are deliberately excluded even when the HAL reports
 * them; exposing them as selectable live streams would create fake configuration and vendor hangs.
 */
val StreamFormat.isLiveViewfinderStream: Boolean
    get() = this == StreamFormat.PRIVATE || this == StreamFormat.YUV_420_888

@Serializable
data class StreamConfiguration(
    val format: StreamFormat,
    val size: Size2D,
    val minFrameDurationNs: Long? = null,
    val stallDurationNs: Long? = null,
    val maximumResolution: Boolean = false,
)

@Serializable
data class HighSpeedConfiguration(
    val size: Size2D,
    val fpsRange: FpsRange,
)

@Serializable
enum class ColorFilterArrangement {
    RGGB,
    GRBG,
    GBRG,
    BGGR,
    RGB,
    MONO,
    NIR,
    UNKNOWN,
}

@Serializable
enum class RawAccess {
    DIRECT,
    PHYSICAL_STREAM,
    MAXIMUM_RESOLUTION,
    NONE,
    UNKNOWN,
}

@Serializable
data class CapabilityFlags(
    val backwardCompatible: CapabilitySupport = CapabilitySupport.UNKNOWN,
    val raw: CapabilitySupport = CapabilitySupport.UNKNOWN,
    val manualSensor: CapabilitySupport = CapabilitySupport.UNKNOWN,
    val manualPostProcessing: CapabilitySupport = CapabilitySupport.UNKNOWN,
    val manualFocus: CapabilitySupport = CapabilitySupport.UNKNOWN,
    val burst: CapabilitySupport = CapabilitySupport.UNKNOWN,
    val logicalMultiCamera: CapabilitySupport = CapabilitySupport.UNKNOWN,
    val highSpeedVideo: CapabilitySupport = CapabilitySupport.UNKNOWN,
    val ultraHighResolution: CapabilitySupport = CapabilitySupport.UNKNOWN,
    val remosaic: CapabilitySupport = CapabilitySupport.UNKNOWN,
    val opticalStabilization: CapabilitySupport = CapabilitySupport.UNKNOWN,
    val videoStabilization: CapabilitySupport = CapabilitySupport.UNKNOWN,
    val depthOutput: CapabilitySupport = CapabilitySupport.UNKNOWN,
    val systemCamera: CapabilitySupport = CapabilitySupport.UNKNOWN,
)

/**
 * Framework-free, loss-tolerant capability snapshot. Every optional item may be absent because
 * real HALs frequently omit keys or return malformed values; calculation code validates values
 * before using them rather than making construction fail.
 */
@Serializable
data class LensCapabilities(
    val focalLengthsMm: List<Double>? = null,
    val sensorPhysicalSize: PhysicalSize? = null,
    val pixelArraySize: Size2D? = null,
    val activeArray: SensorRect? = null,
    val maximumResolutionArray: SensorRect? = null,
    val sensorOrientationDegrees: Int? = null,
    val apertures: List<Double>? = null,
    val minimumFocusDistanceDiopters: Double? = null,
    val hardwareLevel: HardwareLevel = HardwareLevel.UNKNOWN,
    val reportedCapabilities: Set<CameraCapability>? = null,
    val flags: CapabilityFlags = CapabilityFlags(),
    val rawAccess: RawAccess = RawAccess.UNKNOWN,
    val colorFilterArrangement: ColorFilterArrangement? = null,
    val isoRange: IntValueRange? = null,
    val exposureTimeRangeNs: LongValueRange? = null,
    val maximumAnalogSensitivity: Int? = null,
    val aeModes: Set<String>? = null,
    val afModes: Set<String>? = null,
    val awbModes: Set<String>? = null,
    val streamConfigurations: List<StreamConfiguration>? = null,
    val highSpeedConfigurations: List<HighSpeedConfiguration>? = null,
    val previewFpsRanges: List<FpsRange>? = null,
) {
    fun configurations(format: StreamFormat): List<StreamConfiguration> =
        streamConfigurations.orEmpty().filter { it.format == format && it.size.isValid }

    val portableRawConfigurations: List<StreamConfiguration>
        get() = streamConfigurations.orEmpty().filter { it.format.isPortableRaw && it.size.isValid }

    val maximumRawSize: Size2D?
        get() = portableRawConfigurations.maxByOrNull { it.size.area ?: -1L }?.size

    /** Best-case estimate from advertised stream timing; null means the HAL supplied no timing. */
    val estimatedMaximumRawFps: Double?
        get() = portableRawConfigurations
            .mapNotNull { it.minFrameDurationNs?.takeIf { duration -> duration > 0L } }
            .minOrNull()
            ?.let { 1_000_000_000.0 / it }

    val privateResolutions: List<Size2D> get() = configurations(StreamFormat.PRIVATE).map { it.size }
    val yuvResolutions: List<Size2D> get() = configurations(StreamFormat.YUV_420_888).map { it.size }
    val rawResolutions: List<Size2D> get() = portableRawConfigurations.map { it.size }.distinct()
}
