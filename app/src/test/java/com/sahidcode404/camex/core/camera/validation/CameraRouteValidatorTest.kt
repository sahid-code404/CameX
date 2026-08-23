package com.sahidcode404.camex.core.camera.validation

import com.sahidcode404.camex.core.camera.CameraSessionEvent
import com.sahidcode404.camex.core.camera.topology.CameraDiscoverySource
import com.sahidcode404.camex.core.camera.topology.CameraFailureDurability
import com.sahidcode404.camex.core.camera.topology.CameraMetadataTrust
import com.sahidcode404.camex.core.camera.topology.CameraRawTrust
import com.sahidcode404.camex.core.camera.topology.CameraRoute
import com.sahidcode404.camex.core.camera.topology.CameraRouteKind
import com.sahidcode404.camex.core.camera.topology.CameraRouteTrust
import com.sahidcode404.camex.core.camera.topology.CameraSessionTrust
import com.sahidcode404.camex.core.camera.topology.MinimalCameraMetadata
import com.sahidcode404.camex.core.model.ProbeFailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraRouteValidatorTest {
    @Test
    fun `first delivered frame verifies only the selected session dimension`() {
        val route = route().copy(
            trust = CameraRouteTrust(
                metadata = CameraMetadataTrust.DISCOVERED,
                raw = CameraRawTrust.RAW_VERIFIED,
            ),
        )

        val result = CameraRouteValidator.observation(
            route,
            CameraSessionEvent.PreviewVerified("0"),
        )!!

        assertEquals(CameraMetadataTrust.METADATA_VALID, result.metadata)
        assertEquals(CameraSessionTrust.SESSION_VERIFIED, result.session)
        assertEquals(CameraRawTrust.RAW_VERIFIED, result.raw)
    }

    @Test
    fun `deterministic invalid metadata is structural while timeout remains transient`() {
        val structural = CameraRouteValidator.observation(
            route(),
            CameraSessionEvent.PreviewFailed(
                routingKey = "0",
                kind = ProbeFailureKind.INVALID_METADATA,
                structural = true,
                detail = "No PRIVATE output",
            ),
        )!!
        val transient = CameraRouteValidator.observation(
            route(),
            CameraSessionEvent.PreviewFailed(
                routingKey = "0",
                kind = ProbeFailureKind.TIMEOUT,
                structural = false,
                detail = "Timed out",
            ),
        )!!

        assertEquals(CameraMetadataTrust.METADATA_REJECTED, structural.metadata)
        assertEquals(CameraSessionTrust.SESSION_REJECTED, structural.session)
        assertEquals(CameraFailureDurability.STRUCTURAL, structural.failure?.durability)
        assertEquals(CameraSessionTrust.TRANSIENT_FAILURE, transient.session)
        assertEquals(CameraFailureDurability.TRANSIENT, transient.failure?.durability)
    }

    @Test
    fun `permission and intermediate session events never poison route trust`() {
        assertNull(
            CameraRouteValidator.observation(
                route(),
                CameraSessionEvent.PreviewFailed(
                    routingKey = "0",
                    kind = ProbeFailureKind.PERMISSION_DENIED,
                    structural = false,
                    detail = "Permission missing",
                ),
            ),
        )
        assertNull(CameraRouteValidator.observation(route(), CameraSessionEvent.CameraOpened("0")))
        assertNull(
            CameraRouteValidator.observation(route(), CameraSessionEvent.SessionConfigured("0")),
        )
    }

    private fun route() = CameraRoute(
        canonicalRouteId = "cr1_1:0|0:",
        discoveredCameraId = "0",
        openCameraId = "0",
        routeKind = CameraRouteKind.PUBLIC_DIRECT,
        sources = setOf(CameraDiscoverySource.JAVA_PUBLIC),
        minimalMetadata = MinimalCameraMetadata(),
    )
}
