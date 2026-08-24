package com.sahidcode404.camex.core.camera.raw

import com.sahidcode404.camex.core.camera.topology.CameraDiscoverySource
import com.sahidcode404.camex.core.camera.topology.CameraMetadataTrust
import com.sahidcode404.camex.core.camera.topology.CameraProfile
import com.sahidcode404.camex.core.camera.topology.CameraRawTrust
import com.sahidcode404.camex.core.camera.topology.CameraRoute
import com.sahidcode404.camex.core.camera.topology.CameraRouteKind
import com.sahidcode404.camex.core.camera.topology.CameraSessionTrust
import com.sahidcode404.camex.core.camera.topology.MinimalCameraMetadata
import com.sahidcode404.camex.core.model.CapabilitySupport
import com.sahidcode404.camex.core.model.FingerprintStrategy
import com.sahidcode404.camex.core.model.LensFacing
import com.sahidcode404.camex.core.model.LensFingerprint
import com.sahidcode404.camex.core.model.Size2D
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawSameCanonicalFailoverTest {
    @Test
    fun a_structuralA1FailureRetriesA2AndSucceeds() = runBlocking {
        val route = route("canon-a", profile("a1"), profile("a2"))
        val attempts = mutableListOf<String>()
        val execution = executor(route, "a1").recover(
            initialResult = structuralFailure("a1"),
            isSelectionCurrent = { true },
        ) { candidate, _ ->
            attempts += candidate.profileFingerprint
            RawProfileRetry(saved(candidate, route), candidate.profileFingerprint)
        }

        assertTrue(execution.result is RawCaptureResult.Saved)
        assertEquals(listOf("a2"), attempts)
        assertEquals("a2", execution.finalProfileFingerprint)
    }

    @Test
    fun b_twoStructuralFailuresThenA3Succeeds() = runBlocking {
        val route = route("canon-a", profile("a1"), profile("a2"), profile("a3"))
        val attempts = mutableListOf<String>()
        val execution = executor(route, "a1").recover(
            initialResult = structuralFailure("a1"),
            isSelectionCurrent = { true },
        ) { candidate, _ ->
            attempts += candidate.profileFingerprint
            val result = if (candidate.profileFingerprint == "a2") {
                structuralFailure("a2")
            } else {
                saved(candidate, route)
            }
            RawProfileRetry(result, candidate.profileFingerprint)
        }

        assertTrue(execution.result is RawCaptureResult.Saved)
        assertEquals(listOf("a2", "a3"), attempts)
        assertEquals("a3", execution.finalProfileFingerprint)
    }

    @Test
    fun c_sameProfileIsNeverAttemptedTwice() = runBlocking {
        val route = route("canon-a", profile("a1"), profile("a2"), profile("a3"))
        val attempts = mutableListOf<String>()
        val execution = executor(route, "a1").recover(
            initialResult = structuralFailure("a1"),
            isSelectionCurrent = { true },
        ) { candidate, _ ->
            attempts += candidate.profileFingerprint
            RawProfileRetry(structuralFailure(candidate.profileFingerprint), candidate.profileFingerprint)
        }

        assertEquals(attempts.distinct(), attempts)
        assertEquals(listOf("a2", "a3"), attempts)
        assertTrue(execution.exhausted)
    }

    @Test
    fun d_profileFromDifferentCanonicalLensIsNeverAttempted() = runBlocking {
        val routeA = route("canon-a", profile("a1"), profile("a2"))
        val routeB = route("canon-b", profile("b1"), profile("b2"))
        val attempts = mutableListOf<String>()
        val execution = RawSameCanonicalFailoverExecutor(
            routes = listOf(routeA, routeB),
            canonicalFingerprint = requireNotNull(routeA.lensFingerprint),
            initialProfileFingerprint = "a1",
        ).recover(
            initialResult = structuralFailure("a1"),
            isSelectionCurrent = { true },
        ) { candidate, _ ->
            attempts += candidate.profileFingerprint
            RawProfileRetry(structuralFailure(candidate.profileFingerprint), candidate.profileFingerprint)
        }

        assertEquals(listOf("a2"), attempts)
        assertTrue(execution.exhausted)
        assertFalse(execution.attemptedProfileFingerprints.any { it.startsWith("b") })
    }

    @Test
    fun e_noEligibleSameCanonicalRawProfileTerminatesCleanly() = runBlocking {
        val route = route("canon-a", profile("a1"))
        var retries = 0
        val execution = executor(route, "a1").recover(
            initialResult = structuralFailure("a1"),
            isSelectionCurrent = { true },
        ) { candidate, _ ->
            retries += 1
            RawProfileRetry(structuralFailure(candidate.profileFingerprint), candidate.profileFingerprint)
        }

        assertEquals(0, retries)
        assertTrue(execution.exhausted)
        assertTrue(execution.result is RawCaptureResult.Failed)
    }

    @Test
    fun f_selectionGenerationChangeBeforeRetryAborts() = runBlocking {
        val route = route("canon-a", profile("a1"), profile("a2"))
        var retries = 0
        val execution = executor(route, "a1").recover(
            initialResult = structuralFailure("a1"),
            isSelectionCurrent = { false },
        ) { candidate, _ ->
            retries += 1
            RawProfileRetry(saved(candidate, route), candidate.profileFingerprint)
        }

        assertEquals(0, retries)
        assertTrue(execution.abortedForSelectionChange)
        assertEquals(RawFailureKind.STALE_SELECTION, (execution.result as RawCaptureResult.Failed).failureKind)
    }

    @Test
    fun g_staleResultFromA1AfterA2TransportBecomesActiveIsRejected() {
        assertFalse(
            RawCaptureGenerationGuard.isCurrent(
                requestedSelectionGeneration = 12L,
                activeSelectionGeneration = 12L,
                capturedTransportGeneration = 40L,
                activeTransportGeneration = 41L,
                capturedRoutingKey = "route-a1",
                activeRoutingKey = "route-a2",
            ),
        )
    }

    @Test
    fun h_staleRawImageFromA1IsClosedByPairerCleanup() {
        val closed = mutableListOf<String>()
        val pairer = RawFramePairer<String, String>(onDiscardImage = closed::add)
        pairer.offerImage(100L, "a1-image")
        pairer.offerResult(200L, "a2-result")

        pairer.clear()

        assertEquals(listOf("a1-image"), closed)
        assertEquals(0, pairer.pendingImageCount())
    }

    @Test
    fun i_storageOrDngFailureDoesNotTriggerProfileFailover() = runBlocking {
        val route = route("canon-a", profile("a1"), profile("a2"))
        var retries = 0
        val outputFailure = RawCaptureResult.Failed(
            reason = "MediaStore write failed",
            structural = false,
            diagnostics = diagnostics("a1", route),
            failureKind = RawFailureKind.OUTPUT_WRITE,
        )
        val execution = executor(route, "a1").recover(
            initialResult = outputFailure,
            isSelectionCurrent = { true },
        ) { candidate, _ ->
            retries += 1
            RawProfileRetry(saved(candidate, route), candidate.profileFingerprint)
        }

        assertEquals(0, retries)
        assertFalse(execution.exhausted)
        assertEquals(RawFailureKind.OUTPUT_WRITE, (execution.result as RawCaptureResult.Failed).failureKind)
    }

    @Test
    fun j_candidateExhaustionIsBounded() = runBlocking {
        val route = route("canon-a", profile("a1"), profile("a2"), profile("a3"))
        var retries = 0
        val execution = executor(route, "a1").recover(
            initialResult = structuralFailure("a1"),
            isSelectionCurrent = { true },
        ) { candidate, _ ->
            retries += 1
            RawProfileRetry(structuralFailure(candidate.profileFingerprint), candidate.profileFingerprint)
        }

        assertEquals(2, retries)
        assertTrue(execution.exhausted)
        assertEquals(3, execution.attemptedProfileFingerprints.size)
    }

    @Test
    fun k_successfulFailoverReportsActualA2RoutingIdentity() = runBlocking {
        val a1 = profile("a1", openCameraId = "0", physicalCameraId = "wide-a1")
        val a2 = profile("a2", openCameraId = "0", physicalCameraId = "wide-a2")
        val route = route("canon-a", a1, a2)
        val execution = executor(route, "a1").recover(
            initialResult = structuralFailure("a1"),
            isSelectionCurrent = { true },
        ) { candidate, _ ->
            RawProfileRetry(saved(candidate, route), candidate.profileFingerprint)
        }

        val saved = execution.result as RawCaptureResult.Saved
        assertEquals("a2", saved.diagnostics.context?.profileFingerprint)
        assertEquals("0", saved.diagnostics.context?.openCameraId)
        assertEquals("wide-a2", saved.diagnostics.context?.streamPhysicalCameraId)
        assertTrue(saved.diagnostics.context?.routingKey?.contains("wide-a2") == true)
    }

    @Test
    fun l_previewRemainsCallerOwnedWhenAllRawProfilesFail() = runBlocking {
        val route = route("canon-a", profile("a1"), profile("a2"))
        var previewUsable = true
        val execution = executor(route, "a1").recover(
            initialResult = structuralFailure("a1"),
            isSelectionCurrent = { true },
        ) { candidate, _ ->
            RawProfileRetry(structuralFailure(candidate.profileFingerprint), candidate.profileFingerprint)
        }

        assertTrue(previewUsable)
        assertTrue(execution.exhausted)
        previewUsable = false
        assertFalse(previewUsable)
    }

    @Test
    fun structuralRawFailureUpdatesOnlyRawProfileTrust() {
        val route = route("canon-a", profile("a1")).let { canonical ->
            canonical.copy(canonicalRouteId = canonical.profiles.first().profileId)
        }
        val observation = RawProfileTrustObserver.observation(
            route,
            structuralFailure("a1"),
            attemptEpochMs = 123L,
        )

        assertEquals(CameraRawTrust.RAW_REJECTED, observation?.raw)
        assertEquals(route.trust.session, observation?.session)
        assertEquals(route.trust.metadata, observation?.metadata)
    }

    @Test
    fun outputWriteFailureDoesNotDamageCameraProfileTrust() {
        val route = route("canon-a", profile("a1"))
        val failed = RawCaptureResult.Failed(
            reason = "disk full",
            structural = false,
            diagnostics = diagnostics("a1", route),
            failureKind = RawFailureKind.OUTPUT_WRITE,
        )

        assertNull(RawProfileTrustObserver.observation(route, failed))
    }

    @Test
    fun successfulRawCaptureMarksProfileRawVerified() {
        val route = route("canon-a", profile("a1"))
        val observation = RawProfileTrustObserver.observation(
            route,
            saved(route.profiles.first(), route),
            attemptEpochMs = 321L,
        )

        assertEquals(CameraRawTrust.RAW_VERIFIED, observation?.raw)
    }

    private fun executor(route: CameraRoute, initialProfile: String) =
        RawSameCanonicalFailoverExecutor(
            routes = listOf(route),
            canonicalFingerprint = requireNotNull(route.lensFingerprint),
            initialProfileFingerprint = initialProfile,
        )

    private fun route(
        canonical: String,
        vararg profiles: CameraProfile,
    ): CameraRoute {
        val first = profiles.first()
        return CameraRoute(
            canonicalRouteId = first.profileId,
            discoveredCameraId = first.discoveredCameraId,
            openCameraId = first.openCameraId,
            streamPhysicalCameraId = first.streamPhysicalCameraId,
            logicalParentCameraId = first.logicalParentCameraId,
            routeKind = first.routeKind,
            sources = first.discoverySources,
            minimalMetadata = rawMetadata(),
            lensFingerprint = LensFingerprint(canonical, FingerprintStrategy.STABLE_METADATA),
            storedProfiles = profiles.toList(),
            preferredProfileId = first.profileId,
        )
    }

    private fun profile(
        fingerprint: String,
        openCameraId: String = fingerprint,
        physicalCameraId: String? = null,
    ) = CameraProfile(
        profileId = "profile-$fingerprint",
        profileFingerprint = fingerprint,
        discoveredCameraId = physicalCameraId ?: openCameraId,
        openCameraId = openCameraId,
        streamPhysicalCameraId = physicalCameraId,
        logicalParentCameraId = if (physicalCameraId != null) openCameraId else null,
        routeKind = if (physicalCameraId != null) {
            CameraRouteKind.LOGICAL_PHYSICAL_MEMBER
        } else {
            CameraRouteKind.PUBLIC_DIRECT
        },
        discoverySources = setOf(CameraDiscoverySource.JAVA_PUBLIC),
        metadata = rawMetadata(),
        sessionTrust = CameraSessionTrust.SESSION_VERIFIED,
        rawTrust = CameraRawTrust.UNKNOWN,
        metadataTrust = CameraMetadataTrust.METADATA_VALID,
    )

    private fun rawMetadata() = MinimalCameraMetadata(
        facing = LensFacing.BACK,
        rawCapabilityAdvertised = CapabilitySupport.SUPPORTED,
        rawStreamActuallyDeclared = CapabilitySupport.SUPPORTED,
        rawSizes = listOf(Size2D(4000, 3000)),
        previewStreamActuallyDeclared = CapabilitySupport.SUPPORTED,
        privatePreviewSizes = listOf(Size2D(1920, 1080)),
    )

    private fun structuralFailure(profileFingerprint: String) = RawCaptureResult.Failed(
        reason = "RAW session rejected",
        structural = true,
        diagnostics = RawCaptureDiagnostics(
            context = RawCaptureContext(
                selectionGeneration = 9L,
                canonicalFingerprint = "canon-a",
                profileFingerprint = profileFingerprint,
                routingKey = "route-$profileFingerprint",
                openCameraId = profileFingerprint,
                streamPhysicalCameraId = null,
                rawSize = Size2D(4000, 3000),
                captureToken = 1L,
                transportGeneration = 1L,
            ),
            rawSupported = RawSupportState.SUPPORTED,
            selectedRawSize = Size2D(4000, 3000),
            lastRawError = "RAW session rejected",
        ),
        failureKind = RawFailureKind.SESSION_CONFIGURATION,
    )

    private fun saved(profile: CameraProfile, route: CameraRoute): RawCaptureResult.Saved {
        val diagnostics = diagnostics(profile.profileFingerprint, route, profile)
        return RawCaptureResult.Saved(
            file = RawSavedFile(
                uri = "content://camera/${profile.profileFingerprint}",
                bytes = 1024L,
                width = 4000,
                height = 3000,
            ),
            diagnostics = diagnostics,
        )
    }

    private fun diagnostics(
        profileFingerprint: String,
        route: CameraRoute,
        profile: CameraProfile = route.profiles.first { it.profileFingerprint == profileFingerprint },
    ) = RawCaptureDiagnostics(
        context = RawCaptureContext(
            selectionGeneration = 9L,
            canonicalFingerprint = route.lensFingerprint?.value,
            profileFingerprint = profile.profileFingerprint,
            routingKey = profileRoutingKey(profile),
            openCameraId = profile.openCameraId,
            streamPhysicalCameraId = profile.streamPhysicalCameraId,
            rawSize = Size2D(4000, 3000),
            captureToken = 2L,
            transportGeneration = 2L,
        ),
        rawSupported = RawSupportState.SUPPORTED,
        availableRawSizes = listOf(Size2D(4000, 3000)),
        selectedRawSize = Size2D(4000, 3000),
        dngWidth = 4000,
        dngHeight = 3000,
        dngBytes = 1024L,
    )

    private fun profileRoutingKey(profile: CameraProfile): String = buildString {
        append(profile.openCameraId.length)
        append(':')
        append(profile.openCameraId)
        append('|')
        append(profile.streamPhysicalCameraId?.length ?: 0)
        append(':')
        append(profile.streamPhysicalCameraId.orEmpty())
    }
}
