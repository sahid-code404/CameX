package com.sahidcode404.camex.core.camera.cache

import com.sahidcode404.camex.core.camera.topology.CameraEnvironmentFingerprint
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object CameraTrustCacheCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(cached: CachedCameraTrust): String = json.encodeToString(cached)

    fun decodeAndEvaluate(
        raw: String?,
        expectedEnvironment: CameraEnvironmentFingerprint,
    ): CameraTrustCacheResult {
        if (raw.isNullOrBlank()) return CameraTrustCacheResult.Miss(CameraCacheMissReason.EMPTY)
        val cached = try {
            json.decodeFromString<CachedCameraTrust>(raw)
        } catch (_: SerializationException) {
            return CameraTrustCacheResult.Miss(CameraCacheMissReason.CORRUPT)
        } catch (_: IllegalArgumentException) {
            return CameraTrustCacheResult.Miss(CameraCacheMissReason.CORRUPT)
        }
        return CameraTrustCachePolicy.evaluate(cached, expectedEnvironment)
    }
}
