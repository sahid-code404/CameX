package com.sahidcode404.camex.core.camera.topology

import com.sahidcode404.camex.core.model.CapabilitySupport
import com.sahidcode404.camex.core.model.LensCategory
import com.sahidcode404.camex.core.model.LensFacing
import com.sahidcode404.camex.core.model.LensUsability
import com.sahidcode404.camex.core.model.PhysicalSize
import com.sahidcode404.camex.core.model.SensorRect
import com.sahidcode404.camex.core.model.Size2D
import com.sahidcode404.camex.core.model.StreamFormat
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraTopologyResolverTest {
    @Test
    fun `Java-only camera becomes one metadata-valid canonical route`() {
        val topology = resolve(java("0", metadata()))

        assertEquals(1, topology.routes.size)
        with(topology.routes.single()) {
            assertEquals(setOf(CameraDiscoverySource.JAVA_PUBLIC), sources)
            assertEquals(CameraMetadataTrust.METADATA_VALID, trust.metadata)
            assertTrue(role.name.startsWith("PHOTOGRAPHIC_"))
            assertNotNull(lensFingerprint)
        }
    }

    @Test
    fun `NDK-only and deep-only cameras remain application candidates`() {
        val topology = resolve(
            ndk("3", metadata(focal = 2.0)),
            deep("7", metadata(focal = 8.0)),
        )

        assertEquals(2, topology.routes.size)
        assertEquals(
            setOf(CameraDiscoverySource.NDK_ADVERTISED, CameraDiscoverySource.NDK_DEEP),
            topology.routes.flatMapTo(mutableSetOf(), CameraRoute::sources),
        )
        assertTrue(topology.routes.all { it.trust.metadata == CameraMetadataTrust.METADATA_VALID })
    }

    @Test
    fun `logical and physical members preserve explicit routing and relationships`() {
        val topology = resolve(
            java("0", metadata(), CameraRouteKind.LOGICAL_PARENT),
            CameraRouteEvidence(
                source = CameraDiscoverySource.JAVA_PHYSICAL,
                discoveredCameraId = "2",
                openCameraId = "2", // Resolver must correct this to the known parent.
                streamPhysicalCameraId = "2",
                logicalParentCameraId = "0",
                routeKind = CameraRouteKind.LOGICAL_PHYSICAL_MEMBER,
                minimalMetadata = metadata(focal = 2.0),
            ),
        )

        val physical = topology.routes.single { it.streamPhysicalCameraId == "2" }
        assertEquals("0", physical.openCameraId)
        assertEquals("0", physical.logicalParentCameraId)
        assertEquals(
            listOf(LogicalCameraRelationship("0", listOf("2"))),
            topology.logicalRelationships,
        )
    }

    @Test
    fun `Java and NDK observations for the same route merge evidence without duplicate`() {
        val topology = resolve(
            java("0", metadata(raw = CapabilitySupport.UNKNOWN)),
            ndk("0", metadata(raw = CapabilitySupport.SUPPORTED)),
        )

        assertEquals(1, topology.routes.size)
        assertEquals(
            setOf(CameraDiscoverySource.JAVA_PUBLIC, CameraDiscoverySource.NDK_ADVERTISED),
            topology.routes.single().sources,
        )
        assertEquals(
            CapabilitySupport.SUPPORTED,
            topology.routes.single().minimalMetadata.rawStreamActuallyDeclared,
        )
    }

    @Test
    fun `AUX found only by NDK is added to Java topology`() {
        val topology = resolve(
            java("0", metadata(focal = 5.0)),
            ndk("4", metadata(focal = 2.0)),
        )

        assertEquals(setOf("0", "4"), topology.routes.mapTo(mutableSetOf()) { it.openCameraId })
    }

    @Test
    fun `AUX found only by bounded deep scan is retained`() {
        val topology = resolve(
            java("0", metadata()),
            ndk("0", metadata()),
            deep("6", metadata(focal = 9.0)),
        )

        val aux = topology.routes.single { it.openCameraId == "6" }
        assertEquals(setOf(CameraDiscoverySource.NDK_DEEP), aux.sources)
        assertTrue(aux.role.name.startsWith("PHOTOGRAPHIC_"))
    }

    @Test
    fun `direct and logical physical aliases for the same hardware camera merge losslessly`() {
        val optical = metadata(focal = 2.2, rawSizes = listOf(Size2D(4032, 3024)))
        val topology = resolve(
            ndk("2", optical),
            CameraRouteEvidence(
                source = CameraDiscoverySource.JAVA_PHYSICAL,
                discoveredCameraId = "2",
                openCameraId = "0",
                streamPhysicalCameraId = "2",
                logicalParentCameraId = "0",
                routeKind = CameraRouteKind.LOGICAL_PHYSICAL_MEMBER,
                minimalMetadata = optical,
            ),
        )

        assertEquals(1, topology.routes.size)
        val route = topology.routes.single()
        assertEquals(1, route.aliases.size)
        val allAddresses = (route.aliases.map { it.openCameraId to it.streamPhysicalCameraId } +
            (route.openCameraId to route.streamPhysicalCameraId)).toSet()
        assertEquals(setOf("2" to null, "0" to "2"), allAddresses)
    }

    @Test
    fun `different profile IDs with identical strong optics become one canonical lens`() {
        val sameMetadata = metadata(focal = 4.7, rawSizes = listOf(Size2D(4000, 3000)))
        val topology = resolve(java("2", sameMetadata), java("3", sameMetadata))

        assertEquals(1, topology.routes.size)
        val canonicalLens = topology.routes.single()
        assertEquals(2, canonicalLens.profiles.size)
        assertEquals(setOf("2", "3"), canonicalLens.profiles.mapTo(mutableSetOf()) { it.openCameraId })
        assertEquals(1, topology.canonicalLenses.size)
        assertEquals(canonicalLens.lensFingerprint, topology.canonicalLenses.single().lensFingerprint)
    }

    @Test
    fun `unknown photographic AUX is selectable before session validation`() {
        val sparseRaw = MinimalCameraMetadata(
            facing = LensFacing.BACK,
            focalLengthsMm = listOf(4.72),
            rawStreamActuallyDeclared = CapabilitySupport.SUPPORTED,
            rawFormats = setOf(StreamFormat.RAW10),
        )
        val route = resolve(deep("5", sparseRaw)).routes.single()
        val lens = route.toLensDescriptor()

        assertEquals(PhotographicRole.PHOTOGRAPHIC_UNKNOWN, route.role)
        assertEquals(CameraMetadataTrust.METADATA_VALID, route.trust.metadata)
        assertEquals(LensCategory.PHOTOGRAPHIC_UNKNOWN, lens.category)
        assertEquals(LensUsability.PHOTOGRAPHIC_CANDIDATE, lens.usability)
        assertTrue(lens.usability.isSelectable)
    }

    @Test
    fun `declared RAW stream remains photographic when redundant capability flag is false`() {
        val contradictory = metadata().copy(
            rawCapabilityAdvertised = CapabilitySupport.UNSUPPORTED,
            rawStreamActuallyDeclared = CapabilitySupport.SUPPORTED,
        )

        val route = resolve(deep("14", contradictory)).routes.single()

        assertEquals(CapabilitySupport.UNSUPPORTED, route.minimalMetadata.rawCapabilityAdvertised)
        assertEquals(CapabilitySupport.SUPPORTED, route.minimalMetadata.rawStreamActuallyDeclared)
        assertTrue(route.role.name.startsWith("PHOTOGRAPHIC_"))
        assertEquals(CameraMetadataTrust.METADATA_VALID, route.trust.metadata)
    }

    @Test
    fun `depth ToF and IR evidence remain diagnostics-only`() {
        val depth = deep(
            "10",
            MinimalCameraMetadata(depthEvidence = CapabilitySupport.SUPPORTED),
        )
        val tof = deep(
            "11",
            MinimalCameraMetadata(tofEvidence = CapabilitySupport.SUPPORTED),
        )
        val ir = deep(
            "12",
            metadata().copy(infraredEvidence = CapabilitySupport.SUPPORTED),
        )
        val roles = resolve(depth, tof, ir).routes.associate { it.openCameraId to it.role }

        assertEquals(PhotographicRole.NON_PHOTO_DEPTH, roles["10"])
        assertEquals(PhotographicRole.NON_PHOTO_TOF, roles["11"])
        assertEquals(PhotographicRole.NON_PHOTO_IR, roles["12"])
    }

    @Test
    fun `system-only and access-denied routes are retained for diagnostics but not selectable`() {
        val system = java(
            "20",
            metadata().copy(systemCameraAdvertised = CapabilitySupport.SUPPORTED),
        )
        val denied = CameraRouteEvidence(
            source = CameraDiscoverySource.NDK_DEEP,
            discoveredCameraId = "21",
            routeKind = CameraRouteKind.DEEP_NDK_DIRECT,
            trust = CameraRouteTrust(metadata = CameraMetadataTrust.ACCESS_DENIED),
        )
        val routes = resolve(system, denied).routes.associateBy(CameraRoute::openCameraId)

        assertEquals(PhotographicRole.SYSTEM_ONLY, routes.getValue("20").role)
        assertEquals(PhotographicRole.INACCESSIBLE, routes.getValue("21").role)
        assertFalse(routes.getValue("20").toLensDescriptor().usability.isSelectable)
        assertFalse(routes.getValue("21").toLensDescriptor().usability.isSelectable)
    }

    @Test
    fun `front and external facing survive topology and descriptor bridge`() {
        val front = java("1", metadata().copy(facing = LensFacing.FRONT))
        val external = CameraRouteEvidence(
            source = CameraDiscoverySource.JAVA_PUBLIC,
            discoveredCameraId = "usb:0",
            routeKind = CameraRouteKind.EXTERNAL,
            minimalMetadata = metadata().copy(facing = LensFacing.EXTERNAL),
        )
        val lenses = resolve(front, external).routes.map(CameraRoute::toLensDescriptor)

        assertEquals(setOf(LensFacing.FRONT, LensFacing.EXTERNAL), lenses.mapTo(mutableSetOf()) { it.facing })
        assertTrue(lenses.all { it.category.name.startsWith("PHOTOGRAPHIC_") })
    }

    @Test
    fun `missing metadata is unknown rather than broken or discarded`() {
        val route = resolve(deep("9", MinimalCameraMetadata())).routes.single()

        assertEquals(CameraMetadataTrust.DISCOVERED, route.trust.metadata)
        assertEquals(PhotographicRole.PHOTOGRAPHIC_UNKNOWN, route.role)
        assertFalse(route.role == PhotographicRole.BROKEN)
        assertFalse(route.hasCrediblePhotographicEvidence)
        assertEquals(LensUsability.UNKNOWN, route.toLensDescriptor().usability)
        assertFalse(route.toLensDescriptor().usability.isSelectable)
    }

    @Test
    fun `minimal orientation survives cache topology and first-preview projection`() {
        val route = resolve(
            java("seed", metadata().copy(sensorOrientationDegrees = 270)),
        ).routes.single()

        assertEquals(270, route.minimalMetadata.sensorOrientationDegrees)
        assertEquals(270, route.toLensDescriptor().capabilities.sensorOrientationDegrees)
    }

    @Test
    fun `malformed metadata is sanitized without crashing`() {
        val malformed = MinimalCameraMetadata(
            focalLengthsMm = listOf(Double.NaN, -1.0, Double.POSITIVE_INFINITY),
            sensorPhysicalSize = PhysicalSize(-1.0, Double.NaN),
            activeArray = SensorRect(4, 4, 2, 2),
            pixelArraySize = Size2D(-20, 0),
            rawSizes = listOf(Size2D(-1, 4)),
            privatePreviewSizes = listOf(Size2D(0, 0)),
        )
        val result = resolve(deep("8", malformed)).routes.single().minimalMetadata

        assertTrue(result.focalLengthsMm.isEmpty())
        assertNull(result.sensorPhysicalSize)
        assertNull(result.activeArray)
        assertNull(result.pixelArraySize)
        assertTrue(result.rawSizes.isEmpty())
        assertTrue(result.privatePreviewSizes.isEmpty())
    }

    @Test
    fun `resolver output is deterministic across input order`() {
        val evidence = listOf(
            java("0", metadata(focal = 5.0)),
            ndk("0", metadata(focal = 5.0)),
            deep("7", metadata(focal = 12.0)),
            java("1", metadata().copy(facing = LensFacing.FRONT)),
        )
        val expected = CameraTopologyResolver.resolve(environment, evidence)

        repeat(20) { seed ->
            assertEquals(
                expected,
                CameraTopologyResolver.resolve(environment, evidence.shuffled(Random(seed))),
            )
        }
    }

    @Test
    fun `incremental reconciliation retains cache while full reconciliation drops absent route`() {
        val cached = resolve(java("0", metadata()), deep("5", metadata(focal = 9.0)))
        val live = listOf(java("0", metadata()), java("6", metadata(focal = 2.0)))

        val incremental = CameraTopologyResolver.resolve(
            environment,
            live,
            cached,
            TopologyReconciliationMode.INCREMENTAL,
        )
        val complete = CameraTopologyResolver.resolve(
            environment,
            live,
            cached,
            TopologyReconciliationMode.FULLY_RECONCILED,
        )

        assertEquals(setOf("0", "5", "6"), incremental.routes.mapTo(mutableSetOf()) { it.openCameraId })
        assertEquals(setOf("0", "6"), complete.routes.mapTo(mutableSetOf()) { it.openCameraId })
    }

    @Test
    fun `firmware environment mismatch prevents stale cache bootstrap`() {
        val cached = resolve(deep("5", metadata()))
        val changedEnvironment = environment.copy(buildFingerprint = "vendor/device/build:2")

        val result = CameraTopologyResolver.resolve(
            changedEnvironment,
            evidence = emptyList(),
            cachedTopology = cached,
            mode = TopologyReconciliationMode.CACHE_BOOTSTRAP,
        )

        assertTrue(result.routes.isEmpty())
        assertNotEquals(cached.environmentFingerprint, result.environmentFingerprint)
    }

    @Test
    fun `bridge preserves route annotations while refreshing descriptor metadata`() {
        val original = resolve(
            CameraRouteEvidence(
                source = CameraDiscoverySource.JAVA_PHYSICAL,
                discoveredCameraId = "2",
                openCameraId = "0",
                streamPhysicalCameraId = "2",
                logicalParentCameraId = "0",
                routeKind = CameraRouteKind.LOGICAL_PHYSICAL_MEMBER,
                minimalMetadata = metadata(),
                trust = CameraRouteTrust(
                    metadata = CameraMetadataTrust.METADATA_VALID,
                    session = CameraSessionTrust.SESSION_VERIFIED,
                ),
            ),
        ).routes.single().copy(
            aliases = listOf(
                CameraRouteAlias(
                    discoveredCameraId = "2",
                    openCameraId = "2",
                    routeKind = CameraRouteKind.NDK_DIRECT,
                    sources = setOf(CameraDiscoverySource.NDK_ADVERTISED),
                ),
            ),
        )

        val refreshed = original.withLensDescriptor(original.toLensDescriptor())

        assertEquals(original.openCameraId, refreshed.openCameraId)
        assertEquals(original.streamPhysicalCameraId, refreshed.streamPhysicalCameraId)
        assertEquals(original.sources, refreshed.sources)
        assertEquals(original.trust, refreshed.trust)
        assertEquals(original.aliases, refreshed.aliases)
        assertEquals(original.role, refreshed.role)
    }

    private fun resolve(vararg evidence: CameraRouteEvidence): CameraTopology =
        CameraTopologyResolver.resolve(environment, evidence.toList())

    private fun java(
        id: String,
        metadata: MinimalCameraMetadata,
        kind: CameraRouteKind = CameraRouteKind.PUBLIC_DIRECT,
    ) = CameraRouteEvidence(
        source = CameraDiscoverySource.JAVA_PUBLIC,
        discoveredCameraId = id,
        routeKind = kind,
        minimalMetadata = metadata,
    )

    private fun ndk(id: String, metadata: MinimalCameraMetadata) = CameraRouteEvidence(
        source = CameraDiscoverySource.NDK_ADVERTISED,
        discoveredCameraId = id,
        routeKind = CameraRouteKind.NDK_DIRECT,
        minimalMetadata = metadata,
    )

    private fun deep(id: String, metadata: MinimalCameraMetadata) = CameraRouteEvidence(
        source = CameraDiscoverySource.NDK_DEEP,
        discoveredCameraId = id,
        routeKind = CameraRouteKind.DEEP_NDK_DIRECT,
        minimalMetadata = metadata,
    )

    private fun metadata(
        focal: Double = 5.0,
        raw: CapabilitySupport = CapabilitySupport.SUPPORTED,
        rawSizes: List<Size2D> = listOf(Size2D(4000, 3000)),
    ) = MinimalCameraMetadata(
        facing = LensFacing.BACK,
        focalLengthsMm = listOf(focal),
        sensorPhysicalSize = PhysicalSize(7.2, 5.4),
        activeArray = SensorRect(0, 0, 4000, 3000),
        pixelArraySize = Size2D(4000, 3000),
        backwardCompatibleAdvertised = CapabilitySupport.SUPPORTED,
        rawCapabilityAdvertised = raw,
        rawStreamActuallyDeclared = raw,
        rawFormats = if (raw == CapabilitySupport.SUPPORTED) setOf(StreamFormat.RAW_SENSOR) else emptySet(),
        rawSizes = if (raw == CapabilitySupport.SUPPORTED) rawSizes else emptyList(),
        previewStreamActuallyDeclared = CapabilitySupport.SUPPORTED,
        privatePreviewSizes = listOf(Size2D(1920, 1080)),
    )

    private companion object {
        val environment = CameraEnvironmentFingerprint(
            buildFingerprint = "vendor/device/build:1",
            apiLevel = 35,
        )
    }
}
