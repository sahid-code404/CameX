package com.sahidcode404.camex.core.camera.cache

import com.sahidcode404.camex.core.camera.topology.CameraEnvironmentFingerprint
import com.sahidcode404.camex.core.camera.topology.CameraFailureDurability
import com.sahidcode404.camex.core.camera.topology.CameraMetadataTrust
import com.sahidcode404.camex.core.camera.topology.CameraRawTrust
import com.sahidcode404.camex.core.camera.topology.CameraRouteTrust
import com.sahidcode404.camex.core.camera.topology.CameraSessionTrust
import com.sahidcode404.camex.core.camera.topology.CameraTopology
import com.sahidcode404.camex.core.model.LensFingerprint
import kotlinx.serialization.Serializable

@Serializable
data class CameraTrustRecord(
    val canonicalRouteId: String,
    val lensFingerprint: LensFingerprint? = null,
    val trust: CameraRouteTrust,
)

@Serializable
data class CameraTrustSnapshot(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val environmentFingerprint: CameraEnvironmentFingerprint,
    val records: List<CameraTrustRecord> = emptyList(),
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

@Serializable
data class CachedCameraTrust(
    val cacheSchemaVersion: Int = CameraTopology.CACHE_SCHEMA_VERSION,
    val snapshot: CameraTrustSnapshot,
    /** Wall-clock time is cache metadata only and is never used for latency measurement. */
    val generatedAtEpochMs: Long,
)

sealed interface CameraTrustCacheResult {
    data class Hit(
        val cached: CachedCameraTrust,
        val migrated: Boolean = false,
    ) : CameraTrustCacheResult

    data class Miss(val reason: CameraCacheMissReason) : CameraTrustCacheResult
}

object CameraTrustCachePolicy {
    fun evaluate(
        cached: CachedCameraTrust?,
        expectedEnvironment: CameraEnvironmentFingerprint,
    ): CameraTrustCacheResult {
        if (cached == null) return CameraTrustCacheResult.Miss(CameraCacheMissReason.EMPTY)
        val migration = migrate(cached) ?: return CameraTrustCacheResult.Miss(
            if (cached.cacheSchemaVersion > CameraTopology.CACHE_SCHEMA_VERSION) {
                CameraCacheMissReason.CACHE_SCHEMA_CHANGED
            } else {
                CameraCacheMissReason.CORRUPT
            },
        )
        val value = migration.first
        if (value.snapshot.schemaVersion != CameraTrustSnapshot.CURRENT_SCHEMA_VERSION) {
            return CameraTrustCacheResult.Miss(CameraCacheMissReason.CACHE_SCHEMA_CHANGED)
        }
        if (!value.snapshot.environmentFingerprint.isCompatibleWith(expectedEnvironment)) {
            return CameraTrustCacheResult.Miss(CameraCacheMissReason.ENVIRONMENT_CHANGED)
        }
        if (!isStructurallyValid(value)) {
            return CameraTrustCacheResult.Miss(CameraCacheMissReason.CORRUPT)
        }
        return CameraTrustCacheResult.Hit(value, migrated = migration.second)
    }

    private fun migrate(cached: CachedCameraTrust): Pair<CachedCameraTrust, Boolean>? = when {
        cached.cacheSchemaVersion == CameraTopology.CACHE_SCHEMA_VERSION -> cached to false
        cached.cacheSchemaVersion == 0 && CameraTopology.CACHE_SCHEMA_VERSION == 1 -> {
            cached.copy(
                cacheSchemaVersion = CameraTopology.CACHE_SCHEMA_VERSION,
                snapshot = cached.snapshot.copy(
                    schemaVersion = CameraTrustSnapshot.CURRENT_SCHEMA_VERSION,
                    environmentFingerprint = cached.snapshot.environmentFingerprint.copy(
                        cacheSchemaVersion = CameraTopology.CACHE_SCHEMA_VERSION,
                    ),
                ),
            ) to true
        }
        else -> null
    }

    private fun isStructurallyValid(cached: CachedCameraTrust): Boolean {
        if (cached.generatedAtEpochMs < 0L) return false
        val routeIds = cached.snapshot.records.map(CameraTrustRecord::canonicalRouteId)
        return routeIds.none(String::isBlank) && routeIds.distinct().size == routeIds.size
    }
}

/** Monotonic trust progression plus conservative structural-failure handling. */
object CameraTrustPolicy {
    fun merge(previous: CameraRouteTrust, observation: CameraRouteTrust): CameraRouteTrust {
        val transient = observation.failure?.durability == CameraFailureDurability.TRANSIENT ||
            observation.session == CameraSessionTrust.TRANSIENT_FAILURE ||
            observation.raw == CameraRawTrust.TRANSIENT_FAILURE
        val metadata = progressMetadata(previous.metadata, observation.metadata)
        val session = progressSession(previous.session, observation.session, transient)
        val raw = progressRaw(previous.raw, observation.raw, transient)
        val failure = when {
            observation.failure?.durability == CameraFailureDurability.STRUCTURAL ->
                observation.failure.normalized()
            transient -> previous.failure
            observation.failure != null -> observation.failure.normalized()
            else -> previous.failure
        }
        return CameraRouteTrust(metadata, session, raw, failure)
    }

    fun apply(snapshot: CameraTrustSnapshot, topology: CameraTopology): CameraTopology {
        if (!snapshot.environmentFingerprint.isCompatibleWith(topology.environmentFingerprint)) {
            return topology
        }
        val records = snapshot.records.associateBy(CameraTrustRecord::canonicalRouteId)
        return topology.copy(routes = topology.routes.map { route ->
            val record = records[route.canonicalRouteId] ?: return@map route
            if (record.lensFingerprint != null && route.lensFingerprint != null &&
                record.lensFingerprint != route.lensFingerprint
            ) return@map route
            route.copy(trust = merge(route.trust, record.trust))
        })
    }

    fun reconcile(
        previous: CameraTrustSnapshot?,
        topology: CameraTopology,
    ): CameraTrustSnapshot {
        val compatible = previous?.takeIf {
            it.environmentFingerprint.isCompatibleWith(topology.environmentFingerprint)
        }
        val old = compatible?.records.orEmpty().associateBy(CameraTrustRecord::canonicalRouteId)
        return CameraTrustSnapshot(
            environmentFingerprint = topology.environmentFingerprint,
            records = topology.routes.map { route ->
                val stored = old[route.canonicalRouteId]
                val trust = if (stored == null ||
                    stored.lensFingerprint != null && route.lensFingerprint != null &&
                    stored.lensFingerprint != route.lensFingerprint
                ) {
                    route.trust
                } else {
                    merge(stored.trust, route.trust)
                }
                CameraTrustRecord(route.canonicalRouteId, route.lensFingerprint, trust)
            }.sortedBy(CameraTrustRecord::canonicalRouteId),
        )
    }

    private fun progressMetadata(
        previous: CameraMetadataTrust,
        next: CameraMetadataTrust,
    ): CameraMetadataTrust = when {
        next == CameraMetadataTrust.METADATA_VALID -> CameraMetadataTrust.METADATA_VALID
        previous == CameraMetadataTrust.METADATA_VALID && next == CameraMetadataTrust.DISCOVERED -> previous
        next == CameraMetadataTrust.UNKNOWN -> previous
        else -> next
    }

    private fun progressSession(
        previous: CameraSessionTrust,
        next: CameraSessionTrust,
        transient: Boolean,
    ): CameraSessionTrust = when {
        next == CameraSessionTrust.SESSION_VERIFIED -> CameraSessionTrust.SESSION_VERIFIED
        transient || next == CameraSessionTrust.UNKNOWN -> previous
        else -> next
    }

    private fun progressRaw(
        previous: CameraRawTrust,
        next: CameraRawTrust,
        transient: Boolean,
    ): CameraRawTrust = when {
        next == CameraRawTrust.RAW_VERIFIED -> CameraRawTrust.RAW_VERIFIED
        transient || next == CameraRawTrust.UNKNOWN -> previous
        else -> next
    }
}
