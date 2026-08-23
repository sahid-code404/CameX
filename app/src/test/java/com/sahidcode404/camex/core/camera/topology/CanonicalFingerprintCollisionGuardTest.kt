package com.sahidcode404.camex.core.camera.topology

import com.sahidcode404.camex.core.model.FingerprintStrategy
import com.sahidcode404.camex.core.model.LensFacing
import com.sahidcode404.camex.core.model.LensFingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalFingerprintCollisionGuardTest {
    @Test
    fun `distinct canonical lenses never publish duplicate optical fingerprints`() {
        val duplicate = LensFingerprint("ol4_duplicate", FingerprintStrategy.STABLE_METADATA)
        val first = route("first", duplicate)
        val second = route("second", duplicate)
        val topology = CameraTopology(
            environmentFingerprint = environment,
            routes = listOf(first, second),
        )

        val repaired = topology.withUniqueCanonicalFingerprints()

        assertEquals(2, repaired.routes.size)
        assertEquals(2, repaired.routes.mapNotNull { it.lensFingerprint?.value }.distinct().size)
        assertEquals(duplicate, repaired.routes.first().lensFingerprint)
        assertNotEquals(duplicate, repaired.routes.last().lensFingerprint)
        assertEquals(
            FingerprintStrategy.DEVICE_SCOPED_FALLBACK,
            repaired.routes.last().lensFingerprint?.strategy,
        )
        assertTrue(repaired.routes.last().lensFingerprint?.value.orEmpty().startsWith("oc1_"))
    }

    @Test
    fun `collision fallback is deterministic for same environment and profiles`() {
        val duplicate = LensFingerprint("ol4_duplicate", FingerprintStrategy.STABLE_METADATA)
        val topology = CameraTopology(
            environmentFingerprint = environment,
            routes = listOf(route("first", duplicate), route("second", duplicate)),
        )

        assertEquals(
            topology.withUniqueCanonicalFingerprints(),
            topology.withUniqueCanonicalFingerprints(),
        )
    }

    private fun route(id: String, fingerprint: LensFingerprint): CameraRoute {
        val metadata = MinimalCameraMetadata(facing = LensFacing.BACK)
        val profile = CameraProfile(
            profileId = "profile-$id",
            profileFingerprint = "cp2_${id.padEnd(8, 'x')}",
            discoveredCameraId = id,
            openCameraId = id,
            routeKind = CameraRouteKind.PUBLIC_DIRECT,
            discoverySources = setOf(CameraDiscoverySource.JAVA_PUBLIC),
            metadata = metadata,
            metadataTrust = CameraMetadataTrust.METADATA_VALID,
        )
        return CameraRoute(
            canonicalRouteId = profile.profileId,
            discoveredCameraId = id,
            openCameraId = id,
            routeKind = CameraRouteKind.PUBLIC_DIRECT,
            sources = setOf(CameraDiscoverySource.JAVA_PUBLIC),
            minimalMetadata = metadata,
            lensFingerprint = fingerprint,
            trust = CameraRouteTrust(metadata = CameraMetadataTrust.METADATA_VALID),
            storedProfiles = listOf(profile),
            preferredProfileId = profile.profileId,
        )
    }

    private companion object {
        val environment = CameraEnvironmentFingerprint(
            buildFingerprint = "vendor/device/build:collision-test",
            apiLevel = 35,
        )
    }
}
