package com.sahidcode404.camex.core.camera.discovery.nativebackend

import com.sahidcode404.camex.nativebridge.NativeBridge

/** Narrow seam so the JNI boundary can be tested without loading an Android shared library. */
internal interface NativeDiscoveryTransport {
    val isLoaded: Boolean

    fun discoverAdvertisedJson(): String

    fun discoverCandidatesJson(cameraIds: Array<String>): String
}

internal object JniNativeDiscoveryTransport : NativeDiscoveryTransport {
    override val isLoaded: Boolean
        get() = NativeBridge.isLoaded

    override fun discoverAdvertisedJson(): String = NativeBridge.discoverAdvertisedCamerasJson()

    override fun discoverCandidatesJson(cameraIds: Array<String>): String =
        NativeBridge.discoverCameraMetadataJson(cameraIds)
}
