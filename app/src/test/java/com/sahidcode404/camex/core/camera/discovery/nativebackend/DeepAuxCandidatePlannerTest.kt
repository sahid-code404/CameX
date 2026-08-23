package com.sahidcode404.camex.core.camera.discovery.nativebackend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepAuxCandidatePlannerTest {
    @Test
    fun prioritizesLearnedAdvertisedNeighborsThenLowNamespace() {
        val result = DeepAuxCandidatePlanner.plan(
            DeepAuxDiscoveryRequest(
                advertisedCameraIds = listOf("front", "1", "0"),
                cachedSuccessfulCameraIds = listOf("rear_vendor", "20"),
                previouslySuccessfulDeepCameraIds = listOf("61"),
                limits = DeepAuxDiscoveryLimits(
                    lowNumericNamespaceMax = 3,
                    neighborRadius = 1,
                    maximumNumericId = 1024,
                    maximumCandidateCount = 20,
                ),
            ),
        )

        assertEquals(
            listOf("61", "20", "rear_vendor", "0", "1", "2", "19", "21", "60", "62", "3"),
            result,
        )
        assertFalse("front" in result)
    }

    @Test
    fun candidateCountAndGeneratedNumericNamespaceAreHardBounded() {
        val result = DeepAuxCandidatePlanner.plan(
            DeepAuxDiscoveryRequest(
                advertisedCameraIds = (0..5_000).map(Int::toString),
                limits = DeepAuxDiscoveryLimits(
                    lowNumericNamespaceMax = Int.MAX_VALUE,
                    neighborRadius = Int.MAX_VALUE,
                    maximumNumericId = Int.MAX_VALUE,
                    maximumCandidateCount = Int.MAX_VALUE,
                ),
            ),
        )

        assertEquals(DeepAuxCandidatePlanner.HARD_MAXIMUM_CANDIDATE_COUNT, result.size)
        assertTrue(result.mapNotNull(String::toIntOrNull).all { it in 0..1024 })
    }

    @Test
    fun malformedExactIdsAreExcludedWithoutChangingOpaqueValidIds() {
        val result = DeepAuxCandidatePlanner.plan(
            DeepAuxDiscoveryRequest(
                cachedSuccessfulCameraIds = listOf("", "  ", "bad\u0000id", "vendor/rear-A"),
                limits = DeepAuxDiscoveryLimits(
                    lowNumericNamespaceMax = 0,
                    neighborRadius = 0,
                    maximumCandidateCount = 8,
                ),
            ),
        )

        assertEquals(listOf("vendor/rear-A", "0"), result)
    }

    @Test
    fun unorderedInputsStillProduceDeterministicPlan() {
        val first = DeepAuxCandidatePlanner.plan(
            DeepAuxDiscoveryRequest(
                advertisedCameraIds = hashSetOf("20", "1", "0"),
                cachedSuccessfulCameraIds = hashSetOf("vendor-b", "vendor-a"),
            ),
        )
        val second = DeepAuxCandidatePlanner.plan(
            DeepAuxDiscoveryRequest(
                advertisedCameraIds = linkedSetOf("0", "20", "1"),
                cachedSuccessfulCameraIds = linkedSetOf("vendor-a", "vendor-b"),
            ),
        )

        assertEquals(first, second)
    }
}
