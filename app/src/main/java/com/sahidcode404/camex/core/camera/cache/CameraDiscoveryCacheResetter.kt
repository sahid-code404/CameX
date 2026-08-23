package com.sahidcode404.camex.core.camera.cache

/** Reset scope is intentionally narrow: topology and route trust, never user lens preferences. */
class CameraDiscoveryCacheResetter(
    private val topologyStore: CameraTopologyStore,
    private val trustStore: CameraTrustStore,
) {
    suspend fun clearDiscoveryState() {
        topologyStore.clear()
        trustStore.clear()
    }
}
