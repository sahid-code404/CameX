package com.sahidcode404.camex.core.camera.discovery

import com.sahidcode404.camex.core.camera.LogicalCameraRelationship
import com.sahidcode404.camex.core.camera.topology.CameraDiscoverySource
import com.sahidcode404.camex.core.camera.topology.CameraRouteEvidence
import com.sahidcode404.camex.core.camera.topology.CameraRouteKind

/**
 * Preserves logical-to-physical routing even when a vendor withholds child characteristics.
 * Rich Java child metadata, when available, replaces this sparse observation during resolution.
 */
object PhysicalCameraTopologyBackend {
    fun evidence(relationship: LogicalCameraRelationship): List<CameraRouteEvidence> =
        relationship.physicalCameraIds
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .sorted()
            .map { physicalId ->
                CameraRouteEvidence(
                    source = CameraDiscoverySource.JAVA_PHYSICAL,
                    discoveredCameraId = physicalId,
                    openCameraId = relationship.logicalCameraId,
                    streamPhysicalCameraId = physicalId,
                    logicalParentCameraId = relationship.logicalCameraId,
                    routeKind = CameraRouteKind.LOGICAL_PHYSICAL_MEMBER,
                )
            }
            .toList()
}
