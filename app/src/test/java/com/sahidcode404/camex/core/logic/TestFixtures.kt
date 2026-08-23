package com.sahidcode404.camex.core.logic

import com.sahidcode404.camex.core.model.CameraCapability
import com.sahidcode404.camex.core.model.CapabilityFlags
import com.sahidcode404.camex.core.model.CapabilitySupport
import com.sahidcode404.camex.core.model.LensCapabilities
import com.sahidcode404.camex.core.model.LensCategory
import com.sahidcode404.camex.core.model.LensDescriptor
import com.sahidcode404.camex.core.model.LensFacing
import com.sahidcode404.camex.core.model.LensIdentity
import com.sahidcode404.camex.core.model.LensUsability
import com.sahidcode404.camex.core.model.PhysicalSize
import com.sahidcode404.camex.core.model.RawAccess
import com.sahidcode404.camex.core.model.SensorRect
import com.sahidcode404.camex.core.model.Size2D
import com.sahidcode404.camex.core.model.StreamConfiguration
import com.sahidcode404.camex.core.model.StreamFormat

internal fun testCapabilities(
    focalMm: Double = 4.0,
    sensorWidthMm: Double = 5.6,
    sensorHeightMm: Double = 4.2,
    pixelSize: Size2D = Size2D(4000, 3000),
    flags: CapabilityFlags = CapabilityFlags(
        backwardCompatible = CapabilitySupport.SUPPORTED,
        raw = CapabilitySupport.SUPPORTED,
        manualSensor = CapabilitySupport.SUPPORTED,
        burst = CapabilitySupport.SUPPORTED,
    ),
    rawAccess: RawAccess = RawAccess.DIRECT,
    streams: List<StreamConfiguration>? = listOf(
        StreamConfiguration(StreamFormat.PRIVATE, Size2D(1920, 1080), 33_333_333),
        StreamConfiguration(StreamFormat.YUV_420_888, pixelSize, 50_000_000),
        StreamConfiguration(StreamFormat.RAW_SENSOR, pixelSize, 100_000_000),
    ),
): LensCapabilities = LensCapabilities(
    focalLengthsMm = listOf(focalMm),
    sensorPhysicalSize = PhysicalSize(sensorWidthMm, sensorHeightMm),
    pixelArraySize = pixelSize,
    activeArray = SensorRect(0, 0, pixelSize.width, pixelSize.height),
    sensorOrientationDegrees = 90,
    apertures = listOf(1.8),
    reportedCapabilities = setOf(
        CameraCapability.BACKWARD_COMPATIBLE,
        CameraCapability.RAW,
        CameraCapability.MANUAL_SENSOR,
        CameraCapability.BURST_CAPTURE,
    ),
    flags = flags,
    rawAccess = rawAccess,
    streamConfigurations = streams,
)

internal fun testLens(
    id: String,
    focalMm: Double = 4.0,
    sensorWidthMm: Double = 5.6,
    sensorHeightMm: Double = 4.2,
    facing: LensFacing = LensFacing.BACK,
    usability: LensUsability = LensUsability.RAW_NATIVE,
    category: LensCategory = LensCategory.PHOTOGRAPHIC_WIDE,
    discoveryOrder: Int = 0,
    capabilities: LensCapabilities = testCapabilities(
        focalMm = focalMm,
        sensorWidthMm = sensorWidthMm,
        sensorHeightMm = sensorHeightMm,
    ),
): LensDescriptor = LensDescriptor(
    identity = LensIdentity(publicCameraId = id),
    facing = facing,
    capabilities = capabilities,
    usability = usability,
    category = category,
    discoveryOrder = discoveryOrder,
)
