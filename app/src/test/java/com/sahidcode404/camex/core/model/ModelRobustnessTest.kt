package com.sahidcode404.camex.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRobustnessTest {
    @Test
    fun physicalIdentityRoutesThroughLogicalParent() {
        val identity = LensIdentity(
            publicCameraId = "public-route",
            physicalCameraId = "physical-target",
            logicalParentCameraId = "logical-parent",
        )

        assertEquals("logical-parent", identity.openCameraId)
        assertEquals("physical-target", identity.streamPhysicalCameraId)
        assertTrue(identity.routingKey.contains("logical-parent"))
        assertTrue(identity.routingKey.contains("physical-target"))
    }

    @Test
    fun nullableAndInvalidMetadataRemainRepresentable() {
        val capabilities = LensCapabilities(
            sensorPhysicalSize = PhysicalSize(Double.NaN, -1.0),
            pixelArraySize = Size2D(-1, 0),
            activeArray = SensorRect(10, 10, 5, 5),
            streamConfigurations = listOf(
                StreamConfiguration(StreamFormat.RAW_SENSOR, Size2D(0, 12)),
            ),
        )

        assertFalse(capabilities.sensorPhysicalSize!!.isValid)
        assertFalse(capabilities.pixelArraySize!!.isValid)
        assertFalse(capabilities.activeArray!!.isValid)
        assertTrue(capabilities.rawResolutions.isEmpty())
        assertNull(capabilities.maximumRawSize)
    }

    @Test
    fun rawHelpersExcludeImplementationSpecificRawPrivate() {
        val capabilities = LensCapabilities(
            streamConfigurations = listOf(
                StreamConfiguration(StreamFormat.RAW_PRIVATE, Size2D(8000, 6000)),
                StreamConfiguration(StreamFormat.RAW12, Size2D(4000, 3000)),
                StreamConfiguration(StreamFormat.RAW_SENSOR, Size2D(6000, 4000)),
            ),
        )

        assertEquals(listOf(Size2D(4000, 3000), Size2D(6000, 4000)), capabilities.rawResolutions)
        assertEquals(Size2D(6000, 4000), capabilities.maximumRawSize)
        assertNull(capabilities.estimatedMaximumRawFps)
    }

    @Test
    fun estimatesMaximumRawFpsOnlyFromValidDurations() {
        val capabilities = LensCapabilities(
            streamConfigurations = listOf(
                StreamConfiguration(StreamFormat.RAW_SENSOR, Size2D(4000, 3000), 100_000_000),
                StreamConfiguration(StreamFormat.RAW12, Size2D(2000, 1500), 50_000_000),
                StreamConfiguration(StreamFormat.RAW10, Size2D(1000, 750), 0),
            ),
        )

        assertEquals(20.0, capabilities.estimatedMaximumRawFps!!, 0.0001)
    }
}
