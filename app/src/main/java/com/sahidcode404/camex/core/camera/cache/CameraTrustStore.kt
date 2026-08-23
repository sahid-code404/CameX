package com.sahidcode404.camex.core.camera.cache

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sahidcode404.camex.core.camera.topology.CameraEnvironmentFingerprint
import com.sahidcode404.camex.core.camera.topology.CameraRouteTrust
import com.sahidcode404.camex.core.model.LensFingerprint
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private const val TRUST_STORE_NAME = "camera_route_trust"
private val Context.cameraTrustDataStore by preferencesDataStore(name = TRUST_STORE_NAME)

/** Environment-bound verified/rejected-route knowledge, separate from user lens preferences. */
class CameraTrustStore(
    context: Context,
    private val epochMillis: () -> Long = System::currentTimeMillis,
) {
    private val dataStore = context.applicationContext.cameraTrustDataStore

    fun observe(expectedEnvironment: CameraEnvironmentFingerprint): Flow<CameraTrustCacheResult> =
        dataStore.data
            .catch { error ->
                if (error is IOException) emit(emptyPreferences()) else throw error
            }
            .map { preferences ->
                CameraTrustCacheCodec.decodeAndEvaluate(preferences[trustKey], expectedEnvironment)
            }

    suspend fun read(expectedEnvironment: CameraEnvironmentFingerprint): CameraTrustCacheResult =
        observe(expectedEnvironment).first()

    suspend fun write(snapshot: CameraTrustSnapshot) {
        val cached = CachedCameraTrust(
            snapshot = snapshot,
            generatedAtEpochMs = epochMillis().coerceAtLeast(0L),
        )
        dataStore.edit { preferences ->
            preferences[trustKey] = CameraTrustCacheCodec.encode(cached)
        }
    }

    suspend fun record(
        environment: CameraEnvironmentFingerprint,
        canonicalRouteId: String,
        lensFingerprint: LensFingerprint?,
        observation: CameraRouteTrust,
    ) {
        val routeId = canonicalRouteId.trim()
        if (routeId.isEmpty()) return
        dataStore.edit { preferences ->
            val previous = when (
                val result = CameraTrustCacheCodec.decodeAndEvaluate(
                    preferences[trustKey],
                    environment,
                )
            ) {
                is CameraTrustCacheResult.Hit -> result.cached.snapshot
                is CameraTrustCacheResult.Miss -> CameraTrustSnapshot(
                    environmentFingerprint = environment,
                )
            }
            val records = previous.records.associateBy(CameraTrustRecord::canonicalRouteId)
                .toMutableMap()
            val old = records[routeId]
            val fingerprintChanged = old?.lensFingerprint != null && lensFingerprint != null &&
                old.lensFingerprint != lensFingerprint
            records[routeId] = CameraTrustRecord(
                canonicalRouteId = routeId,
                lensFingerprint = lensFingerprint ?: old?.lensFingerprint,
                trust = if (old == null || fingerprintChanged) {
                    CameraTrustPolicy.merge(CameraRouteTrust(), observation)
                } else {
                    CameraTrustPolicy.merge(old.trust, observation)
                },
            )
            val snapshot = previous.copy(
                schemaVersion = CameraTrustSnapshot.CURRENT_SCHEMA_VERSION,
                environmentFingerprint = environment,
                records = records.values.sortedBy(CameraTrustRecord::canonicalRouteId),
            )
            preferences[trustKey] = CameraTrustCacheCodec.encode(
                CachedCameraTrust(
                    snapshot = snapshot,
                    generatedAtEpochMs = epochMillis().coerceAtLeast(0L),
                ),
            )
        }
    }

    /** Clears only route trust/rejections; LensSettingsStore remains untouched. */
    suspend fun clear() {
        dataStore.edit { preferences -> preferences.remove(trustKey) }
    }

    private companion object {
        val trustKey = stringPreferencesKey("camera.route.trust.json")
    }
}
