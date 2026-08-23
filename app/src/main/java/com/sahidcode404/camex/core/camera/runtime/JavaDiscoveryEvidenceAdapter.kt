package com.sahidcode404.camex.core.camera.runtime

import com.sahidcode404.camex.core.camera.discovery.JavaDiscoverySource
import com.sahidcode404.camex.core.camera.discovery.JavaMinimalCameraMetadata
import com.sahidcode404.camex.core.camera.topology.CameraDiscoverySource
import com.sahidcode404.camex.core.camera.topology.CameraMetadataTrust
import com.sahidcode404.camex.core.camera.topology.CameraRouteEvidence
import com.sahidcode404.camex.core.camera.topology.CameraRouteKind
import com.sahidcode404.camex.core.camera.topology.CameraRouteTrust
import com.sahidcode404.camex.core.camera.topology.FullCameraCapabilities
import com.sahidcode404.camex.core.camera.topology.MinimalCameraMetadata
import com.sahidcode404.camex.core.camera.topology.toCameraRoute
import com.sahidcode404.camex.core.model.CapabilitySupport
import com.sahidcode404.camex.core.model.LensDescriptor
import com.sahidcode404.camex.core.model.LensFacing
import com.sahidcode404.camex.core.model.LensNodeKind

internal fun JavaMinimalCameraMetadata.toRouteEvidence(
    source: JavaDiscoverySource,
): CameraRouteEvidence {
    val topologySource = source.toTopologySource()
    val kind = when {
        facing == LensFacing.EXTERNAL -> CameraRouteKind.EXTERNAL
        identity.streamPhysicalCameraId != null -> CameraRouteKind.LOGICAL_PHYSICAL_MEMBER
        identity.nodeKind == LensNodeKind.LOGICAL -> CameraRouteKind.LOGICAL_PARENT
        else -> CameraRouteKind.PUBLIC_DIRECT
    }
    return CameraRouteEvidence(
        source = topologySource,
        discoveredCameraId = identity.streamPhysicalCameraId ?: identity.publicCameraId,
        openCameraId = identity.openCameraId,
        streamPhysicalCameraId = identity.streamPhysicalCameraId,
        logicalParentCameraId = identity.logicalParentCameraId,
        routeKind = kind,
        minimalMetadata = MinimalCameraMetadata(
            facing = facing,
            focalLengthsMm = focalLengthsMm.orEmpty(),
            sensorPhysicalSize = sensorPhysicalSize,
            activeArray = activeArray,
            pixelArraySize = pixelArraySize,
            sensorOrientationDegrees = sensorOrientationDegrees,
            hardwareLevel = hardwareLevel,
            backwardCompatibleAdvertised = backwardCompatible,
            rawCapabilityAdvertised = rawCapabilityAdvertised.asSupport(),
            rawStreamActuallyDeclared = rawStreamActuallyDeclared.asPositiveEvidence(),
            previewStreamActuallyDeclared = previewStreamActuallyDeclared.asPositiveEvidence(),
            privatePreviewSizes = privatePreviewSizes,
            depthEvidence = depthOnly.asPositiveEvidence(),
            systemCameraAdvertised = systemOnly.asPositiveEvidence(),
        ),
        trust = CameraRouteTrust(
            metadata = when {
                systemOnly -> CameraMetadataTrust.SYSTEM_ONLY
                crediblePhotographicCandidate || depthOnly -> CameraMetadataTrust.METADATA_VALID
                else -> CameraMetadataTrust.DISCOVERED
            },
        ),
    )
}

internal fun LensDescriptor.toEnrichedRouteEvidence(
    source: JavaDiscoverySource,
): CameraRouteEvidence {
    val topologySource = source.toTopologySource()
    val route = toCameraRoute(source = topologySource)
    return CameraRouteEvidence(
        source = topologySource,
        discoveredCameraId = route.discoveredCameraId,
        openCameraId = route.openCameraId,
        streamPhysicalCameraId = route.streamPhysicalCameraId,
        logicalParentCameraId = route.logicalParentCameraId,
        routeKind = route.routeKind,
        minimalMetadata = route.minimalMetadata,
        fullCapabilities = FullCameraCapabilities(capabilities, complete = true),
        lensFingerprint = route.lensFingerprint,
        trust = route.trust,
    )
}

private fun JavaDiscoverySource.toTopologySource(): CameraDiscoverySource = when (this) {
    JavaDiscoverySource.JAVA_PUBLIC -> CameraDiscoverySource.JAVA_PUBLIC
    JavaDiscoverySource.JAVA_PHYSICAL -> CameraDiscoverySource.JAVA_PHYSICAL
}

private fun Boolean?.asSupport(): CapabilitySupport = when (this) {
    true -> CapabilitySupport.SUPPORTED
    false -> CapabilitySupport.UNSUPPORTED
    null -> CapabilitySupport.UNKNOWN
}

/** False is not negative evidence here: minimal extraction may have lacked a stream map. */
private fun Boolean.asPositiveEvidence(): CapabilitySupport =
    if (this) CapabilitySupport.SUPPORTED else CapabilitySupport.UNKNOWN
