package com.sahidcode404.camex.core.camera.discovery.nativebackend

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Explicit Level-2 discovery backend. Calling this class is always a deliberate background action;
 * it does not own startup policy and cannot open or validate a camera session.
 */
class DeepAuxDiscoveryBackend internal constructor(
    private val transport: NativeDiscoveryTransport,
    private val dispatcher: CoroutineDispatcher,
) {
    constructor() : this(JniNativeDiscoveryTransport, Dispatchers.IO)

    suspend fun discover(request: DeepAuxDiscoveryRequest): NativeDiscoveryResult {
        val candidates = DeepAuxCandidatePlanner.plan(request)
        if (candidates.isEmpty()) {
            return NativeDiscoveryResult(
                source = NativeDiscoverySource.NDK_DEEP,
                counts = NativeDiscoveryCounts(),
            )
        }
        return withContext(dispatcher) {
            if (!transport.isLoaded) {
                return@withContext NativeDiscoveryPayloadParser.unavailableResult(
                    source = NativeDiscoverySource.NDK_DEEP,
                    requestedIds = candidates,
                )
            }
            runCatching { transport.discoverCandidatesJson(candidates.toTypedArray()) }
                .fold(
                    onSuccess = { payload ->
                        NativeDiscoveryPayloadParser.parse(
                            payload = payload,
                            expectedSource = NativeDiscoverySource.NDK_DEEP,
                            fallbackRequestedIds = candidates,
                        )
                    },
                    onFailure = {
                        NativeDiscoveryPayloadParser.invocationFailureResult(
                            source = NativeDiscoverySource.NDK_DEEP,
                            requestedIds = candidates,
                        )
                    },
                )
        }
    }
}
