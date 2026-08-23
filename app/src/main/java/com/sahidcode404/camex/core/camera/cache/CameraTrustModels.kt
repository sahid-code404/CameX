package com.sahidcode404.camex.core.camera.cache

import com.sahidcode404.camex.core.camera.topology.CameraEnvironmentFingerprint
import com.sahidcode404.camex.core.camera.topology.CameraFailureDurability
import com.sahidcode404.camex.core.camera.topology.CameraMetadataTrust
import com.sahidcode404.camex.core.camera.topology.CameraRawTrust
import com.sahidcode404.camex.core.camera.topology.CameraRouteTrust
import com.sahidcode404.camex.core.camera.topology.CameraSessionTrust
import com.sahidcode404.camex.core.camera.topology.CameraTopology
import com.sahidcode404.camex.core.camera.topology.withProfileTrust
import com.sahidcode404.camex.core.model.LensFingerprint
import kotlinx.serialization.Serializable

/** One persisted trust record per CameraProfile transport endpoint. */
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
        const val CURRENT_SCHEMA_VERSION = 2
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
            if (cached.cacheSchemaVersion != CameraTopology.CACHE_SCHEMA_VERSION) {
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

    /** Phase 1B intentionally does not reinterpret route-per-lens trust as profile trust. */
    private fun migrate(cached: CachedCameraTrust): Pair<CachedCameraTrust, Boolean>? = when {
        cached.cacheSchemaVersion == CameraTopology.CACHE_SCHEMA_VERSION &&
            cached.snapshot.schemaVersion == CameraTrustSnapshot.CURRENT_SCHEMA_VERSION -> cached to false
        else -> null
    }

    private fun isStructurallyValid(cached: CachedCameraTrust): Boolean {
        if (cached.generatedAtEpochMs < 0L) return false
        val routeIds = cached.snapshot.records.map(CameraTrustRecord::canonicalRouteId)
        if (routeIds.any(String::isBlank) || routeIds.distinct().size != routeIds.size) return false
        return cached.snapshot.records.all { record ->
            record.trust.lastAttemptEpochMs?.let { it >= 0L } != false
        }
    }
}

/** Monotonic profile trust progression plus conservative structural-failure handling. */
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
        val lastAttempt = listOfNotNull(
            previous.lastAttemptEpochMs?.takeIf { it >= 0L },
            observation.lastAttemptEpochMs?.takeIf { it >= 0L },
        ).maxOrNull()
        return CameraRouteTrust(
            metadata = metadata,
            session = session,
            raw = raw,
            failure = failure,
            lastAttemptEpochMs = lastAttempt,
        )
    }

    /** Apply persisted trust to each profile independently; verified profiles become preferred. */
    fun apply(snapshot: CameraTrustSnapshot, topology: CameraTopology): CameraTopology {
        if (!snapshot.environmentFingerprint.isCompatibleWith(topology.environmentFingerprint)) {
            return topology
        }
        val records = snapshot.records.associateBy(CameraTrustRecord::canonicalRouteId)
        return topology.copy(routes = topology.routes.map { canonical ->
            canonical.profiles.fold(canonical) { route, profile ->
                val record = records[profile.profileId] ?: return@fold route
                if (record.lensFingerprint != null && canonical.lensFingerprint != null &&
                    record.lensFingerprint != canonical.lensFingerprint
                ) return@fold route
                route.withProfileTrust(profile.profileId, merge(profile.trust, record.trust))
            }
        })
    }

    /** Persist one record for every raw discovered profile, never only the preferred route. */
    fun reconcile(
        previous: CameraTrustSnapshot?,
        topology: CameraTopology,
    ): CameraTrustSnapshot {
        val compatible = previous?.takeIf {
            it.environmentFingerprint.isCompatibleWith(topology.environmentFingerprint) &&
                it.schemaVersion == CameraTrustSnapshot.CURRENT_SCHEMA_VERSION
        }
        val old = compatible?.records.orEmpty().associateBy(CameraTrustRecord::canonicalRouteId)
        val records = topology.routes.flatMap { canonical ->
            canonical.profiles.map { profile ->
                val stored = old[profile.profileId]
                val fingerprintChanged = stored?.lensFingerprint != null &&
                    canonical.lensFingerprint != null &&
                    stored.lensFingerprint != canonical.lensFingerprint
                val trust = if (stored == null || fingerprintChanged) {
                    profile.trust
                } else {
                    merge(stored.trust, profile.trust)
                }
                CameraTrustRecord(
                    canonicalRouteId = profile.profileId,
                    lensFingerprint = canonical.lensFingerprint,
                    trust = trust,
                )
            }
        }
        return CameraTrustSnapshot(
            environmentFingerprint = topology.environmentFingerprint,
            records = records.distinctBy(CameraTrustRecord::canonicalRouteId)
                .sortedBy(CameraTrustRecord::canonicalRouteId),
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
