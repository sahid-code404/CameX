package com.sahidcode404.camex.core.camera

import com.sahidcode404.camex.core.model.Size2D
import com.sahidcode404.camex.core.model.StreamConfiguration
import com.sahidcode404.camex.core.model.StreamFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProbePreviewStreamSelectorTest {
    @Test
    fun yuvOnlyMetadataDoesNotQualifyForTexturePreview() {
        val selected = ProbePreviewStreamSelector.select(
            streams = listOf(
                StreamConfiguration(StreamFormat.YUV_420_888, Size2D(1280, 720)),
            ),
            policy = CameraOperationPolicy(),
        )

        assertNull(selected)
    }

    @Test
    fun selectsNonMaximumPrivateStreamUsedByTextureView() {
        val selected = ProbePreviewStreamSelector.select(
            streams = listOf(
                StreamConfiguration(
                    StreamFormat.PRIVATE,
                    Size2D(4000, 3000),
                    maximumResolution = true,
                ),
                StreamConfiguration(StreamFormat.PRIVATE, Size2D(1920, 1080)),
                StreamConfiguration(StreamFormat.PRIVATE, Size2D(1280, 720)),
            ),
            policy = CameraOperationPolicy(),
        )

        assertEquals(Size2D(1280, 720), selected?.size)
    }
}
