package com.sahidcode404.camex.core.camera.runtime

import android.content.Context
import com.sahidcode404.camex.core.camera.CameraSessionController
import com.sahidcode404.camex.core.camera.CameraSessionEvent
import com.sahidcode404.camex.core.camera.CameraSessionState
import com.sahidcode404.camex.core.camera.DefaultCameraSessionController
import com.sahidcode404.camex.core.camera.diagnostics.CameraStartupMilestone
import com.sahidcode404.camex.core.camera.discovery.CameraDiscoveryCoordinator
import com.sahidcode404.camex.core.camera.discovery.CameraDiscoveryTrigger
import com.sahidcode404.camex.core.camera.topology.CameraProfile
import com.sahidcode404.camex.core.camera.topology.CameraRoute
import com.sahidcode404.camex.core.camera.topology.CameraTopology
import com.sahidcode404.camex.core.camera.topology.profileForRoutingKey
import com.sahidcode404.camex.core.camera.topology.profileLensDescriptors
import com.sahidcode404.camex.core.camera.validation.CameraRouteValidator
import com.sahidcode404.camex.core.logic.LensDuplicateFilter
import com.sahidcode404.camex.core.logic.PrimaryLensSelector
import com.sahidcode404.camex.core.model.FingerprintStrategy
import com.sahidcode404.camex.core.model.LensDescriptor
import com.sahidcode404.camex.core.model.LensFacing
import com.sahidcode404.camex.core.model.LensFingerprint
import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val mutablePhase = MutableStateFlow<CameraRuntimePhase>(
        CameraRuntimePhase.BootstrappingCache,
    )
    private var reconciliationJob: Job? = null
    private var permissionGranted = false
    private var preferredRearFingerprint: String? = null
    private var oneXReferenceFingerprint: String? = null

    val phase: StateFlow<CameraRuntimePhase> = mutablePhase.asStateFlow()
    val topology: StateFlow<CameraTopology> = discovery.topologyRepository.topology

    init {
        scope.launch {
            topology.collectLatest { value ->
                // Runtime receives every profile so the failover wrapper can rotate routes. All
                // profiles belonging to one optical lens share one optical fingerprint.
                val lenses = value.toProfileLenses()
                session.updateAvailableLenses(lenses)
                if (value.routes.isNotEmpty()) {
                    discovery.startupTrace.mark(CameraStartupMilestone.UI_LENS_LIST_READY)
                }
            }
        }
        scope.launch { session.sessionEvents.collect(::handleSessionEvent) }
    }

    suspend fun bootstrapCache(): CameraTopology {
        mutablePhase.value = CameraRuntimePhase.BootstrappingCache
        val topology = discovery.bootstrapCache()
        session.updateAvailableLenses(topology.toProfileLenses())
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

    suspend fun normalRescan() {
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
        reconciliationJob?.cancel()
        discovery.resetDiscoveryCache()
        session.clearTransientFailureMemory()
        session.updateAvailableLenses(emptyList())
        mutablePhase.value = CameraRuntimePhase.CacheReady(routeCount = 0, hit = false)
        if (permissionGranted) start(preferredRearFingerprint, oneXReferenceFingerprint)
    }

    suspend fun pause() {
        permissionGranted = false
        reconciliationJob?.cancel()
        session.pause()
        mutablePhase.value = CameraRuntimePhase.Paused
    }

    suspend fun resume() {
        permissionGranted = true
        session.resume()
    }

    override fun close() {
        reconciliationJob?.cancel()
        session.close()
    }

    private suspend fun openPrimary(lens: LensDescriptor) {
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
        session.snapshot.value.lenses.firstOrNull { it.identity.routingKey == key }?.let {
            session.open(it)
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
}
