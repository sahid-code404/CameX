package com.sahidcode404.camex.core.camera.raw

import java.util.LinkedHashMap

/**
 * Exact SENSOR_TIMESTAMP matcher. Callback order is irrelevant: either image or capture metadata may
 * arrive first. Pending state is bounded and discarded images are always closed by the caller hook.
 */
class RawFramePairer<I, R>(
    private val maxPending: Int = 4,
    private val onDiscardImage: (I) -> Unit,
) {
    init {
        require(maxPending > 0) { "maxPending must be positive" }
    }

    data class Pair<I, R>(
        val timestampNs: Long,
        val image: I,
        val result: R,
    )

    private val images = LinkedHashMap<Long, I>()
    private val results = LinkedHashMap<Long, R>()

    @Synchronized
    fun offerImage(timestampNs: Long, image: I): Pair<I, R>? {
        if (timestampNs <= 0L) {
            onDiscardImage(image)
            return null
        }
        val result = results.remove(timestampNs)
        if (result != null) return Pair(timestampNs, image, result)

        images.put(timestampNs, image)?.let(onDiscardImage)
        trimImages()
        trimResults()
        return null
    }

    @Synchronized
    fun offerResult(timestampNs: Long, result: R): Pair<I, R>? {
        if (timestampNs <= 0L) return null
        val image = images.remove(timestampNs)
        if (image != null) return Pair(timestampNs, image, result)

        results[timestampNs] = result
        trimImages()
        trimResults()
        return null
    }

    @Synchronized
    fun clear() {
        images.values.forEach(onDiscardImage)
        images.clear()
        results.clear()
    }

    @Synchronized
    fun pendingImageCount(): Int = images.size

    @Synchronized
    fun pendingResultCount(): Int = results.size

    private fun trimImages() {
        while (images.size > maxPending) {
            val first = images.entries.firstOrNull() ?: return
            images.remove(first.key)?.let(onDiscardImage)
        }
    }

    private fun trimResults() {
        while (results.size > maxPending) {
            val first = results.entries.firstOrNull() ?: return
            results.remove(first.key)
        }
    }
}

/** Selection/transport snapshot validation for late Camera2 callbacks. */
object RawCaptureGenerationGuard {
    fun isCurrent(
        requestedSelectionGeneration: Long,
        activeSelectionGeneration: Long?,
        capturedTransportGeneration: Long,
        activeTransportGeneration: Long,
        capturedRoutingKey: String,
        activeRoutingKey: String?,
    ): Boolean = requestedSelectionGeneration > 0L &&
        activeSelectionGeneration == requestedSelectionGeneration &&
        capturedTransportGeneration == activeTransportGeneration &&
        activeRoutingKey == capturedRoutingKey
}
