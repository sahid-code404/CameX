package com.sahidcode404.camex.core.camera.topology

import com.sahidcode404.camex.core.logic.LensDuplicateFilter
import com.sahidcode404.camex.core.model.CapabilitySupport
import com.sahidcode404.camex.core.model.FieldOfView
import com.sahidcode404.camex.core.model.LensFacing
import com.sahidcode404.camex.core.model.PhysicalSize
import com.sahidcode404.camex.core.model.SensorRect
import com.sahidcode404.camex.core.model.Size2D
import com.sahidcode404.camex.core.model.StreamFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpticalCanonicalizationRegressionTest {
    @Test
    fun `cloned sensor geometry cannot hide a real lens when focal differs`() {
        val first = photographicMetadata(focal = 4.0)
        val second = photographicMetadata(focal = 4.2)

        val comparison = OpticalLensMatcher.compare(
            resolveSingle("first", first),
            resolveSingle("second", second),
        )
        val topology = resolve(route("first", first), route("second", second))

        assertEquals(OpticalLensMatch.CONFLICT, comparison.match)
        assertEquals(2, topology.routes.size)
    }

    @Test
    fun `cloned geometry without optical anchor remains separate`() {
        val metadata = photographicMetadata(focal = null)
        val first = resolveSingle("sparse-a", metadata)
        val second = resolveSingle("sparse-b", metadata)
        val comparison = OpticalLensMatcher.compare(first, second)
        val topology = resolve(route("sparse-a", metadata), route("sparse-b", metadata))

        assertEquals(OpticalLensMatch.INSUFFICIENT_EVIDENCE, comparison.match)
        assertEquals(setOf(OpticalEvidenceFamily.SENSOR, OpticalEvidenceFamily.GEOMETRY), comparison.evidenceFamilies)
        assertEquals(2, comparison.evidenceCount)
        assertEquals(2, topology.routes.size)
    }

    @Test
    fun `pixel active and raw agreement count as one geometry family`() {
        val left = photographicMetadata(focal = null, sensor = null)
        val right = photographicMetadata(focal = null, sensor = null)
        val comparison = OpticalLensMatcher.compare(
            resolveSingle("geometry-a", left),
            resolveSingle("geometry-b", right),
        )

        assertEquals(OpticalLensMatch.INSUFFICIENT_EVIDENCE, comparison.match)
        assertEquals(setOf(OpticalEvidenceFamily.GEOMETRY), comparison.evidenceFamilies)
        assertEquals(1, comparison.evidenceCount)
        assertTrue(comparison.positiveReasons.count { "array" in it || "RAW" in it } >= 3)
    }

    @Test
    fun `complete link prevents transitive alias chain collapse`() {
        val a = photographicMetadata(focal = 4.7, sensor = null).copy(
            approximateFieldOfView = null,
        )
        val b = photographicMetadata(focal = 4.7, sensor = PhysicalSize(7.2, 5.4)).copy(
            approximateFieldOfView = fov(80.0),
        )
        val c = photographicMetadata(
            focal = null,
            sensor = PhysicalSize(7.2, 5.4),
            pixel = Size2D(3000, 2000),
            active = SensorRect(0, 0, 3000, 2000),
            raw = Size2D(3000, 2000),
        ).copy(approximateFieldOfView = fov(80.0))

        val aRoute = resolveSingle("a", a)
        val bRoute = resolveSingle("b", b)
        val cRoute = resolveSingle("c", c)
        assertEquals(OpticalLensMatch.STRONG_MATCH, OpticalLensMatcher.compare(aRoute, bRoute).match)
        assertEquals(OpticalLensMatch.STRONG_MATCH, OpticalLensMatcher.compare(bRoute, cRoute).match)
        assertEquals(OpticalLensMatch.INSUFFICIENT_EVIDENCE, OpticalLensMatcher.compare(aRoute, cRoute).match)

        val topology = resolve(route("a", a), route("b", b), route("c", c))
        assertEquals(2, topology.routes.size)
        assertEquals(listOf(1, 2), topology.routes.map { it.profiles.size }.sorted())
        assertTrue(topology.groupingComparisons.any {
            setOf(it.leftProfileFingerprint, it.rightProfileFingerprint) ==
                setOf(aRoute.profiles.single().profileFingerprint, cRoute.profiles.single().profileFingerprint) &&
                it.match == OpticalLensMatch.INSUFFICIENT_EVIDENCE.name
        })
    }

    @Test
    fun `three strong aliases still become one canonical lens`() {
        val metadata = photographicMetadata(focal = 4.72)
        val topology = resolve(
            route("alias-a", metadata),
            route("alias-b", metadata, CameraDiscoverySource.NDK_ADVERTISED, CameraRouteKind.NDK_DIRECT),
            route("alias-c", metadata, CameraDiscoverySource.NDK_DEEP, CameraRouteKind.DEEP_NDK_DIRECT),
        )

        assertEquals(1, topology.routes.size)
        assertEquals(3, topology.routes.single().profiles.size)
        assertTrue(topology.groupingComparisons.all { it.match == OpticalLensMatch.STRONG_MATCH.name })
    }

    @Test
    fun `multiple optical lenses each keep aliases and produce one selector item per lens`() {
        val ultra = photographicMetadata(focal = 2.0)
        val wide = photographicMetadata(focal = 4.7)
        val other = photographicMetadata(focal = 8.0)
        val front = photographicMetadata(focal = 3.7, facing = LensFacing.FRONT)
        val topology = resolve(
            route("u1", ultra), route("u2", ultra, CameraDiscoverySource.NDK_DEEP, CameraRouteKind.DEEP_NDK_DIRECT),
            route("w1", wide), route("w2", wide, CameraDiscoverySource.NDK_DEEP, CameraRouteKind.DEEP_NDK_DIRECT),
            route("m1", other), route("m2", other, CameraDiscoverySource.NDK_DEEP, CameraRouteKind.DEEP_NDK_DIRECT),
            route("f1", front), route("f2", front, CameraDiscoverySource.NDK_DEEP, CameraRouteKind.DEEP_NDK_DIRECT),
        )

        assertEquals(4, topology.routes.size)
        assertTrue(topology.routes.all { it.profiles.size == 2 })
        val selector = LensDuplicateFilter.filterForSelector(
            topology.routes.mapIndexed { index, route -> route.toLensDescriptor().copy(discoveryOrder = index) },
        )
        assertEquals(3, selector.count { it.facing == LensFacing.BACK })
        assertEquals(1, selector.count { it.facing == LensFacing.FRONT })
    }

    @Test
    fun `different authoritative physical members never merge even with cloned metadata`() {
        val metadata = photographicMetadata(focal = 4.72)
        val left = CameraRouteEvidence(
            source = CameraDiscoverySource.JAVA_PHYSICAL,
            discoveredCameraId = "physical-a",
            openCameraId = "logical",
            streamPhysicalCameraId = "physical-a",
            logicalParentCameraId = "logical",
            routeKind = CameraRouteKind.LOGICAL_PHYSICAL_MEMBER,
            minimalMetadata = metadata,
        )
        val right = CameraRouteEvidence(
            source = CameraDiscoverySource.JAVA_PHYSICAL,
            discoveredCameraId = "physical-b",
            openCameraId = "logical",
            streamPhysicalCameraId = "physical-b",
            logicalParentCameraId = "logical",
            routeKind = CameraRouteKind.LOGICAL_PHYSICAL_MEMBER,
            minimalMetadata = metadata,
        )
        val topology = resolve(left, right)

        assertEquals(2, topology.routes.size)
        val comparison = topology.groupingComparisons.single()
        assertEquals(OpticalLensMatch.CONFLICT.name, comparison.match)
        assertTrue(comparison.negativeReasons.any { "different authoritative physical members" in it })
    }

    private fun resolve(vararg evidence: CameraRouteEvidence): CameraTopology =
        CameraTopologyResolver.resolve(environment, evidence.toList())

    private fun resolveSingle(id: String, metadata: MinimalCameraMetadata): CameraRoute =
        resolve(route(id, metadata)).routes.single()

    private fun route(
        id: String,
        metadata: MinimalCameraMetadata,
        source: CameraDiscoverySource = CameraDiscoverySource.JAVA_PUBLIC,
        kind: CameraRouteKind = CameraRouteKind.PUBLIC_DIRECT,
    ) = CameraRouteEvidence(
        source = source,
        discoveredCameraId = id,
        openCameraId = id,
        routeKind = kind,
        minimalMetadata = metadata,
        trust = CameraRouteTrust(metadata = CameraMetadataTrust.METADATA_VALID),
    )

    private fun photographicMetadata(
        focal: Double?,
        sensor: PhysicalSize? = PhysicalSize(7.2, 5.4),
        pixel: Size2D = Size2D(4000, 3000),
        active: SensorRect = SensorRect(0, 0, 4000, 3000),
        raw: Size2D = Size2D(4000, 3000),
        facing: LensFacing = LensFacing.BACK,
    ) = MinimalCameraMetadata(
        facing = facing,
        focalLengthsMm = focal?.let(::listOf).orEmpty(),
        sensorPhysicalSize = sensor,
        activeArray = active,
        pixelArraySize = pixel,
        sensorOrientationDegrees = 90,
        backwardCompatibleAdvertised = CapabilitySupport.SUPPORTED,
        rawCapabilityAdvertised = CapabilitySupport.SUPPORTED,
        rawStreamActuallyDeclared = CapabilitySupport.SUPPORTED,
        rawFormats = setOf(StreamFormat.RAW_SENSOR),
        rawSizes = listOf(raw),
        previewStreamActuallyDeclared = CapabilitySupport.SUPPORTED,
        privatePreviewSizes = listOf(Size2D(1920, 1080)),
    )

    private fun fov(diagonal: Double) = FieldOfView(
        horizontalDegrees = diagonal - 12.0,
        verticalDegrees = diagonal - 24.0,
        diagonalDegrees = diagonal,
        focalLengthMm = 4.7,
    )

    private companion object {
        val environment = CameraEnvironmentFingerprint(
            buildFingerprint = "vendor/device/build:optical-regression",
            apiLevel = 35,
        )
    }
}
