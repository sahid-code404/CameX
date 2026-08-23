package com.sahidcode404.camex.core.camera.diagnostics

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/** Monotonic startup milestones. These values are latency evidence, never wall-clock timestamps. */
@Serializable
enum class CameraStartupMilestone {
    APP_START,
    CACHE_READ_START,
    CACHE_READY,
    UI_LENS_LIST_READY,
    PRIMARY_ROUTE_READY,
    CAMERA_OPEN_REQUESTED,
    CAMERA_OPENED,
    SESSION_CONFIGURED,
    FIRST_PREVIEW_FRAME,
    JAVA_SCAN_START,
    JAVA_SCAN_COMPLETE,
    NDK_SCAN_START,
    NDK_SCAN_COMPLETE,
    DEEP_SCAN_START,
    DEEP_SCAN_COMPLETE,
    TOPOLOGY_PERSISTED,
}

@Serializable
data class CameraStartupTraceSnapshot(
    /** Nanoseconds relative to APP_START. A missing key means the event has not happened. */
    val offsetsNs: Map<CameraStartupMilestone, Long> = emptyMap(),
) {
    fun elapsedMillis(
        from: CameraStartupMilestone,
        to: CameraStartupMilestone,
    ): Double? {
        val start = offsetsNs[from] ?: return null
        val end = offsetsNs[to] ?: return null
        if (end < start) return null
        return (end - start) / 1_000_000.0
    }

    val cacheToLensesMs: Double?
        get() = elapsedMillis(
            CameraStartupMilestone.CACHE_READ_START,
            CameraStartupMilestone.UI_LENS_LIST_READY,
        )
    val appToCameraRequestMs: Double?
        get() = elapsedMillis(
            CameraStartupMilestone.APP_START,
            CameraStartupMilestone.CAMERA_OPEN_REQUESTED,
        )
    val appToFirstPreviewFrameMs: Double?
        get() = elapsedMillis(
            CameraStartupMilestone.APP_START,
            CameraStartupMilestone.FIRST_PREVIEW_FRAME,
        )
    val advertisedJavaScanMs: Double?
        get() = elapsedMillis(
            CameraStartupMilestone.JAVA_SCAN_START,
            CameraStartupMilestone.JAVA_SCAN_COMPLETE,
        )
    val advertisedNdkScanMs: Double?
        get() = elapsedMillis(
            CameraStartupMilestone.NDK_SCAN_START,
            CameraStartupMilestone.NDK_SCAN_COMPLETE,
        )
    /** Full advertised reconciliation window across the parallel Java and NDK backends. */
    val advertisedScanMs: Double?
        get() {
            val starts = listOfNotNull(
                offsetsNs[CameraStartupMilestone.JAVA_SCAN_START],
                offsetsNs[CameraStartupMilestone.NDK_SCAN_START],
            )
            val completions = listOfNotNull(
                offsetsNs[CameraStartupMilestone.JAVA_SCAN_COMPLETE],
                offsetsNs[CameraStartupMilestone.NDK_SCAN_COMPLETE],
            )
            if (starts.size != 2 || completions.size != 2) return null
            val start = starts.min()
            val end = completions.max()
            if (end < start) return null
            return (end - start) / 1_000_000.0
        }
    val deepAuxScanMs: Double?
        get() = elapsedMillis(
            CameraStartupMilestone.DEEP_SCAN_START,
            CameraStartupMilestone.DEEP_SCAN_COMPLETE,
        )
}

fun interface MonotonicNanoClock {
    fun nowNanos(): Long
}

/** Lightweight, process-local trace shared by discovery and the single session controller. */
class CameraStartupTrace(
    private val clock: MonotonicNanoClock = MonotonicNanoClock(SystemClock::elapsedRealtimeNanos),
) {
    private val originNs = clock.nowNanos()
    private val marks = linkedMapOf(CameraStartupMilestone.APP_START to 0L)
    private val mutableSnapshot = MutableStateFlow(CameraStartupTraceSnapshot(marks.toMap()))

    val snapshot: StateFlow<CameraStartupTraceSnapshot> = mutableSnapshot.asStateFlow()

    /** Records each milestone once so background reconciliation cannot rewrite startup history. */
    fun mark(milestone: CameraStartupMilestone) {
        synchronized(marks) {
            if (milestone in marks) return
            marks[milestone] = (clock.nowNanos() - originNs).coerceAtLeast(0L)
            mutableSnapshot.value = CameraStartupTraceSnapshot(marks.toMap())
        }
    }

    fun current(): CameraStartupTraceSnapshot = mutableSnapshot.value
}
