package com.sahidcode404.camex.core.camera

import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.view.Surface
import com.sahidcode404.camex.core.model.Size2D
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bounded YUV_420_888 repeating stream used when the user explicitly selects the Camera2 YUV
 * viewfinder stream. PRIVATE/SurfaceTexture remains the display transport; this stream represents
 * the real YUV producer path that future native/GPU processing can consume without changing camera
 * ownership again.
 *
 * Until a renderer/processor is attached, acquireLatestImage()+close drains the producer on the
 * existing camera callback thread. maxImages=2 bounds memory and prevents HAL back-pressure.
 */
internal class DrainingYuvViewfinderStream private constructor(
    private val reader: ImageReader,
) : Closeable {
    private val closed = AtomicBoolean(false)

    val surface: Surface
        get() = reader.surface

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { reader.setOnImageAvailableListener(null, null) }
        drain(reader)
        runCatching { reader.close() }
    }

    companion object {
        fun create(size: Size2D, handler: Handler): DrainingYuvViewfinderStream {
            require(size.isValid) { "YUV viewfinder size must be valid" }
            val reader = ImageReader.newInstance(
                size.width,
                size.height,
                ImageFormat.YUV_420_888,
                MAX_IMAGES,
            )
            reader.setOnImageAvailableListener({ source -> drain(source) }, handler)
            return DrainingYuvViewfinderStream(reader)
        }

        private fun drain(reader: ImageReader) {
            while (true) {
                val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
                runCatching { image.close() }
            }
        }

        private const val MAX_IMAGES = 2
    }
}
