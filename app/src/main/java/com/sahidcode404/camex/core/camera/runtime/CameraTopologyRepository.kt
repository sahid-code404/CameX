package com.sahidcode404.camex.core.camera.runtime

import com.sahidcode404.camex.core.camera.cache.CameraTrustPolicy
import com.sahidcode404.camex.core.camera.cache.CameraTrustSnapshot
import com.sahidcode404.camex.core.camera.topology.CameraEnvironmentFingerprint
import com.sahidcode404.camex.core.camera.topology.CameraRoute
import com.sahidcode404.camex.core.camera.topology.CameraRouteEvidence
import com.sahidcode404.camex.core.camera.topology.CameraRouteTrust
import com.sahidcode404.camex.core.camera.topology.CameraTopology
import com.sahidcode404.camex.core.camera.topology.CameraTopologyResolver
import com.sahidcode404.camex.core.camera.topology.TopologyReconciliationMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sole owner of the canonical topology in a running process. Backends submit evidence; callers
 * cannot mutate individual route lists. Reconciliation is pure and serialized separately from
 * the CameraDevice/session mutex.
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
        ).withTrust(trust)
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

    /** A normal rescan drops only process-local observations; cached hidden routes remain usable. */
    suspend fun invalidateLiveEvidence(): CameraTopology = updateMutex.withLock {
        liveEvidence.clear()
        resolveLocked(TopologyReconciliationMode.CACHE_BOOTSTRAP)
    }

    suspend fun updateRouteTrust(
        canonicalRouteId: String,
        observation: CameraRouteTrust,
    ): CameraTopology = updateMutex.withLock {
        val current = mutableTopology.value
        mutableTopology.value = current.copy(
            routes = current.routes.map { route ->
                if (route.canonicalRouteId == canonicalRouteId) {
                    route.copy(trust = CameraTrustPolicy.merge(route.trust, observation))
                } else {
                    route
                }
            },
        )
        mutableTopology.value
    }

    /** Makes a successfully persisted reconciliation the baseline for later in-process rescans. */
    suspend fun commitPersistedBaseline(
        topology: CameraTopology,
        trust: CameraTrustSnapshot,
    ) = updateMutex.withLock {
        bootstrapCache = topology
        trustSnapshot = trust
    }

    suspend fun clear(): CameraTopology = updateMutex.withLock {
        liveEvidence.clear()
        bootstrapCache = null
        trustSnapshot = null
        emptyTopology().also { mutableTopology.value = it }
    }

    fun route(canonicalRouteId: String): CameraRoute? =
        mutableTopology.value.routes.firstOrNull { it.canonicalRouteId == canonicalRouteId }

    private fun resolveLocked(mode: TopologyReconciliationMode): CameraTopology {
        val resolved = CameraTopologyResolver.resolve(
            environmentFingerprint = environmentFingerprint,
            evidence = liveEvidence.values,
            cachedTopology = bootstrapCache,
            mode = mode,
        ).withTrust(trustSnapshot)
        mutableTopology.value = resolved
        return resolved
    }

    private fun CameraTopology.withTrust(snapshot: CameraTrustSnapshot?): CameraTopology =
        snapshot?.let { CameraTrustPolicy.apply(it, this) } ?: this

    private fun emptyTopology() = CameraTopology(environmentFingerprint = environmentFingerprint)

    private fun CameraRouteEvidence.evidenceKey(): String = buildString {
        append(source.ordinal).append('|')
        append(openCameraId.length).append(':').append(openCameraId).append('|')
        append(streamPhysicalCameraId?.length ?: 0).append(':')
        append(streamPhysicalCameraId.orEmpty())
    }
}

/** Read-only route projection used by runtime/session consumers. */
class CameraRouteRepository(
    topologyRepository: CameraTopologyRepository,
    scope: CoroutineScope,
) {
    val routes: StateFlow<List<CameraRoute>> = topologyRepository.topology
        .map { topology -> topology.routes }
        .stateIn(scope, SharingStarted.Eagerly, topologyRepository.topology.value.routes)
}
