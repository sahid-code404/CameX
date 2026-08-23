package com.sahidcode404.camex.core.camera.topology

import com.sahidcode404.camex.core.model.CapabilitySupport
import com.sahidcode404.camex.core.model.ColorFilterArrangement
import com.sahidcode404.camex.core.model.LensCapabilities
import com.sahidcode404.camex.core.model.LensFacing
import com.sahidcode404.camex.core.model.PhysicalSize
import com.sahidcode404.camex.core.model.SensorRect
import com.sahidcode404.camex.core.model.Size2D
import com.sahidcode404.camex.core.model.StreamFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalLensProfileTest {
    @Test
    fun `main vendor aliases become one optical lens with three profiles`() {
        val metadata = opticalMetadata(focal = 5.15)
        val topology = resolve(
            route("0", CameraDiscoverySource.JAVA_PUBLIC, CameraRouteKind.PUBLIC_DIRECT, metadata),
            route("100", CameraDiscoverySource.NDK_DEEP, CameraRouteKind.DEEP_NDK_DIRECT, metadata),
            CameraRouteEvidence(
                source = CameraDiscoverySource.JAVA_PHYSICAL,
                discoveredCameraId = "0",
                openCameraId = "61",
                streamPhysicalCameraId = "0",
                logicalParentCameraId = "61",
                routeKind = CameraRouteKind.LOGICAL_PHYSICAL_MEMBER,
                minimalMetadata = metadata,
                fullCapabilities = fullCapabilities(),
            ),
        )

        assertEquals(1, topology.routes.size)
        val lens = topology.routes.single()
        assertEquals(3, lens.profiles.size)
        assertEquals(1, topology.canonicalLenses.size)
        assertEquals(3, topology.canonicalLenses.single().profiles.size)
        assertTrue(lens.lensFingerprint?.value.orEmpty().startsWith("ol3_"))
    }

    @Test
    fun `front vendor alias becomes one optical lens with two profiles`() {
        val metadata = opticalMetadata(focal = 3.7, facing = LensFacing.FRONT)
        val topology = resolve(
            route("1", CameraDiscoverySource.JAVA_PUBLIC, CameraRouteKind.PUBLIC_DIRECT, metadata),
            route("101", CameraDiscoverySource.NDK_DEEP, CameraRouteKind.DEEP_NDK_DIRECT, metadata),
        )

        assertEquals(1, topology.routes.size)
        assertEquals(2, topology.routes.single().profiles.size)
        assertEquals(LensFacing.FRONT, topology.routes.single().minimalMetadata.facing)
    }

    @Test
    fun `different focal lengths remain separate physical lenses`() {
        val topology = resolve(
            route("21", CameraDiscoverySource.NDK_DEEP, CameraRouteKind.DEEP_NDK_DIRECT, opticalMetadata(2.4)),
            route("22", CameraDiscoverySource.NDK_DEEP, CameraRouteKind.DEEP_NDK_DIRECT, opticalMetadata(5.0)),
        )

        assertEquals(2, topology.routes.size)
        assertNotEquals(
            topology.routes[0].lensFingerprint,
            topology.routes[1].lensFingerprint,
        )
    }

    @Test
    fun `same focal length with different sensor geometry remains separate`() {
        val first = opticalMetadata(focal = 5.0, sensor = PhysicalSize(7.2, 5.4))
        val second = opticalMetadata(focal = 5.0, sensor = PhysicalSize(5.6, 4.2))
        val topology = resolve(
            route("a", CameraDiscoverySource.JAVA_PUBLIC, CameraRouteKind.PUBLIC_DIRECT, first),
            route("b", CameraDiscoverySource.NDK_DEEP, CameraRouteKind.DEEP_NDK_DIRECT, second),
        )

        assertEquals(2, topology.routes.size)
    }

    @Test
    fun `independent public routes with only identical sparse optics stay conservative`() {
        val metadata = opticalMetadata(5.0)
        val topology = resolve(
            CameraRouteEvidence(
                source = CameraDiscoverySource.JAVA_PUBLIC,
                discoveredCameraId = "public-a",
                routeKind = CameraRouteKind.PUBLIC_DIRECT,
                minimalMetadata = metadata,
            ),
            CameraRouteEvidence(
                source = CameraDiscoverySource.JAVA_PUBLIC,
                discoveredCameraId = "public-b",
                routeKind = CameraRouteKind.PUBLIC_DIRECT,
                minimalMetadata = metadata,
            ),
        )

        assertEquals(2, topology.routes.size)
    }

    @Test
    fun `structural failure on one profile keeps lens usable when sibling verifies`() {
        val metadata = opticalMetadata(5.1)
        val failed = route(
            "profile-a",
            CameraDiscoverySource.JAVA_PUBLIC,
            CameraRouteKind.PUBLIC_DIRECT,
            metadata,
            trust = structuralFailure(),
        )
        val working = route(
            "profile-b",
            CameraDiscoverySource.NDK_DEEP,
            CameraRouteKind.DEEP_NDK_DIRECT,
            metadata,
            trust = CameraRouteTrust(
                metadata = CameraMetadataTrust.METADATA_VALID,
                session = CameraSessionTrust.SESSION_VERIFIED,
            ),
        )

        val lens = resolve(failed, working).routes.single()

        assertEquals(CameraSessionTrust.SESSION_VERIFIED, lens.trust.session)
        assertEquals("profile-b", lens.openCameraId)
        assertEquals(2, lens.profiles.size)
        assertTrue(lens.toLensDescriptor().usability.isSelectable)
        assertEquals(
            CameraSessionTrust.SESSION_REJECTED,
            lens.profiles.single { it.openCameraId == "profile-a" }.sessionTrust,
        )
    }

    @Test
    fun `transient profile failure never permanently rejects optical lens`() {
        val metadata = opticalMetadata(5.1)
        val temporary = route(
            "profile-a",
            CameraDiscoverySource.JAVA_PUBLIC,
            CameraRouteKind.PUBLIC_DIRECT,
            metadata,
            trust = CameraRouteTrust(
                metadata = CameraMetadataTrust.METADATA_VALID,
                session = CameraSessionTrust.TRANSIENT_FAILURE,
                failure = CameraRouteFailure(
                    CameraRouteFailureKind.SERVICE_ERROR,
                    CameraFailureDurability.TRANSIENT,
                    "temporary service problem",
                ),
            ),
        )
        val untested = route(
            "profile-b",
            CameraDiscoverySource.NDK_DEEP,
            CameraRouteKind.DEEP_NDK_DIRECT,
            metadata,
        )

        val lens = resolve(temporary, untested).routes.single()

        assertFalse(lens.trust.session == CameraSessionTrust.SESSION_REJECTED)
        assertTrue(lens.toLensDescriptor().usability.isSelectable)
    }

    @Test
    fun `all structurally rejected profiles make optical lens unavailable`() {
        val metadata = opticalMetadata(5.1)
        val lens = resolve(
            route(
                "profile-a",
                CameraDiscoverySource.JAVA_PUBLIC,
                CameraRouteKind.PUBLIC_DIRECT,
                metadata,
                trust = structuralFailure(),
            ),
            route(
                "profile-b",
                CameraDiscoverySource.NDK_DEEP,
                CameraRouteKind.DEEP_NDK_DIRECT,
                metadata,
                trust = structuralFailure(),
            ),
        ).routes.single()

        assertTrue(lens.profiles.all(CameraProfile::structurallyRejected))
        assertEquals(CameraSessionTrust.SESSION_REJECTED, lens.trust.session)
        assertFalse(lens.toLensDescriptor().usability.isSelectable)
    }

    @Test
    fun `verified profile is sticky selector winner over rejected alias`() {
        val metadata = opticalMetadata(5.1)
        val lens = resolve(
            route(
                "known-good",
                CameraDiscoverySource.NDK_DEEP,
                CameraRouteKind.DEEP_NDK_DIRECT,
                metadata,
                trust = CameraRouteTrust(
                    metadata = CameraMetadataTrust.METADATA_VALID,
                    session = CameraSessionTrust.SESSION_VERIFIED,
                ),
            ),
            route(
                "rejected",
                CameraDiscoverySource.JAVA_PUBLIC,
                CameraRouteKind.PUBLIC_DIRECT,
                metadata,
                trust = structuralFailure(),
            ),
        ).routes.single()

        assertEquals("known-good", lens.openCameraId)
        assertEquals(
            "known-good",
            CameraProfileSelector.select(lens.profiles)?.openCameraId,
        )
        assertEquals(
            "known-good",
            lens.profileLensDescriptors().first().identity.openCameraId,
        )
    }

    @Test
    fun `zoom inputs exist once per canonical optical lens rather than once per profile`() {
        val metadata = opticalMetadata(5.15)
        val topology = resolve(
            route("0", CameraDiscoverySource.JAVA_PUBLIC, CameraRouteKind.PUBLIC_DIRECT, metadata),
            route("100", CameraDiscoverySource.NDK_DEEP, CameraRouteKind.DEEP_NDK_DIRECT, metadata),
        )

        assertEquals(1, topology.routes.size)
        assertEquals(2, topology.routes.single().profiles.size)
        assertEquals(1, topology.routes.map(CameraRoute::toLensDescriptor).size)
    }

    private fun resolve(vararg evidence: CameraRouteEvidence): CameraTopology =
        CameraTopologyResolver.resolve(environment, evidence.toList())

    private fun route(
        id: String,
        source: CameraDiscoverySource,
        kind: CameraRouteKind,
        metadata: MinimalCameraMetadata,
        trust: CameraRouteTrust = CameraRouteTrust(metadata = CameraMetadataTrust.METADATA_VALID),
    ) = CameraRouteEvidence(
        source = source,
        discoveredCameraId = id,
        openCameraId = id,
        routeKind = kind,
        minimalMetadata = metadata,
        fullCapabilities = fullCapabilities(),
        trust = trust,
    )

    private fun structuralFailure() = CameraRouteTrust(
        metadata = CameraMetadataTrust.METADATA_VALID,
        session = CameraSessionTrust.SESSION_REJECTED,
        failure = CameraRouteFailure(
            CameraRouteFailureKind.SESSION_CONFIGURATION_UNSUPPORTED,
            CameraFailureDurability.STRUCTURAL,
            "profile route cannot configure preview",
        ),
    )

    private fun opticalMetadata(
        focal: Double,
        facing: LensFacing = LensFacing.BACK,
        sensor: PhysicalSize = PhysicalSize(7.2, 5.4),
    ) = MinimalCameraMetadata(
        facing = facing,
        focalLengthsMm = listOf(focal),
        sensorPhysicalSize = sensor,
        activeArray = SensorRect(0, 0, 4000, 3000),
        pixelArraySize = Size2D(4000, 3000),
        sensorOrientationDegrees = 90,
        backwardCompatibleAdvertised = CapabilitySupport.SUPPORTED,
        rawCapabilityAdvertised = CapabilitySupport.SUPPORTED,
        rawStreamActuallyDeclared = CapabilitySupport.SUPPORTED,
        rawFormats = setOf(StreamFormat.RAW_SENSOR),
        rawSizes = listOf(Size2D(4000, 3000)),
        previewStreamActuallyDeclared = CapabilitySupport.SUPPORTED,
        privatePreviewSizes = listOf(Size2D(1920, 1080)),
    )

    private fun fullCapabilities() = FullCameraCapabilities(
        capabilities = LensCapabilities(
            sensorOrientationDegrees = 90,
            apertures = listOf(1.8),
            colorFilterArrangement = ColorFilterArrangement.RGGB,
        ),
        complete = false,
    )

    private companion object {
        val environment = CameraEnvironmentFingerprint(
            buildFingerprint = "vendor/device/build:phase1b",
            apiLevel = 35,
        )
    }
}
