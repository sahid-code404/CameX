package com.sahidcode404.camex.core.camera.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraStartupTraceTest {
    @Test
    fun recordsMonotonicOffsetsOnceAndDerivesLatency() {
        var now = 1_000_000_000L
        val trace = CameraStartupTrace { now }

        now += 2_000_000L
        trace.mark(CameraStartupMilestone.CACHE_READ_START)
        now += 8_000_000L
        trace.mark(CameraStartupMilestone.UI_LENS_LIST_READY)
        now += 50_000_000L
        trace.mark(CameraStartupMilestone.FIRST_PREVIEW_FRAME)
        now += 100_000_000L
        trace.mark(CameraStartupMilestone.FIRST_PREVIEW_FRAME)
        now += 10_000_000L
        trace.mark(CameraStartupMilestone.JAVA_SCAN_START)
        trace.mark(CameraStartupMilestone.NDK_SCAN_START)
        now += 30_000_000L
        trace.mark(CameraStartupMilestone.JAVA_SCAN_COMPLETE)
        now += 20_000_000L
        trace.mark(CameraStartupMilestone.NDK_SCAN_COMPLETE)

        val result = trace.current()
        assertEquals(8.0, result.cacheToLensesMs!!, 0.0001)
        assertEquals(60.0, result.appToFirstPreviewFrameMs!!, 0.0001)
        assertEquals(50.0, result.advertisedScanMs!!, 0.0001)
        assertNull(result.deepAuxScanMs)
    }
}
