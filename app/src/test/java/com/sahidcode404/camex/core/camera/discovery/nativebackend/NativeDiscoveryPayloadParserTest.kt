package com.sahidcode404.camex.core.camera.discovery.nativebackend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeDiscoveryPayloadParserTest {
    @Test
    fun preservesIndependentRawAndProcessedOnlyEvidence() {
        val result = NativeDiscoveryPayloadParser.parse(
            payload = """
                {
                  "schemaVersion": 1,
                  "source": "NDK_DEEP",
                  "durationMs": 7,
                  "advertisedCount": 0,
                  "requestedCount": 2,
                  "attemptedCount": 2,
                  "validCount": 2,
                  "failureCount": 0,
                  "skippedCount": 0,
                  "advertisedIds": [],
                  "requestedIds": ["4", "vendor_aux"],
                  "attemptedIds": ["4", "vendor_aux"],
                  "cameras": [
                    {
                      "id": "4",
                      "facing": 1,
                      "focalLengthsMm": [4.5],
                      "sensorWidthMm": 5.6,
                      "sensorHeightMm": 4.2,
                      "activeArray": {"left": 0, "top": 0, "right": 4000, "bottom": 3000},
                      "pixelWidth": 4032,
                      "pixelHeight": 3024,
                      "hardwareLevel": 0,
                      "rawCapabilityAdvertised": false,
                      "rawFormats": ["RAW12"],
                      "rawSizes": [{"width": 4000, "height": 3000}],
                      "privatePreviewStreamDeclared": true,
                      "privatePreviewSizes": [{"width": 1920, "height": 1080}],
                      "yuvPreviewStreamDeclared": false,
                      "reportedCapabilities": ["BACKWARD_COMPATIBLE"],
                      "colorFilterArrangement": 0,
                      "physicalCameraIds": []
                    },
                    {
                      "id": "vendor_aux",
                      "facing": null,
                      "focalLengthsMm": [],
                      "rawCapabilityAdvertised": null,
                      "rawFormats": [],
                      "rawSizes": [],
                      "privatePreviewStreamDeclared": false,
                      "privatePreviewSizes": [],
                      "yuvPreviewStreamDeclared": true,
                      "yuvPreviewSizes": [{"width": 640, "height": 480}],
                      "reportedCapabilities": null,
                      "physicalCameraIds": []
                    }
                  ],
                  "failures": []
                }
            """.trimIndent(),
            expectedSource = NativeDiscoverySource.NDK_DEEP,
        )

        assertEquals(2, result.cameras.size)
        val raw = result.cameras.first()
        assertEquals(false, raw.rawCapabilityAdvertised)
        assertTrue(raw.rawStreamActuallyDeclared)
        assertEquals(setOf(NativeRawStreamFormat.RAW12), raw.rawFormats)
        assertEquals(listOf(NativeSize(4000, 3000)), raw.rawSizes)
        assertEquals(listOf(NativeSize(1920, 1080)), raw.privatePreviewSizes)
        val processedOnly = result.cameras.last()
        assertNull(processedOnly.rawCapabilityAdvertised)
        assertFalse(processedOnly.rawStreamActuallyDeclared)
        assertTrue(processedOnly.previewStreamActuallyDeclared)
        assertEquals(listOf(NativeSize(640, 480)), processedOnly.yuvPreviewSizes)
    }

    @Test
    fun malformedPayloadBecomesStableFailureWithoutLeakingParserText() {
        val result = NativeDiscoveryPayloadParser.parse(
            payload = "not-json: device-specific exception detail",
            expectedSource = NativeDiscoverySource.NDK_ADVERTISED,
        )

        assertTrue(result.cameras.isEmpty())
        assertEquals(1, result.failures.size)
        assertEquals(
            NativeDiscoveryFailureReason.MALFORMED_NATIVE_RESPONSE,
            result.failures.single().reason,
        )
        assertNull(result.failures.single().cameraId)
    }

    @Test
    fun unknownNativeFailureStringsAreSanitized() {
        val result = NativeDiscoveryPayloadParser.parse(
            payload = """
                {
                  "schemaVersion": 1,
                  "source": "NDK_ADVERTISED",
                  "failureCount": 1,
                  "failures": [{
                    "cameraId": "0",
                    "stage": "vendor-secret-stage",
                    "reason": "vendor-secret-reason",
                    "statusCode": -12345
                  }]
                }
            """.trimIndent(),
            expectedSource = NativeDiscoverySource.NDK_ADVERTISED,
        )

        val failure = result.failures.single()
        assertEquals(NativeDiscoveryFailureStage.READ_CHARACTERISTICS, failure.stage)
        assertEquals(NativeDiscoveryFailureReason.UNKNOWN_NATIVE_STATUS, failure.reason)
        assertEquals(-12345, failure.statusCode)
    }
}
