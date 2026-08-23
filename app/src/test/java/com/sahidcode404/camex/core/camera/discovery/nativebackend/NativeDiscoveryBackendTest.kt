package com.sahidcode404.camex.core.camera.discovery.nativebackend

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeDiscoveryBackendTest {
    @Test
    fun unavailableDeepBackendReturnsPlanAndStableFailure() = runTest {
        val backend = DeepAuxDiscoveryBackend(
            transport = FakeTransport(isLoaded = false),
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = backend.discover(
            DeepAuxDiscoveryRequest(
                cachedSuccessfulCameraIds = listOf("vendor_aux"),
                limits = DeepAuxDiscoveryLimits(
                    lowNumericNamespaceMax = 1,
                    neighborRadius = 0,
                    maximumCandidateCount = 4,
                ),
            ),
        )

        assertEquals(listOf("vendor_aux", "0", "1"), result.requestedCameraIds)
        assertEquals(
            NativeDiscoveryFailureReason.NATIVE_API_UNAVAILABLE,
            result.failures.single().reason,
        )
    }

    @Test
    fun deepBackendPassesOnlyBoundedPlannerOutputToOneTransportCall() = runTest {
        val transport = FakeTransport(isLoaded = true)
        val backend = DeepAuxDiscoveryBackend(
            transport = transport,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = backend.discover(
            DeepAuxDiscoveryRequest(
                advertisedCameraIds = (0..500).map(Int::toString),
                limits = DeepAuxDiscoveryLimits(maximumCandidateCount = 5),
            ),
        )

        assertEquals(1, transport.candidateCalls)
        assertEquals(5, transport.lastCandidates.size)
        assertTrue(result.failures.isEmpty())
    }

    private class FakeTransport(
        override val isLoaded: Boolean,
    ) : NativeDiscoveryTransport {
        var candidateCalls = 0
        var lastCandidates: List<String> = emptyList()

        override fun discoverAdvertisedJson(): String = emptyPayload("NDK_ADVERTISED")

        override fun discoverCandidatesJson(cameraIds: Array<String>): String {
            ++candidateCalls
            lastCandidates = cameraIds.toList()
            return """
                {
                  "schemaVersion": 1,
                  "source": "NDK_DEEP",
                  "requestedCount": ${cameraIds.size},
                  "requestedIds": [${cameraIds.joinToString { "\"$it\"" }}],
                  "attemptedIds": [],
                  "cameras": [],
                  "failures": []
                }
            """.trimIndent()
        }

        private fun emptyPayload(source: String): String =
            """{"schemaVersion":1,"source":"$source"}"""
    }
}
