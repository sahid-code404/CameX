package com.sahidcode404.camex.core.camera.raw

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.display.DisplayManager
import android.media.Image
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Display
import android.view.Surface
import java.io.FilterOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RawDngWriter(context: Context) {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver
    private val displayManager: DisplayManager = appContext.getSystemService(DisplayManager::class.java)

    fun write(
        characteristics: CameraCharacteristics,
        result: TotalCaptureResult,
        image: Image,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): RawSavedFile {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw UnsupportedOperationException(
                "Public DNG saving without broad storage permission requires Android 10 or newer",
            )
        }

        val formatter = requireNotNull(FILE_TIME_FORMAT.get())
        val displayName = "IMG_${formatter.format(Date(nowEpochMs))}.dng"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, DNG_MIME_TYPE)
            put(MediaStore.MediaColumns.RELATIVE_PATH, CAMERA_ROLL_RELATIVE_PATH)
            put(MediaStore.Images.ImageColumns.DATE_TAKEN, nowEpochMs)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val orientationDegrees = resolveOrientationDegrees(characteristics)
        var byteCount = 0L
        val transaction = RawSaveTransaction<Uri>(
            create = {
                resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            },
            write = { uri ->
                resolver.openOutputStream(uri, "w")?.use { stream ->
                    CountingOutputStream(stream).use { counted ->
                        DngCreator(characteristics, result).use { creator ->
                            orientationDegrees?.let { rotation ->
                                creator.setOrientation(
                                    RawOrientation.exifOrientationForClockwiseRotation(rotation),
                                )
                            }
                            creator.writeImage(counted, image)
                        }
                        byteCount = counted.count
                        check(byteCount > 0L) { "DNG output was empty" }
                    }
                } ?: error("Could not open MediaStore DNG output")
            },
            commit = { uri ->
                val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                check(resolver.update(uri, published, null, null) == 1) {
                    "Could not publish MediaStore DNG"
                }
            },
            delete = { uri -> resolver.delete(uri, null, null) },
        )
        val uri = transaction.execute().getOrThrow()
        return RawSavedFile(
            uri = uri.toString(),
            bytes = byteCount,
            width = image.width,
            height = image.height,
        )
    }

    /**
     * Uses only active-camera metadata and the current Android display rotation. If orientation
     * metadata is incomplete or non-orthogonal, DngCreator's default is retained rather than
     * guessing a device-specific correction.
     */
    private fun resolveOrientationDegrees(characteristics: CameraCharacteristics): Int? {
        val sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
            ?.takeIf { Math.floorMod(it, 90) == 0 }
            ?: return null
        val facing = characteristics.get(CameraCharacteristics.LENS_FACING) ?: return null
        val displayRotation = displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
            ?: Surface.ROTATION_0
        val displayDegrees = when (displayRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return RawOrientation.requiredClockwiseRotationDegrees(
            sensorOrientationDegrees = sensor,
            displayRotationDegrees = displayDegrees,
            frontFacing = facing == CameraCharacteristics.LENS_FACING_FRONT,
        )
    }

    private class CountingOutputStream(output: OutputStream) : FilterOutputStream(output) {
        var count: Long = 0L
            private set

        override fun write(value: Int) {
            out.write(value)
            count += 1L
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            out.write(buffer, offset, length)
            count += length.toLong()
        }
    }

    private companion object {
        const val DNG_MIME_TYPE = "image/x-adobe-dng"
        val CAMERA_ROLL_RELATIVE_PATH = Environment.DIRECTORY_DCIM + "/Camera/"
        val FILE_TIME_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat =
                SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
        }
    }
}
