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

/** Pure validation/migration policy used by both DataStore and unit tests. */
object CameraTopologyCachePolicy {
    fun evaluate(
        cached: CachedCameraTopology?,
        expectedEnvironment: CameraEnvironmentFingerprint,
    ): CameraTopologyCacheResult {
        if (cached == null) return CameraTopologyCacheResult.Miss(CameraCacheMissReason.EMPTY)
        val migration = migrate(cached) ?: return CameraTopologyCacheResult.Miss(
            if (cached.cacheSchemaVersion > CameraTopology.CACHE_SCHEMA_VERSION) {
                CameraCacheMissReason.CACHE_SCHEMA_CHANGED
            } else {
                CameraCacheMissReason.CORRUPT
            },
        )
        val value = migration.first
        if (value.topology.schemaVersion != CameraTopology.CURRENT_SCHEMA_VERSION) {
            return CameraTopologyCacheResult.Miss(CameraCacheMissReason.TOPOLOGY_SCHEMA_CHANGED)
        }
        if (!value.environmentFingerprint.isCompatibleWith(expectedEnvironment) ||
            !value.topology.environmentFingerprint.isCompatibleWith(expectedEnvironment)
        ) return CameraTopologyCacheResult.Miss(CameraCacheMissReason.ENVIRONMENT_CHANGED)
        if (!isStructurallyValid(value)) {
            return CameraTopologyCacheResult.Miss(CameraCacheMissReason.CORRUPT)
        }
        return CameraTopologyCacheResult.Hit(value, migrated = migration.second)
    }

    private fun migrate(cached: CachedCameraTopology): Pair<CachedCameraTopology, Boolean>? = when {
        cached.cacheSchemaVersion == CameraTopology.CACHE_SCHEMA_VERSION -> cached to false
        cached.cacheSchemaVersion == 0 && CameraTopology.CACHE_SCHEMA_VERSION == 1 -> {
            val environment = cached.environmentFingerprint.copy(
                cacheSchemaVersion = CameraTopology.CACHE_SCHEMA_VERSION,
            )
            cached.copy(
                cacheSchemaVersion = CameraTopology.CACHE_SCHEMA_VERSION,
                environmentFingerprint = environment,
                topology = cached.topology.copy(
                    schemaVersion = CameraTopology.CURRENT_SCHEMA_VERSION,
                    environmentFingerprint = environment,
                ),
            ) to true
        }
        else -> null
    }

    private fun isStructurallyValid(cached: CachedCameraTopology): Boolean {
        if (cached.generatedAtEpochMs < 0L) return false
        val routeIds = cached.topology.routes.map { it.canonicalRouteId }
        if (routeIds.any(String::isBlank) || routeIds.distinct().size != routeIds.size) return false
        return cached.topology.routes.all { route ->
            route.openCameraId.isNotBlank() &&
                route.discoveredCameraId.isNotBlank() &&
                route.sources.isNotEmpty() &&
                route.lensFingerprint?.value?.isNotBlank() != false
        }
    }
}
