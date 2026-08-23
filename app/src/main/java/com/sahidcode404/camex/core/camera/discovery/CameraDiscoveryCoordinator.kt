package com.sahidcode404.camex.core.camera.discovery

import android.content.Context
import android.os.Build
import com.sahidcode404.camex.core.camera.CameraDiscoveryFailure
import com.sahidcode404.camex.core.camera.cache.CameraCacheMissReason
import com.sahidcode404.camex.core.camera.cache.CameraDiscoveryCacheResetter
import com.sahidcode404.camex.core.camera.cache.CameraTopologyCacheResult
import com.sahidcode404.camex.core.camera.cache.CameraTopologyStore
import com.sahidcode404.camex.core.camera.cache.CameraTrustCacheResult
import com.sahidcode404.camex.core.camera.cache.CameraTrustPolicy
import com.sahidcode404.camex.core.camera.cache.CameraTrustSnapshot
import com.sahidcode404.camex.core.camera.cache.CameraTrustStore
import com.sahidcode404.camex.core.camera.diagnostics.CameraStartupMilestone
import com.sahidcode404.camex.core.camera.diagnostics.CameraStartupTrace
import com.sahidcode404.camex.core.camera.discovery.nativebackend.DeepAuxDiscoveryBackend
import com.sahidcode404.camex.core.camera.discovery.nativebackend.DeepAuxDiscoveryRequest
import com.sahidcode404.camex.core.camera.discovery.nativebackend.NativeCameraDiscoveryBackend
import com.sahidcode404.camex.core.camera.discovery.nativebackend.NativeDiscoveryFailure
import com.sahidcode404.camex.core.camera.discovery.nativebackend.NativeDiscoveryResult
import com.sahidcode404.camex.core.camera.runtime.CameraTopologyRepository
import com.sahidcode404.camex.core.camera.runtime.toRouteEvidence
import com.sahidcode404.camex.core.camera.runtime.toEnrichedRouteEvidence
import com.sahidcode404.camex.core.camera.topology.CameraDiscoverySource
import com.sahidcode404.camex.core.camera.topology.CameraEnvironmentFingerprint
import com.sahidcode404.camex.core.camera.topology.CameraTopology
import com.sahidcode404.camex.core.camera.topology.TopologyReconciliationMode
import com.sahidcode404.camex.core.camera.topology.toLensDescriptor
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class DiscoveryBackendStatus {
    NOT_STARTED,
    RUNNING,
    COMPLETE,
    FAILED,
    SKIPPED,
}

enum class CameraDiscoveryTrigger {
    STARTUP,
    NORMAL_RESCAN,
    DEEP_RESCAN,
}

data class DiscoveryBackendSummary(
    val status: DiscoveryBackendStatus = DiscoveryBackendStatus.NOT_STARTED,
    val candidateCount: Int = 0,
    val durationMs: Long? = null,
)

data class HybridCameraDiscoverySnapshot(
    val cacheReady: Boolean = false,
    val cacheHit: Boolean = false,
    val cacheMissReason: CameraCacheMissReason? = null,
    /** Wall-clock cache metadata for diagnostics only; never used in latency calculations. */
    val cacheGeneratedAtEpochMs: Long? = null,
    val initialDeepScanRequired: Boolean = false,
    val trigger: CameraDiscoveryTrigger? = null,
    val java: DiscoveryBackendSummary = DiscoveryBackendSummary(),
    val ndk: DiscoveryBackendSummary = DiscoveryBackendSummary(),
    val deep: DiscoveryBackendSummary = DiscoveryBackendSummary(),
    val javaFailures: List<CameraDiscoveryFailure> = emptyList(),
    val ndkFailures: List<NativeDiscoveryFailure> = emptyList(),
    val deepFailures: List<NativeDiscoveryFailure> = emptyList(),
    val topology: CameraTopology? = null,
) {
    val nativeFailures: List<NativeDiscoveryFailure>
        get() = (ndkFailures + deepFailures).distinct()
}

/**
 * Cache-first orchestration for independent Java, advertised-NDK, physical-topology and deep-NDK
 * evidence. This class never opens a camera and has no reference to CameraSessionController.
 */
class CameraDiscoveryCoordinator(
    context: Context,
    val environmentFingerprint: CameraEnvironmentFingerprint = defaultEnvironment(),
    val startupTrace: CameraStartupTrace = CameraStartupTrace(),
    val topologyRepository: CameraTopologyRepository = CameraTopologyRepository(environmentFingerprint),
    private val topologyStore: CameraTopologyStore = CameraTopologyStore(context),
    private val trustStore: CameraTrustStore = CameraTrustStore(context),
    private val javaBackendFactory: () -> JavaCameraDiscoveryBackend = {
        JavaCameraDiscoveryBackend(context)
    },
    private val nativeBackendFactory: () -> NativeCameraDiscoveryBackend = {
        NativeCameraDiscoveryBackend()
    },
    private val deepBackendFactory: () -> DeepAuxDiscoveryBackend = {
        DeepAuxDiscoveryBackend()
    },
) {
    private val bootstrapMutex = Mutex()
    private val reconciliationMutex = Mutex()
    private val bootstrapped = AtomicBoolean(false)
    private val mutableSnapshot = MutableStateFlow(HybridCameraDiscoverySnapshot())
    private var cachedTopology: CameraTopology? = null
    private var trustSnapshot: CameraTrustSnapshot? = null

    val snapshot: StateFlow<HybridCameraDiscoverySnapshot> = mutableSnapshot.asStateFlow()

    private val javaBackend by lazy(LazyThreadSafetyMode.SYNCHRONIZED, javaBackendFactory)
    private val nativeBackend by lazy(LazyThreadSafetyMode.SYNCHRONIZED, nativeBackendFactory)
    private val deepBackend by lazy(LazyThreadSafetyMode.SYNCHRONIZED, deepBackendFactory)
    private val cacheResetter by lazy {
        CameraDiscoveryCacheResetter(topologyStore, trustStore)
    }

    /** Cache and trust are the only backends touched here. CameraManager remains lazy. */
    suspend fun bootstrapCache(): CameraTopology = bootstrapMutex.withLock {
        if (bootstrapped.get()) return@withLock topologyRepository.topology.value
        startupTrace.mark(CameraStartupMilestone.CACHE_READ_START)
        val topologyResult = topologyStore.read(environmentFingerprint)
        val trustResult = trustStore.read(environmentFingerprint)
        cachedTopology = (topologyResult as? CameraTopologyCacheResult.Hit)?.cached?.topology
        trustSnapshot = (trustResult as? CameraTrustCacheResult.Hit)?.cached?.snapshot
        val topology = topologyRepository.installCachedTopology(cachedTopology, trustSnapshot)
        val miss = (topologyResult as? CameraTopologyCacheResult.Miss)?.reason
        mutableSnapshot.update { current -> current.copy(
            cacheReady = true,
            cacheHit = topologyResult is CameraTopologyCacheResult.Hit,
            cacheMissReason = miss,
            cacheGeneratedAtEpochMs = (topologyResult as? CameraTopologyCacheResult.Hit)
                ?.cached?.generatedAtEpochMs,
            initialDeepScanRequired = topologyResult !is CameraTopologyCacheResult.Hit,
            topology = topology,
        ) }
        startupTrace.mark(CameraStartupMilestone.CACHE_READY)
        if (topology.routes.isNotEmpty()) {
            startupTrace.mark(CameraStartupMilestone.UI_LENS_LIST_READY)
            startupTrace.mark(CameraStartupMilestone.PRIMARY_ROUTE_READY)
        }
        bootstrapped.set(true)
        topology
    }

    /** First-install-only seed. It reads minimal Java metadata and stops at one usable route. */
    suspend fun seedPrimaryRoute(): CameraTopology {
        bootstrapCache()
        if (topologyRepository.topology.value.routes.any {
                it.toLensDescriptor().usability.isSelectable
            }
        ) {
            return topologyRepository.topology.value
        }
        val seed = javaBackend.scanPrimaryCameraFast() ?: return topologyRepository.topology.value
        val topology = topologyRepository.upsertEvidence(
            listOf(seed.toRouteEvidence(JavaDiscoverySource.JAVA_PUBLIC)),
        )
        mutableSnapshot.update { it.copy(topology = topology) }
        if (topology.routes.isNotEmpty()) {
            startupTrace.mark(CameraStartupMilestone.UI_LENS_LIST_READY)
            startupTrace.mark(CameraStartupMilestone.PRIMARY_ROUTE_READY)
        }
        return topology
    }

    suspend fun reconcile(
        trigger: CameraDiscoveryTrigger,
        includeDeepScan: Boolean,
    ): CameraTopology = reconciliationMutex.withLock {
        bootstrapCache()
        var shouldRunDeepScan = includeDeepScan
        mutableSnapshot.update { current -> current.copy(
            trigger = trigger,
            java = DiscoveryBackendSummary(DiscoveryBackendStatus.RUNNING),
            ndk = DiscoveryBackendSummary(DiscoveryBackendStatus.RUNNING),
            deep = if (shouldRunDeepScan) {
                DiscoveryBackendSummary(DiscoveryBackendStatus.NOT_STARTED)
            } else {
                DiscoveryBackendSummary(DiscoveryBackendStatus.SKIPPED)
            },
            javaFailures = emptyList(),
            ndkFailures = emptyList(),
            deepFailures = emptyList(),
        ) }

        val advertised = linkedSetOf<String>()
        val advertisedMutex = Mutex()
        coroutineScope {
            startupTrace.mark(CameraStartupMilestone.JAVA_SCAN_START)
            startupTrace.mark(CameraStartupMilestone.NDK_SCAN_START)
            val java = async { runJavaDiscovery(advertised, advertisedMutex) }
            val native = async { runNativeAdvertisedDiscovery(advertised, advertisedMutex) }
            java.await()
            native.await()
        }
        val advertisedIds = advertisedMutex.withLock { advertised.toList() }
        if (advertisedIds.isNotEmpty()) {
            val previousSignature = cachedTopology?.environmentFingerprint
                ?.advertisedTopologySignature
            val currentSignature = CameraEnvironmentFingerprint.advertisedSignature(advertisedIds)
            if (previousSignature != null && currentSignature != null &&
                previousSignature != currentSignature
            ) {
                shouldRunDeepScan = true
                mutableSnapshot.update { it.copy(initialDeepScanRequired = true) }
            }
            topologyRepository.refineAdvertisedCameraIds(advertisedIds)
        }

        var deepRun: NativeDiscoveryResult? = null
        if (shouldRunDeepScan) {
            startupTrace.mark(CameraStartupMilestone.DEEP_SCAN_START)
            mutableSnapshot.update { current -> current.copy(
                deep = DiscoveryBackendSummary(DiscoveryBackendStatus.RUNNING),
            ) }
            val beforeDeep = topologyRepository.topology.value
            deepRun = deepBackend.discover(
                DeepAuxDiscoveryRequest(
                    advertisedCameraIds = advertisedIds,
                    cachedSuccessfulCameraIds = (cachedTopology?.routes.orEmpty() + beforeDeep.routes)
                        .flatMap { route ->
                        listOfNotNull(
                            route.discoveredCameraId,
                            route.openCameraId,
                            route.streamPhysicalCameraId,
                        )
                    },
                    previouslySuccessfulDeepCameraIds =
                        (cachedTopology?.routes.orEmpty() + beforeDeep.routes)
                        .filter { CameraDiscoverySource.NDK_DEEP in it.sources }
                        .map { it.discoveredCameraId },
                ),
            )
            val deepEvidence = deepRun.toRouteEvidence()
            if (deepEvidence.isNotEmpty()) topologyRepository.upsertEvidence(deepEvidence)
            val deepStatus = if (deepRun.hasBackendFailure()) {
                DiscoveryBackendStatus.FAILED
            } else {
                DiscoveryBackendStatus.COMPLETE
            }
            mutableSnapshot.update { current -> current.copy(
                deep = DiscoveryBackendSummary(
                    status = deepStatus,
                    candidateCount = deepRun.cameras.size,
                    durationMs = deepRun.durationMs,
                ),
                deepFailures = deepRun.failures,
            ) }
            startupTrace.mark(CameraStartupMilestone.DEEP_SCAN_COMPLETE)
        }

        val canPruneStaleCache = shouldRunDeepScan && deepRun != null && !deepRun.hasBackendFailure()
        val topology = topologyRepository.reconcile(
            if (canPruneStaleCache) {
                TopologyReconciliationMode.FULLY_RECONCILED
            } else {
                TopologyReconciliationMode.INCREMENTAL
            },
        )
        persist(topology)
        mutableSnapshot.update { it.copy(topology = topology) }
        topology
    }

    suspend fun normalRescan(): CameraTopology {
        topologyRepository.invalidateLiveEvidence()
        return reconcile(CameraDiscoveryTrigger.NORMAL_RESCAN, includeDeepScan = false)
    }

    suspend fun deepRescan(): CameraTopology {
        topologyRepository.invalidateLiveEvidence()
        return reconcile(CameraDiscoveryTrigger.DEEP_RESCAN, includeDeepScan = true)
    }

    /** Clears only discovery topology/trust. User labels, visibility and order are untouched. */
    suspend fun resetDiscoveryCache(): CameraTopology {
        cacheResetter.clearDiscoveryState()
        cachedTopology = null
        trustSnapshot = null
        bootstrapped.set(true)
        val topology = topologyRepository.clear()
        mutableSnapshot.value = HybridCameraDiscoverySnapshot(
            cacheReady = true,
            cacheHit = false,
            cacheMissReason = CameraCacheMissReason.EMPTY,
            initialDeepScanRequired = true,
            topology = topology,
        )
        return topology
    }

    suspend fun recordTrust(
        canonicalRouteId: String,
        observation: com.sahidcode404.camex.core.camera.topology.CameraRouteTrust,
    ): CameraTopology {
        val route = topologyRepository.route(canonicalRouteId) ?: return topologyRepository.topology.value
        trustStore.record(
            environment = topologyRepository.topology.value.environmentFingerprint,
            canonicalRouteId = canonicalRouteId,
            lensFingerprint = route.lensFingerprint,
            observation = observation,
        )
        val topology = topologyRepository.updateRouteTrust(canonicalRouteId, observation)
        mutableSnapshot.update { it.copy(topology = topology) }
        return topology
    }

    private suspend fun runJavaDiscovery(
        advertised: MutableSet<String>,
        advertisedMutex: Mutex,
    ): JavaRun {
        val failures = mutableListOf<CameraDiscoveryFailure>()
        var candidateCount = 0
        var durationMs = 0L
        var finished = false
        runCatching {
            javaBackend.discoverIncrementally(enrichCapabilities = true).collect { update ->
                when (update) {
                    is JavaCameraDiscoveryUpdate.AdvertisedIds -> advertisedMutex.withLock {
                        advertised += update.ids
                    }
                    is JavaCameraDiscoveryUpdate.Relationship -> {
                        val evidence = PhysicalCameraTopologyBackend.evidence(update.value)
                        if (evidence.isNotEmpty()) topologyRepository.upsertEvidence(evidence)
                    }
                    is JavaCameraDiscoveryUpdate.Candidate -> {
                        candidateCount++
                        publishIncremental(
                            listOf(update.metadata.toRouteEvidence(update.source)),
                        )
                    }
                    is JavaCameraDiscoveryUpdate.Enriched -> publishIncremental(
                        listOf(update.lens.toEnrichedRouteEvidence(update.source)),
                    )
                    is JavaCameraDiscoveryUpdate.Failure -> failures += update.value
                    is JavaCameraDiscoveryUpdate.Finished -> {
                        candidateCount = maxOf(candidateCount, update.candidateCount)
                        durationMs = update.durationNs / 1_000_000L
                        finished = true
                    }
                }
            }
        }.onFailure {
            finished = false
        }
        mutableSnapshot.update { current -> current.copy(
            java = DiscoveryBackendSummary(
                status = if (finished) DiscoveryBackendStatus.COMPLETE else DiscoveryBackendStatus.FAILED,
                candidateCount = candidateCount,
                durationMs = durationMs,
            ),
            javaFailures = failures.toList(),
        ) }
        startupTrace.mark(CameraStartupMilestone.JAVA_SCAN_COMPLETE)
        return JavaRun(finished)
    }

    private suspend fun runNativeAdvertisedDiscovery(
        advertised: MutableSet<String>,
        advertisedMutex: Mutex,
    ): NativeDiscoveryResult {
        val result = nativeBackend.discoverAdvertised()
        advertisedMutex.withLock { advertised += result.advertisedCameraIds }
        val evidence = result.toRouteEvidence()
        if (evidence.isNotEmpty()) publishIncremental(evidence)
        mutableSnapshot.update { current -> current.copy(
            ndk = DiscoveryBackendSummary(
                status = if (result.hasBackendFailure()) {
                    DiscoveryBackendStatus.FAILED
                } else {
                    DiscoveryBackendStatus.COMPLETE
                },
                candidateCount = result.cameras.size,
                durationMs = result.durationMs,
            ),
            ndkFailures = result.failures,
        ) }
        startupTrace.mark(CameraStartupMilestone.NDK_SCAN_COMPLETE)
        return result
    }

    private suspend fun publishIncremental(
        evidence: Collection<com.sahidcode404.camex.core.camera.topology.CameraRouteEvidence>,
    ) {
        val topology = topologyRepository.upsertEvidence(evidence)
        mutableSnapshot.update { it.copy(topology = topology) }
        if (topology.routes.isNotEmpty()) startupTrace.mark(CameraStartupMilestone.UI_LENS_LIST_READY)
    }

    private suspend fun persist(topology: CameraTopology) {
        val generatedAtEpochMs = topologyStore.write(topology)
        val updatedTrust = CameraTrustPolicy.reconcile(trustSnapshot, topology)
        trustStore.write(updatedTrust)
        topologyRepository.commitPersistedBaseline(topology, updatedTrust)
        trustSnapshot = updatedTrust
        cachedTopology = topology
        mutableSnapshot.update { it.copy(cacheGeneratedAtEpochMs = generatedAtEpochMs) }
        startupTrace.mark(CameraStartupMilestone.TOPOLOGY_PERSISTED)
    }

    private fun NativeDiscoveryResult.hasBackendFailure(): Boolean =
        cameras.isEmpty() && failures.any { it.cameraId == null }

    private data class JavaRun(val finished: Boolean)

    companion object {
        fun defaultEnvironment() = CameraEnvironmentFingerprint(
            buildFingerprint = Build.FINGERPRINT.orEmpty(),
            apiLevel = Build.VERSION.SDK_INT,
        )
    }
}
