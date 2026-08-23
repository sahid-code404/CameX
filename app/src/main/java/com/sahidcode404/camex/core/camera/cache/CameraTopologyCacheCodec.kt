package com.sahidcode404.camex.core.camera.cache

import com.sahidcode404.camex.core.camera.topology.CameraEnvironmentFingerprint
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Small, forward-tolerant JSON codec. Unknown fields do not invalidate otherwise usable cache. */
object CameraTopologyCacheCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(cached: CachedCameraTopology): String = json.encodeToString(cached)

    fun decodeAndEvaluate(
        raw: String?,
        expectedEnvironment: CameraEnvironmentFingerprint,
    ): CameraTopologyCacheResult {
        if (raw.isNullOrBlank()) {
            return CameraTopologyCacheResult.Miss(CameraCacheMissReason.EMPTY)
        }
        val cached = try {
            json.decodeFromString<CachedCameraTopology>(raw)
        } catch (_: SerializationException) {
            return CameraTopologyCacheResult.Miss(CameraCacheMissReason.CORRUPT)
        } catch (_: IllegalArgumentException) {
            return CameraTopologyCacheResult.Miss(CameraCacheMissReason.CORRUPT)
        }
        return CameraTopologyCachePolicy.evaluate(cached, expectedEnvironment)
    }
}
