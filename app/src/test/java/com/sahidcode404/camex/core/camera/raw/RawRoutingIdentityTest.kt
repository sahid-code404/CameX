package com.sahidcode404.camex.core.camera.raw

import com.sahidcode404.camex.core.model.LensIdentity
import org.junit.Assert.assertEquals
import org.junit.Test

class RawRoutingIdentityTest {
    @Test
    fun directRawRouteUsesCanonicalSessionRoutingKey() {
        val expected = LensIdentity(publicCameraId = "0").routingKey

        assertEquals(expected, rawSessionRoutingKey("0", null))
    }

    @Test
    fun physicalRawRouteUsesCanonicalSessionRoutingKey() {
        val expected = LensIdentity(
            publicCameraId = "61",
            physicalCameraId = "20",
        ).routingKey

        assertEquals(expected, rawSessionRoutingKey("61", "20"))
    }

    @Test
    fun blankPhysicalIdNormalizesToDirectRoute() {
        val expected = LensIdentity(publicCameraId = "100").routingKey

        assertEquals(expected, rawSessionRoutingKey("100", ""))
    }
}
