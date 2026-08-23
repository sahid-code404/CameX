package com.sahidcode404.camex.core.camera.discovery.nativebackend

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Independent ACameraManager advertised-ID backend. It reads metadata and never opens cameras. */
class NativeCameraDiscoveryBackend internal constructor(
    private val transport: NativeDiscoveryTransport,
    private val dispatcher: CoroutineDispatcher,
) {
    constructor() : this(JniNativeDiscoveryTransport, Dispatchers.IO)

    suspend fun discoverAdvertised(): NativeDiscoveryResult = withContext(dispatcher) {
        if (!transport.isLoaded) {
            return@withContext NativeDiscoveryPayloadParser.unavailableResult(
                NativeDiscoverySource.NDK_ADVERTISED,
            )
        }
        runCatching(transport::discoverAdvertisedJson)
            .fold(
                onSuccess = { payload ->
                    NativeDiscoveryPayloadParser.parse(
                        payload = payload,
                        expectedSource = NativeDiscoverySource.NDK_ADVERTISED,
                    )
                },
                onFailure = {
                    NativeDiscoveryPayloadParser.invocationFailureResult(
                        NativeDiscoverySource.NDK_ADVERTISED,
                    )
                },
            )
    }
}
