package com.sahidcode404.camex.core.camera.runtime

import android.content.Context
import com.sahidcode404.camex.core.camera.CameraSessionController
import com.sahidcode404.camex.core.camera.CameraSessionEvent
import com.sahidcode404.camex.core.camera.CameraSessionState
import com.sahidcode404.camex.core.camera.DefaultCameraSessionController
import com.sahidcode404.camex.core.camera.diagnostics.CameraStartupMilestone
import com.sahidcode404.camex.core.camera.discovery.CameraDiscoveryCoordinator
import com.sahidcode404.camex.core.camera.discovery.CameraDiscoveryTrigger
import com.sahidcode404.camex.core.camera.raw.RawCaptureRegistry
import com.sahidcode404.camex.core.camera.raw.RawCaptureRequest
import com.sahidcode404.camex.core.camera.raw.RawCaptureResult
import com.sahidcode404.camex.core.camera.raw.RawFailureKind
import com.sahidcode404.camex.core.camera.raw.RawProfileRetry
import com.sahidcode404.camex.core.camera.raw.RawProfileTrustObserver
import com.sahidcode404.camex.core.camera.raw.RawSameCanonicalFailoverExecutor
import com.sahidcode404.camex.core.camera.raw.mayTriggerProfileFailover
import com.sahidcode404.camex.core.camera.topology.CameraProfile
import com.sahidcode404.camex.core.camera.topology.CameraRoute
import com.sahidcode404.camex.core.camera.topology.CameraTopology
import com.sahidcode404.camex.core.camera.topology.profileForRoutingKey
import com.sahidcode404.camex.core.camera.topology.profileLensDescriptors
import com.sahidcode404.camex.core.camera.topology.toLensDescriptor
import com.sahidcode404.camex.core.camera.validation.CameraRouteValidator
import com.sahidcode404.camex.core.logic.LensDuplicateFilter
import com.sahidcode404.camex.core.logic.PrimaryLensSelector
import com.sahidcode404.camex.core.model.FingerprintStrategy
import com.sahidcode404.camex.core.model.LensDescriptor
import com.sahidcode404.camex.core.model.LensFacing
import com.sahidcode404.camex.core.model.LensFingerprint
import com.sahidcode404.camex.core.model.ProbeFailureKind
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

sealed interface CameraRuntimePhase {
    data object BootstrappingCache : CameraRuntimePhase
    data class CacheReady(val routeCount: Int, val hit: Boolean) : CameraRuntimePhase
    data class OpeningPrimary(val routingKey: String) : CameraRuntimePhase
    data class Previewing(val routingKey: String) : CameraRuntimePhase
    data object ReconcilingTopology : CameraRuntimePhase
    data object Ready : CameraRuntimePhase
    data class ErrorRecoverable(val detail: String) : CameraRuntimePhase
    data object Paused : CameraRuntimePhase
}

/**
 * Application-camera orchestrator. Discovery owns topology and the wrapped session controller owns
 * all CameraDevice/session work. Phase 1B adds profile failover without adding another camera owner.
 * Verified active selection is tracked here so UI never has to guess facing from list order.
 */
class CameraRuntimeCoordinator(
    context: Context,
    private val scope: CoroutineScope,
    val discovery: CameraDiscoveryCoordinator = CameraDiscoveryCoordinator(context),
    val session: CameraSessionController = FailoverCameraSessionController(
        DefaultCameraSessionController(context),
        scope,
    ),
) : Closeable {
    private val startMutex = Mutex()
    private val selectionRequestMutex = Mutex()
    private val selectionTracker = ActiveCameraSelectionTracker()
    private val selectionIntentEpoch = AtomicLong(0L)
    private val mutablePhase = MutableStateFlow<CameraRuntimePhase>(
        CameraRuntimePhase.BootstrappingCache,
    )
    private val mutableActiveSelection = MutableStateFlow<ActiveCameraSelection?>(null)
    private var reconciliationJob: Job? = null
    private var permissionGranted = false
    private var preferredRearFingerprint: String? = null
    private var oneXReferenceFingerprint: String? = null

    val phase: StateFlow<CameraRuntimePhase> = mutablePhase.asStateFlow()
    val topology: StateFlow<CameraTopology> = discovery.topologyRepository.topology
    val activeSelection: StateFlow<ActiveCameraSelection?> = mutableActiveSelection.asStateFlow()

    init {
        RawCaptureRegistry.initialize(context)
        scope.launch {
            mutableActiveSelection.collect { selection ->
                if (selection?.verified == true) {
                    RawCaptureRegistry.updateActiveSelectionDetails(
                        selectionGeneration = selection.selectionGeneration,
                        routingKey = selection.activeProfileRoutingKey,
                        canonicalFingerprint = selection.canonicalLensFingerprint?.value,
                        profileFingerprint = selection.activeProfileFingerprint,
                    )
                } else {
                    RawCaptureRegistry.invalidateSelection()
                }
            }
        }
        scope.launch {
            topology.collectLatest { value ->
                // Runtime receives every profile so the failover wrapper can rotate routes. All
                // profiles belonging to one optical lens share one optical fingerprint.
                val lenses = value.toProfileLenses()
                session.updateAvailableLenses(lenses)
                mutableActiveSelection.value = selectionTracker.reconcile(
                    sessionState = session.state.value,
                    sessionSnapshot = session.snapshot.value,
                    topology = value,
                )
                if (value.routes.isNotEmpty()) {
                    discovery.startupTrace.mark(CameraStartupMilestone.UI_LENS_LIST_READY)
                }
            }
        }
        scope.launch { session.sessionEvents.collect(::handleSessionEvent) }
        scope.launch {
            session.state.collect { state ->
                mutableActiveSelection.value = selectionTracker.updateSessionState(state)
            }
        }
    }

    suspend fun bootstrapCache(): CameraTopology {
        mutablePhase.value = CameraRuntimePhase.BootstrappingCache
        val topology = discovery.bootstrapCache()
        session.updateAvailableLenses(topology.toProfileLenses())
        mutableActiveSelection.value = selectionTracker.reconcile(
            sessionState = session.state.value,
            sessionSnapshot = session.snapshot.value,
            topology = topology,
        )
        mutablePhase.value = CameraRuntimePhase.CacheReady(
            routeCount = topology.routes.size,
            hit = discovery.snapshot.value.cacheHit,
        )
        return topology
    }

    /** Opens cache/seed immediately; advertised and deep discovery continue in a sibling job. */
    suspend fun start(
        preferredRearFingerprint: String?,
        oneXReferenceFingerprint: String?,
    ) = startMutex.withLock {
        permissionGranted = true
        this.preferredRearFingerprint = preferredRearFingerprint
        this.oneXReferenceFingerprint = oneXReferenceFingerprint
        val cached = discovery.bootstrapCache()
        session.resume()
        session.updateAvailableLenses(cached.toProfileLenses())

        var target = chooseStartupLens(cached)
        if (target == null) {
            val seeded = discovery.seedPrimaryRoute()
            session.updateAvailableLenses(seeded.toProfileLenses())
            target = chooseStartupLens(seeded)
        }
        target?.let { openPrimary(it) }

        reconciliationJob?.cancel()
        val runDeep = discovery.snapshot.value.initialDeepScanRequired
        reconciliationJob = scope.launch {
            mutablePhase.value = CameraRuntimePhase.ReconcilingTopology
            try {
                val topology = discovery.reconcile(
                    trigger = CameraDiscoveryTrigger.STARTUP,
                    includeDeepScan = runDeep,
                )
                if (session.snapshot.value.selectedRoutingKey == null && permissionGranted) {
                    chooseStartupLens(topology)?.let { openPrimary(it) }
                }
                mutablePhase.value = CameraRuntimePhase.Ready
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (fatal: VirtualMachineError) {
                throw fatal
            } catch (fatal: ThreadDeath) {
                throw fatal
            } catch (error: Throwable) {
                mutablePhase.value = CameraRuntimePhase.ErrorRecoverable(
                    "Camera discovery failed: ${error.javaClass.simpleName}",
                )
            }
        }
    }

    /** Explicit canonical-lens request. Automatic sibling-profile failover remains one generation. */
    suspend fun selectLens(lens: LensDescriptor) {
        selectionIntentEpoch.incrementAndGet()
        selectionRequestMutex.withLock {
            RawCaptureRegistry.invalidateSelection()
            selectionTracker.beginSelection(lens)
            session.switchTo(lens)
        }
    }

    /**
     * One shutter operation. The first attempt uses the verified active profile; only a structural
     * RAW incompatibility may rotate through other ranked profiles of the same canonical lens.
     */
    suspend fun captureRaw(): RawCaptureResult {
        val initialSelection = mutableActiveSelection.value
        if (initialSelection?.verified != true) {
            return RawCaptureRegistry.captureCurrent()
        }

        val generation = initialSelection.selectionGeneration
        val intentEpoch = selectionIntentEpoch.get()
        val canonicalFingerprint = initialSelection.canonicalLensFingerprint
        val initialProfileFingerprint = initialSelection.activeProfileFingerprint
            ?: profileFingerprintForRoutingKey(
                initialSelection.activeProfileRoutingKey,
                canonicalFingerprint,
            )

        RawCaptureRegistry.updateActiveSelectionDetails(
            selectionGeneration = generation,
            routingKey = initialSelection.activeProfileRoutingKey,
            canonicalFingerprint = canonicalFingerprint?.value,
            profileFingerprint = initialProfileFingerprint,
        )
        val initialResult = RawCaptureRegistry.captureRaw(
            RawCaptureRequest(
                selectionGeneration = generation,
                canonicalFingerprint = canonicalFingerprint?.value,
                profileFingerprint = initialProfileFingerprint,
            ),
        )
        recordRawProfileTrust(initialSelection.activeProfileRoutingKey, initialResult)

        if (!initialResult.mayTriggerProfileFailover() ||
            canonicalFingerprint == null || initialProfileFingerprint == null
        ) {
            return initialResult
        }

        val executor = RawSameCanonicalFailoverExecutor(
            routes = topology.value.routes,
            canonicalFingerprint = canonicalFingerprint,
            initialProfileFingerprint = initialProfileFingerprint,
        )
        val execution = executor.recover(
            initialResult = initialResult,
            isSelectionCurrent = {
                isOriginalRawSelectionCurrent(generation, canonicalFingerprint, intentEpoch)
            },
            retry = { candidate, _ ->
                retryRawOnSameCanonicalProfile(
                    candidate = candidate,
                    canonicalFingerprint = canonicalFingerprint,
                    generation = generation,
                    intentEpoch = intentEpoch,
                )
            },
        )
        RawCaptureRegistry.publishResult(execution.result)
        return execution.result
    }

    suspend fun normalRescan() {
        selectionIntentEpoch.incrementAndGet()
        RawCaptureRegistry.invalidateSelection()
        reconciliationJob?.cancel()
        session.clearTransientFailureMemory()
        reconciliationJob = scope.launch {
            mutablePhase.value = CameraRuntimePhase.ReconcilingTopology
            try {
                discovery.normalRescan()
                reopenSelectedAfterRecoverableFailure()
                mutablePhase.value = CameraRuntimePhase.Ready
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutablePhase.value = CameraRuntimePhase.ErrorRecoverable(
                    "Camera rescan failed: ${error.javaClass.simpleName}",
                )
            }
        }
    }

    suspend fun deepRescan() {
        selectionIntentEpoch.incrementAndGet()
        RawCaptureRegistry.invalidateSelection()
        reconciliationJob?.cancel()
        session.clearTransientFailureMemory()
        reconciliationJob = scope.launch {
            mutablePhase.value = CameraRuntimePhase.ReconcilingTopology
            try {
                discovery.deepRescan()
                reopenSelectedAfterRecoverableFailure()
                mutablePhase.value = CameraRuntimePhase.Ready
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutablePhase.value = CameraRuntimePhase.ErrorRecoverable(
                    "Deep camera rescan failed: ${error.javaClass.simpleName}",
                )
            }
        }
    }

    suspend fun resetDiscoveryCache() {
        selectionIntentEpoch.incrementAndGet()
        reconciliationJob?.cancel()
        discovery.resetDiscoveryCache()
        session.clearTransientFailureMemory()
        session.updateAvailableLenses(emptyList())
        RawCaptureRegistry.invalidateSelection()
        mutablePhase.value = CameraRuntimePhase.CacheReady(routeCount = 0, hit = false)
        if (permissionGranted) start(preferredRearFingerprint, oneXReferenceFingerprint)
    }

    suspend fun pause() {
        selectionIntentEpoch.incrementAndGet()
        permissionGranted = false
        reconciliationJob?.cancel()
        RawCaptureRegistry.invalidateSelection()
        session.pause()
        mutablePhase.value = CameraRuntimePhase.Paused
    }

    suspend fun resume() {
        permissionGranted = true
        session.resume()
    }

    override fun close() {
        selectionIntentEpoch.incrementAndGet()
        reconciliationJob?.cancel()
        RawCaptureRegistry.invalidateSelection()
        session.close()
    }

    private suspend fun retryRawOnSameCanonicalProfile(
        candidate: CameraProfile,
        canonicalFingerprint: LensFingerprint,
        generation: Long,
        intentEpoch: Long,
    ): RawProfileRetry {
        if (!isOriginalRawSelectionCurrent(generation, canonicalFingerprint, intentEpoch)) {
            return RawProfileRetry(
                staleRawFailure("Camera selection changed before RAW profile retry"),
                candidate.profileFingerprint,
            )
        }

        val canonicalRoute = topology.value.routes.firstOrNull {
            it.lensFingerprint?.value == canonicalFingerprint.value
        }
        val exactCandidate = canonicalRoute?.profiles?.firstOrNull {
            it.profileFingerprint == candidate.profileFingerprint
        }
        if (canonicalRoute == null || exactCandidate == null) {
            return RawProfileRetry(
                staleRawFailure("RAW retry profile is no longer part of the selected canonical lens"),
                candidate.profileFingerprint,
            )
        }
        val descriptor = exactCandidate.toLensDescriptor(
            opticalFingerprint = canonicalFingerprint,
            role = canonicalRoute.role,
            roleConfidence = canonicalRoute.roleConfidence,
        )

        selectionRequestMutex.withLock {
            if (!isOriginalRawSelectionCurrent(generation, canonicalFingerprint, intentEpoch)) {
                return RawProfileRetry(
                    staleRawFailure("Camera selection changed before RAW session reconfiguration"),
                    exactCandidate.profileFingerprint,
                )
            }
            RawCaptureRegistry.invalidateSelection()
            // Deliberately do not call selectionTracker.beginSelection: this is an automatic
            // transport retry underneath the same canonical lens and the same selection generation.
            session.switchTo(descriptor)
        }

        if (!isOriginalRawSelectionCurrent(generation, canonicalFingerprint, intentEpoch)) {
            return RawProfileRetry(
                staleRawFailure("Camera selection changed during RAW session reconfiguration"),
                exactCandidate.profileFingerprint,
            )
        }

        val preview = session.state.value as? CameraSessionState.Previewing
        if (preview == null) {
            val error = (session.state.value as? CameraSessionState.ErrorRecoverable)?.error
            val structural = error?.kind.isStructuralProfileFailure()
            val reason = error?.detail?.takeIf(String::isNotBlank)
                ?: "RAW retry profile did not produce a verified preview"
            return RawProfileRetry(
                RawCaptureResult.Failed(
                    reason = reason,
                    structural = structural,
                    diagnostics = RawCaptureRegistry.rawCaptureState.value.diagnostics.copy(
                        lastRawError = reason,
                    ),
                    failureKind = if (structural) {
                        RawFailureKind.SESSION_CONFIGURATION
                    } else {
                        RawFailureKind.CAPTURE_FAILED
                    },
                ),
                exactCandidate.profileFingerprint,
            )
        }

        val verified = withTimeoutOrNull(RAW_PROFILE_VERIFY_TIMEOUT_MILLIS) {
            activeSelection.first { selection ->
                selection?.verified == true &&
                    selection.selectionGeneration == generation &&
                    selection.canonicalLensFingerprint?.value == canonicalFingerprint.value &&
                    selection.activeProfileRoutingKey == preview.routingKey
            }
        }
        if (verified == null) {
            return RawProfileRetry(
                RawCaptureResult.Failed(
                    reason = "Timed out waiting for verified RAW retry profile",
                    structural = false,
                    diagnostics = RawCaptureRegistry.rawCaptureState.value.diagnostics.copy(
                        lastRawError = "RAW retry verification timeout",
                    ),
                    failureKind = RawFailureKind.TIMEOUT,
                ),
                exactCandidate.profileFingerprint,
            )
        }

        if (!isOriginalRawSelectionCurrent(generation, canonicalFingerprint, intentEpoch)) {
            return RawProfileRetry(
                staleRawFailure("Camera selection changed before RAW retry capture"),
                verified.activeProfileFingerprint ?: exactCandidate.profileFingerprint,
            )
        }

        val actualProfileFingerprint = verified.activeProfileFingerprint
            ?: profileFingerprintForRoutingKey(preview.routingKey, canonicalFingerprint)
            ?: exactCandidate.profileFingerprint
        val stillSameCanonical = topology.value.routes.firstOrNull {
            it.lensFingerprint?.value == canonicalFingerprint.value
        }?.profiles?.any { it.profileFingerprint == actualProfileFingerprint } == true
        if (!stillSameCanonical) {
            return RawProfileRetry(
                staleRawFailure("RAW retry resolved outside the selected canonical lens"),
                actualProfileFingerprint,
            )
        }

        RawCaptureRegistry.updateActiveSelectionDetails(
            selectionGeneration = generation,
            routingKey = preview.routingKey,
            canonicalFingerprint = canonicalFingerprint.value,
            profileFingerprint = actualProfileFingerprint,
        )
        val result = RawCaptureRegistry.captureRaw(
            RawCaptureRequest(
                selectionGeneration = generation,
                canonicalFingerprint = canonicalFingerprint.value,
                profileFingerprint = actualProfileFingerprint,
            ),
        )
        recordRawProfileTrust(preview.routingKey, result)
        return RawProfileRetry(result, actualProfileFingerprint)
    }

    private fun isOriginalRawSelectionCurrent(
        generation: Long,
        canonicalFingerprint: LensFingerprint,
        intentEpoch: Long,
    ): Boolean {
        if (selectionIntentEpoch.get() != intentEpoch) return false
        val current = selectionTracker.current() ?: return false
        return current.selectionGeneration == generation &&
            current.canonicalLensFingerprint?.value == canonicalFingerprint.value
    }

    private suspend fun recordRawProfileTrust(
        routingKey: String,
        result: RawCaptureResult,
    ) {
        val route = routeForRoutingKey(routingKey) ?: return
        RawProfileTrustObserver.observation(route, result)?.let { observation ->
            discovery.recordTrust(route.canonicalRouteId, observation)
        }
    }

    private fun staleRawFailure(reason: String): RawCaptureResult.Failed =
        RawCaptureResult.Failed(
            reason = reason,
            structural = false,
            diagnostics = RawCaptureRegistry.rawCaptureState.value.diagnostics.copy(
                lastRawError = "stale selection generation",
            ),
            failureKind = RawFailureKind.STALE_SELECTION,
        )

    private fun profileFingerprintForRoutingKey(
        routingKey: String,
        canonicalFingerprint: LensFingerprint?,
    ): String? = topology.value.routes
        .asSequence()
        .filter {
            canonicalFingerprint == null ||
                it.lensFingerprint?.value == canonicalFingerprint.value
        }
        .mapNotNull { it.profileForRoutingKey(routingKey)?.profileFingerprint }
        .firstOrNull()

    private suspend fun openPrimary(lens: LensDescriptor) = selectionRequestMutex.withLock {
        RawCaptureRegistry.invalidateSelection()
        selectionTracker.beginSelection(lens)
        discovery.startupTrace.mark(CameraStartupMilestone.PRIMARY_ROUTE_READY)
        mutablePhase.value = CameraRuntimePhase.OpeningPrimary(lens.identity.routingKey)
        discovery.startupTrace.mark(CameraStartupMilestone.CAMERA_OPEN_REQUESTED)
        session.open(lens)
    }

    private fun chooseStartupLens(topology: CameraTopology): LensDescriptor? {
        // DuplicateFilter is now only a final safety net. Optical fingerprints group profile
        // descriptors before this point, so the selector sees one representative per real lens.
        val lenses = LensDuplicateFilter.filterForSelector(topology.toProfileLenses())
        val preferred = preferredRearFingerprint
        if (!preferred.isNullOrBlank()) {
            lenses.firstOrNull {
                it.facing == LensFacing.BACK && it.fingerprint?.value == preferred
            }?.let { return it }
        }
        val reference = oneXReferenceFingerprint?.let {
            LensFingerprint(it, FingerprintStrategy.STABLE_METADATA)
        }
        return PrimaryLensSelector.select(lenses, reference)
            ?: lenses.firstOrNull { it.facing == LensFacing.BACK }
            ?: lenses.firstOrNull { it.facing == LensFacing.FRONT }
            ?: lenses.firstOrNull()
    }

    private suspend fun reopenSelectedAfterRecoverableFailure() {
        if (!permissionGranted || session.state.value !is CameraSessionState.ErrorRecoverable) return
        val key = session.snapshot.value.selectedRoutingKey ?: return
        val selected = session.snapshot.value.lenses
            .firstOrNull { it.identity.routingKey == key }
            ?: return
        selectionRequestMutex.withLock {
            RawCaptureRegistry.invalidateSelection()
            selectionTracker.beginSelection(selected)
            session.open(selected)
        }
    }

    private suspend fun handleSessionEvent(event: CameraSessionEvent) {
        when (event) {
            is CameraSessionEvent.CameraOpened ->
                discovery.startupTrace.mark(CameraStartupMilestone.CAMERA_OPENED)
            is CameraSessionEvent.SessionConfigured ->
                discovery.startupTrace.mark(CameraStartupMilestone.SESSION_CONFIGURED)
            is CameraSessionEvent.PreviewVerified -> {
                discovery.startupTrace.mark(CameraStartupMilestone.FIRST_PREVIEW_FRAME)
                mutablePhase.value = CameraRuntimePhase.Previewing(event.routingKey)
                mutableActiveSelection.value = selectionTracker.previewVerified(
                    routingKey = event.routingKey,
                    sessionState = session.state.value,
                    sessionSnapshot = session.snapshot.value,
                    topology = topology.value,
                )
                routeForRoutingKey(event.routingKey)?.let { route ->
                    CameraRouteValidator.observation(route, event)?.let { observation ->
                        discovery.recordTrust(route.canonicalRouteId, observation)
                    }
                }
            }
            is CameraSessionEvent.PreviewFailed -> {
                val route = routeForRoutingKey(event.routingKey) ?: return
                CameraRouteValidator.observation(route, event)?.let { observation ->
                    discovery.recordTrust(route.canonicalRouteId, observation)
                }
            }
        }
    }

    /** Reconstruct the exact profile route so trust is persisted per profile, not per optical lens. */
    private fun routeForRoutingKey(routingKey: String): CameraRoute? = topology.value.routes
        .asSequence()
        .mapNotNull { canonical ->
            canonical.profileForRoutingKey(routingKey)?.toRoute(canonical)
        }
        .firstOrNull()

    private fun CameraProfile.toRoute(canonical: CameraRoute): CameraRoute = CameraRoute(
        canonicalRouteId = profileId,
        discoveredCameraId = discoveredCameraId,
        openCameraId = openCameraId,
        streamPhysicalCameraId = streamPhysicalCameraId,
        logicalParentCameraId = logicalParentCameraId,
        routeKind = routeKind,
        sources = discoverySources,
        minimalMetadata = metadata,
        fullCapabilities = fullCapabilities,
        lensFingerprint = canonical.lensFingerprint,
        role = canonical.role,
        roleConfidence = canonical.roleConfidence,
        trust = trust,
    )

    private fun CameraTopology.toProfileLenses(): List<LensDescriptor> = routes
        .flatMap(CameraRoute::profileLensDescriptors)
        .mapIndexed { index, lens -> lens.copy(discoveryOrder = index) }

    private fun ProbeFailureKind?.isStructuralProfileFailure(): Boolean =
        this == ProbeFailureKind.INVALID_METADATA ||
            this == ProbeFailureKind.SESSION_CONFIGURATION

    private companion object {
        const val RAW_PROFILE_VERIFY_TIMEOUT_MILLIS = 3_000L
    }
}
