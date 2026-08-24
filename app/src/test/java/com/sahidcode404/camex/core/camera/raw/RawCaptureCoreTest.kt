package com.sahidcode404.camex.core.camera.raw

import com.sahidcode404.camex.core.model.CapabilityFlags
import com.sahidcode404.camex.core.model.CapabilitySupport
import com.sahidcode404.camex.core.model.LensCapabilities
import com.sahidcode404.camex.core.model.LensDescriptor
import com.sahidcode404.camex.core.model.LensIdentity
import com.sahidcode404.camex.core.model.Size2D
import com.sahidcode404.camex.core.model.StreamConfiguration
import com.sahidcode404.camex.core.model.StreamFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawCaptureCoreTest {
    @Test
    fun rawSessionModeIsPreviewOnlyByDefaultAndExplicitlyBounded() {
        RawSessionMode.clear()
        assertFalse(RawSessionMode.isRequested())

        RawSessionMode.request()
        assertTrue(RawSessionMode.isRequested())

        RawSessionMode.clear()
        assertFalse(RawSessionMode.isRequested())
    }

    @Test
    fun rawSupportedSelectsLargestStandardRawSensorSize() {
        val lens = lens(
            raw = CapabilitySupport.SUPPORTED,
            configurations = listOf(
                raw(2000, 1500),
                raw(4000, 3000),
                raw(8000, 6000, maximumResolution = true),
                StreamConfiguration(StreamFormat.JPEG, Size2D(9000, 7000)),
            ),
        )

        val info = RawCapabilityResolver.resolve(lens)

        assertEquals(RawSupportState.SUPPORTED, info.support)
        assertEquals(Size2D(4000, 3000), info.selectedSize)
        assertEquals(listOf(Size2D(4000, 3000), Size2D(2000, 1500)), info.availableSizes)
        assertTrue(info.canAttempt)
    }

    @Test
    fun rawUnsupportedWhenCapabilityAndSizesAreAbsent() {
        val info = RawCapabilityResolver.resolve(
            lens(raw = CapabilitySupport.UNSUPPORTED, configurations = emptyList()),
        )

        assertEquals(RawSupportState.UNSUPPORTED, info.support)
        assertNull(info.selectedSize)
        assertFalse(info.canAttempt)
    }

    @Test
    fun rawCapabilityWithNoRawSizesRemainsUnknown() {
        val info = RawCapabilityResolver.resolve(
            lens(raw = CapabilitySupport.SUPPORTED, configurations = emptyList()),
        )

        assertEquals(RawSupportState.UNKNOWN, info.support)
        assertNull(info.selectedSize)
        assertNotNull(info.detail)
    }

    @Test
    fun unknownCapabilityWithRawSizeRemainsUnknownButCanBeAttempted() {
        val info = RawCapabilityResolver.resolve(
            lens(raw = CapabilitySupport.UNKNOWN, configurations = listOf(raw(3000, 2000))),
        )

        assertEquals(RawSupportState.UNKNOWN, info.support)
        assertEquals(Size2D(3000, 2000), info.selectedSize)
        assertTrue(info.canAttempt)
    }

    @Test
    fun resultFirstPairsOnlyExactTimestamp() {
        val discarded = mutableListOf<String>()
        val pairer = RawFramePairer<String, String>(onDiscardImage = discarded::add)

        assertNull(pairer.offerResult(123L, "result"))
        val pair = pairer.offerImage(123L, "image")

        assertEquals(123L, pair?.timestampNs)
        assertEquals("image", pair?.image)
        assertEquals("result", pair?.result)
        assertTrue(discarded.isEmpty())
    }

    @Test
    fun imageFirstPairsOnlyExactTimestamp() {
        val discarded = mutableListOf<String>()
        val pairer = RawFramePairer<String, String>(onDiscardImage = discarded::add)

        assertNull(pairer.offerImage(456L, "image"))
        val pair = pairer.offerResult(456L, "result")

        assertEquals("image", pair?.image)
        assertEquals("result", pair?.result)
        assertTrue(discarded.isEmpty())
    }

    @Test
    fun timestampMismatchNeverPairsAndCleanupClosesOrphanImage() {
        val discarded = mutableListOf<String>()
        val pairer = RawFramePairer<String, String>(onDiscardImage = discarded::add)

        assertNull(pairer.offerImage(10L, "orphan"))
        assertNull(pairer.offerResult(11L, "other-frame"))
        assertEquals(1, pairer.pendingImageCount())
        assertEquals(1, pairer.pendingResultCount())

        pairer.clear()

        assertEquals(listOf("orphan"), discarded)
        assertEquals(0, pairer.pendingImageCount())
        assertEquals(0, pairer.pendingResultCount())
    }

    @Test
    fun pendingImagesAreBoundedAndOldestIsDiscarded() {
        val discarded = mutableListOf<String>()
        val pairer = RawFramePairer<String, String>(maxPending = 2, onDiscardImage = discarded::add)

        pairer.offerImage(1L, "one")
        pairer.offerImage(2L, "two")
        pairer.offerImage(3L, "three")

        assertEquals(listOf("one"), discarded)
        assertEquals(2, pairer.pendingImageCount())
        pairer.clear()
        assertEquals(listOf("one", "two", "three"), discarded)
    }

    @Test
    fun staleSelectionGenerationIsRejected() {
        assertFalse(
            RawCaptureGenerationGuard.isCurrent(
                requestedSelectionGeneration = 7L,
                activeSelectionGeneration = 8L,
                capturedTransportGeneration = 4L,
                activeTransportGeneration = 4L,
                capturedRoutingKey = "profile-A",
                activeRoutingKey = "profile-A",
            ),
        )
        assertFalse(
            RawCaptureGenerationGuard.isCurrent(
                requestedSelectionGeneration = 7L,
                activeSelectionGeneration = 7L,
                capturedTransportGeneration = 4L,
                activeTransportGeneration = 5L,
                capturedRoutingKey = "profile-A",
                activeRoutingKey = "profile-A",
            ),
        )
        assertTrue(
            RawCaptureGenerationGuard.isCurrent(
                requestedSelectionGeneration = 7L,
                activeSelectionGeneration = 7L,
                capturedTransportGeneration = 4L,
                activeTransportGeneration = 4L,
                capturedRoutingKey = "profile-A",
                activeRoutingKey = "profile-A",
            ),
        )
    }

    @Test
    fun duplicateCaptureIsRejectedUntilPreviousCaptureEnds() {
        val gate = RawCaptureGate()

        assertTrue(gate.tryBegin())
        assertFalse(gate.tryBegin())
        assertTrue(gate.isActive())
        gate.end()
        assertFalse(gate.isActive())
        assertTrue(gate.tryBegin())
    }

    @Test
    fun dngWriteFailureDeletesIncompleteDestination() {
        val events = mutableListOf<String>()
        val transaction = RawSaveTransaction(
            create = { "uri" },
            write = { events += "write"; error("dng failed") },
            commit = { events += "commit" },
            delete = { events += "delete:$it" },
        )

        assertTrue(transaction.execute().isFailure)
        assertEquals(listOf("write", "delete:uri"), events)
    }

    @Test
    fun mediaStoreCommitFailureDeletesIncompleteDestination() {
        val events = mutableListOf<String>()
        val transaction = RawSaveTransaction(
            create = { "uri" },
            write = { events += "write" },
            commit = { events += "commit"; error("publish failed") },
            delete = { events += "delete:$it" },
        )

        assertTrue(transaction.execute().isFailure)
        assertEquals(listOf("write", "commit", "delete:uri"), events)
    }

    private fun lens(
        raw: CapabilitySupport,
        configurations: List<StreamConfiguration>,
    ) = LensDescriptor(
        identity = LensIdentity("0"),
        capabilities = LensCapabilities(
            flags = CapabilityFlags(raw = raw),
            streamConfigurations = configurations,
        ),
    )

    private fun raw(
        width: Int,
        height: Int,
        maximumResolution: Boolean = false,
    ) = StreamConfiguration(
        format = StreamFormat.RAW_SENSOR,
        size = Size2D(width, height),
        maximumResolution = maximumResolution,
    )
}
