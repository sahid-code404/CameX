package com.sahidcode404.camex.core.camera.runtime

import com.sahidcode404.camex.core.camera.discovery.nativebackend.NativeCameraCapability
import com.sahidcode404.camex.core.camera.discovery.nativebackend.NativeColorFilterArrangement
import com.sahidcode404.camex.core.camera.discovery.nativebackend.NativeDiscoveryResult
import com.sahidcode404.camex.core.camera.discovery.nativebackend.NativeDiscoverySource
import com.sahidcode404.camex.core.camera.discovery.nativebackend.NativeHardwareLevel
import com.sahidcode404.camex.core.camera.discovery.nativebackend.NativeLensFacing
import com.sahidcode404.camex.core.camera.discovery.nativebackend.NativeMinimalCameraMetadata
import com.sahidcode404.camex.core.camera.discovery.nativebackend.NativeRawStreamFormat
import com.sahidcode404.camex.core.camera.topology.CameraDiscoverySource
import com.sahidcode404.camex.core.camera.topology.CameraMetadataTrust
import com.sahidcode404.camex.core.camera.topology.CameraRouteEvidence
import com.sahidcode404.camex.core.camera.topology.CameraRouteKind
import com.sahidcode404.camex.core.camera.topology.CameraRouteTrust
import com.sahidcode404.camex.core.camera.topology.FullCameraCapabilities
import com.sahidcode404.camex.core.camera.topology.MinimalCameraMetadata
import com.sahidcode404.camex.core.model.CameraCapability
import com.sahidcode404.camex.core.model.CapabilitySupport
import com.sahidcode404.camex.core.model.ColorFilterArrangement
import com.sahidcode404.camex.core.model.HardwareLevel
import com.sahidcode404.camex.core.model.LensCapabilities
import com.sahidcode404.camex.core.model.LensFacing
import com.sahidcode404.camex.core.model.PhysicalSize
import com.sahidcode404.camex.core.model.SensorRect
import com.sahidcode404.camex.core.model.Size2D
import com.sahidcode404.camex.core.model.StreamFormat

internal fun NativeDiscoveryResult.toRouteEvidence(): List<CameraRouteEvidence> = cameras.flatMap { camera ->
    camera.sources.ifEmpty { setOf(source) }
        .sortedBy { it.ordinal }
        .flatMap { nativeSource ->
            listOf(camera.toRouteEvidence(nativeSource)) +
                camera.toPhysicalRouteEvidence(nativeSource)
        }
}

/**
 * NDK logical metadata can reveal physical members that Java does not. Preserve those addresses
 * even before child characteristics are available; sparse routes remain diagnostics-only until a
 * Java/deep observation contributes credible photographic metadata.
 */
private fun NativeMinimalCameraMetadata.toPhysicalRouteEvidence(
    nativeSource: NativeDiscoverySource,
): List<CameraRouteEvidence> = physicalCameraIds
    .asSequence()
    .map(String::trim)
    .filter(String::isNotEmpty)
    .filterNot { it == cameraId }
    .distinct()
    .sorted()
    .map { physicalId ->
        CameraRouteEvidence(
            source = nativeSource.toTopologySource(),
            discoveredCameraId = physicalId,
            openCameraId = cameraId,
            streamPhysicalCameraId = physicalId,
            logicalParentCameraId = cameraId,
            routeKind = CameraRouteKind.LOGICAL_PHYSICAL_MEMBER,
            minimalMetadata = MinimalCameraMetadata(facing = facing.toModel()),
            trust = CameraRouteTrust(metadata = CameraMetadataTrust.DISCOVERED),
        )
    }
    .toList()

private fun NativeMinimalCameraMetadata.toRouteEvidence(
    nativeSource: NativeDiscoverySource,
): CameraRouteEvidence {
    val source = nativeSource.toTopologySource()
    val system = reportedCapabilities?.contains(NativeCameraCapability.SYSTEM_CAMERA) == true
    val depth = reportedCapabilities?.contains(NativeCameraCapability.DEPTH_OUTPUT) == true
    val monochrome = reportedCapabilities?.contains(NativeCameraCapability.MONOCHROME) == true ||
        colorFilterArrangement == NativeColorFilterArrangement.MONO
    val infrared = colorFilterArrangement == NativeColorFilterArrangement.NIR
    val logical = physicalCameraIds.isNotEmpty() ||
        reportedCapabilities?.contains(NativeCameraCapability.LOGICAL_MULTI_CAMERA) == true
    val topologyKind = when {
        facing == NativeLensFacing.EXTERNAL -> CameraRouteKind.EXTERNAL
        nativeSource == NativeDiscoverySource.NDK_DEEP -> CameraRouteKind.DEEP_NDK_DIRECT
        logical -> CameraRouteKind.LOGICAL_PARENT
        else -> CameraRouteKind.NDK_DIRECT
    }
    val portableRawFormats = rawFormats.mapTo(linkedSetOf()) { it.toModel() }
    val reported = reportedCapabilities?.mapTo(linkedSetOf()) { it.toModel() }
    return CameraRouteEvidence(
        source = source,
        discoveredCameraId = cameraId,
        openCameraId = cameraId,
        routeKind = topologyKind,
        minimalMetadata = MinimalCameraMetadata(
            facing = facing.toModel(),
            focalLengthsMm = focalLengthsMm,
            sensorPhysicalSize = sensorPhysicalSizeMm?.let {
                PhysicalSize(it.width, it.height)
            },
            activeArray = activeArray?.let { SensorRect(it.left, it.top, it.right, it.bottom) },
            pixelArraySize = pixelArraySize?.let { Size2D(it.width, it.height) },
            sensorOrientationDegrees = sensorOrientationDegrees,
            hardwareLevel = hardwareLevel.toModel(),
            backwardCompatibleAdvertised = reportedCapabilities
                .containsSupport(NativeCameraCapability.BACKWARD_COMPATIBLE),
            rawCapabilityAdvertised = rawCapabilityAdvertised.asSupport(),
            rawStreamActuallyDeclared = rawStreamActuallyDeclared.asSupport(),
            rawFormats = portableRawFormats,
            rawSizes = rawSizes.map { Size2D(it.width, it.height) },
            previewStreamActuallyDeclared = previewStreamActuallyDeclared.asSupport(),
            privatePreviewSizes = privatePreviewSizes.map { Size2D(it.width, it.height) },
            yuvPreviewSizes = yuvPreviewSizes.map { Size2D(it.width, it.height) },
            depthEvidence = depth.asSupport(),
            infraredEvidence = infrared.asSupport(),
            monochromeEvidence = monochrome.asSupport(),
            systemCameraAdvertised = system.asSupport(),
        ),
        fullCapabilities = FullCameraCapabilities(
            capabilities = LensCapabilities(
                sensorOrientationDegrees = sensorOrientationDegrees,
                reportedCapabilities = reported,
                colorFilterArrangement = colorFilterArrangement?.toModel(),
            ),
            complete = false,
        ),
        trust = CameraRouteTrust(
            metadata = if (system) {
                CameraMetadataTrust.SYSTEM_ONLY
            } else {
                CameraMetadataTrust.METADATA_VALID
            },
        ),
    )
}

private fun NativeDiscoverySource.toTopologySource(): CameraDiscoverySource = when (this) {
    NativeDiscoverySource.NDK_ADVERTISED -> CameraDiscoverySource.NDK_ADVERTISED
    NativeDiscoverySource.NDK_DEEP -> CameraDiscoverySource.NDK_DEEP
}

private fun NativeLensFacing.toModel(): LensFacing = when (this) {
    NativeLensFacing.FRONT -> LensFacing.FRONT
    NativeLensFacing.BACK -> LensFacing.BACK
    NativeLensFacing.EXTERNAL -> LensFacing.EXTERNAL
    NativeLensFacing.UNKNOWN -> LensFacing.UNKNOWN
}

private fun NativeHardwareLevel.toModel(): HardwareLevel = when (this) {
    NativeHardwareLevel.LEGACY -> HardwareLevel.LEGACY
    NativeHardwareLevel.LIMITED -> HardwareLevel.LIMITED
    NativeHardwareLevel.FULL -> HardwareLevel.FULL
    NativeHardwareLevel.LEVEL_3 -> HardwareLevel.LEVEL_3
    NativeHardwareLevel.EXTERNAL -> HardwareLevel.EXTERNAL
    NativeHardwareLevel.UNKNOWN -> HardwareLevel.UNKNOWN
}

private fun NativeRawStreamFormat.toModel(): StreamFormat = when (this) {
    NativeRawStreamFormat.RAW_SENSOR -> StreamFormat.RAW_SENSOR
    NativeRawStreamFormat.RAW10 -> StreamFormat.RAW10
    NativeRawStreamFormat.RAW12 -> StreamFormat.RAW12
    NativeRawStreamFormat.RAW14 -> StreamFormat.RAW14
    NativeRawStreamFormat.RAW_PRIVATE -> StreamFormat.RAW_PRIVATE
}

private fun NativeCameraCapability.toModel(): CameraCapability = when (this) {
    NativeCameraCapability.BACKWARD_COMPATIBLE -> CameraCapability.BACKWARD_COMPATIBLE
    NativeCameraCapability.RAW -> CameraCapability.RAW
    NativeCameraCapability.LOGICAL_MULTI_CAMERA -> CameraCapability.LOGICAL_MULTI_CAMERA
    NativeCameraCapability.DEPTH_OUTPUT -> CameraCapability.DEPTH_OUTPUT
    NativeCameraCapability.MONOCHROME -> CameraCapability.MONOCHROME
    NativeCameraCapability.SYSTEM_CAMERA -> CameraCapability.SYSTEM_CAMERA
}

private fun NativeColorFilterArrangement.toModel(): ColorFilterArrangement = when (this) {
    NativeColorFilterArrangement.RGGB -> ColorFilterArrangement.RGGB
    NativeColorFilterArrangement.GRBG -> ColorFilterArrangement.GRBG
    NativeColorFilterArrangement.GBRG -> ColorFilterArrangement.GBRG
    NativeColorFilterArrangement.BGGR -> ColorFilterArrangement.BGGR
    NativeColorFilterArrangement.RGB -> ColorFilterArrangement.RGB
    NativeColorFilterArrangement.MONO -> ColorFilterArrangement.MONO
    NativeColorFilterArrangement.NIR -> ColorFilterArrangement.NIR
    NativeColorFilterArrangement.UNKNOWN -> ColorFilterArrangement.UNKNOWN
}

private fun Set<NativeCameraCapability>?.containsSupport(
    value: NativeCameraCapability,
): CapabilitySupport = when {
    this == null -> CapabilitySupport.UNKNOWN
    value in this -> CapabilitySupport.SUPPORTED
    else -> CapabilitySupport.UNSUPPORTED
}

private fun Boolean?.asSupport(): CapabilitySupport = when (this) {
    true -> CapabilitySupport.SUPPORTED
    false -> CapabilitySupport.UNSUPPORTED
    null -> CapabilitySupport.UNKNOWN
}

private fun Boolean.asSupport(): CapabilitySupport =
    if (this) CapabilitySupport.SUPPORTED else CapabilitySupport.UNSUPPORTED
