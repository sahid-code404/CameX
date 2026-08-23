package com.sahidcode404.camex.core.camera.topology

import com.sahidcode404.camex.core.model.LensDescriptor
import com.sahidcode404.camex.core.model.LensFingerprint

/** Build a runtime descriptor for one transport profile while retaining optical lens identity. */
fun CameraProfile.toLensDescriptor(
    opticalFingerprint: LensFingerprint,
    role: PhotographicRole,
    roleConfidence: RoleConfidence,
): LensDescriptor = CameraRoute(
    canonicalRouteId = profileId,
    discoveredCameraId = discoveredCameraId,
    openCameraId = openCameraId,
    streamPhysicalCameraId = streamPhysicalCameraId,
    logicalParentCameraId = logicalParentCameraId,
    routeKind = routeKind,
    sources = discoverySources,
    minimalMetadata = metadata,
    fullCapabilities = fullCapabilities,
    lensFingerprint = opticalFingerprint,
    role = role,
    roleConfidence = roleConfidence,
    trust = trust,
).toLensDescriptor().copy(fingerprint = opticalFingerprint)

/** All profile descriptors share one optical fingerprint, which is the failover grouping key. */
fun CameraRoute.profileLensDescriptors(): List<LensDescriptor> {
    val opticalFingerprint = lensFingerprint ?: return listOf(toLensDescriptor())
    return profiles.map { profile ->
        profile.toLensDescriptor(opticalFingerprint, role, roleConfidence)
    }
}
