package com.sahidcode404.camex.core.camera.cache

import com.sahidcode404.camex.core.camera.topology.CameraEnvironmentFingerprint
import com.sahidcode404.camex.core.camera.topology.CameraTopology
import kotlinx.serialization.Serializable

@Serializable
data class CachedCameraTopology(
    val cacheSchemaVersion: Int = CameraTopology.CACHE_SCHEMA_VERSION,
    val environmentFingerprint: CameraEnvironmentFingerprint,
    val topology: CameraTopology,
    /** Wall-clock time is cache metadata only and is never used for latency measurement. */
    val generatedAtEpochMs: Long,
)

enum class CameraCacheMissReason {
    EMPTY,
    CORRUPT,
    CACHE_SCHEMA_CHANGED,
    TOPOLOGY_SCHEMA_CHANGED,
    ENVIRONMENT_CHANGED,
}

sealed interface CameraTopologyCacheResult {
    data class Hit(
        val cached: CachedCameraTopology,
        val migrated: Boolean = false,
    ) : CameraTopologyCacheResult

    data class Miss(
        val reason: CameraCacheMissReason,
    ) : CameraTopologyCacheResult
}

/** Pure validation policy used by both DataStore and unit tests. */
object CameraTopologyCachePolicy {
    fun evaluate(
        cached: CachedCameraTopology?,
        expectedEnvironment: CameraEnvironmentFingerprint,
    ): CameraTopologyCacheResult {
        if (cached == null) return CameraTopologyCacheResult.Miss(CameraCacheMissReason.EMPTY)
        if (cached.cacheSchemaVersion != CameraTopology.CACHE_SCHEMA_VERSION) {
            // Phase 1B changes route-per-lens cache semantics. Never reinterpret an old route cache
            // as canonical optical lenses; rediscovery is safer than a false optical identity.
            return CameraTopologyCacheResult.Miss(CameraCacheMissReason.CACHE_SCHEMA_CHANGED)
        }
        val value = cached
        if (value.topology.schemaVersion != CameraTopology.CURRENT_SCHEMA_VERSION) {
            return CameraTopologyCacheResult.Miss(CameraCacheMissReason.TOPOLOGY_SCHEMA_CHANGED)
        }
        if (!value.environmentFingerprint.isCompatibleWith(expectedEnvironment) ||
            !value.topology.environmentFingerprint.isCompatibleWith(expectedEnvironment)
        ) return CameraTopologyCacheResult.Miss(CameraCacheMissReason.ENVIRONMENT_CHANGED)
        if (!isStructurallyValid(value)) {
            return CameraTopologyCacheResult.Miss(CameraCacheMissReason.CORRUPT)
        }
        return CameraTopologyCacheResult.Hit(value, migrated = false)
    }

    private fun isStructurallyValid(cached: CachedCameraTopology): Boolean {
        if (cached.generatedAtEpochMs < 0L) return false
        val profileIds = cached.topology.routes.flatMap { route -> route.profiles.map { it.profileId } }
        if (profileIds.any(String::isBlank) || profileIds.distinct().size != profileIds.size) return false
        val opticalFingerprints = cached.topology.routes.mapNotNull { it.lensFingerprint?.value }
        if (opticalFingerprints.any(String::isBlank) ||
            opticalFingerprints.distinct().size != opticalFingerprints.size
        ) return false
        return cached.topology.routes.all { route ->
            route.openCameraId.isNotBlank() &&
                route.discoveredCameraId.isNotBlank() &&
                route.sources.isNotEmpty() &&
                route.lensFingerprint?.value?.isNotBlank() == true &&
                route.profiles.all { profile ->
                    profile.openCameraId.isNotBlank() &&
                        profile.discoveredCameraId.isNotBlank() &&
                        profile.discoverySources.isNotEmpty()
                }
        }
    }
}
