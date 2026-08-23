package com.sahidcode404.camex.core.camera.runtime

import com.sahidcode404.camex.core.camera.CameraRuntimeSnapshot
import com.sahidcode404.camex.core.camera.CameraSessionState
import com.sahidcode404.camex.core.camera.topology.CameraDiscoverySource
import com.sahidcode404.camex.core.camera.topology.CameraEnvironmentFingerprint
import com.sahidcode404.camex.core.camera.topology.CameraMetadataTrust
import com.sahidcode404.camex.core.camera.topology.CameraProfile
import com.sahidcode404.camex.core.camera.topology.CameraRoute
import com.sahidcode404.camex.core.camera.topology.CameraRouteKind
import com.sahidcode404.camex.core.camera.topology.CameraRouteTrust
import com.sahidcode404.camex.core.camera.topology.CameraTopology
import com.sahidcode404.camex.core.camera.topology.MinimalCameraMetadata
import com.sahidcode404.camex.core.camera.topology.PhotographicRole
import com.sahidcode404.camex.core.camera.topology.RoleConfidence
import com.sahidcode404.camex.core.camera.topology.sessionRoutingKey
import com.sahidcode404.camex.core.camera.topology.toLensDescriptor
import com.sahidcode404.camex.core.model.CapabilitySupport
import com.sahidcode404.camex.core.model.FingerprintStrategy
import com.sahidcode404.camex.core.model.LensFacing
import com.sahidcode404.camex.core.model.LensFingerprint
import com.sahidcode404.camex.core.model.LensUsability
import com.sahidcode404.camex.core.model.Size2D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveCameraSelectionTest {
    @Test
    fun `profile session routing key exactly matches descriptor routing key`() {
        val route = canonicalRoute("rear", LensFacing.BACK, "transport-a")
        val profile = route.profiles.single()
        val descriptor = profile.toLensDescriptor(
            opticalFingerprint = requireNotNull(route.lensFingerprint),
            role = route.role,
            roleConfidence = route.roleConfidence,
        )

        assertNotEquals(profile.routingKey, descriptor.identity.routingKey)
        assertEquals(descriptor.identity.routingKey, profile.sessionRoutingKey())
        assertEquals(
            profile.profileFingerprint,
            CanonicalLensResolver.resolveProfile(
                topology = topology(route),
                sessionSnapshot = CameraRuntimeSnapshot(
                    lenses = listOf(descriptor),
                    selectedRoutingKey = descriptor.identity.routingKey,
                ),
                routingKey = descriptor.identity.routingKey,
            )?.profile?.profileFingerprint,
        )
    }

    @Test
    fun `front verified descriptor keeps front when canonical topology is temporarily unavailable`() {
        val front = canonicalRoute("front", LensFacing.FRONT, "front-route")
        val descriptor = frontProfileDescriptor(front)
        val tracker = ActiveCameraSelectionTracker()
        tracker.beginSelection(descriptor)
        val preview = CameraSessionState.Previewing(descriptor.identity.routingKey, Size2D(1920, 1080))

        val active = tracker.previewVerified(
            routingKey = descriptor.identity.routingKey,
            sessionState = preview,
            sessionSnapshot = CameraRuntimeSnapshot(
                lenses = listOf(descriptor),
                selectedRoutingKey = descriptor.identity.routingKey,
            ),
            topology = topology(),
        )

        assertNotNull(active)
        assertEquals(LensFacing.FRONT, active?.facing)
        assertEquals(front.lensFingerprint, active?.canonicalLensFingerprint)
        assertTrue(active?.verified == true)
    }

    @Test
    fun `stale verified event cannot overwrite newer facing request`() {
        val rear = canonicalRoute("rear", LensFacing.BACK, "rear-route")
        val front = canonicalRoute("front", LensFacing.FRONT, "front-route")
        val rearDescriptor = frontProfileDescriptor(rear)
        val frontDescriptor = frontProfileDescriptor(front)
        val topology = topology(rear, front)
        val tracker = ActiveCameraSelectionTracker()

        tracker.beginSelection(rearDescriptor)
        tracker.previewVerified(
            rearDescriptor.identity.routingKey,
            CameraSessionState.Previewing(rearDescriptor.identity.routingKey, Size2D(1920, 1080)),
            CameraRuntimeSnapshot(
                lenses = listOf(rearDescriptor, frontDescriptor),
                selectedRoutingKey = rearDescriptor.identity.routingKey,
            ),
            topology,
        )

        tracker.beginSelection(frontDescriptor)
        val stale = tracker.previewVerified(
            rearDescriptor.identity.routingKey,
            CameraSessionState.Switching(rearDescriptor.identity.routingKey, frontDescriptor.identity.routingKey),
            CameraRuntimeSnapshot(
                lenses = listOf(rearDescriptor, frontDescriptor),
                selectedRoutingKey = frontDescriptor.identity.routingKey,
            ),
            topology,
        )
        assertEquals(LensFacing.BACK, stale?.facing)

        val activeFront = tracker.previewVerified(
            frontDescriptor.identity.routingKey,
            CameraSessionState.Previewing(frontDescriptor.identity.routingKey, Size2D(1920, 1080)),
            CameraRuntimeSnapshot(
                lenses = listOf(rearDescriptor, frontDescriptor),
                selectedRoutingKey = frontDescriptor.identity.routingKey,
            ),
            topology,
        )
        assertEquals(LensFacing.FRONT, activeFront?.facing)

        val lateRear = tracker.previewVerified(
            rearDescriptor.identity.routingKey,
            CameraSessionState.Previewing(frontDescriptor.identity.routingKey, Size2D(1920, 1080)),
            CameraRuntimeSnapshot(
                lenses = listOf(rearDescriptor, frontDescriptor),
                selectedRoutingKey = frontDescriptor.identity.routingKey,
            ),
            topology,
        )
        assertEquals(LensFacing.FRONT, lateRear?.facing)
        assertEquals(activeFront?.selectionGeneration, lateRear?.selectionGeneration)
    }

    @Test
    fun `sibling profile failover preserves canonical lens facing and generation`() {
        val rear = canonicalRoute(
            name = "rear",
            facing = LensFacing.BACK,
            routeIds = arrayOf("profile-a", "profile-b"),
        )
        val descriptors = rear.profiles.map { profile ->
            profile.toLensDescriptor(
                opticalFingerprint = requireNotNull(rear.lensFingerprint),
                role = rear.role,
                roleConfidence = rear.roleConfidence,
            )
        }
        val tracker = ActiveCameraSelectionTracker()
        val generation = tracker.beginSelection(descriptors.first())
        val succeeded = descriptors.last()

        val active = tracker.previewVerified(
            routingKey = succeeded.identity.routingKey,
            sessionState = CameraSessionState.Previewing(
                succeeded.identity.routingKey,
                Size2D(1920, 1080),
            ),
            sessionSnapshot = CameraRuntimeSnapshot(
                lenses = descriptors,
                selectedRoutingKey = succeeded.identity.routingKey,
            ),
            topology = topology(rear),
        )

        assertEquals(generation, active?.selectionGeneration)
        assertEquals(LensFacing.BACK, active?.facing)
        assertEquals(rear.lensFingerprint, active?.canonicalLensFingerprint)
        assertEquals(rear.profiles.last().profileFingerprint, active?.activeProfileFingerprint)
        assertEquals(succeeded.identity.routingKey, active?.activeProfileRoutingKey)
    }

    @Test
    fun `topology reconciliation cannot reset verified rear selection`() {
        val rear = canonicalRoute("rear", LensFacing.BACK, "rear-route")
        val descriptor = frontProfileDescriptor(rear)
        val tracker = ActiveCameraSelectionTracker()
        tracker.beginSelection(descriptor)
        val preview = CameraSessionState.Previewing(descriptor.identity.routingKey, Size2D(1920, 1080))
        val snapshot = CameraRuntimeSnapshot(
            lenses = listOf(descriptor),
            selectedRoutingKey = descriptor.identity.routingKey,
        )
        val first = tracker.previewVerified(
            descriptor.identity.routingKey,
            preview,
            snapshot,
            topology(rear),
        )

        val duringRefresh = tracker.reconcile(
            sessionState = preview,
            sessionSnapshot = snapshot,
            topology = topology(),
        )

        assertEquals(first?.canonicalLensFingerprint, duringRefresh?.canonicalLensFingerprint)
        assertEquals(LensFacing.BACK, duringRefresh?.facing)
        assertEquals(descriptor.identity.routingKey, duringRefresh?.activeProfileRoutingKey)
        assertTrue(duringRefresh?.verified == true)
    }

    private fun frontProfileDescriptor(route: CameraRoute) = route.profiles.first().toLensDescriptor(
        opticalFingerprint = requireNotNull(route.lensFingerprint),
        role = route.role,
        roleConfidence = route.roleConfidence,
    ).copy(usability = LensUsability.PREVIEW_ONLY)

    private fun canonicalRoute(
        name: String,
        facing: LensFacing,
        vararg routeIds: String,
    ): CameraRoute {
        val ids = routeIds.toList().ifEmpty { listOf("$name-route") }
        val fingerprint = LensFingerprint(
            value = "ol4_${name.padEnd(8, 'x')}",
            strategy = FingerprintStrategy.STABLE_METADATA,
        )
        val metadata = MinimalCameraMetadata(
            facing = facing,
            focalLengthsMm = listOf(if (facing == LensFacing.FRONT) 3.0 else 5.0),
            previewStreamActuallyDeclared = CapabilitySupport.SUPPORTED,
            privatePreviewSizes = listOf(Size2D(1920, 1080)),
        )
        val profiles = ids.map { id ->
            CameraProfile(
                profileId = "profile-$id",
                profileFingerprint = "cp2_${id.padEnd(8, 'x')}",
                discoveredCameraId = id,
                openCameraId = id,
                routeKind = CameraRouteKind.PUBLIC_DIRECT,
                discoverySources = setOf(CameraDiscoverySource.JAVA_PUBLIC),
                metadata = metadata,
                metadataTrust = CameraMetadataTrust.METADATA_VALID,
            )
        }
        val preferred = profiles.first()
        return CameraRoute(
            canonicalRouteId = preferred.profileId,
            discoveredCameraId = preferred.discoveredCameraId,
            openCameraId = preferred.openCameraId,
            routeKind = preferred.routeKind,
            sources = preferred.discoverySources,
            minimalMetadata = metadata,
            lensFingerprint = fingerprint,
            role = PhotographicRole.PHOTOGRAPHIC_WIDE,
            roleConfidence = RoleConfidence.STRONG,
            trust = CameraRouteTrust(metadata = CameraMetadataTrust.METADATA_VALID),
            storedProfiles = profiles,
            preferredProfileId = preferred.profileId,
        )
    }

    private fun topology(vararg routes: CameraRoute) = CameraTopology(
        environmentFingerprint = CameraEnvironmentFingerprint(
            buildFingerprint = "vendor/device/build:selection-test",
            apiLevel = 35,
        ),
        routes = routes.toList(),
    )
}
