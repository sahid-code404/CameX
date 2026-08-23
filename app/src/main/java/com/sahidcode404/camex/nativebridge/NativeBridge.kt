package com.sahidcode404.camex.nativebridge

/** Minimal JNI boundary used to prove the native foundation in Phase 1. */
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

    private external fun nativeVersion(): String
    private external fun nativeSelfTest(): Boolean
}

data class NativeStatus(
    val loaded: Boolean,
    val version: String?,
    val selfTestPassed: Boolean,
)
