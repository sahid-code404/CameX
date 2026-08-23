package com.sahidcode404.camex.core.camera.cache

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sahidcode404.camex.core.camera.topology.CameraEnvironmentFingerprint
import com.sahidcode404.camex.core.camera.topology.CameraTopology
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private const val TOPOLOGY_STORE_NAME = "camera_topology"
private val Context.cameraTopologyDataStore by preferencesDataStore(name = TOPOLOGY_STORE_NAME)

/** Compact cache backend. Callers decide the dispatcher; no API here performs camera discovery. */
class CameraTopologyStore(
    context: Context,
    private val epochMillis: () -> Long = System::currentTimeMillis,
) {
    private val dataStore = context.applicationContext.cameraTopologyDataStore

    fun observe(expectedEnvironment: CameraEnvironmentFingerprint): Flow<CameraTopologyCacheResult> =
        dataStore.data
            .catch { error ->
                if (error is IOException) emit(emptyPreferences()) else throw error
            }
            .map { preferences ->
                CameraTopologyCacheCodec.decodeAndEvaluate(
                    preferences[topologyKey],
                    expectedEnvironment,
                )
            }

    suspend fun read(expectedEnvironment: CameraEnvironmentFingerprint): CameraTopologyCacheResult =
        observe(expectedEnvironment).first()

    suspend fun write(topology: CameraTopology): Long {
        val generatedAtEpochMs = epochMillis().coerceAtLeast(0L)
        val cached = CachedCameraTopology(
            environmentFingerprint = topology.environmentFingerprint,
            topology = topology,
            generatedAtEpochMs = generatedAtEpochMs,
        )
        dataStore.edit { preferences ->
            preferences[topologyKey] = CameraTopologyCacheCodec.encode(cached)
        }
        return generatedAtEpochMs
    }

    /** Clears only discovery topology; LensSettingsStore remains untouched. */
    suspend fun clear() {
        dataStore.edit { preferences -> preferences.remove(topologyKey) }
    }

    private companion object {
        val topologyKey = stringPreferencesKey("camera.topology.json")
    }
}
