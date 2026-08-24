package com.sahidcode404.camex.core.camera.raw

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-local capture-mode gate.
 *
 * Normal camera operation is always preview-only. RAW output surfaces are admitted into a Camera2
 * session only while one shutter transaction explicitly requests them. The gate carries no camera
 * identity and does not own a CameraDevice/session; it only keeps RAW from becoming a permanent
 * preview dependency on devices whose HALs cannot sustain preview + RAW continuously.
 */
object RawSessionMode {
    private val requested = AtomicBoolean(false)

    fun request() {
        requested.set(true)
    }

    fun clear() {
        requested.set(false)
    }

    fun isRequested(): Boolean = requested.get()
}
