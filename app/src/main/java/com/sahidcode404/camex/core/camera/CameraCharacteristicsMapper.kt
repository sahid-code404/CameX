package com.sahidcode404.camex.core.camera

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.util.Range
import android.util.Size
import com.sahidcode404.camex.core.logic.CapabilityEvidence
import com.sahidcode404.camex.core.logic.CapabilityMapper
import com.sahidcode404.camex.core.model.CameraCapability
import com.sahidcode404.camex.core.model.CapabilitySupport
import com.sahidcode404.camex.core.model.ColorFilterArrangement
import com.sahidcode404.camex.core.model.FpsRange
import com.sahidcode404.camex.core.model.HardwareLevel
import com.sahidcode404.camex.core.model.HighSpeedConfiguration
import com.sahidcode404.camex.core.model.IntValueRange
import com.sahidcode404.camex.core.model.LensCapabilities
import com.sahidcode404.camex.core.model.LensDescriptor
import com.sahidcode404.camex.core.model.LensFacing
import com.sahidcode404.camex.core.model.LensIdentity
import com.sahidcode404.camex.core.model.LensNodeKind
import com.sahidcode404.camex.core.model.LensUsability
import com.sahidcode404.camex.core.model.LongValueRange
import com.sahidcode404.camex.core.model.PhysicalSize
import com.sahidcode404.camex.core.model.RawAccess
import com.sahidcode404.camex.core.model.SensorRect
import com.sahidcode404.camex.core.model.Size2D
import com.sahidcode404.camex.core.model.StreamConfiguration
import com.sahidcode404.camex.core.model.StreamFormat

/** Converts fragile framework metadata into immutable, null-safe application data. */
internal object CameraCharacteristicsMapper {
    fun map(
        identity: LensIdentity,
        characteristics: CameraCharacteristics,
        discoveryOrder: Int,
    ): LensDescriptor {
        val reported = characteristics.value(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.toSet()
        val capabilities = reported?.mapTo(linkedSetOf(), ::mapCapability)
        val standardMap = characteristics.value(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val standardStreams = readStreamConfigurations(standardMap, maximumResolution = false)
        val maximumStreams = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            readMaximumResolutionStreams(characteristics)
        } else {
            emptyList()
        }
        val normalizedStreams = (standardStreams + maximumStreams)
            .distinctBy { listOf(it.format, it.size.width, it.size.height, it.maximumResolution) }
            .sortedWith(
                compareBy<StreamConfiguration> { it.format.ordinal }
                    .thenByDescending { it.size.area ?: -1L }
                    .thenBy { it.size.width }
                    .thenBy { it.size.height },
            )
        val allStreams = normalizedStreams.takeIf(List<StreamConfiguration>::isNotEmpty)
        val minimumFocusDistance = characteristics
            .value(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
            ?.takeIf { it.isFinite() && it >= 0f }
            ?.toDouble()
        val afModeValues = characteristics.value(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
        val opticalStabilizationModes = characteristics
            .value(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        val videoStabilizationModes = characteristics
            .value(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
        val highSpeedConfigurations = readHighSpeedConfigurations(standardMap)
        val flags = CapabilityMapper.map(
            CapabilityEvidence(
                reportedCapabilities = capabilities,
                // An empty normalized list is meaningful negative/contradictory evidence. In
                // particular, a RAW capability bit without a portable RAW stream stays UNKNOWN.
                streamConfigurations = normalizedStreams,
                minimumFocusDistanceDiopters = minimumFocusDistance,
                autofocusOffAvailable = afModeValues?.contains(
                    CameraMetadata.CONTROL_AF_MODE_OFF,
                ),
                opticalStabilizationAvailable = opticalStabilizationModes?.contains(
                    CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON,
                ),
                videoStabilizationAvailable = videoStabilizationModes?.contains(
                    CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON,
                ),
                highSpeedConfigurations = highSpeedConfigurations,
            ),
        )
        val hasRaw = flags.raw
        val rawAccess = when {
            hasRaw != CapabilitySupport.SUPPORTED -> when (hasRaw) {
                CapabilitySupport.UNSUPPORTED -> RawAccess.NONE
                else -> RawAccess.UNKNOWN
            }
            identity.streamPhysicalCameraId != null -> RawAccess.PHYSICAL_STREAM
            standardStreams.any { it.format.isRaw() } -> RawAccess.DIRECT
            maximumStreams.any { it.format.isRaw() } -> RawAccess.MAXIMUM_RESOLUTION
            else -> RawAccess.UNKNOWN
        }
        val backwardCompatible = flags.backwardCompatible
        val depthOutput = flags.depthOutput
        val systemCamera = flags.systemCamera

        val lensCapabilities = LensCapabilities(
            focalLengthsMm = characteristics
                .value(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.filter { it.isFinite() && it > 0f }
                ?.map(Float::toDouble)
                ?.takeIf(List<Double>::isNotEmpty),
            sensorPhysicalSize = characteristics.value(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                ?.let { PhysicalSize(it.width.toDouble(), it.height.toDouble()) },
            pixelArraySize = characteristics.value(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                ?.toModel(),
            activeArray = characteristics.value(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                ?.toModel(),
            maximumResolutionArray = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                maximumResolutionArray(characteristics)
            } else {
                null
            },
            sensorOrientationDegrees = characteristics.value(CameraCharacteristics.SENSOR_ORIENTATION)
                ?.takeIf { it in 0..359 },
            apertures = characteristics.value(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                ?.filter { it.isFinite() && it > 0f }
                ?.map(Float::toDouble)
                ?.takeIf(List<Double>::isNotEmpty),
            minimumFocusDistanceDiopters = minimumFocusDistance,
            hardwareLevel = mapHardwareLevel(
                characteristics.value(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL),
            ),
            reportedCapabilities = capabilities,
            flags = flags,
            rawAccess = rawAccess,
            colorFilterArrangement = mapCfa(
                characteristics.value(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT),
            ),
            isoRange = characteristics.value(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                ?.let { IntValueRange(it.lower, it.upper) },
            exposureTimeRangeNs = characteristics
                .value(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                ?.let { LongValueRange(it.lower, it.upper) },
            maximumAnalogSensitivity = characteristics
                .value(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY)
                ?.takeIf { it > 0 },
            aeModes = characteristics.value(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)
                ?.mapToModeSet(::aeModeName),
            afModes = afModeValues?.mapToModeSet(::afModeName),
            awbModes = characteristics.value(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
                ?.mapToModeSet(::awbModeName),
            streamConfigurations = allStreams,
            highSpeedConfigurations = highSpeedConfigurations,
            previewFpsRanges = characteristics
                .value(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.mapNotNull { it.toModel() }
                ?.distinct()
                ?.sortedWith(compareBy<FpsRange> { it.min }.thenBy { it.max })
                ?.takeIf(List<FpsRange>::isNotEmpty),
        )
        val facing = mapFacing(characteristics.value(CameraCharacteristics.LENS_FACING))
        val usability = when {
            systemCamera == CapabilitySupport.SUPPORTED -> LensUsability.SYSTEM_ONLY
            depthOutput == CapabilitySupport.SUPPORTED &&
                backwardCompatible == CapabilitySupport.UNSUPPORTED -> LensUsability.DEPTH_AUXILIARY
            else -> LensUsability.UNKNOWN
        }
        val nodeKind = when {
            identity.streamPhysicalCameraId != null -> LensNodeKind.PHYSICAL
            lensCapabilities.flags.logicalMultiCamera == CapabilitySupport.SUPPORTED -> LensNodeKind.LOGICAL
            else -> identity.nodeKind
        }
        return LensDescriptor(
            identity = identity.copy(nodeKind = nodeKind),
            facing = facing,
            capabilities = lensCapabilities,
            usability = usability,
            discoveryOrder = discoveryOrder,
        )
    }

    private fun readStreamConfigurations(
        map: StreamConfigurationMap?,
        maximumResolution: Boolean,
    ): List<StreamConfiguration> {
        if (map == null) return emptyList()
        val result = mutableListOf<StreamConfiguration>()
        val formats = map.safe { outputFormats }?.toList().orEmpty()
        formats.forEach { imageFormat ->
            val format = mapStreamFormat(imageFormat)
            map.safe { getOutputSizes(imageFormat) }
                .orEmpty()
                .filter { it.width > 0 && it.height > 0 }
                .forEach { size ->
                    result += StreamConfiguration(
                        format = format,
                        size = size.toModel(),
                        minFrameDurationNs = map.safe {
                            getOutputMinFrameDuration(imageFormat, size)
                        }?.takeIf { it > 0L },
                        stallDurationNs = map.safe {
                            getOutputStallDuration(imageFormat, size)
                        }?.takeIf { it > 0L },
                        maximumResolution = maximumResolution,
                    )
                }
        }

        // TextureView-compatible PRIVATE sizes are sometimes absent from outputFormats on broken
        // implementations. Reading the class-keyed list gives preview a safe second source.
        map.safe { getOutputSizes(SurfaceTexture::class.java) }
            .orEmpty()
            .filter { it.width > 0 && it.height > 0 }
            .forEach { size ->
                val config = StreamConfiguration(
                    format = StreamFormat.PRIVATE,
                    size = size.toModel(),
                    minFrameDurationNs = map.safe {
                        getOutputMinFrameDuration(SurfaceTexture::class.java, size)
                    }?.takeIf { it > 0L },
                    maximumResolution = maximumResolution,
                )
                if (result.none { it.format == config.format && it.size == config.size }) result += config
            }
        return result
    }

    private fun readHighSpeedConfigurations(map: StreamConfigurationMap?): List<HighSpeedConfiguration>? {
        if (map == null) return null
        return map.safe { highSpeedVideoSizes }
            .orEmpty()
            .flatMap { size ->
                map.safe { getHighSpeedVideoFpsRangesFor(size) }
                    .orEmpty()
                    .mapNotNull { range ->
                        range.toModel()?.let { HighSpeedConfiguration(size.toModel(), it) }
                    }
            }
            .distinct()
            .sortedWith(
                compareBy<HighSpeedConfiguration> { it.size.area ?: -1L }
                    .thenBy { it.fpsRange.min }
                    .thenBy { it.fpsRange.max },
            )
            .takeIf(List<HighSpeedConfiguration>::isNotEmpty)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private fun readMaximumResolutionStreams(
        characteristics: CameraCharacteristics,
    ): List<StreamConfiguration> = readStreamConfigurations(
        characteristics.value(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION,
        ),
        maximumResolution = true,
    )

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private fun maximumResolutionArray(characteristics: CameraCharacteristics): SensorRect? =
        characteristics.value(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION)
            ?.toModel()

    private fun mapFacing(value: Int?): LensFacing = when (value) {
        CameraMetadata.LENS_FACING_FRONT -> LensFacing.FRONT
        CameraMetadata.LENS_FACING_BACK -> LensFacing.BACK
        CameraMetadata.LENS_FACING_EXTERNAL -> LensFacing.EXTERNAL
        else -> LensFacing.UNKNOWN
    }

    private fun mapHardwareLevel(value: Int?): HardwareLevel = when (value) {
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> HardwareLevel.LEGACY
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> HardwareLevel.LIMITED
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> HardwareLevel.FULL
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> HardwareLevel.LEVEL_3
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> HardwareLevel.EXTERNAL
        else -> HardwareLevel.UNKNOWN
    }

    private fun mapCapability(value: Int): CameraCapability = when (value) {
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE ->
            CameraCapability.BACKWARD_COMPATIBLE
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> CameraCapability.MANUAL_SENSOR
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING ->
            CameraCapability.MANUAL_POST_PROCESSING
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW -> CameraCapability.RAW
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> CameraCapability.BURST_CAPTURE
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA ->
            CameraCapability.LOGICAL_MULTI_CAMERA
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO ->
            CameraCapability.CONSTRAINED_HIGH_SPEED_VIDEO
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR ->
            CameraCapability.ULTRA_HIGH_RESOLUTION_SENSOR
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_REMOSAIC_REPROCESSING ->
            CameraCapability.REMOSAIC_REPROCESSING
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT -> CameraCapability.DEPTH_OUTPUT
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME -> CameraCapability.MONOCHROME
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_SYSTEM_CAMERA -> CameraCapability.SYSTEM_CAMERA
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_OFFLINE_PROCESSING ->
            CameraCapability.OFFLINE_PROCESSING
        else -> CameraCapability.UNKNOWN
    }

    private fun mapStreamFormat(value: Int): StreamFormat {
        if (Build.VERSION.SDK_INT >= 37 && value == ImageFormat.RAW14) {
            return StreamFormat.RAW14
        }
        return when (value) {
            ImageFormat.PRIVATE -> StreamFormat.PRIVATE
            ImageFormat.YUV_420_888 -> StreamFormat.YUV_420_888
            ImageFormat.JPEG -> StreamFormat.JPEG
            ImageFormat.HEIC -> StreamFormat.HEIC
            ImageFormat.RAW_SENSOR -> StreamFormat.RAW_SENSOR
            ImageFormat.RAW10 -> StreamFormat.RAW10
            ImageFormat.RAW12 -> StreamFormat.RAW12
            ImageFormat.RAW_PRIVATE -> StreamFormat.RAW_PRIVATE
            ImageFormat.DEPTH16 -> StreamFormat.DEPTH16
            ImageFormat.DEPTH_POINT_CLOUD -> StreamFormat.DEPTH_POINT_CLOUD
            ImageFormat.DEPTH_JPEG -> StreamFormat.DEPTH_JPEG
            else -> StreamFormat.UNKNOWN
        }
    }

    private fun StreamFormat.isRaw(): Boolean = when (this) {
        StreamFormat.RAW_SENSOR,
        StreamFormat.RAW10,
        StreamFormat.RAW12,
        StreamFormat.RAW14,
        -> true
        else -> false
    }

    private fun mapCfa(value: Int?): ColorFilterArrangement? = when (value) {
        null -> null
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> ColorFilterArrangement.RGGB
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> ColorFilterArrangement.GRBG
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> ColorFilterArrangement.GBRG
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> ColorFilterArrangement.BGGR
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGB -> ColorFilterArrangement.RGB
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO -> ColorFilterArrangement.MONO
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_NIR -> ColorFilterArrangement.NIR
        else -> ColorFilterArrangement.UNKNOWN
    }

    private fun aeModeName(value: Int): String = when (value) {
        CameraMetadata.CONTROL_AE_MODE_OFF -> "OFF"
        CameraMetadata.CONTROL_AE_MODE_ON -> "ON"
        CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH -> "ON_AUTO_FLASH"
        CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH -> "ON_ALWAYS_FLASH"
        CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE -> "ON_AUTO_FLASH_REDEYE"
        CameraMetadata.CONTROL_AE_MODE_ON_EXTERNAL_FLASH -> "ON_EXTERNAL_FLASH"
        else -> "UNKNOWN_$value"
    }

    private fun afModeName(value: Int): String = when (value) {
        CameraMetadata.CONTROL_AF_MODE_OFF -> "OFF"
        CameraMetadata.CONTROL_AF_MODE_AUTO -> "AUTO"
        CameraMetadata.CONTROL_AF_MODE_MACRO -> "MACRO"
        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO -> "CONTINUOUS_VIDEO"
        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE -> "CONTINUOUS_PICTURE"
        CameraMetadata.CONTROL_AF_MODE_EDOF -> "EDOF"
        else -> "UNKNOWN_$value"
    }

    private fun awbModeName(value: Int): String = when (value) {
        CameraMetadata.CONTROL_AWB_MODE_OFF -> "OFF"
        CameraMetadata.CONTROL_AWB_MODE_AUTO -> "AUTO"
        CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT -> "INCANDESCENT"
        CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT -> "FLUORESCENT"
        CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT -> "WARM_FLUORESCENT"
        CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT -> "DAYLIGHT"
        CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> "CLOUDY_DAYLIGHT"
        CameraMetadata.CONTROL_AWB_MODE_TWILIGHT -> "TWILIGHT"
        CameraMetadata.CONTROL_AWB_MODE_SHADE -> "SHADE"
        else -> "UNKNOWN_$value"
    }

    private fun IntArray.mapToModeSet(mapper: (Int) -> String): Set<String>? =
        mapTo(linkedSetOf(), mapper).takeIf(Set<String>::isNotEmpty)

    private fun Range<Int>.toModel(): FpsRange? =
        FpsRange(lower, upper).takeIf(FpsRange::isValid)

    private fun Size.toModel() = Size2D(width, height)

    private fun Rect.toModel() = SensorRect(left, top, right, bottom)

    private inline fun <T> CameraCharacteristics.value(
        key: CameraCharacteristics.Key<T>,
    ): T? = safeMetadata { get(key) }

    private inline fun <T> StreamConfigurationMap.safe(block: StreamConfigurationMap.() -> T): T? =
        safeMetadata { block() }

    private inline fun <T> safeMetadata(block: () -> T): T? = try {
        block()
    } catch (error: Throwable) {
        when (error) {
            is VirtualMachineError, is ThreadDeath -> throw error
            else -> null
        }
    }
}
