package com.sahidcode404.camex.core.camera.cache

import com.sahidcode404.camex.core.camera.topology.CameraDiscoverySource
import com.sahidcode404.camex.core.camera.topology.CameraEnvironmentFingerprint
import com.sahidcode404.camex.core.camera.topology.CameraFailureDurability
import com.sahidcode404.camex.core.camera.topology.CameraMetadataTrust
import com.sahidcode404.camex.core.camera.topology.CameraRawTrust
import com.sahidcode404.camex.core.camera.topology.CameraRoute
import com.sahidcode404.camex.core.camera.topology.CameraRouteFailure
import com.sahidcode404.camex.core.camera.topology.CameraRouteFailureKind
import com.sahidcode404.camex.core.camera.topology.CameraRouteKind
import com.sahidcode404.camex.core.camera.topology.CameraRouteTrust
import com.sahidcode404.camex.core.camera.topology.CameraSessionTrust
import com.sahidcode404.camex.core.camera.topology.CameraTopology
import com.sahidcode404.camex.core.camera.topology.MinimalCameraMetadata
import com.sahidcode404.camex.core.camera.topology.PhotographicRole
import com.sahidcode404.camex.core.camera.topology.RoleConfidence
import com.sahidcode404.camex.core.model.CapabilitySupport
import com.sahidcode404.camex.core.model.FingerprintStrategy
import com.sahidcode404.camex.core.model.LensFacing
import com.sahidcode404.camex.core.model.LensFingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraCachePolicyTest {
    @Test
    fun `topology cache JSON round trips as a valid hit`() {
        val cached = cachedTopology()
        val encoded = CameraTopologyCacheCodec.encode(cached)

        val result = CameraTopologyCacheCodec.decodeAndEvaluate(encoded, environment)

        assertTrue(result is CameraTopologyCacheResult.Hit)
        assertEquals(cached, (result as CameraTopologyCacheResult.Hit).cached)
        assertFalse(result.migrated)
    }

    @Test
    fun `empty and corrupt documents are clean cache misses`() {
        assertEquals(
            CameraCacheMissReason.EMPTY,
            (CameraTopologyCacheCodec.decodeAndEvaluate(null, environment) as
                CameraTopologyCacheResult.Miss).reason,
        )
        assertEquals(
            CameraCacheMissReason.CORRUPT,
            (CameraTopologyCacheCodec.decodeAndEvaluate("{not-json", environment) as
                CameraTopologyCacheResult.Miss).reason,
        )
    }

    @Test
    fun `legacy route-per-lens cache is invalidated rather than reinterpreted`() {
        val legacyEnvironment = environment.copy(cacheSchemaVersion = 1)
        val legacy = cachedTopology().copy(
            cacheSchemaVersion = 1,
            environmentFingerprint = legacyEnvironment,
            topology = topology(environment = legacyEnvironment).copy(schemaVersion = 1),
        )

        val result = CameraTopologyCachePolicy.evaluate(legacy, environment)

        assertTrue(result is CameraTopologyCacheResult.Miss)
        assertEquals(
            CameraCacheMissReason.CACHE_SCHEMA_CHANGED,
            (result as CameraTopologyCacheResult.Miss).reason,
        )
    }

    @Test
    fun `newer cache and topology schema are invalidated`() {
        val newCache = cachedTopology().copy(
            cacheSchemaVersion = CameraTopology.CACHE_SCHEMA_VERSION + 1,
        )
        val newTopology = cachedTopology().copy(
            topology = topology().copy(schemaVersion = CameraTopology.CURRENT_SCHEMA_VERSION + 1),
        )

        assertEquals(
            CameraCacheMissReason.CACHE_SCHEMA_CHANGED,
            (CameraTopologyCachePolicy.evaluate(newCache, environment) as
                CameraTopologyCacheResult.Miss).reason,
        )
        assertEquals(
            CameraCacheMissReason.TOPOLOGY_SCHEMA_CHANGED,
            (CameraTopologyCachePolicy.evaluate(newTopology, environment) as
                CameraTopologyCacheResult.Miss).reason,
        )
    }

    @Test
    fun `firmware API and discovery schema changes invalidate topology cache`() {
        val cached = cachedTopology()
        val changed = listOf(
            environment.copy(buildFingerprint = "vendor/new-rom"),
            environment.copy(apiLevel = environment.apiLevel + 1),
            environment.copy(discoverySchemaVersion = environment.discoverySchemaVersion + 1),
        )

        changed.forEach { expected ->
            assertEquals(
                CameraCacheMissReason.ENVIRONMENT_CHANGED,
                (CameraTopologyCachePolicy.evaluate(cached, expected) as
                    CameraTopologyCacheResult.Miss).reason,
            )
        }
    }

    @Test
    fun `advertised signature is optional at bootstrap but invalidates after both sides know it`() {
        val ids01 = CameraEnvironmentFingerprint.advertisedSignature(listOf("1", "0", "0"))
        val ids012 = CameraEnvironmentFingerprint.advertisedSignature(listOf("0", "1", "2"))
        val cachedEnvironment = environment.copy(advertisedTopologySignature = ids01)
        val cached = cachedTopology(cachedEnvironment)

        assertTrue(
            CameraTopologyCachePolicy.evaluate(cached, environment) is CameraTopologyCacheResult.Hit,
        )
        assertEquals(
            CameraCacheMissReason.ENVIRONMENT_CHANGED,
            (CameraTopologyCachePolicy.evaluate(
                cached,
                environment.copy(advertisedTopologySignature = ids012),
            ) as CameraTopologyCacheResult.Miss).reason,
        )
        assertEquals(
            ids01,
            CameraEnvironmentFingerprint.advertisedSignature(listOf("0", "1")),
        )
    }

    @Test
    fun `duplicate optical fingerprints make cache corrupt instead of exposing unstable topology`() {
        val route = route("0")
        val invalid = cachedTopology().copy(
            topology = topology(routes = listOf(route, route.copy(openCameraId = "1"))),
        )

        assertEquals(
            CameraCacheMissReason.CORRUPT,
            (CameraTopologyCachePolicy.evaluate(invalid, environment) as
                CameraTopologyCacheResult.Miss).reason,
        )
    }

    @Test
    fun `transient camera in-use failure never downgrades verified session`() {
        val verified = CameraRouteTrust(
            metadata = CameraMetadataTrust.METADATA_VALID,
            session = CameraSessionTrust.SESSION_VERIFIED,
            raw = CameraRawTrust.RAW_VERIFIED,
        )
        val temporary = CameraRouteTrust(
            metadata = CameraMetadataTrust.DISCOVERED,
            session = CameraSessionTrust.TRANSIENT_FAILURE,
            raw = CameraRawTrust.TRANSIENT_FAILURE,
            failure = CameraRouteFailure(
                CameraRouteFailureKind.CAMERA_IN_USE,
                CameraFailureDurability.TRANSIENT,
                "Camera is busy",
            ),
        )

        assertEquals(verified, CameraTrustPolicy.merge(verified, temporary))
    }

    @Test
    fun `structural session rejection is persisted but does not fabricate RAW rejection`() {
        val observation = CameraRouteTrust(
            metadata = CameraMetadataTrust.METADATA_VALID,
            session = CameraSessionTrust.SESSION_REJECTED,
            failure = CameraRouteFailure(
                CameraRouteFailureKind.SESSION_CONFIGURATION_UNSUPPORTED,
                CameraFailureDurability.STRUCTURAL,
                "unsupported route",
            ),
        )

        val merged = CameraTrustPolicy.merge(CameraRouteTrust(), observation)

        assertEquals(CameraSessionTrust.SESSION_REJECTED, merged.session)
        assertEquals(CameraRawTrust.UNKNOWN, merged.raw)
        assertEquals(CameraFailureDurability.STRUCTURAL, merged.failure?.durability)
    }

    @Test
    fun `trust applies only when profile and optical lens fingerprint agree`() {
        val route = route("0")
        val matching = CameraTrustSnapshot(
            environmentFingerprint = environment,
            records = listOf(
                CameraTrustRecord(
                    route.canonicalRouteId,
                    route.lensFingerprint,
                    route.trust.copy(session = CameraSessionTrust.SESSION_VERIFIED),
                ),
            ),
        )
        val mismatch = matching.copy(
            records = matching.records.map {
                it.copy(lensFingerprint = LensFingerprint("different_optical", FingerprintStrategy.STABLE_METADATA))
            },
        )

        assertEquals(
            CameraSessionTrust.SESSION_VERIFIED,
            CameraTrustPolicy.apply(matching, topology(routes = listOf(route)))
                .routes.single().trust.session,
        )
        assertEquals(
            CameraSessionTrust.UNKNOWN,
            CameraTrustPolicy.apply(mismatch, topology(routes = listOf(route)))
                .routes.single().trust.session,
        )
    }

    @Test
    fun `trust reconciliation prunes vanished profile and adds new profile`() {
        val oldRoute = route("0")
        val vanishedRoute = route("5")
        val previous = CameraTrustSnapshot(
            environmentFingerprint = environment,
            records = listOf(oldRoute, vanishedRoute).map {
                CameraTrustRecord(
                    it.canonicalRouteId,
                    it.lensFingerprint,
                    it.trust.copy(session = CameraSessionTrust.SESSION_VERIFIED),
                )
            },
        )
        val newRoute = route("6")

        val reconciled = CameraTrustPolicy.reconcile(
            previous,
            topology(routes = listOf(oldRoute, newRoute)),
        )

        assertEquals(
            setOf(oldRoute.canonicalRouteId, newRoute.canonicalRouteId),
            reconciled.records.mapTo(mutableSetOf(), CameraTrustRecord::canonicalRouteId),
        )
        assertEquals(
            CameraSessionTrust.SESSION_VERIFIED,
            reconciled.records.single { it.canonicalRouteId == oldRoute.canonicalRouteId }.trust.session,
        )
        assertEquals(
            CameraSessionTrust.UNKNOWN,
            reconciled.records.single { it.canonicalRouteId == newRoute.canonicalRouteId }.trust.session,
        )
    }

    @Test
    fun `trust cache round trips and invalidates with HAL environment`() {
        val cached = CachedCameraTrust(
            snapshot = CameraTrustSnapshot(
                environmentFingerprint = environment,
                records = listOf(
                    CameraTrustRecord(
                        route("0").canonicalRouteId,
                        route("0").lensFingerprint,
                        CameraRouteTrust(session = CameraSessionTrust.SESSION_VERIFIED),
                    ),
                ),
            ),
            generatedAtEpochMs = 42L,
        )
        val raw = CameraTrustCacheCodec.encode(cached)

        assertTrue(
            CameraTrustCacheCodec.decodeAndEvaluate(raw, environment) is CameraTrustCacheResult.Hit,
        )
        assertEquals(
            CameraCacheMissReason.ENVIRONMENT_CHANGED,
            (CameraTrustCacheCodec.decodeAndEvaluate(
                raw,
                environment.copy(buildFingerprint = "new-firmware"),
            ) as CameraTrustCacheResult.Miss).reason,
        )
    }

    private fun cachedTopology(env: CameraEnvironmentFingerprint = environment) = CachedCameraTopology(
        environmentFingerprint = env,
        topology = topology(environment = env),
        generatedAtEpochMs = 42L,
    )

    private fun topology(
        environment: CameraEnvironmentFingerprint = CameraCachePolicyTest.environment,
        routes: List<CameraRoute> = listOf(route("0")),
    ) = CameraTopology(environmentFingerprint = environment, routes = routes)

    private fun route(id: String) = CameraRoute(
        canonicalRouteId = "cr1_${id.length}:$id|0:",
        discoveredCameraId = id,
        openCameraId = id,
        routeKind = CameraRouteKind.PUBLIC_DIRECT,
        sources = setOf(CameraDiscoverySource.JAVA_PUBLIC),
        minimalMetadata = MinimalCameraMetadata(
            facing = LensFacing.BACK,
            focalLengthsMm = listOf(5.0 + id.length),
            backwardCompatibleAdvertised = CapabilitySupport.SUPPORTED,
            previewStreamActuallyDeclared = CapabilitySupport.SUPPORTED,
        ),
        lensFingerprint = LensFingerprint("ol3_camera_$id", FingerprintStrategy.STABLE_METADATA),
        role = PhotographicRole.PHOTOGRAPHIC_UNKNOWN,
        roleConfidence = RoleConfidence.MODERATE,
        trust = CameraRouteTrust(metadata = CameraMetadataTrust.METADATA_VALID),
    )

    private companion object {
        val environment = CameraEnvironmentFingerprint(
            buildFingerprint = "vendor/device/build:1",
            apiLevel = 35,
        )
    }
}
