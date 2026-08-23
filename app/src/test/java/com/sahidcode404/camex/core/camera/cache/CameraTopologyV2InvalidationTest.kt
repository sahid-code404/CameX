package com.sahidcode404.camex.core.camera.cache

import com.sahidcode404.camex.core.camera.topology.CameraDiscoverySource
import com.sahidcode404.camex.core.camera.topology.CameraEnvironmentFingerprint
import com.sahidcode404.camex.core.camera.topology.CameraRoute
import com.sahidcode404.camex.core.camera.topology.CameraRouteKind
import com.sahidcode404.camex.core.camera.topology.CameraTopology
import com.sahidcode404.camex.core.camera.topology.MinimalCameraMetadata
import com.sahidcode404.camex.core.model.FingerprintStrategy
import com.sahidcode404.camex.core.model.LensFacing
import com.sahidcode404.camex.core.model.LensFingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraTopologyV2InvalidationTest {
    @Test
    fun `over-merged topology schema v2 cache cannot bootstrap corrected canonicalization`() {
        val oldEnvironment = CameraEnvironmentFingerprint(
            cacheSchemaVersion = 2,
            buildFingerprint = "vendor/device/build:cache-regression",
            apiLevel = 35,
            discoverySchemaVersion = 3,
        )
        val oldWideOnlyTopology = CameraTopology(
            schemaVersion = 2,
            environmentFingerprint = oldEnvironment,
            routes = listOf(
                CameraRoute(
                    canonicalRouteId = "old-wide",
                    discoveredCameraId = "old-route",
                    openCameraId = "old-route",
                    routeKind = CameraRouteKind.PUBLIC_DIRECT,
                    sources = setOf(CameraDiscoverySource.JAVA_PUBLIC),
                    minimalMetadata = MinimalCameraMetadata(facing = LensFacing.BACK),
                    lensFingerprint = LensFingerprint(
                        "old-over-merged-optical",
                        FingerprintStrategy.STABLE_METADATA,
                    ),
                ),
            ),
        )
        val cached = CachedCameraTopology(
            cacheSchemaVersion = 2,
            environmentFingerprint = oldEnvironment,
            topology = oldWideOnlyTopology,
            generatedAtEpochMs = 1L,
        )
        val correctedEnvironment = CameraEnvironmentFingerprint(
            buildFingerprint = oldEnvironment.buildFingerprint,
            apiLevel = oldEnvironment.apiLevel,
        )

        val result = CameraTopologyCachePolicy.evaluate(cached, correctedEnvironment)

        assertTrue(result is CameraTopologyCacheResult.Miss)
        assertEquals(
            CameraCacheMissReason.CACHE_SCHEMA_CHANGED,
            (result as CameraTopologyCacheResult.Miss).reason,
        )
    }
}
