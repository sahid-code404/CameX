package com.sahidcode404.camex.core.logic

import com.sahidcode404.camex.core.model.CameraCapability
import com.sahidcode404.camex.core.model.CapabilitySupport
import com.sahidcode404.camex.core.model.FpsRange
import com.sahidcode404.camex.core.model.HighSpeedConfiguration
import com.sahidcode404.camex.core.model.Size2D
import com.sahidcode404.camex.core.model.StreamConfiguration
import com.sahidcode404.camex.core.model.StreamFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class CapabilityMapperTest {
    @Test
    fun absentMetadataRemainsUnknown() {
        val flags = CapabilityMapper.map(CapabilityEvidence())

        assertEquals(CapabilitySupport.UNKNOWN, flags.raw)
        assertEquals(CapabilitySupport.UNKNOWN, flags.manualSensor)
        assertEquals(CapabilitySupport.UNKNOWN, flags.opticalStabilization)
        assertEquals(CapabilitySupport.UNKNOWN, flags.manualFocus)
    }

    @Test
    fun knownCapabilitySetMapsPresenceAndAbsence() {
        val flags = CapabilityMapper.map(
            CapabilityEvidence(
                reportedCapabilities = setOf(
                    CameraCapability.BACKWARD_COMPATIBLE,
                    CameraCapability.MANUAL_SENSOR,
                    CameraCapability.BURST_CAPTURE,
                ),
            ),
        )

        assertEquals(CapabilitySupport.SUPPORTED, flags.backwardCompatible)
        assertEquals(CapabilitySupport.SUPPORTED, flags.manualSensor)
        assertEquals(CapabilitySupport.SUPPORTED, flags.burst)
        assertEquals(CapabilitySupport.UNSUPPORTED, flags.depthOutput)
    }

    @Test
    fun portableRawStreamIsPositiveEvidence() {
        val flags = CapabilityMapper.map(
            CapabilityEvidence(
                reportedCapabilities = emptySet(),
                streamConfigurations = listOf(
                    StreamConfiguration(StreamFormat.RAW12, Size2D(4000, 3000)),
                ),
            ),
        )

        assertEquals(CapabilitySupport.SUPPORTED, flags.raw)
    }

    @Test
    fun rawPrivateAloneIsNotPortableRaw() {
        val flags = CapabilityMapper.map(
            CapabilityEvidence(
                reportedCapabilities = emptySet(),
                streamConfigurations = listOf(
                    StreamConfiguration(StreamFormat.RAW_PRIVATE, Size2D(4000, 3000)),
                ),
            ),
        )

        assertEquals(CapabilitySupport.UNSUPPORTED, flags.raw)
    }

    @Test
    fun contradictoryRawAdvertisementRequiresProbe() {
        val flags = CapabilityMapper.map(
            CapabilityEvidence(
                reportedCapabilities = setOf(CameraCapability.RAW),
                streamConfigurations = emptyList(),
            ),
        )

        assertEquals(CapabilitySupport.UNKNOWN, flags.raw)
    }

    @Test
    fun manualFocusRequiresPositiveDistanceAndAfOff() {
        assertEquals(
            CapabilitySupport.SUPPORTED,
            CapabilityMapper.map(
                CapabilityEvidence(
                    minimumFocusDistanceDiopters = 5.0,
                    autofocusOffAvailable = true,
                ),
            ).manualFocus,
        )
        assertEquals(
            CapabilitySupport.UNSUPPORTED,
            CapabilityMapper.map(
                CapabilityEvidence(
                    minimumFocusDistanceDiopters = 0.0,
                    autofocusOffAvailable = true,
                ),
            ).manualFocus,
        )
    }

    @Test
    fun normalizedModeAndHighSpeedEvidenceMapWithoutFrameworkConstants() {
        val flags = CapabilityMapper.map(
            CapabilityEvidence(
                opticalStabilizationAvailable = true,
                videoStabilizationAvailable = false,
                highSpeedConfigurations = listOf(
                    HighSpeedConfiguration(Size2D(1920, 1080), FpsRange(120, 120)),
                ),
            ),
        )

        assertEquals(CapabilitySupport.SUPPORTED, flags.opticalStabilization)
        assertEquals(CapabilitySupport.UNSUPPORTED, flags.videoStabilization)
        assertEquals(CapabilitySupport.SUPPORTED, flags.highSpeedVideo)
    }
}
