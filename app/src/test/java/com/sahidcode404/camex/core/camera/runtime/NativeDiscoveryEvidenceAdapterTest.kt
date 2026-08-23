package com.sahidcode404.camex.core.camera.runtime

import com.sahidcode404.camex.core.camera.discovery.nativebackend.NativeCameraCapability
import com.sahidcode404.camex.core.camera.discovery.nativebackend.NativeDiscoveryResult
import com.sahidcode404.camex.core.camera.discovery.nativebackend.NativeDiscoverySource
import com.sahidcode404.camex.core.camera.discovery.nativebackend.NativeLensFacing
import com.sahidcode404.camex.core.camera.discovery.nativebackend.NativeMinimalCameraMetadata
import com.sahidcode404.camex.core.camera.topology.CameraDiscoverySource
import com.sahidcode404.camex.core.camera.topology.CameraMetadataTrust
import com.sahidcode404.camex.core.camera.topology.CameraRouteKind
import com.sahidcode404.camex.core.model.LensFacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeDiscoveryEvidenceAdapterTest {
    @Test
    fun `NDK logical metadata preserves sparse physical routes without inventing child optics`() {
        val evidence = NativeDiscoveryResult(
            source = NativeDiscoverySource.NDK_ADVERTISED,
            cameras = listOf(
                NativeMinimalCameraMetadata(
                    cameraId = "logical",
                    sources = setOf(NativeDiscoverySource.NDK_ADVERTISED),
                    facing = NativeLensFacing.BACK,
                    reportedCapabilities = setOf(
                        NativeCameraCapability.BACKWARD_COMPATIBLE,
                        NativeCameraCapability.LOGICAL_MULTI_CAMERA,
                    ),
                    physicalCameraIds = listOf("tele", "wide", "tele", "logical"),
                ),
            ),
        ).toRouteEvidence()

        assertEquals(3, evidence.size)
        val parent = evidence.single { it.discoveredCameraId == "logical" }
        assertEquals(CameraRouteKind.LOGICAL_PARENT, parent.routeKind)

        val children = evidence.filter { it.streamPhysicalCameraId != null }
        assertEquals(listOf("tele", "wide"), children.map { it.discoveredCameraId })
        children.forEach { child ->
            assertEquals("logical", child.openCameraId)
            assertEquals("logical", child.logicalParentCameraId)
            assertEquals(CameraRouteKind.LOGICAL_PHYSICAL_MEMBER, child.routeKind)
            assertEquals(setOf(CameraDiscoverySource.NDK_ADVERTISED), setOf(child.source))
            assertEquals(LensFacing.BACK, child.minimalMetadata.facing)
            assertEquals(CameraMetadataTrust.DISCOVERED, child.trust.metadata)
            assertTrue(child.minimalMetadata.focalLengthsMm.isEmpty())
            assertTrue(child.minimalMetadata.rawSizes.isEmpty())
        }
    }
}
