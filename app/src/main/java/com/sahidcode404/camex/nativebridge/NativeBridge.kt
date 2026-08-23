package com.sahidcode404.camex.nativebridge

/** JNI boundary for the small native foundation and read-only NDK camera discovery. */
object NativeBridge {
    private val loaded: Boolean = runCatching {
        System.loadLibrary("camex_native")
        true
    }.getOrDefault(false)

    fun status(): NativeStatus = if (!loaded) {
        NativeStatus(loaded = false, version = null, selfTestPassed = false)
    } else {
        runCatching {
            NativeStatus(
                loaded = true,
                version = nativeVersion(),
                selfTestPassed = nativeSelfTest(),
            )
        }.getOrElse {
            NativeStatus(loaded = true, version = null, selfTestPassed = false)
        }
    }

    /**
     * Whether the CameX library loaded. Camera NDK availability is reported by the structured
     * discovery payload because Android 6 (the app's minimum API) predates ACameraManager.
     */
    internal val isLoaded: Boolean
        get() = loaded

    /** Returns one structured metadata-only scan using a single native ACameraManager. */
    internal fun discoverAdvertisedCamerasJson(): String {
        check(loaded) { "CameX native library is unavailable" }
        return nativeDiscoverAdvertisedCamerasPayload().decodeToString(throwOnInvalidSequence = true)
    }

    /**
     * Reads characteristics for the caller's already bounded candidates. This JNI path never
     * opens a camera and does not call any privileged API.
     */
    internal fun discoverCameraMetadataJson(cameraIds: Array<String>): String {
        check(loaded) { "CameX native library is unavailable" }
        return nativeDiscoverCameraMetadataPayload(cameraIds)
            .decodeToString(throwOnInvalidSequence = true)
    }

    private external fun nativeVersion(): String
    private external fun nativeSelfTest(): Boolean
    private external fun nativeDiscoverAdvertisedCamerasPayload(): ByteArray
    private external fun nativeDiscoverCameraMetadataPayload(cameraIds: Array<String>): ByteArray
}

data class NativeStatus(
    val loaded: Boolean,
    val version: String?,
    val selfTestPassed: Boolean,
)
