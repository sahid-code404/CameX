package com.sahidcode404.camex.core.camera.runtime

import com.sahidcode404.camex.core.camera.cache.CameraTrustPolicy
import com.sahidcode404.camex.core.camera.cache.CameraTrustSnapshot
import com.sahidcode404.camex.core.camera.topology.CameraEnvironmentFingerprint
import com.sahidcode404.camex.core.camera.topology.CameraProfile
import com.sahidcode404.camex.core.camera.topology.CameraRoute
import com.sahidcode404.camex.core.camera.topology.CameraRouteEvidence
import com.sahidcode404.camex.core.camera.topology.CameraRouteTrust
import com.sahidcode404.camex.core.camera.topology.CameraTopology
import com.sahidcode404.camex.core.camera.topology.CameraTopologyResolver
import com.sahidcode404.camex.core.camera.topology.TopologyReconciliationMode
import com.sahidcode404.camex.core.camera.topology.profile
import com.sahidcode404.camex.core.camera.topology.withProfileTrust
import com.sahidcode404.camex.core.camera.topology.withUniqueCanonicalFingerprints
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sole owner of canonical optical topology in a running process. Backends submit route/profile
 * evidence; reconciliation remains pure and serialized separately from CameraDevice operations.
 */
class CameraTopologyRepository(
    environment: CameraEnvironmentFingerprint,
) {
    private val updateMutex = Mutex()
    private val liveEvidence = linkedMapOf<String, CameraRouteEvidence>()
    private var bootstrapCache: CameraTopology? = null
    private var trustSnapshot: CameraTrustSnapshot? = null
    private var environmentFingerprint = environment
    private val mutableTopology = kotlinx.coroutines.flow.MutableStateFlow(emptyTopology())

    val topology: StateFlow<CameraTopology> = mutableTopology

    suspend fun installCachedTopology(
        cached: CameraTopology?,
        trust: CameraTrustSnapshot?,
    ): CameraTopology = updateMutex.withLock {
        bootstrapCache = cached
        trustSnapshot = trust
        val resolved = CameraTopologyResolver.resolve(
            environmentFingerprint = environmentFingerprint,
            evidence = emptyList(),
            cachedTopology = cached,
            mode = TopologyReconciliationMode.CACHE_BOOTSTRAP,
        ).publishableWithTrust(trust)
        mutableTopology.value = resolved
        resolved
    }

    suspend fun upsertEvidence(
        evidence: Collection<CameraRouteEvidence>,
        mode: TopologyReconciliationMode = TopologyReconciliationMode.INCREMENTAL,
    ): CameraTopology = updateMutex.withLock {
        evidence.forEach { candidate -> liveEvidence[candidate.evidenceKey()] = candidate }
        resolveLocked(mode)
    }

    suspend fun reconcile(
        mode: TopologyReconciliationMode = TopologyReconciliationMode.INCREMENTAL,
    ): CameraTopology = updateMutex.withLock { resolveLocked(mode) }

    /** Adds the cheap advertised-ID signature after cache-first startup has already completed. */
    suspend fun refineAdvertisedCameraIds(cameraIds: Collection<String>): CameraTopology =
        updateMutex.withLock {
            environmentFingerprint = environmentFingerprint.copy(
                advertisedTopologySignature = CameraEnvironmentFingerprint.advertisedSignature(cameraIds),
            )
            resolveLocked(TopologyReconciliationMode.INCREMENTAL)
        }

    /** A normal rescan drops only process-local observations; cached hidden profiles remain usable. */
    suspend fun invalidateLiveEvidence(): CameraTopology = updateMutex.withLock {
        liveEvidence.clear()
        resolveLocked(TopologyReconciliationMode.CACHE_BOOTSTRAP)
    }

    /** Update one transport profile only; then promote the best remaining profile for its lens. */
    suspend fun updateRouteTrust(
        canonicalRouteId: String,
        observation: CameraRouteTrust,
    ): CameraTopology = updateMutex.withLock {
        val current = mutableTopology.value
        mutableTopology.value = current.copy(
            routes = current.routes.map { route ->
                val profile = route.profile(canonicalRouteId) ?: return@map route
                route.withProfileTrust(
                    canonicalRouteId,
                    CameraTrustPolicy.merge(profile.trust, observation),
                )
            },
        ).withUniqueCanonicalFingerprints()
        mutableTopology.value
    }

    /** Makes a successfully persisted reconciliation the baseline for later in-process rescans. */
    suspend fun commitPersistedBaseline(
        topology: CameraTopology,
        trust: CameraTrustSnapshot,
    ) = updateMutex.withLock {
        bootstrapCache = topology.withUniqueCanonicalFingerprints()
        trustSnapshot = trust
    }

    suspend fun clear(): CameraTopology = updateMutex.withLock {
        liveEvidence.clear()
        bootstrapCache = null
        trustSnapshot = null
        emptyTopology().also { mutableTopology.value = it }
    }

    /** Finds either the preferred profile or an alias profile by transport/profile ID. */
    fun route(canonicalRouteId: String): CameraRoute? = mutableTopology.value.routes
        .asSequence()
        .mapNotNull { canonical ->
            canonical.profile(canonicalRouteId)?.toRoute(canonical)
        }
        .firstOrNull()

    private fun resolveLocked(mode: TopologyReconciliationMode): CameraTopology {
        val resolved = CameraTopologyResolver.resolve(
            environmentFingerprint = environmentFingerprint,
            evidence = liveEvidence.values,
            cachedTopology = bootstrapCache,
            mode = mode,
        ).publishableWithTrust(trustSnapshot)
        mutableTopology.value = resolved
        return resolved
    }

    /** Collision repair precedes trust application so old ambiguous trust cannot attach to a new lens. */
    private fun CameraTopology.publishableWithTrust(
        snapshot: CameraTrustSnapshot?,
    ): CameraTopology {
        val unique = withUniqueCanonicalFingerprints()
        return snapshot?.let { CameraTrustPolicy.apply(it, unique) } ?: unique
    }

    private fun emptyTopology() = CameraTopology(environmentFingerprint = environmentFingerprint)

    private fun CameraRouteEvidence.evidenceKey(): String = buildString {
        append(source.ordinal).append('|')
        append(openCameraId.length).append(':').append(openCameraId).append('|')
        append(streamPhysicalCameraId?.length ?: 0).append(':')
        append(streamPhysicalCameraId.orEmpty())
    }

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
}

/** Read-only canonical-lens route projection used by UI/runtime consumers. */
class CameraRouteRepository(
    topologyRepository: CameraTopologyRepository,
    scope: CoroutineScope,
) {
    val routes: StateFlow<List<CameraRoute>> = topologyRepository.topology
        .map { topology -> topology.routes }
        .stateIn(scope, SharingStarted.Eagerly, topologyRepository.topology.value.routes)
}
